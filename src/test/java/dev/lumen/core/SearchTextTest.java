package dev.lumen.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTextTest {

    @Test
    void normalizesCaseDiacriticsAndWhitespace() {
        assertEquals("cafe", SearchText.normalize("Café"));
        assertEquals("istanbul", SearchText.normalize("İstanbul"));
        assertEquals("hello world", SearchText.normalize("  Hello\u00A0  World  "));
        assertEquals("", SearchText.normalize(null));
    }

    @Test
    void buildsCompactFormsAndAcronyms() {
        assertEquals("visualstudiocode", SearchText.compact("visual studio code"));
        assertEquals("vsc", SearchText.acronym("Visual Studio Code"));
        assertEquals("7z", SearchText.acronym("7-Zip"));
    }

    @Test
    void matchesAcrossSeparators() {
        assertTrue(SearchText.containsCompact("7-zip", "7zip"));
        assertTrue(SearchText.containsCompact("visual studio code", "studiocode"));
        assertFalse(SearchText.containsCompact("chrome", "zzz"));
    }

    @Test
    void matchesWordStartsAndAcronyms() {
        assertTrue(SearchText.hasWordStartingWith("google chrome", "chr"));
        assertTrue(SearchText.hasWordStartingWith("chrome", "chr"));
        assertFalse(SearchText.hasWordStartingWith("enchrome", "chr"));

        assertTrue(SearchText.acronymMatches("visual studio code", "vsc"));
        assertTrue(SearchText.acronymMatches("visual studio code", "vs"));
        assertFalse(SearchText.acronymMatches("chrome", "vsc"));
    }

    @Test
    void countsPathDepth() {
        assertEquals(3, SearchText.pathDepth("C:\\Users\\me\\file.txt"));
        assertEquals(0, SearchText.pathDepth("file.txt"));
    }
}
