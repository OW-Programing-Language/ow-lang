# Build the native, JRE-bundled OwIDE distribution with jpackage.
#
#   - app-image  -> portable folder (OwIDE.exe + ow.exe + bundled runtime)
#   - msi        -> Windows installer (Start-menu/desktop shortcuts); needs WiX 3.x
#
# Requires: JDK 17+ (for jpackage). For the .msi, WiX 3.14 (candle.exe/light.exe)
# must be on PATH (https://github.com/wixtoolset/wix3/releases).
#
# Usage (from repo root, after `mvn -q -o package`):
#   powershell -File scripts/build-native-installer.ps1

$ErrorActionPreference = "Stop"
$version = "1.2.0"
$jar     = "target/OwIDE.jar"
if (-not (Test-Path $jar)) { throw "Build the jar first: mvn -q -o package -DskipTests" }

# Stage an input dir containing only the jar.
$input = "target/jp-input"
Remove-Item $input -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $input | Out-Null
Copy-Item $jar "$input/"

# A console launcher for the `ow` CLI (so stdin/stdout work); OwIDE stays GUI.
$props = "target/ow-launcher.properties"
"win-console=true" | Out-File -Encoding ascii $props

$common = @(
  "--name", "OwIDE",
  "--app-version", $version,
  "--input", $input,
  "--main-jar", "OwIDE.jar",
  "--main-class", "dev.oreo.Main",
  "--icon", "assets/owide-icon.ico",
  "--add-launcher", "ow=$props",
  "--vendor", "Oreo Studios"
)

# 1) Portable app-image
New-Item -ItemType Directory -Force "target/jp-out" | Out-Null
& jpackage --type app-image @common --dest "target/jp-out"
Compress-Archive -Path "target/jp-out/OwIDE" -DestinationPath "target/jp-out/OwIDE-$version-win-x64.zip" -Force
Write-Host "Portable: target/jp-out/OwIDE-$version-win-x64.zip"

# 2) MSI installer (requires WiX on PATH)
if (Get-Command candle -ErrorAction SilentlyContinue) {
  New-Item -ItemType Directory -Force "target/jp-msi" | Out-Null
  & jpackage --type msi @common `
    --win-menu --win-menu-group "Ow" --win-shortcut --win-dir-chooser --win-per-user-install `
    --dest "target/jp-msi"
  Write-Host "Installer: target/jp-msi/OwIDE-$version.msi"
} else {
  Write-Host "WiX not found on PATH - skipped .msi. Install WiX 3.14 to build it."
}
