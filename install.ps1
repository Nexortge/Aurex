# Aurex installer — Windows, one-liner via:
#   irm https://github.com/Nexortge/Aurex/raw/main/install.ps1 | iex
#
# Re-run the same command to update. Pass -Uninstall to remove.

[CmdletBinding()]
param(
    [switch]$Uninstall,
    [switch]$Purge
)

# PS 5.1 defaults to TLS 1.0, which GitHub rejects — force 1.2 before any HTTP call.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$ErrorActionPreference = 'Stop'

$JarUrl     = 'https://github.com/Nexortge/Aurex/releases/latest/download/aurex-agent.jar'
$AppDir     = Join-Path $env:APPDATA 'Aurex'
$JarPath    = Join-Path $AppDir 'aurex-agent.jar'
$ConfigPath = Join-Path $AppDir 'config.json'
$LunarDir   = Join-Path $env:LOCALAPPDATA 'Programs\Lunar Client'

try {
    # Pre-flight — Windows only. PS 5.1 doesn't define $IsWindows, so infer from
    # the environment variable (present on every Windows box, absent elsewhere).
    if (-not $env:APPDATA -or -not $env:LOCALAPPDATA) {
        throw 'not-windows'
    }
    if (-not (Test-Path $LunarDir)) {
        throw 'lunar-not-found'
    }

    if ($Uninstall) {
        if (Test-Path $JarPath) {
            Remove-Item -Force $JarPath
            Write-Host "Removed $JarPath"
        }
        if ($Purge -and (Test-Path $AppDir)) {
            Remove-Item -Force -Recurse $AppDir
            Write-Host "Removed $AppDir (config + modes)"
        }
        Write-Host ""
        Write-Host "Uninstalled. Remember to remove the -javaagent: line from"
        Write-Host "  Lunar -> Settings -> Java Integration -> JVM Arguments."
        exit 0
    }

    # Ensure %APPDATA%\Aurex exists, then download the jar (overwrites on update).
    New-Item -ItemType Directory -Force -Path $AppDir | Out-Null
    Write-Host "Downloading latest build..."
    Invoke-WebRequest -UseBasicParsing -Uri $JarUrl -OutFile $JarPath

    # Config scaffold — only on a clean install. Never clobber existing keys.
    if (-not (Test-Path $ConfigPath)) {
        Write-Host ""
        Write-Host "Enter API keys (press Enter to skip any):"
        $apiKey    = Read-Host "  Hypixel API key"
        $seraphKey = Read-Host "  Seraph API key"
        $urchinKey = Read-Host "  Urchin API key"

        # Build the JSON by hand — ConvertTo-Json quotes booleans as strings in
        # some PS versions and there's no %APPDATA%\Aurex\modes\*.json yet, so
        # keep it aligned with what Config.buildDefaultGlobalJson() writes.
        $apiKey    = if ($apiKey)    { $apiKey }    else { '' }
        $seraphKey = if ($seraphKey) { $seraphKey } else { '' }
        $urchinKey = if ($urchinKey) { $urchinKey } else { '' }

        $json = @"
{
    "apiKey": "$apiKey",
    "seraphApiKey": "$seraphKey",
    "urchinApiKey": "$urchinKey",
    "activeMode": "bedwars",
    "nickDetection": true,
    "chatAlerts": true,
    "ignoreList": []
}
"@
        [System.IO.File]::WriteAllText($ConfigPath, $json, [System.Text.UTF8Encoding]::new($false))
    }

    # Final instructions — the user copies the -javaagent: line into Lunar.
    Write-Host ""
    Write-Host "Installed." -ForegroundColor Green
    Write-Host ""
    Write-Host "To activate, paste this line into Lunar:"
    Write-Host "  Settings -> Java Integration -> JVM Arguments"
    Write-Host ""
    Write-Host "  -javaagent:$JarPath" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Then restart Lunar."
}
catch {
    Write-Host ""
    Write-Host "Something went wrong. Please contact the owner for assistance." -ForegroundColor Red
    exit 1
}
