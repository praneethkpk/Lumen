# Architecture

## The one decision that shapes everything

A launcher is judged in the first 100 ms: hotkey → visible → first keystroke → results.
That rules out a few obvious designs.

| Design | Why not |
| --- | --- |
| Java backend + web UI (Electron/Tauri) | Two processes, an IPC hop per keystroke, a second toolchain, 150 MB+ shell, and a cold JVM start in front of the UI |
| Java backend + localhost HTTP | Every keystroke becomes a socket round trip, plus JSON encoding of results; jitter shows up as typing lag |
| Pure Win32/C# native | Fastest possible, but gives up the Java requirement |

So: **one JVM process that stays resident**. JavaFX renders the overlay, Java owns the index
and the search. The JVM start cost is paid once at login, not on every summon, and a summon
is just `Stage.show()` on a warm scene graph.

The "backend" is still Java — it is simply in-process, which is the point. `Provider`,
`IndexStore`, `SearchEngine` and `Indexer` have no JavaFX imports, so moving them behind an
IPC boundary later (if a different front end is ever wanted) is mechanical.

## Latency budget

```
hotkey thread ──WM_HOTKEY──▶ Platform.runLater ──▶ Overlay.show()      ~5 ms
                                                    │
keystroke ──▶ refresh() ──▶ SearchEngine.search() ──┘                 1–3 ms
                                 │
                                 ├─ CalculatorProvider   (<0.1 ms)
                                 ├─ IndexProvider        (fuzzy scan)
                                 └─ WebSearchProvider    (<0.1 ms)
```

`SearchEngine.search()` is called synchronously from the text listener. For 3.4k items a
typical query is 0.5–2 ms; on a synthetic 120k-item corpus it is 2–11 ms p50 depending on how
much of the corpus matches, with the worst case (a token present in almost every name) around
11 ms p50 / 14 ms p95. If that ever stops being true, the fix is a worker thread with a
request id — not a redesign.

Three details make the scan cheap:

1. **Pre-normalized strings** — `IndexEntry` stores `nameLower`/`pathLower`, plus a cached
   acronym and path depth. A keystroke never allocates lowercase or acronym copies of the
   index.
2. **Cheap-first ranking** — `FuzzyMatcher.scoreToken` uses `indexOf` for exact, prefix,
   word-prefix and contains checks, compares the cached acronym, and only then falls back to
   a subsequence scan. Path matching is skipped for one-character tokens so the drive letter
   cannot match every path.
3. **Atomic list swap** — `IndexStore` holds an `AtomicReference<List<IndexEntry>>`. Readers
   never lock; the indexer builds a new list and swaps it in one write.

## Indexing

`Indexer` runs on a virtual thread. It walks:

- `%APPDATA%\Microsoft\Windows\Start Menu\Programs`
- `%PROGRAMDATA%\Microsoft\Windows\Start Menu\Programs`
- every configured root (Desktop/Documents/Downloads by default)

Skips hidden entries, dot-directories, and a noise list (`node_modules`, `.git`, `target`,
`AppData`, …). Deduplicates by absolute path, and stops at `maxIndexedFiles`.

The result is written to `index.json` (atomic temp-file + move). On startup the snapshot is
loaded synchronously — ~60 ms for 3.4k items — so the first summon already has results while
the background rebuild runs. The rebuild then calls `overlay.refreshIfVisible()`, which
re-ranks the current query in place without losing the selection.

## Packaging

`scripts\build-installer.ps1` produces the app image:
1. **jlink** — builds a trimmed runtime (~78 MB) from a module graph containing
   `java.base`, `java.desktop`, `java.logging`, `java.prefs`, `jdk.unsupported` and the
   JavaFX modules (controls, graphics, base). JavaFX jmods are downloaded from Gluon's CDN on
   first build and cached in `.cache/`.
2. **CDS** — `jlink --generate-cds-archive` produces a class-data archive that the JVM loads
   instead of re-reading the module image. The archive is copied into `dist/Lumen/app/` as
   `lumen.jsa` and referenced from `Lumen.cfg` via `-XX:SharedArchiveFile=$APPDIR/lumen.jsa`.
3. **jpackage** — wraps the runtime + app jars into `dist\Lumen\Lumen.exe`.
4. **Install** — `scripts\install.ps1` copies to `%LOCALAPPDATA%\Programs\Lumen`, adds an
   autostart shortcut, and launches the app.

