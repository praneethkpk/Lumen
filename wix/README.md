# Lumen WiX MSI authoring templates

These files target **WiX Toolset v4** syntax.

## Files

- `Product.wxs` — per-user MSI package, major-upgrade handling, Start Menu shortcut,
  and install-directory cleanup.
- `AppFiles.wxs` — harvests the full `jpackage` app image into the install directory.

## Expected app-image layout

The current Lumen packaging script creates:

```text
dist/
  Lumen/
    Lumen.exe
    app/
    runtime/
```

The MSI installs that image under:

```text
%LOCALAPPDATA%\Programs\Lumen
```

The Start Menu shortcut points to `Lumen.exe`.

## Important build requirements

1. Build the app image first with `scripts\build-installer.ps1`.
2. Compile both `.wxs` files with WiX v4 and define:
   - `ProductVersion` (for example `0.1.0`)
   - `AppImageDir` (for example `dist\Lumen`)
3. Keep the `UpgradeCode` in `Product.wxs` unchanged for every release.
4. Increment `ProductVersion` for releases. The MSI package code should be regenerated
   for each build by the WiX toolchain.
5. Test install, upgrade, downgrade rejection, repair, and uninstall on a clean Windows VM.

## Compatibility warning

The repository's currently published `main` branch `pom.xml` inspected for this template
does not contain the WiX Maven profile described in the accompanying change summary.
Also, the proposed coordinates `org.wix:wix-maven-plugin:4.0.2` do not match the
official `wix-maven-plugin` project's published coordinates/documented configuration.
That project documents `com.github.wix-maven:wix-maven-plugin` and configures the
WiX v4 toolset separately.

Do not assume that a Maven property named `wix.version` selects the WiX compiler version.
Confirm the exact plugin release and its toolset artifact configuration before wiring
these templates into the Maven lifecycle.

## Notes

- This is a per-user installer and does not require administrator elevation.
- It intentionally does not add a Desktop shortcut or an auto-start entry.
- It does not bundle an icon or custom banner because the current public app-image
  script does not establish a stable icon asset path.
- These templates use WiX v4 `Files` harvesting. They are not compatible with WiX 3.11
  syntax without conversion.
