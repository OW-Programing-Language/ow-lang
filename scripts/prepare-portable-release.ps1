param(
  [string]$ZigPath = "",
  [string]$OutputDir = "target\portable\OwIDE-portable",
  [switch]$SkipZip
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $repoRoot "target\OwIDE.jar"
$templateDir = Join-Path $repoRoot "release-template"
$outDir = Join-Path $repoRoot $OutputDir
$zipPath = Join-Path $repoRoot "target\portable\OwIDE-portable.zip"

if (-not (Test-Path $jarPath)) {
  throw "Missing target\OwIDE.jar. Run: mvn -q -DskipTests package"
}

if (Test-Path $outDir) {
  Remove-Item -LiteralPath $outDir -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Copy-Item -Path (Join-Path $templateDir "*") -Destination $outDir -Recurse -Force
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $outDir "OwIDE.jar") -Force

if ($ZigPath -and (Test-Path $ZigPath)) {
  $zigOutDir = Join-Path $outDir "tools\zig"
  Remove-Item -LiteralPath $zigOutDir -Recurse -Force -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force -Path $zigOutDir | Out-Null
  $zigExe = Join-Path $ZigPath "zig.exe"
  $zigLib = Join-Path $ZigPath "lib"
  $zigLicense = Join-Path $ZigPath "LICENSE"
  $zigReadme = Join-Path $ZigPath "README.md"

  if (Test-Path $zigExe) { Copy-Item -LiteralPath $zigExe -Destination (Join-Path $zigOutDir "zig.exe") -Force }
  if (Test-Path $zigLib) { Copy-Item -LiteralPath $zigLib -Destination (Join-Path $zigOutDir "lib") -Recurse -Force }
  if (Test-Path $zigLicense) { Copy-Item -LiteralPath $zigLicense -Destination (Join-Path $zigOutDir "LICENSE") -Force }
  if (Test-Path $zigReadme) { Copy-Item -LiteralPath $zigReadme -Destination (Join-Path $zigOutDir "README.md") -Force }
}

$zipDir = Split-Path -Parent $zipPath
Write-Host "Portable folder: $outDir"
if (-not $SkipZip) {
  New-Item -ItemType Directory -Force -Path $zipDir | Out-Null
  if (Test-Path $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
  }

  Compress-Archive -Path (Join-Path $outDir "*") -DestinationPath $zipPath -Force
  Write-Host "Portable zip:    $zipPath"
}
