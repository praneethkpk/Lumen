package dev.lumen.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchQueryTest {

    @Test
    void parsesFiltersAndExclusions() {
        SearchQuery query = SearchQuery.parse("report ext:pdf -draft");

        assertEquals(java.util.List.of("report"), query.tokens());
        assertEquals(java.util.List.of("pdf"), query.extensions());
        assertEquals(java.util.List.of("draft"), query.exclusions());
        assertTrue(query.hasFilters());
        assertFalse(query.isFilterOnly());
    }

    @Test
    void parsesKindAndPathFilters() {
        SearchQuery query = SearchQuery.parse("kind:folder,file in:downloads setup");

        assertEquals(java.util.List.of("setup"), query.tokens());
        assertEquals(java.util.Set.of(ItemKind.FILE, ItemKind.FOLDER), query.kinds());
        assertEquals(java.util.List.of("downloads"), query.pathHints());
    }

    @Test
    void acceptsLeadingDotAndTypeAlias() {
        SearchQuery query = SearchQuery.parse("type:.PDF");
        assertEquals(java.util.List.of("pdf"), query.extensions());
        assertTrue(query.isFilterOnly());
    }

    @Test
    void parsesWindowKind() {
        assertEquals(java.util.Set.of(ItemKind.WINDOW), SearchQuery.parse("kind:window").kinds());
        assertEquals(java.util.Set.of(ItemKind.WINDOW), SearchQuery.parse("kind:win").kinds());
        assertEquals(java.util.Set.of(ItemKind.WINDOW, ItemKind.FILE),
                SearchQuery.parse("kind:windows,file").kinds());
    }

    @Test
    void dropsTheRoutedAlias() {
        SearchQuery query = SearchQuery.parse("apps chrome").withoutFirstToken();

        assertEquals(java.util.List.of("chrome"), query.tokens());
        assertEquals("chrome", query.raw());
    }

    @Test
    void filtersEntriesByExtensionKindPathAndExclusion() {
        IndexEntry report = IndexEntry.of(IndexItem.file(
                "Quarterly Report", "C:\\Users\\me\\Documents\\report.pdf", "Documents"));
        IndexEntry draft = IndexEntry.of(IndexItem.file(
                "draft notes", "C:\\Users\\me\\Documents\\draft notes.txt", "Documents"));
        IndexEntry app = IndexEntry.of(IndexItem.app(
                "Report Viewer", "C:\\Apps\\viewer.exe"));

        assertTrue(SearchQuery.parse("ext:pdf").accepts(report));
        assertFalse(SearchQuery.parse("ext:docx").accepts(report));
        assertTrue(SearchQuery.parse("in:documents").accepts(report));
        assertFalse(SearchQuery.parse("in:downloads").accepts(report));
        assertFalse(SearchQuery.parse("-draft").accepts(draft));
        assertTrue(SearchQuery.parse("-draft").accepts(report));
        assertTrue(SearchQuery.parse("kind:app").accepts(app));
        assertFalse(SearchQuery.parse("kind:file").accepts(app));
    }
}
