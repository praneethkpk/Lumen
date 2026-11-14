# Reference patterns from quick-search

Source reviewed: [teja2495/quick-search](https://github.com/teja2495/quick-search) (MIT).
It is an Android launcher and unified-search app built with Kotlin, Jetpack Compose and
Material 3. The UI stack does not transfer to a JavaFX desktop launcher, but its search core
does. This document records what we adopted, what we adapted, and what we deliberately left
out, so future changes don't re-litigate the same decisions.

## Adopted in this pass

| Pattern | Where it lives in quick-search | What we did | Our files |
| --- | --- | --- | --- |
| Match-priority ladder | `search/utils/SearchRankingUtils.kt` | Replaced pure additive scoring with wide bands: exact > prefix > word prefix > contains > acronym > path > subsequence. Usage and path depth only break ties. | `core/FuzzyMatcher.java` |
| Normalized search text | `search/utils/SearchTextNormalizer.kt` | Diacritic stripping, case folding, Turkish dotless-i folding, compact and acronym helpers, computed once at index time. | `core/SearchText.java`, `core/IndexEntry.java` |
| Prepared query context | `search/utils/SearchMatcher.kt` (`SearchQueryContext`) | Parse the query once per keystroke into tokens, filters and exclusions instead of re-splitting it inside every provider. | `core/SearchQuery.java` |
| Prefix aliases | `searchEngines/AliasParser.kt` | A leading word routes the query to one provider: `apps chrome`, `files report`, `web cats`, `calc 2+2`. | `core/SearchEngine.java`, `providers/Provider.java` |
| Minimum query length | `search/core/SearchSectionRegistry.kt` | Providers declare the shortest query they are willing to run, so expensive sources don't fire on one character. | `providers/Provider.java` |
| Provider metadata | `SearchSectionDefinition` | Aliases, implied kinds, order and minimum length live next to each provider instead of in a central table (see "Rejected" below). | `providers/Provider.java` |
| Exact first, fuzzy last | `search/fuzzy/FuzzySearchPolicy.kt`, `UnifiedResultRanker.kt` | Exact/prefix/contains checks run first; subsequence matching is the final fallback, not the first move. The top-K threshold prunes before the expensive fallback. | `core/FuzzyMatcher.java`, `providers/IndexProvider.java` |
| Degrade only the affected source | `AGENTS.md` guardrails | A provider that throws is logged and skipped; the rest of the search still returns. | `core/SearchEngine.java` |
| Recency then alphabetical | `search/utils/RecentResultRankingUtils.kt` | Usage boost stays bounded, and the final sort is score, then title length, then alphabetical for deterministic ties. | `core/UsageStore.java`, `core/SearchEngine.java` |
| Verification gate | `scripts/verify.sh` | Added a Windows equivalent that runs tests, checks file size, and prints `VERIFY PASSED`. | `scripts/verify.ps1` |
| Opt-in performance check | `benchmark/` module, `FuzzySearchPerformanceLogger` | Added a disabled-by-default benchmark over a synthetic six-figure index. | `test/.../SearchBenchmarkTest.java` |

## Adapted, not copied

- **No central `SearchSectionRegistry`.** quick-search has eleven sections, each with an
  alias target, a settings toggle, a permission, and a secondary-search flag. A single table
  pays off there. We have three providers; the same information fits on the `Provider`
  interface without an extra lookup layer. Revisit this when we pass ~8 providers.
- **No separate fuzzy pass.** quick-search runs exact matching first and then a budgeted
  fuzzy pass over remaining slots. Our index is small enough that a single scan with the
  ladder in `FuzzyMatcher` is simpler and fast (see the benchmark numbers below). If we add
  full-disk or content sources, split into two passes then.
- **No `PreparedSearchText` field on the model.** Storing four strings per item would cost
  more memory than it saves at 1M items. We cache only the name, path, acronym and path
  depth; compact and word forms are derived without allocation.
- **No Kotlin-style sealed result types.** Java records and the existing `Hit` model are
  enough. Adding a sealed hierarchy now would be architecture for its own sake.

## Deliberately not adopted

| Pattern | Why not |
| --- | --- |
| Compose + MVVM + `StateFlow` | UI-toolkit mismatch. JavaFX already provides observable properties and a single UI thread; copying Android's state layer would add indirection without a payoff. |
| Room, SharedPreferences, encrypted preferences | Desktop persistence is JSON today and SQLite later. DPAPI will handle secrets when clipboard history lands. |
| Android permission model | Not applicable. Windows capabilities are handled by the platform layer (JNA/Win32). |
| Standard/F-Droid flavors and Glance widgets | Distribution model is a Windows app image/MSI, not an APK. |
| 30-engine web-search registry | Our web fallback is one configurable URL. A bang registry is planned in Phase 6; copy the registry shape then, not now. |
| Section-by-section handlers | Our `Provider` interface already is that boundary. |
| AI provider fleet | Phase 9; a local/OpenAI-compatible endpoint is the planned shape. |
| 800-line hard limit | Adopted as a review guideline in `verify.ps1`, not as a build-breaking rule for this codebase yet. |

## Measured effect

Synthetic 120k-item index, 120 iterations per query after warm-up:

| Query | Before this pass (p50 / p95) | After (p50 / p95) |
| --- | --- | --- |
| `c` | ~5.9 / 20 ms (previous algorithm, different corpus) | 2.6 / 3.7 ms |
| `chrome` | — | 4.2 / 5.1 ms |
| `report` (matches most names) | — | 10.7 / 13.9 ms |
| `vsc` (acronym) | — | 5.4 / 9.1 ms |
| `ext:pdf` (filter only) | — | 7.2 / 13.2 ms |
| `in:documents report` | — | 11.3 / 17.8 ms |
| `apps chrome` (alias) | — | 5.4 / 7.8 ms |

Run it yourself:

```powershell
mvn -q -Dtest=SearchBenchmarkTest -Dbench=true test
```

The first uncached version of the ladder was 46 ms p50 on `c`; caching the acronym, replacing
per-character scans with `indexOf`, skipping path matching for one-character tokens, and
skipping equal-scoring candidates before the usage lookup brought it back under the previous
latency. This is the reason the hot path looks the way it does.

## What to borrow next

| Roadmap phase | Pattern to reuse |
| --- | --- |
| Phase 2 — search quality | Build the labeled query set and nDCG harness in the shape of quick-search's snapshot/policy tests; add a real two-pass fuzzy budget if full-disk search lands. |
| Phase 4 — launcher depth | `AppIconManager`'s bounded LRU, per-item cache invalidation, and explicit "clear memory" vs "invalidate" semantics. |
| Phase 5 — settings and UX | `AppSettingsRepository` as a searchable settings catalog: every setting should be findable from the launcher itself. `SearchSectionRegistry` becomes worth copying once providers grow. |
| Phase 5 — history | `SearchHistoryPreferences`: recent queries and recently opened results are separate lists with separate actions. |
| Phase 10 — distribution | `scripts/verify.sh` + `AGENTS.md` guardrails: one command that proves the build, tests and packaging are healthy. |

## License note

quick-search is MIT-licensed. We adapted ideas and algorithms, not source files. If a future
change copies a substantial block of code, keep the MIT notice with it.
