Add-Type -AssemblyName System.Drawing
$out = $args[0]
function C($h) { [System.Drawing.ColorTranslator]::FromHtml($h) }
function F($n, $s, $st = 'Regular') { New-Object System.Drawing.Font($n, $s, [System.Drawing.FontStyle]::$st, [System.Drawing.GraphicsUnit]::Pixel) }

# Receipt photo: paper receipt on a wooden-toned table, slightly rotated
$b = New-Object System.Drawing.Bitmap 1200, 1600
$g = [System.Drawing.Graphics]::FromImage($b); $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAlias'
$g.Clear((C '#8B6A4E'))
$rnd = New-Object System.Random 7
for ($i = 0; $i -lt 90; $i++) { $y = $rnd.Next(0, 1600); $g.DrawLine((New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(40, 60, 40, 25)), ($rnd.Next(1, 4))), 0, $y, 1200, $y + $rnd.Next(-30, 30)) }
$g.TranslateTransform(600, 800); $g.RotateTransform(-4); $g.TranslateTransform(-330, -680)
$g.FillRectangle((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(70, 0, 0, 0))), 14, 18, 660, 1360)
$g.FillRectangle((New-Object System.Drawing.SolidBrush (C '#FBFAF5')), 0, 0, 660, 1360)
$ink = New-Object System.Drawing.SolidBrush (C '#2A2A2A')
$mono = F 'Consolas' 34; $big = F 'Consolas' 44 'Bold'
$lines = @(@('MARKET HALL', $big), @('Fairview, OR', $mono), @('', $mono), @('04 NOV 2026   10:42', $mono), @('--------------------------', $mono),
  @('Tomatoes 1.2kg     4.80', $mono), @('Bread              3.95', $mono), @('Coffee beans      12.50', $mono), @('Milk 2L            2.89', $mono),
  @('Apples 1kg         3.60', $mono), @('Seed packets x4    9.00', $mono), @('--------------------------', $mono), @('TOTAL             36.74', $big),
  @('', $mono), @('CARD           **** 1120', $mono), @('', $mono), @('THANK YOU!', $big))
$y = 50; foreach ($l in $lines) { $g.DrawString($l[0], $l[1], $ink, 40, $y); $y += ($l[1].Size + 22) }
$g.Dispose(); $b.Save("$out\Receipt.jpg", [System.Drawing.Imaging.ImageFormat]::Jpeg); $b.Dispose()

# Whiteboard photo (landscape): plan sketch with handwriting-like marker text
$b = New-Object System.Drawing.Bitmap 1600, 1200
$g = [System.Drawing.Graphics]::FromImage($b); $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAlias'
$g.Clear((C '#9AA3A8'))
$g.FillRectangle((New-Object System.Drawing.SolidBrush (C '#F2F4F3')), 80, 70, 1440, 1060)
$g.DrawRectangle((New-Object System.Drawing.Pen (C '#B9BEC1'), 14), 80, 70, 1440, 1060)
$blue = New-Object System.Drawing.SolidBrush (C '#1E4DB7'); $red = New-Object System.Drawing.SolidBrush (C '#C2362B'); $green = New-Object System.Drawing.SolidBrush (C '#1F7A4D')
$hand = 'Segoe Print'; if (-not ((New-Object System.Drawing.Text.InstalledFontCollection).Families.Name -contains $hand)) { $hand = 'Comic Sans MS' }
$g.DrawString('Garden layout - phase 1', (F $hand 64 'Bold'), $blue, 150, 120)
$pen = New-Object System.Drawing.Pen (C '#1F7A4D'), 7
for ($r = 0; $r -lt 2; $r++) { for ($c = 0; $c -lt 4; $c++) { $g.DrawRectangle($pen, 170 + $c * 230, 330 + $r * 220, 180, 150); $g.DrawString("Bed $($r*4+$c+1)", (F $hand 34), $green, 195 + $c * 230, 380 + $r * 220) } }
$g.DrawRectangle((New-Object System.Drawing.Pen (C '#C2362B'), 7), 1150, 330, 260, 370); $g.DrawString('Shed', (F $hand 44), $red, 1210, 470)
$g.DrawString('Rain barrels by the shed', (F $hand 40), $red, 170, 840)
$g.DrawString('Start: March 15', (F $hand 40), $blue, 170, 920)
$g.Dispose(); $b.Save("$out\Whiteboard.jpg", [System.Drawing.Imaging.ImageFormat]::Jpeg); $b.Dispose()
'ok'
