# Oto — continuidad después de /clear

Actualizado: 04/11/2026, release 2.0.0 publicada (ver «Release 2.0.0»). Actualizar este archivo después de cambios importantes o antes de un /clear. Distinguir implementado, probado, instalado y publicado.

## Punto de partida

- Repo: https://github.com/iaguito22/oto; rama canónica `main` (unificación de la antigua rama `feat/prueba-acoplamiento`).
- Clon: `C:\Users\iago\Documents\Codex\2026-11-02\files-pasted-by-the-user-encargo\work\oto`.
- Los cambios acumulados ya están confirmados y subidos. Empezar con `git status --short --branch` y conservar cualquier cambio posterior del usuario.
- Estado vigente: bienvenida, valores por defecto, instalador y paquetes 1.2.3 terminados. Windows instalado y probado con el perfil habitual conservado. Las secciones antiguas «EN CURSO» son históricas; este punto de partida prevalece.
- Validación final: core 56 pruebas (5 omitidas), desktop 71 (5 omitidas), sin fallos; compilación y pruebas Android correctas. Ubuntu instalado y arrancado bajo Xvfb, incluidos tres ciclos de ocultar/mostrar: Actions 41724981355. Arch instalado y comprobado en contenedor: Actions 41724548965.
- Corregido el cierre involuntario de la composición al ocultar la última ventana: ventana invisible de sesión mantiene bandeja y servidor DesktopAccess activos. Probado también en Windows instalado. Regresión en `tools/check-desktop-session.py`.
- Defender terminó el análisis del MSI final sin amenazas. MSI y lanzador siguen sin firma Authenticode: no garantizar ausencia de avisos SmartScreen ni desactivar protecciones.
- Release: https://github.com/iaguito22/oto/releases/tag/v1.2.3; MSI Windows, DEB Ubuntu, paquete Arch, APK Android phone y SHA256SUMS. El APK phone conserva la firma personal y el identificador `.debug`; la clave de la antigua variante pública sin `.debug` no está disponible. La ruta de `prompt.md` correspondía a la clave personal, no a la pública.
- El AGENTS anterior completo está preservado en `docs/AGENTS_HISTORICO_20261102.md`. Consultarlo por tema; sus fechas y pendientes son históricos. Este estado y las peticiones nuevas prevalecen ante contradicciones.

## Cómo trabajar con Iago

- Español para respuestas, interfaz y commits; frases claras y breves.
- Ejecutar lo autorizado sin pedir permisos rutinarios repetidamente. El usuario pidió rapidez y autonomía.
- Respetar su feedback visual. Quiere animaciones presentes, variadas, con dirección y rebote, pero fluidas.
- Navegador predeterminado: **Zen**. Google bloqueó login en navegador temporal; cookie pegada por él dentro de Oto sí funcionó. Nunca pedir cookies en el chat.
- Capturas permitidas. Su borde amarillo NO debe quedar permanente: cerrar/resetear la sesión de captura al acabar. Con sky funcionó `mcp__node_repl__js_reset`.
- Leer la habilidad computer-use antes de automatizar UI. No automatizar autenticación ni avisos de seguridad.
- Cerrar Oto desde bandeja o «Salir de Oto» para actualizar. Las X mantienen el proceso en segundo plano. Evitar terminarlo a la fuerza y modificar DataStore abierto.
- No declarar fluidez por screenshots o compilación. Tras dos hipótesis fallidas, instrumentar antes de seguir cambiando código.

## Compilación en ESTE Windows

Desde la raíz del repositorio, PowerShell:

```powershell
. ..\env.ps1
.\gradlew.bat :core:test :desktopApp:test --offline --no-configuration-cache
.\gradlew.bat :desktopApp:packageMsi --offline --no-configuration-cache
```

