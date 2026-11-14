package dev.lumen.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ShellLauncherTest {

    @Test
    void blankTargetsAreRejectedBeforeTheShellIsTouched() {
        assertFalse(ShellLauncher.open(null));
        assertFalse(ShellLauncher.open(""));
        assertFalse(ShellLauncher.open("   "));
        assertFalse(ShellLauncher.openAsAdmin(null));
        assertFalse(ShellLauncher.openAsAdmin("   "));
        assertFalse(ShellLauncher.reveal(""));
    }
}
