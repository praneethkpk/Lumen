package dev.lumen.providers;

import dev.lumen.config.Config;
import dev.lumen.core.Hit;
import dev.lumen.core.IndexItem;
import dev.lumen.core.ItemKind;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Always-available fallback: search the web for whatever was typed. */
public final class WebSearchProvider implements Provider {

    private final Config config;

    public WebSearchProvider(Config config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "web";
    }

    @Override
    public List<String> aliases() {
        return List.of("web", "g", "google", "search");
    }

    @Override
    public int minQueryLength() {
        return 1;
    }

    @Override
    public int order() {
        return 1_000;
    }

    @Override
    public List<Hit> search(String query) {
        String q = query.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        String encoded = URLEncoder.encode(q, StandardCharsets.UTF_8).replace("+", "%20");
        String url = config.webSearchUrl.replace("%s", encoded);
        IndexItem item = new IndexItem(
                "Search the web for \"" + q + "\"",
                hostOf(config.webSearchUrl) + "   ·   Enter opens your browser",
                url,
                ItemKind.WEB);
        return List.of(new Hit(item, 0));
    }

    private static String hostOf(String template) {
        try {
            java.net.URI uri = java.net.URI.create(template.replace("%s", "x"));
            String host = uri.getHost();
            return host == null ? "the web" : host.replaceFirst("^www\\.", "");
        } catch (RuntimeException e) {
            return "the web";
        }
    }
}
