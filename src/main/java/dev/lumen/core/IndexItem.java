package dev.lumen.core;

/**
 * One searchable thing.
 *
 * @param title    what the user sees first (short)
 * @param subtitle supporting line (path, "Application", …)
 * @param path     what gets opened: a file system path or a URL
 * @param kind     drives the action taken on Enter
 */
public record IndexItem(String title, String subtitle, String path, ItemKind kind) {

    public static IndexItem app(String title, String target) {
        return new IndexItem(title, "Application", target, ItemKind.APP);
    }

    public static IndexItem file(String title, String path, String parent) {
        return new IndexItem(title, parent, path, ItemKind.FILE);
    }

    public static IndexItem folder(String title, String path, String parent) {
        return new IndexItem(title, parent, path, ItemKind.FOLDER);
    }
}
