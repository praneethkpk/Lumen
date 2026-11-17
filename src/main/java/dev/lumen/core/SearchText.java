package dev.lumen.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Normalization and cheap text predicates shared by indexing and search.
 *
 * <p>Adapted from quick-search's {@code SearchTextNormalizer}: strip diacritics, fold case,
 * unify the Turkish dotless i, and provide allocation-free helpers for compact and initial
 * matching. Doing this once at index time keeps the keystroke path free of normalization work.
 */
public final class SearchText {

    private SearchText() {
    }

    /** Lowercase, remove diacritics, collapse whitespace, and unify Turkish dotless i. */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        boolean pendingSpace = false;

        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (c == '\u00A0' || Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            if (c == '\u0131') {
                c = 'i';
            }
            out.append(Character.toLowerCase(c));
        }

        return out.toString();
    }

    /** Letters and digits only, used for separator-insensitive matching. */
    public static String compact(String normalizedText) {
        StringBuilder out = new StringBuilder(normalizedText.length());
        for (int i = 0; i < normalizedText.length(); i++) {
            char c = normalizedText.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** First letter/digit of every word, e.g. "Visual Studio Code" to "vsc". */
    public static String acronym(String normalizedText) {
        StringBuilder out = new StringBuilder(8);
        boolean atWordStart = true;
        for (int i = 0; i < normalizedText.length(); i++) {
            char c = normalizedText.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                atWordStart = true;
                continue;
            }
            if (atWordStart) {
                out.append(Character.toLowerCase(c));
                atWordStart = false;
            }
        }
        return out.toString();
    }

    /** Split into words without a regex allocation. */
    public static List<String> words(String normalizedText) {
        List<String> words = new ArrayList<>(4);
        int start = -1;
        for (int i = 0; i < normalizedText.length(); i++) {
            char c = normalizedText.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (start < 0) {
                    start = i;
                }
            } else if (start >= 0) {
                words.add(normalizedText.substring(start, i));
                start = -1;
            }
        }
        if (start >= 0) {
            words.add(normalizedText.substring(start));
        }
        return words;
    }

    /** True when {@code needle} occurs in {@code text} once separators are skipped. */
    public static boolean containsCompact(String text, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        for (int start = 0; start < text.length(); start++) {
            if (!Character.isLetterOrDigit(text.charAt(start)) || text.charAt(start) != needle.charAt(0)) {
                continue;
            }
            int textIndex = start;
            int needleIndex = 0;
            while (textIndex < text.length() && needleIndex < needle.length()) {
                char c = text.charAt(textIndex++);
                if (!Character.isLetterOrDigit(c)) {
                    continue;
                }
                if (c != needle.charAt(needleIndex)) {
                    break;
                }
                needleIndex++;
            }
            if (needleIndex == needle.length()) {
                return true;
            }
        }
        return false;
    }

    /** True when any word in {@code text} starts with {@code token}. */
    public static boolean hasWordStartingWith(String text, String token) {
        if (token.isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.startsWith(token, i) && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))) {
                return true;
            }
        }
        return false;
    }

    /** True when the initials of {@code text} equal or start with {@code token}. */
    public static boolean acronymMatches(String text, String token) {
        if (token.length() < 2 || token.length() > 12) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isLetterOrDigit(token.charAt(i))) {
                return false;
            }
        }

        StringBuilder initials = new StringBuilder(8);
        boolean atWordStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                atWordStart = true;
                continue;
            }
            if (atWordStart) {
                initials.append(Character.toLowerCase(c));
                atWordStart = false;
            }
        }
        return initials.toString().equals(token) || initials.toString().startsWith(token);
    }

    /** Path depth, used as a small tie-break penalty so shallow results win. */
    public static int pathDepth(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' || c == '/') {
                depth++;
            }
        }
        return depth;
    }

    /** Normalized alias lookup helper. */
    public static String alias(String value) {
        return normalize(value);
    }

    /** Locale-independent lowercase; kept for call sites that only need case folding. */
    public static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    /**
     * A 64-bit bitmap of the character 3-grams in {@code text}, where each 3-gram is a
     * 15-bit id packed into a single {@code long}. Letters and digits are folded to lowercase;
     * separators and punctuation are dropped. Used by {@link TrigramIndex} to skip entries
     * that cannot possibly contain a query before any scoring work is done.
     *
     * <p>64 bits holds up to 4 distinct 3-grams per slot, which is enough to catch the vast
     * majority of false positives. A collision between two different 3-grams is a false
     * negative for one entry at most, and the exact scorer still runs on survivors, so the
     * result is never wrong — only occasionally a little slower than ideal.
     */
    public static long trigramMask(String text) {
        if (text == null || text.length() < 3) {
            return 0L;
        }
        long mask = 0L;
        char a = 0, b = 0;
        boolean have = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                a = b = 0;
                have = false;
                continue;
            }
            c = Character.toLowerCase(c);
            if (have) {
                int id = ((a - 'a') << 10) | ((b - 'a') << 5) | (c - 'a');
                mask |= 1L << (id & 63);
            }
            a = b;
            b = c;
            have = true;
        }
        return mask;
    }
}
