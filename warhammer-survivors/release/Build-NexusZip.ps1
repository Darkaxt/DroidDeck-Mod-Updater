<#
.SYNOPSIS
  Builds the Nexus Mods archive: MelonLoader 0.7.3 (pinned, SHA-256 verified) + Mods\WHSurvivorsTweaks.dll
  + WHSurvivorsTweaks.txt (the README), laid out to be extracted into the game folder.
.EXAMPLE
  pwsh -File release\Build-NexusZip.ps1 -CacheDir E:\Temp\wh-release
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CacheDir,
    [string]$OutDir = $PSScriptRoot
)
$ErrorActionPreference = 'Stop'

$Root        = Split-Path $PSScriptRoot -Parent
$Dll         = Join-Path $Root 'src\WHSurvivorsTweaks\bin\Release\WHSurvivorsTweaks.dll'
$Readme      = Join-Path $Root 'README.md'
$MelonUrl    = 'https://github.com/LavaGang/MelonLoader/releases/download/v0.7.3/MelonLoader.x64.zip'
$MelonSha256 = '5B2B2F3D1CD42B59EC886C5BDC2663EDAE87A0097A4F4A8F58C0965A99DDA416'

if (-not (Test-Path $Dll)) { throw "Build the mod first: $Dll not found." }
$version = (Get-Item $Dll).VersionInfo.ProductVersion -replace '\+.*$', ''

New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
$mlZip = Join-Path $CacheDir 'MelonLoader.x64.0.7.3.zip'
if (-not (Test-Path $mlZip) -or (Get-FileHash $mlZip -Algorithm SHA256).Hash -ne $MelonSha256) {
    Invoke-WebRequest -Uri $MelonUrl -OutFile $mlZip
}
$hash = (Get-FileHash $mlZip -Algorithm SHA256).Hash
if ($hash -ne $MelonSha256) { throw "MelonLoader hash mismatch: $hash" }

$stage = Join-Path $CacheDir 'stage'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $stage 'Mods') | Out-Null
Expand-Archive -Path $mlZip -DestinationPath $stage
Copy-Item $Dll (Join-Path $stage 'Mods\WHSurvivorsTweaks.dll')
Copy-Item $Readme (Join-Path $stage 'WHSurvivorsTweaks.txt')

$out = Join-Path $OutDir "WHSurvivorsTweaks-$version.zip"
if (Test-Path $out) { Remove-Item $out -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $out
Remove-Item $stage -Recurse -Force
$h = (Get-FileHash $out -Algorithm SHA256).Hash
Write-Host "Created $out ($((Get-Item $out).Length) bytes, SHA-256 $h)"
