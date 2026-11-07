# Oto — continuidad después de /clear

Actualizado: 05/11/2026. Actualizar tras cambios importantes. Distinguir implementado, probado, instalado y publicado.

## Estado actual

- Repo: https://github.com/iaguito22/oto, rama `main`. Clon: `C:\Users\iago\Documents\Codex\2026-11-02\files-pasted-by-the-user-encargo\work\oto`.
- Release publicada: 2.0.0 (v2.0.0). Instalada en el PC y en el móvil habitual.
- Todo subido a `origin/main` (05/11), incluida la curva de volumen ^1,5 (`aa72934`, pendiente de que Iago la pruebe; si <10 % sigue bajo, probar 1,25).
- **Commit «iconos 2K, varias pantallas y bandeja» (05/11), subido e INSTALADO en el PC (23:38, JAR igual al compilado).** Su MSI sustituyó al `Oto-2.0.0-windows-x64.msi` de la release v2.0.0 (SHA-256 `54442ec0…be34e`, SHA256SUMS actualizado). Ojo: ese MSI ya no coincide con la etiqueta `v2.0.0` (está en `main`, posterior a la etiqueta); DEB, Arch y APK siguen siendo los del 04/11.
  - Iconos nítidos en 2K: `AppIcon.kt` da a la ventana todos los tamaños (16–256) y a la bandeja el tamaño exacto (16 × escala), reducidos con suavizado. Antes AWT reducía 256 px sin interpolar.
  - Varias pantallas (sin probar: Iago solo tiene un monitor; lo reportó un amigo): el arrastre ya no se reinicia al cambiar la densidad a mitad de gesto (`DockDragArea` ya no usa la densidad como clave); `screenSignature()` + `DockSession.screensChanged()` detectan cambios de monitores/escala, ignoran los movimientos sueltos de Windows y recolocan los conjuntos con `recover`; `nearestArea()` evita caer a la primera pantalla en los huecos entre monitores de escala distinta.
  - Bandeja: el menú del clic derecho muestra la canción actual (carátula, título, artista; clic abre el reproductor) y el tooltip lleva «Oto · título — artista».
  - Iconos obsoletos pasados a AutoMirrored.
- 06/11, con commit «bucle en la radio, letra fluida y gráfica dedicada» (`df68d40`, subido), APK sin instalar: en Android, la cola de una radio (canción buscada) muestra el botón de bucle, que alterna apagado ↔ «repetir esta canción» (`Panels.kt`, `LoopButton(radio)`, `PlayerConnection.cycleLoop/isRadio`). Compila y pasan `:app:testDebugUnitTest` (hace falta `ANDROID_HOME=..\tools\android-sdk`).
- Letra PC vs móvil (06/11): misma fuente en `:core`, pero cada aparato guarda su propia caché (explica diferencias de palabra a palabra y quizá el desfase de ~20 s; sin confirmar con una canción concreta). Sin commit ni instalar: quitado el `delay(10)` del reloj de la letra en PC (`MobileLyrics.kt`); medido en 120 Hz: 69–87 fps irregulares → 114–120 fps estables, ~+5 puntos de CPU de un núcleo. El parón de ~250 ms cada 10–20 s es de la AMD integrada con ventanas transparentes (reproducido sin Oto); con la NVIDIA desaparece. Nuevo ajuste «Gráfica dedicada» (Windows, apagado por defecto; `DesktopGraphics.kt`, se aplica al reabrir Oto): probado en perfil aislado, 0 parones. **Mismo commit, subido; INSTALADO en el PC el 06/11 a las 04:27** (MSI local, códigos 0, JAR igual al compilado `E79A4CC3…D56A`; incluye también el reloj de la letra sin `delay(10)`). Iago debe activar el ajuste y reabrir Oto. La release v2.0.0 NO se ha tocado. Detalle en `docs/agents/rendimiento.md`.
- 06/11, sin commit ni instalar: etiqueta «Menú» de la gota debajo de ella al subir (`IslaScreen.kt`, Android); carátula del PC en alta en el móvil; el PC muestra y controla lo que suena en el móvil en reproductor y bandeja (`RemoteMirror.kt`). Compila, pasan `:desktopApp:test`; sin probar con el móvil real. Propuesto (sin aprobar) rediseñar Dispositivos en PC y móvil: tarjeta con lo que suena en el otro aparato, botón grande «Pasar al móvil/Traer aquí», lo técnico plegado.
- 06/11 (2), sin commit ni instalar, compila y pasan `:core:test :desktopApp:test :app:testDebugUnitTest`:
  - Corte entre ventanas al abrir: medido 1 px de hueco físico a 125 % (Letra acababa en 1088, Reproductor en 1089) porque no llegaba el solape de la unión hasta mover. `BindDock` hace una pasada al asentarse que fija bounds con solape (`settle` en el trace). Sin comprobar en la app real.
  - Bordes del conjunto (`DockEdges`, `DockLayout.resizeEdge/releaseMaximized`): izquierdo, derecho, superior, inferior y esquinas; maximizado deja de estarlo al arrastrar. Test `outerEdgesResizeTheGroupAndReleaseMaximized`.
  - Móvil: siguiendo al PC, la notificación de `ConnectionService` pasa a reproductor (MediaSession `oto-ordenador` con `PcPlayer`) con «Sonando en el ordenador»; pantalla de bloqueo sin probar.
  - Batería: `StateOutbox` (LocalLink) envía el estado al cambiar, 1/s sonando, 10 s en pausa; la cola solo cuando cambia (`queueSame`). Requiere las DOS apps actualizadas (una vieja recibiría la cola vacía).
  - RAM: los «OpenJDK» de 1,3 GB y 0,8 GB del Administrador eran los daemons de Gradle/Kotlin de las compilaciones, no Oto (Oto ≈790 MB). Arranque: CDS entrenado al empaquetar (`packaging/windows/prepare-cds.ps1`, desde `package-windows.ps1`): medido ~3,0 s → ~1,7 s hasta ver el reproductor; sin instalar. Detalle en `docs/agents/rendimiento.md`.
  - Fuente: el PC ya usa Rubik (variable) pero `DesktopTokens.kt` no fija `variationSettings`; posible que los pesos no se apliquen. Sin comprobar.
