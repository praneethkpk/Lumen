package dev.lumen.ui;

import dev.lumen.core.Hit;
import dev.lumen.core.IndexItem;
import dev.lumen.platform.ShellIcons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * One result row: icon or kind badge, title, subtitle, and the Enter action.
 *
 * <p>Real shell icons are extracted in the background ({@link ShellIcons}), so a row paints
 * its badge immediately and upgrades to the icon when it arrives. The cell is reused by
 * {@code ListView}, so a late callback checks that the row still shows the same result before
 * touching it.
 */
public final class ResultCell extends ListCell<Hit> {

    private static final double ICON_SIZE = 22;

    private final HBox root = new HBox(12);
    private final StackPane badge = new StackPane();
    private final Label badgeGlyph = new Label();
    private final ImageView icon = new ImageView();
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
        icon.setFitWidth(ICON_SIZE);
        icon.setFitHeight(ICON_SIZE);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);
        icon.setVisible(false);
        badge.getChildren().addAll(icon, badgeGlyph);

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
        title.setText(item.title());
        subtitle.setText(item.subtitle());
        action.setText(actionLabel(item));

        Image ready = ShellIcons.cached(item);
        if (ready != null) {
            showIcon(ready);
        } else {
            showBadge(item);
            ShellIcons.request(item, image -> {
                // A recycled cell may already be showing a different result.
                if (getItem() == hit) {
                    showIcon(image);
                }
            });
        }
        setGraphic(root);
    }

    private void showIcon(Image image) {
        if (image == null) {
            return;
        }
        icon.setImage(image);
        icon.setVisible(true);
        badgeGlyph.setVisible(false);
        badge.getStyleClass().setAll("badge", "badge-icon");
    }

    private void showBadge(IndexItem item) {
        icon.setImage(null);
        icon.setVisible(false);
        badgeGlyph.setVisible(true);
        badgeGlyph.setText(glyph(item));
        badge.getStyleClass().setAll("badge",
                "badge-" + item.kind().name().toLowerCase(Locale.ROOT));
    }

    private static String glyph(IndexItem item) {
        return switch (item.kind()) {
            case APP -> "▶";
            case FILE -> "▤";
            case FOLDER -> "▸";
            case CALC -> "=";
            case WEB -> "↗";
            case WINDOW -> "▣";
        };
    }

    private static String actionLabel(IndexItem item) {
        return switch (item.kind()) {
            case APP, FILE, FOLDER -> "open";
            case CALC -> "copy";
            case WEB -> "search";
            case WINDOW -> "switch";
        };
    }
}
