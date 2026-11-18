param(
    [Parameter(Mandatory=$true)][string]$Destination
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
New-Item -ItemType Directory -Force -Path $Destination | Out-Null

# ── Paleta canónica de Oto (misma que DesktopTheme.kt) ───────────────
$surface = [System.Drawing.ColorTranslator]::FromHtml('#121212')
$accent  = [System.Drawing.ColorTranslator]::FromHtml('#8FD3DB')   # DefaultAccent
$white   = [System.Drawing.Color]::White

$brushSurface = New-Object System.Drawing.SolidBrush($surface)
$brushAccent  = New-Object System.Drawing.SolidBrush($accent)
$brushWhite   = New-Object System.Drawing.SolidBrush($white)

# ── Tipografía (Segoe UI, igual que la app en Windows) ───────────────
$fontTitle  = New-Object System.Drawing.Font('Segoe UI', 42, [System.Drawing.FontStyle]::Bold)
$fontBanner = New-Object System.Drawing.Font('Segoe UI', 12, [System.Drawing.FontStyle]::Bold)

# ── Helper: nuevo canvas ──────────────────────────────────────────────
function New-Canvas([int]$W, [int]$H) {
    $bmp = New-Object System.Drawing.Bitmap($W, $H, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g   = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode   = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    return @($bmp, $g)
}

# Dibuja el icono de Oto: anillo exterior + punto interior
# Fiel al ic_launcher_foreground.xml y al AppIcon.kt
function Draw-OtoIcon($g, [single]$cx, [single]$cy, [single]$r, [System.Drawing.Color]$color) {
    $stroke   = [math]::Max(1.5, $r * 0.10)
    $pen      = New-Object System.Drawing.Pen($color, $stroke)
    $g.DrawEllipse($pen, $cx - $r, $cy - $r, $r * 2, $r * 2)
    $dotR     = $r * 0.30
    $dotBrush = New-Object System.Drawing.SolidBrush($color)
    $g.FillEllipse($dotBrush, $cx - $dotR, $cy - $dotR, $dotR * 2, $dotR * 2)
    $pen.Dispose(); $dotBrush.Dispose()
}

# Dibuja un rectángulo redondeado (simula las tarjetas de la app)
function Draw-RoundRect($g, [System.Drawing.Brush]$brush, [int]$x, [int]$y, [int]$w, [int]$h, [int]$radius) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($x, $y, $radius*2, $radius*2, 180, 90)
    $path.AddArc($x+$w-$radius*2, $y, $radius*2, $radius*2, 270, 90)
    $path.AddArc($x+$w-$radius*2, $y+$h-$radius*2, $radius*2, $radius*2, 0, 90)
    $path.AddArc($x, $y+$h-$radius*2, $radius*2, $radius*2, 90, 90)
    $path.CloseFigure()
    $g.FillPath($brush, $path)
    $path.Dispose()
}

# The built-in MSI dialog places its own text on the right.
$bmp, $g = New-Canvas 493 312
$g.Clear($white)
$g.FillRectangle($brushSurface, 0, 0, 164, 312)
Draw-OtoIcon $g 82 113 32 $accent
$nameSize = $g.MeasureString('Oto', $fontTitle)
$g.DrawString('Oto', $fontTitle, $brushWhite, (82 - $nameSize.Width / 2), 155)
$bmp.Save((Join-Path $Destination 'dialog.bmp'), [System.Drawing.Imaging.ImageFormat]::Bmp)
$bmp.Save((Join-Path $Destination 'dialog.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()

# Keep the banner's title region clear for Windows Installer.
$bmp, $g = New-Canvas 493 58
$g.Clear($white)
$g.FillRectangle($brushAccent, 0, 56, 493, 2)
Draw-OtoIcon $g 460 28 14 $surface
$bmp.Save((Join-Path $Destination 'banner.bmp'), [System.Drawing.Imaging.ImageFormat]::Bmp)
$bmp.Save((Join-Path $Destination 'banner.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()


$brushSurface.Dispose()
$brushAccent.Dispose()
$brushWhite.Dispose()
$fontTitle.Dispose()
$fontBanner.Dispose()
