# Copiar como ..\env.ps1 (FUERA del repo) y ajustar rutas. Cargar con: . ..\env.ps1
$root = Split-Path $PSScriptRoot -Parent   # carpeta que contiene el repo
$env:JAVA_HOME = "$HOME\.jdks\temurin-21"                 # JDK 21
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:OTO_FFMPEG_DIR = "$root\tools\ffmpeg\bin"            # FFmpeg para empaquetar y probar audio
$env:GRADLE_USER_HOME = "$root\gradle"                    # caché de Gradle compartida (permite --offline)
# Android (opcional)
# $env:ANDROID_HOME = "$root\tools\android-sdk"
# $env:OTO_PHONE_KEYSTORE = "$root\tools\signing\oto-mobile-linux.keystore"
# WiX para el MSI (opcional)
# $env:WIX_TOOLSET_DIR = "C:\ruta\a\wix"
