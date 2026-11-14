package dev.lumen.providers;

import dev.lumen.core.FuzzyMatcher;
import dev.lumen.core.Hit;
import dev.lumen.core.IndexItem;
import dev.lumen.core.ItemKind;
import dev.lumen.core.SearchQuery;
import dev.lumen.core.SearchText;
import dev.lumen.platform.OpenWindows;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Finds windows that are already open and switches to them on Enter.
 *
 * <p>This is the difference between "launch an app" and "go to the app I am already using".
 * Window titles are searched directly, so typing a document name (for example part of a
 * browser tab or an editor window title) jumps straight there instead of hunting through the
 * taskbar. Windows ordering comes from the z-order, so among equally good matches the app
 * the user just left ranks first.
 */
public final class WindowProvider implements Provider {

    /** Windows shown alongside apps and files for an ordinary query. */
    private static final int MATCH_LIMIT = 6;

    /** Windows shown when the query is explicitly about windows ({@code win …}). */
    private static final int ALIAS_LIMIT = 20;

    /** Small bonus for z-order position, so the most recent window wins ties. */
    private static final int Z_ORDER_BONUS = 10;

    private final Supplier<List<OpenWindows.Window>> source;

    public WindowProvider() {
        this(OpenWindows::list);
    }

    WindowProvider(Supplier<List<OpenWindows.Window>> source) {
        this.source = source;
    }

    @Override
    public String id() {
        return "windows";
    }

    @Override
    public List<String> aliases() {
        return List.of("windows", "window", "win");
    }

    @Override
    public Set<ItemKind> aliasKinds(String alias) {
        return Set.of(ItemKind.WINDOW);
    }

    @Override
    public int minQueryLength() {
        return 2;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public List<Hit> search(String query) {
        return search(SearchQuery.parse(query));
    }

    @Override
    public List<Hit> search(SearchQuery query) {
        boolean explicit = query.kinds().contains(ItemKind.WINDOW);
        if (!query.kinds().isEmpty() && !explicit) {
            return List.of();
        }

        List<String> tokens = query.tokens();
        if (!tokens.isEmpty() && String.join(" ", tokens).length() < minQueryLength()) {
            return List.of();
        }

        List<OpenWindows.Window> windows;
        try {
            windows = source.get();
        } catch (RuntimeException e) {
            System.err.println("[windows] provider failed: " + e.getMessage());
            return List.of();
        }
        if (windows.isEmpty()) {
            return List.of();
        }

        List<Hit> hits = new ArrayList<>(16);
        for (int index = 0; index < windows.size(); index++) {
            OpenWindows.Window window = windows.get(index);
            if (window.title() == null || window.title().isBlank()) {
                continue;
            }

            int score = tokens.isEmpty() ? 0 : score(window, tokens);
            if (score < 0) {
                continue;
            }

            int rank = score + Math.max(0, Z_ORDER_BONUS - index);
            hits.add(new Hit(item(window), rank));
        }

        hits.sort(Comparator.comparingInt(Hit::score).reversed()
                .thenComparingInt(hit -> hit.item().title().length())
                .thenComparing(hit -> SearchText.lower(hit.item().title())));

        int limit = explicit ? ALIAS_LIMIT : MATCH_LIMIT;
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    /** Every token must match the title or the process name, mirroring the file provider. */
    private static int score(OpenWindows.Window window, List<String> tokens) {
        String title = SearchText.normalize(window.title());
        String acronym = SearchText.acronym(title);
        String process = SearchText.normalize(window.process());
        int total = 0;

        for (String token : tokens) {
            int best = FuzzyMatcher.scoreToken(token, title, acronym, process);
            if (best < 0) {
                return -1;
            }
            total += best;
        }
        return total;
    }

    private static IndexItem item(OpenWindows.Window window) {
        String subtitle = window.process().isBlank()
                ? "Running window"
                : window.process() + " · running";
        return new IndexItem(window.title(), subtitle, Long.toString(window.handle()),
                ItemKind.WINDOW);
    }
}
