package dev.lumen.platform;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;

/**
 * Forces the overlay to the foreground.
 *
 * <p>{@code Stage.show()} normally activates a window, but Windows can refuse foreground
 * rights when the app that had focus is elevated or when the foreground lock is held. The
 * attach/SetForegroundWindow dance is the standard workaround; without it the overlay can
 * appear behind the current window and look like it never opened.
 */
public final class WindowFocus {

    /** Title set on the overlay stage; used to locate its native window. */
    private static final String WINDOW_TITLE = "Lumen";

    private WindowFocus() {
    }

    public static boolean claim() {
        HWND target = overlayHandle();
        return target != null && raise(target);
    }

    /**
     * Brings a specific window to the foreground, restoring it first if it is minimized.
     *
     * <p>Used when a search result is an already-running window: switching to it is what the
     * user meant, and launching a second copy of the app would be wrong.
     */
    public static boolean claimHandle(long handle) {
        if (handle == 0L) {
            return false;
        }
        User32 user32 = User32.INSTANCE;
        HWND target = new HWND(Pointer.createConstant(handle));
        if (!user32.IsWindow(target)) {
            return false;
        }
        if (Win32Ext.User32Ex.INSTANCE.IsIconic(target)) {
            user32.ShowWindow(target, WinUser.SW_RESTORE);
        }
        return raise(target);
    }

    /**
     * The attach/SetForegroundWindow dance. Windows refuses foreground rights to a process
     * that does not own the current foreground window; briefly sharing the input queue with
     * that thread lifts the restriction.
     */
    private static boolean raise(HWND target) {
        User32 user32 = User32.INSTANCE;
        HWND foreground = user32.GetForegroundWindow();
        int foregroundThread = foreground == null ? 0 : user32.GetWindowThreadProcessId(foreground, null);
        int ownThread = Kernel32.INSTANCE.GetCurrentThreadId();
        boolean attached = false;

        try {
            if (foregroundThread != 0 && foregroundThread != ownThread) {
                attached = user32.AttachThreadInput(
                        new WinDef.DWORD(ownThread), new WinDef.DWORD(foregroundThread), true);
            }
            user32.BringWindowToTop(target);
            boolean raised = user32.SetForegroundWindow(target);
            user32.SetFocus(target);
            return raised;
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (attached) {
                user32.AttachThreadInput(
                        new WinDef.DWORD(ownThread), new WinDef.DWORD(foregroundThread), false);
            }
        }
    }

    private static HWND overlayHandle() {
        return findOwnWindow();
    }

    /**
     * Finds the overlay window by its exact title, restricted to this process so a stray
     * window from another app called "Lumen" can never be mistaken for the overlay.
     */
    private static HWND findOwnWindow() {
        final HWND[] result = { null };
        int ownPid = Kernel32.INSTANCE.GetCurrentProcessId();
        try {
            User32.INSTANCE.EnumWindows((hwnd, data) -> {
                com.sun.jna.ptr.IntByReference pidRef = new com.sun.jna.ptr.IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
                if (pidRef.getValue() != ownPid) {
                    return true;
                }
                char[] buffer = new char[256];
                int length = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
                if (length > 0 && new String(buffer, 0, length).equals(WINDOW_TITLE)) {
                    result[0] = hwnd;
                    return false;
                }
                return true;
            }, null);
        } catch (RuntimeException e) {
            return null;
        }
        HWND target = result[0];
        return target == null || Pointer.nativeValue(target.getPointer()) == 0L ? null : target;
    }

    /**
     * Title of the window that actually owns the foreground. Logged on focus loss so a
     * "the hotkey did nothing" report can be traced to the app that stole activation.
     */
    public static String foregroundTitle() {
        try {
            HWND foreground = User32.INSTANCE.GetForegroundWindow();
            if (foreground == null) {
                return "";
            }
            char[] buffer = new char[512];
            int length = User32.INSTANCE.GetWindowText(foreground, buffer, buffer.length);
            return length <= 0 ? "" : new String(buffer, 0, length);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Whether Windows itself considers the overlay the foreground window.
     *
     * <p>JavaFX's {@code stage.isFocused()} is optimistic: right after {@code show()} it
     * reports true even when Windows refused activation, which then leaves the overlay
     * visible but unable to receive typing or release focus. Every decision about
     * activation must use this native check instead.
     */
    public static boolean isForeground() {
        try {
            User32 user32 = User32.INSTANCE;
            HWND target = findOwnWindow();
            HWND foreground = user32.GetForegroundWindow();
            return target != null && foreground != null
                    && Pointer.nativeValue(target.getPointer()) != 0L
                    && Pointer.nativeValue(target.getPointer())
                    == Pointer.nativeValue(foreground.getPointer());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
