param(
    [switch]$Console,
    [switch]$FullRuntime
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

mvn -q -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "maven package failed" }

$jar = Get-ChildItem target\*.jar |
    Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
    Select-Object -First 1
if (-not $jar) { throw "no jar produced in target\" }

Copy-Item $jar.FullName target\lib\ -Force

function Find-JdkTool([string]$name) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\$name.exe"
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

$jpackage = Find-JdkTool 'jpackage'
if (-not $jpackage) {
    throw "jpackage not found; use a JDK 17+ (not a JRE) and add its bin folder to PATH"
}

# The runtime image is built from JavaFX jmods, which are not published to Maven Central;
# they ship in the JavaFX SDK. Cache them under .cache\ so repeat builds are offline.
$fxJar = Get-ChildItem target\lib -Filter 'javafx-controls-*-win.jar' | Select-Object -First 1
if (-not $fxJar) { throw "javafx-controls jar missing from target\lib" }
if ($fxJar.Name -notmatch 'javafx-controls-(\d[\d.]*)') { throw "cannot read the JavaFX version" }
$fxVersion = $Matches[1]

$runtime = 'target\runtime'
if ($FullRuntime) {
    Write-Host "Building with the full JDK runtime (-FullRuntime)."
} else {
    $jlink = Find-JdkTool 'jlink'
    if (-not $jlink) {
        throw "jlink not found; use a JDK 21 and add its bin folder to PATH (or pass -FullRuntime)"
    }

    $cache = Join-Path (Get-Location) '.cache'
    $jmods = Join-Path $cache "javafx-jmods-$fxVersion"
    if (-not (Test-Path (Join-Path $jmods 'javafx.controls.jmod'))) {
        $arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'aarch64' } else { 'x64' }
        $url = "https://download2.gluonhq.com/openjfx/$fxVersion/openjfx-${fxVersion}_windows-${arch}_bin-jmods.zip"
        $zip = Join-Path $cache "openjfx-$fxVersion-jmods.zip"
        New-Item -ItemType Directory -Force $cache | Out-Null
        if (-not (Test-Path $zip)) {
            Write-Host "Downloading JavaFX $fxVersion jmods (first build only)..."
            $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
            if ($curl) {
                & $curl.Source --silent --show-error -L --fail --retry 2 -o $zip $url
                if ($LASTEXITCODE -ne 0) { throw "could not download the JavaFX jmods from $url" }
            } else {
                Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
            }
        }
        Expand-Archive -Path $zip -DestinationPath $cache -Force
    }
    if (-not (Test-Path (Join-Path $jmods 'javafx.controls.jmod'))) {
        throw "JavaFX jmods not found in $jmods"
    }

    if (Test-Path $runtime) { Remove-Item $runtime -Recurse -Force }
    Write-Host "Linking a runtime image (JavaFX $fxVersion) with CDS..."
    & $jlink --module-path $jmods `
        --add-modules javafx.controls,java.desktop,java.logging,java.prefs,jdk.unsupported `
        --output $runtime `
        --strip-debug --no-header-files --no-man-pages --compress=zip-6 `
        --generate-cds-archive
    if ($LASTEXITCODE -ne 0) { throw "jlink failed" }
    $cdsArchive = Get-ChildItem $runtime -Recurse -Filter *.jsa -File |
        Select-Object -First 1
    if (-not $cdsArchive) { throw "jlink did not produce a CDS archive" }
}

New-Item -ItemType Directory -Force dist | Out-Null
if (Test-Path dist\Lumen) {
    Remove-Item dist\Lumen -Recurse -Force
}

$jpackageArgs = @(
    '--type', 'app-image',
    '--name', 'Lumen',
    '--app-version', '0.1.0',
    '--input', 'target\lib',
    '--main-jar', $jar.Name,
    '--main-class', 'dev.lumen.Launcher',
    '--dest', 'dist'
)

if (-not $FullRuntime) {
    $jpackageArgs += @('--runtime-image', (Resolve-Path $runtime).Path)
}

if ($Console) {
    $jpackageArgs += '--win-console'
}

& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

if (-not $FullRuntime) {
    # Copy the jlink-generated CDS archive next to the app jars and point the JVM at it, so
    # the packaged image loads classes from the shared archive instead of re-reading the
    # module image on every cold start.
    Copy-Item $cdsArchive.FullName (Join-Path 'dist\Lumen' 'app\lumen.jsa') -Force
    $cfgPath = Join-Path 'dist\Lumen' 'app\Lumen.cfg'
    $cfg = Get-Content $cfgPath -Raw
    $cfg = Get-Content $cfgPath -Raw
    $cfg = $cfg -replace '(?ms)^\[JavaOptions\].*$', "[JavaOptions]`r`njava-options=-Djpackage.app-version=0.1.0 -XX:SharedArchiveFile=`$APPDIR/lumen.jsa -XX:+AutoCreateSharedArchive -Xshare:auto"
    Set-Content -Path $cfgPath -Value $cfg -NoNewline
}

$runtimeMb = if (Test-Path (Join-Path 'dist\Lumen' 'runtime')) {
    (Get-ChildItem 'dist\Lumen\runtime' -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
} else { 0 }
$totalMb = (Get-ChildItem 'dist\Lumen' -Recurse -File | Measure-Object Length -Sum).Sum / 1MB

Write-Host ""
Write-Host "Built: dist\Lumen\Lumen.exe"
Write-Host ("Runtime: {0:N0} MB bundled, {1:N0} MB installed" -f $runtimeMb, $totalMb)
Write-Host "Runs without a console; diagnostics go to %LOCALAPPDATA%\Lumen\lumen.log"
Write-Host "Use -Console to attach a console window for development."
