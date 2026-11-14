package dev.lumen.providers;

import dev.lumen.config.Config;
import dev.lumen.core.Hit;
import dev.lumen.core.IndexItem;
import dev.lumen.core.IndexStore;
import dev.lumen.core.SearchEngine;
import dev.lumen.core.UsageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexProviderTest {

    @TempDir
    Path temp;

    private SearchEngine engine;

    @BeforeEach
    void setUp() {
        IndexStore store = new IndexStore(temp.resolve("index.json"));
        store.replace(List.of(
                IndexItem.app("Visual Studio Code", "C:\\Apps\\Code.exe"),
                IndexItem.app("Chrome", "C:\\Apps\\Chrome.exe"),
                IndexItem.app("Google Chrome", "C:\\Apps\\GoogleChrome.exe"),
                IndexItem.file("Quarterly Report", "C:\\Users\\me\\Documents\\report.pdf", "Documents"),
                IndexItem.file("Budget", "C:\\Users\\me\\Documents\\budget.docx", "Documents"),
                IndexItem.file("draft notes", "C:\\Users\\me\\Documents\\draft notes.txt", "Documents"),
                IndexItem.folder("Projects", "C:\\Users\\me\\Documents\\Projects", "Documents")
        ));

        UsageStore usage = new UsageStore(temp.resolve("usage.json"));
        Config config = new Config();
        IndexProvider index = new IndexProvider(store, usage, config);
        engine = new SearchEngine(List.of(index), config);
    }

    @Test
    void exactNameBeatsWordPrefix() {
        List<Hit> hits = engine.search("chrome");

        assertFalse(hits.isEmpty());
        assertEquals("Chrome", hits.get(0).item().title());
        assertEquals("Google Chrome", hits.get(1).item().title());
    }

    @Test
    void acronymFindsMultiWordApps() {
        List<Hit> hits = engine.search("vsc");

        assertFalse(hits.isEmpty());
        assertEquals("Visual Studio Code", hits.get(0).item().title());
    }

    @Test
    void filtersNarrowResults() {
        List<Hit> pdf = engine.search("report ext:pdf");
        assertEquals(1, pdf.size());
        assertEquals("Quarterly Report", pdf.get(0).item().title());

        List<Hit> folders = engine.search("kind:folder");
        assertEquals(1, folders.size());
        assertEquals("Projects", folders.get(0).item().title());

        List<Hit> documents = engine.search("in:documents report");
        assertEquals(1, documents.size());
        assertEquals("Quarterly Report", documents.get(0).item().title());
    }

    @Test
    void exclusionsRemoveNoise() {
        List<Hit> hits = engine.search("-draft notes");

        assertTrue(hits.stream().noneMatch(hit -> hit.item().title().equals("draft notes")));
    }

    @Test
    void aliasesRouteAndImplyKinds() {
        List<Hit> apps = engine.search("apps chrome");
        assertEquals(2, apps.size());
        assertTrue(apps.stream().allMatch(hit -> hit.item().kind() == dev.lumen.core.ItemKind.APP));

        List<Hit> files = engine.search("files report");
        assertEquals(1, files.size());
        assertEquals("Quarterly Report", files.get(0).item().title());

        List<Hit> folders = engine.search("folders");
        assertEquals(1, folders.size());
        assertEquals("Projects", folders.get(0).item().title());
    }
}
