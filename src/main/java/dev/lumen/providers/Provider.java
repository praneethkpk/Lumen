package dev.lumen.providers;

import dev.lumen.core.Hit;
import dev.lumen.core.ItemKind;
import dev.lumen.core.SearchQuery;

import java.util.List;
import java.util.Set;

/**
 * A source of results.
 *
 * <p>Adding a new capability (clipboard history, notes, AI actions, browser bookmarks)
 * means writing one class and registering it in {@code LumenApp} — the UI and ranking
 * do not change.
 *
 * <p>The metadata methods are adapted from quick-search's section registry, but kept on each
 * provider instead of in one central table: with a handful of providers, a registry would be
 * indirection without a payoff. Aliases, minimum query length and ordering live next to the
 * code they describe.
 */
public interface Provider {

    String id();

    List<Hit> search(String query);

    /** Context-aware entry point. Providers that only need the text can ignore it. */
    default List<Hit> search(SearchQuery query) {
        return search(query.raw());
    }

    /** Results shown when the query is empty. Only the first non-empty one is used. */
    default List<Hit> initial() {
        return List.of();
    }

    /** Prefix words that route a query to this provider, e.g. {@code web cats}. */
    default List<String> aliases() {
        return List.of();
    }

    /** Kind filters implied by an alias, e.g. {@code apps} means app results only. */
    default Set<ItemKind> aliasKinds(String alias) {
        return Set.of();
    }

    /** Skip this provider for queries shorter than this, unless the query is filter-only. */
    default int minQueryLength() {
        return 0;
    }

    /** Lower runs first when building the initial (empty-query) list. */
    default int order() {
        return 100;
    }
}
