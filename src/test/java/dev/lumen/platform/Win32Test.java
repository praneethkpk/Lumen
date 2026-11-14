package dev.lumen.platform;

import javafx.scene.image.Image;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These exercise the real Win32 bindings, not mocks: SHGetFileInfo/DrawIconEx and
 * EnumWindows are exactly where a wrong structure field or calling convention shows up.
 */
class Win32Test {

    @Test
    void extractsARealShellIcon() {
        Path notepad = Path.of(System.getenv("SystemRoot"), "System32", "notepad.exe");
        assertTrue(Files.isRegularFile(notepad), "notepad.exe should exist on Windows");

        Image image = ShellIcons.extract(notepad.toString());

        assertNotNull(image);
        assertTrue(image.getWidth() >= 16, "icon width");
        assertTrue(image.getHeight() >= 16, "icon height");
    }

    @Test
    void enumeratesOnlyTitledWindows() {
        var windows = OpenWindows.list();

        assertNotNull(windows);
        assertTrue(windows.stream().noneMatch(window -> window.title().isBlank()));
        assertTrue(windows.stream().noneMatch(window -> window.handle() == 0L));
    }
}
