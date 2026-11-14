package dev.lumen.ui;

import dev.lumen.core.Hit;
import dev.lumen.core.IndexItem;
import dev.lumen.core.SearchEngine;
import dev.lumen.core.UsageStore;
import dev.lumen.platform.ShellLauncher;
import dev.lumen.platform.WindowFocus;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.MouseInfo;
import java.awt.Point;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * The search overlay: a borderless, always-on-top panel that is shown and hidden by the
 * global hotkey.
 *
 * <p>Kept in one class on purpose — it owns the whole interaction model (keyboard
 * navigation, activation, dismissal) and splitting it would only add indirection.
 */
public final class Overlay {

    private static final double WIDTH = 720;
    private static final double ROW_HEIGHT = 52;
    private static final int MAX_VISIBLE_ROWS = 9;

    /**
     * How often the native foreground window is checked while the overlay is visible.
     * JavaFX's {@code isFocused()} cannot be trusted for this (it reports true while
     * Windows keeps another app in front), so the real answer has to be polled.
     */
    private static final int POLL_MS = 200;

    /**
     * Ticks the overlay may stay out of the foreground before it hides. 3 × 200 ms is long
     * enough to ride out a fast Alt+Tab back or a menu opening, and short enough that the
     * window never sits on top of an app the user is trying to use.
     */
    private static final int AWAY_TICKS_BEFORE_HIDE = 3;

    private final Stage stage;
    private final SearchEngine engine;
    private final UsageStore usage;
    private final IntSupplier indexSize;

    private final VBox shell = new VBox(6);
    private final TextField field = new TextField();
    private final ListView<Hit> list = new ListView<>();
    private final Label footerRight = new Label();
    private StackPane root;
    private final Timeline foregroundPoll =
            new Timeline(new KeyFrame(Duration.millis(200), e -> checkForeground()));
    private final boolean hideOnFocusLoss;
    private int awayTicks;

    public Overlay(Stage stage, SearchEngine engine, UsageStore usage, IntSupplier indexSize,
                   boolean hideOnFocusLoss) {
        this.stage = stage;
        this.engine = engine;
        this.usage = usage;
        this.indexSize = indexSize;
        this.hideOnFocusLoss = hideOnFocusLoss;
    }

