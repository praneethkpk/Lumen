package dev.lumen.core;

/**
 * An index item with pre-normalized strings.
 *
 * <p>Normalizing (case folding, diacritics, whitespace) once at index time instead of on every
 * keystroke is the single biggest win in the search hot path. The acronym and path depth are
 * cached too: both are cheap to build here and would otherwise be recomputed for every
 * candidate on every keystroke.
 */
public record IndexEntry(
        IndexItem item,
        String nameLower,
        String pathLower,
        String nameAcronym,
        int pathDepth) {

    public static IndexEntry of(IndexItem item) {
        String name = SearchText.normalize(item.title());
        String path = SearchText.normalize(item.path());
        return new IndexEntry(
                item,
                name,
                path,
                SearchText.acronym(name),
                SearchText.pathDepth(path)
        );
    }
}
