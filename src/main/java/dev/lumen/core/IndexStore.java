package dev.lumen.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lumen.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory index plus a JSON snapshot on disk.
 *
 * <p>On startup the snapshot is read synchronously (a few ms for ~100k rows) so the very
 * first keystroke already returns results. A background reindex then replaces it.
 *
 * <p>Reads are lock-free: the whole entry list is swapped atomically.
 */
public final class IndexStore {

    private static final TypeReference<List<IndexItem>> TYPE = new TypeReference<>() {
    };

    private final Path file;
    private final ObjectMapper mapper = Json.mapper();
    private final AtomicReference<List<IndexEntry>> entries = new AtomicReference<>(List.of());
    private volatile boolean dirty;

    public IndexStore(Path file) {
        this.file = file;
    }

    public List<IndexEntry> entries() {
        return entries.get();
    }

    public int size() {
        return entries.get().size();
    }

    /** Replace the live index. Safe to call from the indexer thread. */
    public void replace(List<IndexItem> items) {
        List<IndexEntry> next = new ArrayList<>(items.size());
        for (IndexItem item : items) {
            next.add(IndexEntry.of(item));
        }
        entries.set(List.copyOf(next));
        dirty = true;
    }

    /** Insert or replace one item, matching by normalized path. */
    public void upsert(IndexItem item) {
        if (item == null || item.path() == null || item.path().isBlank()) {
            return;
        }
        String key = normalize(item.path());
        List<IndexEntry> current = entries.get();
        List<IndexEntry> next = new ArrayList<>(current.size() + 1);
        for (IndexEntry entry : current) {
            if (!normalize(entry.item().path()).equals(key)) {
                next.add(entry);
            }
        }
        next.add(IndexEntry.of(item));
        entries.set(List.copyOf(next));
        dirty = true;
    }

    /** Remove one item by path. */
    public void removePath(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        String key = normalize(path);
        List<IndexEntry> current = entries.get();
        List<IndexEntry> next = new ArrayList<>(current.size());
        boolean changed = false;
        for (IndexEntry entry : current) {
            if (normalize(entry.item().path()).equals(key)) {
                changed = true;
            } else {
                next.add(entry);
            }
        }
        if (changed) {
            entries.set(List.copyOf(next));
            dirty = true;
        }
    }

    /** Remove every item whose path is under {@code prefix} (inclusive). */
    public void removeUnder(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        String base = normalize(prefix);
        String withSep = base.endsWith("\\") || base.endsWith("/") ? base : base + "\\";
        List<IndexEntry> current = entries.get();
        List<IndexEntry> next = new ArrayList<>(current.size());
        boolean changed = false;
        for (IndexEntry entry : current) {
            String p = normalize(entry.item().path());
            if (p.equals(base) || p.startsWith(withSep) || p.startsWith(base + "/")) {
                changed = true;
            } else {
                next.add(entry);
            }
        }
        if (changed) {
            entries.set(List.copyOf(next));
            dirty = true;
        }
    }

    private static String normalize(String path) {
        return path.replace('/', '\\').toLowerCase(Locale.ROOT);
    }

    public void load() {
        long start = System.nanoTime();
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            List<IndexItem> items = mapper.readValue(file.toFile(), TYPE);
            if (items != null) {
                replace(items);
                dirty = false;
                long ms = (System.nanoTime() - start) / 1_000_000;
                System.out.println("[index] loaded " + items.size() + " items from snapshot in " + ms + " ms");
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[index] could not read snapshot: " + e.getMessage());
        }
    }

    public synchronized void save() {
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), entries.get().stream().map(IndexEntry::item).toList());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[index] could not save snapshot: " + e.getMessage());
        }
    }

    /** Blocking save used on shutdown, where a background write could be killed. */
    public void flush() {
        save();
    }
}
