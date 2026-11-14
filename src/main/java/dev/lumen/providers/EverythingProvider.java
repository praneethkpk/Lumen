package dev.lumen.providers;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.WString;
import dev.lumen.config.Config;
import dev.lumen.core.FuzzyMatcher;
import dev.lumen.core.Hit;
import dev.lumen.core.IndexEntry;
import dev.lumen.core.IndexItem;
import dev.lumen.core.ItemKind;
import dev.lumen.core.SearchQuery;
import dev.lumen.core.SearchText;
import dev.lumen.core.UsageStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-disk name search through the Everything SDK, when Everything is installed and its
 * service/database is loaded. Feature-detected at startup; absent on machines without
 * Everything, where the app behaves exactly as before.
 *
 * <p>Everything does the disk-wide filtering; results are then scored with the same ladder
 * as the local index and ranked slightly below local roots on ties, so configured folders
 * still win when both match.
 */
public final class EverythingProvider implements Provider {

    /** Slightly below the local index's base so local roots win exact ties. */
    private static final int FULL_DISK_PENALTY = 50;
    private static final int FILTER_ONLY_BASE = 100;

    private final Config config;
    private final UsageStore usage;

    public EverythingProvider(Config config, UsageStore usage) {
        this.config = config;
        this.usage = usage;
    }

    public static boolean isAvailable() {
        try {
            Everything lib = Native.load("Everything64", Everything.class);
            return lib.Everything_IsDBLoaded();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String id() {
        return "everything";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public int minQueryLength() {
        return 2;
    }

    @Override
    public List<Hit> search(String query) {
        return search(SearchQuery.parse(query));
    }

    @Override
    public List<Hit> search(SearchQuery query) {
        if (query.isEmpty()) {
            return List.of();
        }

        Everything lib;
        try {
            lib = Native.load("Everything64", Everything.class);
        } catch (Throwable t) {
            return List.of();
        }
        if (!lib.Everything_IsDBLoaded()) {
            return List.of();
        }

        lib.Everything_Reset();
        lib.Everything_SetMatchPath(false);
        lib.Everything_SetMatchCase(false);
        lib.Everything_SetMatchWholeWord(false);
        lib.Everything_SetRegex(false);
        lib.Everything_SetMax(Math.min(config.maxResults * 2, 200));
        lib.Everything_SetOffset(0);

        String search = query.tokens().isEmpty() ? query.raw().trim() : String.join(" ", query.tokens());
        lib.Everything_SetSearchW(new WString(search));
        if (!lib.Everything_QueryW(true)) {
            return List.of();
        }

        int count = lib.Everything_GetNumResults();
        char[] buffer = new char[32768];
        List<Hit> hits = new ArrayList<>(Math.min(count, config.maxResults));
        for (int i = 0; i < count && hits.size() < config.maxResults * 2; i++) {
            int length = lib.Everything_GetResultFullPathNameW(i, buffer, buffer.length);
            if (length <= 0) {
                continue;
            }
            String path = new String(buffer, 0, length);
            if (path.isBlank()) {
                continue;
            }

            Path p = Path.of(path);
            String fileName = p.getFileName() == null ? path : p.getFileName().toString();
            String parent = p.getParent() == null ? "" : p.getParent().toString();
            boolean directory = Files.isDirectory(p);
            IndexItem item = directory
                    ? IndexItem.folder(fileName, path, parent)
                    : IndexItem.file(fileName, path, parent);

            IndexEntry entry = IndexEntry.of(item);
            if (!query.accepts(entry)) {
                continue;
            }

            List<String> tokens = query.tokens();
            int total = tokens.isEmpty() ? FILTER_ONLY_BASE : 0;
            boolean matchedAll = true;
            String nameLower = entry.nameLower();
            String acronym = entry.nameAcronym();
            String pathLower = entry.pathLower();
            for (String token : tokens) {
                int best = FuzzyMatcher.scoreToken(token, nameLower, acronym, pathLower);
                if (best < 0) {
                    matchedAll = false;
                    break;
                }
                total += best;
            }
            if (!matchedAll) {
                continue;
            }

            total -= FULL_DISK_PENALTY;
            total += usage == null ? 0 : usage.boost(path);
            if (item.kind() == ItemKind.APP) {
                total += 12;
            }
            hits.add(new Hit(item, total));
        }

        return hits;
    }

    interface Everything extends Library {
        void Everything_SetSearchW(WString lpSearch);

        void Everything_SetMatchPath(boolean bEnable);

        void Everything_SetMatchCase(boolean bEnable);

        void Everything_SetMatchWholeWord(boolean bEnable);

        void Everything_SetRegex(boolean bEnable);

        void Everything_SetMax(int dwMax);

        void Everything_SetOffset(int dwOffset);

        boolean Everything_QueryW(boolean bWait);

        int Everything_GetNumResults();

        int Everything_GetResultFullPathNameW(int dwIndex, char[] wBuf, int nBufLength);

        void Everything_Reset();

        int Everything_GetLastError();

        boolean Everything_IsDBLoaded();
    }
}