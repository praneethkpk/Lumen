package dev.lumen.core;

import dev.lumen.config.Config;
import dev.lumen.providers.Provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges and ranks provider results.
 *
 * <p>Runs on the JavaFX thread; the whole scan is a few milliseconds for six-figure
 * indexes, which is why keystrokes get results with no async plumbing.
 *
 * <p>Query parsing and provider routing are adapted from quick-search's query coordinator:
 * the query is parsed once, an optional leading alias targets one provider, providers below
 * their minimum query length are skipped, and a failing provider is isolated instead of
 * taking down the whole search.
 */
public final class SearchEngine {

    private final List<Provider> providers;
    private final Config config;

    public SearchEngine(List<Provider> providers, Config config) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(Provider::order))
                .toList();
        this.config = config;
    }

    public List<Hit> search(String rawQuery) {
        SearchQuery parsed = SearchQuery.parse(rawQuery);

        if (parsed.isEmpty()) {
            for (Provider provider : providers) {
                List<Hit> hits = provider.initial();
                if (!hits.isEmpty()) {
                    return hits;
                }
            }
            return List.of();
        }

        Route route = route(parsed);
        SearchQuery query = route == null ? parsed : route.query();
        List<Provider> candidates = route == null ? providers : List.of(route.provider());

        Map<String, Hit> best = new LinkedHashMap<>(64);
        for (Provider provider : candidates) {
            if (!query.tokens().isEmpty()
                    && query.normalized().length() < provider.minQueryLength()) {
                continue;
            }
            List<Hit> hits;
            try {
                hits = provider.search(query);
            } catch (RuntimeException e) {
                // One broken source must never make the launcher look dead.
                System.err.println("[search] provider " + provider.id() + " failed: " + e);
                continue;
            }
            for (Hit hit : hits) {
                String key = hit.item().kind() + "|"
                        + SearchText.lower(hit.item().path());
                best.merge(key, hit, (a, b) -> a.score() >= b.score() ? a : b);
            }
        }

        List<Hit> ranked = new ArrayList<>(best.values());
        ranked.sort(Comparator
                .comparingInt(Hit::score).reversed()
                .thenComparingInt(h -> h.item().title().length())
                .thenComparing(h -> SearchText.lower(h.item().title()))
                .thenComparing(h -> SearchText.lower(h.item().path())));

        int limit = Math.min(ranked.size(), config.maxResults);
        return limit == ranked.size() ? ranked : new ArrayList<>(ranked.subList(0, limit));
    }

    /**
     * A leading word that names a provider routes the query there. This is the cheap half of
     * quick-search's alias system: {@code apps chrome}, {@code web cats}, {@code files report}.
     */
    private Route route(SearchQuery query) {
        if (query.tokens().isEmpty()) {
            return null;
        }
        String first = query.tokens().get(0);
        for (Provider provider : providers) {
            for (String alias : provider.aliases()) {
                if (first.equals(SearchText.normalize(alias))) {
                    SearchQuery routed = query.withoutFirstToken()
                            .withKinds(provider.aliasKinds(first));
                    return new Route(provider, routed);
                }
            }
        }
        return null;
    }

    private record Route(Provider provider, SearchQuery query) {
    }
}
