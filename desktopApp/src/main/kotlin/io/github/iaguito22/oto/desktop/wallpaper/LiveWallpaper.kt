package io.github.iaguito22.oto.desktop.wallpaper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.github.iaguito22.oto.desktop.playback.AudioTap
import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
import io.github.iaguito22.oto.desktop.ui.artworkUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import timber.log.Timber
import java.io.File
import java.net.URI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** El audio que suena, analizado una vez por cuadro para todas las pantallas. */
internal object WallpaperAudio {
    /** Adelanto de la lectura del audio, en ms (ver [update]). */
    const val LEAD_MS = 30f
    val spectrum = Spectrum()
    private var last = 0L
    var hasAudio = false; private set

    fun update(now: Long, dt: Float, p: WallpaperSettings, playing: Boolean) {
        if (now - last < 4_000_000L) return   // otra pantalla ya lo ha hecho en este cuadro
        last = now
        val d = demo
        // Se lee un poco por delante de lo que suena (la línea guarda ~128 ms ya escritos): la ventana de 2048 muestras
        // ve un golpe ~15-25 ms después de que empiece y la pantalla tarda uno o dos cuadros más en enseñarlo.
        hasAudio = if (d != null) readDemo(d, now) else playing && AudioTap.read(spectrum.samples, ((LEAD_MS + p.desfase) * 48).toLong())
        spectrum.update(hasAudio, dt, p.suavizado)
    }

    /**
     * Solo para pruebas: con `OTO_FONDO_DEMO=<archivo de audio>` el fondo «oye» ese archivo (decodificado con FFmpeg)
     * en bucle, como si sonara en Oto, sin cuenta ni red. No reproduce nada por los altavoces.
     */
    val demo: FloatArray? by lazy {
        System.getenv("OTO_FONDO_DEMO")?.let(::File)?.takeIf { it.isFile }?.let { f ->
            runCatching {
                val proc = ProcessBuilder(io.github.iaguito22.oto.desktop.playback.DesktopTools.require("ffmpeg"), "-v", "error", "-i", f.absolutePath,
                    "-ac", "1", "-ar", "48000", "-f", "f32le", "pipe:1").redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val bytes = proc.inputStream.use { it.readBytes() }
                val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                FloatArray(bb.remaining()).also { bb.get(it) }
            }.onFailure { Timber.w(it, "Fondo vivo: no se pudo leer el audio de prueba") }.getOrNull()
        }
    }
    private val demoStart = System.nanoTime()
    private fun readDemo(d: FloatArray, now: Long): Boolean {
        if (d.isEmpty()) return false
        val end = ((now - demoStart) / 1e9 * 48_000).toLong() + (LEAD_MS * 48).toLong()
        val out = spectrum.samples
        for (k in out.indices) { val i = ((end - out.size + k) % d.size).toInt(); out[k] = if (i < 0) 0f else d[i] }
        return true
    }
    val demoPositionMs get() = ((System.nanoTime() - demoStart) / 1_000_000L) % ((demo?.size ?: 1) / 48L).coerceAtLeast(1L)
}

/** Estado visible en Ajustes: si no se pudo poner detrás de los iconos, por qué. */
internal object WallpaperStatus {
    var issue by mutableStateOf<String?>(null)
}

/**
 * Las ventanas del fondo vivo (una por monitor elegido). Va dentro de `application { }`, en «Una ventana» y en el
 * modo de varias ventanas. Apagado (por defecto) no crea nada ni copia audio.
 */
