# Package ANY self-contained Ow application (game, console tool, or web
# backend) as a native .exe + installer with jpackage. Bundles the JRE + the
# program's .ow files + a launcher that runs <appdir>/game/main.ow
# (dev.oreo.GameMain) — the result just opens the app. No Java, no `ow run`.
#
# Run from the ow-lang repo root (this folder's parent):
#   powershell -File scripts\build-app-installer.ps1 -Name RockPaperScissors -AppSrc C:\path\to\game\src -Type gui
#
# -Type: gui (windowed, native UI) | console (console window) | web (console + opens browser)
# The .msi step needs WiX 3.14 (candle.exe/light.exe) on PATH; otherwise only the portable zip is produced.

param(
  [Parameter(Mandatory=$true)][string]$Name,
  [Parameter(Mandatory=$true)][string]$AppSrc,
  [ValidateSet("gui","console","web")][string]$Type = "gui",
  [string]$Version = "1.0.0",
  [string]$Icon = "assets/owide-icon.ico"
)

$ErrorActionPreference = "Stop"

# Run from the repo root (the parent of this script's folder).
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

# Locate jpackage: PATH -> JAVA_HOME -> newest JDK under Program Files\Java.
function Find-JPackage {
  $c = Get-Command jpackage -ErrorAction SilentlyContinue
  if ($c) { return $c.Source }
  if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) { return "$env:JAVA_HOME\bin\jpackage.exe" }
  $jp = Get-ChildItem "C:\Program Files\Java\*\bin\jpackage.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
  if ($jp) { return $jp.FullName }
  throw "jpackage not found. Install a JDK 17+ (it ships with jpackage)."
}
$jpackage = Find-JPackage
Write-Host "Using jpackage: $jpackage"

$jar = "target\OwIDE.jar"
if (-not (Test-Path $jar)) { throw "Build the jar first:  mvn -q -o package -DskipTests" }
if (-not (Test-Path (Join-Path $AppSrc 'main.ow'))) { throw "No main.ow found in $AppSrc" }

$input = "target\app-input"
Remove-Item $input -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$input\game" | Out-Null
Copy-Item $jar "$input\"
Copy-Item "$AppSrc\*.ow" "$input\game\"

$common = @(
  "--name", $Name, "--app-version", $Version,
  "--input", $input, "--main-jar", "OwIDE.jar", "--main-class", "dev.oreo.GameMain",
  "--icon", $Icon, "--vendor", "Oreo Studios"
)
switch ($Type) {
  "gui"     { $common += @("--java-options", "-Dow.ui.mode=native") }
  default   { $common += @("--win-console") }   # console + web
}

Remove-Item "target\app-out","target\app-msi" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "target\app-out" | Out-Null
& $jpackage --type app-image @common --dest "target\app-out"
Compress-Archive -Path "target\app-out\$Name" -DestinationPath "target\app-out\$Name-win-x64.zip" -Force
Write-Host "`nPortable : target\app-out\$Name-win-x64.zip"
Write-Host "Exe      : target\app-out\$Name\$Name.exe"

if (Get-Command candle -ErrorAction SilentlyContinue) {
  New-Item -ItemType Directory -Force "target\app-msi" | Out-Null
  & $jpackage --type msi @common --win-menu --win-menu-group "Ow Apps" --win-shortcut --win-per-user-install --dest "target\app-msi"
  Write-Host "Installer: target\app-msi\$Name-$Version.msi"
} else {
  Write-Host "WiX not found on PATH - skipped the .msi (the portable .exe above still works)."
}
