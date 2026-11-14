package dev.lumen.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lumen.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * User-editable settings, stored as JSON next to the index.
 *
 * <p>Loaded lazily and never fatal: a missing or broken config falls back to defaults.
 */
public final class Config {

    public String hotkey = "ALT+SPACE";

    /** Folders that are scanned for files and applications. */
    public List<String> roots = defaultRoots();

    /** Directory names that are never scanned (compared case-insensitively, per segment). */
    public List<String> excludedDirs = List.of(
            "node_modules", ".git", ".svn", "target", "build", "out", "dist",
            "bin", "obj", ".venv", "venv", "__pycache__", ".gradle", ".idea",
            "AppData", "$Recycle.Bin", "System Volume Information", "Windows",
            "ProgramData", "Program Files", "Program Files (x86)"
    );

    /** Maximum number of files kept in the index. Keeps memory and scan time predictable. */
    public int maxIndexedFiles = 120_000;

    /** How deep to walk each root. */
    public int maxDepth = 8;

    /** Maximum number of rows shown in the overlay. */
    public int maxResults = 40;

    /** When true the overlay opens once at startup so a fresh install is obviously working. */
    public boolean showOnStartup = false;

    /**
     * When true (the default) the overlay closes when you click into another window.
     *
     * <p>A short settling period keeps the opposite failure away: Windows can pull activation
     * away for a moment while a global hotkey is released, and that must not be treated as a
     * real click-away. Only a focus loss after the overlay has been focused for a moment
     * closes it.
     */
    public boolean hideOnFocusLoss = true;

    /** %s is replaced with the URL-encoded query. */
    public String webSearchUrl = "https://www.google.com/search?q=%s";

    public static Path dataDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData == null || localAppData.isBlank())
                ? Path.of(System.getProperty("user.home"), ".lumen")
                : Path.of(localAppData, "Lumen");
        return base;
    }

    public Path indexPath() {
        return dataDir().resolve("index.json");
    }

    public Path usagePath() {
        return dataDir().resolve("usage.json");
    }

    public Path configPath() {
        return dataDir().resolve("config.json");
    }

    public static Config load() {
        migrateLegacyDataDir();
        Path file = new Config().configPath();
        ObjectMapper mapper = Json.mapper();
        try {
            if (Files.isRegularFile(file)) {
                Config loaded = mapper.readValue(file.toFile(), Config.class);
                loaded.sanitize();
                return loaded;
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[config] could not read " + file + ": " + e.getMessage());
        }
        Config fresh = new Config();
        fresh.save();
        return fresh;
    }

    /**
     * Copies data from the pre-rename Spotlight directory once, so settings, usage history and
     * the index survive the Lumen rename instead of forcing a fresh reindex and losing the
     * user's ranking history.
     */
    private static void migrateLegacyDataDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            return;
        }
        Path legacy = Path.of(localAppData, "SpotlightWin");
        Path current = dataDir();
        if (!Files.isDirectory(legacy) || Files.exists(current.resolve("config.json"))) {
            return;
        }
        try {
            Files.createDirectories(current);
            for (String name : List.of("config.json", "usage.json", "index.json",
                    "first-run", "hotkey-notified")) {
                Path source = legacy.resolve(name);
                if (Files.isRegularFile(source)) {
                    Files.copy(source, current.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            System.out.println("[config] migrated settings from " + legacy);
        } catch (IOException e) {
            System.err.println("[config] could not migrate " + legacy + ": " + e.getMessage());
        }
    }

    private void sanitize() {
        if (hotkey == null || hotkey.isBlank()) {
            hotkey = "ALT+SPACE";
        }
        if (roots == null) {
            roots = defaultRoots();
        }
        if (excludedDirs == null) {
            excludedDirs = List.of();
        }
        if (maxResults < 5 || maxResults > 200) {
            maxResults = 40;
        }
        if (maxIndexedFiles < 1_000) {
            maxIndexedFiles = 120_000;
        }
        if (maxDepth < 1 || maxDepth > 24) {
            maxDepth = 8;
        }
        if (webSearchUrl == null || !webSearchUrl.contains("%s")) {
            webSearchUrl = "https://www.google.com/search?q=%s";
        }
    }

    public void save() {
        try {
            Files.createDirectories(dataDir());
            Json.mapper().writerWithDefaultPrettyPrinter().writeValue(configPath().toFile(), this);
        } catch (IOException e) {
            System.err.println("[config] could not save: " + e.getMessage());
        }
    }

    private static List<String> defaultRoots() {
        Path home = Path.of(System.getProperty("user.home"));
        List<String> roots = new ArrayList<>();
        addIfDir(roots, home.resolve("Desktop"));
        addIfDir(roots, home.resolve("Documents"));
        addIfDir(roots, home.resolve("Downloads"));
        return roots;
    }

    private static void addIfDir(List<String> roots, Path candidate) {
        if (Files.isDirectory(candidate)) {
            roots.add(candidate.toAbsolutePath().toString());
        }
    }
}
