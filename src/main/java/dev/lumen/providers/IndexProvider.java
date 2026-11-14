package dev.lumen.providers;

import dev.lumen.config.Config;
import dev.lumen.core.FuzzyMatcher;
import dev.lumen.core.Hit;
import dev.lumen.core.IndexEntry;
import dev.lumen.core.IndexStore;
import dev.lumen.core.ItemKind;
import dev.lumen.core.SearchQuery;
import dev.lumen.core.SearchText;
import dev.lumen.core.UsageStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Searches the live app/file index.
 *
 * <p>Every whitespace-separated token must match either the name or the full path, so
 * "chrome setup" and "proj report" both behave the way people expect. Filter tokens
 * ({@code ext:}, {@code kind:}, {@code in:}, {@code -word}) are applied before scoring.
 *
 * <p>Scoring is a match-priority ladder adapted from quick-search: exact name, name prefix,
 * word prefix, contains, compact (separator-free), acronym, then subsequence. Wide bands keep
 * a strong match ahead of a pile of weak ones; usage and path depth only break ties.
 */
public final class IndexProvider implements Provider {

    /** Upper bound of the app bonus + usage boost, used to skip hopeless candidates. */
    private static final int MAX_BONUS = 12 + 78; // app bonus + (20 opens * 3) + 24h recency

    /** Baseline for filter-only queries such as {@code ext:pdf}. */
    private static final int FILTER_ONLY_BASE = 100;

    private final IndexStore store;
    private final UsageStore usage;
    private final Config config;

    public IndexProvider(IndexStore store, UsageStore usage, Config config) {
        this.store = store;
        this.usage = usage;
        this.config = config;
    }

    @Override
    public String id() {
        return "index";
    }

    @Override
    public List<String> aliases() {
        return List.of("apps", "app", "files", "file", "folders", "folder", "dir", "dirs");
    }

    @Override
    public Set<ItemKind> aliasKinds(String alias) {
        return switch (SearchText.normalize(alias)) {
            case "apps", "app" -> Set.of(ItemKind.APP);
            case "files", "file" -> Set.of(ItemKind.FILE, ItemKind.FOLDER);
            case "folders", "folder", "dir", "dirs" -> Set.of(ItemKind.FOLDER);
            default -> Set.of();
        };
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<Hit> search(String query) {
        return search(SearchQuery.parse(query));
    }

    @Override
    public List<Hit> search(SearchQuery query) {
        List<String> tokens = query.tokens();
        if (tokens.isEmpty() && !query.hasFilters()) {
            return initial();
        }

        // Bounded top-K instead of "collect every match, then sort". On a single-character
        // query against a 120k index that difference is 90 ms p95 vs ~2 ms.
        int limit = Math.min(config.maxResults * 3, 400);
        PriorityQueue<Hit> top = new PriorityQueue<>(limit + 1, Comparator.comparingInt(Hit::score));
        int threshold = Integer.MIN_VALUE;

        for (IndexEntry entry : store.entries()) {
            if (!query.accepts(entry)) {
                continue;
            }

            int total = tokens.isEmpty() ? FILTER_ONLY_BASE : 0;
            boolean matchedAll = true;

            for (String token : tokens) {
                int best = FuzzyMatcher.scoreToken(
                        token, entry.nameLower(), entry.nameAcronym(), entry.pathLower());
                if (best < 0) {
                    matchedAll = false;
                    break;
                }
                total += best;
            }

            if (!matchedAll || total + MAX_BONUS < threshold) {
                continue;
            }

            // Once the heap is full, equal-scoring candidates can't improve it. Skipping them
            // before the usage lookup is what keeps a query that matches the whole corpus fast.
            if (top.size() >= limit && total <= threshold) {
                continue;
            }

            if (entry.item().kind() == ItemKind.APP) {
                total += 12;
            }
            total += usage.boostNormalized(entry.pathLower());
            total -= Math.min(entry.pathDepth(), 12);

            if (total < threshold) {
                continue;
            }

            top.add(new Hit(entry.item(), total));
            if (top.size() > limit) {
                top.poll();
            }
            if (top.size() == limit && top.peek() != null) {
                threshold = top.peek().score();
            }
        }

        return new ArrayList<>(top);
    }

    /** Empty query: recently and frequently used apps, then the rest alphabetically. */
    @Override
    public List<Hit> initial() {
        List<IndexEntry> apps = new ArrayList<>();
        for (IndexEntry entry : store.entries()) {
            if (entry.item().kind() == ItemKind.APP) {
                apps.add(entry);
            }
        }

        apps.sort(Comparator
                .comparingLong((IndexEntry e) -> usage.lastUsedNormalized(e.pathLower())).reversed()
                .thenComparing(e -> e.nameLower()));

        int limit = Math.min(8, apps.size());
        List<Hit> hits = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            IndexEntry entry = apps.get(i);
            hits.add(new Hit(entry.item(), usage.boostNormalized(entry.pathLower())));
        }
        return hits;
    }

    public Config config() {
        return config;
    }
}
