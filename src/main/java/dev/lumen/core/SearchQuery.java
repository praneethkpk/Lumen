package dev.lumen.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A parsed query: free-text tokens plus optional filters.
 *
 * <p>Adapted from quick-search's query context and alias parser. Parsing once per keystroke
 * (instead of once per provider) keeps filters, aliases and tokenization consistent across
 * every result source.
 *
 * <pre>
 *   report ext:pdf              files named with "report" and a .pdf extension
 *   invoice -draft              excludes anything containing "draft"
 *   kind:folder projects        only folders
 *   in:downloads setup          only paths containing "downloads"
 *   apps chrome                 route to app results only
 * </pre>
 */
public record SearchQuery(
        String raw,
        List<String> tokens,
        Set<ItemKind> kinds,
        List<String> extensions,
        List<String> pathHints,
        List<String> exclusions) {

    public SearchQuery {
        tokens = List.copyOf(tokens);
        kinds = Set.copyOf(kinds);
        extensions = List.copyOf(extensions);
        pathHints = List.copyOf(pathHints);
        exclusions = List.copyOf(exclusions);
    }

    public static SearchQuery parse(String rawQuery) {
        String raw = rawQuery == null ? "" : rawQuery;
        List<String> tokens = new ArrayList<>(4);
        Set<ItemKind> kinds = new LinkedHashSet<>();
        List<String> extensions = new ArrayList<>(2);
        List<String> pathHints = new ArrayList<>(2);
        List<String> exclusions = new ArrayList<>(2);

        for (String part : raw.trim().split("\\s+")) {
            if (part.isEmpty()) {
                continue;
            }
            String token = SearchText.normalize(part);

            if (token.length() > 1 && token.charAt(0) == '-') {
                exclusions.add(token.substring(1));
                continue;
            }
            if (token.startsWith("ext:") || token.startsWith("type:")) {
                // `type:` is accepted as an alias for `ext:`; `kind:` covers item kinds.
                String value = token.substring(token.indexOf(':') + 1);
                for (String extension : value.split(",")) {
                    String cleaned = extension.startsWith(".")
                            ? extension.substring(1)
                            : extension;
                    if (!cleaned.isBlank()) {
                        extensions.add(cleaned);
                    }
                }
                continue;
            }
            if (token.startsWith("kind:")) {
                for (String value : token.substring(5).split(",")) {
                    ItemKind kind = kindOf(value);
                    if (kind != null) {
                        kinds.add(kind);
                    }
                }
                continue;
            }
            if (token.startsWith("in:")) {
                String value = token.substring(3);
                if (!value.isBlank()) {
                    pathHints.add(value);
                }
                continue;
            }

            tokens.add(token);
        }

        return new SearchQuery(raw, tokens, kinds, extensions, pathHints, exclusions);
    }

    private static ItemKind kindOf(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "app", "apps", "application", "applications" -> ItemKind.APP;
            case "file", "files", "document", "documents" -> ItemKind.FILE;
            case "folder", "folders", "dir", "dirs", "directory", "directories" -> ItemKind.FOLDER;
            case "window", "windows", "win" -> ItemKind.WINDOW;
            default -> null;
        };
    }

    public boolean isEmpty() {
        return raw.isBlank();
    }

    public boolean hasFilters() {
        return !kinds.isEmpty() || !extensions.isEmpty()
                || !pathHints.isEmpty() || !exclusions.isEmpty();
    }

    /** A query that is only filters, e.g. {@code ext:pdf}; tokens are then empty. */
    public boolean isFilterOnly() {
        return tokens.isEmpty() && hasFilters();
    }

    /** Everything the user meant to search for, filters removed. */
    public String normalized() {
        return String.join(" ", tokens);
    }

    /** Separator-free form used for compact matching. */
    public String compact() {
        StringBuilder out = new StringBuilder(normalized().length());
        for (String token : tokens) {
            out.append(token);
        }
        return out.toString();
    }

    /** Drop the leading alias token after it has been routed to a provider. */
    public SearchQuery withoutFirstToken() {
        if (tokens.isEmpty()) {
            return this;
        }
        List<String> rest = tokens.subList(1, tokens.size());
        String remainingRaw = String.join(" ", rest);
        return new SearchQuery(remainingRaw, rest, kinds, extensions, pathHints, exclusions);
    }

    /** Narrow a filtered query when an alias implies a kind, e.g. {@code apps}. */
    public SearchQuery withKinds(Set<ItemKind> implied) {
        if (implied.isEmpty()) {
            return this;
        }
        Set<ItemKind> merged = new LinkedHashSet<>(kinds);
        merged.addAll(implied);
        return new SearchQuery(raw, tokens, merged, extensions, pathHints, exclusions);
    }

    /** Whether an index entry passes the non-text filters. */
    public boolean accepts(IndexEntry entry) {
        IndexItem item = entry.item();

        if (!kinds.isEmpty() && !kinds.contains(item.kind())) {
            return false;
        }
        for (String extension : extensions) {
            if (!entry.pathLower().endsWith("." + extension)) {
                return false;
            }
        }
        for (String hint : pathHints) {
            if (!entry.pathLower().contains(hint)) {
                return false;
            }
        }
        for (String excluded : exclusions) {
            if (entry.nameLower().contains(excluded) || entry.pathLower().contains(excluded)) {
                return false;
            }
        }
        return true;
    }
}
