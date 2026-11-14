package dev.lumen.platform;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enumerates the top-level windows the user can actually switch to.
 *
 * <p>"Running apps" is what a launcher is really for: when an app is already open, Enter
 * should bring that window forward instead of starting a second copy. The enumeration is
 * deliberately conservative — visible, titled, non-tool windows that are not our own and
 * are not cloaked by the shell — because a launcher full of invisible UWP hosts is worse
 * than no window results at all.
 *
 * <p>The list is cached for a fraction of a second: a keystroke should not pay for a full
 * {@code EnumWindows} pass plus a process lookup per window, and window sets do not change
 * meaningfully inside that window.
 */
public final class OpenWindows {

    /** One switchable window. {@code processPath} is the executable, when it can be read. */
    public record Window(
            long handle,
            String title,
            String process,
            String processPath,
            boolean minimized) {
    }

    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TOOLWINDOW = 0x0000_0080;
    private static final int DWMWA_CLOAKED = 14;

    private static final long CACHE_MS = 700;
    private static final int MAX_PROCESS_CACHE = 512;
    private static final int MAX_HANDLE_CACHE = 512;

    /** pid → executable path; a process lookup per window per keystroke is wasted work. */
    private static final Map<Long, String> EXE_BY_PID = new HashMap<>();
    /** handle → executable path, so a window result can draw the app's icon. */
    private static final Map<Long, String> EXE_BY_HANDLE = new HashMap<>();

    private static volatile List<Window> cached = List.of();
    private static volatile long cachedAt;

    private OpenWindows() {
    }

    /** Visible, titled windows in z-order, most recently active first. */
    public static List<Window> list() {
        long now = System.currentTimeMillis();
        if (cachedAt != 0L && now - cachedAt < CACHE_MS) {
            return cached;
        }

        List<Window> windows = new ArrayList<>(32);
        long ownPid = ProcessHandle.current().pid();

        try {
            User32.INSTANCE.EnumWindows((hwnd, data) -> {
                Window window = describe(hwnd, ownPid);
                if (window != null) {
                    windows.add(window);
                }
                return true;
            }, null);
        } catch (RuntimeException e) {
            System.err.println("[windows] enumeration failed: " + e.getMessage());
        }

        cached = List.copyOf(windows);
        cachedAt = now;
        return cached;
    }

    /** Executable path for a handle from the last enumeration, or {@code null}. */
    public static String processPath(long handle) {
        if (handle == 0L) {
            return null;
        }
        synchronized (EXE_BY_HANDLE) {
            return EXE_BY_HANDLE.get(handle);
        }
    }

    /** Switches to an existing window, restoring it first when it is minimized. */
    public static boolean focus(long handle) {
        return WindowFocus.claimHandle(handle);
    }

    private static Window describe(HWND hwnd, long ownPid) {
        User32 user32 = User32.INSTANCE;
        try {
            if (!user32.IsWindowVisible(hwnd)) {
                return null;
            }
            if ((user32.GetWindowLong(hwnd, GWL_EXSTYLE) & WS_EX_TOOLWINDOW) != 0) {
                return null;
            }
            if (isCloaked(hwnd)) {
                return null;
            }

            String title = titleOf(hwnd);
            if (title.isBlank()) {
                return null;
            }

            IntByReference pidRef = new IntByReference();
            user32.GetWindowThreadProcessId(hwnd, pidRef);
            long pid = Integer.toUnsignedLong(pidRef.getValue());
            if (pid == 0L || pid == ownPid) {
                return null;
            }

            String exePath = exePath(pid);
            String process = processName(exePath);
            if (title.equalsIgnoreCase("Program Manager") && "explorer".equalsIgnoreCase(process)) {
                return null; // the desktop itself
            }

            long handle = Pointer.nativeValue(hwnd.getPointer());
            synchronized (EXE_BY_HANDLE) {
                if (EXE_BY_HANDLE.size() > MAX_HANDLE_CACHE) {
                    EXE_BY_HANDLE.clear();
                }
                EXE_BY_HANDLE.put(handle, exePath);
            }
            return new Window(handle, title, process, exePath,
                    Win32Ext.User32Ex.INSTANCE.IsIconic(hwnd));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String titleOf(HWND hwnd) {
        int length = User32.INSTANCE.GetWindowTextLength(hwnd);
        if (length <= 0) {
            return "";
        }
        char[] buffer = new char[length + 1];
        int copied = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
        return copied <= 0 ? "" : new String(buffer, 0, copied).trim();
    }

    private static String exePath(long pid) {
        synchronized (EXE_BY_PID) {
            String known = EXE_BY_PID.get(pid);
            if (known != null) {
                return known;
            }
        }

        String path = "";
        try {
            path = ProcessHandle.of(pid)
                    .flatMap(process -> process.info().command())
                    .orElse("");
        } catch (RuntimeException e) {
            // Protected or already-exited process; the title still makes a usable result.
        }

        synchronized (EXE_BY_PID) {
            if (EXE_BY_PID.size() > MAX_PROCESS_CACHE) {
                EXE_BY_PID.clear();
            }
            EXE_BY_PID.put(pid, path);
        }
        return path;
    }

    private static String processName(String exePath) {
        if (exePath == null || exePath.isBlank()) {
            return "";
        }
        String name;
        try {
            name = Path.of(exePath).getFileName().toString();
        } catch (RuntimeException e) {
            return "";
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static boolean isCloaked(HWND hwnd) {
        try {
            IntByReference cloaked = new IntByReference(0);
            int hr = Dwmapi.INSTANCE.DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, cloaked, 4);
            return hr == 0 && cloaked.getValue() != 0;
        } catch (Throwable t) {
            // Older Windows or a missing dwmapi: the visible/titled checks are enough.
            return false;
        }
    }

    /** Minimal dwmapi binding; JNA does not ship DwmGetWindowAttribute. */
    private interface Dwmapi extends StdCallLibrary {
        Dwmapi INSTANCE = Native.load("dwmapi", Dwmapi.class, W32APIOptions.DEFAULT_OPTIONS);

        int DwmGetWindowAttribute(HWND hwnd, int attribute, IntByReference value, int size);
    }
}
