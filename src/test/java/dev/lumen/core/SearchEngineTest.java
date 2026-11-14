package dev.lumen.core;

import dev.lumen.config.Config;
import dev.lumen.providers.Provider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchEngineTest {

    @Test
    void aliasRoutesToASingleProvider() {
        FakeProvider index = new FakeProvider("index", 10, List.of("apps"), 0);
        FakeProvider web = new FakeProvider("web", 20, List.of("g"), 1);
        web.results = List.of(hit("cats", "https://example.com/cats", 10));
        SearchEngine engine = new SearchEngine(List.of(index, web), new Config());

        List<Hit> hits = engine.search("g cats");

        assertEquals(1, hits.size());
        assertEquals("cats", hits.get(0).item().title());
        assertTrue(index.queries.isEmpty(), "the non-routed provider must not run");
        assertEquals(List.of("cats"), web.queries);
    }

    @Test
    void providersBelowMinimumQueryLengthAreSkipped() {
        FakeProvider shortQueries = new FakeProvider("short", 10, List.of(), 3);
        shortQueries.results = List.of(hit("result", "p", 10));
        SearchEngine engine = new SearchEngine(List.of(shortQueries), new Config());

        assertEquals(0, engine.search("ab").size());
        assertTrue(shortQueries.queries.isEmpty());
        assertEquals(1, engine.search("abc").size());
        assertEquals(List.of("abc"), shortQueries.queries);
    }

    @Test
    void filterOnlyQueriesBypassMinimumLength() {
        FakeProvider provider = new FakeProvider("filtered", 10, List.of(), 5);
        provider.results = List.of(hit("result", "p", 10));
        SearchEngine engine = new SearchEngine(List.of(provider), new Config());

        assertEquals(1, engine.search("ext:pdf").size());
        assertEquals(List.of("ext:pdf"), provider.queries);
    }

    @Test
    void oneBrokenProviderDoesNotHideTheOthers() {
        FakeProvider broken = new FakeProvider("broken", 10, List.of(), 0);
        broken.fail = true;
        FakeProvider healthy = new FakeProvider("healthy", 20, List.of(), 0);
        healthy.results = List.of(hit("still here", "p", 10));
        SearchEngine engine = new SearchEngine(List.of(broken, healthy), new Config());

        List<Hit> hits = engine.search("query");

        assertEquals(1, hits.size());
        assertEquals("still here", hits.get(0).item().title());
    }

    @Test
    void initialResultsFollowProviderOrder() {
        FakeProvider later = new FakeProvider("later", 50, List.of(), 0);
        later.initial = List.of(hit("later", "p1", 10));
        FakeProvider earlier = new FakeProvider("earlier", 10, List.of(), 0);
        earlier.initial = List.of(hit("earlier", "p2", 10));
        SearchEngine engine = new SearchEngine(List.of(later, earlier), new Config());

        List<Hit> hits = engine.search("");

        assertEquals(1, hits.size());
        assertEquals("earlier", hits.get(0).item().title());
    }

    @Test
    void duplicateTargetsAreMergedWithTheBestScore() {
        FakeProvider first = new FakeProvider("first", 10, List.of(), 0);
        first.results = List.of(hit("low", "same-path", 10));
        FakeProvider second = new FakeProvider("second", 20, List.of(), 0);
        second.results = List.of(hit("high", "same-path", 90));
        SearchEngine engine = new SearchEngine(List.of(first, second), new Config());

        List<Hit> hits = engine.search("query");

        assertEquals(1, hits.size());
        assertEquals("high", hits.get(0).item().title());
    }

    private static Hit hit(String title, String path, int score) {
        return new Hit(new IndexItem(title, "subtitle", path, ItemKind.FILE), score);
    }

    private static final class FakeProvider implements Provider {

        private final String id;
        private final int order;
        private final List<String> aliases;
        private final int minQueryLength;
        private final List<String> queries = new ArrayList<>();

        private List<Hit> results = List.of();
        private List<Hit> initial = List.of();
        private boolean fail;

        private FakeProvider(String id, int order, List<String> aliases, int minQueryLength) {
            this.id = id;
            this.order = order;
            this.aliases = aliases;
            this.minQueryLength = minQueryLength;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<Hit> search(String query) {
            queries.add(query);
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return results;
        }

        @Override
        public List<Hit> initial() {
            return initial;
        }

        @Override
        public List<String> aliases() {
            return aliases;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public int minQueryLength() {
            return minQueryLength;
        }
    }
}
