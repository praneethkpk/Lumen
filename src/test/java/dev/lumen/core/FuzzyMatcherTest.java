package dev.lumen.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuzzyMatcherTest {

    @Test
    void matchesSubsequences() {
        assertTrue(FuzzyMatcher.score("chrm", "chrome") > 0);
        assertTrue(FuzzyMatcher.score("vsc", "visual studio code") > 0);
    }

    @Test
    void rejectsNonMatches() {
        assertEquals(-1, FuzzyMatcher.score("zzz", "chrome"));
        assertEquals(-1, FuzzyMatcher.score("chromex", "chrome"));
    }

    @Test
    void prefersPrefixesAndBoundaries() {
        int prefix = FuzzyMatcher.score("chr", "chrome");
        int middle = FuzzyMatcher.score("chr", "google chrome");
        assertTrue(prefix > middle, "prefix should outrank a mid-word match");

        int boundary = FuzzyMatcher.score("vs", "visual studio");
        int scattered = FuzzyMatcher.score("vs", "adversity");
        assertTrue(boundary > scattered, "word-boundary initials should outrank scattered hits");
    }

    @Test
    void exactMatchWins() {
        assertTrue(FuzzyMatcher.score("chrome", "chrome") > FuzzyMatcher.score("chrome", "chrome helper"));
    }

    @Test
    void matchPriorityBandsAreOrdered() {
        assertEquals(FuzzyMatcher.EXACT_NAME, FuzzyMatcher.scoreToken("chrome", "chrome", ""));
        assertTrue(FuzzyMatcher.scoreToken("chrome", "chrome", "")
                > FuzzyMatcher.scoreToken("chrome", "chrome helper", ""));
        assertTrue(FuzzyMatcher.scoreToken("chr", "chrome", "")
                > FuzzyMatcher.scoreToken("chr", "google chrome", ""));
        assertTrue(FuzzyMatcher.scoreToken("chr", "google chrome", "")
                > FuzzyMatcher.scoreToken("chr", "enchrome helper", ""));
        assertTrue(FuzzyMatcher.scoreToken("vsc", "visual studio code", "")
                > FuzzyMatcher.scoreToken("vsc", "adversity score", ""));
        assertTrue(FuzzyMatcher.scoreToken("7zip", "7-zip", "")
                > FuzzyMatcher.scoreToken("7zip", "zip helper", ""));
    }

    @Test
    void matchesPathSegmentsAndAcronyms() {
        assertEquals(FuzzyMatcher.PATH_CONTAINS,
                FuzzyMatcher.scoreToken("docs", "report", "c:\\docs\\report.txt"));
        assertEquals(FuzzyMatcher.ACRONYM,
                FuzzyMatcher.scoreToken("vsc", "visual studio code", ""));
        assertEquals(-1, FuzzyMatcher.scoreToken("zzz", "chrome", "c:\\chrome"));
    }
}
