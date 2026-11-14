package dev.lumen.platform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotkeyManagerTest {

    @Test
    void candidatesStartWithTheConfiguredKeyAndStayUnique() {
        List<HotkeyManager.Spec> specs =
                HotkeyManager.candidates(HotkeyManager.Spec.parse("CTRL+ALT+SPACE"));

        assertEquals("Ctrl+Alt+Space", specs.get(0).label());
        assertEquals(3, specs.size(), "configured key, then the two remaining fallbacks");
        assertEquals(specs.size(),
                specs.stream().map(spec -> spec.mods() + ":" + spec.vk()).distinct().count(),
                "duplicate bindings must not be retried");
        assertTrue(specs.stream().anyMatch(spec -> spec.label().equals("Ctrl+Shift+Space")));
        assertTrue(specs.stream().anyMatch(spec -> spec.label().equals("Alt+`")));
    }

    @Test
    void aConfiguredKeyThatIsAlreadyAFallbackDoesNotRepeatIt() {
        List<HotkeyManager.Spec> specs =
                HotkeyManager.candidates(HotkeyManager.Spec.parse("alt+space"));

        assertEquals("Alt+Space", specs.get(0).label());
        assertEquals(4, specs.size());
    }

    @Test
    void parsesModifiersAndFunctionKeys() {
        assertEquals("Alt+Space", HotkeyManager.Spec.parse("ALT+SPACE").label());
        assertEquals("Ctrl+Shift+F9", HotkeyManager.Spec.parse("ctrl+shift+f9").label());
        assertEquals("Win+Space", HotkeyManager.Spec.parse("WIN+space").label());
        assertEquals("Ctrl+Alt+`", HotkeyManager.Spec.parse("ctrl+alt+backtick").label());
    }
}
