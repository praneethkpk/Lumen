package dev.lumen;

import dev.lumen.config.Config;
import dev.lumen.core.IndexStore;
import dev.lumen.core.IndexWatcher;
import dev.lumen.core.Indexer;
import dev.lumen.core.SearchEngine;
import dev.lumen.core.UsageStore;
import dev.lumen.platform.HotkeyManager;
import dev.lumen.platform.InstanceGuard;
import dev.lumen.platform.ShellLauncher;
import dev.lumen.platform.SingleInstance;
import dev.lumen.platform.TrayManager;
import dev.lumen.providers.CalculatorProvider;
import dev.lumen.providers.EverythingProvider;
import dev.lumen.providers.IndexProvider;
import dev.lumen.providers.Provider;
import dev.lumen.providers.WebSearchProvider;
import dev.lumen.providers.WindowProvider;
import dev.lumen.ui.Overlay;
import dev.lumen.util.Log;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires the pieces together: config, index, providers, overlay, tray and hotkey.
 *
 * <p>Startup order matters for perceived speed — the snapshot index is loaded before the
 * window is created, so the first summon already has results even though the background
 * reindex is still running.
 */
public final class LumenApp extends Application {

    private Config config;
    private IndexStore index;
    private UsageStore usage;
    private Indexer indexer;
    private IndexWatcher watcher;
    private volatile boolean watcherStarted;
    private Overlay overlay;

    @Override
    public void start(Stage stage) {
        Platform.setImplicitExit(false);

        config = Config.load();
        mark("config");

        index = new IndexStore(config.indexPath());
        index.load();
        mark("snapshot");

        usage = new UsageStore(config.usagePath());

        List<Provider> providers = new ArrayList<>();
        providers.add(new CalculatorProvider());
        providers.add(new IndexProvider(index, usage, config));
        providers.add(new WindowProvider());
        if (EverythingProvider.isAvailable()) {
            providers.add(new EverythingProvider(config, usage));
        }
        providers.add(new WebSearchProvider(config));

        // The hotkey goes live before anything expensive (AWT tray init is the slow one),
        // so the launcher is summonable as early as the JVM allows.
        HotkeyManager.setStatusListener(status -> TrayManager.setStatus(status.detail()));
        HotkeyManager.install(config.hotkey, () -> {
            if (overlay != null) {
                overlay.toggle();
            }
        }, this::onHotkeyReady);
        mark("hotkey");

        overlay = new Overlay(stage, new SearchEngine(providers, config), usage, index::size,
                () -> indexer != null && indexer.isRebuilding(),
                () -> indexer != null && indexer.isTruncated(),
                config.hideOnFocusLoss);
        overlay.install();
        HotkeyManager.setDisplayChangeListener(overlay::onDisplayChange);
        mark("overlay");

        // Launching the exe again (or double-clicking it) opens this instance instead of
        // failing silently.
        SingleInstance.listen(() -> {
            if (overlay != null) {
                overlay.show();
            }
        });

        indexer = new Indexer(config, index, usage, () -> {
            Platform.runLater(overlay::refreshIfVisible);
            if (!watcherStarted) {
                watcherStarted = true;
                watcher = new IndexWatcher(config, index,
                        () -> Platform.runLater(overlay::refreshIfVisible),
                        () -> Thread.ofVirtual().name("reindex").start(indexer::rebuild));
                watcher.start();
            }
        });
        Thread.ofVirtual().name("indexer").start(indexer::rebuild);

        // Show once on a fresh install so the app is discoverable, then stay out of the way.
        Path firstRunMarker = Config.dataDir().resolve("first-run");
        boolean firstRun = !Files.exists(firstRunMarker);
        if (config.showOnStartup || firstRun) {
            Platform.runLater(overlay::show);
        }
        if (firstRun) {
            try {
                Files.writeString(firstRunMarker, java.time.Instant.now().toString());
            } catch (IOException ignored) {
                // cosmetic only
            }
        }

        // AWT (SystemTray) pulls in its own toolkit; keep it off the critical path.
        // Restart needs the packaged app image; in a development run the item stays disabled.
        boolean packaged = System.getProperty("jpackage.app-path") != null;
        TrayManager.install(
                () -> Platform.runLater(overlay::show),
                () -> Thread.ofVirtual().name("reindex").start(indexer::rebuild),
                packaged ? this::restart : null,
                () -> ShellLauncher.open(Config.dataDir().resolve("lumen.log").toString()),
                () -> ShellLauncher.open(Config.dataDir().toString()),
                this::quit
        );
        mark("tray requested");

        // One line that says whether this launch is healthy, after the tray has settled.
        PauseTransition settle = new PauseTransition(Duration.seconds(1.2));
        settle.setOnFinished(e -> selfTest());
        settle.play();
    }

