package dev.lumen.core;

import java.util.List;

/**
 * Labeled judgment for one query in the evaluation harness.
 *
 * @param query      the raw query text
 * @param relevantPaths  paths that are relevant (in order of relevance, highest first)
 * @param relevantTitles titles matched against {@link IndexEntry#nameLower()} when paths are
 *                       unknown (e.g. for synthetic corpora)
 */
public record QueryJudgment(String query, List<String> relevantPaths, List<String> relevantTitles) {

    public QueryJudgment {
        relevantPaths = relevantPaths == null ? List.of() : List.copyOf(relevantPaths);
        relevantTitles = relevantTitles == null ? List.of() : List.copyOf(relevantTitles);
    }

    public static QueryJudgment of(String query, List<String> relevantPaths) {
        return new QueryJudgment(query, relevantPaths, List.of());
    }

    public static QueryJudgment of(String query, List<String> relevantPaths, List<String> relevantTitles) {
        return new QueryJudgment(query, relevantPaths, relevantTitles);
    }

    public static QueryJudgment byTitle(String query, List<String> relevantTitles) {
        return new QueryJudgment(query, List.of(), relevantTitles);
    }
}