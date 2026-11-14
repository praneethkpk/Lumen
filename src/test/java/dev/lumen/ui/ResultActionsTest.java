package dev.lumen.ui;

import dev.lumen.core.IndexItem;
import dev.lumen.core.ItemKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultActionsTest {

    /** Records what an action asked the overlay to do, without touching the shell. */
    private static final class Recorder implements ResultActions.Target {

        private String called;
        private String argument;

        @Override
        public void open(IndexItem item) {
            called = "open";
            argument = item.path();
        }

        @Override
        public void openAsAdmin(IndexItem item) {
            called = "admin";
            argument = item.path();
        }

        @Override
        public void reveal(IndexItem item) {
            called = "reveal";
            argument = item.path();
        }

        @Override
        public void switchTo(IndexItem item) {
            called = "switch";
            argument = item.path();
        }

        @Override
        public void copy(String text) {
            called = "copy";
            argument = text;
        }
    }

    private static IndexItem of(ItemKind kind) {
        return new IndexItem("Title", "subtitle", "C:\\target", kind);
    }

    @Test
    void aFileOffersOpenRevealAndTheTwoCopyActions() {
        Recorder recorder = new Recorder();
        List<ActionMenu.Action> actions =
                new ResultActions(recorder, path -> {
                }).forItem(of(ItemKind.FILE));

        assertEquals(List.of("Open", "Open as administrator", "Show in folder",
                "Copy path", "Copy name"), labels(actions));
    }

    @Test
    void aWindowOffersSwitchAndCopyTitle() {
        Recorder recorder = new Recorder();
        List<ActionMenu.Action> actions =
                new ResultActions(recorder, path -> {
                }).forItem(of(ItemKind.WINDOW));

        assertEquals(List.of("Switch to window", "Copy title"), labels(actions));
    }

    @Test
    void runningAnActionReachesTheTarget() {
        Recorder recorder = new Recorder();
        List<ActionMenu.Action> actions =
                new ResultActions(recorder, path -> {
                }).forItem(of(ItemKind.WINDOW));

        actions.get(0).handler().run();

        assertEquals("switch", recorder.called);
        assertEquals("C:\\target", recorder.argument);
    }

    @Test
    void openingFromTheSheetRemembersTheUsage() {
        Recorder recorder = new Recorder();
        StringBuilder remembered = new StringBuilder();
        List<ActionMenu.Action> actions =
                new ResultActions(recorder, remembered::append).forItem(of(ItemKind.APP));

        actions.get(0).handler().run();

        assertEquals("open", recorder.called);
        assertEquals("C:\\target", remembered.toString());
    }

    @Test
    void everyKindHasAtLeastOneAction() {
        Recorder recorder = new Recorder();
        ResultActions actions = new ResultActions(recorder, path -> {
        });

        for (ItemKind kind : ItemKind.values()) {
            assertTrue(!actions.forItem(of(kind)).isEmpty(), kind + " must offer actions");
        }
    }

    private static List<String> labels(List<ActionMenu.Action> actions) {
        return actions.stream().map(ActionMenu.Action::label).toList();
    }
}
