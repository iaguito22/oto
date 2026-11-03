param([Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
function New-Art([int]$Width,[int]$Height) {
    $image=New-Object System.Drawing.Bitmap($Width,$Height,[System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $graphics=[System.Drawing.Graphics]::FromImage($image)
    $graphics.SmoothingMode=[System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint=[System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#F4F7F3'))
    return @($image,$graphics)
}
function Draw-Oto($Graphics,[single]$X,[single]$Y,[single]$Size) {
    $pen=New-Object System.Drawing.Pen([System.Drawing.Color]::White,($Size*.045))
    $Graphics.DrawEllipse($pen,$X,$Y,$Size,$Size)
    $Graphics.FillEllipse([System.Drawing.Brushes]::White,($X+$Size*.18),($Y+$Size*.18),($Size*.31),($Size*.31))
    $pen.Dispose()
}
$dark=New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#14201C'))
$mint=New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#8FD3DB'))
$small=New-Object System.Drawing.Font('Segoe UI',10)
$large=New-Object System.Drawing.Font('Segoe UI',31,[System.Drawing.FontStyle]::Bold)
try {
    $image,$graphics=New-Art 493 312
    $graphics.FillRectangle($dark,0,0,164,312)
    Draw-Oto $graphics 45 38 70
    $graphics.DrawString('Oto',$large,[System.Drawing.Brushes]::White,37,119)
    $graphics.DrawString("Tu música,`na tu manera.",$small,$mint,25,181)
    $line=New-Object System.Drawing.Pen([System.Drawing.ColorTranslator]::FromHtml('#42665D'),1)
    $graphics.DrawRectangle($line,24,252,32,39)
    $graphics.DrawRectangle($line,61,242,43,49)
    $graphics.DrawRectangle($line,109,261,28,30)
    $line.Dispose()
    $image.Save((Join-Path $Destination 'dialog.bmp'),[System.Drawing.Imaging.ImageFormat]::Bmp)
    $image.Save((Join-Path $Destination 'dialog.png'),[System.Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose();$image.Dispose()
    $image,$graphics=New-Art 493 58
    $graphics.FillRectangle($dark,414,0,79,58)
    Draw-Oto $graphics 437 13 31
    $image.Save((Join-Path $Destination 'banner.bmp'),[System.Drawing.Imaging.ImageFormat]::Bmp)
    $image.Save((Join-Path $Destination 'banner.png'),[System.Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose();$image.Dispose()
} finally { $dark.Dispose();$mint.Dispose();$small.Dispose();$large.Dispose() }