@Composable
internal fun LiveWallpaperWindows(player: DesktopPlayerConnection, liked: Boolean = false, onLike: ((Boolean) -> Unit)? = null) {
    if (!wallpaperSupported) return
    val enabled by WallpaperStore.enabled.collectAsState()
    val settings by WallpaperStore.settings.collectAsState()
    DisposableEffect(enabled) {
        AudioTap.active = enabled
        onDispose {
            AudioTap.active = false
            // al apagarlo (o salir de Oto), que Windows vuelva a pintar el fondo de siempre
            if (enabled) WallpaperHost.refreshWallpaper()
        }
    }
    // sin el fondo vivo, la hora puede ir sola (es una entrada aparte en Extras)
    val like by rememberUpdatedState(liked); val likeAction by rememberUpdatedState(onLike)
    val actions = remember { WidgetActions(player, { like }, { on -> likeAction?.invoke(on) }) }
    // sin el fondo vivo, la hora y los controles pueden ir solos (cada uno es una entrada aparte en Extras)
    if (!enabled) {
        if (settings.reloj || settings.controles) WallpaperHost.monitors().let { all -> (all.firstOrNull { it.primary } ?: all.firstOrNull()) }?.let { m ->
            if (settings.reloj) key(m, "hora") { WidgetOnlyWindow(m, true, player, actions) }
            if (settings.controles) key(m, "controles") { WidgetOnlyWindow(m, false, player, actions) }
        }
        return
    }
    val monitors = remember(settings.monitor) {
        val all = WallpaperHost.monitors()
        when (settings.monitor) {
            "todos" -> all
            "segundo" -> listOfNotNull(all.firstOrNull { !it.primary } ?: all.firstOrNull())
            else -> listOfNotNull(all.firstOrNull { it.primary } ?: all.firstOrNull())
        }
    }
    for (m in monitors) key(m) { WallpaperWindow(m, player, actions) }
}

@Composable
private fun WallpaperWindow(monitor: Monitor, player: DesktopPlayerConnection, actions: WidgetActions) {
    // Nace fuera de la pantalla y pequeña; al colgarla detrás de los iconos se coloca y se estira al monitor.
    val state = rememberWindowState(position = WindowPosition((-32000).dp, (-32000).dp), size = DpSize(64.dp, 64.dp))
    Window(onCloseRequest = {}, state = state, title = "Oto · Fondo", undecorated = true, resizable = false, focusable = false, alwaysOnTop = false) {
        LaunchedEffect(window) {
            delay(150)
            val ok = WallpaperHost.attach(window, monitor)
            WallpaperStatus.issue = if (ok) null else "Windows no dejó poner el fondo detrás de los iconos."
            if (!ok) window.isVisible = false
        }
        WallpaperContent(player, monitor, window, actions)
    }
}

