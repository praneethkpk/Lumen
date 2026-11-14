param(
    [switch]$Package,
    [switch]$Bench
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root

# Native tools write progress and warnings to stderr. With ErrorActionPreference=Stop,
# PowerShell would turn that into a terminating error even when the command succeeded,
# so capture every stream to a log and decide from the exit code instead.
function Invoke-Maven {
    param(
        [string]$Label,
        [string[]]$MavenArgs
    )

    $log = Join-Path $env:TEMP "lumen-verify-$Label.log"
    $errLog = "$log.err"
    # Start-Process keeps native stderr out of PowerShell's error stream, where
    # ErrorActionPreference=Stop would treat a normal Maven warning as a failure.
    $process = Start-Process -FilePath 'mvn.cmd' -ArgumentList $MavenArgs -NoNewWindow -Wait `
        -PassThru -RedirectStandardOutput $log -RedirectStandardError $errLog
    if ($process.ExitCode -ne 0) {
        Write-Host "  last 80 lines of $log" -ForegroundColor Red
        Get-Content $log -Tail 80 | Write-Host
        Get-Content $errLog -Tail 40 | Write-Host
        throw "Maven '$Label' failed (exit $($process.ExitCode)). Full log: $log"
    }
    Write-Host "  $Label passed"
}

try {
    Write-Host "== tests =="
    $mavenArgs = if ($Bench) {
        @('-q', '-Dtest=SearchBenchmarkTest', '-Dbench=true', 'test')
    } else {
        @('-q', 'test')
    }
    Invoke-Maven -Label 'tests' -MavenArgs $mavenArgs

    Write-Host "== source size =="
    $oversized = @(
        Get-ChildItem 'src\main\java' -Recurse -Filter *.java |
            Where-Object { (Get-Content $_.FullName | Measure-Object -Line).Lines -gt 800 }
    )
    if ($oversized.Count -gt 0) {
        $oversized | ForEach-Object { Write-Host "  too large: $($_.FullName)" }
        throw "Move cohesive logic out of the files above before continuing."
    }
    Write-Host "  all sources <= 800 lines"

    if ($Package) {
        Write-Host "== package =="
        # A child script's failure throws; there is no meaningful $LASTEXITCODE for it.
        & "$PSScriptRoot\build-installer.ps1"
        if (-not $?) {
            throw "Packaging failed"
        }
        Write-Host "  app image built"
    }

    Write-Host ""
    Write-Host "VERIFY PASSED" -ForegroundColor Green
} finally {
    Pop-Location
}
