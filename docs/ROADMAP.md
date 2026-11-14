# Improvement Roadmap

**Status:** v0.1.0 — working prototype, installed and in daily use
**Goal:** a launcher you trust enough to replace the Start Menu, then a platform for the
things you reach for every day.

This document is the working plan. Every phase is independently shippable: it can be
released, used for a week, and rolled back without blocking later phases. Effort is in
focused developer-days for one person (with agent assistance); treat it as a relative
measure, not a deadline.

---

## 1. Where it is today

### 1.1 What already works

| Area | State |
| --- | --- |
| Global hotkey | `RegisterHotKey` on a dedicated message-pump thread; `Alt+Space` plus a fallback chain (`Ctrl+Alt+Space`, `Ctrl+Shift+Space`, ``Alt+` ``) |
| Lifecycle | Hidden window receives power/session/display broadcasts and re-registers and re-positions |
| Summon | Warm overlay shows in 30–88 ms, takes foreground, `Esc` dismisses |
| Search | Apps + configured folders, fuzzy subsequence match, usage/recency boost |
| Running windows | `EnumWindows` provider; Enter restores and focuses the existing window |
| Icons | Real shell icons via `SHGetFileInfo` + GDI, extracted off the UI thread and cached |
| Providers | Calculator, index, running windows, web fallback behind one `Provider` interface |
| Index | In-memory, pre-lowercased, lock-free atomic list swap, JSON snapshot |
| Packaging | jpackage app image (`~105 MB`), jlink runtime, CDS archive, per-user install, autostart shortcut |
| Diagnostics | Tee'd log at `%LOCALAPPDATA%\\Lumen\lumen.log`, plus a `[self-test]` line per launch |
| Robustness | Single-instance lock + loopback handoff across a 10-port range; tray fallback for open/reindex/restart/quit; uncaught exceptions logged without taking the tray down |

The suite covers the expression parser, units, matcher, query parsing, provider routing,
ranking, and the real Win32 icon and window bindings.

### 1.2 Known weaknesses

These are the honest gaps, ordered by how much they hurt.

| Area | Symptom | Root cause |
| --- | --- | --- |
| Hotkey trust | "I pressed it and nothing happened" | No supervisor: if the pump thread dies or registration is lost after sleep/RDP, nothing re-registers it; failures are silent unless the log is read |
| Focus semantics | Window disappears while typing / first press looks dead | Auto-hide on focus loss is too aggressive and the window can hide while another app steals focus |
| Startup | 0.99–1.07 s until the hotkey is live | JVM + JavaFX + AWT tray cost; antenna/AV can still add variance |
| Footprint | ~343 MB working set, 105 MB installed | Trimmed jlink runtime; CDS archive included; AWT and JavaFX toolkits both loaded |
| Recall | Misses files outside the three default roots | Directory walk only; no full-disk source |
| Ranking | Long paths can outrank the app you meant; acronyms not matched | Subsequence scoring has no path-depth penalty or acronym pass |
| Scale | 20 ms p95 at 120k, degrades linearly | No candidate pruning (n-gram/inverted prefilter) |
| Apps | UWP/Store apps missing | Start Menu walk only; no `AppsFolder` enumeration |
| Actions | Enter or "show in Explorer" only | No admin launch, copy path, arguments, actions menu |
| Configuration | Hand-edited JSON, restart required | No settings UI, no live hotkey re-registration |
| Content | Names only | No PDF/Office/text extraction, no content index |
| Clipboard | None | Not implemented |
| Distribution | Copy a folder, update by hand | No MSI, no signing, no auto-update, no CI |

### 1.3 Measured baseline

Development machine, 3.4k indexed items, warm process:

| Metric | Current |
| --- | --- |
| Hotkey → window visible | 45–88 ms (p95 ~88 ms) |
| Keystroke → ranked results | 0.48 ms p50 / 1.47 ms p95 |
| Same, 120k-item synthetic index | 5.9 ms p50 / 20 ms p95 |
| Snapshot load | 62–118 ms |
| Background full reindex | 0.6–1.7 s |
| Cold start → hotkey live | 0.99–1.07 s (jlink + CDS) |
| Working set | ~343 MB (2 processes: launcher + JVM) |
| Installed size | ~105 MB |

**Important:** these numbers are from the jlink+CDS build on the development machine (warm  
file cache). The 0.99–1.07 s range is with a trimmed runtime; a FullRuntime build would be  
larger and slower.

---

## 2. Principles

1. **Trust beats features.** A launcher that occasionally doesn't open is worse than one
   with fewer commands. Every phase must preserve the "hotkey always works" guarantee.
2. **One process, no IPC in the hot path.** Keep the single-JVM design. Background helpers
   (e.g. an elevated indexer) are allowed only if they never sit between a keystroke and a
   result.
3. **Measure before optimizing.** Any ranking or indexing change lands with a benchmark and
   a labeled query set, not a vibe.
4. **Local by default.** No telemetry, no network calls except explicit web search and
   opt-in update checks.
5. **Ship vertical slices.** Each phase ends with an installed build the user can live with.
6. **Fail loudly in the log, quietly in the UI.** A missing hotkey is a tray balloon plus a
   log line, never silence.

### Non-goals (for now)

- Cross-platform support (macOS/Linux) — the Win32 integration is the value.
- A plugin marketplace or sandboxed third-party runtime — the `Provider` interface is
  enough until there is real demand.
- Cloud sync of settings/history — privacy and complexity for little gain.
- Bundling a local LLM — use Ollama/OpenAI-compatible endpoints instead.

---

## 3. Targets for v1.0

| Metric | Target | How measured |
| --- | --- | --- |
| Hotkey → visible, p95 | ≤ 40 ms | In-app timing log, 100 summons |
| Hotkey → visible, p99 | ≤ 75 ms | Same |
| Cold start → hotkey live, p95 | ≤ 1.5 s | Startup trace marker |
| Keystroke → paint, p99 | ≤ 16 ms at 1M items, 12-char query | Benchmark harness |
| Keystroke → paint, p50 | ≤ 4 ms at 1M items | Benchmark harness |
| Snapshot load, p95 | ≤ 120 ms at 500k items | Startup trace |
| Working set | ≤ 250 MB at 1M items | Task Manager / JFR |
| Installed size | ≤ 80 MB | `jlink` runtime |
| Silent hotkey failures | 0 (every transition logged + surfaced) | Log audit + tray state |
| Crash-free sessions | ≥ 99.5% | Session markers in log |
| Ranking quality | ≥ 0.85 nDCG@10 on the labeled set | `mvn -Pbench` |
| Search recall on labeled set | ≥ 95% of expected top-3 | `mvn -Pbench` |

---

## 4. Priority ladder

| Priority | Meaning | Rule |
| --- | --- | --- |
| **P0** | The app is not trustworthy without it | Fix before adding any feature |
| **P1** | Makes it the launcher you actually use | Next, in order |
| **P2** | Differentiators and depth | After the core is solid |
| **P3** | Nice-to-have, revisit later | Backlog only |

---

## 5. Phases

### Phase 0 — Trust and reliability (P0)

**Why:** the only repeated complaint so far is "the hotkey didn't work." That is a
reliability problem, not a feature gap, and it blocks everything else.

**Progress:** Phase 0 is implemented. 0.1 (watchdog re-registration plus clock-gap resume
detection), 0.2 (a hidden window listening for `WM_POWERBROADCAST`, `WM_WTSSESSION_CHANGE` and
`WM_DISPLAYCHANGE`, re-registering and re-positioning), 0.3 (tray status), 0.4 (native
foreground polling hides the overlay 600 ms after it stops being the real foreground window),
0.5 (0/60/150/300 ms summon retries), 0.6 (uncaught-exception handler plus a tray "Restart"
item), 0.7 (single-instance listener walks a 10-port range and records the live port),
0.8 (one-line `[self-test]` marker per launch), 0.9 (configured key, then
`CTRL+ALT+SPACE`, `CTRL+SHIFT+SPACE`, ``ALT+` `` until one registers) and 0.10 (focus-loss log
names the foreground window) are done.