@Composable
private fun WallpaperContent(player: DesktopPlayerConnection, monitor: Monitor, window: java.awt.Window, actions: WidgetActions) {
    val settings by WallpaperStore.settings.collectAsState()
    val p by rememberUpdatedState(settings)
    val np by player.state.collectAsState()
    val lyrics by player.lyrics.collectAsState()
    // El mismo adelanto que la letra de Oto (MobileLyrics.leadMs): 250 ms palabra a palabra y 100 ms línea a línea.
    // Sin él la letra buena (palabra a palabra) iba ~250 ms tarde respecto a la de la app.
    val leadMs by rememberUpdatedState((lyrics as? Lyrics.Synced)?.let { if (it.wordByWord) 250L else 100L } ?: 250L)
    val scene = remember { WallpaperScene().apply { audio = WallpaperAudio.spectrum } }
    val lyr = remember { WallpaperLyrics() }
    LaunchedEffect(lyrics) { lyr.set(demoLyrics ?: lyricLines(lyrics)) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val frame = remember { mutableLongStateOf(0L) }
    val slowFrame = remember { mutableLongStateOf(0L) }
    var geo by remember { mutableStateOf<Geometry?>(null) }
    val tm = rememberTextMeasurer(cacheSize = 64)
    val widgets = remember { WidgetState() }

    // carátula de lo que suena
    LaunchedEffect(np.artwork) {
        val demo = System.getenv("OTO_FONDO_DEMO_COVER")
        val url = np.artwork
        if (demo == null && url == null) return@LaunchedEffect scene.setCover(null)
        val (img, cols) = withContext(Dispatchers.IO) {
            val i = if (demo != null) loadFile(File(demo)) else loadImage(runCatching { artworkUrl(url!!, 544) }.getOrDefault(url!!))
            i to i?.let(::coverColors)
        }
        scene.setCover(img, cols); widgets.cover = img
    }
    // tu fondo de pantalla de siempre (se vuelve a mirar cada 30 s por si cambia o es una presentación)
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    var fit by remember { mutableStateOf("rellenar") }
    LaunchedEffect(Unit) {
        var key = ""
        while (isActive) {
            withContext(Dispatchers.IO) {
                val f = WallpaperHost.currentWallpaperFile()
                fit = WallpaperHost.wallpaperFit()
                // ya ajustado al tamaño de la pantalla: un fondo 4K se quedaba entero en memoria y se escalaba en cada cuadro
                val kf = wallpaperKey(f, fit)
                if (kf != key) { key = kf; wallpaper = desktopWallpaper(monitor, f, fit) }
            }
            delay(30_000)
        }
    }
    // ¿Tapado (pantalla completa o una ventana maximizada)? Se mira aparte, una vez por segundo: antes el bucle de
    // cuadros esperaba a esa consulta y cada segundo se perdía un cuadro.
    val hidden = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val h = p.pausaCompleta && withContext(Dispatchers.IO) { WallpaperHost.desktopHidden(monitor) }
            hidden.set(h)
            delay(if (h) 700 else 1000)
        }
    }
    // el reloj: limita los fps, ahorra sin música y se para con algo a pantalla completa
    LaunchedEffect(Unit) {
        var last = System.nanoTime(); var lastPlaying = System.nanoTime()
        var posBase = 0L; var posAt = System.nanoTime(); var lastPos = -1L
        while (isActive) {
            if (hidden.get()) { delay(500); last = System.nanoTime(); FrameTrace.reset(); continue }
            val now0 = System.nanoTime()
            val st = player.state.value
            val playing = (st.playing && !st.buffering) || WallpaperAudio.demo != null
            if (playing) lastPlaying = now0
            val idle = !playing && p.ahorro && now0 - lastPlaying > 2_500_000_000L && scene.coverT > 2f
            val interval = if (p.fps > 0 && !idle) 1_000_000_000L / p.fps else 0L
            if (idle) delay(250)
            // Con límite: Windows despierta los temporizadores cada ~15,6 ms, así que solo se duerme si falta mucho y el
            // resto se espera a la pantalla (un refresco cada vez). Antes, a 120 fps, «esperar 7 ms» eran 15 y salían
            // ~50 cuadros a saltos (medido: p50 16,6 ms, p95 31 ms; ahora 115-120 con p95 ~10 ms).

            else if (interval > 0) { val left = interval - (now0 - last); if (left > 20_000_000L) delay((left - 17_000_000L) / 1_000_000L) }
            var now = withFrameNanos { it }.let { System.nanoTime() }
            while (interval > 0 && now - last < interval - 2_500_000L) now = withFrameNanos { it }.let { System.nanoTime() }
            FrameTrace.frame(now - last, interval)
            val dt = ((now - last) / 1e9f).coerceIn(0f, .1f); last = now
            WallpaperAudio.update(now, dt, p, playing)
            val sp = WallpaperAudio.spectrum.samples
            for (i in scene.td.indices) scene.td[i] = sp[sp.size - scene.td.size + i]
            scene.p = p; scene.playing = playing
            if (size.width > 0) scene.ensureGeometry(size.width.toFloat(), size.height.toFloat(), density)
            if (scene.G !== geo) geo = scene.G
            scene.step(dt)
            // el vinilo: 33 rpm mientras suena (más despacio en los respiros)
            if (playing && p.ctlGiro) widgets.spin = (widgets.spin + dt * 3.49f * (1 - .5f * scene.U.slow)) % 6.2832f
            // posición de la letra: la del reproductor, adelantada con el reloj entre actualizaciones
            val pos = if (WallpaperAudio.demo != null) WallpaperAudio.demoPositionMs else player.positionMs.value
            if (pos != lastPos) { lastPos = pos; posBase = pos; posAt = now }
            lyricTime = (posBase + (if (playing) (now - posAt) / 1_000_000L else 0L) + leadMs + p.desfase.toLong()) / 1000f
            frame.longValue++
            if (frame.longValue % maxOf(1, (p.fps.takeIf { it > 0 } ?: 120) / 15) == 0L) slowFrame.longValue++
            FrameTrace.work(System.nanoTime() - now)
        }
    }
    // ── la hora y los controles ──
    val icons = WidgetIcons(rememberVectorPainter(Icons.Rounded.SkipPrevious), rememberVectorPainter(Icons.Rounded.PlayArrow), rememberVectorPainter(Icons.Rounded.Pause),
        rememberVectorPainter(Icons.Rounded.SkipNext), rememberVectorPainter(Icons.Rounded.FavoriteBorder), rememberVectorPainter(Icons.Rounded.Favorite),
        rememberVectorPainter(Icons.Rounded.Shuffle), rememberVectorPainter(Icons.Rounded.Repeat), rememberVectorPainter(Icons.Rounded.RepeatOne),
        rememberVectorPainter(Icons.AutoMirrored.Rounded.VolumeUp))
    val shuffle by player.shuffle.collectAsState()
    val loopMode by player.loopMode.collectAsState()
    val volume by player.volume.collectAsState()
    LaunchedEffect(np, shuffle, loopMode, volume) {
        val demo = WallpaperAudio.demo
        widgets.title = if (demo != null) "Canción de prueba" else np.title
        widgets.artist = if (demo != null) "Fondo vivo de Oto" else np.artist
        widgets.hasSong = demo != null || np.id != null
        widgets.playing = demo != null || (np.playing && !np.buffering)
        widgets.durMs = if (demo != null) demo.size / 48L else np.durationMs
        widgets.shuffle = shuffle; widgets.loop = loopMode.ordinal; widgets.volume = volume
    }
    LaunchedEffect(Unit) { snapshotFlow { actions.liked() }.collect { widgets.liked = it } }
    // vinilo: las siguientes de la cola y sus miniaturas
    val queue by player.queue.collectAsState()
    LaunchedEffect(queue, np.index, p.controles, p.ctlEstilo) {
        if (!p.controles || p.ctlEstilo != "vinilo") return@LaunchedEffect
        widgets.queue = if (WallpaperAudio.demo != null) List(3) { QItem(it + 1, "Siguiente canción ${it + 1}", "Artista de prueba", null) }
            else queue.filter { it.index > np.index }.take(5).map { QItem(it.index, it.title, it.artist, it.artwork) }
        for (q in widgets.queue) {
            val art = q.art ?: continue
            if (art in widgets.thumbs) continue
            withContext(Dispatchers.IO) { loadImage(runCatching { artworkUrl(art, 120) }.getOrDefault(art)) }?.let { widgets.thumbs[art] = it }
        }
        // que no crezca sin fin: solo las de ahora
        widgets.thumbs.keys.retainAll(widgets.queue.mapNotNull { it.art }.toSet())
    }
    // la hora, al empezar cada segundo
    LaunchedEffect(p.reloj, p.hora24, p.segundos, p.relojIdioma) {
        if (!p.reloj) return@LaunchedEffect
        while (isActive) { widgets.tick(p); delay(1000 - System.currentTimeMillis() % 1000 + 5) }
    }
    // la barra de la canción: 4 veces por segundo mientras suena
    LaunchedEffect(p.controles) {
        if (!p.controles) return@LaunchedEffect
        while (isActive) {
            widgets.posMs = if (WallpaperAudio.demo != null) WallpaperAudio.demoPositionMs else player.positionMs.value
            delay(if (widgets.playing && p.ctlProgreso && !hidden.get()) 250 else 1000)
        }
    }
    // el ratón: la ventana del fondo no recibe clics (están los iconos delante), así que se mira dónde está y si se
    // pulsa. Un botón se activa al soltar encima del mismo botón que se pulsó; la barra de volumen, también al arrastrar.
    LaunchedEffect(p.controles) {
        if (!p.controles || !wallpaperSupported) return@LaunchedEffect
        val input = WidgetInput()
        while (isActive) {
            if (hidden.get()) { widgets.hover = null; widgets.pressed = null; input.step(null, false, emptyList()); delay(300); continue }
            val ptr = WallpaperHost.pointer(window, size.width, size.height)
            val at = ptr?.let { Offset(it.x, it.y) }
            val hit = at?.let { pt -> widgets.hits.firstOrNull { it.rect.contains(pt) } }
            widgets.hover = hit?.id
            val g = geo
            if (p.ctlAlPasar) {
                val near = at != null && g?.ctl?.inflate(g.H * .06f)?.contains(at) == true
                val target = if (near || input.pressed != null) 1f else 0f
                widgets.reveal = if (kotlin.math.abs(widgets.reveal - target) > .02f) widgets.reveal + (target - widgets.reveal) * .3f else target
            } else widgets.reveal = 1f
            input.step(at, ptr?.down == true, widgets.hits)?.let { (id, frac) -> actions.run(id, frac, widgets.durMs) }
            widgets.pressed = input.pressed
            delay(if (ptr == null) 60 else 16)
        }
    }
    val glow = min(1.2f, p.resplandor * (if (p.estilo == "malla") .7f else if (p.estilo == "onda") 1.3f else if (p.estilo == "eclipse") .8f else 1f))
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { size = it }) {
        // 1 · tu fondo de siempre (o el que elijas)
        // no lee el reloj: solo se vuelve a pintar si cambia el fondo de pantalla o el tamaño
        if (p.fondo == "actual") Canvas(Modifier.fillMaxSize()) { wallpaper?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt())) } }
        if (p.fondo == "caratula") {
            val blur = (6 + p.desenfoque * 60) * density
            Canvas(Modifier.fillMaxSize().graphicsLayer { renderEffect = BlurEffect(blur, blur, TileMode.Clamp) }) {
                // se mueve muy despacio: basta con repintarlo ~15 veces por segundo (difuminar la pantalla entera cuesta)
                slowFrame.longValue
                val img = scene.cover ?: return@Canvas
                val t = scene.vtime; val drift = if (p.movimiento) 1f else 0f; val s = 1.12f + .03f * sin(t * .05f) * drift
                val side = max(size.width, size.height) * s
                val ox = sin(t * .031f) * size.width * .025f * drift; val oy = cos(t * .027f) * size.height * .025f * drift
                drawSquare(img, androidx.compose.ui.geometry.Rect(Offset((size.width - side) / 2 + ox, (size.height - side) / 2 + oy), androidx.compose.ui.geometry.Size(side, side)))
            }
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = p.opacidad; compositingStrategy = if (p.opacidad < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }) {
            // 2 · detrás: sombra, luz del bajo, partículas, láseres, ondas
            Canvas(Modifier.fillMaxSize()) { frame.longValue; drawBack(scene, lyr.box) }
            // 3 · el brillo: el visualizador difuminado en la gráfica y sumado
            // solo un cuadrado alrededor del círculo, no la pantalla entera (la gráfica difumina mucho menos)
            val g0 = geo
            if (glow > 0f && g0 != null) {
                val r = (10f + 16f * p.resplandor) * density
                val half = g0.R * 2.9f + r * 3
                val box = with(LocalDensity.current) { (half * 2).toDp() }
                val ox = with(LocalDensity.current) { (g0.cx - half).toDp() }; val oy = with(LocalDensity.current) { (g0.cy - half).toDp() }
                Canvas(Modifier.offset(ox, oy).size(box).graphicsLayer { renderEffect = BlurEffect(r, r, TileMode.Decal); alpha = glow; compositingStrategy = CompositingStrategy.Offscreen }) {
                    frame.longValue; translate(half - g0.cx, half - g0.cy) { drawViz(scene, true) }
                }
            }
            // 4 · el visualizador, la carátula y la letra
            Canvas(Modifier.fillMaxSize()) {
                frame.longValue
                val t0 = System.nanoTime()
                drawViz(scene, false)
                drawFront(scene)
                with(lyr) { draw(scene, lyricTime, tm) }
                FrameTrace.draw(System.nanoTime() - t0)
            }
        }
        // 5 · la hora y los controles: solo se repintan cuando cambian (o con el bombo, si la hora late)
        if (p.reloj || p.controles) Canvas(Modifier.fillMaxSize()) {
            // la hora que late y el vinilo que gira necesitan cada cuadro; lo demás, solo cuando cambia
            if (p.relojLatido || (p.controles && p.ctlEstilo == "vinilo" && widgets.playing)) frame.longValue
            val g = geo ?: return@Canvas
            drawClock(scene, g, tm, widgets)
            drawControls(scene, g, tm, widgets, icons)
        }
    }
}

