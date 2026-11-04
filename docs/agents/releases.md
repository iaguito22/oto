# Releases e instalaciones

Índice en `AGENTS.md`. Estados de 03–05/11/2026; contrastar con `git log` y GitHub.

## Estado

- Publicada: **v2.0.0** (Latest), etiqueta → `2c64f3c`. Instalada en el PC (04/11) y en el móvil habitual.
- Después de la 2.0.0 hay commits locales sin push (`061d1d5` y la división de AGENTS.md; `origin/main` está en `f8665ef`) y un cambio de volumen sin commit. Lo instalado en el PC los incluye.
- MSI y lanzador sin firma Authenticode: no prometer ausencia de avisos SmartScreen ni tocar protecciones. Defender analizó el MSI de la 1.2.3 sin amenazas.

## Release 2.0.0 — 04/11/2026

- Fusionado en `main` (merge `2c64f3c` de `feat/pc-como-movil`); `feat/prueba-acoplamiento` avanzada por fast-forward; ramas subidas.
- https://github.com/iaguito22/oto/releases/tag/v2.0.0: MSI, DEB, Arch, APK pública (`io.github.iaguito22.oto`, firma 4043c8…), APK phone (`.debug`, firma 717d8f…, ambas versionCode 14) y SHA256SUMS. Copias y notas: `C:\Users\iago\Documents\Codex\2026-11-04\release-2.0.0`.
- Actions «Comprobar paquetes Linux» 41890416138 y «Comprobar paquete Arch» 41890422226, correctas.
- PC: instalada el 04/11 a las 19:50 (MSI código 0, JAR igual al compilado, enlace local responde; respaldo de `modular_windows.json` en `...\2026-11-04\backup-before-2.0.0`). Móvil: `install -r`, firma 717d8f…, no depurable, cola restaurada (58 canciones, índice 17, 177107 ms, pausada).

## Herramientas

- `gh`: `...\2026-11-03\quier\work\gh-cli\bin\gh.exe`, sin sesión; usar `GH_TOKEN` obtenido de `git credential fill` en bash (en PowerShell falla).
- WiX: `WIX_TOOLSET_DIR=...\quier\work\wix` (WiX 3.14, JDK 21). Linux: runtime y zstandard en `...\quier\work\linux-runtime` y `python-packages`; Python de `~\.cache\codex-runtimes\...\python.exe`. Empaquetador: `tools/package-linux-portable.py` (JRE Temurin Linux 21.0.13+9 y Skiko Linux 0.9.4 oficiales, hash del JRE verificado; excluye EXE/DLL/keystores). Detalles en `docs/PUBLICACION.md`.
- CI Ubuntu 22.04 instala el DEB y abre la app con Xvfb. El paquete Arch comparte payload y se valida su estructura: no afirmar prueba en una máquina Arch física.
- Instalación Windows: cerrar Oto con WM_CLOSE a «Oto · Sesión» (hwnd por proceso; FindWindow con «·» falla en PowerShell 5.1); una reparación MSI de la misma versión la rechaza SecureRepair, así que se desinstala y se instala (ver `AGENTS.md`).

## APKs y firmas

- Dos variantes: **phone** (`io.github.iaguito22.oto.debug`, firma personal 717d8f…) y **pública** (`io.github.iaguito22.oto`, firma 4043c8f48bbd633dec99cd9c2ce370e7a89a176a2d0fd69f398f3276f9749cb4, igual que las públicas 1.2.0 y 1.2.2). La phone NO actualiza la pública.
- La firma de publicación está en `../tools/signing/oto-firma` (configurada en `local.properties`): no compartir ni subir sus archivos.
- APK pública 1.2.3: `Oto-1.2.3-android.apk`, versionCode 13, SHA-256 `b5052dbc2fc10f9373d1bbca5dafd0df25b90a4843e332a83c0ba18e297663ff`, publicada en v1.2.3; no se instaló en ningún móvil.

## Reglas de publicación

- Iago autorizó subir todo, arreglar ramas y crear releases la última vez; no es un permiso permanente: push y release, solo si los pide.
- Rama canónica `main`; no forzar ramas. Si hay un borrador de release, actualizar su etiqueta al commit probado antes de publicar; no reescribir etiquetas ya publicadas.
- El clon sigue todas las ramas de origen (`remote.origin.fetch` ampliado).
- Capturas, perfiles, herramientas descargadas, keystores y logs permanecen FUERA del repo. `prompt.md` es una guía pública de continuidad; la petición privada antigua está en `...\quier\work\prompt-key-transfer-private.md`. No poner IPs locales en notas públicas.

## Notas de la 1.2.3 (histórico)

- Instalador MSI con panel y cabecera de Oto, por usuario, carpeta `Oto Desktop`; generador `desktopApp/package-windows.ps1`.
- El cierre involuntario al ocultar la última ventana se corrigió con una ventana invisible de sesión que mantiene bandeja y servidor DesktopAccess; regresión en `tools/check-desktop-session.py`.
- Un fallo de desacoplamiento por diferencias DPI de 1 px se corrigió en `nativeDockChange`. Formatos probados: Horizontal 351×108, Vertical 96×240.
- Validación final de la 1.2.3: core 56 tests (5 omitidos), desktop 71 (5 omitidos), sin fallos; Android correcto; Actions Ubuntu 41724981355 y Arch 41724548965.
