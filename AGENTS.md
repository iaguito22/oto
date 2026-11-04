# Oto — continuidad después de /clear

Actualizado: 05/11/2026. Actualizar tras cambios importantes. Distinguir implementado, probado, instalado y publicado.

## Estado actual

- Repo: https://github.com/iaguito22/oto, rama `main`. Clon: `C:\Users\iago\Documents\Codex\2026-11-02\files-pasted-by-the-user-encargo\work\oto`.
- Release publicada: 2.0.0 (v2.0.0). Instalada en el PC y en el móvil habitual.
- Sin push (`origin/main` está en `f8665ef`): `061d1d5` (ventanas, letra, búsqueda) y el commit que divide este archivo. Instalado en el PC: ese código más la curva de volumen.
- **Sin commit:** curva de volumen `volumeGain()` = volumen^1,5 en `DesktopPlayerConnection.kt`. Pendiente de que Iago la pruebe (si <10 % sigue muy bajo, probar 1,25).
- Sin resolver: congelación breve antes de la animación al abrir ventanas. Medir antes de tocar (`docs/agents/rendimiento.md`).
- Sin confirmar por Iago: clics en el historial de búsqueda, y el crash «Key Library is missing» (endurecido, no reproducido).
- Empezar con `git status --short --branch` y conservar cambios ajenos.

## Cómo trabajar con Iago

- Español para respuestas, interfaz y commits; frases claras y breves.
- Ejecutar lo autorizado sin pedir permisos rutinarios. Commit solo si lo pide («haz commit»); push solo si lo pide.
- Respetar su feedback visual. Quiere animaciones presentes, con dirección y rebote, pero fluidas.
- Navegador predeterminado: Zen. Nunca pedir cookies en el chat.
- Capturas permitidas; cerrar/resetear la sesión de captura al acabar (borde amarillo).
- Leer la habilidad computer-use antes de automatizar UI. No automatizar autenticación ni avisos de seguridad.
- Cerrar Oto con «Salir de Oto» o WM_CLOSE a la ventana «Oto · Sesión» (buscar el hwnd por proceso; FindWindow con «·» falla en PowerShell 5.1). Las X dejan el proceso en segundo plano. No terminarlo a la fuerza ni tocar DataStore abierto.
- No declarar fluidez por screenshots o compilación. Tras dos hipótesis fallidas, instrumentar antes de seguir.

## Compilación en ESTE Windows

Desde la raíz del repositorio, PowerShell:

```powershell
. ..\env.ps1
.\gradlew.bat :core:test :desktopApp:test --offline --no-configuration-cache
.\gradlew.bat :desktopApp:packageMsi --offline --no-configuration-cache
```

- `../env.ps1` configura JDK 21, FFmpeg y `GRADLE_USER_HOME=../gradle`. Sin ello parece que faltan Java/dependencias.
- WiX/empaquetado falla con configuration cache: usar el indicador anterior. Generador: `desktopApp/package-windows.ps1`; MSI: `desktopApp/build/dist/Oto-2.0.0.msi`. Ver `docs/PUBLICACION.md`.
- Instalación: `%LOCALAPPDATA%\Oto Desktop\Oto.exe`. Misma versión: desinstalar el paquete (buscar el UninstallString/producto actual en HKCU y HKLM, no asumir GUID) e instalar con `msiexec /qn /norestart`; comprobar códigos 0 y que el JAR instalado coincide con el compilado.
- `Start-Process -WindowStyle Hidden`. No matar todos los java.exe (daemons Gradle). Dos Oto.exe son lanzador y JVM.

## Arquitectura y archivos útiles

- `:core`: preferencias, cuenta, listas, letras, DJ y enlace compartido (afecta a Android). `:desktopApp`: Compose Desktop y audio. `:app`: Android.
- Entrada: `desktopApp/.../desktop/Main.kt`, `ModularDesktop.kt`.
- Ventanas: `DockLayout.kt` (geometría pura), `DockWindows.kt` (AWT/gestos), `DockPersistence.kt`; pruebas `DockLayoutTest.kt`.
- UI activa: `ui/LibrarySpace.kt`, `SmartSpace.kt`, `SettingsSpace.kt`, `DownloadsSpace.kt`, `PlayerSurface.kt`, `MobileLyrics.kt`. `DesktopStage.kt` y `DesktopMenuSheet.kt` tienen código antiguo: seguir las llamadas reales.
- Audio/cola/fundido/volumen: `playback/DesktopPlayerConnection.kt`, `DesktopAudioEngine.kt`, `DesktopBeat.kt`.
- Cuenta: `core/.../account/Account.kt`, `Cloud.kt`. Enlace móvil: `DeviceConnection.kt`, `remote/LibraryReplica.kt`, `LinkedPlayback.kt`.

## Pendientes reales

1. Vincular el móvil habitual con el PC (Dispositivos/QR) y comprobar la sincronización con los perfiles reales. Probado solo con datos aislados.
2. Medir ahorro de RAM on/off con tres ventanas y audio.
3. Duplicados de YouTube: quedan grupos sin auditar (`../playlist-audit.json`). No borrar por nombre solo. El anti-duplicados de Cloud no garantiza resolución total.
4. MSI y lanzador sin firma Authenticode: no prometer ausencia de avisos SmartScreen.

## Dónde mirar según la tarea (leer solo lo necesario)

- `docs/agents/rendimiento.md`: ahorro de RAM, rueda suave, mediciones, apertura de ventanas.
- `docs/agents/escritorio-ui.md`: decisiones visuales, MP3, bienvenida, valores por defecto y tamaños.
- `docs/agents/datos-sync.md`: perfiles, datos privados, Me gusta de Oto.
- `docs/agents/android-movil.md`: teléfono, firma, pruebas, cola remota, modo Connect.
- `docs/agents/releases.md`: releases, instalaciones, APK pública.
- Históricos: `docs/AGENTS_HISTORICO_20261102.md`, `docs/ACOPLAMIENTO.md`, `ESTADO_MODULAR.md`, `docs/VALIDACION_WINDOWS.md`. Contrastar fechas con el código.
