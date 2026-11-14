package dev.lumen.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lumen.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Remembers which results the user actually opens so frequently used apps float up.
 *
 * <p>Kept deliberately tiny (path -> count + last use) and persisted with a 2s debounce,
 * because it is written on every launch.
 */
public final class UsageStore {

    public record Usage(int count, long lastUsed) {
    }

    private static final TypeReference<Map<String, Usage>> TYPE = new TypeReference<>() {
    };

    private final Path file;
    private final Path pinnedFile;
    private final ObjectMapper mapper = Json.mapper();
    private final Map<String, Usage> usage = new ConcurrentHashMap<>();
    private final Set<String> pinned = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "usage-saver");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean dirty;

    public UsageStore(Path file) {
        this.file = file;
        this.pinnedFile = file.resolveSibling("pinned.json");
        load();
        loadPinned();
    }

    private void load() {
        try {
            if (Files.isRegularFile(file)) {
                Map<String, Usage> loaded = mapper.readValue(file.toFile(), TYPE);
                if (loaded != null) {
                    usage.putAll(loaded);
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[usage] could not read " + file + ": " + e.getMessage());
        }
    }

    private void loadPinned() {
        try {
            if (Files.isRegularFile(pinnedFile)) {
                Set<String> loaded = mapper.readValue(pinnedFile.toFile(), new com.fasterxml.jackson.core.type.TypeReference<Set<String>>() {
                });
                if (loaded != null) {
                    pinned.addAll(loaded);
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[usage] could not read " + pinnedFile + ": " + e.getMessage());
        }
    }

    private void savePinned() {
        try {
            Files.createDirectories(pinnedFile.getParent());
            Path tmp = pinnedFile.resolveSibling(pinnedFile.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), new HashSet<>(pinned));
            Files.move(tmp, pinnedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[usage] could not save " + pinnedFile + ": " + e.getMessage());
        }
    }

    public void record(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        usage.compute(key(path), (k, old) -> {
            int count = old == null ? 1 : Math.min(old.count() + 1, 10_000);
            return new Usage(count, System.currentTimeMillis());
        });
        dirty = true;
        saver.schedule(this::flush, 2, TimeUnit.SECONDS);
    }

    /** Additive score bonus; bounded so it can never drown out a good text match. */
    public int boost(String path) {
        return boostNormalized(key(path));
    }

    /** Hot-path variant: the caller already holds a normalized path (see IndexEntry). */
    public int boostNormalized(String normalizedPath) {
        int boost = 0;
        if (pinned.contains(normalizedPath)) {
            boost += 1000;
        }
        Usage u = usage.get(normalizedPath);
        if (u == null) {
            return boost;
        }
        boost += Math.min(u.count(), 20) * 3;
        if (System.currentTimeMillis() - u.lastUsed() < TimeUnit.HOURS.toMillis(24)) {
            boost += 18;
        }
        return boost;
    }

    public boolean isPinned(String path) {
        return isPinnedNormalized(key(path));
    }

    public boolean isPinnedNormalized(String normalizedPath) {
        return pinned.contains(normalizedPath);
    }

    public void pin(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        if (pinned.add(key(path))) {
            savePinned();
        }
    }

    public void unpin(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        if (pinned.remove(key(path))) {
            savePinned();
        }
    }

    public long lastUsed(String path) {
        return lastUsedNormalized(key(path));
    }

    public long lastUsedNormalized(String normalizedPath) {
        Usage u = usage.get(normalizedPath);
        return u == null ? 0L : u.lastUsed();
    }

    private static String key(String path) {
        return path == null ? "" : SearchText.normalize(path);
    }

    public synchronized void flush() {
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), new HashMap<>(usage));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[usage] could not save: " + e.getMessage());
        }
    }

    public void shutdown() {
        saver.shutdownNow();
        flush();
    }
}
