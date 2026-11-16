package dev.lumen.platform;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.DWORDByReference;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * System-wide hotkey via {@code RegisterHotKey}, with a dedicated message pump thread.
 *
 * <p>Chosen over a low-level keyboard hook: no elevation surprises, no per-keystroke
 * overhead, and Windows itself does the matching.
 *
 * <h2>Why the supervisor exists</h2>
 *
 * <p>Windows drops or refuses a registration after a sleep/resume, an RDP reconnect, a user
 * switch, or when another app owns the key at startup and releases it later. Because the
 * shortcut is the product's only summon mechanism, a silent loss of it is a total failure, so
 * recovery cannot depend on the user noticing.
 *
 * <h2>Why the health check is an active probe</h2>
 *
 * <p>An earlier version watched a timestamp that a helper thread refreshed, which made a
 * wedged pump look healthy: the only thread still running was the one reporting on it. Health
 * is now established two ways, neither of which trusts the pump's own bookkeeping:
 *
 * <ul>
 *   <li>{@link #pumpThread}.{@code isAlive()} catches a thread that has exited.</li>
 *   <li>{@code SendMessageTimeout} to the pump's own window catches a thread that is alive
 *       but no longer pumping. Windows must dispatch that message from inside the pump's
 *       {@code GetMessage} wait, so a reply is proof the loop is still turning. A stuck loop
 *       times out and reports failure.</li>
 * </ul>
 *
 * <p>A restart is generation-guarded: the old pump is retired before the new one starts, and
 * a retiring pump may not touch shared state that belongs to a later generation. That matters
 * because {@code UnregisterHotKey} and the thread id are both per-thread; an old pump that
 * unregisters after the new one starts would silently disable the shortcut it just installed.
 *
 * <p>Registration is supervised and always ends with a working shortcut when one is
 * available. The configured combination falls back through {@link #FALLBACKS} until something
 * is free, and every transition is logged and published to the tray, so a dead shortcut is
 * visible instead of silent.
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

    static final String DEFAULT_SPEC = "ALT+SPACE";

    /** How often the supervisor looks at the registration, in milliseconds. */
    private static final long SUPERVISOR_TICK_MS = 1_000;
    /** Retry interval for reclaiming the configured key while a fallback carries the load. */
    private static final long PRIMARY_RETRY_MS = 15_000;
    /** A wall-clock jump larger than this means the machine slept or the supervisor stalled. */
    private static final long RESUME_GAP_MS = 8_000;
    /** Grace period after a pump starts before an unanswered probe counts as a failure. */
    private static final long STARTUP_GRACE_MS = 4_000;
    /** How long to wait for a liveness reply from the pump thread. */
    private static final int PROBE_TIMEOUT_MS = 1_000;
    /** Backoff between restarts when a pump keeps failing immediately. */
    private static final long MAX_RESTART_BACKOFF_MS = 30_000;
    private static final int SMTO_BLOCK = 0x0001;
    private static final int SMTO_ABORTIFHUNG = 0x0002;

    public record Status(boolean registered, boolean primary, String label, String detail) {
    }

    private static final Status STARTING = new Status(false, false, "", "starting");
    private static final Status RESTARTING =
            new Status(false, false, "", "Hotkey: recovering after a pump failure");

    private static volatile Status status = STARTING;
    private static volatile Consumer<Status> statusListener = ignored -> {
    };
    private static volatile Runnable displayChangeListener = () -> {
    };

    /** Thread id of the current pump, or {@code 0} while no pump is running. */
    private static volatile int pumpThreadId;
    /** Set once by {@link #uninstall()}; every loop in this class checks it. */
    private static volatile boolean stopped;
    private static volatile HotkeySpec activePrimary;
    private static volatile HotkeySpec activeBackup;
    private static volatile List<HotkeySpec> candidateSpecs = List.of();

    // ---- restartable pump state -------------------------------------------------------

    private static volatile Thread pumpThread;
    private static volatile Thread supervisorThread;
    /**
     * Bumped whenever authority moves to a new pump. Anything that writes shared state checks
     * this first, which is what keeps a retiring pump from unregistering its successor's keys.
     */
    private static volatile int activeGeneration;
    /** Written by the pump thread itself, never by a helper thread. */
    private static volatile long pumpHeartbeat;
    /** When the current pump thread was started, for the liveness grace period. */
    private static volatile long pumpStartedAt;
    /** The current pump's hidden lifecycle window, or {@code 0}. */
    private static volatile long lifecycleWindow;
    /** The generation that owns {@link #lifecycleWindow}. */
    private static volatile int lifecycleGeneration = -1;

    private static volatile HotkeySpec configuredSpec;
    private static volatile List<HotkeySpec> candidateList = List.of();
    private static volatile Runnable hotkeyCallback = () -> {
    };
    private static volatile Consumer<String> readyCallback = ignored -> {
    };
    /** Restarts since the last successful registration; drives the backoff. */
    private static volatile int restartStreak;
    /** The ready callback must fire once per install, not once per pump generation. */
    private static volatile boolean readySent;

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
    public static synchronized void install(String spec, Runnable callback,
                                            Consumer<String> onReady) {
        String requested = spec == null || spec.isBlank() ? DEFAULT_SPEC : spec;
        if (!HotkeySpec.isValid(requested)) {
            System.err.println("[hotkey] \"" + requested + "\" is not a usable combination "
                    + "(need a modifier plus a key); using " + DEFAULT_SPEC);
            requested = DEFAULT_SPEC;
        }

        HotkeySpec configured = HotkeySpec.parse(requested);
        List<HotkeySpec> candidates = candidates(configured);
        candidateSpecs = candidates;
        configuredSpec = configured;
        candidateList = candidates;
        hotkeyCallback = callback == null ? () -> {
        } : callback;
        readyCallback = onReady == null ? ignored -> {
        } : onReady;

        // A second install must replace the first one rather than add a second pump and a
        // second supervisor that both believe they own the registration.
        if (pumpThread != null) {
            retireCurrentPump();
        }
        stopped = false;
        restartStreak = 0;
        readySent = false;
        activeGeneration++;
        status = STARTING;
        listenerSafely(status);

        startPump(activeGeneration);
        startSupervisor();
    }

    /** The configured combination first, then each fallback that is not a duplicate. */
    static List<HotkeySpec> candidates(HotkeySpec configured) {
        List<HotkeySpec> specs = new ArrayList<>(FALLBACKS.size() + 1);
        specs.add(configured);
        for (String fallback : FALLBACKS) {
            HotkeySpec spec = HotkeySpec.parse(fallback);
            if (specs.stream().noneMatch(existing -> sameBinding(existing, spec))) {
                specs.add(spec);
            }
        }
        return specs;
    }

    // ---- pump -------------------------------------------------------------------------

    private static void startPump(int generation) {
        HotkeySpec configured = configuredSpec;
        if (configured == null) {
            return;
        }
        List<HotkeySpec> candidates = candidateList;
        Thread pump = new Thread(
                () -> pump(generation, configured, candidates), "global-hotkey");
        pump.setDaemon(true);
        pumpThread = pump;
        pumpStartedAt = System.currentTimeMillis();
        pumpHeartbeat = pumpStartedAt;
        pump.start();
    }

    private static void pump(int generation, HotkeySpec configured, List<HotkeySpec> candidates) {
        User32 user32 = User32.INSTANCE;
        try {
            if (!isCurrent(generation)) {
                return;
            }
            pumpThreadId = Kernel32.INSTANCE.GetCurrentThreadId();

            long window = LifecycleWindow.create(
                    event -> onLifecycleEvent(generation, event), generation);
            if (window != 0L) {
                lifecycleGeneration = generation;
                lifecycleWindow = window;
                System.out.println("[hotkey] lifecycle listener active");
            }

            while (isCurrent(generation) && !stopped) {
                Registration registration = register(user32, configured, candidates);
                pumpHeartbeat = System.currentTimeMillis();
                publish(registration);

                if (!readySent) {
                    readySent = true;
                    notifyReady(readyCallback,
                            registration.any() ? registration.label() : null);
                }

                // Pump until the supervisor asks for a re-registration or we are retired.
                boolean reregister = false;
                WinUser.MSG message = new WinUser.MSG();
                while (!reregister && isCurrent(generation) && !stopped) {
                    int result = user32.GetMessage(message, null, 0, 0);
                    if (result == 0) {
                        // WM_QUIT: the supervisor retired us, or the app is shutting down.
                        return;
                    }
                    if (result == -1) {
                        // Transient pump error: keep serving the hotkey rather than dying.
                        System.out.println("[hotkey] message pump error " + Native.getLastError()
                                + "; retrying");
                        sleepQuietly(500);
                        continue;
                    }
                    pumpHeartbeat = System.currentTimeMillis();
                    if (message.message == WM_REREGISTER) {
                        reregister = true;
                        continue;
                    }
                    if (message.message == WM_HOTKEY) {
                        dispatchPress(message.wParam.intValue());
                    }
                    user32.TranslateMessage(message);
                    user32.DispatchMessage(message);
                }

                if (isCurrent(generation)) {
                    unregister(user32);
                }
            }
        } catch (Throwable e) {
            // Catch Errors too: a NoClassDefFoundError/UnsatisfiedLinkError must not escape
            // into the thread and leave a half-torn-down registration. The supervisor sees
            // the dead pump and starts a fresh generation, which re-registers the shortcut.
            System.err.println("[hotkey] pump failed: " + e);
            e.printStackTrace();
        } finally {
            // Only the pump that still owns the registration may release it. A retired pump
            // exits its thread, and Windows frees hotkeys registered by a dead thread on its
            // own, so an unconditional unregister here would be a bug, not a cleanup.
            if (isCurrent(generation)) {
                unregister(user32);
                releaseWindow(generation);
                activePrimary = null;
                activeBackup = null;
                pumpThreadId = 0;
            }
        }
    }

    private static void dispatchPress(int id) {
        HotkeySpec pressed = id == BACKUP_ID ? activeBackup : activePrimary;
        System.out.println("[hotkey] pressed " + (pressed == null ? "unknown" : pressed.label()));
        Runnable callback = hotkeyCallback;
        try {
            javafx.application.Platform.runLater(callback);
        } catch (IllegalStateException e) {
            // Toolkit not up yet (or shutting down): still honour the press.
            runCallbackSafely(callback);
        }
    }

    /**
     * Runs a callback without letting it take the pump thread down with it.
     *
     * <p>{@code Platform.runLater} and the app callback both execute arbitrary code. A
     * throwing callback (or a toolkit/JNA {@link Error}) must never reach {@link #pump}'s
     * control flow: an exception escaping the message loop runs the {@code finally} block,
     * which unregisters the hotkey and leaves the launcher with no way to be summoned. The
     * shortcut is the product's only entry point, so one bad callback is not worth losing it.
     */
    private static void runCallbackSafely(Runnable callback) {
        try {
            callback.run();
        } catch (Throwable t) {
            System.err.println("[hotkey] callback failed: " + t);
            t.printStackTrace();
        }
    }

    /**
     * Power/session events invalidate registrations; display changes move the overlay.
     * Both arrive on the pump thread, so re-registration can be posted back into the loop
     * that is already running.
     */
    private static void onLifecycleEvent(int generation, LifecycleWindow.Event event) {
        if (!isCurrent(generation)) {
            return;
        }
        switch (event) {
            case RESUME -> {
                System.out.println("[hotkey] resume/session event; re-registering");
                postReregister(generation);
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

    private static Registration register(User32 user32, HotkeySpec configured, List<HotkeySpec> candidates) {
        // Unregistering first makes the retry path idempotent: RegisterHotKey fails with
        // "already registered" for a binding we still own.
        unregister(user32);

        HotkeySpec primary = null;
        HotkeySpec backup = null;
        int firstError = 0;

        for (HotkeySpec candidate : candidates) {
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

        if (registered) {
            restartStreak = 0;
        }

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
        for (HotkeySpec spec : candidateSpecs) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(spec.label());
        }
        return sb.toString();
    }

    // ---- supervisor -------------------------------------------------------------------

    private static synchronized void startSupervisor() {
        Thread existing = supervisorThread;
        if (existing != null && existing.isAlive()) {
            return;
        }
        Thread supervisor = new Thread(HotkeyManager::supervise, "hotkey-supervisor");
        supervisor.setDaemon(true);
        supervisorThread = supervisor;
        supervisor.start();
    }

    /**
     * One supervisor for the lifetime of the install. It reads the current generation every
     * tick instead of being replaced on each restart, so repeated recoveries cannot pile up
     * competing supervisors.
     */
    private static void supervise() {
        long lastTick = System.currentTimeMillis();
        long lastPrimaryAttempt = 0;
        while (!stopped) {
            sleepQuietly(SUPERVISOR_TICK_MS);
            if (stopped) {
                return;
            }

            long now = System.currentTimeMillis();
            long gap = now - lastTick;
            lastTick = now;

            int generation = activeGeneration;
            Thread currentPump = pumpThread;
            if (currentPump == null || !currentPump.isAlive()) {
                restartPump(generation);
                lastTick = System.currentTimeMillis();
                continue;
            }

            // A pump that has just started has not had time to create its window yet.
            boolean settling = now - pumpStartedAt < STARTUP_GRACE_MS;
            if (!settling && !pumpResponds(generation)) {
                System.err.println("[hotkey] pump thread is not answering ("
                        + (now - pumpHeartbeat) + " ms since it was last seen); restarting");
                restartPump(generation);
                lastTick = System.currentTimeMillis();
                continue;
            }

            if (gap > RESUME_GAP_MS) {
                // Sleep/resume, RDP reconnect or a stalled thread. Registrations are cheap
                // to rebuild, so assume they are stale rather than waiting for a user report.
                System.out.println("[hotkey] resume detected (" + gap + " ms gap); re-registering");
                postReregister(generation);
                continue;
            }

            if (!status.registered()) {
                postReregister(generation);
                continue;
            }

            if (!status.primary() && now - lastPrimaryAttempt >= PRIMARY_RETRY_MS) {
                // The app that owned the configured key may have exited since startup.
                lastPrimaryAttempt = now;
                postReregister(generation);
            }
        }
    }

    /**
     * Asks the pump thread to prove it is still pumping.
     *
     * <p>{@code SendMessageTimeout} is dispatched from inside the target thread's message wait,
     * so a reply means the loop is alive even when it is idle. {@code SMTO_ABORTIFHUNG} makes a
     * wedged thread fail instead of hanging the supervisor.
     */
    private static boolean pumpResponds(int generation) {
        if (!isCurrent(generation)) {
            return true;
        }
        long window = lifecycleWindow;
        if (window == 0L || lifecycleGeneration != generation) {
            // No window for this generation yet. Restarting would not help (whatever broke
            // window creation breaks it again), so fall back to the pump-written heartbeat
            // and let the thread-liveness check above catch a real death.
            return true;
        }
        HWND hwnd = new HWND(Pointer.createConstant(window));
        DWORDByReference result = new DWORDByReference();
        try {
            LRESULT reply = User32.INSTANCE.SendMessageTimeout(hwnd, LifecycleWindow.WM_LIVENESS,
                    new WPARAM(0), new LPARAM(0),
                    SMTO_BLOCK | SMTO_ABORTIFHUNG, PROBE_TIMEOUT_MS, result);
            return reply != null && reply.longValue() != 0L;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Retires the current pump and starts a fresh one. Safe to call with a stale generation. */
    private static void restartPump(int observedGeneration) {
        if (!isCurrent(observedGeneration) || stopped) {
            return;
        }
        restartStreak++;
        long backoff = Math.min(MAX_RESTART_BACKOFF_MS,
                250L * (1L << Math.min(restartStreak, 7)));
        if (restartStreak > 2) {
            System.out.println("[hotkey] " + restartStreak + " restarts in a row; waiting "
                    + backoff + " ms before the next one");
            sleepQuietly(backoff);
            if (stopped) {
                return;
            }
        }

        // Reset the published state first: leaving a stale "registered" status while nothing
        // is registered is exactly the failure mode this class exists to prevent.
        status = RESTARTING;
        listenerSafely(status);

        retireCurrentPump();
        System.out.println("[hotkey] restarting the message pump (generation "
                + activeGeneration + ")");
        pumpStartedAt = System.currentTimeMillis();
        pumpHeartbeat = pumpStartedAt;
        startPump(activeGeneration);
    }

    /**
     * Asks the current pump to exit and waits briefly for it.
     *
     * <p>The generation is advanced <em>before</em> the quit is posted, which is the whole
     * point: from that instant the old pump is a stranger. If it then unregisters (per-thread
     * state that would hit the successor's keys too) or clears {@code pumpThreadId}, the
     * generation check in its {@code finally} block stops it.
     */
    private static void retireCurrentPump() {
        Thread old = pumpThread;
        int oldThreadId = pumpThreadId;
        activeGeneration++;
        pumpThreadId = 0;
        if (oldThreadId != 0) {
            try {
                User32.INSTANCE.PostThreadMessage(oldThreadId, WM_QUIT,
                        new WPARAM(0), new LPARAM(0));
            } catch (RuntimeException ignored) {
                // The old thread is already gone; Windows released its hotkeys.
            }
        }
        if (old == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 1_500;
        while (old.isAlive() && System.currentTimeMillis() < deadline) {
            sleepQuietly(10);
        }
    }

    private static void postReregister(int generation) {
        if (!isCurrent(generation)) {
            return;
        }
        int threadId = pumpThreadId;
        if (threadId == 0) {
            return;
        }
        try {
            User32.INSTANCE.PostThreadMessage(threadId, WM_REREGISTER,
                    new WPARAM(0), new LPARAM(0));
        } catch (RuntimeException e) {
            System.err.println("[hotkey] could not post a re-registration: " + e);
        }
    }

    private static boolean isCurrent(int generation) {
        return generation == activeGeneration;
    }

    private static void releaseWindow(int generation) {
        long window = lifecycleWindow;
        if (lifecycleGeneration != generation) {
            // Belongs to a newer pump.
            return;
        }
        lifecycleWindow = 0L;
        lifecycleGeneration = -1;
        LifecycleWindow.destroy(window);
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

    private static boolean sameBinding(HotkeySpec a, HotkeySpec b) {
        return a.mods() == b.mods() && a.vk() == b.vk();
    }

    private static void notifyReady(Consumer<String> onReady, String label) {
        try {
            javafx.application.Platform.runLater(() -> {
                try {
                    onReady.accept(label);
                } catch (Throwable t) {
                    System.err.println("[hotkey] onReady callback failed: " + t);
                }
            });
        } catch (IllegalStateException e) {
            // Toolkit not up (or shutting down): notify synchronously, but never let a bad
            // callback or a missing toolkit class tear down the pump that owns the hotkey.
            runCallbackSafely(() -> onReady.accept(label));
        } catch (Throwable t) {
            System.err.println("[hotkey] could not schedule the ready notification: " + t);
        }
    }

    public static synchronized void uninstall() {
        stopped = true;
        activeGeneration++;
        int threadId = pumpThreadId;
        if (threadId != 0) {
            try {
                User32.INSTANCE.PostThreadMessage(threadId, WM_QUIT,
                        new WPARAM(0), new LPARAM(0));
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
        Thread pump = pumpThread;
        if (pump != null) {
            long deadline = System.currentTimeMillis() + 1_000;
            while (pump.isAlive() && System.currentTimeMillis() < deadline) {
                sleepQuietly(10);
            }
        }
        Thread supervisor = supervisorThread;
        if (supervisor != null && supervisor.isAlive()) {
            supervisor.interrupt();
        }
        supervisorThread = null;
        pumpThread = null;
        pumpThreadId = 0;
        status = new Status(false, false, "", "hotkey uninstalled");
    }

    // ---- test seams -------------------------------------------------------------------

    /** Current pump thread id, or {@code 0}. Used by the pump-kill regression test. */
    static int pumpThreadIdForTest() {
        return pumpThreadId;
    }

    /** Current generation. Used by the pump-kill regression test. */
    static int generationForTest() {
        return activeGeneration;
    }

    /** Timestamp last written by the pump thread itself. */
    static long heartbeatForTest() {
        return pumpHeartbeat;
    }

    /** True when the pump thread answers the liveness probe. */
    static boolean pumpRespondsForTest() {
        return pumpResponds(activeGeneration);
    }

    /** Forces one supervisor cycle instead of waiting for the tick. */
    static void superviseOnceForTest() {
        int generation = activeGeneration;
        Thread pump = pumpThread;
        if (pump == null || !pump.isAlive() || !pumpResponds(generation)) {
            restartPump(generation);
        }
    }

    /**
     * One registration attempt. {@code primary} and {@code backup} are the combinations that
     * actually registered (either may be {@code null}); {@code configured} is what the user
     * asked for, so the UI can say when it had to fall back.
     */
    private record Registration(HotkeySpec primary, HotkeySpec backup, HotkeySpec configured, int error) {

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

}