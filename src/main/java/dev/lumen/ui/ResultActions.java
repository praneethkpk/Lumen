package dev.lumen.ui;

import dev.lumen.core.IndexItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * What the action sheet offers for a given result.
 *
 * <p>Separated from {@link Overlay} so the list is data, not windowing: the overlay supplies
 * the capabilities through {@link Target} and this class only decides which ones apply to an
 * application, a file, a window, a calculation or a web query.
 */
final class ResultActions {

    /** The overlay's capabilities, so this class never touches the stage or the index. */
    interface Target {

        void open(IndexItem item);

        void openAsAdmin(IndexItem item);

        void reveal(IndexItem item);

        void switchTo(IndexItem item);

        /** Copies text and dismisses the overlay. */
        void copy(String text);
    }

    private final Target target;
    private final Consumer<String> remember;

    ResultActions(Target target, Consumer<String> remember) {
        this.target = target;
        this.remember = remember;
    }

    List<ActionMenu.Action> forItem(IndexItem item) {
        List<ActionMenu.Action> actions = new ArrayList<>(6);
        switch (item.kind()) {
            case APP, FILE, FOLDER -> {
                actions.add(new ActionMenu.Action("Open", "Enter", () -> {
                    remember.accept(item.path());
                    target.open(item);
                }));
                actions.add(new ActionMenu.Action("Open as administrator", "",
                        () -> target.openAsAdmin(item)));
                actions.add(new ActionMenu.Action("Show in folder", "Ctrl+Enter",
                        () -> target.reveal(item)));
                actions.add(new ActionMenu.Action("Copy path", "",
                        () -> target.copy(item.path())));
                actions.add(new ActionMenu.Action("Copy name", "",
                        () -> target.copy(item.title())));
            }
            case WINDOW -> {
                actions.add(new ActionMenu.Action("Switch to window", "Enter",
                        () -> target.switchTo(item)));
                actions.add(new ActionMenu.Action("Copy title", "",
                        () -> target.copy(item.title())));
            }
            case CALC -> actions.add(new ActionMenu.Action("Copy result", "Enter",
                    () -> target.copy(item.path())));
            case WEB -> actions.add(new ActionMenu.Action("Search the web", "Enter",
                    () -> target.open(item)));
        }
        return actions;
    }
}
