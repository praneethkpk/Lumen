package dev.lumen.ui;

import dev.lumen.core.IndexItem;
import dev.lumen.platform.ShellLauncher;
import dev.lumen.platform.WindowFocus;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Opens results, and keeps a failure visible instead of closing as if it worked.
 *
 * <p>A target is existence-checked before the overlay hides, so a file deleted after indexing
 * produces an inline message rather than a silent dismissal. Launching is deferred by a frame
 * or two because the overlay must release the foreground before the launched window can take
 * it; a failure from that deferred step re-summons the overlay with the reason.
 */
final class ResultLauncher implements ResultActions.Target {

    private static final Duration RELEASE_DELAY = Duration.millis(60);

    private final Runnable hide;
    private final Runnable summon;
    private final Consumer<String> reportError;
    private final Consumer<String> clipboard;

    /**
     * @param hide        dismisses the overlay before the shell is called
     * @param summon      shows the overlay again after a deferred failure
     * @param reportError displays the inline banner
     * @param clipboard   copies text to the clipboard
     */
    ResultLauncher(Runnable hide, Runnable summon, Consumer<String> reportError,
                   Consumer<String> clipboard) {
        this.hide = hide;
        this.summon = summon;
        this.reportError = reportError;
        this.clipboard = clipboard;
    }

    // ---- ResultActions.Target ------------------------------------------

    @Override
    public void open(IndexItem item) {
        open(item.path());
    }

    @Override
    public void openAsAdmin(IndexItem item) {
        openAsAdmin(item.path());
    }

    @Override
    public void reveal(IndexItem item) {
        reveal(item.path());
    }

    @Override
    public void switchTo(IndexItem item) {
        hide.run();
        switchTo(item.path());
    }

    @Override
    public void copy(String text) {
        hide.run();
        clipboard.accept(text);
    }

    // ---- direct forms --------------------------------------------------

    void open(String target) {
        if (!launchable(target)) {
            reportError.accept("Not found: " + target);
            return;
        }
        hide.run();
        afterRelease(() -> ShellLauncher.open(target), "Could not open " + target);
    }

    /** Opens with the {@code runas} verb, which is what raises the UAC prompt. */
    void openAsAdmin(String target) {
        if (!launchable(target)) {
            reportError.accept("Not found: " + target);
            return;
        }
        hide.run();
        afterRelease(() -> ShellLauncher.openAsAdmin(target),
                "Could not run " + target + " as administrator");
    }

    void reveal(String target) {
        if (!launchable(target)) {
            reportError.accept("Not found: " + target);
            return;
        }
        hide.run();
        if (!ShellLauncher.reveal(target)) {
            failed("Could not show " + target + " in Explorer");
        }
    }

    /**
     * Switching is deferred for the same reason launching is: the overlay must be gone and
     * must no longer hold the foreground before Windows will hand it to the target window.
     */
    void switchTo(String handle) {
        long value;
        try {
            value = Long.parseLong(handle);
        } catch (NumberFormatException e) {
            return;
        }
        afterRelease(() -> WindowFocus.claimHandle(value), "That window is no longer open");
    }

    private void afterRelease(BooleanSupplier action, String failure) {
        PauseTransition delay = new PauseTransition(RELEASE_DELAY);
        delay.setOnFinished(e -> {
            if (!action.getAsBoolean()) {
                failed(failure);
            }
        });
        delay.play();
    }

    /** A deferred failure happened while the overlay was hidden: bring it back with the reason. */
    private void failed(String message) {
        summon.run();
        reportError.accept(message);
    }

    /** URLs and shell URIs are launchable by definition; real paths must still exist. */
    private static boolean launchable(String target) {
        if (target == null || target.isBlank()) {
            return false;
        }
        if (target.contains("://") || target.startsWith("shell:") || target.startsWith("ms-")) {
            return true;
        }
        try {
            return Files.exists(Path.of(target));
        } catch (RuntimeException e) {
            return true; // not a parseable path: let the shell decide
        }
    }
}