    public void install() {
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setTitle("Lumen");

        StackPane built = buildRoot();
        root = built;
        Scene scene = new Scene(built, WIDTH + 52, 160, Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                if (field.getText().isEmpty()) {
                    hide();
                } else {
                    field.clear();
                }
                event.consume();
            }
        });

        stage.setScene(scene);
        foregroundPoll.setCycleCount(Animation.INDEFINITE);
        foregroundPoll.setDelay(Duration.ZERO);
    }

    /**
     * Hides the overlay when Windows no longer gives it the foreground.
     *
     * <p>This deliberately does not use {@code stage.focusedProperty()}: JavaFX reports the
     * stage as focused while Windows keeps another app in front, which left the overlay
     * visible, always-on-top and unable to receive input — the app underneath could not be
     * reached without minimising. The native foreground window is the only reliable signal.
     */
    private void checkForeground() {
        if (!stage.isShowing()) {
            foregroundPoll.stop();
            return;
        }
        if (!hideOnFocusLoss) {
            awayTicks = 0;
            return;
        }
        if (WindowFocus.isForeground()) {
            if (awayTicks > 0) {
                System.out.println("[focus] foreground regained after "
                        + awayTicks * POLL_MS + " ms");
            }
            awayTicks = 0;
            return;
        }

        awayTicks++;
        if (awayTicks == 1) {
            System.out.println("[focus] left the foreground (foreground=\""
                    + WindowFocus.foregroundTitle() + "\")");
        }
        if (awayTicks >= AWAY_TICKS_BEFORE_HIDE) {
            System.out.println("[focus] stayed away for " + awayTicks * POLL_MS
                    + " ms; hiding");
            hide();
        }
    }

    private StackPane buildRoot() {
        shell.getStyleClass().add("shell");
        shell.setPrefWidth(WIDTH);
        shell.setPadding(new Insets(12, 14, 8, 14));

        field.getStyleClass().add("search-field");
        field.setPromptText("Search apps and files…");
        HBox.setHgrow(field, Priority.ALWAYS);
        field.textProperty().addListener((obs, old, value) -> refresh(true));

        Label keycap = new Label("ESC");
        keycap.getStyleClass().add("kbd");
        keycap.setAlignment(Pos.CENTER);

        HBox searchRow = new HBox(10, magnifier(), field, keycap);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        Region divider = new Region();
        divider.getStyleClass().add("divider");
        divider.setMinHeight(1);
        divider.setMaxHeight(1);

        list.getStyleClass().add("result-list");
        list.setFocusTraversable(false);
        list.setFixedCellSize(ROW_HEIGHT);
        list.setCellFactory(view -> new ResultCell());
        list.setPlaceholder(emptyState());

        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                activate(event.isControlDown());
            }
        });
        // Keep typing possible after clicking a row.
        list.setOnMousePressed(event -> Platform.runLater(field::requestFocus));

        HBox footer = buildFooter();

        shell.getChildren().addAll(searchRow, divider, list, footer);

        StackPane container = new StackPane(shell);
        container.setPadding(new Insets(26));
        container.setAlignment(Pos.TOP_CENTER);

        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);

        return container;
    }

    private HBox buildFooter() {
        HBox navigate = hint("↑↓", "navigate");
        HBox open = hint("↵", "open");
        HBox reveal = hint("Ctrl+↵", "show in folder");
        HBox close = hint("Esc", "close");

        footerRight.getStyleClass().add("footer-count");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(12, navigate, open, reveal, close, spacer, footerRight);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("footer");
        return footer;
    }

    private static HBox hint(String key, String label) {
        Label keycap = new Label(key);
        keycap.getStyleClass().add("kbd");
        Label text = new Label(label);
        text.getStyleClass().add("hint");
        HBox box = new HBox(5, keycap, text);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Region magnifier() {
        Canvas canvas = new Canvas(20, 20);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setStroke(Color.web("#8b95a5"));
        gc.setLineWidth(1.8);
        gc.strokeOval(2.5, 2.5, 11, 11);
        gc.strokeLine(12.5, 12.5, 17.5, 17.5);

        StackPane wrapper = new StackPane(canvas);
        wrapper.setMinWidth(20);
        return wrapper;
    }

    private static Label emptyState() {
        Label label = new Label("No matches");
        label.getStyleClass().add("empty");
        return label;
    }

    private void onKeyPressed(KeyEvent event) {
        switch (event.getCode()) {
            case DOWN -> {
                move(1);
                event.consume();
            }
            case UP -> {
                move(-1);
                event.consume();
            }
            case TAB -> {
                if (event.isAltDown()) {
                    // Alt+Tab is the Windows task switcher combo; never consume it here.
                    break;
                }
                move(event.isShiftDown() ? -1 : 1);
                event.consume();
            }
            case ENTER -> {
                activate(event.isControlDown());
                event.consume();
            }
            case ESCAPE -> event.consume(); // the scene-level filter does the work
            default -> {
            }
        }
    }

    private void move(int delta) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int current = list.getSelectionModel().getSelectedIndex();
        int next = Math.floorMod(current + delta, size);
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void activate(boolean reveal) {
        Hit hit = list.getSelectionModel().getSelectedItem();
        if (hit == null) {
            return;
        }

        IndexItem item = hit.item();
        hide();

        switch (item.kind()) {
            case CALC -> copyToClipboard(item.path());
            case WEB -> ShellLauncher.open(item.path());
            case WINDOW -> switchToWindow(item.path());
            case APP, FILE, FOLDER -> {
                if (reveal) {
                    ShellLauncher.reveal(item.path());
                } else {
                    usage.record(item.path());
                    // Let the overlay disappear first so the launched window owns the foreground.
                    PauseTransition delay = new PauseTransition(Duration.millis(60));
                    delay.setOnFinished(e -> ShellLauncher.open(item.path()));
                    delay.play();
                }
            }
        }
    }

    /**
     * Switching is deferred for the same reason launching is: the overlay must be gone and
     * must no longer hold the foreground before Windows will hand it to the target window.
     */
    private static void switchToWindow(String handle) {
        long value;
        try {
            value = Long.parseLong(handle);
        } catch (NumberFormatException e) {
            return;
        }
        PauseTransition delay = new PauseTransition(Duration.millis(60));
        delay.setOnFinished(e -> WindowFocus.claimHandle(value));
        delay.play();
    }

    private static void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void refresh(boolean resetSelection) {
        List<Hit> hits = engine.search(field.getText());
        int previous = list.getSelectionModel().getSelectedIndex();

        list.getItems().setAll(hits);
        if (!hits.isEmpty()) {
            int target = resetSelection ? 0 : Math.max(0, Math.min(previous, hits.size() - 1));
            list.getSelectionModel().select(target);
            list.scrollTo(target);
        }

        footerRight.setText(String.format("%,d indexed", indexSize.getAsInt()));
        resize();
    }

    public void refreshIfVisible() {
        if (stage.isShowing()) {
            refresh(false);
        }
    }

    private void resize() {
        int rows = Math.min(list.getItems().size(), MAX_VISIBLE_ROWS);
        double height = rows == 0 ? 56 : rows * ROW_HEIGHT + 4;

        list.setMinHeight(height);
        list.setPrefHeight(height);
        list.setMaxHeight(height);

        // A non-resizable transparent stage keeps the size its scene was constructed with
        // when sizeToScene() runs, so the height is applied from the live content instead.
        // Without this the window stays one row tall and later results scroll out of sight.
        stage.sizeToScene();
        stage.setHeight(root.prefHeight(stage.getWidth()));

        Rectangle2D bounds = activeScreen().getVisualBounds();
        centerHorizontally(bounds);
        positionVertically(bounds);
    }

    public void show() {
        long traceStart = System.nanoTime();
        awayTicks = 0;

        // Already on screen but behind another window: raise it instead of toggling it away.
        if (stage.isShowing()) {
            takeForeground();
            field.requestFocus();
            System.out.printf("[show] raised existing window in %.2f ms (foreground=%s)%n",
                    (System.nanoTime() - traceStart) / 1_000_000.0, WindowFocus.isForeground());
            startForegroundPoll();
            return;
        }

        field.clear();
        refresh(true);

        stage.show();
        stage.toFront();
        stage.requestFocus();
        // The stage now has a real width, so this sizes the overlay to its content; before
        // show() the preferred height is computed against a not-yet-known width.
        resize();
        field.requestFocus();
        takeForeground();

        shell.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(110), shell);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();

        startForegroundPoll();

        System.out.printf("[show] %.2f ms (visible, foreground=%s)%n",
                (System.nanoTime() - traceStart) / 1_000_000.0, WindowFocus.isForeground());
    }

    private void startForegroundPoll() {
        if (hideOnFocusLoss) {
            foregroundPoll.playFromStart();
        }
    }

    public void hide() {
        foregroundPoll.stop();
        awayTicks = 0;
        if (stage.isShowing()) {
            stage.hide();
            System.out.println("[hide]");
        }
    }

    /**
     * Hotkey behaviour: hide only when Windows says the overlay owns the foreground. If it
     * is visible but behind something, bring it forward — otherwise the first press looks
     * like it did nothing and the second one closes a window the user cannot see.
     */
    public void toggle() {
        if (stage.isShowing() && WindowFocus.isForeground()) {
            System.out.println("[overlay] hide via toggle (hotkey)");
            hide();
        } else {
            show();
        }
    }

    /**
     * Activation can be refused by Windows, and it can also land a few milliseconds late.
     * JavaFX's {@code isFocused()} is optimistic right after {@code show()}, so the native
     * foreground window is the only trustworthy answer.
     */
    private void takeForeground() {
        WindowFocus.claim();
        if (WindowFocus.isForeground()) {
            return;
        }
        stage.requestFocus();
        field.requestFocus();
        claimWithRetries(0);
    }

    private void claimWithRetries(int attempt) {
        long[] delays = {60, 150, 300};
        if (attempt >= delays.length) {
            // A visible, always-on-top window that cannot take input would sit in front of
            // the app the user is trying to use. Hide it and say why in the log.
            System.out.println("[focus] could not take the foreground after retries (foreground=\""
                    + WindowFocus.foregroundTitle() + "\"); hiding");
            hide();
            return;
        }
        PauseTransition retry = new PauseTransition(Duration.millis(delays[attempt]));
        retry.setOnFinished(e -> {
            if (!stage.isShowing()) {
                return;
            }
            if (WindowFocus.isForeground()) {
                return;
            }
            boolean raised = WindowFocus.claim();
            field.requestFocus();
            System.out.println("[focus] retry " + (attempt + 1) + "/" + delays.length + " "
                    + (raised ? "ok" : "refused")
                    + ", foreground=\"" + WindowFocus.foregroundTitle() + "\"");
            if (WindowFocus.isForeground()) {
                return;
            }
            claimWithRetries(attempt + 1);
        });
        retry.play();
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    private void centerHorizontally(Rectangle2D bounds) {
        stage.setX(bounds.getMinX() + (bounds.getWidth() - stage.getWidth()) / 2.0);
    }

    private void positionVertically(Rectangle2D bounds) {
        double preferred = bounds.getMinY() + bounds.getHeight() * 0.18;
        // Keep the whole panel on screen when a long result list makes it tall.
        double highest = bounds.getMaxY() - stage.getHeight();
        stage.setY(Math.max(bounds.getMinY(), Math.min(preferred, highest)));
    }

    private static Screen activeScreen() {
        Screen primary = Screen.getPrimary();
        try {
            Point pointer = MouseInfo.getPointerInfo().getLocation();
            for (Screen screen : Screen.getScreensForRectangle(pointer.x, pointer.y, 1, 1)) {
                return screen;
            }
        } catch (RuntimeException e) {
            // Headless or a transient AWT failure: fall back to the primary display.
        }
        return primary;
    }
}