Full-disk instant search (Everything-style MFT/USN enumeration) is deliberately out of scope
for v1; it needs elevated access and a different data structure. The `Provider` boundary is
where it would slot in.

## Ranking

`FuzzyMatcher` uses a match-priority ladder adapted from quick-search. Each tier is separated
by a wide band so a strong match cannot be overtaken by several weak ones:

| Tier | Score | Example (`chrome`) |
| --- | --- | --- |
| Exact name | 10 000 | `Chrome` |
| Name prefix | 4 000 | `Chrome Helper` |
| Word prefix | 2 200 | `Google Chrome` |
| Name contains | 1 200 | `Enchrome` |
| Cached acronym | 800 | `vsc` → `Visual Studio Code` |
| Path contains | 300 | `docs` → `…\docs\report.pdf` |
| Subsequence fallback | ≤ ~300 | `chrm` → `chrome` |

Within the subsequence fallback the original scoring still applies: 10 per matched character,
+40 at position 0, +22 at a word boundary, +14 for consecutive characters, and a length nudge.
`IndexProvider` requires *all* tokens to match, sums their best scores, adds +12 for apps,
then adds a bounded usage boost (+3 per open, max 60, plus +18 if used in the last 24 h) and
subtracts a small path-depth penalty. `CalculatorProvider` returns score 100 000 so a valid
expression is always the first row. `WebSearchProvider` returns 0 and therefore sits last.

Ties are broken by title length and then alphabetically, so results are stable between
keystrokes. Once the bounded top-K heap is full, equal-scoring candidates are skipped before
the usage lookup; that is what keeps a query matching the whole corpus fast.

## Query syntax

`SearchQuery` parses the text once per keystroke and shares it across providers:

| Syntax | Meaning |
| --- | --- |
| `report ext:pdf` | tokens plus a file-extension filter (`.pdf`) |
| `kind:folder` / `kind:app` / `kind:file` / `kind:window` | restrict by item kind |
| `in:downloads report` | path must contain `downloads` |
| `invoice -draft` | exclude anything containing `draft` |
| `apps chrome` | leading alias routes to the index, app results only |
| `files report` | files and folders only |
| `win chrome` / `windows chrome` | route to open windows |
| `web cats` / `g cats` | route to the web fallback |
| `calc 2+2` / `= 2+2` | route to the calculator |

A provider declares its aliases, implied kinds, minimum query length and initial-list order in
`Provider`; `SearchEngine` parses, routes, skips providers below their minimum, and isolates a
provider that throws. This is the small-project adaptation of quick-search's section registry
(see `docs/REFERENCE-PATTERNS.md`).

## Extending it

Adding a source means implementing one interface:

```java
public final class ClipboardProvider implements Provider {
    @Override public String id() { return "clipboard"; }

    @Override public List<Hit> search(String query) {
        return entries.stream()
                .filter(e -> FuzzyMatcher.score(query.toLowerCase(), e.textLower()) > 0)
                .map(e -> new Hit(new IndexItem(shorten(e.text()), "Clipboard", e.text(), ItemKind.CALC), 50))
                .toList();
    }
}
```

…and registering it in `LumenApp`. Ranking, keyboard handling and the UI need no changes;
`ItemKind` decides the Enter action (`CALC` copies, `WEB` opens, `WINDOW` switches to the
window handle stored in `path`, the rest launch through the shell).

## Windows integration

