package dev.lumen.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared synthetic corpus for ranking and latency benchmarks.
 *
 * <p>Built deterministically so metrics are reproducible across runs. The first 240 items are
 * apps (15 names × 16 version groups); the remaining items are files named {@code report} with
 * random-looking suffixes and paths.
 */
public final class SyntheticCorpus {

    private static final int ITEMS = 120_000;

    public static final String[] APP_NAMES = {
            "Visual Studio Code", "Google Chrome", "Firefox", "Notepad", "Calculator",
            "Terminal", "PowerShell", "Spotify", "Slack", "Discord", "Steam",
            "Task Manager", "Settings", "Paint", "Snipping Tool"
    };

    private SyntheticCorpus() {
    }

    /** Builds the synthetic corpus for benchmark use. */
    public static List<IndexItem> build() {
        List<IndexItem> items = new ArrayList<>(ITEMS);
        for (int i = 0; i < ITEMS; i++) {
            if (i < 240) {
                String name = APP_NAMES[i % APP_NAMES.length] + " " + (i / APP_NAMES.length);
                items.add(IndexItem.app(name, "C:\\Apps\\app" + i + ".exe"));
            } else {
                String folder = "folder" + (i % 200);
                String name = "report " + i + (i % 3 == 0 ? ".pdf" : ".txt");
                items.add(IndexItem.file(name,
                        "C:\\Users\\me\\Documents\\" + folder + "\\" + name, folder));
            }
        }
        return items;
    }
}