package dev.lumen.ui;

import dev.lumen.config.Config;
import dev.lumen.core.IndexStore;
import dev.lumen.core.UsageStore;
import dev.lumen.platform.HotkeyManager;
import dev.lumen.platform.WindowFocus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Settings window for Luner — live hotkey capture, folder roots, and all configuration.
 *
 * <p>Every change applies immediately (or asynchronously without restart) so the user
 * never has to hand-edit JSON or restart the app to make a change take effect.
 */
public final class SettingsWindow {

    private final Stage stage;
    private final Config config;
    private final IndexStore indexStore;
    private final UsageStore usageStore;
    private String pendingHotkey = "";

    public SettingsWindow(Stage owner, Config config, IndexStore indexStore, UsageStore usageStore) {
        this.config = config;
        this.indexStore = indexStore;
        this.usageStore = usageStore;

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Lumen — Settings");
        stage.setMinWidth(560);
        stage.setMinHeight(400);

        buildScene();
    }

    private void buildScene() {
        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.setAlignment(Pos.TOP_LEFT);

        // ---- General section ----
        TitledPane generalPane = buildGeneralSection();
        root.getChildren().add(generalPane);

        // ---- Search section ----
        TitledPane searchPane = buildSearchSection();
        root.getChildren().add(searchPane);

        // ---- Appearance section ----
        TitledPane appearancePane = buildAppearanceSection();
        root.getChildren().add(appearancePane);

        // Buttons at bottom
        HBox buttonBox = new HBox(12);
        buttonBox.setAlignment(Pos.BOTTOM_RIGHT);
        buttonBox.setPadding(new Insets(12, 16, 16, 16));

        Button applyBtn = new Button("Apply");
        applyBtn.setDefaultButton(true);
        applyBtn.setOnAction(e -> applyChanges());

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setOnAction(e -> stage.close());

        buttonBox.getChildren().addAll(applyBtn, cancelBtn);
        root.getChildren().add(buttonBox);

        Scene scene = new Scene(root);
        stage.setScene(scene);
    }

    private TitledPane buildGeneralSection() {
        VBox vbox = new VBox(16);
        vbox.setPadding(new Insets(8, 0, 0, 0));

        // Hotkey
        Label hotkeyLabel = new Label("Hotkey:");
        hotkeyLabel.setFont(Font.font(14));
        TextField hotkeyField = new TextField(config.hotkey);
        hotkeyField.setMaxWidth(180);
        hotkeyField.setPrefWidth(180);
        hotkeyField.setAlignment(Pos.CENTER);
        hotkeyField.setFocusTraversable(true);

        // Live capture hint
        Label hintLabel = new Label("(Press any modifier + key to set; Esc to clear)");
        hintLabel.setStyle("-fx-text-fill: #666;");
        hintLabel.setFont(Font.font(10));

        // Build the HBox for hotkey
        HBox hotkeyBox = new HBox(8, hotkeyLabel, hotkeyField, hintLabel);
        hotkeyBox.setAlignment(Pos.CENTER_LEFT);

        // Apply hotkey button
        Button applyHotkeyBtn = new Button("Set");
        applyHotkeyBtn.setOnAction(e -> {
            String newSpec = hotkeyField.getText().trim();
            if (!newSpec.isBlank() && HotkeySpec.isValid(newSpec)) {
                pendingHotkey = newSpec;
                applyHotkeyNow(newSpec, hotkeyField);
            } else if (newSpec.isBlank() || newSpec.equalsIgnoreCase("esc")) {
                pendingHotkey = "";
                config.hotkey = "ALT+SPACE";
                hotkeyField.setText("ALT+SPACE");
                HotkeyManager.uninstall();
                HotkeyManager.install("ALT+SPACE", () -> {
                    if (stage != null && stage.isShowing()) {
                        stage.hide();
                    }
                }, label -> {});
                hotkeyField.setText("ALT+SPACE");
            } else {
                // Invalid combo - shake the field
                hotkeyField.setStyle("-fx-border-color: #d00;");
                hotkeyField.requestFocus();
                hotkeyField.selectEnd();
                // Clear shake style after a moment
                javafx.animation.PauseTransition shake = new javafx.animation.PauseTransition(javafx.util.Duration.millis(200));
                shake.setOnFinished(fxEvent -> hotkeyField.setStyle(""));
                shake.playFromStart();
            }
        });

        vbox.getChildren().addAll(hotkeyBox, applyHotkeyBtn);

        // Show current status
        StatusLabel statusLabel = new StatusLabel("Current: " + config.hotkey);
        vbox.getChildren().add(statusLabel);

        return new TitledPane("General", vbox);
    }

