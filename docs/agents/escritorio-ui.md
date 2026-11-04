# Escritorio: interfaz, bienvenida y valores por defecto

Índice en `AGENTS.md`. Estados de 03–05/11/2026; contrastar con el código.

## Decisiones visuales que conservar

- Fondo integrado entre ventanas, esquinas redondas incluso interiores, sin líneas rectas de unión. Carátula completa con laterales reflejados/extendidos, sin franjas planas grandes. Intensidad inicial 75 %.
- Conservar posición y tamaños al intercambiar, abrir/cerrar módulos, usar el atajo y cambiar de canción. Letra y cola no pueden quedar únicas; cerrar las principales conserva el conjunto para restaurarlo con el atajo.
- Cambiar la vista del reproductor acoplado DEBE desacoplarlo (petición explícita).
- Atajo global configurable en Ajustes: oculta/restaura todo el grupo. La X de todas las ventanas deja Oto en bandeja. Una entrada de barra de tareas por grupo; minimizar/restaurar afecta al grupo.
- Chincheta solo con el reproductor solo: vertical abajo a la izquierda, oculta hasta hover, estado reconocible.
- Biblioteca sin tres puntos; miniaturas de lista con las primeras cuatro canciones; la pista inferior lleva al origen. Me gusta sin relleno si no está marcado.
- Letra con aspecto Android (subidas/bajadas/vibrato); no copiar su sincronización a ciegas. La letra de Awaken se corrigió y Iago confirmó la inglesa.
- Tooltip del punto de la ventana principal: «Ventana principal del conjunto» + explicación (aparece en la barra de tareas, arrastra a las demás; para cambiar el orden se arrastra la barra de otra ventana hasta ella).

## Ventanas: comportamiento y robustez

- `DockLayout.memory` (`rememberOrder`) recuerda el orden de los miembros de un conjunto; `open()` reabre en la posición recordada aunque se cierren/abran en cualquier orden (test `reopeningInAnyOrderRestoresThreeWindowOrder`).
- Los gestos de `DockDragArea` y el tirador de redimensionado leen `layout.bounds` con acceso seguro: una ventana que sale del modelo con un gesto en curso no debe lanzar «Key … is missing in the map». El origen exacto del crash «Key Library is missing» no se reprodujo; está endurecido, no confirmado.
- `nativeDockChange` ignora diferencias de 1 px por redondeo DPI (se interpretaban como resize/move y desacoplaban el reproductor); conserva los gestos reales.
- Tooltip, memoria de orden y robustez van en el commit `061d1d5`.

## Reproductor, letra y volumen

- **Letra:** `LyricsStore` (memoria `MemCache(64)` + disco, `peek`, `get`, `romanized`). `DesktopPlayerConnection.fetchLyrics` publica al instante lo que haya en caché (`peek`) y `prefetchNextLyrics` precarga la de la siguiente canción. En `MobileLyrics.kt`, `SungLine` pinta `active`/`preview`/`lit`; la línea siguiente se previsualiza completa y el desenfoque es 0 dp (distancia 0–1), 1,2 dp (2) o 2 dp (resto). Fluidez sin medir.
- **Volumen:** la ganancia es `volumen^1,5` (`volumeGain()` en `DesktopPlayerConnection.kt`); la ruta Windows escala muestras PCM de FFmpeg en `DesktopAudioEngine`. Aprox.: 50 % ≈ −9 dB, 10 % ≈ −30 dB, 1 % ≈ −60 dB, 100 % sin cambio. Lineal sonaba demasiado alto abajo y el cuadrado dejaba casi mudo por debajo del 10 %. Sin commit y sin confirmar por Iago; alternativa: exponente 1,25.
- **Búsqueda:** al abrir el buscador se muestra el historial (`RecentSearches` en `SmartSpace.kt`). Pulsar una fila le quitaba el foco al campo y cerraba la lista antes del clic; `LibrarySpace.kt` marca `pressingRecents`/`searchHasFocus` y retrasa el cierre. Iago no ha confirmado que ya navegue; si falla, preguntar por el tipo de entrada y probar en la app real.

