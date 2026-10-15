param(
  [string]$Name = "OwIDE-Setup",
  [string]$Version = "1.0.0"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$appImageDir = Join-Path $repoRoot "target\installer-app\OwIDE"
$templateDir = Join-Path $repoRoot "installer-template"
$workDir = Join-Path $repoRoot "target\iexpress"
$payloadDir = Join-Path $workDir "payload"
$sedPath = Join-Path $workDir "$Name.sed"
$targetExe = Join-Path $repoRoot "target\installer\$Name.exe"
$appZip = Join-Path $payloadDir "OwIDE-app.zip"

if (-not (Test-Path $appImageDir)) {
  throw "Missing app image: $appImageDir"
}

if (Test-Path $workDir) {
  Remove-Item -LiteralPath $workDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $payloadDir | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $targetExe) | Out-Null

tar.exe -a -cf $appZip -C (Split-Path -Parent $appImageDir) (Split-Path -Leaf $appImageDir)
Copy-Item -LiteralPath (Join-Path $templateDir "Install-OwIDE.ps1") -Destination (Join-Path $payloadDir "Install-OwIDE.ps1") -Force
Copy-Item -LiteralPath (Join-Path $templateDir "Run-Install.cmd") -Destination (Join-Path $payloadDir "Run-Install.cmd") -Force

$sed = @"
[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=1
HideExtractAnimation=0
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=
DisplayLicense=
FinishMessage=OwIDE installation finished.
TargetName=$targetExe
FriendlyName=OwIDE Setup $Version
AppLaunched=Run-Install.cmd
PostInstallCmd=<None>
AdminQuietInstCmd=
UserQuietInstCmd=
SourceFiles=SourceFiles
[Strings]
FILE0=OwIDE-app.zip
FILE1=Install-OwIDE.ps1
FILE2=Run-Install.cmd
[SourceFiles]
SourceFiles0=$payloadDir\
[SourceFiles0]
%FILE0%=
%FILE1%=
%FILE2%=
"@

Set-Content -LiteralPath $sedPath -Value $sed -Encoding ASCII

& iexpress.exe /N $sedPath

Write-Host "Created: $targetExe"