/** Lo que hacen los botones de los controles del escritorio. */
internal class WidgetActions(private val player: DesktopPlayerConnection, val liked: () -> Boolean, private val like: (Boolean) -> Unit) {
    fun run(id: String, frac: Float, durMs: Long) {
        when (id) {
            "prev" -> player.previous()
            "play" -> player.toggle()
            "next" -> player.next()
            "like" -> like(!liked())
            "shuffle" -> player.setShuffle(!player.shuffle.value)
            "repeat" -> player.cycleLoop()
            "seek" -> if (durMs > 0) player.seekTo((frac * durMs).toLong())
            "vol" -> player.setVolume(frac)
            else -> if (id.startsWith("q:")) id.removePrefix("q:").toIntOrNull()?.let(player::skipTo)
        }
    }
}

/** Tu fondo de pantalla ya colocado como lo coloca Windows en una imagen del tamaño del monitor. */
/** Tu fondo como lo ve Windows ([WallpaperHost.paintDesktop]); si no se puede, el archivo ajustado a mano. */
private fun desktopWallpaper(monitor: Monitor, f: File?, fit: String): ImageBitmap? =
    WallpaperHost.paintDesktop(monitor)?.let { px ->
        runCatching {
            org.jetbrains.skia.Bitmap().apply {
                allocPixels(org.jetbrains.skia.ImageInfo(monitor.w, monitor.h, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE))
                installPixels(px); setImmutable()
            }.asComposeImageBitmap()
        }.onFailure { Timber.w(it, "Fondo vivo: no se pudo usar el fondo pintado por Windows") }.getOrNull()
    } ?: f?.let { loadWallpaper(it, monitor.w, monitor.h, fit) }

