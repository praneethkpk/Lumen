param(
    [switch]$NoLaunch,
    [switch]$NoAutostart
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$source = Join-Path $root 'dist\Lumen'
if (-not (Test-Path (Join-Path $source 'Lumen.exe'))) {
    throw "Build first: scripts\build-installer.ps1"
}

$target = Join-Path $env:LOCALAPPDATA 'Programs\Lumen'
$log = Join-Path $env:LOCALAPPDATA 'Lumen\lumen.log'

# Retire the pre-rename Spotlight install. Leaving it in place would let two copies start
# at login and fight over Alt+Space. User data is migrated by the app itself on first run.
$legacyTarget = Join-Path $env:LOCALAPPDATA 'Programs\Spotlight'
$legacyLink = Join-Path ([Environment]::GetFolderPath('Startup')) 'Spotlight.lnk'
Get-Process Spotlight, Lumen -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2
if (Test-Path $legacyLink) { Remove-Item $legacyLink -Force }
if (Test-Path $legacyTarget) { Remove-Item $legacyTarget -Recurse -Force }

if (Test-Path $target) { Remove-Item $target -Recurse -Force }
New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
Copy-Item $source $target -Recurse

$exe = Join-Path $target 'Lumen.exe'

if (-not $NoAutostart) {
    $startup = [Environment]::GetFolderPath('Startup')
    $shell = New-Object -ComObject WScript.Shell
    $link = $shell.CreateShortcut((Join-Path $startup 'Lumen.lnk'))
    $link.TargetPath = $exe
    $link.WorkingDirectory = $target
    $link.Description = 'Lumen'
    $link.Save()
}

if (-not $NoLaunch) {
    Remove-Item $log -ErrorAction SilentlyContinue
    Start-Process $exe

    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $log) -and (Select-String -Path $log -Pattern 'hotkey. registered' -Quiet -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Milliseconds 100
    }
    Start-Sleep -Milliseconds 600
    Write-Host ""
    Get-Content $log -ErrorAction SilentlyContinue | Select-Object -Last 10
}

Write-Host ""
Write-Host "Installed:  $exe"
if (-not $NoAutostart) {
    Write-Host "Autostart:  $([Environment]::GetFolderPath('Startup'))\Lumen.lnk"
}
Write-Host "Log:        $log"
Write-Host "Running:    $([bool](Get-Process Lumen -ErrorAction SilentlyContinue))"
Write-Host ""
Write-Host "Press Alt+Space (fallbacks: Ctrl+Alt+Space, Ctrl+Shift+Space, Alt+backtick). Remove autostart with:"
Write-Host "  Remove-Item '$([Environment]::GetFolderPath('Startup'))\Lumen.lnk'"
