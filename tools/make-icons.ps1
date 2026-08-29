$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$outDir = Join-Path $PSScriptRoot "..\src-tauri\icons"
New-Item -ItemType Directory -Force $outDir | Out-Null

$ground = [System.Drawing.Color]::FromArgb(255, 30, 34, 44)
$accent = [System.Drawing.Color]::FromArgb(255, 0, 255, 195)

function New-Icon([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.TextRenderingHint = 'AntiAliasGridFit'
    $g.Clear([System.Drawing.Color]::Transparent)

    $r = [Math]::Max(2, [int]($size * 0.18))
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $m = [Math]::Max(0, [int]($size * 0.02))
    $w = $size - ($m * 2)
    $path.AddArc($m, $m, $d, $d, 180, 90)
    $path.AddArc($m + $w - $d, $m, $d, $d, 270, 90)
    $path.AddArc($m + $w - $d, $m + $w - $d, $d, $d, 0, 90)
    $path.AddArc($m, $m + $w - $d, $d, $d, 90, 90)
    $path.CloseFigure()

    $brush = New-Object System.Drawing.SolidBrush $ground
    $g.FillPath($brush, $path)
    $pen = New-Object System.Drawing.Pen $accent, ([Math]::Max(1, $size / 32))
    $g.DrawPath($pen, $path)

    $font = New-Object System.Drawing.Font "Segoe UI", ($size * 0.52), ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $fmt = New-Object System.Drawing.StringFormat
    $fmt.Alignment = 'Center'
    $fmt.LineAlignment = 'Center'
    $textBrush = New-Object System.Drawing.SolidBrush $accent
    $rect = New-Object System.Drawing.RectangleF 0, 0, $size, $size
    $g.DrawString("Z", $font, $textBrush, $rect, $fmt)

    $g.Dispose(); $brush.Dispose(); $pen.Dispose(); $textBrush.Dispose(); $font.Dispose(); $path.Dispose()
    return $bmp
}

function Save-Png([System.Drawing.Bitmap]$bmp, [string]$path) {
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

foreach ($spec in @(@(32, "32x32.png"), @(128, "128x128.png"), @(256, "128x128@2x.png"), @(512, "icon.png"))) {
    $b = New-Icon $spec[0]
    Save-Png $b (Join-Path $outDir $spec[1])
    $b.Dispose()
}

$sizes = @(16, 32, 48, 64, 128, 256)
$blobs = @()
foreach ($s in $sizes) {
    $b = New-Icon $s
    $ms = New-Object System.IO.MemoryStream
    $b.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $blobs += , $ms.ToArray()
    $ms.Dispose(); $b.Dispose()
}

$icoPath = Join-Path $outDir "icon.ico"
$fs = [System.IO.File]::Create($icoPath)
$bw = New-Object System.IO.BinaryWriter $fs
$bw.Write([UInt16]0)
$bw.Write([UInt16]1)
$bw.Write([UInt16]$sizes.Count)

$offset = 6 + (16 * $sizes.Count)
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]
    $bw.Write([Byte]$(if ($s -ge 256) { 0 } else { $s }))
    $bw.Write([Byte]$(if ($s -ge 256) { 0 } else { $s }))
    $bw.Write([Byte]0)
    $bw.Write([Byte]0)
    $bw.Write([UInt16]1)
    $bw.Write([UInt16]32)
    $bw.Write([UInt32]$blobs[$i].Length)
    $bw.Write([UInt32]$offset)
    $offset += $blobs[$i].Length
}
foreach ($b in $blobs) { $bw.Write($b) }
$bw.Flush(); $bw.Dispose(); $fs.Dispose()

"icons -> $((Resolve-Path $outDir).Path)"
Get-ChildItem $outDir | ForEach-Object { "  {0,-18} {1,7} bytes" -f $_.Name, $_.Length }
