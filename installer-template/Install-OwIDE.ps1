param(
  [string]$InstallDir = "$env:LOCALAPPDATA\Programs\OwIDE"
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

function Select-InstallDir([string]$DefaultPath) {
  $form = New-Object System.Windows.Forms.Form
  $form.Text = "Choose Install Folder"
  $form.StartPosition = "CenterScreen"
  $form.Size = New-Object System.Drawing.Size(640, 165)
  $form.FormBorderStyle = [System.Windows.Forms.FormBorderStyle]::FixedDialog
  $form.MaximizeBox = $false
  $form.MinimizeBox = $false
  $form.TopMost = $true

  $label = New-Object System.Windows.Forms.Label
  $label.Text = "Install OwIDE to:"
  $label.Location = New-Object System.Drawing.Point(12, 15)
  $label.Size = New-Object System.Drawing.Size(120, 20)
  $form.Controls.Add($label)

  $pathBox = New-Object System.Windows.Forms.TextBox
  $pathBox.Location = New-Object System.Drawing.Point(15, 40)
  $pathBox.Size = New-Object System.Drawing.Size(500, 23)
  $pathBox.Text = $DefaultPath
  $form.Controls.Add($pathBox)

  $browse = New-Object System.Windows.Forms.Button
  $browse.Text = "Browse..."
  $browse.Location = New-Object System.Drawing.Point(525, 38)
  $browse.Size = New-Object System.Drawing.Size(85, 27)
  $browse.Add_Click({
    $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
    $dialog.Description = "Choose the folder where OwIDE should be installed"
    $dialog.ShowNewFolderButton = $true
    $dialog.SelectedPath = if ([string]::IsNullOrWhiteSpace($pathBox.Text)) { $env:LOCALAPPDATA } else { $pathBox.Text }
    if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
      $pathBox.Text = $dialog.SelectedPath
    }
  })
  $form.Controls.Add($browse)

  $install = New-Object System.Windows.Forms.Button
  $install.Text = "Install"
  $install.Location = New-Object System.Drawing.Point(430, 82)
  $install.Size = New-Object System.Drawing.Size(85, 30)
  $install.DialogResult = [System.Windows.Forms.DialogResult]::OK
  $install.Add_Click({
    if ([string]::IsNullOrWhiteSpace($pathBox.Text)) {
      [System.Windows.Forms.MessageBox]::Show(
        "Choose a folder first.",
        "OwIDE Setup",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Warning
      ) | Out-Null
      $form.DialogResult = [System.Windows.Forms.DialogResult]::None
    }
  })
  $form.Controls.Add($install)

  $cancel = New-Object System.Windows.Forms.Button
  $cancel.Text = "Cancel"
  $cancel.Location = New-Object System.Drawing.Point(525, 82)
  $cancel.Size = New-Object System.Drawing.Size(85, 30)
  $cancel.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
  $form.Controls.Add($cancel)

  $form.AcceptButton = $install
  $form.CancelButton = $cancel

  $result = $form.ShowDialog()
  if ($result -ne [System.Windows.Forms.DialogResult]::OK) {
    throw "Installation cancelled."
  }
  return $pathBox.Text.Trim()
}

$InstallDir = Select-InstallDir $InstallDir
$tempExtract = Join-Path ([System.IO.Path]::GetTempPath()) ("OwIDE-setup-" + [guid]::NewGuid().ToString("N"))
$payloadRoot = $null

if (Test-Path $InstallDir) {
  $overwrite = [System.Windows.Forms.MessageBox]::Show(
    "The folder already exists:`n$InstallDir`n`nReplace it?",
    "OwIDE Setup",
    [System.Windows.Forms.MessageBoxButtons]::YesNo,
    [System.Windows.Forms.MessageBoxIcon]::Question
  )
  if ($overwrite -ne [System.Windows.Forms.DialogResult]::Yes) {
    throw "Installation cancelled."
  }
  Remove-Item -LiteralPath $InstallDir -Recurse -Force
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $InstallDir) | Out-Null
New-Item -ItemType Directory -Force -Path $tempExtract | Out-Null

$sourceRoot = Join-Path $PSScriptRoot "OwIDE"
$sourceZip = Join-Path $PSScriptRoot "OwIDE-app.zip"

if (Test-Path $sourceRoot) {
  $payloadRoot = $sourceRoot
} elseif (Test-Path $sourceZip) {
  Expand-Archive -LiteralPath $sourceZip -DestinationPath $tempExtract -Force
  $payloadRoot = Join-Path $tempExtract "OwIDE"
} else {
  throw "Missing bundled app image folder or archive in $PSScriptRoot"
}

if (-not (Test-Path $payloadRoot)) {
  throw "Missing extracted OwIDE payload."
}

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Get-ChildItem -LiteralPath $payloadRoot -Force | ForEach-Object {
  Copy-Item -LiteralPath $_.FullName -Destination $InstallDir -Recurse -Force
}

$exePath = Join-Path $InstallDir "OwIDE.exe"
if (-not (Test-Path $exePath)) {
  throw "Missing executable after install: $exePath"
}

$shell = New-Object -ComObject WScript.Shell

$desktopShortcut = Join-Path ([Environment]::GetFolderPath("Desktop")) "OwIDE.lnk"
$desktop = $shell.CreateShortcut($desktopShortcut)
$desktop.TargetPath = $exePath
$desktop.WorkingDirectory = $InstallDir
$desktop.Save()

$startMenuDir = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\OwIDE"
New-Item -ItemType Directory -Force -Path $startMenuDir | Out-Null
$menuShortcut = Join-Path $startMenuDir "OwIDE.lnk"
$menu = $shell.CreateShortcut($menuShortcut)
$menu.TargetPath = $exePath
$menu.WorkingDirectory = $InstallDir
$menu.Save()

Write-Host "Installed OwIDE to: $InstallDir"
Write-Host "Desktop shortcut: $desktopShortcut"
Write-Host "Start menu shortcut: $menuShortcut"

Remove-Item -LiteralPath $tempExtract -Recurse -Force -ErrorAction SilentlyContinue
