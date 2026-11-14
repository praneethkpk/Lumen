param(
    [switch]$Console
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

$jpackage = Get-Command jpackage -ErrorAction SilentlyContinue
if (-not $jpackage -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
    if (Test-Path $candidate) { $jpackage = Get-Item $candidate }
}
if (-not $jpackage) { throw "jpackage not found; use a JDK 17+ (not a JRE) and add its bin folder to PATH" }

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

if ($Console) {
    $jpackageArgs += '--win-console'
}

& $jpackage.Source @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

Write-Host ""
Write-Host "Built: dist\Lumen\Lumen.exe"
Write-Host "Runs without a console; diagnostics go to %LOCALAPPDATA%\Lumen\lumen.log"
Write-Host "Use -Console to attach a console window for development."
