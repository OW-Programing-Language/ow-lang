param(
  [ValidateSet("app-image", "exe", "msi")]
  [string]$Type = "app-image",
  [string]$ZigPath = "",
  [string]$Name = "OwIDE",
  [string]$Version = "1.0.0",
  [string]$Vendor = "Ow Language",
  [string]$InstallDir = "OwIDE"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$portableScript = Join-Path $repoRoot "scripts\prepare-portable-release.ps1"
$portableDir = Join-Path $repoRoot "target\portable\OwIDE-portable"
$stagingDir = Join-Path $repoRoot "t\in"
$outputDir = Join-Path $repoRoot "target\installer"
$tempRoot = Join-Path $repoRoot "t"
$tempDir = Join-Path $tempRoot ("jp-" + [guid]::NewGuid().ToString("N"))
$iconPath = Join-Path $repoRoot "assets\owide-icon.ico"

if ($ZigPath) {
  & powershell -ExecutionPolicy Bypass -File $portableScript -ZigPath $ZigPath -SkipZip | Out-Host
} else {
  & powershell -ExecutionPolicy Bypass -File $portableScript -SkipZip | Out-Host
}

if (Test-Path $outputDir) {
  Remove-Item -LiteralPath $outputDir -Recurse -Force
}
if (Test-Path $stagingDir) {
  Remove-Item -LiteralPath $stagingDir -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
New-Item -ItemType Directory -Force -Path $stagingDir | Out-Null
Copy-Item -Path (Join-Path $portableDir "*") -Destination $stagingDir -Recurse -Force

$args = @(
  "--type", $Type,
  "--input", $stagingDir,
  "--dest", $outputDir,
  "--name", $Name,
  "--main-jar", "OwIDE.jar",
  "--main-class", "dev.oreo.Main",
  "--app-version", $Version,
  "--vendor", $Vendor,
  "--description", "Ow IDE with bundled runtime and portable native toolchain",
  "--copyright", "Ow Language",
  "--temp", $tempDir
)

if ($Type -eq "exe" -or $Type -eq "msi") {
  $args += @(
    "--win-menu",
    "--win-shortcut",
    "--win-dir-chooser",
    "--install-dir", $InstallDir
  )
}

if (Test-Path $iconPath) {
  $args += @("--icon", $iconPath)
}

& jpackage @args

Write-Host "Installer output: $outputDir"
