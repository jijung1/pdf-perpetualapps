Add-Type -AssemblyName System.Drawing
$src = 'D:\VIBECODE\pdf-editor\design\play\raw'
$out = 'D:\VIBECODE\pdf-editor\design\play\screenshots'
New-Item -ItemType Directory -Force $out | Out-Null
function C($h) { [System.Drawing.ColorTranslator]::FromHtml($h) }
function RoundRect($x, $y, $w, $h, $r) {
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $p.AddArc($x, $y, 2*$r, 2*$r, 180, 90); $p.AddArc($x+$w-2*$r, $y, 2*$r, 2*$r, 270, 90)
  $p.AddArc($x+$w-2*$r, $y+$h-2*$r, 2*$r, 2*$r, 0, 90); $p.AddArc($x, $y+$h-2*$r, 2*$r, 2*$r, 90, 90)
  $p.CloseFigure(); return $p
}
$items = @(
  @('01-home', 'home', 'PDF editing without the subscription', 'No account. No cloud uploads. Works offline.'),
  @('02-whiteout', 'whiteout', "Remove text. Don't just cover it.", 'See exactly what will be deleted before you save.'),
  @('03-fill-sign', 'fill-sign', 'Fill in and sign forms', 'Type onto any PDF form and add your signature.'),
  @('04-merge', 'merge', 'Combine PDFs and photos', 'Documents, receipts and pictures in one PDF.'),
  @('05-organize', 'organize', 'Organize pages your way', 'Rotate, duplicate, extract or delete, with undo.'),
  @('06-split', 'split', 'Split into separate PDFs', 'By page ranges, every few pages, or one per page.'),
  @('07-privacy', 'about', 'Your documents stay yours', 'No internet permission. No accounts or tracking.')
)
$W = 1080; $H = 1920
$green = C '#13463B'; $deep = C '#0B2D26'; $paper = C '#F7F3E8'; $gold = C '#E3B04B'
$fh = New-Object System.Drawing.Font('Georgia', 66, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fs = New-Object System.Drawing.Font('Segoe UI', 38, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$fmt = New-Object System.Drawing.StringFormat; $fmt.Alignment = 'Center'
foreach ($it in $items) {
  $bmp = New-Object System.Drawing.Bitmap $W, $H, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'; $g.TextRenderingHint = 'AntiAliasGridFit'; $g.PixelOffsetMode = 'HighQuality'
  $g.Clear($green)
  # Headline (wraps to two lines if needed) and subline
  $size = $g.MeasureString($it[2], $fh, ($W - 140))
  $lineH = $fh.GetHeight($g); $off = [int]((2 * $lineH - $size.Height) / 2)
  $hr = New-Object System.Drawing.RectangleF 70, (70 + $off), ($W - 140), 170
  $g.DrawString($it[2], $fh, (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)), $hr, $fmt)
  $subY = 70 + $off + [math]::Ceiling($size.Height) + 14
  $fixedTop = 70 + [math]::Ceiling(2 * $lineH) + 14 + 90
  $g.DrawString($it[3], $fs, (New-Object System.Drawing.SolidBrush $gold), (New-Object System.Drawing.RectangleF 70, $subY, ($W - 140), 60), $fmt)
  # Screenshot without the status bar, scaled into a rounded frame
  $shot = [System.Drawing.Image]::FromFile("$src\$($it[1]).png")
  $crop = 84
  $sh = $shot.Height - $crop; $top = $fixedTop
  $th = $H - $top - 50; $tw = [int]($th * $shot.Width / $sh); $x = [int](($W - $tw) / 2)
  $g.FillPath((New-Object System.Drawing.SolidBrush $deep), (RoundRect ($x - 14) ($top - 14) ($tw + 28) ($th + 28) 46))
  $g.SetClip((RoundRect $x $top $tw $th 34))
  $g.DrawImage($shot, (New-Object System.Drawing.Rectangle $x, $top, $tw, $th), 0, $crop, $shot.Width, $sh, [System.Drawing.GraphicsUnit]::Pixel)
  $g.ResetClip(); $shot.Dispose(); $g.Dispose()
  $bmp.Save("$out\$($it[0]).png", [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
  "$($it[0]) headline-lines=$([math]::Round($size.Height / $fh.Height)) top=$top"
}