## Exportación MP3

- Descargas → «Exportar como MP3…» (todas o una canción, incluidas las de listas): copias independientes `artista - título.mp3` con etiquetas y sufijos numéricos sin sobrescribir; originales intactos. Descargas internas: `.webm`/`.m4a` con nombre por id (no cambiarlo sin migrar el índice). No existe «Abrir carpeta de descargas».
- `playback/Mp3Export.kt`: FFmpeg incluido, libmp3lame VBR q2, temporales limpiados al cancelar/fallar. `Mp3ExportTask.kt`: lote ligado al appScope de DesktopDownloads (independiente de la pantalla); `ui/Mp3ExportControls.kt` (compartido por DownloadsSpace y DesktopDownloadsPane) muestra progreso, Cancelar y errores.
- Cambiar de pantalla o dejar Oto en bandeja mantiene el trabajo; salir lo termina (los MP3 guardados permanecen) y no se reanuda tras reiniciar.
- Selector de carpeta: el nativo de Windows (`WindowsExportFolder.kt`, IFileOpenDialog vía JNA); fuera de Windows, JFileChooser. El navegador Compose propio se descartó: NO recuperarlo.
- Tests: `Mp3ExportTest`, `Mp3ExportTaskTest`.

## Bienvenida

- Una pantalla (`WelcomeWindow.kt`) con carrusel de clips sin audio: pestañas «Combina ventanas», «Cambia el orden» y «Reproductor»; avance automático, pausa, Omitir y Empezar. WebP decodificado con Skia; sin red, audio ni FFmpeg.
- Preferencia independiente `desktop_welcome.json` (seen_version=1 al cerrar u omitir); reabrir desde «Ver bienvenida» en PlayerOptions (diálogo ampliado a 430 px).
- Seis WebP en `desktopApp/src/main/resources/welcome/` (~1,5 MiB). El intercambio es una animación explicativa y el horizontal un montaje de grabaciones: no presentarlos como una grabación continua. Test: `WelcomeMediaTest`.
- `OTO_WELCOME_PREVIEW=1` abre SOLO la bienvenida, antes de inicializar preferencias o servicios; usar una COPIA del JAR para que recompilar no rompa la lectura de recursos.
- Material de grabación (MP4 sin audio 1280×680, originales, herramientas) fuera del repo, en `C:\Users\iago\Documents\Codex\2026-11-03\quier`.

## Valores por defecto y tamaños del reproductor

- Reglas autorizadas por Iago: horizontal SIEMPRE con altura mínima 108; vertical con ancho mínimo 96; carátula 9:16, inicial 360×640 (`PlayerForm.Full`). Primer arranque: SOLO el reproductor centrado; al abrir módulos, Letra / Biblioteca / Reproductor y Cola independiente; aleatorio activado.
- `PlayerWindowSizing.kt` centraliza mínimos y tamaños de apertura: horizontal conserva ancho y fuerza 108 de alto; vertical conserva alto y fuerza 96 de ancho; carátula conserva el ancho y calcula alto 16/9 (acoplada, adapta el ancho a la altura compartida). Se normaliza al seleccionar/abrir/restaurar; después se puede redimensionar a mano. Horizontal se desacopla (108 no es compatible con la biblioteca).
- `changeForm` actualiza el `minimumSize` de AWT ANTES de reducir los bounds (si no, el horizontal hereda 370 px). `initialDockLayout()` usa altura 640 si cabe y ancho de biblioteca 640–960. Tests: `PlayerWindowSizingTest`, `DockLayoutTest`.
- Otros defaults de código: calidad Auto, volumen 100 %, fundido 3 s, ahorro de datos/RAM off, aleatorio/repetición off, fondo on 75 %, latido on, romanización on, letra 120 %, tarjetas medianas, inicio en Tu música. Inventario para Iago: `C:\Users\iago\Documents\Codex\2026-11-03\quier\outputs\valores-por-defecto-oto.md`.
