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
        scanUwpApps(items, seen);
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

    private void scanUwpApps(List<IndexItem> items, Set<String> seen) {
        // Store apps (and other apps surfaced through the shell's AppsFolder) are not on
        // disk as .lnk files, so the Start Menu walk misses them. Ask the shell for the
        // list instead; feature-detect by simply skipping when PowerShell is unavailable.
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Get-StartApps | ForEach-Object { $_.Name + [char]9 + $_.AppID }");
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!hasRoom(items)) {
                        break;
                    }
                    int tab = line.indexOf('\t');
                    if (tab <= 0 || tab == line.length() - 1) {
                        continue;
                    }
                    String name = line.substring(0, tab).trim();
                    String appId = line.substring(tab + 1).trim();
                    if (name.isBlank() || appId.isBlank()) {
                        continue;
                    }
                    // Get-StartApps also lists desktop apps; those are already covered by the
                    // Start Menu walk. UWP/Store AUMIDs always contain a '!', so use that as
                    // the filter and avoid duplicate rows.
                    if (!appId.contains("!")) {
                        continue;
                    }
                    // Skip entries that are already covered by the Start Menu walk (they
                    // would only duplicate a .lnk with a worse launch target).
                    String target = "shell:AppsFolder\\" + appId;
                    add(items, seen, IndexItem.app(name, target));
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.err.println("[index] UWP app enumeration unavailable: " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
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

    public static void add(List<IndexItem> items, Set<String> seen, IndexItem item) {
        if (seen.add(item.path().toLowerCase(Locale.ROOT))) {
            items.add(item);
        }
    }

    public static boolean isExcludedDir(Path dir, Set<String> excluded) {
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

    public static boolean isIgnoredFile(Path file) {
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

    public static boolean isAppFile(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && APP_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String shorten(Path path, String home) {
        if (path == null) {
            return "";
        }
        String full = path.toString();
        String prefix = home + "\\";
        return full.startsWith(prefix) ? "~\\" + full.substring(prefix.length()) : full;
    }

    public static List<Path> startMenuDirs() {
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