- `../env.ps1` configura JDK 21, FFmpeg y **GRADLE_USER_HOME=../gradle**. Sin ello puede parecer que faltan Java/dependencias ya disponibles.
- Wrapper actual Gradle 9.8.0. WiX/empaquetado falla con configuration cache: usar el indicador anterior.
- Empaquetado Windows actual: `desktopApp/package-windows.ps1`; MSI: `desktopApp/build/dist/Oto-1.2.3.msi`. Ver `docs/PUBLICACION.md` para WiX y los paquetes Linux.
- Instalación: `%LOCALAPPDATA%\Oto Desktop\Oto.exe`.
- Actualizar misma versión ha requerido desinstalar paquete y después instalar MSI con msiexec `/qn /norestart`; comprobar códigos 0. Obtener UninstallString actual del registro; no asumir GUID antiguo. Datos conservados aparte.
- Usar `Start-Process -WindowStyle Hidden`. No matar todos los java.exe (hay daemons Gradle).
- Dos Oto.exe pueden ser lanzador y JVM; medir el de mayor memoria.

## Arquitectura y archivos útiles

- `:core`: preferencias, cuenta, listas, letras, DJ y enlace compartido; los cambios pueden afectar Android. `:desktopApp`: Compose Desktop y audio. `:app`: Android.
- Entrada: `desktopApp/.../desktop/Main.kt`, `ModularDesktop.kt`.
- Ventanas: `DockLayout.kt` geometría pura; `DockWindows.kt` AWT/gestos; `DockPersistence.kt`; pruebas `DockLayoutTest.kt`.
- UI activa: `ui/LibrarySpace.kt`, `SettingsSpace.kt`, `DownloadsSpace.kt`, `PlayerSurface.kt`, `MobileLyrics.kt`. `DesktopStage.kt` y `DesktopMenuSheet.kt` contienen código antiguo y componentes reutilizados: seguir llamadas reales.
- Audio/cola/fundido: `playback/DesktopPlayerConnection.kt`, `DesktopAudioEngine.kt`, `DesktopBeat.kt`.
- Carátulas: `ui/DesktopTheme.kt` (ArtworkLoader), `PlayerBackdrop.kt`.
- Cuenta: `core/.../account/Account.kt`, `Cloud.kt`. Enlace móvil: `DeviceConnection.kt`, `remote/LibraryReplica.kt`, `LinkedPlayback.kt`.

## Pendientes reales para retomar

1. Vincular el móvil habitual con el Windows mediante Dispositivos/QR si aún no lo ha hecho Iago; comprobar sincronización con sus perfiles reales. Canal y reglas probados con teléfono físico y datos aislados. No afirmar que los perfiles habituales ya están sincronizados.
2. Para después, por petición explícita: pantalla de bienvenida del primer arranque, revisar valores por defecto y pulir fluidez/animaciones (incluido pico de biblioteca). No cambiarlo de oficio al retomar otro tema.
3. Medir ahorro RAM on/off con misma carga. Implementado/instalado, sin A/B prolongado todavía.
4. Duplicados YouTube: eliminadas 14 copias comparadas, conservando otra de cada lista. Quedan grupos distintos o sin auditar. Local `../playlist-audit.json`. No borrar por nombre solo.
5. Cloud anti-duplicados reutiliza ids, bloquea nombres y reintentos ambiguos, pero consulta Music; listas visibles solo en YouTube normal pueden escapar. NO garantizar resolución total.
6. Muchos cambios locales sin publicar. Revisar diff y datos privados antes de commit/push. No asumir que Codex Linux tiene este estado.

## Referencias bajo demanda

`docs/AGENTS_HISTORICO_20261102.md`, `docs/ACOPLAMIENTO.md`, `ESTADO_MODULAR.md`, `docs/VALIDACION_WINDOWS.md`. Contrastar sus fechas con código. No cargar toda la historia tras cada /clear: empezar aquí, git status y archivos de la tarea concreta.


## Índice por temas (leer solo el que haga falta)

- `docs/agents/rendimiento.md`: ahorro de RAM, rueda suave, mediciones, arreglo de apertura de ventanas.
- `docs/agents/escritorio-ui.md`: decisiones visuales, exportación MP3, bienvenida, valores por defecto y tamaños.
- `docs/agents/datos-sync.md`: perfiles, datos privados, Me gusta de Oto.
- `docs/agents/android-movil.md`: teléfono, firma, pruebas, cola remota, modo Connect.
- `docs/agents/releases.md`: releases 1.2.3 / 2.0.0, instalaciones, APK pública.
