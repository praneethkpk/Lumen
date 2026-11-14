package dev.lumen.platform;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * System-wide hotkey via {@code RegisterHotKey}, with a dedicated message pump thread.
 *
 * <p>Chosen over a low-level keyboard hook: no elevation surprises, no per-keystroke
 * overhead, and Windows itself does the matching.
 *
 * <p>Registration is supervised. Windows can drop or refuse a registration after a
 * sleep/resume, an RDP reconnect or a user switch, and another app may own the key when
 * Lumen starts and release it later. A watchdog therefore re-runs registration and
 * publishes its state, so a dead shortcut is always visible instead of silent.
 */
public final class HotkeyManager {

    private static final int PRIMARY_ID = 0xB00B;
    private static final int BACKUP_ID = 0xB00C;
    private static final String BACKUP_COMBO = "CTRL+ALT+SPACE";
    private static final int WM_HOTKEY = 0x0312;
    private static final int WM_QUIT = 0x0012;
    private static final int WM_APP = 0x8000;
    /** Thread message: unregister and register the pair again. */
    private static final int WM_REREGISTER = WM_APP + 1;

    /** How often the watchdog looks at the registration, in milliseconds. */
    private static final long SUPERVISOR_TICK_MS = 2_000;
    /** Retry interval for reclaiming the primary key while a backup is carrying the load. */
    private static final long PRIMARY_RETRY_MS = 15_000;
    /** A wall-clock jump larger than this means the machine slept or the thread stalled. */
    private static final long RESUME_GAP_MS = 8_000;

    public record Status(boolean registered, boolean primary, String label, String detail) {
    }

    private static volatile Status status = new Status(false, false, "", "starting");
    private static volatile Consumer<Status> statusListener = ignored -> {
    };
    private static volatile int pumpThreadId;
    private static volatile boolean stopped;
    private static boolean readySent;

    private HotkeyManager() {
    }

    /** Kept for callers that only want the boolean. Prefer {@link #currentStatus()}. */
    public static boolean isRegistered() {
        return status.registered();
    }

    public static String activeSpec() {
        return status.label();
    }

    public static Status currentStatus() {
        return status;
    }

    /**
     * Receives every registration transition, including the first one, so the tray can show
     * whether the hotkey is live without polling.
     */
    public static void setStatusListener(Consumer<Status> listener) {
        statusListener = listener == null ? ignored -> {
        } : listener;
        listenerSafely(status);
    }

    /** Installs the hotkey. The callback runs on the JavaFX thread. */
    public static void install(String spec, Runnable callback) {
        install(spec, callback, label -> {
        });
    }