/** Cambia si cambia el fondo (archivo, copia de Windows o forma de colocarlo). */
private fun wallpaperKey(f: File?, fit: String): String {
    val t = System.getenv("APPDATA")?.let { File(it, "Microsoft/Windows/Themes/TranscodedWallpaper") }
    return "${f?.path}|${f?.lastModified()}|${f?.length()}|${t?.lastModified()}|$fit"
}

private fun loadWallpaper(f: File, w: Int, h: Int, fit: String): ImageBitmap? = runCatching {
    val src = Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap()
    val out = ImageBitmap(w, h)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr,
        androidx.compose.ui.graphics.Canvas(out), androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat())) { drawWallpaperImage(src, fit) }
    out
}.onFailure { Timber.w(it, "Fondo vivo: no se pudo leer el fondo de pantalla") }.getOrNull()

/**
 * La hora o los controles solos, sin el fondo vivo (cada uno es una entrada aparte en Extras): una ventanita detrás de
 * los iconos del tamaño del widget, que pinta debajo su trozo de tu fondo de pantalla (así parece que va encima del
 * fondo) y solo se vuelve a pintar cuando cambia algo (el vinilo, mientras gira, ~30 veces por segundo).
 */
@Composable
private fun WidgetOnlyWindow(monitor: Monitor, clock: Boolean, player: DesktopPlayerConnection, actions: WidgetActions) {
    val settings by WallpaperStore.settings.collectAsState()
    val p by rememberUpdatedState(settings)
    val d = remember { runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat() }.getOrDefault(1f) }
    // solo el widget de esta ventana: el mismo sitio que con el fondo vivo, sin reservar el del otro
    val g = remember(settings, monitor) { geometry(monitor.w.toFloat(), monitor.h.toFloat(), d, if (clock) settings.copy(controles = false) else settings.copy(reloj = false)) }
    val c = (if (clock) g.clock else g.ctl) ?: return
    // el vinilo asoma por la izquierda de su sitio y las sombras salen un poco: margen
    val pad = g.H * .012f + (if (!clock && settings.ctlEstilo == "vinilo") c.height * .5f else 0f)
    val box = androidx.compose.ui.unit.IntRect(max(0, (c.left - pad).toInt()), max(0, (c.top - pad).toInt()), min(monitor.w, (c.right + pad).toInt() + 1), min(monitor.h, (c.bottom + pad).toInt() + 1))
    DisposableEffect(Unit) { onDispose { WallpaperHost.refreshWallpaper() } }
    key(box) {
        val state = rememberWindowState(position = WindowPosition((-32000).dp, (-32000).dp), size = DpSize(64.dp, 64.dp))
        Window(onCloseRequest = {}, state = state, title = if (clock) "Oto · Hora" else "Oto · Controles", undecorated = true, resizable = false, focusable = false, alwaysOnTop = false) {
            LaunchedEffect(window) {
                delay(150)
                val ok = WallpaperHost.attach(window, Monitor(monitor.x + box.left, monitor.y + box.top, box.width, box.height, false))
                if (!ok) window.isVisible = false
            }
            val st = remember { WidgetState() }
            val scene = remember { WallpaperScene().apply { audio = Spectrum() } }
            val tm = rememberTextMeasurer(cacheSize = 32)
            val local = remember(g, box) {
                val r = c.translate(-box.left.toFloat(), -box.top.toFloat())
                if (clock) g.copy(clock = r, ctl = null) else g.copy(ctl = r, clock = null)
            }
            var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(Unit) {
                var key = ""
                while (isActive) {
                    withContext(Dispatchers.IO) {
                        val f = WallpaperHost.currentWallpaperFile(); val fit = WallpaperHost.wallpaperFit()
                        val k = wallpaperKey(f, fit)
                        if (k != key) { key = k; wallpaper = desktopWallpaper(monitor, f, fit) }
                    }
                    delay(30_000)
                }
            }
            if (clock) {
                LaunchedEffect(settings.hora24, settings.segundos, settings.relojIdioma) { while (isActive) { st.tick(settings); delay(1000 - System.currentTimeMillis() % 1000 + 5) } }
                Canvas(Modifier.fillMaxSize().background(Color.Black)) {
                    wallpaper?.let { drawImage(it, srcOffset = androidx.compose.ui.unit.IntOffset(box.left, box.top), srcSize = IntSize(box.width, box.height), dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
                    scene.p = settings
                    drawClock(scene, local, tm, st)
                }
            } else WidgetControls(monitor, d, g, local, box, wallpaper, st, scene, tm, player, actions)
        }
    }
}

/** Los controles de la ventanita: lo mismo que en el fondo vivo (WallpaperContent), sin el visualizador. */
@Composable
private fun androidx.compose.ui.window.FrameWindowScope.WidgetControls(
    monitor: Monitor, d: Float, g: Geometry, local: Geometry, box: androidx.compose.ui.unit.IntRect, wallpaper: ImageBitmap?,
    st: WidgetState, scene: WallpaperScene, tm: androidx.compose.ui.text.TextMeasurer, player: DesktopPlayerConnection, actions: WidgetActions,
) {
    val settings by WallpaperStore.settings.collectAsState()
    val p by rememberUpdatedState(settings)
    val frame = remember { mutableLongStateOf(0L) }
    val icons = WidgetIcons(rememberVectorPainter(Icons.Rounded.SkipPrevious), rememberVectorPainter(Icons.Rounded.PlayArrow), rememberVectorPainter(Icons.Rounded.Pause),
        rememberVectorPainter(Icons.Rounded.SkipNext), rememberVectorPainter(Icons.Rounded.FavoriteBorder), rememberVectorPainter(Icons.Rounded.Favorite),
        rememberVectorPainter(Icons.Rounded.Shuffle), rememberVectorPainter(Icons.Rounded.Repeat), rememberVectorPainter(Icons.Rounded.RepeatOne),
        rememberVectorPainter(Icons.AutoMirrored.Rounded.VolumeUp))
    val np by player.state.collectAsState()
    val shuffle by player.shuffle.collectAsState()
    val loopMode by player.loopMode.collectAsState()
    val volume by player.volume.collectAsState()
    val queue by player.queue.collectAsState()
    LaunchedEffect(np, shuffle, loopMode, volume) {
        st.title = np.title; st.artist = np.artist; st.hasSong = np.id != null
        st.playing = np.playing && !np.buffering; st.durMs = np.durationMs
        st.shuffle = shuffle; st.loop = loopMode.ordinal; st.volume = volume
    }
    LaunchedEffect(Unit) { snapshotFlow { actions.liked() }.collect { st.liked = it } }
    LaunchedEffect(np.artwork) {
        val url = np.artwork
        val (img, cols) = if (url == null) null to null else withContext(Dispatchers.IO) {
            val i = loadImage(runCatching { artworkUrl(url, 544) }.getOrDefault(url)); i to i?.let(::coverColors)
        }
        scene.setCover(img, cols); st.cover = img
        // los colores de la carátula (opción «de la carátula») se asientan poco a poco en el fondo vivo: aquí, de golpe
        scene.p = p; scene.ensureGeometry(monitor.w.toFloat(), monitor.h.toFloat(), d); repeat(40) { scene.step(.1f) }
        frame.longValue++
    }
    LaunchedEffect(queue, np.index, settings.ctlEstilo) {
        if (settings.ctlEstilo != "vinilo") return@LaunchedEffect
        st.queue = queue.filter { it.index > np.index }.take(5).map { QItem(it.index, it.title, it.artist, it.artwork) }
        for (q in st.queue) {
            val art = q.art ?: continue
            if (art in st.thumbs) continue
            withContext(Dispatchers.IO) { loadImage(runCatching { artworkUrl(art, 120) }.getOrDefault(art)) }?.let { st.thumbs[art] = it }
        }
        st.thumbs.keys.retainAll(st.queue.mapNotNull { it.art }.toSet())
    }
    // la barra (4 veces por segundo mientras suena) y el vinilo girando (~30 cuadros por segundo)
    LaunchedEffect(Unit) {
        var last = System.nanoTime(); var lastPos = 0L
        while (isActive) {
            val spinning = st.playing && p.ctlEstilo == "vinilo" && p.ctlGiro
            delay(if (spinning) 33 else if (st.playing) 250 else 1000)
            val now = System.nanoTime(); val dt = ((now - last) / 1e9f).coerceIn(0f, .5f); last = now
            if (spinning) { st.spin = (st.spin + dt * 3.49f) % 6.2832f; frame.longValue++ }
            if (now - lastPos > 240_000_000L) { lastPos = now; st.posMs = player.positionMs.value }
        }
    }
    // el ratón, como en el fondo vivo
    var px by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(Unit) {
        val input = WidgetInput()
        while (isActive) {
            val ptr = WallpaperHost.pointer(window, px.width, px.height)
            val at = ptr?.let { Offset(it.x, it.y) }
            st.hover = at?.let { pt -> st.hits.firstOrNull { it.rect.contains(pt) } }?.id
            if (p.ctlAlPasar) {
                val near = at != null && local.ctl?.inflate(g.H * .06f)?.contains(at) == true
                val target = if (near || input.pressed != null) 1f else 0f
                st.reveal = if (kotlin.math.abs(st.reveal - target) > .02f) st.reveal + (target - st.reveal) * .3f else target
            } else st.reveal = 1f
            input.step(at, ptr?.down == true, st.hits)?.let { (id, frac) -> actions.run(id, frac, st.durMs) }
            st.pressed = input.pressed
            delay(if (ptr == null) 60 else 16)
        }
    }
    Canvas(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { px = it }) {
        frame.longValue
        wallpaper?.let { drawImage(it, srcOffset = androidx.compose.ui.unit.IntOffset(box.left, box.top), srcSize = IntSize(box.width, box.height), dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
        scene.p = settings; scene.playing = st.playing
        drawControls(scene, local, tm, st, icons)
    }
}

@Volatile private var lyricTime = 0f

private fun loadImage(url: String): ImageBitmap? = runCatching {
    val bytes = if (url.startsWith("http")) URI(url).toURL().openConnection().apply { connectTimeout = 6000; readTimeout = 8000 }.getInputStream().use { it.readBytes() }
        else File(URI(url)).readBytes()
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.onFailure { Timber.w(it, "Fondo vivo: no se pudo cargar la carátula") }.getOrNull()

private fun loadFile(f: File): ImageBitmap? = runCatching { Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap() }
    .onFailure { Timber.w(it, "Fondo vivo: no se pudo leer el fondo de pantalla") }.getOrNull()

/** Solo pruebas: letra LRC (línea a línea; las palabras se reparten por la línea) con `OTO_FONDO_DEMO_LRC`. */
private val demoLyrics: List<LLine>? by lazy {
    val f = System.getenv("OTO_FONDO_DEMO_LRC")?.let(::File)?.takeIf { it.isFile } ?: return@lazy null
    val rx = Regex("""\[(\d+):(\d+(?:\.\d+)?)\](.*)""")
    val raw = f.readLines().mapNotNull { rx.matchEntire(it.trim()) }.map { m -> (m.groupValues[1].toInt() * 60 + m.groupValues[2].toFloat()) to m.groupValues[3].trim() }
        .filter { it.second.isNotBlank() }
    raw.mapIndexed { i, (t, text) ->
        val end = raw.getOrNull(i + 1)?.first ?: (t + 4f)
        val ws = text.split(Regex("""\s+""")); val dur = (end - t).coerceAtMost(5f) * .8f
        LLine(t, end, ws.mapIndexed { k, w -> LWord(w, t + dur * k / ws.size) })
    }
}
