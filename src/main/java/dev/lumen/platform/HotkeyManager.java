package dev.lumen.platform;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * System-wide hotkey via {@code RegisterHotKey}, with a dedicated message pump thread.
 *
 * <p>Chosen over a low-level keyboard hook: no elevation surprises, no per-keystroke
 * overhead, and Windows itself does the matching.
 *
 * <p>Registration is supervised and always ends with a working shortcut when one is
 * available. Windows can drop or refuse a registration after a sleep/resume, an RDP
 * reconnect or a user switch, and another app may own the configured key when Lumen starts
 * and release it later. A watchdog re-runs registration, a hidden window listens for the
 * power/session/display broadcasts that cause those drops, and the configured combination
 * falls back through {@link #FALLBACKS} until something is free. Every transition is logged
 * and published to the tray, so a dead shortcut is visible instead of silent.
 */
public final class HotkeyManager {

    private static final int PRIMARY_ID = 0xB00B;
    private static final int BACKUP_ID = 0xB00C;
    private static final int WM_HOTKEY = 0x0312;
    private static final int WM_QUIT = 0x0012;
    private static final int WM_APP = 0x8000;
    /** Thread message: unregister and register the pair again. */
    private static final int WM_REREGISTER = WM_APP + 1;

    /** Tried in order when the configured combination is already owned by another app. */
    private static final List<String> FALLBACKS =
            List.of("CTRL+ALT+SPACE", "CTRL+SHIFT+SPACE", "ALT+`");

    /** How often the watchdog looks at the registration, in milliseconds. */
    private static final long SUPERVISOR_TICK_MS = 2_000;
    /** Retry interval for reclaiming the configured key while a fallback carries the load. */
    private static final long PRIMARY_RETRY_MS = 15_000;
    /** A wall-clock jump larger than this means the machine slept or the thread stalled. */
    private static final long RESUME_GAP_MS = 8_000;

    public record Status(boolean registered, boolean primary, String label, String detail) {
    }

    private static volatile Status status = new Status(false, false, "", "starting");
    private static volatile Consumer<Status> statusListener = ignored -> {
    };
    private static volatile Runnable displayChangeListener = () -> {
    };
    private static volatile int pumpThreadId;
    private static volatile boolean stopped;
    private static volatile Spec activePrimary;
    private static volatile Spec activeBackup;
    private static volatile List<Spec> candidateSpecs = List.of();
    private static volatile long lifecycleWindow;
    private static boolean readySent;

    // Restartable pump state.
    private static volatile Thread pumpThread;
    private static volatile int activeGeneration = 0;
    private static volatile long lastHeartbeat;
    private static volatile Spec configuredSpec;
    private static volatile List<Spec> candidateList = List.of();
    private static volatile String specText = "";
    private static volatile Runnable hotkeyCallback = () -> {
    };
    private static volatile Consumer<String> readyCallback = ignored -> {
    };

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

    /** Runs after a monitor or DPI change, on the JavaFX thread. */
    public static void setDisplayChangeListener(Runnable listener) {
        displayChangeListener = listener == null ? () -> {
        } : listener;
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
     *                 hotkey could be registered (another app owns the whole chain)
     */
    public static void install(String spec, Runnable callback, Consumer<String> onReady) {
        Spec configured = Spec.parse(spec);
        List<Spec> candidates = candidates(configured);
        candidateSpecs = candidates;
        configuredSpec = configured;
        candidateList = candidates;
        specText = spec;
        hotkeyCallback = callback == null ? () -> {
        } : callback;
        readyCallback = onReady == null ? ignored -> {
        } : onReady;
        stopped = false;
        readySent = false;
        activeGeneration = 1;
        lastHeartbeat = System.currentTimeMillis();

        Thread pump = new Thread(
                () -> pump(configured, candidates, spec, hotkeyCallback, readyCallback), "global-hotkey");
        pump.setDaemon(true);
        pumpThread = pump;
        pump.start();

        Thread supervisor = new Thread(HotkeyManager::supervise, "hotkey-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    /** The configured combination first, then each fallback that is not a duplicate. */
    static List<Spec> candidates(Spec configured) {
        List<Spec> specs = new ArrayList<>(FALLBACKS.size() + 1);
        specs.add(configured);
        for (String fallback : FALLBACKS) {
            Spec spec = Spec.parse(fallback);
            if (specs.stream().noneMatch(existing -> sameBinding(existing, spec))) {
                specs.add(spec);
            }
        }
        return specs;
    }

    private static void pump(Spec configured, List<Spec> candidates, String specText,
                             Runnable callback, Consumer<String> onReady) {
        int myGeneration = activeGeneration;
        final Thread thisPump = Thread.currentThread();
        pumpThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
        User32 user32 = User32.INSTANCE;

        // Heartbeat thread: stays alive only while this pump thread is alive, so a dead
        // pump leaves a stale timestamp the supervisor can react to.
        Thread heartbeat = new Thread(() -> {
            while (!stopped && myGeneration == activeGeneration && thisPump.isAlive()) {
                lastHeartbeat = System.currentTimeMillis();
                sleepQuietly(1000);
            }
        }, "hotkey-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();

        lifecycleWindow = LifecycleWindow.create(event -> onLifecycleEvent(event));
        if (lifecycleWindow != 0L) {
            System.out.println("[hotkey] lifecycle listener active");
        }

        while (!stopped && myGeneration == activeGeneration) {
            Registration registration = register(user32, configured, candidates);
            lastHeartbeat = System.currentTimeMillis();
            publish(registration);
            if (!readySent) {
                readySent = true;
                notifyReady(onReady, registration.any() ? registration.label() : null);
            }

            // Pump until the watchdog asks for a re-registration or the app quits.
            boolean reregister = false;
            WinUser.MSG message = new WinUser.MSG();
            while (!reregister && !stopped && myGeneration == activeGeneration) {
                int result = user32.GetMessage(message, null, 0, 0);
                if (result == 0) {
                    // WM_QUIT
                    shutdown(user32);
                    heartbeat.interrupt();
                    return;
                }
                if (result == -1) {
                    // Transient pump error: keep serving the hotkey rather than dying silently.
                    System.out.println("[hotkey] message pump error " + Native.getLastError()
                            + "; retrying");
                    sleepQuietly(500);
                    continue;
                }
                lastHeartbeat = System.currentTimeMillis();
                if (message.message == WM_REREGISTER) {
                    reregister = true;
                    continue;
                }
                if (message.message == WM_HOTKEY) {
                    int id = message.wParam.intValue();
                    Spec pressed = id == BACKUP_ID ? activeBackup : activePrimary;
                    System.out.println("[hotkey] pressed "
                            + (pressed == null ? "unknown" : pressed.label()));
                    javafx.application.Platform.runLater(callback);
                }
                user32.TranslateMessage(message);
                user32.DispatchMessage(message);
            }

            unregister(user32);
        }

        heartbeat.interrupt();
        shutdown(user32);
    }

    /**
     * Power/session events invalidate registrations; display changes move the overlay.
     * Both happen on the pump thread, so re-registration can simply be posted back into the
     * loop that is already running.
     */
    private static void onLifecycleEvent(LifecycleWindow.Event event) {
        switch (event) {
            case RESUME -> {
                System.out.println("[hotkey] resume/session event; re-registering");
                postReregister();
                notifyDisplayChange();
            }
            case DISPLAY -> notifyDisplayChange();
        }
    }

    private static void notifyDisplayChange() {
        try {
            javafx.application.Platform.runLater(() -> displayChangeListener.run());
        } catch (IllegalStateException ignored) {
            // Toolkit shutting down.
        }
    }

    private static Registration register(User32 user32, Spec configured, List<Spec> candidates) {
        // Unregistering first makes the retry path idempotent: RegisterHotKey fails with
        // "already registered" for a binding we still own.
        unregister(user32);

        Spec primary = null;
        Spec backup = null;
        int firstError = 0;

        for (Spec candidate : candidates) {
            if (primary != null && backup != null) {
                break;
            }
            if (primary != null && sameBinding(primary, candidate)) {
                continue;
            }
            if (primary == null) {
                if (user32.RegisterHotKey(null, PRIMARY_ID, candidate.mods(), candidate.vk())) {
                    primary = candidate;
                } else {
                    firstError = Native.getLastError();
                }
            } else if (backup == null
                    && user32.RegisterHotKey(null, BACKUP_ID, candidate.mods(), candidate.vk())) {
                backup = candidate;
            }
        }

        activePrimary = primary;
        activeBackup = backup;
        return new Registration(primary, backup, configured, firstError);
    }

    private static void publish(Registration registration) {
        Status previous = status;
        boolean registered = registration.any();
        boolean configuredActive = registration.configuredActive();
        String label = registered ? registration.label() : "";
        String detail;
        if (!registered) {
            detail = "Hotkey unavailable";
        } else if (configuredActive) {
            detail = "Hotkey: " + label;
        } else {
            detail = "Hotkey: " + label + " (" + registration.configured().label()
                    + " is taken)";
        }

        Status next = new Status(registered, configuredActive, label, detail);
        status = next;
        listenerSafely(next);

        boolean changed = previous.registered() != next.registered()
                || previous.primary() != next.primary()
                || !previous.label().equals(next.label());
        if (!changed) {
            return;
        }

        if (!registered) {
            System.err.println("[hotkey] every candidate is taken (win32 error "
                    + registration.error() + "); tried " + describeCandidates()
                    + "; open the search bar from the tray icon");
        } else if (configuredActive) {
            System.out.println("[hotkey] registered " + label);
        } else {
            System.out.println("[hotkey] " + registration.configured().label()
                    + " is owned by another app; using " + label);
        }
    }

    private static String describeCandidates() {
        StringBuilder sb = new StringBuilder();
        for (Spec spec : candidateSpecs) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(spec.label());
        }
        return sb.toString();
    }

    private static void supervise() {
        long lastTick = System.currentTimeMillis();
        long lastPrimaryAttempt = 0;
        while (!stopped) {
            sleepQuietly(SUPERVISOR_TICK_MS);
            if (stopped) {
                continue;
            }

            long now = System.currentTimeMillis();
            long gap = now - lastTick;
            lastTick = now;

            Thread currentPump = pumpThread;
            if (currentPump == null || !currentPump.isAlive()
                    || now - lastHeartbeat > 5_000) {
                System.out.println("[hotkey] pump thread dead or stale; restarting");
                restartPumpThread();
                lastTick = now;
                continue;
            }

            if (pumpThreadId == 0) {
                continue;
            }

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

    private static void restartPumpThread() {
        Thread old = pumpThread;
        int oldThreadId = pumpThreadId;
        pumpThreadId = 0;

        // Tell the old pump thread to exit; it unregisters its hotkeys in shutdown().
        activeGeneration++;
        if (oldThreadId != 0) {
            try {
                User32.INSTANCE.PostThreadMessage(oldThreadId, WM_QUIT,
                        new WinDef.WPARAM(0), new WinDef.LPARAM(0));
            } catch (RuntimeException ignored) {
                // Old thread may already be gone.
            }
        }

        if (old != null) {
            long deadline = System.currentTimeMillis() + 1_000;
            while (old.isAlive() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (stopped) {
            return;
        }

        Spec configured = configuredSpec;
        List<Spec> candidates = candidateList;
        if (configured == null) {
            return;
        }

        Thread pump = new Thread(
                () -> pump(configured, candidates, specText, hotkeyCallback, readyCallback),
                "global-hotkey");
        pump.setDaemon(true);
        pumpThread = pump;
        lastHeartbeat = System.currentTimeMillis();
        pump.start();
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

    private static void shutdown(User32 user32) {
        unregister(user32);
        long window = lifecycleWindow;
        lifecycleWindow = 0L;
        LifecycleWindow.destroy(window);
        activePrimary = null;
        activeBackup = null;
        pumpThreadId = 0;
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

    /**
     * One registration attempt. {@code primary} and {@code backup} are the combinations that
     * actually registered (either may be {@code null}); {@code configured} is what the user
     * asked for, so the UI can say when it had to fall back.
     */
    private record Registration(Spec primary, Spec backup, Spec configured, int error) {

        boolean any() {
            return primary != null || backup != null;
        }

        boolean configuredActive() {
            return primary != null && sameBinding(primary, configured);
        }

        String label() {
            return primary != null ? primary.label() : backup == null ? "" : backup.label();
        }
    }

    record Spec(int mods, int vk) {

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
                if (Character.isLetterOrDigit(c)) {
                    return Character.toUpperCase(c);
                }
                if (c == '`') {
                    return 0xC0;
                }
                return 0x20;
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
