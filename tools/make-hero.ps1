$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$W = 1600
$H = 900
$out = Join-Path $PSScriptRoot "..\ui\hero.png"

$bmp = New-Object System.Drawing.Bitmap $W, $H
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'

function C([int]$a, [int]$r, [int]$gr, [int]$b) { [System.Drawing.Color]::FromArgb($a, $r, $gr, $b) }

$horizon = [int]($H * 0.62)

$rect = New-Object System.Drawing.Rectangle 0, 0, $W, $H
$grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, (C 255 9 9 11), (C 255 20 20 23), 90
$blend = New-Object System.Drawing.Drawing2D.ColorBlend 5
$blend.Colors = @((C 255 9 9 11), (C 255 17 17 20), (C 255 32 32 37), (C 255 20 20 24), (C 255 11 11 13))
$blend.Positions = @(0.0, 0.34, 0.62, 0.70, 1.0)
$grad.InterpolationColors = $blend
$g.FillRectangle($grad, $rect)

$heights = @(38, 96, 54, 130, 72, 44, 168, 88, 60, 112, 46, 140, 78, 52, 104, 66, 150, 84, 40, 118, 58, 92, 48, 134)
$blockX = -40
$rand = 0
foreach ($blockH in $heights) {
    $rand = ($rand * 37 + 91) % 53
    $blockW = 44 + $rand

    $br = New-Object System.Drawing.SolidBrush (C 255 8 8 10)
    $g.FillRectangle($br, $blockX, $horizon - $blockH, $blockW, $H - $horizon + $blockH)
    $br.Dispose()

    if ($blockH -gt 60) {
        $wb = New-Object System.Drawing.SolidBrush (C 46 255 255 255)
        $g.FillRectangle($wb, $blockX + [int]($blockW * 0.28), $horizon - $blockH + 16, 5, 7)
        $g.FillRectangle($wb, $blockX + [int]($blockW * 0.62), $horizon - $blockH + 34, 5, 7)
        $wb.Dispose()

        $wb2 = New-Object System.Drawing.SolidBrush (C 26 255 255 255)
        $g.FillRectangle($wb2, $blockX + [int]($blockW * 0.45), $horizon - $blockH + 52, 5, 7)
        $wb2.Dispose()
    }

    $blockX += $blockW + 6
    if ($blockX -gt $W) { break }
}

$cx = $W / 2.0
$cy = $H / 2.0
$maxD = [Math]::Sqrt($cx * $cx + $cy * $cy)
for ($i = 0; $i -lt 200; $i++) {
    $t = $i / 200.0
    $inset = [int]($t * $maxD * 0.9)
    $alpha = [int]($t * $t * 3)
    if ($alpha -le 0) { continue }
    $pen = New-Object System.Drawing.Pen (C $alpha 0 0 0), 3
    $g.DrawEllipse($pen, -$inset, -$inset + 100, $W + ($inset * 2), $H + ($inset * 2) - 200)
    $pen.Dispose()
}

$g.Dispose(); $grad.Dispose()
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

$item = Get-Item $out
"hero -> $($item.FullName)  ($([int]($item.Length / 1024)) KB, ${W}x${H})"
