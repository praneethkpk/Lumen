package dev.lumen.platform;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * Tray icon so the app is discoverable even when the hotkey is unavailable, and so it has
 * an obvious way to quit.
 *
 * <p>The tooltip and a disabled menu row always show the live hotkey state. A launcher that
 * silently loses its shortcut is indistinguishable from a crashed one; this is the cheapest
 * way to make the difference visible.
 */
public final class TrayManager {

    private static final String TITLE = "Lumen";

    private static TrayIcon trayIcon;
    private static MenuItem statusItem;
    private static String pendingCaption;
    private static String pendingText;
    private static volatile String statusText = "Hotkey: starting";

    private TrayManager() {
    }

    /**
     * Shows a tray balloon. Safe to call before the icon exists: the message is queued and
     * shown as soon as the tray finishes initialising.
     */
    public static void notify(String caption, String text) {
        EventQueue.invokeLater(() -> {
            if (trayIcon == null) {
                pendingCaption = caption;
                pendingText = text;
                return;
            }
            trayIcon.displayMessage(caption, text, TrayIcon.MessageType.INFO);
        });
    }

    /** Updates the tooltip and the status row. Safe from any thread, including before install. */
    public static void setStatus(String text) {
        statusText = text == null || text.isBlank() ? "Hotkey: starting" : text;
        if (trayIcon == null && statusItem == null) {
            // Do not touch EventQueue here: the hotkey status arrives before the tray is
            // created, and initialising AWT at that point would slow start-up for no reason.
            // install() applies the pending text when the icon is added.
            return;
        }
        EventQueue.invokeLater(() -> {
            String current = statusText;
            if (statusItem != null) {
                statusItem.setLabel(current);
            }
            if (trayIcon != null) {
                trayIcon.setToolTip(TITLE + " — " + current);
            }
        });
    }

    public static void install(Runnable openAction, Runnable reindexAction, Runnable quitAction) {
        if (!SystemTray.isSupported()) {
            System.err.println("[tray] system tray not supported");
            return;
        }
        EventQueue.invokeLater(() -> {
            try {
                PopupMenu menu = new PopupMenu();

                statusItem = new MenuItem(statusText);
                statusItem.setEnabled(false);

                MenuItem open = new MenuItem("Open search bar");
                open.addActionListener(e -> openAction.run());

                MenuItem reindex = new MenuItem("Rebuild index");
                reindex.addActionListener(e -> reindexAction.run());

                MenuItem quit = new MenuItem("Quit");
                quit.addActionListener(e -> quitAction.run());

                menu.add(statusItem);
                menu.addSeparator();
                menu.add(open);
                menu.add(reindex);
                menu.addSeparator();
                menu.add(quit);

                TrayIcon icon = new TrayIcon(createIcon(32), TITLE + " — " + statusText, menu);
                icon.setImageAutoSize(true);
                icon.addActionListener(e -> openAction.run());

                SystemTray.getSystemTray().add(icon);
                trayIcon = icon;
                System.out.println("[tray] icon added");

                if (pendingText != null) {
                    icon.displayMessage(pendingCaption, pendingText, TrayIcon.MessageType.INFO);
                    pendingCaption = null;
                    pendingText = null;
                }
            } catch (AWTException | RuntimeException e) {
                System.err.println("[tray] could not create tray icon: " + e.getMessage());
            }
        });
    }

    public static void uninstall() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
        statusItem = null;
    }

    private static Image createIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x6E, 0xA8, 0xFE));
            g.setStroke(new BasicStroke(size * 0.11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float inset = size * 0.18f;
            float diameter = size * 0.46f;
            g.drawOval((int) inset, (int) inset, (int) diameter, (int) diameter);
            g.drawLine((int) (inset + diameter - 1), (int) (inset + diameter - 1),
                    (int) (size - inset - 1), (int) (size - inset - 1));
        } finally {
            g.dispose();
        }
        return image;
    }
}
