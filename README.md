# Spotlight for Windows

A fast, native search overlay for Windows: press **Alt+Space**, type, hit **Enter**.

It launches apps from your Start Menu, finds files under your folders, evaluates math and
unit conversions inline, and falls back to a web search — all from one search bar.

Built with Java 21 + JavaFX + JNA. One process, no browser engine, no IPC between a backend
and a UI.

![status](https://img.shields.io/badge/version-0.1.0-blue)

## Quick start

Requires a JDK 21 (the build uses `maven.compiler.release=21`) and Maven 3.9+.

Development:

```powershell
git clone <this-repo> spotlight-win
cd spotlight-win
mvn javafx:run
```

Daily use — build the app image, install it under `%LOCALAPPDATA%\Programs\Spotlight`, add
autostart, and launch it:

```powershell
scripts\build-installer.ps1
scripts\install.ps1
```

`scripts\install.ps1 -NoAutostart -NoLaunch` installs without touching startup or launching.
`scripts\run.ps1` runs from source.

The app starts in the tray and stays out of the way. Press **Alt+Space** from anywhere to
summon it; the overlay also opens once on a fresh install so you know it works.

> **Ctrl+Alt+Space** is always registered as a backup, so the launcher stays usable if
> another app owns Alt+Space. The active combination is logged at startup.

## Keys

| Key | Action |
| --- | --- |
| `Alt+Space` | Show / hide the overlay |
| `Enter` | Open the selected result |
| `Ctrl+Enter` | Show the selected item in Explorer |
| `↑` `↓` / `Tab` | Move the selection |
| `Esc` | Clear the query, then close |

## What it finds

- **Apps** — every `.lnk`, `.url`, `.exe` and `.appref-ms` in the user and machine Start Menus.
- **Files and folders** — everything under the configured roots, skipping hidden items,
  `node_modules`, `.git`, `target`, `AppData`, and similar noise.
- **Calculations** — `12 * (3 + 4)`, `sqrt(144)`, `2^10`, `pi * 2`. Enter copies the result.
- **Unit conversions** — `10 km to miles`, `1 kg in lb`, `100 c to f`, `1 mb to kb`,
  `90 min to hours`.
- **Web** — anything else offers "Search the web for …" as the last row.

Frequently and recently opened results float to the top; an empty query shows recent apps.

## Configuration

Settings live in `%LOCALAPPDATA%\SpotlightWin\config.json` (created on first run):

```json
{
  "hotkey": "ALT+SPACE",
  "roots": ["C:\\Users\\you\\Desktop", "C:\\Users\\you\\Documents"],
  "maxDepth": 8,
  "maxIndexedFiles": 120000,
  "maxResults": 40,
  "showOnStartup": false,
  "webSearchUrl": "https://www.google.com/search?q=%s"
}
```

Restart the app after editing. Hotkey syntax is `MOD+MOD+KEY`, e.g. `CTRL+ALT+S`,
`WIN+SPACE`, `ALT+F2`.

The index snapshot (`index.json`) and usage history (`usage.json`) sit next to it. Deleting
`index.json` is safe — it is rebuilt on the next start.

## Install as a real app

```powershell
scripts\build-installer.ps1
scripts\install.ps1
```

The first command produces a self-contained app image in `dist\Spotlight\` with a bundled
runtime (~160 MB, no Java required on the target machine). The second copies it to
`%LOCALAPPDATA%\Programs\Spotlight`, adds a Startup shortcut, and launches it. Uninstall by
deleting that folder and the `Spotlight.lnk` shortcut.

For an `.exe`/`.msi` installer instead of an app image, install [WiX Toolset](https://wixtoolset.org/)
and run `jpackage --type msi ...` with the same arguments (see the script). A signed MSI and
auto-update are on the roadmap.

## Performance notes

Measured on the development machine:

| Step | Time |
| --- | --- |
| Load index snapshot at startup (3.4k items) | 62–118 ms |
| Full reindex (warm cache, background) | 0.6–1.7 s |
| Keystroke → ranked results (3.4k items) | 0.48 ms p50 / 1.47 ms p95 |
| Keystroke → ranked results (120k items) | 5.9 ms p50 / 20 ms p95 |
| Hotkey → window visible (warm) | 45–88 ms |

Search runs on the JavaFX thread on purpose: at these numbers, dispatching to a worker and
back would cost more than the search itself. The index array is swapped atomically, so a
rebuild never blocks a query or produces a torn view.

## Project layout

```
src/main/java/dev/spotlight/
  SpotlightApp.java        wiring: config, index, providers, tray, hotkey
  ui/Overlay.java          the borderless search window
  ui/ResultCell.java       one result row
  core/Indexer.java        Start Menu + folder walker (virtual thread)
  core/IndexStore.java     in-memory index + JSON snapshot
  core/SearchEngine.java   provider merge + ranking
  core/FuzzyMatcher.java   scoring
  core/UsageStore.java     frequency/recency boost
  providers/               one class per result source
  platform/                Win32: hotkey, ShellExecute, tray
  calc/                    expression parser + unit tables
```

## Roadmap

Shipped:

- [x] App launch + file search
- [x] Global hotkey overlay with a permanent backup combination
- [x] Calculator + unit conversion
- [x] Web search fallback
- [x] Provider-based extensibility
- [x] Single instance, tray fallback, file logging

Next, in priority order — see [docs/ROADMAP.md](docs/ROADMAP.md) for the full plan, targets,
acceptance criteria, and effort estimates:

- [ ] Hotkey supervisor + sleep/resume/RDP re-registration (P0)
- [ ] Never hide on transient focus loss; raise instead (P0)
- [ ] jlink runtime + AppCDS: smaller install, faster cold start (P0)
- [ ] Ranking evaluation harness, trigram pruning, acronym/typo scoring (P1)
- [ ] Full-disk search via Everything SDK, then Windows Search (P1)
- [ ] Real shell icons, UWP/Store apps, running-window switching (P1)
- [ ] Settings UI with live hotkey capture (P1)
- [ ] Clipboard history, content search, system commands (P2)
- [ ] Signed MSI, auto-update, CI (P2)

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design rationale and
[docs/ROADMAP.md](docs/ROADMAP.md) for the improvement plan.

## License

MIT