- **Hotkey** — `RegisterHotKey` + a dedicated thread running `GetMessage`, delivered to the
  UI with `Platform.runLater`. Chosen over a low-level keyboard hook: no elevation issues,
  no per-keystroke cost, and the OS does the matching. The configured combination is tried
  first, then `Ctrl+Alt+Space`, `Ctrl+Shift+Space` and ``Alt+` ``; the first two free
  combinations are registered, so a taken key degrades to a named fallback instead of a dead
  shortcut. A watchdog thread re-runs registration after sleep/resume (detected as a
  wall-clock gap), while the hotkey is unavailable, and every 15 s while a fallback is
  carrying the load, so the configured key is reclaimed when the other app exits. All
  re-registration happens on the pump thread via `PostThreadMessage`, because
  `RegisterHotKey` binds to the calling thread.
- **Lifecycle** — a never-shown `WS_POPUP` window (created on the pump thread, so one message
  loop serves everything) receives `WM_POWERBROADCAST`, `WM_WTSSESSION_CHANGE` and
  `WM_DISPLAYCHANGE`; session notifications are enabled with
  `WTSRegisterSessionNotification`. Resume, unlock and reconnect re-register the hotkey;
  display changes re-center the overlay on the new work area. Message-only windows would not
  receive these broadcasts, which is why this is a real top-level window.
- **Crash containment** — `Log.install()` also sets the default uncaught-exception handler:
  the trace goes to `lumen.log`, the tray stays alive, and a balloon says the app is still
  running. The tray menu offers **Restart Lumen**, which releases the instance lock before
  relaunching the app image.
- **Launching** — `Shell32.ShellExecute("open", …)` so shortcuts, documents, folders and URLs
  all resolve exactly as in Explorer. `explorer /select,` handles "show in folder".
- **Running windows** — `EnumWindows` lists visible, titled, non-tool windows that are not
  DWM-cloaked and not owned by Lumen. The process executable is resolved once per PID through
  `ProcessHandle`, and the list is cached for 700 ms so keystrokes do not pay for a full
  enumeration. Enter calls `SetForegroundWindow` with the same attach-input workaround used
  for the overlay, after `SW_RESTORE` if the window is minimized.
- **Shell icons** — `SHGetFileInfo` returns an `HICON`; it is painted into a 32-bit top-down
  DIB section with `DrawIconEx` and copied straight into a JavaFX `WritableImage`. Extraction
  runs on a single background thread so `SHGetFileInfo` can never stall typing, and the
  result is cached by executable or file path. Icons without an alpha channel are recovered
  through a sentinel-colour second pass.
- **Window** — `StageStyle.TRANSPARENT`, always-on-top, positioned on the display under the
  cursor at 18% of its height. While it is visible the native foreground window is polled
  every 200 ms; after 600 ms away from the foreground it hides. This deliberately does not
  use JavaFX's `focusedProperty`, which reports the stage as focused while Windows keeps
  another app in front — the cause of an always-on-top panel that blocked clicks and made
  Alt+Tab look broken. Falling out of the foreground for any reason (Alt+Tab, clicking
  another window, a UAC prompt) therefore closes the overlay, so the other app is usable
  immediately. `hideOnFocusLoss=false` keeps it open until `Esc` or the hotkey. Summon
  retries foreground activation at 0/60/150/300 ms and hides if activation never succeeds,
  rather than leaving an unfocused panel on top.
- **Action sheet** — `Ctrl+K` (or `→` at the end of the query) opens a keyboard-driven action
  list beside the results. It is rendered inside the overlay scene, not as a native popup: a
  popup is its own window, so Windows would report it — not Lumen — as the foreground window
  and the native-focus poll would hide the launcher underneath it. The sheet occupies a second
  column of the results row, so one layout owns both and `resize()` sizes the stage to exactly
  what is visible; `↑` `↓` move, `Enter` runs, `Esc` closes the sheet without closing the
  launcher, and typing replaces it.
- **Launch feedback** — a target is existence-checked before the overlay hides, so a file
  deleted after indexing produces an inline "Not found" banner instead of a silent close. A
  deferred `ShellExecute` failure (blocked by policy, association missing) reopens the overlay
  with the reason. `runas` cancellation (error 1223) counts as success, because nothing failed.
- **Tray** — AWT `SystemTray` with a live hotkey-status row, open, rebuild index, restart and
  quit, so the app is usable even if the hotkey could not be registered. The tooltip always
  names the active combination; the status text is stored without touching AWT until the icon
  exists, so start-up stays fast.
- **Single instance** — a file lock in `%LOCALAPPDATA%\\Lumen\instance.lock` stops two
  copies from fighting over the hotkey and tray icon. The handoff listener takes the first
  free port in `51733–51742` and records it in `instance.port`; a second launch reads that
  file and otherwise probes the range, so an unrelated app on one port cannot break the
  handoff.
- **Self-test** — 1.2 s after start, one `[self-test] ok/fail` line records the live hotkey,
  tray state, index size and overlay readiness. It is the first thing to read in a bug report.