**Scope**

1. **Hotkey supervisor.** A watchdog thread checks `HotkeyManager.isRegistered()` every
   2 seconds and re-registers after sleep/resume, RDP reconnect, user switch, or a message-pump
   failure. Re-registration re-walks the whole fallback chain, so it never depends on which
   combination happened to be available at start-up.
2. **Lifecycle listeners.** Handle `WM_POWERBROADCAST` (resume), `WM_WTSSESSION_CHANGE`
   (lock/unlock, session switch), and `WM_DISPLAYCHANGE` (monitor/DPI change) on the pump
   thread; re-register and re-position after each.
3. **Visible state.** Tray tooltip shows the active hotkey. If registration fails, show a
   balloon naming the fallback and add a "Hotkey status" item to the tray menu.
4. **Fix auto-hide semantics.** The overlay hides on `Esc`, on action, or once it has been
   away from the *native* foreground for ~600 ms. A transient activation steal therefore
   never closes it, and it never sits on top of the app you switched to. Set
   `hideOnFocusLoss=false` to keep it open until `Esc`. JavaFX's own focus flag is never
   trusted for this decision.
5. **Retry the summon.** If `show()` fails to focus, retry at 0/60/150/300 ms before giving
   up; log the final `focused` state.
6. **Crash containment.** Install a default uncaught-exception handler that logs the
   stack trace and keeps the tray alive with a "Restart" item. Never let a provider exception
   take down the hotkey thread.
7. **Single-instance hard fallback.** If the loopback port is owned by something else, use a
   named mutex/event or a second port from a small range; the second launch must always
   either hand off or start cleanly.
8. **Startup self-test.** On first run of a new build, verify: hotkey registered, tray added,
   index loaded, overlay can show. Write a one-line `[self-test] ok/fail` marker.
