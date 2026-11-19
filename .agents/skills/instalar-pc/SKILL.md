---
name: instalar-pc
description: Instalar, abrir y cerrar Oto en el PC de Iago (Windows o Linux) y comprobar que lo instalado es lo compilado. Usar con «instálalo», «ábrelo», «no veo los cambios».
---

# Instalar y probar Oto en el PC

## Antes

- Preguntar o comprobar si Oto está abierto con el perfil real. Si Iago lo está usando, no cerrarlo sin avisar.
- Para probar sin tocar el perfil real, usar el portable de `desktopApp/build/compose/binaries/...` con un perfil aislado.

## Cerrar Oto bien

- «Salir de Oto», o WM_CLOSE a la ventana «Oto · Sesión» buscando el hwnd por proceso (`FindWindow` con «·» falla en PowerShell 5.1).
- Nunca `Stop-Process`/`kill` a todos los `java.exe`: también son los daemons de Gradle. Dos `Oto.exe` son el lanzador y la JVM.

## Windows (MSI)

1. Versión nueva: `msiexec /i <msi> /qn /norestart` y comprobar código 0.
2. Misma versión: SecureRepair rechaza la reparación. Desinstalar primero (buscar el producto actual en HKCU y HKLM, no suponer el GUID) e instalar.
3. Comprobar una sola entrada «Oto <v>» y que el SHA-256 del JAR de `%LOCALAPPDATA%\Oto Desktop` coincide con el compilado.
4. Abrir con `Start-Process -WindowStyle Hidden` y revisar `errores.log` del perfil.

## Linux

- Paquete: `sudo pacman -U` / `sudo dpkg -i`. Portable: ejecutar desde `build/`.
- Perfil: `$XDG_DATA_HOME/oto` (por defecto `~/.local/share/oto`).

## Informar

Decir qué se instaló (versión, hash), dónde, si se abrió y qué no se comprobó (p. ej., «sin oír», «sin gestos con ratón físico»).
