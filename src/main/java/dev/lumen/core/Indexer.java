package dev.lumen.core;

import dev.lumen.config.Config;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the index from the Start Menu plus the configured folders.
 *
 * <p>Runs on a virtual thread. Everything here is blocking IO, so it stays off the
 * JavaFX thread; the UI keeps serving the previous snapshot until the swap happens.
 */
public final class Indexer {

    private static final Set<String> APP_EXTENSIONS = Set.of("lnk", "url", "exe", "appref-ms", "msc");

    private final Config config;
    private final IndexStore store;
    private final UsageStore usage;
    private final Runnable onFinished;

    public Indexer(Config config, IndexStore store, UsageStore usage, Runnable onFinished) {
        this.config = config;
        this.store = store;
        this.usage = usage;
        this.onFinished = onFinished;
    }

    public void rebuild() {
        long started = System.nanoTime();
        List<IndexItem> items = new ArrayList<>(4096);
        Set<String> seen = new HashSet<>(8192);

        scanStartMenu(items, seen);
        int apps = items.size();
        scanRoots(items, seen);

        store.replace(items);
        store.save();

        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.println("[index] " + items.size() + " items (" + apps + " apps) in " + ms + " ms");
        onFinished.run();
    }

    private void scanStartMenu(List<IndexItem> items, Set<String> seen) {
        for (Path dir : startMenuDirs()) {
            if (Files.isDirectory(dir)) {
                walk(dir, items, seen, true, 6);
            }
        }
    }

    private void scanRoots(List<IndexItem> items, Set<String> seen) {
        for (String root : config.roots) {
            Path dir;
            try {
                dir = Path.of(root);
            } catch (RuntimeException e) {
                continue;
            }
            if (Files.isDirectory(dir)) {
                walk(dir, items, seen, false, config.maxDepth);
            }
        }
    }

    private void walk(Path root, List<IndexItem> items, Set<String> seen, boolean appsOnly, int maxDepth) {
        Set<String> excluded = new HashSet<>();
        for (String name : config.excludedDirs) {
            excluded.add(name.toLowerCase(Locale.ROOT));
        }
        String home = System.getProperty("user.home");

        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), maxDepth,
                    new SimpleFileVisitor<>() {

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(root)) {
                                if (isExcludedDir(dir, excluded)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                if (!appsOnly) {
                                    add(items, seen, IndexItem.folder(
                                            dir.getFileName().toString(),
                                            dir.toString(),
                                            shorten(dir.getParent(), home)));
                                }
                            }
                            return hasRoom(items) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isRegularFile() && !isIgnoredFile(file)) {
                                String name = file.getFileName().toString();
                                if (appsOnly) {
                                    if (isAppFile(name)) {
                                        add(items, seen, IndexItem.app(stripExtension(name), file.toString()));
                                    }
                                } else {
                                    add(items, seen, IndexItem.file(
                                            name,
                                            file.toString(),
                                            shorten(file.getParent(), home)));
                                }
                            }
                            return hasRoom(items) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            System.err.println("[index] failed walking " + root + ": " + e.getMessage());
        }
    }

    private boolean hasRoom(List<IndexItem> items) {
        return items.size() < config.maxIndexedFiles;
    }

    private static void add(List<IndexItem> items, Set<String> seen, IndexItem item) {
        if (seen.add(item.path().toLowerCase(Locale.ROOT))) {
            items.add(item);
        }
    }

    private static boolean isExcludedDir(Path dir, Set<String> excluded) {
        String name = dir.getFileName().toString();
        if (name.startsWith(".")) {
            return true;
        }
        if (excluded.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        try {
            return Files.isHidden(dir);
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean isIgnoredFile(Path file) {
        String name = file.getFileName().toString();
        if (name.startsWith(".") || name.startsWith("~$")) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("desktop.ini") || lower.equals("thumbs.db")) {
            return true;
        }
        try {
            return Files.isHidden(file);
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean isAppFile(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && APP_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String shorten(Path path, String home) {
        if (path == null) {
            return "";
        }
        String full = path.toString();
        String prefix = home + "\\";
        return full.startsWith(prefix) ? "~\\" + full.substring(prefix.length()) : full;
    }

    private static List<Path> startMenuDirs() {
        List<Path> dirs = new ArrayList<>(3);
        String appData = System.getenv("APPDATA");
        String programData = System.getenv("PROGRAMDATA");
        if (appData != null) {
            dirs.add(Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs"));
        }
        if (programData != null) {
            dirs.add(Path.of(programData, "Microsoft", "Windows", "Start Menu", "Programs"));
        }
        return dirs;
    }
}
