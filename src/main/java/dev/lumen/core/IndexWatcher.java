package dev.lumen.core;

import dev.lumen.config.Config;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the index fresh after the initial build by watching the configured roots (and the
 * Start Menu) and applying create/delete/modify events incrementally, instead of rebuilding
 * the whole tree on every change.
 */
public final class IndexWatcher {

    private final Config config;
    private final IndexStore store;
    private final Runnable onFinished;
    private final Runnable rebuildFallback;
    private final Set<String> excluded = new HashSet<>();

    private volatile boolean running = true;
    private WatchService watchService;
    private final ConcurrentHashMap<WatchKey, Path> rootsByKey = new ConcurrentHashMap<>();

    public IndexWatcher(Config config, IndexStore store, Runnable onFinished) {
        this(config, store, onFinished, null);
    }

    public IndexWatcher(Config config, IndexStore store, Runnable onFinished, Runnable rebuildFallback) {
        this.config = config;
        this.store = store;
        this.onFinished = onFinished;
        this.rebuildFallback = rebuildFallback;
        for (String name : config.excludedDirs) {
            excluded.add(name.toLowerCase(Locale.ROOT));
        }
    }

    public void start() {
        Thread.ofVirtual().name("index-watcher").start(this::run);
    }

    public void stop() {
        running = false;
        try {
            if (watchService != null) {
                watchService.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void run() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            for (Path dir : watchedRoots()) {
                registerTree(dir, config.maxDepth);
            }
            for (Path dir : Indexer.startMenuDirs()) {
                if (Files.isDirectory(dir)) {
                    registerTree(dir, 6);
                }
            }

            while (running) {
                WatchKey key = watchService.take();
                Path dir = rootsByKey.get(key);
                if (dir == null) {
                    key.reset();
                    continue;
                }

                List<WatchEvent<?>> batch = new ArrayList<>(key.pollEvents());
                long deadline = System.currentTimeMillis() + 500;
                while (System.currentTimeMillis() < deadline) {
                    WatchKey next = watchService.poll(50, TimeUnit.MILLISECONDS);
                    if (next == null) {
                        break;
                    }
                    Path nextDir = rootsByKey.get(next);
                    if (nextDir != null) {
                        batch.addAll(next.pollEvents());
                    }
                    next.reset();
                }

                boolean overflow = false;
                List<Path> created = new ArrayList<>();
                List<Path> modified = new ArrayList<>();
                List<Path> deleted = new ArrayList<>();
                for (WatchEvent<?> event : batch) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        overflow = true;
                        break;
                    }
                    Path changed = dir.resolve((Path) event.context());
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        created.add(changed);
                    } else if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                        modified.add(changed);
                    } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                        deleted.add(changed);
                    }
                }

                if (overflow) {
                    // Fall back to a full rebuild when the OS drops events.
                    if (rebuildFallback != null) {
                        rebuildFallback.run();
                    } else {
                        new Indexer(config, store, null, null).rebuild();
                    }
                    continue;
                }

                for (Path path : deleted) {
                    if (Files.isDirectory(path)) {
                        store.removeUnder(path.toString());
                    } else {
                        store.removePath(path.toString());
                    }
                }
                for (Path path : created) {
                    handlePath(path, true);
                }
                for (Path path : modified) {
                    handlePath(path, false);
                }

                store.save();
                if (onFinished != null) {
                    onFinished.run();
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.println("[index] watcher stopped: " + e.getMessage());
        }
    }

    private List<Path> watchedRoots() {
        List<Path> roots = new ArrayList<>();
        for (String root : config.roots) {
            try {
                Path dir = Path.of(root);
                if (Files.isDirectory(dir)) {
                    roots.add(dir);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return roots;
    }

    private void registerTree(Path root, int maxDepth) {
        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    maxDepth, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(root) && Indexer.isExcludedDir(dir, excluded)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            register(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            System.err.println("[index] could not watch " + root + ": " + e.getMessage());
        }
    }

    private void register(Path dir) {
        try {
            WatchKey key = dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            rootsByKey.put(key, dir);
        } catch (IOException e) {
            System.err.println("[index] could not register " + dir + ": " + e.getMessage());
        }
    }

    private void handlePath(Path path, boolean created) {
        if (Files.isDirectory(path)) {
            if (Indexer.isExcludedDir(path, excluded)) {
                return;
            }
            if (created) {
                registerTree(path, config.maxDepth);
            }
            store.upsert(IndexItem.folder(path.getFileName().toString(), path.toString(),
                    Indexer.shorten(path.getParent(), System.getProperty("user.home"))));
            return;
        }

        if (!Files.isRegularFile(path)) {
            return;
        }
        if (Indexer.isIgnoredFile(path)) {
            return;
        }

        String name = path.getFileName().toString();
        boolean underStartMenu = Indexer.startMenuDirs().stream().anyMatch(dir -> path.startsWith(dir));
        if (underStartMenu && Indexer.isAppFile(name)) {
            store.upsert(IndexItem.app(Indexer.stripExtension(name), path.toString()));
            return;
        }
        if (underStartMenu) {
            return;
        }
        store.upsert(IndexItem.file(name, path.toString(),
                Indexer.shorten(path.getParent(), System.getProperty("user.home"))));
    }
}