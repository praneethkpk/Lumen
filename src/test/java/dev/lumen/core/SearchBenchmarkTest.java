package dev.lumen.core;

import dev.lumen.config.Config;
import dev.lumen.providers.IndexProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Opt-in latency check over a six-figure index.
 *
 * <p>Run with {@code mvn -Dtest=SearchBenchmarkTest -Dbench=true test}. Kept out of the
 * normal suite because timing assertions depend on the machine.
 */
class SearchBenchmarkTest {

    private static final int ITERATIONS = 120;

    @TempDir
    Path temp;

    @Test
    void reportsKeystrokeLatency() {
        Assumptions.assumeTrue(Boolean.getBoolean("bench"), "run with -Dbench=true");

        List<IndexItem> items = SyntheticCorpus.build();
        IndexStore store = new IndexStore(temp.resolve("index.json"));
        store.replace(items);
        UsageStore usage = new UsageStore(temp.resolve("usage.json"));
        Config config = new Config();
        SearchEngine engine = new SearchEngine(
                List.of(new IndexProvider(store, usage, config)), config);

        for (String query : List.of("c", "chr", "chrome", "report", "quarterly report",
                "vsc", "ext:pdf", "in:documents report", "apps chrome")) {
            for (int i = 0; i < 20; i++) {
                engine.search(query);
            }

            long[] nanos = new long[ITERATIONS];
            for (int i = 0; i < ITERATIONS; i++) {
                long start = System.nanoTime();
                engine.search(query);
                nanos[i] = System.nanoTime() - start;
            }
            Arrays.sort(nanos);

            double p50 = nanos[ITERATIONS / 2] / 1_000_000.0;
            double p95 = nanos[(int) Math.ceil(ITERATIONS * 0.95) - 1] / 1_000_000.0;
            System.out.printf(Locale.ROOT, "%-22s p50=%6.2f ms  p95=%6.2f ms%n", query, p50, p95);
        }
    }
}