    /**
     * Installs the hotkey.
     *
     * @param callback runs on the JavaFX thread when the hotkey is pressed
     * @param onReady  receives the effective key combination once, or {@code null} when no
     *                 hotkey could be registered (another app owns it)
     */
    public static void install(String spec, Runnable callback, Consumer<String> onReady) {
        Spec primary = Spec.parse(spec);
        stopped = false;
        readySent = false;
        Thread pump = new Thread(() -> pump(primary, spec, callback, onReady), "global-hotkey");
        pump.setDaemon(true);
        pump.start();

        Thread supervisor = new Thread(HotkeyManager::supervise, "hotkey-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    private static void pump(Spec primary, String specText, Runnable callback, Consumer<String> onReady) {
        pumpThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
        User32 user32 = User32.INSTANCE;
        Spec backup = Spec.parse(BACKUP_COMBO);

        while (!stopped) {
            Registration registration = register(user32, primary, backup, specText);
            publish(registration);
            if (!readySent) {
                readySent = true;
                String effective = registration.primaryOk() ? primary.label()
                        : registration.backupOk() ? backup.label() : null;
                notifyReady(onReady, effective);
            }

            // Pump until the watchdog asks for a re-registration or the app quits.
            boolean reregister = false;
            WinUser.MSG message = new WinUser.MSG();
            while (!reregister && !stopped) {
                int result = user32.GetMessage(message, null, 0, 0);
                if (result == 0) {
                    // WM_QUIT
                    unregister(user32);
                    pumpThreadId = 0;
                    return;
                }
                if (result == -1) {
                    // Transient pump error: keep serving the hotkey rather than dying silently.
                    System.out.println("[hotkey] message pump error " + Native.getLastError()
                            + "; retrying");
                    sleepQuietly(500);
                    continue;
                }
                if (message.message == WM_REREGISTER) {
                    reregister = true;
                    continue;
                }
                if (message.message == WM_HOTKEY) {
                    int id = message.wParam.intValue();
                    System.out.println("[hotkey] pressed "
                            + (id == BACKUP_ID ? backup.label() : status.label()));
                    javafx.application.Platform.runLater(callback);
                }
                user32.TranslateMessage(message);
                user32.DispatchMessage(message);
            }

            unregister(user32);
        }

        unregister(user32);
        pumpThreadId = 0;
    }

    private static Registration register(User32 user32, Spec primary, Spec backup, String specText) {
        // Unregistering first makes the retry path idempotent: RegisterHotKey fails with
        // "already registered" for a binding we still own.
        unregister(user32);

        boolean primaryOk = user32.RegisterHotKey(null, PRIMARY_ID, primary.mods(), primary.vk());
        int primaryError = primaryOk ? 0 : Native.getLastError();
        boolean backupOk = !sameBinding(primary, backup)
                && user32.RegisterHotKey(null, BACKUP_ID, backup.mods(), backup.vk());
        return new Registration(primaryOk, backupOk, primary, backup, primaryError, specText);
    }

    private static void publish(Registration registration) {
        Status previous = status;
        boolean registered = registration.primaryOk() || registration.backupOk();
        String label = registration.primaryOk() ? registration.primary().label()
                : registration.backupOk() ? registration.backup().label() : "";
        String detail;
        if (!registered) {
            detail = "Hotkey unavailable";
        } else if (registration.primaryOk()) {
            detail = "Hotkey: " + label;
        } else {
            detail = "Hotkey: " + label + " (fallback; " + registration.specText() + " is taken)";
        }

        Status next = new Status(registered, registration.primaryOk(), label, detail);
        status = next;
        listenerSafely(next);

        boolean changed = previous.registered() != next.registered()
                || previous.primary() != next.primary()
                || !previous.label().equals(next.label());
        if (!changed) {
            return;
        }

        if (!registered) {
            System.err.println("[hotkey] could not register " + registration.specText()
                    + " or " + BACKUP_COMBO + " (win32 error " + registration.primaryError()
                    + "); open the search bar from the tray icon");
        } else if (registration.primaryOk()) {
            System.out.println("[hotkey] registered " + label);
        } else {
            System.out.println("[hotkey] " + registration.specText()
                    + " is owned by another app; using " + BACKUP_COMBO);
        }
    }

    private static void supervise() {
        long lastTick = System.currentTimeMillis();
        long lastPrimaryAttempt = 0;
        while (!stopped) {
            sleepQuietly(SUPERVISOR_TICK_MS);
            if (stopped || pumpThreadId == 0) {
                continue;
            }

            long now = System.currentTimeMillis();
            long gap = now - lastTick;
            lastTick = now;

            if (gap > RESUME_GAP_MS) {
                // Sleep/resume, RDP reconnect or a stalled thread. Registrations are cheap
                // to rebuild, so assume they are stale rather than waiting for a user report.
                System.out.println("[hotkey] resume detected (" + gap + " ms gap); re-registering");
                postReregister();
                continue;
            }

            if (!status.registered()) {
                postReregister();
                continue;
            }

            if (!status.primary() && now - lastPrimaryAttempt >= PRIMARY_RETRY_MS) {
                // The app that owned the configured key may have exited since startup.
                lastPrimaryAttempt = now;
                postReregister();
            }
        }
    }

    private static void postReregister() {
        int threadId = pumpThreadId;
        if (threadId == 0) {
            return;
        }
        User32.INSTANCE.PostThreadMessage(threadId, WM_REREGISTER,
                new WinDef.WPARAM(0), new WinDef.LPARAM(0));
    }

    private static void listenerSafely(Status next) {
        try {
            statusListener.accept(next);
        } catch (RuntimeException e) {
            System.err.println("[hotkey] status listener failed: " + e);
        }
    }

    private static void unregister(User32 user32) {
        user32.UnregisterHotKey(null, PRIMARY_ID);
        user32.UnregisterHotKey(null, BACKUP_ID);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean sameBinding(Spec a, Spec b) {
        return a.mods() == b.mods() && a.vk() == b.vk();
    }

    private static void notifyReady(Consumer<String> onReady, String label) {
        try {
            javafx.application.Platform.runLater(() -> onReady.accept(label));
        } catch (IllegalStateException e) {
            onReady.accept(label);
        }
    }

    public static void uninstall() {
        stopped = true;
        if (pumpThreadId != 0) {
            User32.INSTANCE.PostThreadMessage(pumpThreadId, WM_QUIT,
                    new WinDef.WPARAM(0), new WinDef.LPARAM(0));
        }
    }

    private record Registration(
            boolean primaryOk,
            boolean backupOk,
            Spec primary,
            Spec backup,
            int primaryError,
            String specText) {
    }

    private record Spec(int mods, int vk) {

        private static final int MOD_ALT = 0x0001;
        private static final int MOD_CONTROL = 0x0002;
        private static final int MOD_SHIFT = 0x0004;
        private static final int MOD_WIN = 0x0008;

        static Spec parse(String raw) {
            int mods = 0;
            int vk = 0x20; // SPACE
            for (String part : raw.toUpperCase(Locale.ROOT).split("\\+")) {
                switch (part.trim()) {
                    case "ALT" -> mods |= MOD_ALT;
                    case "CTRL", "CONTROL" -> mods |= MOD_CONTROL;
                    case "SHIFT" -> mods |= MOD_SHIFT;
                    case "WIN", "META" -> mods |= MOD_WIN;
                    default -> vk = virtualKey(part.trim());
                }
            }
            return new Spec(mods, vk);
        }

        private static int virtualKey(String key) {
            if (key.length() == 1) {
                char c = key.charAt(0);
                return Character.isLetterOrDigit(c) ? Character.toUpperCase(c) : 0x20;
            }
            return switch (key) {
                case "SPACE" -> 0x20;
                case "ENTER", "RETURN" -> 0x0D;
                case "TAB" -> 0x09;
                case "ESC", "ESCAPE" -> 0x1B;
                case "BACKTICK", "`" -> 0xC0;
                default -> {
                    if (key.startsWith("F") && key.length() <= 3) {
                        try {
                            int n = Integer.parseInt(key.substring(1));
                            if (n >= 1 && n <= 24) {
                                yield 0x70 + n - 1;
                            }
                        } catch (NumberFormatException ignored) {
                            // fall through
                        }
                    }
                    yield 0x20;
                }
            };
        }

        String label() {
            StringBuilder sb = new StringBuilder();
            if ((mods & MOD_CONTROL) != 0) {
                sb.append("Ctrl+");
            }
            if ((mods & MOD_ALT) != 0) {
                sb.append("Alt+");
            }
            if ((mods & MOD_SHIFT) != 0) {
                sb.append("Shift+");
            }
            if ((mods & MOD_WIN) != 0) {
                sb.append("Win+");
            }
            sb.append(keyName(vk));
            return sb.toString();
        }

        private static String keyName(int vk) {
            if (vk >= 0x41 && vk <= 0x5A) {
                return String.valueOf((char) vk);
            }
            if (vk >= 0x30 && vk <= 0x39) {
                return String.valueOf((char) vk);
            }
            if (vk >= 0x70 && vk <= 0x87) {
                return "F" + (vk - 0x70 + 1);
            }
            return switch (vk) {
                case 0x20 -> "Space";
                case 0x0D -> "Enter";
                case 0x09 -> "Tab";
                case 0x1B -> "Esc";
                case 0xC0 -> "`";
                default -> "VK_" + vk;
            };
        }
    }
}