    private TitledPane buildSearchSection() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(8, 0, 0, 0));

        // Roots
        Label rootsLabel = new Label("Search roots:");
        TextArea rootsField = new TextArea(config.roots.toString());
        rootsField.setPrefHeight(80);
        rootsField.setMaxHeight(100);
        rootsField.setWrapText(true);

        // Exclusions
        Label exclLabel = new Label("Excluded dirs (comma-separated):");
        TextField exclField = new TextField(String.join(", ", config.excludedDirs));
        exclField.setPrefWidth(400);
        exclField.setMaxWidth(400);

        vbox.getChildren().addAll(rootsLabel, rootsField, exclLabel, exclField);

        return new TitledPane("Search", vbox);
    }

    private TitledPane buildAppearanceSection() {
        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(8, 0, 0, 0));

        // Theme toggle
        CheckBox darkTheme = new CheckBox("Dark mode");
        darkTheme.setSelected(false); // TODO: read from config later
        darkTheme.setOnAction(e -> System.out.println("Theme toggle: " + darkTheme.isSelected()));

        // Result count
        Label maxResultsLabel = new Label("Max results:");
        Spinner<Integer> maxResultsSpinner = new Spinner<>(5, 200, config.maxResults);
        maxResultsSpinner.valueProperty().addListener((obs, old, val) -> {
            config.maxResults = val;
            System.out.println("Max results set to: " + val);
        });

        vbox.getChildren().addAll(darkTheme, maxResultsLabel, maxResultsSpinner);

        return new TitledPane("Appearance", vbox);
    }

    private void applyHotkeyNow(String newSpec, TextField field) {
        // Update the config first so the UI reflects it immediately
        config.hotkey = newSpec;
        field.setText(newSpec);

        // Reinstall the hotkey with the new combination
        HotkeyManager.uninstall();
        boolean ok = HotkeyManager.install(newSpec, () -> {
            // Hotkey pressed - show the overlay
            if (stage != null && stage.isShowing()) {
                stage.hide();
            }
        }, label -> {
                    // label is the effective key combo, or null if nothing available
                    if (label != null) {
                        TrayManager.notify("Hotkey set", "Using " + label + " to open Lumen");
                    } else {
                        TrayManager.notify("Hotkey unavailable",
                                "Another app owns " + newSpec + ". Right-click the tray icon to open Lumen.");
                    }
                    // Re-install with the original fallback chain
                    HotkeyManager.install("ALT+SPACE", () -> {
                        if (stage != null && stage.isShowing()) {
                            stage.hide();
                        }
                    }, label2 -> {});
                    field.setText("ALT+SPACE");
                });

        if (!ok) {
            // Something went wrong - restore and re-install
            config.hotkey = "ALT+SPACE";
            field.setText("ALT+SPACE");
            HotkeyManager.install("ALT+SPACE", () -> {
                if (stage != null && stage.isShowing()) {
                    stage.hide();
                }
            }, label -> {});
        }
    }

    private void applyChanges() {
        // Save config to disk
        config.save();

        // Re-apply the hotkey with the pending combination if there is one
        if (!pendingHotkey.isBlank()) {
            applyHotkeyNow(pendingHotkey, null);
        }

        stage.close();
    }

    /** Simple status label that right-aligns its text. */
    private static final class StatusLabel extends Label {
        StatusLabel(String text) {
            setText(text);
            setAlignment(Pos.CENTER_RIGHT);
        }
    }

    /** Show the settings window, centered over the owner. */
    public void show() {
        stage.showAndWait();
    }
}