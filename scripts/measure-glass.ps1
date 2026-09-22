# Measure whether a "glass" panel is actually translucent.
#   glass works  -> card mean ~= background mean, card sd > 0 (ambient dot grid shows through)
#   black board  -> card mean clearly below background, sd ~ 0
#
# NOTE: keep this file pure ASCII. Windows PowerShell 5.1 reads .ps1 as ANSI unless the
# file carries a UTF-8 BOM, so any CJK in comments or labels silently breaks string
# parsing (it reports "missing string terminator" many lines later).
param([string]$Path)
Add-Type -AssemblyName System.Drawing
$fs = [IO.File]::OpenRead($Path)
$bmp = New-Object System.Drawing.Bitmap $fs

function Stats([double]$x0, [double]$y0, [double]$x1, [double]$y1) {
  $px = [int]($x0 * $bmp.Width); $py = [int]($y0 * $bmp.Height)
  $w  = [int](($x1 - $x0) * $bmp.Width); $h = [int](($y1 - $y0) * $bmp.Height)
  $rect = New-Object System.Drawing.Rectangle $px, $py, $w, $h
  $crop = $bmp.Clone($rect, $bmp.PixelFormat)
  $lum = @()
  for ($y = 0; $y -lt $crop.Height; $y += 3) {
    for ($x = 0; $x -lt $crop.Width; $x += 3) {
      $c = $crop.GetPixel($x, $y)
      $lum += (0.2126 * $c.R + 0.7152 * $c.G + 0.0722 * $c.B)
    }
  }
  $crop.Dispose()
  $m = ($lum | Measure-Object -Average).Average
  $var = (($lum | ForEach-Object { ($_ - $m) * ($_ - $m) } | Measure-Object -Sum).Sum) / $lum.Count
  "mean={0,5}  sd={1,5}" -f [math]::Round($m,1), [math]::Round([math]::Sqrt($var),1)
}

"image {0}x{1}  ({2})" -f $bmp.Width, $bmp.Height, (Split-Path $Path -Leaf)
"  card1  info card   : " + (Stats 0.12 0.165 0.88 0.235)
"  card2  how-to card : " + (Stats 0.12 0.400 0.60 0.500)
"  bg     empty middle: " + (Stats 0.12 0.600 0.88 0.700)
$bmp.Dispose(); $fs.Dispose()
