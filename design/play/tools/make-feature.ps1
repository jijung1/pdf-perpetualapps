Add-Type -AssemblyName System.Drawing
$out = 'D:\VIBECODE\pdf-editor\design\play'
New-Item -ItemType Directory -Force $out | Out-Null
$W = 1024; $H = 500
$bmp = New-Object System.Drawing.Bitmap $W, $H, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'; $g.TextRenderingHint = 'AntiAliasGridFit'
function C($h) { [System.Drawing.ColorTranslator]::FromHtml($h) }
$green = C '#13463B'; $deep = C '#0D332B'; $paper = C '#F7F3E8'; $gold = C '#E3B04B'; $line = C '#C9C2B0'
$g.Clear($green)

function RoundRect($x, $y, $w, $h, $r) {
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $p.AddArc($x, $y, 2*$r, 2*$r, 180, 90); $p.AddArc($x+$w-2*$r, $y, 2*$r, 2*$r, 270, 90)
  $p.AddArc($x+$w-2*$r, $y+$h-2*$r, 2*$r, 2*$r, 0, 90); $p.AddArc($x, $y+$h-2*$r, 2*$r, 2*$r, 90, 90)
  $p.CloseFigure(); return $p
}
function Page($x, $y, $w, $h, $angle) {
  $st = $g.Save()
  $g.TranslateTransform($x + $w/2, $y + $h/2); $g.RotateTransform($angle); $g.TranslateTransform(-$w/2, -$h/2)
  $g.FillPath((New-Object System.Drawing.SolidBrush $deep), (RoundRect 8 10 $w $h 10))
  $g.FillPath((New-Object System.Drawing.SolidBrush $paper), (RoundRect 0 0 $w $h 10))
  return $st
}

# Back page: plain text lines
$st = Page 640 70 230 300 -8
$lb = New-Object System.Drawing.SolidBrush $line
foreach ($i in 0..8) { $g.FillRectangle($lb, 26, 40 + $i*26, (170 - ($i % 3)*30), 9) }
$g.Restore($st)

# Front page: text lines, a whiteout box with dashed outline, and a signature
$st = Page 740 120 230 300 5
foreach ($i in 0..5) { if ($i -ne 2) { $g.FillRectangle($lb, 26, 36 + $i*26, (178 - ($i % 2)*40), 9) } }
$pen = New-Object System.Drawing.Pen ($gold), 3; $pen.DashStyle = 'Dash'
$g.FillRectangle((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)), 20, 84, 190, 26)
$g.DrawRectangle($pen, 20, 84, 190, 26)
$sig = New-Object System.Drawing.Pen ((C '#1F3A8A')), 3.5; $sig.StartCap = 'Round'; $sig.EndCap = 'Round'
$pts = [System.Drawing.PointF[]]@(
  (New-Object System.Drawing.PointF 34, 236), (New-Object System.Drawing.PointF 52, 206), (New-Object System.Drawing.PointF 62, 240),
  (New-Object System.Drawing.PointF 80, 214), (New-Object System.Drawing.PointF 92, 238), (New-Object System.Drawing.PointF 116, 220),
  (New-Object System.Drawing.PointF 140, 230), (New-Object System.Drawing.PointF 170, 216))
$g.DrawCurve($sig, $pts, 0.5)
$g.DrawLine((New-Object System.Drawing.Pen $line, 2), 28, 252, 196, 252)
$g.Restore($st)

# Left: icon, name, tagline (kept inside the safe centre band)
$icon = [System.Drawing.Image]::FromFile('D:\VIBECODE\pdf-editor\website\assets\icon-512.png')
$g.SetClip((RoundRect 72 118 84 84 20)); $g.DrawImage($icon, 67, 113, 94, 94); $g.ResetClip(); $icon.Dispose()
$white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
$g.DrawString('PDF Editor', (New-Object System.Drawing.Font('Georgia', 64, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)), $white, 170, 116)
$g.DrawString('Perpetual Apps', (New-Object System.Drawing.Font('Segoe UI', 26, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)), (New-Object System.Drawing.SolidBrush $gold), 176, 196)
$tag = New-Object System.Drawing.Font('Segoe UI', 30, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$g.DrawString('Edit, sign, merge and split.', $tag, (New-Object System.Drawing.SolidBrush $paper), 74, 278)
$g.DrawString('Right on your phone, offline.', $tag, (New-Object System.Drawing.SolidBrush $paper), 74, 318)

$g.Dispose()
$bmp.Save("$out\feature-graphic.png", [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
(Get-Item "$out\feature-graphic.png").Length
