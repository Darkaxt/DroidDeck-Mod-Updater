<#
.SYNOPSIS
  Installs (or re-applies after a game update) the WHSurvivorsTweaks MelonLoader mod
  for Warhammer Survivors: twin-stick aiming, auto-aim, optional ultrawide support.

.DESCRIPTION
  Safe to run repeatedly. Each run:
    1. Locates the game folder (or uses -GameDir).
    2. Installs the pinned MelonLoader release (download verified by SHA-256) if missing or different.
    3. Copies WHSurvivorsTweaks.dll (next to this script) into <game>\Mods.
  -Uninstall removes MelonLoader and the mod (UserData\ settings are kept).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File Install-WHSurvivorsTweaks.ps1
#>
[CmdletBinding()]
param(
    [string]$GameDir,
    [switch]$Uninstall,
    [string]$CacheDir = (Join-Path $PSScriptRoot 'cache')
)
$ErrorActionPreference = 'Stop'

$MelonVersion = '0.7.3'
$MelonUrl     = "https://github.com/LavaGang/MelonLoader/releases/download/v$MelonVersion/MelonLoader.x64.zip"
$MelonSha256  = '5b2b2f3d1cd42b59ec886c5bdc2663edae87a0097a4f4a8f58c0965a99dda416'
$ModFile      = 'WHSurvivorsTweaks.dll'
$ManifestName = 'WHSurvivorsTweaks.install.txt'   # list of files MelonLoader put in the game root

function Get-Sha256([string]$Path) {
    $sha = [Security.Cryptography.SHA256]::Create()
    $fs = [IO.File]::OpenRead($Path)
    try { -join ($sha.ComputeHash($fs) | ForEach-Object { $_.ToString('X2') }) } finally { $fs.Dispose(); $sha.Dispose() }
}

function Find-GameDir {
    $roots = @()
    try {
        $steam = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction Stop).SteamPath
        if ($steam) {
            $roots += $steam
            $vdf = Join-Path $steam 'steamapps\libraryfolders.vdf'
            if (Test-Path $vdf) {
                foreach ($m in [regex]::Matches((Get-Content $vdf -Raw), '"path"\s+"([^"]+)"')) {
                    $roots += $m.Groups[1].Value -replace '\\\\', '\'
                }
            }
        }
    } catch { }
    foreach ($r in $roots | Select-Object -Unique) {
        $p = Join-Path $r 'steamapps\common\Warhammer Survivors'
        if (Test-Path (Join-Path $p 'WarhammerSurvivors.exe')) { return $p }
    }
    throw 'Could not find Warhammer Survivors. Pass -GameDir "<path to game folder>".'
}

if (-not $GameDir) { $GameDir = Find-GameDir }
$GameDir = (Resolve-Path $GameDir).Path
if (-not (Test-Path (Join-Path $GameDir 'GameAssembly.dll'))) { throw "Not a Warhammer Survivors folder: $GameDir" }
Write-Host "Game folder: $GameDir"

if (Get-Process -Name 'WarhammerSurvivors' -ErrorAction SilentlyContinue) {
    throw 'Warhammer Survivors is running. Close it and run this script again.'
}

$manifest = Join-Path $GameDir $ManifestName

if ($Uninstall) {
    if (Test-Path $manifest) {
        foreach ($rel in Get-Content $manifest) {
            $p = Join-Path $GameDir $rel
            if ($rel -and (Test-Path -LiteralPath $p)) { Remove-Item -LiteralPath $p -Recurse -Force; Write-Host "Removed $rel" }
        }
        Remove-Item $manifest -Force
    } else {
        Write-Warning "No install manifest found; nothing from MelonLoader removed."
    }
    $mod = Join-Path $GameDir "Mods\$ModFile"
    if (Test-Path $mod) { Remove-Item $mod -Force; Write-Host "Removed Mods\$ModFile" }
    Write-Host 'Uninstalled. Settings in UserData\ were kept.'
    return
}

# --- 1. MelonLoader ---------------------------------------------------------------------------
$mlDll = Join-Path $GameDir 'MelonLoader\net6\MelonLoader.dll'
$haveVersion = if (Test-Path $mlDll) { (Get-Item $mlDll).VersionInfo.ProductVersion } else { $null }
if ($haveVersion -and $haveVersion.StartsWith($MelonVersion)) {
    Write-Host "MelonLoader $haveVersion already installed."
} else {
    New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
    $zip = Join-Path $CacheDir "MelonLoader.x64.$MelonVersion.zip"
    if (-not (Test-Path $zip) -or (Get-Sha256 $zip) -ne $MelonSha256.ToUpper()) {
        Write-Host "Downloading MelonLoader $MelonVersion ..."
        Invoke-WebRequest -Uri $MelonUrl -OutFile $zip -UseBasicParsing
    }
    $hash = (Get-Sha256 $zip)
    if ($hash -ne $MelonSha256.ToUpper()) { Remove-Item $zip -Force; throw "MelonLoader download hash mismatch ($hash)." }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($zip)
    try {
        $top = $archive.Entries | ForEach-Object { ($_.FullName -split '[\\/]')[0] } | Where-Object { $_ } | Select-Object -Unique
        foreach ($e in $archive.Entries) {
            $dest = Join-Path $GameDir $e.FullName
            if ($e.FullName.EndsWith('/') -or $e.FullName.EndsWith('\')) { New-Item -ItemType Directory -Force -Path $dest | Out-Null; continue }
            New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
        }
    } finally { $archive.Dispose() }
    Set-Content -Path $manifest -Value $top -Encoding ASCII
    Write-Host "Installed MelonLoader $MelonVersion."
}

# --- 2. The mod ---------------------------------------------------------------------------------
$src = Join-Path $PSScriptRoot $ModFile
$mods = Join-Path $GameDir 'Mods'
New-Item -ItemType Directory -Force -Path $mods | Out-Null
if (Test-Path $src) {
    Copy-Item $src (Join-Path $mods $ModFile) -Force
    Write-Host "Installed Mods\$ModFile."
} else {
    Write-Warning "$ModFile not found next to this script; only MelonLoader was installed."
}
Write-Host 'Done. The first launch after installing or after a game update takes longer while MelonLoader regenerates its files.'
