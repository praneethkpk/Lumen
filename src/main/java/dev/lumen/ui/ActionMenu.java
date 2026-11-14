package dev.lumen.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * A keyboard-first action sheet over the results.
 *
 * <p>Rendered inside the overlay scene rather than as a native popup: a popup is a separate
 * window, and while it is open Windows reports it — not Lumen — as the foreground window, so
 * the overlay's native-focus poll would hide the launcher out from under the menu. Keeping it
 * in-scene also means one focus owner and no extra window to manage.
 *
 * <p>Keys are handled by the overlay's scene filter; this class only owns selection and
 * painting.
 */
final class ActionMenu extends VBox {

    /** One row. {@code hint} is the shortcut the same action has outside the menu. */
    record Action(String label, String hint, Runnable handler) {
    }

    private static final double ROW_HEIGHT = 34;

    private final ListView<Action> list = new ListView<>();
    private final Runnable onClose;

    ActionMenu(String title, List<Action> actions, Runnable onClose) {
        this.onClose = onClose;

        getStyleClass().add("action-menu");
        setMaxWidth(320);
        setPrefWidth(320);
        setMinWidth(320);
        setFillWidth(true);

        Label header = new Label(title);
        header.getStyleClass().add("action-title");
        header.setMaxWidth(Double.MAX_VALUE);
        header.setPadding(new Insets(2, 10, 4, 10));

        list.getStyleClass().add("action-list");
        list.setFocusTraversable(false);
        list.setFixedCellSize(ROW_HEIGHT);
        double listHeight = actions.size() * ROW_HEIGHT + 4;
        list.setPrefHeight(listHeight);
        list.setMinHeight(listHeight);
        list.setMaxHeight(listHeight);
        list.setCellFactory(view -> new ActionCell());
        list.getItems().setAll(actions);
        list.getSelectionModel().selectFirst();
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                run();
            }
        });

        Label footer = new Label("↑↓ choose · ↵ run · Esc back");
        footer.getStyleClass().add("action-footer");
        footer.setPadding(new Insets(4, 10, 2, 10));

        getChildren().addAll(header, list, footer);
    }

    /** Routes a key. Returns true when the menu consumed it. */
    boolean handleKey(KeyEvent event) {
        switch (event.getCode()) {
            case DOWN -> {
                move(1);
                return true;
            }
            case UP -> {
                move(-1);
                return true;
            }
            case ENTER -> {
                run();
                return true;
            }
            case ESCAPE -> {
                onClose.run();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void move(int delta) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int current = list.getSelectionModel().getSelectedIndex();
        list.getSelectionModel().select(Math.floorMod(current + delta, size));
        list.scrollTo(list.getSelectionModel().getSelectedIndex());
    }

    private void run() {
        Action action = list.getSelectionModel().getSelectedItem();
        if (action != null) {
            action.handler().run();
        }
    }

    /** One action row: name on the left, its direct shortcut on the right. */
    private static final class ActionCell extends ListCell<Action> {

        private final Label name = new Label();
        private final Label hint = new Label();
        private final HBox row = new HBox(10);

        ActionCell() {
            getStyleClass().add("action-cell");
            name.getStyleClass().add("action-name");
            hint.getStyleClass().add("action-hint");

            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.NEVER);

            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(0, 10, 0, 10));
            row.getChildren().addAll(name, spacer, hint);
            setGraphic(row);
            setPadding(Insets.EMPTY);
        }

        @Override
        protected void updateItem(Action action, boolean empty) {
            super.updateItem(action, empty);
            if (empty || action == null) {
                setGraphic(null);
                return;
            }
            name.setText(action.label());
            hint.setText(action.hint());
            hint.setVisible(!action.hint().isBlank());
            hint.setManaged(!action.hint().isBlank());
            setGraphic(row);
        }
    }
}
