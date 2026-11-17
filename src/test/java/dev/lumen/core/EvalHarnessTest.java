package dev.lumen.core;

import dev.lumen.config.Config;
import dev.lumen.providers.IndexProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the labeled evaluation set against the synthetic corpus and asserts quality targets.
 *
 * <p>This is the gate that every ranking change must clear: if nDCG@10 drops, the change
 * is rejected. Run with the rest of the unit tests via {@code mvn test}.
 */
class EvalHarnessTest {

    @TempDir
    Path temp;

    @Test
    void meetsQualityTargets() {
        List<IndexItem> corpus = SyntheticCorpus.build();
        IndexStore store = new IndexStore(temp.resolve("index.json"));
        store.replace(corpus);
        UsageStore usage = new UsageStore(temp.resolve("usage.json"));
        Config config = new Config();
        config.maxResults = 40;
        SearchEngine engine = new SearchEngine(
                List.of(new IndexProvider(store, usage, config)), config);

        List<QueryJudgment> judgments = EvalQueries.synthetic();
        EvalResults results = EvalRunner.evaluate(judgments, engine, store);

        EvalRunner.printSummary("synthetic", results);

        // Every query must produce at least one relevant result.
        assertTrue(results.failures() == 0,
                "expected 0 failures but got " + results.failures());

        // Quality targets from the roadmap.
        assertTrue(results.ndcg() >= EvalResults.NDCG_TARGET,
                "nDCG@10 " + String.format("%.4f", results.ndcg())
                        + " < target " + EvalResults.NDCG_TARGET);
        assertTrue(results.recall() >= EvalResults.RECALL_TARGET,
                "recall@3 " + String.format("%.4f", results.recall())
                        + " < target " + EvalResults.RECALL_TARGET);
    }
}