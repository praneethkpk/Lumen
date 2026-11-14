package dev.lumen.providers;

import dev.lumen.config.Config;
import dev.lumen.core.Hit;
import dev.lumen.core.ItemKind;
import dev.lumen.core.SearchEngine;
import dev.lumen.platform.OpenWindows;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowProviderTest {

    private static final List<OpenWindows.Window> WINDOWS = List.of(
            new OpenWindows.Window(101, "README.md - Visual Studio Code", "Code",
                    "C:\\Apps\\Code.exe", false),
            new OpenWindows.Window(102, "Inbox - Outlook", "OUTLOOK",
                    "C:\\Apps\\OUTLOOK.EXE", false),
            new OpenWindows.Window(103, "Quarterly report - Google Chrome", "chrome",
                    "C:\\Apps\\chrome.exe", true),
            new OpenWindows.Window(104, "Untitled - Notepad", "notepad",
                    "C:\\Windows\\notepad.exe", false));

    private static SearchEngine engine(List<OpenWindows.Window> windows) {
        return new SearchEngine(List.of(new WindowProvider(() -> windows)), new Config());
    }

    @Test
    void findsWindowsByTitleAndProcess() {
        List<Hit> hits = engine(WINDOWS).search("code");

        assertFalse(hits.isEmpty());
        Hit first = hits.get(0);
        assertEquals("README.md - Visual Studio Code", first.item().title());
        assertEquals(ItemKind.WINDOW, first.item().kind());
        assertEquals("101", first.item().path());

        assertFalse(engine(WINDOWS).search("outlook").isEmpty());
    }

    @Test
    void aliasRoutesToWindowsAndReturnsSwitchTargets() {
        List<Hit> hits = engine(WINDOWS).search("win report");

        assertEquals(1, hits.size());
        assertEquals("Quarterly report - Google Chrome", hits.get(0).item().title());
        assertTrue(hits.get(0).item().subtitle().contains("running"));
    }

    @Test
    void bareAliasListsEveryWindow() {
        assertEquals(WINDOWS.size(), engine(WINDOWS).search("win").size());
    }

    @Test
    void otherKindFiltersExcludeWindows() {
        assertTrue(engine(WINDOWS).search("kind:folder code").isEmpty());
        assertFalse(engine(WINDOWS).search("kind:window code").isEmpty());
    }

    @Test
    void shortQueriesAndEmptySourcesAreIgnored() {
        assertTrue(engine(List.of()).search("code").isEmpty());
        assertTrue(engine(WINDOWS).search("c").isEmpty());
    }
}