- 06/11 (3), sin commit ni instalar, pasan todas las pruebas:
  - «Tu resumen» (Wrapped sencillo): `core/.../dj/Recap.kt` (+`RecapTest`) desde `Plays` de ESTE aparato (no se comparten; últimas 6000 escuchas). Tarjeta arriba de Explorar en PC (`RecapSpace.kt`, `LibrarySpace`) y móvil (`MenuSheet.RecapCard/RecapView`, `Page.Recap`). Sin revisar visualmente.
  - Gustos: en `Profile`, estar en una lista pesa 1,5 por canción y Me gusta 0,5 (antes 1 y 2); Me gusta como escena vale la mitad (`ProfileWeightTest`). Afecta al DJ y a las listas de Explorar.
  - Bienvenida del PC: a Iago no le convence (clips WebP de baja calidad, explica poco). Pendiente de rediseño.
- **06/11: release v2.1.0 PUBLICADA** (commit `2176563`, todo lo anterior de este día incluido). Iago la instaló él mismo en el móvil (phone) con el APK; el PC, pendiente de que lo confirme. El APK phone se quitó después de la release. Variante Android 12 en `../oto-android12` (rama local `android12`). Ver `docs/agents/releases.md`.
- **07/11 16:52: INSTALADO en el PC de Iago** con Snap nativo y latido mejorado (MSI local `Oto-2.1.0.msi`, desinstalar/instalar códigos 0, JAR `6CBDFAB4E305…` igual al compilado). Sustituye a la instalación de las 16:10 (`784D8C4C…`). Sin commit. Empaquetado en este PC: `..\env.ps1` añade FFmpeg (`Documents\ffmpeg\bin`, copiado del Oto instalado) y WiX 3.14 (`~\.wix314`); `desktopApp\package-windows.ps1`.
- **07/11, cambios (PC de Iago `igl20`, clon en `Documents\oto`, `..\env.ps1` con JDK 21 en `~\.jdks`)**: dos pantallas y Snap, PROBADO en pantallas reales con el arnés.
  - Pantallas medidas: 1080p 100 % principal + 2K vertical 125 % a la izquierda (físico -1440,-1941 → 0,619). AWT (JDK 21) deja el ORIGEN físico y escala solo el tamaño: hay huecos entre pantallas en AWT. El fallo de «varias pantallas» era eso: el arrastre seguía el punto de AWT, que salta al cruzar, y las secundarias caían en el hueco.
  - Arrastre en Windows ahora en píxeles físicos (`DockSession.startNativeDrag`, temporizador con GetCursorPos/GetAsyncKeyState: al cambiar de escala Compose corta el gesto). Al soltar: pantalla que más tapa el conjunto (`dropScreen`), `settleInto` (encoge hasta mínimos con `fit(tight)` antes de separar; `squeezed` recuerda anchos y los devuelve), `syncGroupBounds` + `rescaleUntil` (Windows da el tamaño físico de la escala anterior: 513 en vez de 640; no es un redimensionado del usuario).
  - Snap propio del conjunto (`DockSnap.kt`): bordes libres izq./der. = mitad, esquinas = cuarto, arriba = maximizar; vista previa translúcida (`SnapPreview`); borde compartido con otra pantalla no ajusta. Respeta Configuración > Multitarea (`WindowsSnapSettings`). Maximizar estando en mitad → pantalla entera; restaurar → tamaño previo.
  - **07/11 (tarde), sin commit ni instalar: Snap NATIVO de Windows (lo pidió Iago; el Snap propio y su vista previa se han quitado).** `beginSystemMove` (`NativeScreens.kt`): subclase del procedimiento de la ventana (JNA) que, en el hilo de la ventana, suelta la captura de AWT y entra en `SC_DRAGMOVE`; durante el gesto lleva `WS_THICKFRAME|WS_MAXIMIZEBOX` (sin ellos no hay Snap) y `WM_NCCALCSIZE` evita el marco. Windows mueve la ventana agarrada y hace Snap y cambio de escala; las demás la siguen (`followPhysical`). Al acabar el bucle (`systemMoveEnded`): si `IsWindowArranged` y cambió el tamaño, el conjunto entero se ajusta a ese hueco (vale para tercios/diseños de Win11); el maximizado arriba llega después y lo recoge el efecto de `placement`. Arreglado así el parpadeo al mantener el conjunto en la unión entre pantallas (capturas a 60 ms: sin parpadeo ni contenido cortado). Arnés: cross, snap, top, jitter y resize OK. Fallan en el arnés: soltar arriba en línea recta sin pararse (Windows no maximiza) y, con Letra, «borde derecho a 1700» queda en 1713 (sin investigar; no toca el arrastre).
  - Latido de la carátula (07/11, sin commit ni instalar): mismo detector y misma envolvente que el móvil, pero el PC lo leía cada ~20–26 ms (`delay(10)` en `rememberCoverPulse`) y tomaba un solo trozo de 10,7 ms: se perdían bombos. Ahora `DesktopBeat.peak` da el más fuerte de los últimos 3 trozos (~32 ms) y `lineFrames` interpola los saltos de 10 ms de `longFramePosition` (medido con `AudioClockProbeTest`, OTO_AUDIO_PROBE=1). Sin compensar: el retraso de Windows + Voicemeeter + UMC202HD de Iago (Java no lo ve; el pulso puede ir algo adelantado). Sin probar a oído.
  - Win+flechas NO funciona: Windows no las aplica a ventanas sin marco ni se las pasa a la app (probado). Haría falta un gancho de teclado; sin hacer.
  - Arranque: el conjunto recién abierto se minimizaba solo (`taskbarToggle` confundía la activación inicial); ignorado 1,5 s tras mostrar una ventana.
  - Pruebas: `DockSnapTest` (geometría medida), `ScreenProbeTest` (OTO_SCREEN_PROBE=1, pantallas reales). Arnés: `OTO_DOCK_HARNESS=1 OTO_DOCK_HARNESS_TOP=1 [OTO_DOCK_HARNESS_LYRICS=1]`, `:desktopApp:run -x :desktopApp:prepareWindowsAudio` (sin FFmpeg en este PC; por eso falla `Mp3ExportTest`).
  - Artista sin id (Ariana Grande): causa medida en `library-replica.json`: lo importado/compartido lleva `artistId`; lo perdían el Me gusta/Guardar del reproductor del PC y TODO el enlace en directo (cola transferida, añadir a la cola, espejo, Me gusta remoto). Ahora `QueueEntry.artistId` y `"artistId"` en el JSON del enlace (PC y móvil; requiere las dos apps nuevas para que llegue). Además `MenuController.openArtist` busca por nombre si falta. El código Android NO se ha compilado en este PC (sin SDK).
  - Mini reproductor de Tu música: solo se esconde si el Reproductor está en su mismo conjunto. Corazón del reproductor: un clic abre «Guardar» (Me gusta incluido); doble clic en la carátula sigue siendo Me gusta directo.
  - Redimensionar maximizado (07/11, PROBADO en el arnés): Compose ponía su propio borde invisible de redimensionado en ventanas sin marco y se adelantaba a la unión; ahora `resizable = false` en las acopladas. Unión y bordes siguen el puntero en pantalla y aplican el conjunto entero en el mismo evento (`applyGroupBounds`); `reconcile` reaplica el modelo antes de aceptar una geometría ajena. Borde exterior: al llegar una ventana a su mínimo sigue encogiendo la siguiente.
  - Reabrir una ventana cerrada suelta: vuelve a la pantalla donde se cerró (antes se metía en la de la ventana desde la que se abría). Probado con `ScreenProbeTest`.
  - Batería real: `scratchpad\scenarios.ps1 [-Lyrics] -Only cross|snap|resize` (no versionada; mueve el ratón, solo hace clic si debajo hay una ventana del arnés).
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
