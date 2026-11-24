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

- Con una ventana de Oto enfocada, Espacio pausa o reanuda. Escribir una letra o un número sin Ctrl, Alt ni Windows abre y enfoca el buscador con ese carácter. Los campos de texto conservan su escritura normal. Escape sale de la búsqueda.

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

**06/11: rehecha** (sin instalar). Cinco pasos con los clips grabados por Iago (`Videos\*.mp4`, Recortes de Windows, 30 fps): Unir, Ordenar, Tamaño, Reproductor, Atajo (este con la barra de tareas a propósito, para ver el clic en la bandeja). Codificados con FFmpeg `libwebp_anim` a 1280 px, `-q:v 82` (~20 MB en total). Pasos pulsables con barra de progreso, avance solo al acabar cada clip, Anterior/Siguiente/Empezar. El dibujo usa `CubicResampler` (antes sin filtrado: bordes dentados). Iago la vio y le pareció bien. Lo de abajo describe la versión anterior.
- Aviso: libwebp con pérdida deja algún resto en fundidos lentos (la letra apagándose); probado Pillow con fotogramas clave cada 6–12 sin mejora medible. Se aceptó tal cual.

- Una pantalla (`WelcomeWindow.kt`) con carrusel de clips sin audio: pestañas «Combina ventanas», «Cambia el orden» y «Reproductor»; avance automático, pausa, Omitir y Empezar. WebP decodificado con Skia; sin red, audio ni FFmpeg.
- Preferencia independiente `desktop_welcome.json` (seen_version=1 al cerrar u omitir); reabrir desde «Ver bienvenida» en PlayerOptions (diálogo ampliado a 430 px).
- Seis WebP en `desktopApp/src/main/resources/welcome/` (~1,5 MiB). El intercambio es una animación explicativa y el horizontal un montaje de grabaciones: no presentarlos como una grabación continua. Test: `WelcomeMediaTest`.
- `OTO_WELCOME_PREVIEW=1` abre SOLO la bienvenida, antes de inicializar preferencias o servicios; usar una COPIA del JAR para que recompilar no rompa la lectura de recursos.
- Material de grabación (MP4 sin audio 1280×680, originales, herramientas) fuera del repo, en `C:\Users\iago\Documents\Codex\2026-11-03\quier`.

## Valores por defecto y tamaños del reproductor

- Reglas autorizadas por Iago: horizontal SIEMPRE con altura mínima 108; vertical con ancho mínimo 96; carátula 9:16, inicial 360×640 (`PlayerForm.Full`). Primer arranque: SOLO el reproductor centrado; al abrir módulos, Letra / Biblioteca / Reproductor y Cola independiente; aleatorio activado.
- `PlayerWindowSizing.kt` centraliza mínimos y tamaños de apertura: horizontal conserva ancho y fuerza 108 de alto; vertical conserva alto y fuerza 96 de ancho; carátula conserva el ancho y calcula alto 16/9 (acoplada, adapta el ancho a la altura compartida). Se normaliza al seleccionar/abrir/restaurar; después se puede redimensionar a mano. Horizontal se desacopla (108 no es compatible con la biblioteca).
- `changeForm` actualiza el `minimumSize` de AWT ANTES de reducir los bounds (si no, el horizontal hereda 370 px). `initialDockLayout()` usa altura 640 si cabe y ancho de biblioteca 640–960. Tests: `PlayerWindowSizingTest`, `DockLayoutTest`.
- Otros defaults de código: calidad Auto, volumen 100 %, fundido 3 s, ahorro de datos/RAM off, aleatorio/repetición off, fondo on 75 %, latido on, romanización on, letra 120 %, tarjetas medianas, inicio en Tu música, «Luces y visuales externos» off (`VisualsBridge`: 127.0.0.1:8767, `/estado` y `/audio`, solo orígenes locales; lo usa `scratchpad/laboratorio-visual` con «Seguir a Oto»). La fila de luces solo sale en Extras si el PC tiene el laboratorio (`lab` en `visuals_bridge.json`, `OTO_LAB_DIR` o `%APPDATA%\oto\laboratorio-visual`, que en el PC de Iago es un enlace a la carpeta del scratchpad) o si ya estaba encendida. Inventario para Iago: `C:\Users\iago\Documents\Codex\2026-11-03\quier\outputs\valores-por-defecto-oto.md`.

## Fondo de escritorio vivo (Ajustes → Extras (beta))

