package dev.lumen.core;

import java.util.List;
import java.util.Locale;

/**
 * Ranking-quality metrics from the evaluation harness.
 *
 * @param ndcg     nDCG@10
 * @param ndcgAt5  nDCG@5
 * @param recall   recall@3
 * @param mrr      Mean Reciprocal Rank
 * @param failures number of queries with zero relevant results in top 20
 */
public record EvalResults(double ndcg, double ndcgAt5, double recall, double mrr, int failures) {

    public static final double NDCG_TARGET = 0.85;
    public static final double RECALL_TARGET = 0.95;
}

/**
 * Evaluation harness — runs labeled judgments against a {@link SearchEngine} and reports
 * nDCG@10, nDCG@5, recall@3 and MRR.
 *
 * <p>Not public — accessed from {@code EvalHarnessTest} in the test tree.
 */
final class EvalRunner {

    private EvalRunner() {
    }

    /**
     * Evaluates a set of judgments.
     *
     * @param judgments the labeled queries
     * @param engine    search engine to test
     * @param store     the full index store, scanned once per judgment to count every relevant
     *                  item in the corpus (needed for correct recall and ideal-DCG in synthetic
     *                  corpora where one pattern matches many entries)
     */
    static EvalResults evaluate(List<QueryJudgment> judgments, SearchEngine engine, IndexStore store) {
        int failures = 0;
        double ndcgSum = 0;
        double ndcg5Sum = 0;
        double recallSum = 0;
        double mrrSum = 0;
        int count = judgments.size();

        for (QueryJudgment j : judgments) {
            List<Hit> results = engine.search(j.query());
            if (results.isEmpty()) {
                failures++;
                System.err.println("[eval] no results for: " + j.query());
                continue;
            }

            int totalRelevant = countRelevantInStore(store, j);
            if (totalRelevant == 0) {
                failures++;
                System.err.println("[eval] no relevant items in corpus for: " + j.query());
                continue;
            }

            int found = 0;
            int firstRank = -1;
            boolean[] relevant = new boolean[Math.min(results.size(), 20)];

            for (int rank = 0; rank < relevant.length; rank++) {
                if (relevant(results.get(rank), j)) {
                    relevant[rank] = true;
                    found++;
                    if (firstRank == -1) {
                        firstRank = rank + 1;
                    }
                }
            }

            // nDCG@10
            ndcgSum += ndcg(results, relevant, totalRelevant, 10);
            // nDCG@5
            ndcg5Sum += ndcg(results, relevant, totalRelevant, 5);
            // recall@3
            recallSum += (double) Math.min(found, 3) / Math.min(totalRelevant, 3);
            // MRR
            mrrSum += firstRank > 0 ? 1.0 / firstRank : 0.0;

            if (found == 0) {
                failures++;
                System.err.println("[eval] no relevant result in top 20 for: " + j.query());
            }
        }

        int n = Math.max(count, 1);
        return new EvalResults(ndcgSum / n, ndcg5Sum / n, recallSum / n, mrrSum / n, failures);
    }

    static void printSummary(String label, EvalResults results) {
        System.out.printf(Locale.ROOT,
                "[eval] %s: nDCG@10=%.4f  nDCG@5=%.4f  recall@3=%.4f  MRR=%.4f  failures=%d%n",
                label, results.ndcg(), results.ndcgAt5(), results.recall(),
                results.mrr(), results.failures());
    }

    // -- relevance ----------------------------------------------------------

    private static int countRelevantInStore(IndexStore store, QueryJudgment j) {
        int count = 0;
        boolean hasPatterns = !j.relevantPaths().isEmpty() || !j.relevantTitles().isEmpty();
        if (!hasPatterns) {
            return 0;
        }
        for (IndexEntry entry : store.entries()) {
            if (relevant(entry, j)) {
                count++;
            }
        }
        return count;
    }

    /** True when a Hit is relevant for a judgment. */
    private static boolean relevant(Hit hit, QueryJudgment j) {
        return relevant(hit.item().path().toLowerCase(Locale.ROOT), hit.item().title().toLowerCase(Locale.ROOT), j);
    }

    /** True when an IndexEntry is relevant for a judgment. */
    private static boolean relevant(IndexEntry entry, QueryJudgment j) {
        return relevant(entry.pathLower(), entry.nameLower(), j);
    }

    private static boolean relevant(String pathLower, String nameLower, QueryJudgment j) {
        for (String r : j.relevantPaths()) {
            if (pathLower.contains(r)) {
                return true;
            }
        }
        for (String r : j.relevantTitles()) {
            if (nameLower.contains(r)) {
                return true;
            }
        }
        return false;
    }

    // -- metrics ------------------------------------------------------------

    /** nDCG@K = dcg / idealDcg. */
    private static double ndcg(List<Hit> results, boolean[] relevant, int totalRelevant, int k) {
        double dcg = dcg(results, relevant, k);
        double idcg = idealDcg(totalRelevant, k);
        return idcg > 0 ? dcg / idcg : 0.0;
    }

    private static double dcg(List<Hit> results, boolean[] relevant, int k) {
        double dcg = 0.0;
        for (int i = 0; i < results.size() && i < k; i++) {
            double rel = (i < relevant.length && relevant[i]) ? 1.0 : 0.0;
            dcg += rel / log2(i + 2);
        }
        return dcg;
    }

    private static double idealDcg(int nRelevant, int k) {
        double dcg = 0.0;
        for (int i = 0; i < Math.min(nRelevant, k); i++) {
            dcg += 1.0 / log2(i + 2);
        }
        return dcg;
    }

    private static double log2(int i) {
        return Math.log(i) / Math.log(2);
    }
}