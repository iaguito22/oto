---
name: release-oto
description: Publicar una versión de Oto (Windows, Linux, Android) en GitHub Releases. Usar solo cuando Iago pida «release», «publica la X.Y.Z» o «sube los paquetes».
---

# Release de Oto

Solo con petición explícita de Iago. Una autorización anterior no vale para esta.

## 1. Preparar

1. `main` limpio y actualizado (`git status -sb`). La release sale de un commit de `main`.
2. Subir la versión: `packageVersion` en `desktopApp/build.gradle.kts`; `versionName` y `versionCode` en `app/build.gradle.kts`. Son la única fuente del número que muestra Ajustes («Oto X.Y.Z · GPL-3.0»): escritorio vía `jpackage.app-version`, Android vía `BuildConfig.VERSION_NAME`. No escribir la versión a mano en la UI.
3. Todos los tests: `tools/check.sh --all` (o `tools\check.ps1 -All`). Anotar los recuentos (pasan / omitidos / fallos) para las notas.

## 2. Paquetes

- Windows: `desktopApp/package-windows.ps1` → `Oto-<v>-Setup.exe` y MSI en `desktopApp/build/dist`. `--no-configuration-cache`.
- Linux: `desktopApp/package-linux.sh` o `tools/package-linux-portable.py` → `.deb` y `.pkg.tar.zst` (verificar el hash del JRE).
- Android pública: `io.github.iaguito22.oto`, firma 4043c8…. **No** subir la APK `phone` salvo petición.
- `SHA256SUMS.txt` de todo lo adjunto.
- **Comprobar la versión en Ajustes** del paquete generado (no desde `gradlew run`, que muestra solo «Oto»): el instalado de Windows o Linux y la APK deben decir la versión nueva. Si no, falta subir uno de los números del paso 2.

Detalles y herramientas: `docs/PUBLICACION.md` y `docs/agents/releases.md`.

## 3. Borrador y CI

1. `gh release create v<v> --draft --target <commit>` con los archivos y notas. Incluir el bloque de `docs/SMARTSCREEN.md`.
2. Lanzar «Comprobar paquetes Linux» y «Comprobar paquete Arch» con `tag=v<v>` y esperar a que pasen.
3. Publicar el borrador solo si pasan.

## Reglas

- No reescribir etiquetas publicadas. Si hay que sustituir archivos (`--clobber`), decirlo en las notas.
- Nada privado en la release: keystores, perfiles, logs, IPs locales.
- Instalar en el PC o el móvil es otro paso (`instalar-pc`, `probar-movil`), solo si Iago lo pide.
- Al acabar, añadir una entrada corta en `docs/agents/releases.md`: commit, archivos, hashes, CI.