9. **Hotkey conflict report.** On failure, walk the fallback chain
   (`CTRL+ALT+SPACE`, `CTRL+SHIFT+SPACE`, ``ALT+` ``) and register the first two free
   combinations; the tray tooltip and first-run balloon name the live combination and say
   which requested one was taken.
10. **Focus-loss telemetry.** Log every `[focus] lost` with the foreground window title, so
    the next "it didn't work" report is diagnosable in one glance.

**Acceptance criteria**

- Kill the message pump thread deliberately → hotkey returns within 2 s.
- Lock/unlock, sleep/resume, RDP reconnect, monitor unplug → hotkey works on the first press.
- Press the hotkey 50 times in a row while switching focus → the window is visible and
  focused every time; 0 hides caused by focus loss.
- Press the hotkey while the window is visible but unfocused → it raises, never closes.
- A provider throwing on every keystroke leaves the tray and hotkey alive.
- `lumen.log` contains an explicit line for every registration, loss, and recovery.

**Effort:** 3–5 d. **Risk:** low. **Blocks:** everything.

---

### Phase 1 — Startup and footprint (P0)

**Why:** the app should be invisible until summoned. Today cold start can take ~1 s and the
install is ~105 MB — both already improved from the original 163 MB + 5 s.

**Progress:** 1.1 (jlink runtime, compressed, stripped) and 1.2 (AppCDS archive generated at
build time and baked into the app image) are implemented. The build script downloads JavaFX
jmods on first use and produces a ~78 MB runtime / ~105 MB total install. Cold start to
`[hotkey] registered` is ~1.0 s p50 with CDS on the development machine.

**Scope**

1. **Trim the runtime with jlink.** Build a custom runtime containing only
   `java.base`, `java.desktop`, `java.logging`, `java.management`, `jdk.unsupported`,
   JavaFX modules, and JNA. Expect 163 MB → 60–80 MB. *(Implemented; the runtime is
   78 MB.)*
2. **AppCDS / class-data sharing.** Generate a CDS archive at build time for JavaFX + the
   app classes; CDS provides a small cold-start improvement (~1.0 s vs 1.07 s without it).
   *(Implemented.)*
3. **Lazy-load the tray.** AWT init is the slowest step; keep the hotkey and overlay ahead
   of it (already done) and defer tray/icon work until after the first idle.
4. **Defer non-critical providers.** Web search and calculator stay cheap; anything new that
   does I/O must not run on the first paint.
5. **Startup budget guard.** A build-time check fails if `hotkey live` exceeds 1.5 s on the
   reference machine.
6. **Optional fast-start wrapper.** A tiny native launcher that starts the JVM only after a
   hotkey press is explicitly rejected; document why (it would trade memory for cold
   latency and break "always warm").
7. **Memory pass.** JFR allocation profile at 1M items; remove per-keystroke allocations
   (token arrays, `toLowerCase`, `String.split`), intern repeated subtitles, and consider
   primitive score arrays.

**Acceptance criteria**

- Installed size ≤ 80 MB, cold start p95 ≤ 1.5 s, memory ≤ 250 MB at 1M items.
- No regression in warm summon or search latency.
- `scripts\build-installer.ps1` produces the trimmed image with one command.

**Effort:** 2–3 d. **Risk:** medium (jlink module graph and CDS can be fiddly).

---

### Phase 2 — Search quality and scale (P1)

**Why:** the current scan is already fast at 3.4k items but grows linearly; recall and
ranking are the bigger user-visible gaps ("why is this app third?").

**Scope**

1. **Evaluation harness.** `src/test/resources/queries.json` with ~100 labeled queries
   (app names, partial words, acronyms, paths, typos, multi-token). `mvn -Pbench` reports
   nDCG@10, MRR, recall@3, and p50/p95 latency. Every ranking change must beat or tie the
   baseline.
2. **Candidate pruning.** Character-trigram bitmap (one `long[]` per entry, or a global
   inverted map for 3-grams) to skip entries that cannot contain all query trigrams. Keep
   the exact scorer for survivors. Target: 1M items in ≤ 8 ms p99.
3. **Better scoring.**
   - Acronym match (`vsc` → Visual Studio Code, `gimp` from path words).
   - Word-boundary and camelCase tokenization.
   - Path-depth penalty and shorter-name tie-break.
   - Extension-aware scoring (`invoice pdf`).
   - Small typo tolerance (Damerau–Levenshtein ≤ 1) for tokens of length ≥ 4.
   - Stable ordering: usage > exact > prefix > acronym > subsequence.
4. **Query syntax.**
   - `ext:pdf`, `kind:app|file|folder`, `in:downloads`, `-word` exclusion.
   - `>` prefix for commands, `?` prefix for web, `=` prefix for calculator-only.
   - Quoted phrases.
5. **Incremental index updates.** `ReadDirectoryChangesW` (or `WatchService` as a portable
   start) with a 500 ms debounce; update entries instead of a full rebuild. Full rebuild
   stays as a nightly/tray action.
6. **Dedupe by target.** Multiple shortcuts pointing at the same exe collapse into one row
   with the best-scoring name.
7. **Result highlighting.** Return match spans from the scorer so the UI can bold the
   matched characters (today the row is plain text).

**Acceptance criteria**

- 1M-item synthetic index: p50 ≤ 4 ms, p99 ≤ 16 ms per keystroke.
- nDCG@10 ≥ 0.85 and recall@3 ≥ 95% on the labeled set.
- A new file appears in results within 2 s of creation without a full reindex.
- Highlighted match characters visible in the overlay.

**Effort:** 6–9 d. **Risk:** medium; the harness is what keeps it honest.

---

### Phase 3 — Full-disk search (P1)

**Why:** "find any file on this PC" is the headline promise of a Spotlight-style tool. The
current walk only sees configured folders.

**Decision:** do **not** start with a custom MFT/USN reader. It needs elevation, is
filesystem-specific (NTFS only), and is a large correctness surface. Sequence:

1. **Everything SDK provider (first).** If Everything is installed and its service is
   running, call `Everything64.dll` via JNA (`Everything_SetSearchW`,
   `Everything_QueryW`, `Everything_GetResultFullPathNameW`). This gives instant full-disk
   names with no elevation in our process and zero indexing cost. Feature-detect at startup;
   hide the provider when unavailable. Document the dependency and license.
2. **Windows Search provider (second).** Query the built-in index through OLE DB
   (`System.Search.Query` / `Search.CollatorDSO`) for machines without Everything. Slower
   and dependent on Windows indexing scope, but no install and covers content too.
3. **USN journal helper (third, optional).** A small signed helper that runs elevated,
   reads `FSCTL_READ_USN_JOURNAL` / MFT, and streams name+parent records over a local
   named pipe to the UI process. Only if the first two prove insufficient. Must be opt-in,
   auto-start via a scheduled task, and never required for the launcher to work.
4. **Merge semantics.** Full-disk hits rank slightly below local roots on equal score, so
   the folders you care about still win ties. Dedupe by path.

**Acceptance criteria**

- With Everything running, a file created anywhere on `C:` is findable within 1 s.
- With Everything absent, the app starts, logs the missing provider once, and search is
  unchanged.
- Full-disk results never delay local results (provider timeout ≤ 40 ms; stale results are
  better than a frozen UI).
- No elevation prompt on normal startup.

**Effort:** 3–5 d for steps 1–2; 8–12 d for step 3. **Risk:** medium (third-party
dependency, licensing) / high (elevated helper).

---

### Phase 4 — Launcher depth and icons (P1)

**Why:** this is what makes it feel like a real launcher rather than a file finder.

**Progress:** 4.1, 4.3, 4.4 (first slice) and 4.6 are implemented. Icons are extracted with
`SHGetFileInfo` and rendered through a GDI DIB section on a background thread, cached in memory
per executable or file path; a failed extraction falls back to the kind badge. Running windows
come from `EnumWindows` (visible, titled, non-tool, non-cloaked, not our own process) and Enter
restores and focuses the window. `Ctrl+K` (or `→` at the end of the query) opens an in-scene
action sheet — open, open as administrator, show in folder, copy path, copy name, switch to
window, copy title, copy result — with `↑` `↓` / `Enter` / `Esc` navigation. A missing target
now keeps the overlay open with an inline "Not found" banner instead of closing silently.
Remaining: 4.2 (UWP/Store apps), pin to top, run with arguments and uninstall in the action
sheet, and the on-disk icon cache.

**Scope**

1. **Shell icons.** Extract per-item icons with `SHGetFileInfo`, draw the `HICON` into a
   32-bit DIB section and copy the pixels into a JavaFX image. Extraction runs off the
   JavaFX thread and is cached by source path, so typing never waits for it. Fall back to a
   kind badge when extraction fails. *(Implemented; a PNG disk cache keyed by path + mtime
   is still open.)*
2. **UWP / Store apps.** Enumerate `shell:AppsFolder` through the Shell COM API, capture
   `AppUserModelID` + display name, and launch via
   `explorer.exe shell:AppsFolder\<AUMID>`. This adds Settings, Calculator, Photos, Store
   apps, etc.
3. **Running windows.** `EnumWindows` + process name/title matching; Enter switches to the
   window (`SetForegroundWindow`, with the existing attach-input workaround, plus
   `SW_RESTORE` for minimized windows) instead of launching a second instance.
   *(Implemented.)*
4. **Actions menu (`Ctrl+K` or `→`).** Open, open as administrator (`runas`), open
   containing folder, copy path, copy name, pin to top, run with arguments, uninstall
   (where a shortcut provides it). *(Implemented: open, run as administrator, show in folder,
   copy path, copy name, switch to window, copy title, copy result. Pin to top, run with
   arguments and uninstall remain.)*
5. **Aliases and manual entries.** `aliases.json` mapping `code` → path, `chrome` → exe,
   plus user-defined commands with arguments and working directory.
6. **Launch feedback.** If a launch fails (missing file, blocked by policy), show an inline
   error row instead of closing silently. *(Implemented: a missing target is caught before
   the overlay hides, and a deferred shell failure reopens the overlay with the reason.)*

**Acceptance criteria**

- Every Start Menu app shows its real icon shortly after first paint, without delaying typed
  results; repeat views are served from the in-memory cache.
- Store apps are searchable and launch correctly.
- Typing an app that is already running and pressing Enter focuses the existing window.
- "Run as administrator" triggers UAC and launches elevated.
- No launch failure is silent.

**Effort:** 4–6 d. **Risk:** medium (COM interop, icon memory pressure).

---

### Phase 5 — Settings and UX polish (P1)

**Why:** JSON editing and restarts are acceptable for a prototype, not for daily use.

**Scope**

1. **Settings window.** JavaFX, same visual language as the overlay. Sections:
   - **General:** hotkey (live capture field, not free text), launch at login, language.
   - **Search:** roots (folder picker), exclusions, depth, result count, full-disk toggles.
   - **Appearance:** theme (system/light/dark), width, vertical position, opacity,
     result density, animations.
   - **Providers:** enable/disable each provider, web-search engine, custom bangs.
   - **Index:** item count, last build, size, "Rebuild now", "Open data folder".
   - **Advanced:** log level, "open log", reset config, export/import.
2. **Live apply.** Hotkey changes re-register without restart; theme and width apply
   immediately; root changes trigger a background reindex.
3. **First-run onboarding.** Three screens: pick hotkey, pick folders, done. Offer to import
   Start Menu apps only (fast) or scan folders.
4. **Preview pane.** Right-side panel for the selected row: full path, size, modified date,
   icon, and (later) content snippet. Toggle with `Ctrl+P`, off by default on small screens.
5. **Keyboard model.** `↑/↓`, `Tab/Shift+Tab`, `Enter`, `Ctrl+Enter`, `Ctrl+K` actions,
   `Ctrl+,` settings, `F5` reindex, `?` shortcut cheatsheet, `Esc` back/close.
6. **Window behavior.** Remember size/position per monitor; respect DPI changes; optional
   "keep open until Esc"; click-outside to dismiss; drag to move; snap to top-center.
7. **Accessibility.** Screen-reader labels on the field and list, focus ring, high-contrast
   theme, reduced-motion support, full keyboard operation of settings.
8. **IME support.** Do not fire searches mid-composition; commit on composition end (CJK,
   dead keys).
9. **Empty states.** Recent apps when empty; "No results — press Enter to search the web"
   row; tips on first run.

**Acceptance criteria**

- Change the hotkey in settings → new combo works immediately, old one stops, no restart.
- Toggle dark mode → overlay and settings both update instantly.
- Settings survive restart and can be exported/imported.
- Overlay is usable with keyboard only and with a screen reader announcing selected row.
- Typing with a CJK IME does not search on every composition keystroke.

**Effort:** 5–8 d. **Risk:** low–medium (mostly UI work).

---

### Phase 6 — Command surface (P2)

**Why:** a launcher becomes a habit when it does the small things faster than the mouse.

**Scope**

1. **Calculator expansion.** Variables (`x = 5`, then `x * 2`), percentages (`15% of 240`),
   date math (`today + 45 days`), time zones (`3pm EST in IST`), base conversion
   (`0xff in decimal`), and a history of recent results.
2. **Currency.** Daily ECB rates with an offline cache; `100 usd to eur` works offline with
   the last known rate and shows the rate date.
3. **System commands.** Lock, sleep, restart, shut down, sign out, empty recycle bin, show
   desktop, mute, volume up/down, toggle Wi-Fi/Bluetooth (WinRT via JNA or `netsh`/`radios`
   fallback), toggle dark mode, night light.
4. **Web bangs.** `g foo`, `yt foo`, `gh foo`, `so foo`, `wiki foo`, plus user-defined
   `"bang": "https://…%s"` entries. Configurable default engine.
5. **Snippets.** Named text templates with placeholders (date, clipboard, selection); Enter
   pastes into the previously focused app.
6. **Developer tools.** Base64/URL encode-decode, SHA-256, UUID v4, JSON pretty-print,
   regex tester, timestamp ↔ date, color picker/convert, lorem ipsum.
7. **File actions.** Create file/folder in the current folder, move to Recycle Bin (with
   confirmation), compress to zip, copy as path, open terminal here.
8. **Window management.** Switch to window, minimize all, move active window to next
   monitor, tile left/right.
9. **Command registry.** A declarative `commands.json` (name, aliases, icon, action,
   confirmation flag) so new commands don't each need a class.

**Acceptance criteria**

- Every command is discoverable by typing its name, and destructive ones require explicit
  confirmation.
- Currency works offline with a visible rate date.
- Snippets paste into the app that had focus before the overlay opened.
- No command blocks the UI thread for more than 50 ms.

**Effort:** 7–10 d. **Risk:** low, but scope creep is real — ship in slices.

---

### Phase 7 — Content search (P2)

**Why:** "the PDF that mentions the invoice number" is the next level of findability.

**Scope**

1. **Opt-in content index.** SQLite with FTS5, one row per document: path, mtime, size,
   extracted text, tokens. Store in `%LOCALAPPDATA%\\Lumen\content.db`.
2. **Extraction.** Apache Tika + PDFBox (PDF), POI (Office), plain text/markdown/JSON/code.
   Cap extraction at the first ~200 KB per file and skip binaries by magic bytes.
3. **Crawler.** Background, throttled (CPU/IO budget), resumable, incremental by mtime;
   excludes node_modules/AppData/etc.; user-selectable roots and extensions.
4. **Ranking.** BM25 from FTS5, blended with the filename score (content hits rank below
   exact name hits). Show a snippet with highlighted terms in the preview pane.
5. **OCR (optional, later).** Tesseract for scanned PDFs/images, behind a separate opt-in
   with a clear CPU/storage warning.
6. **Privacy controls.** Per-folder exclusions, "never index" patterns, a delete-all button,
   and a visible index size.

**Acceptance criteria**

- A phrase inside a PDF is findable within one search after indexing, with a snippet.
- Content indexing never exceeds a configurable CPU/IO budget and pauses on battery.
- Index can be deleted and rebuilt from the settings UI.
- Search latency stays within the Phase 2 targets (content query runs as a second pass or in
  parallel, never blocking filename results).

**Effort:** 8–12 d. **Risk:** medium–high (extraction edge cases, storage growth).

---

### Phase 8 — Clipboard history (P2)

**Why:** the second-most-requested launcher feature after file search.

**Scope**

1. **Listener.** Message-only window with `AddClipboardFormatListener`; capture text and
   images, dedupe consecutive duplicates, cap entry size.
2. **Store.** SQLite, bounded (e.g. 500 entries / 30 days), with pinning and search.
3. **Provider.** Clipboard entries appear in search; Enter pastes into the previously
   focused window (save `GetForegroundWindow`, set clipboard, send `Ctrl+V`, restore).
4. **Privacy.** Skip content marked `ExcludeClipboardContentFromMonitorProcessing`,
   `CanIncludeInClipboardHistory=0`, and clipboard-viewer-ignore formats (password
   managers). Encrypt the database with DPAPI. Pause capture from the tray.
5. **Images.** Thumbnail in the row; paste original bitmap.

**Acceptance criteria**

- Copying text makes it searchable within 200 ms.
- Password-manager copies are never stored (verified with a test harness).
- Paste lands in the app that was focused before the overlay opened.
- History is bounded and can be cleared from settings.

**Effort:** 4–6 d. **Risk:** medium (privacy correctness matters more than features here).

---

### Phase 9 — Local AI and semantic search (P2/later)

**Why:** natural-language recall ("the deck I made for the Q3 review") is the direction the
category is moving. Do it only after the lexical index is excellent.

**Scope**

1. **Embeddings.** ONNX Runtime + a quantized MiniLM-class model (~25–40 MB), run on CPU
   with a small thread pool; embed filenames, paths, and content snippets.
2. **Hybrid ranking.** Combine BM25/fuzzy score with cosine similarity; semantic results
   never outrank exact name matches. Ship behind a flag, measure on the labeled set.
3. **Intent router.** A small classifier or rule layer that maps "open…", "find…",
   "what did I copy about…", "how much is…" to the right provider. Rules first, model later.
4. **Optional local LLM.** Connect to Ollama or any OpenAI-compatible local endpoint for
   summarization/Q&A over results; never send data to a cloud endpoint without explicit
   opt-in.
5. **Index lifecycle.** Incremental embedding queue, model versioning, rebuild on model
   change, storage budget.

**Acceptance criteria**

- Enabling AI does not slow lexical search (separate thread; lexical results paint first).
- Semantic mode improves nDCG on a natural-language subset without regressing exact-match
  queries.
- Works fully offline; no data leaves the machine by default.
- Feature can be disabled and its data deleted in one click.

**Effort:** 8–12 d. **Risk:** high (quality expectations, model size, CPU cost).

---

### Phase 10 — Distribution and updates (P2, but required before sharing)

**Why:** a folder you copy by hand is not an app other people can use.

**Scope**

1. **MSI installer.** WiX via `jpackage --type msi`, per-user install (no admin), Start Menu
   shortcut, optional autostart checkbox, clean uninstall that leaves `config.json` unless
   "remove my data" is checked.
2. **Code signing.** Sign the exe/msi with an EV or OV certificate; unsigned binaries trigger
   SmartScreen and look untrustworthy. Budget for it before public release.
3. **Auto-update.** Signed manifest (version, URL, SHA-256, signature) checked daily or on
   demand; download to temp, verify, swap on exit or via a tiny updater; never update
   silently while the app is open. Delta updates if size becomes a problem.
4. **Channels.** `stable` and `beta`; config can pin a version.
5. **CI.** GitHub Actions on `windows-latest`: compile, unit tests, benchmark smoke test,
   package, sign (when secrets exist), publish artifact + release notes.
6. **winget manifest** and a portable zip.
7. **Crash/diagnostics export.** "Copy diagnostics" button that zips the log, config
   (redacted), and system info for a bug report. Opt-in, never automatic upload.
8. **License and third-party notices.** MIT for the app; include Everything SDK, JNA,
   Jackson, JavaFX, Tika licenses.

**Acceptance criteria**

- A clean VM installs with one click, starts at login, and uninstalls cleanly.
- An update from N to N+1 preserves config/history/usage and takes under 30 s.
- Signed builds show no SmartScreen warning.
- CI produces a installable artifact on every tagged commit.

**Effort:** 5–8 d (plus certificate lead time). **Risk:** low technically, external
dependencies for signing.

---

### Phase 11 — Extensibility (P3)

**Why:** let the community add providers without touching core.

**Scope**

1. Public `Provider` SPI in a small `lumen-api` module, with a stability guarantee.
2. Script providers: a JSON/JS (GraalJS) or external-process manifest that maps a query to
   results, with timeouts and no filesystem access by default.
3. Provider settings UI and per-provider enable/disable.
4. A `docs/PROVIDERS.md` cookbook with three worked examples (clipboard, Windows Search,
   custom web bangs).
5. Deliberately **no** marketplace until there is demand; distribute as zip drops into a
   `providers` folder.

**Acceptance criteria**

- A third-party provider can be added without recompiling the app.
- A misbehaving provider is isolated by a timeout and cannot break search.
- The SPI is documented and covered by a sample test.

**Effort:** 4–6 d. **Risk:** medium (API design is hard to undo).

---

## 6. Cross-cutting workstreams

These run alongside the phases, not as separate phases.

### 6.1 Test matrix

| Category | Cases |
| --- | --- |
| Hotkey | Single/multi-monitor, DPI 100/150/200%, RDP, sleep/resume, lock/unlock, user switch, fullscreen game, elevated foreground app, another app owning `Alt+Space` |
| Input | US layout, CJK IME, dead keys, Ctrl/Alt/Shift combos, rapid typing, paste into search |
| Windows | Windows 10 21H2+, Windows 11 22H2+, Server (best effort) |
| Lifecycle | Fresh install, upgrade, uninstall, portable mode, config corruption, index corruption, disk full |
| Load | 1M-item index, 24 h soak, repeated summon/hide 10k times, memory leak check |

### 6.2 Performance lab

- `mvn -Pbench` runs search + ranking benchmarks on a checked-in synthetic corpus.
- Startup trace behind `LUMEN_TRACE=1` with markers: config, snapshot, hotkey, overlay,
  tray.
- A weekly manual run on the reference machine; regressions block release.

### 6.3 Security and privacy

- No network by default; web search and update checks are the only outbound calls.
- Command execution and destructive actions always confirm.
- Clipboard sensitive-format filtering + DPAPI encryption.
- Signed binaries and signed update manifests.
- Least privilege: no elevation for normal operation; elevated helper only if Phase 3.3
  happens, with a narrow, documented protocol.
- Log redaction: never log clipboard contents, query text at `INFO`, or full paths outside
  `DEBUG`.

### 6.4 Accessibility and i18n

- Keyboard-only operation of overlay and settings.
- Screen-reader labels and announcements.
- High-contrast theme, reduced motion.
- Externalize UI strings from the start; ship English first, add locales when requested.

### 6.5 Documentation

- `README.md` — what it is, install, keys, config.
- `docs/ARCHITECTURE.md` — design rationale (update as decisions change).
- `docs/ROADMAP.md` — this file.
- `docs/PROVIDERS.md` — how to add a source (Phase 11).
- `docs/TROUBLESHOOTING.md` — hotkey playbook, log locations, reset steps.

---

## 7. Prioritized backlog (top items)

| # | Item | Phase | Priority | Impact | Effort |
| --- | --- | --- | --- | --- | --- |
| 1 | Hotkey supervisor + lifecycle re-registration | 0 | P0 | Trust | 1–2 d |
| 2 | Never hide on focus loss; raise instead | 0 | P0 | Trust | 0.5 d |
| 3 | Visible hotkey status + conflict fallback chain | 0 | P0 | Trust | 0.5 d |
| 4 | Crash containment + startup self-test | 0 | P0 | Trust | 1 d |
| 5 | Single-instance hard fallback | 0 | P0 | Trust | 0.5 d |
| 6 | jlink runtime + AppCDS | 1 | P0 | Size/startup | 2–3 d |
| 7 | Ranking evaluation harness | 2 | P1 | Quality | 1–2 d |
| 8 | Trigram candidate pruning | 2 | P1 | Scale | 2–3 d |
| 9 | Acronym + path-depth + typo scoring | 2 | P1 | Quality | 2 d |
| 10 | Query syntax (`ext:`, `kind:`, `in:`, `-`) | 2 | P1 | Power use | 1–2 d |
| 11 | Incremental index updates | 2 | P1 | Freshness | 2 d |
| 12 | Everything SDK provider | 3 | P1 | Full disk | 2–3 d |
| 13 | Windows Search provider | 3 | P1 | Full disk fallback | 2 d |
| 14 | Shell icons + disk cache | 4 | P1 | Feel | 2–3 d |
| 15 | UWP app enumeration/launch | 4 | P1 | Coverage | 1–2 d |
| 16 | Running-window switch | 4 | P1 | Feel | 1 d |
| 17 | Actions menu + admin launch | 4 | P1 | Depth | 1–2 d |
| 18 | Settings window + live hotkey capture | 5 | P1 | Usability | 3–4 d |
| 19 | Preview pane + match highlighting | 5 | P1 | Feel | 2 d |
| 20 | Accessibility + IME correctness | 5 | P1 | Inclusivity | 1–2 d |
| 21 | System commands + bangs + snippets | 6 | P2 | Daily value | 4–6 d |
| 22 | Dev/encoding tools | 6 | P2 | Daily value | 2 d |
| 23 | Clipboard history | 8 | P2 | Daily value | 4–6 d |
| 24 | Content search (FTS5 + Tika) | 7 | P2 | Recall | 8–12 d |
| 25 | MSI + signing + auto-update + CI | 10 | P2 | Distribution | 5–8 d |
| 26 | Local embeddings + hybrid ranking | 9 | P2 | Differentiator | 8–12 d |
| 27 | Provider SPI + script providers | 11 | P3 | Ecosystem | 4–6 d |

---

## 8. Immediate next five (this week)

1. **Ranking eval harness** (Phase 2.1). Turns "feels better" into a number and protects the
   next ranking change.
2. **UWP/Store apps and remaining action-sheet depth** (Phase 4.2, 4.4). The remaining launcher
   coverage that people notice immediately — pin to top, run with arguments, uninstall.
3. **On-disk icon cache** (Phase 4.1 remainder). Cache extracted icons to disk so the first
   cold reindex after startup isn't iconless.
4. **Settings UI with live hotkey capture** (Phase 5), so the configured key and the fallback
   chain can be changed without editing JSON.
5. **Signed MSI + auto-update + CI** (Phase 10), required before handing the app to anyone
   outside this machine.

Do these in that order. Ship after each one; do not batch them into a big-bang release.

---

## 9. Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
| --- | --- | --- | --- |
| Another app owns the hotkey | Medium | High | Backup combo, fallback chain, tray fallback, visible status |
| Focus/activation refused by Windows | Medium | High | AttachThreadInput + retries; never auto-hide on transient focus loss |
| Everything SDK licensing/distribution ambiguity | Medium | Medium | Feature-detect, do not bundle, document; Windows Search as fallback |
| USN/MFT helper needs elevation and breaks | Medium | Medium | Optional, signed, piped; never required for core |
| Icon extraction leaks memory / slows startup | Medium | Medium | Lazy, off-thread extraction with a bounded cache; a disk cache is the next step if memory or startup grows |
| Content index grows unbounded | High | Medium | Size caps, exclusions, budgeted crawler, one-click delete |
| Clipboard stores secrets | Medium | High | Skip sensitive formats, DPAPI, pause, clear |
| Ranking changes regress quality | High | Medium | Labeled eval set + CI gate |
| jlink/CDS build fragility | Medium | Low | Keep the plain jpackage build as a fallback target |
| Scope creep delays v1.0 | High | High | Phase gates, shippable slices, P0 first |

---

## 10. Release criteria for v1.0

- All P0 items closed; no known silent hotkey failure path.
- Targets in §3 met on the reference machine.
- Test matrix in §6.1 passed for Windows 10 and 11.
- Installer, uninstaller, and update path verified on a clean VM.
- Settings UI covers everything that currently requires editing JSON.
- Documentation complete: README, architecture, roadmap, troubleshooting.
- A 24-hour soak shows no memory growth beyond 10%.

---

## Appendix A — Hotkey troubleshooting playbook

When "the hotkey didn't work," check in this order:

1. `Get-Process Lumen` — is the app running? Two processes (launcher + JVM) is normal.
2. `Get-Content %LOCALAPPDATA%\\Lumen\lumen.log -Tail 40`:
   - `[hotkey] registered …` — which combination is live? It may have fallen back.
   - `[hotkey] pressed …` — did the OS deliver it? If pressed but no `[show]`, the UI thread
     is blocked or the toggle logic hid it.
   - `[focus] foreground claim refused` — activation was denied; the retry should fix it.
3. Press the fallback `Ctrl+Alt+Space`.
4. Double-click `Lumen.exe` — it hands off to the running instance and opens the
   overlay. If nothing happens, the listener is down; check the log.
5. Use the tray icon → Open.
6. If the log shows no `[hotkey] registered`, another app owns both combos; change
   `hotkey` in `config.json` or via Settings (Phase 5).

## Appendix B — Benchmark commands

```powershell
# unit tests
mvn -q test

# search/ranking benchmarks on the synthetic corpus (added in Phase 2)
mvn -q -Pbench

# startup trace
$env:LUMEN_TRACE = "1"; scripts\run.ps1

# build + install
scripts\build-installer.ps1
scripts\install.ps1
```
