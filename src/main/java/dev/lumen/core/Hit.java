package dev.lumen.core;

/** A ranked search result. */
public record Hit(IndexItem item, int score) {
}
