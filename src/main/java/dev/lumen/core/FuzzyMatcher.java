package dev.lumen.core;

/**
 * Scores a fuzzy subsequence match, higher is better, {@code -1} means no match.
 *
 * <p>The scoring model follows quick-search's match-priority ladder rather than a pure
 * additive score: exact, prefix, word-start, contains, acronym, and subsequence matches are
 * separated by wide bands, so a strong match can never be overtaken by a pile of weak ones.
 * The old {@link #score(String, String)} entry point is kept for provider code and tests.
 */
public final class FuzzyMatcher {

    /** Bands adapted from quick-search's priority levels; higher is better. */
    public static final int EXACT_NAME = 10_000;
    public static final int NAME_PREFIX = 4_000;
    public static final int WORD_PREFIX = 2_200;
    public static final int NAME_CONTAINS = 1_200;
    public static final int COMPACT_CONTAINS = 1_000;
    public static final int ACRONYM = 800;
    public static final int PATH_CONTAINS = 300;

    private FuzzyMatcher() {
    }

    /**
     * Best score for one token against an item's name and path.
     *
     * @return a positive score, or {@code -1} when the token does not match either field
     */
    public static int scoreToken(String token, String name, String path) {
        return scoreToken(token, name, SearchText.acronym(name), path);
    }

    /**
     * Hot-path variant: the acronym is precomputed by {@link IndexEntry}.
     *
     * <p>Order matters for speed as well as ranking. {@code indexOf} answers exact, prefix,
     * word-prefix and contains in one allocation-free pass; the acronym is a string compare;
     * path matching is skipped for single-character tokens (the drive letter would otherwise
     * match every path); subsequence matching is the last resort.
     */
    public static int scoreToken(String token, String name, String acronym, String path) {
        if (token.isEmpty()) {
            return 0;
        }
        if (name.equals(token)) {
            return EXACT_NAME;
        }

        int occurrence = name.indexOf(token);
        if (occurrence == 0) {
            return NAME_PREFIX;
        }
        if (occurrence > 0) {
            while (occurrence >= 0) {
                if (!Character.isLetterOrDigit(name.charAt(occurrence - 1))) {
                    return WORD_PREFIX;
                }
                occurrence = name.indexOf(token, occurrence + 1);
            }
            return NAME_CONTAINS;
        }

        if (token.length() >= 2 && acronym != null
                && (acronym.equals(token) || acronym.startsWith(token))) {
            return ACRONYM;
        }

        if (token.length() >= 2 && path != null && !path.isEmpty() && path.contains(token)) {
            return PATH_CONTAINS;
        }

        int nameFuzzy = score(token, name);
        return nameFuzzy >= 0 ? nameFuzzy : -1;
    }

    public static int score(String needle, String hay) {
        if (needle.isEmpty()) {
            return 0;
        }
        if (hay.length() < needle.length()) {
            return -1;
        }

        int score = 0;
        int from = 0;
        int previous = -2;

        for (int i = 0; i < needle.length(); i++) {
            int found = hay.indexOf(needle.charAt(i), from);
            if (found < 0) {
                return -1;
            }

            score += 10;
            if (found == 0) {
                score += 40;
            } else if (isBoundary(hay.charAt(found - 1))) {
                score += 22;
            }

            if (found == previous + 1) {
                score += 14;
            } else if (previous >= 0) {
                score -= Math.min(found - previous - 1, 12);
            }

            previous = found;
            from = found + 1;
        }

        if (hay.startsWith(needle)) {
            score += 90;
        }
        if (hay.equals(needle)) {
            score += 260;
        }

        // Nudge shorter results ahead of long paths that happen to match.
        return score - Math.min(hay.length() / 12, 10);
    }

    private static boolean isBoundary(char c) {
        return c == ' ' || c == '-' || c == '_' || c == '.' || c == '\\' || c == '/'
                || c == '(' || c == '[' || c == '{' || c == ':';
    }
}
