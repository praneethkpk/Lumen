package dev.lumen.platform;

import com.sun.jna.platform.win32.Shell32;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens things through the Windows shell, so shortcuts, folders, documents and URLs all
 * behave exactly as they do in Explorer.
 */
public final class ShellLauncher {

    private static final int SW_SHOWNORMAL = 1;
    /** ShellExecute's "the user cancelled the UAC prompt" code; not a failure. */
    private static final int ERROR_CANCELLED = 1223;

    private ShellLauncher() {
    }

    public static boolean open(String target) {
        return execute("open", target);
    }

    /**
     * Launches with the {@code runas} verb, which is what raises the UAC prompt. A cancelled
     * prompt counts as success: nothing failed, the user chose not to continue.
     */
    public static boolean openAsAdmin(String target) {
        if (target == null || target.isBlank()) {
            return false;
        }
        try {
            com.sun.jna.platform.win32.WinDef.INT_PTR result =
                    Shell32.INSTANCE.ShellExecute(null, "runas", target, null, null, SW_SHOWNORMAL);
            int code = result.intValue();
            if (code == ERROR_CANCELLED) {
                return true;
            }
            boolean ok = code > 32;
            if (!ok) {
                System.err.println("[shell] could not run " + target
                        + " as administrator (code " + code + ")");
            }
            return ok;
        } catch (Throwable t) {
            System.err.println("[shell] error running " + target + " as administrator: "
                    + t.getMessage());
            return false;
        }
    }

    private static boolean execute(String verb, String target) {
        if (target == null || target.isBlank()) {
            return false;
        }
        try {
            com.sun.jna.platform.win32.WinDef.INT_PTR result =
                    Shell32.INSTANCE.ShellExecute(null, verb, target, null, null, SW_SHOWNORMAL);
            boolean ok = result.intValue() > 32;
            if (!ok) {
                System.err.println("[shell] could not open " + target + " (code " + result.intValue() + ")");
            }
            return ok;
        } catch (Throwable t) {
            System.err.println("[shell] error opening " + target + ": " + t.getMessage());
            return false;
        }
    }

    /** Opens Explorer with the item selected, or opens the folder itself. */
    public static boolean reveal(String target) {
        if (target == null || target.isBlank()) {
            return false;
        }
        try {
            Path path = Path.of(target);
            ProcessBuilder builder = Files.isDirectory(path)
                    ? new ProcessBuilder("explorer.exe", path.toString())
                    : new ProcessBuilder("explorer.exe", "/select," + path);
            builder.start();
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[shell] could not reveal " + target + ": " + e.getMessage());
            return false;
        }
    }
}
