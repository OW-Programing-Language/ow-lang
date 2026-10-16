# Package ANY self-contained Ow application (game, console tool, or web
# backend) as a native .exe + installer with jpackage. Bundles the JRE + the
# program's .ow files + a launcher that runs <appdir>/game/main.ow
# (dev.oreo.GameMain) — the result just opens the app. No Java, no `ow run`.
#
# Usage (from the ow-lang repo root, after `mvn -q -o package`):
#   powershell -File scripts/build-app-installer.ps1 -Name NotesAPI -AppSrc C:\path\to\app\src -Type web
#
# -Type:
#   gui      GUI/desktop app (ui_run)      -> windowed, native UI mode
#   console  console tool (input()/print)  -> console window
#   web      web backend (route/serve)     -> console window + auto-opens browser
#
# For the .msi, WiX 3.14 (candle.exe/light.exe) must be on PATH.

param(
  [Parameter(Mandatory=$true)][string]$Name,
  [Parameter(Mandatory=$true)][string]$AppSrc,
  [ValidateSet("gui","console","web")][string]$Type = "gui",
  [string]$Version = "1.0.0",
  [string]$Icon = "assets/owide-icon.ico"
)

$ErrorActionPreference = "Stop"
$jar = "target/OwIDE.jar"
if (-not (Test-Path $jar)) { throw "Build the jar first: mvn -q -o package -DskipTests" }

$input = "target/app-input"
Remove-Item $input -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$input/game" | Out-Null
Copy-Item $jar "$input/"
Copy-Item "$AppSrc/*.ow" "$input/game/"
if (-not (Test-Path "$input/game/main.ow")) { throw "No main.ow found in $AppSrc" }

$common = @(
  "--name", $Name, "--app-version", $Version,
  "--input", $input, "--main-jar", "OwIDE.jar", "--main-class", "dev.oreo.GameMain",
  "--icon", $Icon, "--vendor", "Oreo Studios"
)
switch ($Type) {
  "gui"     { $common += @("--java-options", "-Dow.ui.mode=native") }
  "console" { $common += @("--win-console") }
  "web"     { $common += @("--win-console") }   # web server auto-opens the browser
}

New-Item -ItemType Directory -Force "target/app-out" | Out-Null
& jpackage --type app-image @common --dest "target/app-out"
Compress-Archive -Path "target/app-out/$Name" -DestinationPath "target/app-out/$Name-win-x64.zip" -Force
Write-Host "Portable: target/app-out/$Name-win-x64.zip"

if (Get-Command candle -ErrorAction SilentlyContinue) {
  New-Item -ItemType Directory -Force "target/app-msi" | Out-Null
  & jpackage --type msi @common `
    --win-menu --win-menu-group "Ow Apps" --win-shortcut --win-per-user-install `
    --dest "target/app-msi"
  Write-Host "Installer: target/app-msi/$Name-$Version.msi"
} else {
  Write-Host "WiX not found - skipped .msi."
}
