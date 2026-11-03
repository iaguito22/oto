# Publicación de Oto

`main` es la rama canónica para Android y escritorio. Las ramas de funciones parten de ella y vuelven a ella conservando el historial. No mantener versiones distintas por sistema operativo.

## Windows

JDK 21, WiX 3.11/3.14 y FFmpeg. Configurar `WIX_TOOLSET_DIR` y `OTO_FFMPEG_DIR` cuando las herramientas no estén en PATH.

```powershell
./gradlew.bat :core:test :desktopApp:test --no-configuration-cache
./desktopApp/package-windows.ps1
```

El MSI se genera en `desktopApp/build/dist`, con panel y cabecera propios de Oto. La instalación es por usuario y conserva las preferencias fuera de la carpeta de la app. Incrementar la versión para permitir actualización normal. El UUID de actualización permanece estable.

El diseño no concede reputación de seguridad. Para distribución firmada hace falta una identidad Authenticode válida, consistente y con sellado de tiempo; no se incluye ninguna clave en este repositorio. No crear exclusiones de antivirus ni desactivar SmartScreen. Microsoft explica las diferencias entre firma, reputación y avisos en https://learn.microsoft.com/windows/apps/package-and-deploy/smartscreen-reputation.

## Ubuntu y Arch Linux

Se puede usar `desktopApp/package-linux.sh` desde Linux, o preparar paquetes desde los JAR compilados con `tools/package-linux-portable.py`. Este último requiere un JRE Linux x86_64 oficial, el JAR nativo `skiko-awt-runtime-linux-x64` de la misma versión y Python con `zstandard`. Debe comprobarse el hash del runtime descargado antes de empaquetar.

El empaquetador portable excluye los ejecutables y DLL de Windows; incorpora el runtime Linux, conserva permisos y enlaces simbólicos y genera `.deb` y `.pkg.tar.zst` nativos. FFmpeg es dependencia del paquete. No necesita un JRE instalado globalmente.

El workflow «Comprobar paquetes Linux» instala el DEB en Ubuntu 22.04, comprueba el manifiesto de Arch y abre la bienvenida y el reproductor bajo Xvfb. Ejecutarlo contra una release en borrador antes de publicarla.

## Android

La aplicación publicada tiene identificador `io.github.iaguito22.oto` y debe mantener su certificado de publicación para actualizar versiones anteriores. La variante optimizada `phone` usa `io.github.iaguito22.oto.debug`, para instalaciones personales existentes. Son paquetes e identidades distintos: etiquetar claramente los APK y no sustituir la firma del paquete público por la de `phone`.

La clave de `phone` puede indicarse con `OTO_PHONE_KEYSTORE`; el resto de credenciales se suministra localmente por variables de entorno. Ningún keystore, contraseña, cookie, perfil o log privado pertenece al repositorio ni a los assets de una release.

## Release

Publicar una etiqueta `v<versión>` que apunte al commit probado de `main`. Adjuntar MSI de Windows, DEB de Ubuntu, paquete Arch y APK identificado; incluir `SHA256SUMS.txt` y explicar cualquier limitación de firma. Las fuentes incluyen la bienvenida; las grabaciones originales y los perfiles de prueba quedan fuera.
