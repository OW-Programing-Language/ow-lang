# Package a self-contained Ow application (e.g. a game) as a native .exe +
# installer with jpackage. Bundles the JRE + the game's .ow files + a launcher
# that runs <appdir>/game/main.ow (dev.oreo.GameMain), so the result just opens
# the app — no Java, no `ow run`.
#
# Usage (from the ow-lang repo root, after `mvn -q -o package`):
#   powershell -File scripts/build-game-installer.ps1 -Name RockPaperScissors -GameSrc C:\path\to\game\src
#
# -GameSrc must contain the game's .ow files, including main.ow (the entry).
# For the .msi, WiX 3.14 (candle.exe/light.exe) must be on PATH.

param(
  [Parameter(Mandatory=$true)][string]$Name,
  [Parameter(Mandatory=$true)][string]$GameSrc,
  [string]$Version = "1.0.0",
  [string]$Icon = "assets/owide-icon.ico",
  [string]$UiMode = "native"   # native desktop window; use "web" for browser
)

$ErrorActionPreference = "Stop"
$jar = "target/OwIDE.jar"
if (-not (Test-Path $jar)) { throw "Build the jar first: mvn -q -o package -DskipTests" }

$input = "target/game-input"
Remove-Item $input -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$input/game" | Out-Null
Copy-Item $jar "$input/"
Copy-Item "$GameSrc/*.ow" "$input/game/"
if (-not (Test-Path "$input/game/main.ow")) { throw "No main.ow found in $GameSrc" }

$common = @(
  "--name", $Name, "--app-version", $Version,
  "--input", $input, "--main-jar", "OwIDE.jar", "--main-class", "dev.oreo.GameMain",
  "--java-options", "-Dow.ui.mode=$UiMode",
  "--icon", $Icon, "--vendor", "Oreo Studios"
)

New-Item -ItemType Directory -Force "target/game-out" | Out-Null
& jpackage --type app-image @common --dest "target/game-out"
Compress-Archive -Path "target/game-out/$Name" -DestinationPath "target/game-out/$Name-win-x64.zip" -Force
Write-Host "Portable: target/game-out/$Name-win-x64.zip"

if (Get-Command candle -ErrorAction SilentlyContinue) {
  New-Item -ItemType Directory -Force "target/game-msi" | Out-Null
  & jpackage --type msi @common `
    --win-menu --win-menu-group "Ow Games" --win-shortcut --win-per-user-install `
    --dest "target/game-msi"
  Write-Host "Installer: target/game-msi/$Name-$Version.msi"
} else {
  Write-Host "WiX not found - skipped .msi."
}
