package dev.lumen.core;

/** What a result points at, which also decides what Enter does with it. */
public enum ItemKind {
    /** A shortcut or executable discovered in the Start Menu. */
    APP,
    FILE,
    FOLDER,
    /** An evaluated expression; Enter copies the value. */
    CALC,
    /** A web search; Enter opens the browser. */
    WEB,
    /** An already-open top-level window; Enter switches to it instead of launching. */
    WINDOW
}
