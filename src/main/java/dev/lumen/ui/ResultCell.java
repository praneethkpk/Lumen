package dev.lumen.ui;

import dev.lumen.core.Hit;
import dev.lumen.core.IndexItem;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** One result row: kind badge, title, subtitle, and the Enter action. */
public final class ResultCell extends ListCell<Hit> {

    private final HBox root = new HBox(12);
    private final StackPane badge = new StackPane();
    private final Label badgeGlyph = new Label();
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final Label action = new Label();
    private final VBox text = new VBox(2, title, subtitle);

    public ResultCell() {
        getStyleClass().add("result-cell");

        badge.getStyleClass().add("badge");
        badge.setMinSize(30, 30);
        badge.setPrefSize(30, 30);
        badge.setMaxSize(30, 30);
        badgeGlyph.getStyleClass().add("badge-glyph");
        badge.getChildren().add(badgeGlyph);

        title.getStyleClass().add("item-title");
        subtitle.getStyleClass().add("item-subtitle");
        action.getStyleClass().add("item-action");

        // Let long paths ellipsize instead of pushing the row wider than the window.
        text.setMinWidth(0);
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        subtitle.setMinWidth(0);
        subtitle.setMaxWidth(Double.MAX_VALUE);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.NEVER);

        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(0, 10, 0, 2));
        root.getChildren().addAll(badge, text, spacer, action);

        setGraphic(root);
    }

    @Override
    protected void updateItem(Hit hit, boolean empty) {
        super.updateItem(hit, empty);

        if (empty || hit == null) {
            setGraphic(null);
            return;
        }

        IndexItem item = hit.item();
        badgeGlyph.setText(glyph(item));
        badge.getStyleClass().setAll("badge", "badge-" + item.kind().name().toLowerCase());
        title.setText(item.title());
        subtitle.setText(item.subtitle());
        action.setText(actionLabel(item));
        setGraphic(root);
    }

    private static String glyph(IndexItem item) {
        return switch (item.kind()) {
            case APP -> "▶";
            case FILE -> "▤";
            case FOLDER -> "▸";
            case CALC -> "=";
            case WEB -> "↗";
        };
    }

    private static String actionLabel(IndexItem item) {
        return switch (item.kind()) {
            case APP, FILE, FOLDER -> "open";
            case CALC -> "copy";
            case WEB -> "search";
        };
    }
}
