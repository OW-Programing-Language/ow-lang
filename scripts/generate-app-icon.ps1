param(
  [string]$OutputDir = "assets"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $repoRoot $OutputDir
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$pngPath = Join-Path $outDir "owide-icon.png"
$icoPath = Join-Path $outDir "owide-icon.ico"

Add-Type -AssemblyName System.Drawing

$size = 256
$bmp = New-Object System.Drawing.Bitmap $size, $size
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

$rect = New-Object System.Drawing.Rectangle 0, 0, $size, $size
$bgBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, ([System.Drawing.Color]::FromArgb(255, 7, 10, 27)), ([System.Drawing.Color]::FromArgb(255, 39, 14, 77)), 35
$g.FillRectangle($bgBrush, $rect)

$glowBlue = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(70, 0, 153, 255))
$glowPurple = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(60, 151, 71, 255))
$g.FillEllipse($glowBlue, 28, 88, 108, 108)
$g.FillEllipse($glowPurple, 130, 92, 112, 128)

$penGradientRect = New-Object System.Drawing.Rectangle 40, 55, 180, 150
$penBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $penGradientRect, ([System.Drawing.Color]::FromArgb(255, 25, 193, 255)), ([System.Drawing.Color]::FromArgb(255, 150, 40, 255)), 0
$outerPen = New-Object System.Drawing.Pen $penBrush, 22
$outerPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$outerPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$g.DrawArc($outerPen, 48, 72, 92, 92, 38, 306)

$wBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush ([System.Drawing.Point]::new(120, 70)), ([System.Drawing.Point]::new(220, 165)), ([System.Drawing.Color]::FromArgb(255, 70, 96, 255)), ([System.Drawing.Color]::FromArgb(255, 150, 40, 255))
$wPoints = [System.Drawing.PointF[]]@(
  [System.Drawing.PointF]::new(122, 165),
  [System.Drawing.PointF]::new(154, 88),
  [System.Drawing.PointF]::new(182, 145),
  [System.Drawing.PointF]::new(224, 53),
  [System.Drawing.PointF]::new(198, 150),
  [System.Drawing.PointF]::new(160, 112)
)
$g.FillPolygon($wBrush, $wPoints)

$glyphBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush ([System.Drawing.Point]::new(54, 96)), ([System.Drawing.Point]::new(110, 140)), ([System.Drawing.Color]::FromArgb(255, 40, 185, 255)), ([System.Drawing.Color]::FromArgb(255, 76, 109, 255))
$glyphPen = New-Object System.Drawing.Pen $glyphBrush, 7
$glyphPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$glyphPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$g.DrawLine($glyphPen, 64, 117, 78, 103)
$g.DrawLine($glyphPen, 64, 117, 78, 131)
$g.DrawLine($glyphPen, 92, 130, 106, 103)
$g.DrawLine($glyphPen, 106, 103, 120, 117)
$g.DrawLine($glyphPen, 120, 117, 106, 131)
$slashPen = New-Object System.Drawing.Pen $glyphBrush, 7
$slashPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$slashPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$g.DrawLine($slashPen, 88, 136, 101, 92)

$highlightPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(95, 255, 255, 255)), 2
$g.DrawArc($highlightPen, 48, 72, 92, 92, 52, 85)
$g.DrawLine($highlightPen, 154, 88, 182, 145)

$bmp.Save($pngPath, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()

[byte[]]$pngBytes = [System.IO.File]::ReadAllBytes($pngPath)
$fs = [System.IO.File]::Open($icoPath, [System.IO.FileMode]::Create)
$bw = New-Object System.IO.BinaryWriter $fs

$bw.Write([UInt16]0)
$bw.Write([UInt16]1)
$bw.Write([UInt16]1)
$bw.Write([byte]0)
$bw.Write([byte]0)
$bw.Write([byte]0)
$bw.Write([byte]0)
$bw.Write([UInt16]1)
$bw.Write([UInt16]32)
$bw.Write([UInt32]$pngBytes.Length)
$bw.Write([UInt32]22)
$bw.Write($pngBytes)
$bw.Close()
$fs.Close()

Write-Host "Generated $pngPath"
Write-Host "Generated $icoPath"
