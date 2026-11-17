package dev.lumen.core;

import java.util.List;

/**
 * Labeled judgment set for the synthetic corpus.
 *
 * <p>Each judgment has a query, the set of relevant paths (matched by containment against
 * the lowercased entry path) and relevant titles (matched by containment against the
 * lowercased entry title). These are hand-labeled against {@link SyntheticCorpus#build()}.
 */
public final class EvalQueries {

    private EvalQueries() {
    }

    /** Returns the full query set for synthetic-corpus evaluation. */
    public static List<QueryJudgment> synthetic() {
        return List.of(
                // --- single-token app names ---
                QueryJudgment.byTitle("chrome", List.of("google chrome")),
                QueryJudgment.byTitle("vsc", List.of("visual studio code")),

                // --- partial / typo ---
                QueryJudgment.byTitle("chr", List.of("google chrome")),
                QueryJudgment.byTitle("notepad", List.of("notepad")),
                QueryJudgment.byTitle("calculator", List.of("calculator")),

                // --- short single character ---
                QueryJudgment.byTitle("c", List.of("calculator", "discord")),

                // --- files ---
                QueryJudgment.byTitle("report", List.of("report")),
                QueryJudgment.byTitle("report ext:pdf", List.of("report")),

                // --- alias routing ---
                QueryJudgment.of("apps chrome", List.of("c:\\apps\\app1.exe"),
                        List.of("google chrome")),
                QueryJudgment.byTitle("calc", List.of("calculator")),

                // --- multi-token ---
                QueryJudgment.byTitle("visual studio", List.of("visual studio code")),

                // --- path hints ---
                QueryJudgment.byTitle("in:folder1 report", List.of("report")),

                // --- exclusion ---
                QueryJudgment.byTitle("report -txt", List.of("report")),

                // --- edge cases ---
                QueryJudgment.byTitle("powershell", List.of("powershell")),
                QueryJudgment.byTitle("terminal", List.of("terminal")),
                QueryJudgment.byTitle("spotify", List.of("spotify"))
        );
    }

    /** Smaller set for quick smoke-test runs. */
    public static List<QueryJudgment> smoke() {
        return List.of(
                QueryJudgment.byTitle("chrome", List.of("google chrome")),
                QueryJudgment.byTitle("vsc", List.of("visual studio code")),
                QueryJudgment.byTitle("report", List.of("report")),
                QueryJudgment.of("apps chrome", List.of("c:\\apps\\app1.exe"),
                        List.of("google chrome")),
                QueryJudgment.byTitle("visual studio", List.of("visual studio code"))
        );
    }
}