- Apagado por defecto; solo Windows. Se enciende en Extras o en la bandeja («Poner / Quitar fondo vivo»). Código en `desktop/wallpaper/`.
- Son elementos encima del fondo de pantalla de siempre, no un fondo nuevo: la ventana va dentro de Progman, justo debajo de los iconos (Windows 11 24H2+) o en el WorkerW clásico, y pinta el fondo actual (registro `WallPaper` o `TranscodedWallpaper`) debajo. Al apagarlo se repinta el fondo de Windows.
- Valores por defecto (los que eligió Iago en el laboratorio): estilo «Barras en anillo» (cambiado por Iago el 23/11; antes NCS), intensidad del aro NCS 85 %, resplandor 60 %, carátula dentro, «Tu fondo actual», sombra 50 %, partículas apagadas, letra donde haya sitio, 120 fps (Iago, 23/11; nunca más que la pantalla), ahorro sin música y sin pintar mientras algo tapa el escritorio (pantalla completa o cualquier ventana maximizada en ese monitor, `WallpaperHost.desktopHidden`).
- El audio sale de `AudioTap` (copia de lo que va a la línea, solo con el fondo encendido) y se analiza en vivo (`Spectrum`); no hay análisis previo de la canción.
- La letra del fondo usa el mismo adelanto que la de la app (`MobileLyrics.leadMs`: 250 ms palabra a palabra, 100 ms línea a línea); sin él iba tarde.
- La letra nunca se pinta encima del visualizador: va en zonas libres y además se recorta con el círculo (`WallpaperTest.zonasDeLetraNuncaTocanElVisualizador`).
- Pruebas sin cuenta ni red: `OTO_FONDO_DEMO=<audio>`, `OTO_FONDO_DEMO_LRC`, `OTO_FONDO_DEMO_COVER` (con perfil aislado).
- Instrumentos (`Spectrum.instruments`): bombo, caja y platos son subidas de energía en dB de su rango en ~30 ms, solo si ese rango pesa en la mezcla (un bajo que se desliza no es bombo, la voz no es caja, el arranque de una caja no es bombo). Energía = volumen real frente a lo más fuerte de la canción (escala de 6 dB: la música actual separa estrofa y estribillo 2-4 dB). Drop = los graves entran de golpe. Pruebas con música sintética en `InstrumentsTest`.
- El audio se lee 30 ms por delante de lo que suena (`WallpaperAudio.LEAD_MS`) para compensar la ventana del análisis y lo que tarda la pantalla.
- Ritmo de cuadros: nunca `delay` corto (Windows despierta cada ~15,6 ms: a «120 fps» salían ~50 a saltos); se espera a la pantalla y se saltan refrescos. ¿Escritorio tapado? se mira aparte, cada segundo. Traza: `OTO_FONDO_TRAZA=<archivo>`.
- Hora y controles de música en el escritorio (apagados por defecto, Iago 24/11). Al encenderlos: hora «el día en grande» (Anurati si está instalada en Windows o en `<datos de Oto>/fuentes`; si no, Michroma, OFL, en `resources/fonts`) y tarjeta «compacta»; hay píldora, tarjeta, solo botones y vinilo (abajo a la izquierda, con la cola). Anurati, Blanka, Elianto y Ailerons NO se meten en Oto: Oto se publica y sus licencias no lo permiten.
- La hora y los controles de música son entradas propias en Extras (beta), aparte del fondo vivo: funcionan también con el fondo apagado (`WidgetOnlyWindow`: ventanas pequeñas «Oto · Hora» y «Oto · Controles» detrás de los iconos que pinta su trozo del fondo de Windows). El fondo se saca con `PaintDesktop` (`WallpaperHost.paintDesktop`), igual píxel a píxel que lo que pinta Windows; leer el archivo del registro dejaba un recuadro desplazado (24/11). Idioma español o inglés (`relojIdioma`). Los nombres de días y meses van escritos en `WidgetState`: el Java que va con Oto no lleva `jdk.localedata` y `DateTimeFormatter` en español daba abreviaturas.
- El día de la hora «día» se dibuja letra a letra con el mismo hueco entre el dibujo de cada una (`evenText`): Anurati deja mucho aire tras la «M». Si falta una fuente de fuera, Ajustes explica cómo descargarla y abre la carpeta `fuentes`; se vuelve a buscar cada 10 s (sin reiniciar). La letra va a las zonas con al menos el 45 % del área de la mayor; los lados bastan con el 30 % (`lyricCandidates`): con el vinilo la izquierda se quedaba en el 44 %. Bajarlo para todas metía la franja de debajo del círculo y Iago prefería las posiciones de antes.
- Los controles se pulsan aunque la ventana esté detrás de los iconos: se mira el ratón (`WallpaperHost.pointer`: solo si debajo está el escritorio) y `WidgetInput` decide (se activa al soltar sobre el mismo botón). La hora y los controles reservan su sitio y la letra no lo pisa (`WidgetsTest`).