    /**
     * Verifies the promises the launcher makes, once per launch: the shortcut is live, the
     * tray exists, the index loaded and the overlay is constructed. The marker is the first
     * thing to look at when someone reports "it didn't work".
     */
    private void selfTest() {
        HotkeyManager.Status hotkey = HotkeyManager.currentStatus();
        boolean tray = TrayManager.isInstalled();
        boolean overlayReady = overlay != null && overlay.isReady();
        boolean indexLoaded = index != null;
        boolean ok = hotkey.registered() && tray && overlayReady && indexLoaded;

        String line = "[self-test] " + (ok ? "ok" : "fail")
                + " hotkey=" + (hotkey.registered() ? hotkey.label() : "unavailable")
                + " tray=" + tray
                + " index=" + (index == null ? "none" : index.size())
                + " overlay=" + overlayReady;
        if (ok) {
            System.out.println(line);
        } else {
            System.err.println(line);
            if (!hotkey.registered()) {
                System.err.println("[self-test] no shortcut is live; use the tray icon");
            }
        }
    }

    /** Confirms the shortcut once, and always explains when the requested one was taken. */
    private void onHotkeyReady(String effective) {
        if (effective == null) {
            TrayManager.notify("Hotkey not available",
                    "Another app owns " + config.hotkey + ". Right-click the tray icon to open Lumen.");
            return;
        }

        boolean fellBack = !effective.replace(" ", "").equalsIgnoreCase(config.hotkey.replace(" ", ""));
        Path marker = Config.dataDir().resolve("hotkey-notified");
        if (fellBack || !Files.exists(marker)) {
            TrayManager.notify("Lumen is ready", effective + " opens the search bar");
            try {
                Files.writeString(marker, effective);
            } catch (IOException ignored) {
                // cosmetic only
            }
        }
    }

    private static void mark(String step) {
        if (System.getenv("LUMEN_TRACE") == null) {
            return;
        }
        long ms = ProcessHandle.current().info().startInstant()
                .map(start -> java.time.Duration.between(start, java.time.Instant.now()).toMillis())
                .orElse(-1L);
        System.out.println("[startup] " + step + " +" + ms + " ms");
    }

    private void quit() {
        if (watcher != null) {
            watcher.stop();
        }
        HotkeyManager.uninstall();
        TrayManager.uninstall();
        usage.shutdown();
        index.flush();
        InstanceGuard.release();
        Platform.exit();
        System.exit(0);
    }

    /**
     * Relaunch through the app image. The instance lock is released first so the new process
     * can acquire it; in a development run there is no app image, so the tray item stays
     * disabled.
     */
    private void restart() {
        if (watcher != null) {
            watcher.stop();
        }
        String appPath = System.getProperty("jpackage.app-path");
        HotkeyManager.uninstall();
        TrayManager.uninstall();
        usage.shutdown();
        index.flush();
        InstanceGuard.release();
        if (appPath != null && !appPath.isBlank()) {
            try {
                new ProcessBuilder(appPath).start();
                System.out.println("[app] restarting from " + appPath);
            } catch (IOException e) {
                System.err.println("[app] restart failed: " + e.getMessage());
            }
        }
        Platform.exit();
        System.exit(0);
    }

    @Override
    public void stop() {
        HotkeyManager.uninstall();
        usage.shutdown();
        index.flush();
        InstanceGuard.release();
    }

    public static void main(String[] args) {
        Log.install();
        Log.setCrashListener(error -> TrayManager.notify("Lumen hit an error",
                "It is still running. Open the log or restart from the tray icon."));

        if (!InstanceGuard.tryAcquire()) {
            boolean opened = SingleInstance.requestShow();
            System.out.println(opened
                    ? "[instance] already running; asked it to show the search bar"
                    : "[instance] already running but did not respond (see lumen.log)");
            return;
        }

        launch(args);
    }
}
