param(
  [string]$ZigPath = "",
  [string]$Version = "1.0.0"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$portableScript = Join-Path $repoRoot "scripts\prepare-portable-release.ps1"
$installerScript = Join-Path $repoRoot "scripts\build-installer.ps1"
$templateDir = Join-Path $repoRoot "installer-template"
$appImageDir = Join-Path $repoRoot "target\installer-app\OwIDE"
$distDir = Join-Path $repoRoot "target\install-dist"
$zipPath = Join-Path $repoRoot "target\install-dist\OwIDE-Setup-$Version.zip"
$iconPath = Join-Path $repoRoot "assets\owide-icon.ico"

& powershell -ExecutionPolicy Bypass -File $portableScript -ZigPath $ZigPath | Out-Host

if (Test-Path (Join-Path $repoRoot "target\installer-app")) {
  Remove-Item -LiteralPath (Join-Path $repoRoot "target\installer-app") -Recurse -Force
}

$jpackageArgs = @(
  "--type", "app-image",
  "--input", (Join-Path $repoRoot "target\portable\OwIDE-portable"),
  "--dest", (Join-Path $repoRoot "target\installer-app"),
  "--name", "OwIDE",
  "--main-jar", "OwIDE.jar",
  "--main-class", "dev.oreo.Main",
  "--app-version", $Version,
  "--vendor", "Ow Language",
  "--description", "Ow IDE with bundled runtime and portable native toolchain"
)

if (Test-Path $iconPath) {
  $jpackageArgs += @("--icon", $iconPath)
}

jpackage @jpackageArgs

if (Test-Path $distDir) {
  Remove-Item -LiteralPath $distDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

Copy-Item -LiteralPath $appImageDir -Destination (Join-Path $distDir "OwIDE") -Recurse -Force
Copy-Item -LiteralPath (Join-Path $templateDir "Install-OwIDE.ps1") -Destination (Join-Path $distDir "Install-OwIDE.ps1") -Force

if (Test-Path $zipPath) {
  Remove-Item -LiteralPath $zipPath -Force
}
Compress-Archive -Path (Join-Path $distDir "*") -DestinationPath $zipPath -Force

Write-Host "Installable zip: $zipPath"
