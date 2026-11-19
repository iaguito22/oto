# Copiar como ..\env.ps1 (FUERA del repo, junto a la carpeta oto) y ajustar rutas. Cargar con: . ..\env.ps1
# Una vez copiado, $PSScriptRoot es la carpeta que contiene el repo, tools\ y gradle\.
$root = $PSScriptRoot
$env:JAVA_HOME = Join-Path $root 'tools\jdk21\jdk-21.0.13+9'   # JDK 21 (Temurin)
# FFmpeg: carpeta bin con ffmpeg.exe y ffplay.exe. En PATH para probar audio y en OTO_FFMPEG_DIR para empaquetar.
$ffmpegBin = Get-ChildItem (Join-Path $root 'tools\ffmpeg') -Recurse -Filter ffplay.exe -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty DirectoryName
$env:OTO_FFMPEG_DIR = $ffmpegBin
$env:PATH = "$env:JAVA_HOME\bin;$ffmpegBin;$env:PATH"
$env:GRADLE_USER_HOME = Join-Path $root 'gradle'               # caché de Gradle compartida (permite --offline)
# Android (opcional; tools\check.ps1 lo busca solo en ..\tools\android-sdk)
# $env:ANDROID_HOME = Join-Path $root 'tools\android-sdk'
# Firma de la APK phone: ponerla solo al compilar :app:assemblePhone (sin ella sale firmada con otra clave y no instala encima)
# $env:OTO_PHONE_KEYSTORE = Join-Path $root 'tools\signing\oto-mobile-linux.keystore'
# WiX 3.14 para el MSI (opcional)
# $env:WIX_TOOLSET_DIR = 'C:\ruta\a\wix'
