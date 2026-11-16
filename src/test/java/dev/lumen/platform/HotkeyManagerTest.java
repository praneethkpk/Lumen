package dev.lumen.platform;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotkeyManagerTest {

    private static final int WM_QUIT = 0x0012;
    /** Nothing else on a normal desktop wants this one, so the test can register for real. */
    private static final String TEST_SPEC = "CTRL+ALT+SHIFT+F24";

    @AfterEach
    void releaseHotkey() {
        HotkeyManager.uninstall();
    }

    @Test
    void candidatesStartWithTheConfiguredKeyAndStayUnique() {
        List<HotkeySpec> specs =
                HotkeyManager.candidates(HotkeySpec.parse("CTRL+ALT+SPACE"));

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
        List<HotkeySpec> specs =
                HotkeyManager.candidates(HotkeySpec.parse("alt+space"));

        assertEquals("Alt+Space", specs.get(0).label());
        assertEquals(4, specs.size());
    }

    @Test
    void parsesModifiersAndFunctionKeys() {
        assertEquals("Alt+Space", HotkeySpec.parse("ALT+SPACE").label());
        assertEquals("Ctrl+Shift+F9", HotkeySpec.parse("ctrl+shift+f9").label());
        assertEquals("Win+Space", HotkeySpec.parse("WIN+space").label());
        assertEquals("Ctrl+Alt+`", HotkeySpec.parse("ctrl+alt+backtick").label());
    }

    @Test
    void rejectsCombinationsThatWouldSwallowAKeySystemWide() {
        assertTrue(HotkeySpec.isValid("ALT+SPACE"));
        assertTrue(HotkeySpec.isValid("ctrl+shift+f9"));
        assertTrue(HotkeySpec.isValid("WIN+`"));

        assertFalse(HotkeySpec.isValid("SPACE"), "a bare key hijacks it everywhere");
        assertFalse(HotkeySpec.isValid("ALT+NONSENSE"), "unknown key names");
        assertFalse(HotkeySpec.isValid("ALT+F25"), "function keys stop at F24");
        assertFalse(HotkeySpec.isValid("ALT+"), "a modifier alone is not a hotkey");
        assertFalse(HotkeySpec.isValid(""), "empty");
        assertFalse(HotkeySpec.isValid(null), "null");
    }

    /**
     * Kill the message pump the way a crash or a Windows shutdown of the queue would, and
     * require the shortcut to come back on its own.
     *
     * <p>{@code WM_QUIT} is used rather than {@code Thread.stop} because it is exactly what the
     * pump sees when Windows tears its queue down: {@code GetMessage} returns 0 and the thread
     * exits. That is the failure the audit was about — the thread is gone, so nothing is
     * pumping, but the registration may still look alive to everyone else.
     */
    @Test
    void recoversWhenThePumpThreadDies() {
        Assumptions.assumeTrue(isWindows(), "RegisterHotKey only exists on Windows");

        HotkeyManager.install(TEST_SPEC, () -> {
        }, label -> {
        });

        assertTrue(awaitTrue(() -> HotkeyManager.pumpThreadIdForTest() != 0, 5_000),
                "the pump never started");
        int firstGeneration = HotkeyManager.generationForTest();
        int firstThread = HotkeyManager.pumpThreadIdForTest();
        boolean registeredBefore = awaitTrue(
                () -> HotkeyManager.currentStatus().registered(), 5_000);

        // The supervisor's proof of life must come from the pump thread itself.
        assertTrue(awaitTrue(HotkeyManager::pumpRespondsForTest, 3_000),
                "the liveness probe was not answered by the pump thread");

        User32.INSTANCE.PostThreadMessage(firstThread, WM_QUIT,
                new WPARAM(0), new LPARAM(0));

        assertTrue(awaitTrue(
                        () -> HotkeyManager.generationForTest() > firstGeneration, 5_000),
                "the supervisor never noticed the dead pump");
        assertTrue(awaitTrue(
                        () -> HotkeyManager.pumpThreadIdForTest() != 0
                                && HotkeyManager.pumpThreadIdForTest() != firstThread, 5_000),
                "no new pump thread was started");
        assertTrue(awaitTrue(HotkeyManager::pumpRespondsForTest, 5_000),
                "the replacement pump does not answer either");
        assertEquals(registeredBefore, awaitTrue(
                        () -> HotkeyManager.currentStatus().registered() == registeredBefore,
                        5_000),
                "registration state did not come back");
        if (registeredBefore) {
            assertFalse(HotkeyManager.currentStatus().label().isEmpty(),
                    "a registered hotkey must name its combination");
        }
    }

    /**
     * A callback that throws must never take the pump down with it. The pump owns the only way
     * to summon the app, so an Error escaping a callback would run the pump's {@code finally}
     * and silently unregister the hotkey.
     */
    @Test
    void aThrowingReadyCallbackDoesNotKillTheHotkey() throws InterruptedException {
        Assumptions.assumeTrue(isWindows(), "RegisterHotKey only exists on Windows");

        HotkeyManager.install(TEST_SPEC, () -> {
        }, label -> {
            throw new AssertionError("callback blew up");
        });

        assertTrue(awaitTrue(() -> HotkeyManager.pumpThreadIdForTest() != 0, 5_000),
                "the pump never started");
        assertTrue(awaitTrue(() -> HotkeyManager.currentStatus().registered(), 5_000),
                "the hotkey was not registered");
        int firstThread = HotkeyManager.pumpThreadIdForTest();
        Thread.sleep(1_000);
        assertEquals(firstThread, HotkeyManager.pumpThreadIdForTest(),
                "a throwing callback must not tear down the pump");
        assertTrue(HotkeyManager.currentStatus().registered(),
                "a throwing callback must not unregister the hotkey");
    }

    /** A restart must replace the pump, not accumulate one supervisor per install. */
    @Test
    void reinstallingKeepsASingleSupervisor() {
        Assumptions.assumeTrue(isWindows(), "RegisterHotKey only exists on Windows");

        HotkeyManager.install(TEST_SPEC, () -> {
        }, label -> {
        });
        assertTrue(awaitTrue(() -> HotkeyManager.pumpThreadIdForTest() != 0, 5_000));
        int firstThread = HotkeyManager.pumpThreadIdForTest();

        HotkeyManager.install(TEST_SPEC, () -> {
        }, label -> {
        });

        assertTrue(awaitTrue(
                        () -> HotkeyManager.pumpThreadIdForTest() != 0
                                && HotkeyManager.pumpThreadIdForTest() != firstThread, 5_000),
                "install() did not start a fresh pump");
        long supervisors = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().equals("hotkey-supervisor"))
                .count();
        assertEquals(1, supervisors, "supervisors must not pile up across installs");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean awaitTrue(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
