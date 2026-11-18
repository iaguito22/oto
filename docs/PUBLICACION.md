# Publicación de Oto

`main` es la rama canónica para Android y escritorio. Las ramas de funciones parten de ella y vuelven a ella conservando el historial. No mantener versiones distintas por sistema operativo.

## Windows

JDK 21, WiX 3.11/3.14, .NET Framework 4.8 y FFmpeg. Configurar `WIX_TOOLSET_DIR` y `OTO_FFMPEG_DIR` cuando las herramientas no estén en PATH.

```powershell
./gradlew.bat :core:test :desktopApp:test --no-configuration-cache
./desktopApp/package-windows.ps1
```

El instalador para Windows es `Oto-<versión>-Setup.exe`. Incluye el MSI y muestra la instalación, el progreso y el resultado en una sola ventana. Los dos archivos se generan en `desktopApp/build/dist`. El MSI también está disponible para instalación administrada. La instalación es por usuario y conserva las preferencias fuera de la carpeta de la app. Incrementar la versión para permitir la actualización y mantener el UUID de actualización del MSI.

Para revisar únicamente el diseño, generar una vista previa sin carga instalable:

```powershell
./desktopApp/packaging/windows/build-setup.ps1 -Version 2.2.0 -Destination "$env:TEMP/oto-setup-preview" -Preview
```

### SmartScreen («Windows protegió tu PC»)

Al abrir el instalador sin firma Authenticode, Windows puede mostrar un aviso de reputación.

**Cómo saltárselo** (el usuario final):
1. Aparece la pantalla «Windows protegió tu PC» con el botón **Ejecutar de todas formas** oculto.
2. Hacer clic en **Más información** (enlace gris, parte superior).
3. El botón **Ejecutar de todas formas** queda visible → hacer clic.

**Por qué aparece:**
SmartScreen requiere reputación acumulada (descargas + tiempo) o firma con un certificado Authenticode de confianza emitida por una CA comercial (DigiCert, Sectigo…). Sin firma, cada nueva versión empieza desde cero.

**Para que desaparezca** el ejecutable tiene que acumular reputación con descargas y tiempo. Firmarlo con un certificado Authenticode ayuda a que esa reputación se conserve entre versiones; desde 2024 ni siquiera un certificado EV la da al instante. No incluir claves ni contraseñas en el repositorio.

## Ubuntu y Arch Linux

Se puede usar `desktopApp/package-linux.sh` desde Linux, o preparar paquetes desde los JAR compilados con `tools/package-linux-portable.py`. Este último requiere un JRE Linux x86_64 oficial, el JAR nativo `skiko-awt-runtime-linux-x64` de la misma versión y Python con `zstandard`. Debe comprobarse el hash del runtime descargado antes de empaquetar.

El empaquetador portable excluye los ejecutables y DLL de Windows; incorpora el runtime Linux, conserva permisos y enlaces simbólicos y genera `.deb` y `.pkg.tar.zst` nativos. FFmpeg es dependencia del paquete. No necesita un JRE instalado globalmente.

El workflow «Comprobar paquetes Linux» instala el DEB en Ubuntu 22.04, comprueba el manifiesto de Arch y abre la bienvenida y el reproductor bajo Xvfb. Ejecutarlo contra una release en borrador antes de publicarla.

## Android

La aplicación publicada tiene identificador `io.github.iaguito22.oto` y debe mantener su certificado de publicación para actualizar versiones anteriores. La variante optimizada `phone` usa `io.github.iaguito22.oto.debug`, para instalaciones personales existentes. Son paquetes e identidades distintos: etiquetar claramente los APK y no sustituir la firma del paquete público por la de `phone`.

La clave de `phone` puede indicarse con `OTO_PHONE_KEYSTORE`; el resto de credenciales se suministra localmente por variables de entorno. Ningún keystore, contraseña, cookie, perfil o log privado pertenece al repositorio ni a los assets de una release.

## Release

Publicar una etiqueta `v<versión>` que apunte al commit probado de `main`. Adjuntar el instalador EXE de Windows, el MSI, el DEB de Ubuntu, el paquete Arch y el APK identificado. Incluir `SHA256SUMS.txt` y explicar cualquier limitación de firma. Las fuentes incluyen la bienvenida. Las grabaciones originales y los perfiles de prueba quedan fuera.
