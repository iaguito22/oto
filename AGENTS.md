# Oto

Reproductor de música (YouTube Music) para Android, Windows y Linux. Un solo repositorio y una sola rama canónica: `main`. No mantener versiones distintas por sistema operativo.

- `:core` (JVM puro): preferencias, cuenta, listas, letras, DJ y enlace entre dispositivos. **Cambiarlo afecta a Android y al escritorio.**
- `:desktopApp`: Compose Desktop y motor de audio (FFmpeg → PCM).
- `:app`: Android (Media3). Variantes `phone` (personal) y pública.
- `:innertube`: cliente de YouTube.

## Lo que no se negocia

1. **Estados honestos.** Distinguir siempre *implementado*, *probado* (tests), *instalado* y *publicado*. Compilar no es probar; un test no es oír. El agente no oye audio: los cambios de sonido quedan «sin oír» hasta que Iago lo confirme.
2. **Fluidez real.** Animaciones presentes, con dirección y rebote, pero fluidas. No declarar fluidez por capturas ni compilación. Tras dos hipótesis fallidas, instrumentar (ver `docs/agents/rendimiento.md`).
3. **Datos privados fuera del repo.** Cookies, DataStore, perfiles, keystores, contraseñas, QR, logs, capturas, informes y audio con derechos. Trabajo temporal en `scratchpad/` (ignorado).
4. **No romper la instalación habitual.** Ni la app del PC con el perfil real ni la `phone` del Nothing: nunca desinstalarlas, sustituir sus datos ni firmarlas con otra clave.

## Formas de hacerse daño

- **Matar procesos a ciegas.** No matar todos los `java.exe` (daemons de Gradle) ni Oto a la fuerza. Cerrar Oto con «Salir de Oto» o WM_CLOSE a la ventana «Oto · Sesión» (hwnd por proceso; `FindWindow` con «·» falla en PowerShell 5.1). Las X lo dejan en bandeja.
- **Tocar el perfil real.** `%APPDATA%\oto` (Linux: `~/.config/oto`, `~/.local/share/oto`) es el de Iago y puede estar abierto. Probar con perfiles aislados; no escribir en un DataStore abierto.
- **Probar contra el móvil real.** Nada de `connectedAndroidTest` ni builds `debug` contra él: usar la APK `.linktest` (`docs/agents/android-movil.md`).
- **Confundir firmas.** `phone` (`.debug`, firma 717d8f…) y pública (`io.github.iaguito22.oto`, firma 4043c8…) son apps distintas. Verificar con apksigner antes de instalar.

## Verificar

```sh
tools/check.sh            # Linux: tests de los módulos que tocaste (contra main)
tools/check.sh --all      # todos los módulos
```

```powershell
. ..\env.ps1              # obligatorio en Windows: JDK 21, FFmpeg y GRADLE_USER_HOME
tools\check.ps1           # ídem; plantilla de env en tools/env.example.ps1
```

- Tocar `:core` ejecuta también los tests de Android y escritorio.
- Antes de decir «hecho», ejecutar `check` y decir qué se probó y qué no.
- CI (`.github/workflows/ci.yml`) pasa todos los tests en cada push y PR.
- Bug de lógica (DJ, letras, sincronización, ventanas): primero un test que falle, luego el arreglo. Bug visual: captura antes/después.
- Empaquetar en Windows: `--no-configuration-cache` (WiX falla con la caché).

## Cómo trabajar con Iago

- Español en respuestas, interfaz y commits; frases claras y breves.
- Ejecutar lo autorizado sin pedir permisos rutinarios. Commit solo si lo pide; push, release e instalación solo si los pide (una autorización no es permanente).
- Una rama por tarea (`feat/…`, `fix/…`) y PR a `main` con la plantilla. Conventional commits: `fix(desktop): …`, `feat(dj): …`.
- Pendientes y bugs en issues de GitHub, no en este archivo. El commit los cierra con `fixes #N`.
- Respetar su feedback visual. Navegador: Zen. Nunca pedir cookies en el chat.
- Capturas permitidas; cerrar la sesión de captura al acabar.

## Hit every surface

Antes de dar por terminado, repasar y decir cuáles aplicaban:

- **Plataformas:** Android `phone` y pública, Windows, Linux (DEB/Arch).
- **Modos de escritorio:** «Una ventana» (`SingleDesktop.kt`) y modular (`ModularDesktop.kt`), incluido el mini.
- **Enlace:** PC↔móvil (espejo, cola remota, Me gusta compartidos).
- **Entradas:** Ajustes, bandeja, atajo global, miniatura de la barra de tareas.
- **Valores por defecto y primer arranque** (`docs/agents/escritorio-ui.md`).
- **Vuelta atrás:** si añades una forma de entrar, también la de salir.
- **Docs:** si el cambio vuelve falsa una regla de `docs/agents/`, reescribirla.

## Dónde está el código

- Entrada escritorio: `desktopApp/.../desktop/Main.kt` (elige `SingleDesktop` o `ModularDesktop` según `shell_mode`).
- Ventanas modulares: `DockLayout.kt` (geometría pura), `DockWindows.kt`, `DockPersistence.kt`.
- UI: `ui/LibrarySpace.kt`, `SmartSpace.kt`, `SettingsSpace.kt`, `DownloadsSpace.kt`, `PlayerSurface.kt`, `MobileLyrics.kt`. `DesktopStage.kt` y `DesktopMenuSheet.kt` tienen código antiguo: seguir las llamadas reales.
- Audio, cola y mezcla (PC): `playback/DesktopPlayerConnection.kt`, `DesktopAudioEngine.kt`, `DesktopBeat.kt`, `DesktopDjPlayback.kt`; Android: `app/.../playback/Crossfade.kt`.
- DJ compartido: `core/.../dj/` (`DjBridgePlanner`, `DjBridgeRenderer`, `SongSections`, `DjDsp`). Ver `docs/agents/dj-audio.md`.
- Cuenta: `core/.../account/Account.kt`, `Cloud.kt`. Enlace: `DeviceConnection.kt`, `remote/LibraryReplica.kt`, `LinkedPlayback.kt`.

## Dónde mirar según la tarea (leer solo lo necesario)

- `docs/agents/dj-audio.md`: DJ, mezclas, puentes, ganancia, logs de audio.
- `docs/agents/escritorio-ui.md`: decisiones visuales, «Una ventana», bienvenida, valores por defecto.
- `docs/agents/rendimiento.md`: RAM, fluidez, trazas y mediciones.
- `docs/agents/datos-sync.md`: perfiles, datos privados, Me gusta.
- `docs/agents/android-movil.md`: teléfono, firmas, `.linktest`, modo Connect.
- `docs/agents/releases.md` y `docs/PUBLICACION.md`: empaquetado, instalación y releases.
- Skills en `.agents/skills/`: `release-oto`, `instalar-pc`, `probar-movil`, `medir-rendimiento`.
- `docs/historico/`: planes, bitácora y validaciones antiguas. Solo si hace falta contexto; contrastar fechas con el código.

Si una regla de aquí choca con la tarea, decirlo claramente y pedir confirmación a Iago antes de saltársela.
