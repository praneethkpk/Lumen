package dev.lumen.platform;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary.StdCallCallback;

import java.util.function.Consumer;

/**
 * A hidden top-level window that receives the broadcasts a launcher must react to.
 *
 * <p>{@code WM_POWERBROADCAST}, {@code WM_WTSSESSION_CHANGE} and {@code WM_DISPLAYCHANGE} are
 * sent to windows, not to threads, and message-only windows do not receive broadcasts at all.
 * A never-shown {@code WS_POPUP} window is the smallest thing that does, and creating it on the
 * hotkey pump thread means one message loop serves both the shortcut and the lifecycle events.
 *
 * <p>Session notifications additionally need {@code WTSRegisterSessionNotification}; the other
 * two broadcasts arrive without registration.
 */
final class LifecycleWindow {

    /** What happened, reduced to the three cases the app acts on. */
    enum Event {
        /** The machine woke up, or a session was unlocked or reconnected. */
        RESUME,
        /** A display was added, removed or its DPI changed. */
        DISPLAY
    }

    private static final String CLASS_NAME = "LumenLifecycle";

    private static final int WS_POPUP = 0x8000_0000;
    private static final int WS_EX_TOOLWINDOW = 0x0000_0080;

    private static final int WM_DESTROY = 0x0002;
    private static final int WM_DISPLAYCHANGE = 0x007E;
    private static final int WM_POWERBROADCAST = 0x0218;
    private static final int WM_WTSSESSION_CHANGE = 0x02B1;

    private static final int PBT_APMRESUMECRITICAL = 0x0006;
    private static final int PBT_APMRESUMESUSPEND = 0x0007;
    private static final int PBT_APMRESUMEAUTOMATIC = 0x0012;

    private static final int WTS_CONSOLE_CONNECT = 0x1;
    private static final int WTS_REMOTE_CONNECT = 0x3;
    private static final int WTS_SESSION_UNLOCK = 0x8;

    private static final int NOTIFY_FOR_THIS_SESSION = 0;

    /** The callback must be strongly reachable or the JVM may collect it under Win32. */
    private static WndProc proc;
    private static Consumer<Event> listener = event -> {
    };

    private LifecycleWindow() {
    }

    /**
     * Creates the window. Events are delivered on the thread that pumps this window's
     * messages, so call this from the pump thread before its message loop starts.
     *
     * @return the window handle, or {@code 0} when creation failed
     */
    static long create(Consumer<Event> onEvent) {
        listener = onEvent == null ? event -> {
        } : onEvent;
        if (proc == null) {
            proc = LifecycleWindow::handle;
        }

        WinUser.WNDCLASSEX wc = new WinUser.WNDCLASSEX();
        wc.cbSize = wc.size();
        wc.lpfnWndProc = proc;
        wc.hInstance = Kernel32.INSTANCE.GetModuleHandle(null);
        wc.lpszClassName = CLASS_NAME;
        wc.write();
        // ERROR_CLASS_ALREADY_EXISTS is fine: the class survives a relaunch in the same process.
        User32.INSTANCE.RegisterClassEx(wc);

        HWND handle = User32.INSTANCE.CreateWindowEx(
                WS_EX_TOOLWINDOW, CLASS_NAME, "Lumen lifecycle", WS_POPUP,
                0, 0, 0, 0, null, null, wc.hInstance, null);
        if (handle == null) {
            System.err.println("[lifecycle] could not create the event window");
            return 0L;
        }

        boolean wts = Win32Ext.Wtsapi32.INSTANCE
                .WTSRegisterSessionNotification(handle, NOTIFY_FOR_THIS_SESSION);
        if (!wts) {
            // Power and display broadcasts still arrive; only lock/unlock feedback is lost.
            System.err.println("[lifecycle] session notifications unavailable ("
                    + com.sun.jna.Native.getLastError() + ")");
        }
        return Pointer.nativeValue(handle.getPointer());
    }

    /** Destroys the window. Safe to call when it was never created. */
    static void destroy(long handle) {
        if (handle == 0L) {
            return;
        }
        HWND hwnd = new HWND(Pointer.createConstant(handle));
        Win32Ext.Wtsapi32.INSTANCE.WTSUnRegisterSessionNotification(hwnd);
        User32.INSTANCE.DestroyWindow(hwnd);
    }

    private static LRESULT handle(HWND hwnd, int message, WPARAM wParam, LPARAM lParam) {
        switch (message) {
            case WM_POWERBROADCAST -> {
                int event = wParam.intValue();
                if (event == PBT_APMRESUMEAUTOMATIC
                        || event == PBT_APMRESUMESUSPEND
                        || event == PBT_APMRESUMECRITICAL) {
                    emit(Event.RESUME);
                }
            }
            case WM_WTSSESSION_CHANGE -> {
                int event = wParam.intValue();
                if (event == WTS_SESSION_UNLOCK
                        || event == WTS_CONSOLE_CONNECT
                        || event == WTS_REMOTE_CONNECT) {
                    emit(Event.RESUME);
                }
            }
            case WM_DISPLAYCHANGE -> emit(Event.DISPLAY);
            case WM_DESTROY -> {
                // The pump loop owns shutdown; nothing to clean up here.
            }
            default -> {
            }
        }
        return User32.INSTANCE.DefWindowProc(hwnd, message, wParam, lParam);
    }

    private static void emit(Event event) {
        try {
            listener.accept(event);
        } catch (RuntimeException e) {
            System.err.println("[lifecycle] handler failed: " + e);
        }
    }

    /** Delivers a lifecycle event to the app. Kept separate for the native signature. */
    interface WndProc extends StdCallCallback {
        LRESULT callback(HWND hwnd, int message, WPARAM wParam, LPARAM lParam);
    }
}
