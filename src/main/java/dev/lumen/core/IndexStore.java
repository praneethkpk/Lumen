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
