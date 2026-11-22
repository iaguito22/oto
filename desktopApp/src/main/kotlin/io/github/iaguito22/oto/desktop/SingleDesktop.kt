package io.github.iaguito22.oto.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.compose.ui.zIndex
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.account.*
import io.github.iaguito22.oto.desktop.playback.*
import io.github.iaguito22.oto.desktop.ui.*
import io.github.iaguito22.oto.importer.Importer
import io.github.iaguito22.oto.lyrics.LyricsQuery
import io.github.iaguito22.oto.lyrics.LyricsStore
import io.github.iaguito22.oto.ui.menu.MenuController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.WindowEvent
import kotlin.math.roundToInt

/** Interfaz elegida en Ajustes. Se lee al arrancar: el cambio se aplica al reiniciar Oto. */
internal object ShellMode {
    private val kv get() = OtoDirs.keyValue("modular_windows")
    val startedSingle: Boolean by lazy { single }
    var single: Boolean
        get() = kv.getString("shell_mode", "single") == "single"
        set(value) { kv.putString("shell_mode", if(value) "single" else "docking") }
}

/** Todo lo de dentro de la ventana se dibuja algo más grande, a la escala de Spotify. */
private const val SingleScale = 1.1f
/** Si hace falta, los laterales se pliegan para dejar sitio al dock y el panel central. */
private const val SingleMinWidth = 720
private const val SingleMinHeight = 460
/** Carátula y franja de controles de ventana en el mini más estrecho. */
private const val SingleMiniMinWidth = 96
private const val SingleMiniWidth = 720
/** Dock (76) + márgenes (6 + 6), escalado. */
private const val SingleMiniHeight = 84
private val DockHeight = 76.dp
// Relleno de desplazamiento, independiente de dónde termine colocado el dock.
private val FloatingScrollEnd = DockHeight + 24.dp
private val RailNarrow = 72.dp
private val Gap = 12.dp
private val CenterMin = 440.dp
/** Ancho con el que el dock entra entero (todo visible) dentro del panel central. */
/** 8 + 196 + 230 + 196 + 8: lo que pide el propio dock para que todo quepa con el título legible. */
private val DockFitWidth = 640.dp

private fun Rectangle.fitIn(work: DockRect): Rectangle {
    val w = minOf(width, work.width)
    val h = minOf(height, work.height)
    return Rectangle(x.coerceIn(work.x, work.x + work.width - w),
        y.coerceIn(work.y, work.y + work.height - h), w, h)
}

private fun restoredSingleBounds(kv: KeyValueFile): Pair<WindowPosition, DpSize> {
    val x = kv.getLong("single_x", Long.MIN_VALUE)
    val y = kv.getLong("single_y", Long.MIN_VALUE)
    val area = desktopAreas().firstOrNull { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height }
    val work = area ?: desktopArea(java.awt.Point())
    val w = kv.getLong("single_w", 1360L).coerceIn(SingleMinWidth.toLong(), work.width.coerceAtLeast(SingleMinWidth).toLong()).toInt()
    val h = kv.getLong("single_h", 860L).coerceIn(SingleMinHeight.toLong(), work.height.coerceAtLeast(SingleMinHeight).toLong()).toInt()
    val position = if (area == null) WindowPosition.Aligned(Alignment.Center) else
        WindowPosition(minOf(x.toInt(), work.x + (work.width - w).coerceAtLeast(0)).dp,
            minOf(y.toInt(), work.y + (work.height - h).coerceAtLeast(0)).dp)
    return position to DpSize(w.dp, h.dp)
}

/** Tinte y borde de las islas sobre el fondo de carátula. */
private val Border = Color(0x1CE5E5E5)
private val IslandRadius = 28.dp
private val IslandShape = RoundedCornerShape(IslandRadius)
private val PanelColor = Color(0xBF1A1A1A)

private fun Modifier.island() = clip(IslandShape).background(PanelColor).border(1.dp, Border, IslandShape)

/** Sube al salir el puntero de la ventana o al perder el foco: los hovers se reinician y no se quedan encendidos. */
internal val LocalHoverEpoch = compositionLocalOf { 0 }
/** Verdadero tras usar el teclado (Tab, flechas, Intro); falso al pulsar con el ratón. El anillo de foco solo se ve con teclado. */
private val LocalKeyboardNav = compositionLocalOf { false }

/** filter: saturate(2) brightness(0.7) del fondo del tema. */
private val BackdropFilter = ColorFilter.colorMatrix(ColorMatrix().apply {
    setToSaturation(2f)
    timesAssign(ColorMatrix(floatArrayOf(.7f, 0f, 0f, 0f, 0f, 0f, .7f, 0f, 0f, 0f, 0f, 0f, .7f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
})

/** Biblioteca, contenido, ahora suena y dock en un mismo marco. La letra y las hojas se abren dentro de la ventana. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ApplicationScope.SingleDesktop(player: DesktopPlayerConnection, prefs: Prefs, menu: MenuController,
    importer: Importer, account: Account, cloud: Cloud, login: BrowserLogin, downloads: DesktopDownloads,
    lyricsStore: LyricsStore, quit: () -> Unit) {
    Window(onCloseRequest = quit, visible = false, title = "Oto · Sesión",
        state = rememberWindowState(width = 1.dp, height = 1.dp), undecorated = true) { }
    DisposableEffect(login) { onDispose { login.cancel() } }
    val kv = remember { OtoDirs.keyValue("modular_windows") }
    val uiScope = rememberCoroutineScope()
    var deviceRevision by remember { mutableIntStateOf(0) }
    val devices = remember(deviceRevision) { DeviceConnection(player, prefs, account, uiScope) }
    DisposableEffect(devices) { onDispose { devices.close() } }
    val initial = remember { restoredSingleBounds(kv) }
    val state = rememberWindowState(position = initial.first, size = initial.second)
    var miniShortcut by remember { mutableIntStateOf(0) }
    var miniReturn by remember { mutableStateOf<Rectangle?>(null) }
    val miniAnchor = remember { Point() }
    var miniFromMaximized by remember { mutableStateOf(false) }
    var miniExpandRequest by remember { mutableIntStateOf(0) }
    var miniTransition by remember { mutableStateOf(false) }
    val miniFade = remember { Animatable(0f) }
    val mini = miniReturn != null
    fun expandMini() {
        if (miniReturn != null && !miniTransition) miniExpandRequest++
    }
    val customFrame = remember { System.getProperty("os.name").startsWith("Windows") }
    val localNow by player.state.collectAsState()
    val mirrored by devices.mirror.state.collectAsState()
    val remote by devices.link.remote.collectAsState()
    val now = mirrored?.now ?: localNow
    val queue by player.queue.collectAsState()
    val source by player.source.collectAsState()
    val shuffle by player.shuffle.collectAsState()
    val loop by player.loop.collectAsState()
    val loopMode by player.loopMode.collectAsState()
    val djActive by player.djActive.collectAsState()
    val djMode by player.djMode.collectAsState()
    val djTanda by player.djTanda.collectAsState()
    val djFullOnce by player.djFullOnce.collectAsState()
    val djNextUp by player.djNextUp.collectAsState()
    val nextMix by player.nextMix.collectAsState()
    val volume by player.volume.collectAsState()
    val currentQuality by player.currentQuality.collectAsState()
    val mixingNow by player.mixing.collectAsState()
    val qualityLabel = currentQuality?.let { q -> listOfNotNull(q.codec.uppercase(), q.bitrate?.let { "${(it + 500) / 1000} kbps" },
        q.sampleRate?.let { if (it % 1000 == 0) "${it / 1000} kHz" else "${it / 1000f} kHz" }).joinToString(" · ") }
    val notices = remember { SnackbarHostState() }
    val recentSongs by prefs.history.collectAsState(initial = emptyList())
    val likedIds by prefs.likedIds.collectAsState(initial = emptySet())
    val likedList by prefs.likedList.collectAsState(initial = LocalPlaylist(LIKED_ID, "Me gusta", emptyList()))
    val playlists by prefs.playlists.collectAsState(initial = emptyList())
    val flipped by prefs.sortFlipped.collectAsState(initial = emptySet())
    val session by account.session.collectAsState()
    val menuState by menu.state.collectAsState()
    val isLiked = now.id != null && now.id in likedIds
    val saved = isLiked || now.id != null && playlists.any { list -> list.songs.any { it.id == now.id } }
    var shown by remember { mutableStateOf(true) }
    val drawing = shown && !state.isMinimized
    val ramSaver by prefs.ramSaver.collectAsState(initial = false)
    LaunchedEffect(ramSaver) { RamSaver.apply(ramSaver) }
    LaunchedEffect(ramSaver, drawing) { if (ramSaver && !drawing) RamSaver.whileHidden() }
    var artworkBackground by remember { mutableStateOf(kv.getString("artwork_background") != "false") }
    var backdropOpacity by remember { mutableFloatStateOf(kv.getString("artwork_opacity")?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f) }
    var lyricsScale by remember { mutableFloatStateOf(kv.getString("lyrics_scale")?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(.8f, 2f) ?: 1.2f) }
    fun changeLyricsScale(delta: Float) {
        lyricsScale = (lyricsScale + delta).coerceIn(.8f, 2f)
        kv.putString("lyrics_scale", lyricsScale.toString())
    }
    var backdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    var palette by remember { mutableStateOf(ArtworkPalette()) }
    LaunchedEffect(now.artwork, drawing, artworkBackground, ramSaver) {
        if (drawing) {
            val (image, colors) = ArtworkLoader.load(now.artwork)
            palette = colors
            backdrop = if (artworkBackground) image?.let { blurredBackdrop(it) } else null
        } else if (ramSaver) backdrop = null
    }
    val accent = palette.primary
    val coverBeat by prefs.coverBeat.collectAsState(initial = true)
    var lyricsOpen by remember { mutableStateOf(false) }
    var devicesOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Heard?>(null) }
    val sheetOpen = devicesOpen || saving != null
    val searchFocus = remember { FocusRequester() }
    var searchRequest by remember { mutableStateOf(false) }
    val workspaceFocus = remember { FocusRequester() }
    var restoreWorkspaceFocus by remember { mutableStateOf(false) }
    LaunchedEffect(sheetOpen, lyricsOpen) {
        if (!sheetOpen && !lyricsOpen && restoreWorkspaceFocus) {
            workspaceFocus.restoreFocusedChild()
            restoreWorkspaceFocus = false
        }
    }
    fun keepWorkspaceFocus() {
        if (!sheetOpen && !lyricsOpen) restoreWorkspaceFocus = workspaceFocus.saveFocusedChild()
    }
    var recentsOpen by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var recentRowsFocused by remember { mutableStateOf(false) }
    LaunchedEffect(recentsOpen, searchFocused, recentRowsFocused) {
        if (recentsOpen && !searchFocused && !recentRowsFocused) {
            delay(150)
            recentsOpen = false
        }
    }
    var backRequest by remember { mutableIntStateOf(0) }
    var jump by remember { mutableStateOf(LibraryJump()) }
    var navRequest by remember { mutableStateOf(0 to 0) }
    var activeLocal by remember { mutableStateOf<String?>(null) }
    var activeSection by remember { mutableStateOf(0) }
    // Paneles laterales: abiertos o plegados, con su ancho elegido arrastrando el borde.
    var rightOpen by remember { mutableStateOf(kv.getString("single_right") != "false") }
    var libraryWide by remember { mutableStateOf(kv.getString("single_lib") != "narrow") }
    var libWidth by remember { mutableStateOf((kv.getString("single_lib_w")?.toFloatOrNull() ?: 300f).coerceIn(240f, 420f).dp) }
    /** Mientras se arrastra un borde, los paneles y el dock siguen al puntero sin resorte. */
    var draggingPanel by remember { mutableStateOf(false) }
    var rightWidth by remember { mutableStateOf((kv.getString("single_right_w")?.toFloatOrNull() ?: 340f).coerceIn(280f, 480f).dp) }
    fun saveLibrary() {
        kv.putString("single_lib", if (libraryWide) "wide" else "narrow")
        kv.putString("single_lib_w", libWidth.value.toString())
    }
    fun saveRight() {
        kv.putString("single_right", rightOpen.toString())
        kv.putString("single_right_w", rightWidth.value.toString())
    }
    fun goSection(i: Int) {
        expandMini()
        lyricsOpen = false
        devicesOpen = false
        saving = null
        recentsOpen = false
        navRequest = (navRequest.first + 1) to i
    }
    var recentLists by remember { mutableStateOf(kv.getString("single_played_lists")?.split('\n')?.filter { it.isNotBlank() }.orEmpty()) }
    fun touchList(id: String?) {
        if (id == null || id == LIKED_ID || recentLists.firstOrNull() == id) return
        recentLists = (listOf(id) + recentLists.filter { it != id }).take(60)
        kv.putString("single_played_lists", recentLists.joinToString("\n"))
    }
    LaunchedEffect(source?.local, localNow.id, localNow.playing, localNow.buffering, playlists) {
        if (!localNow.playing || localNow.buffering) return@LaunchedEffect
        val list = playlists.firstOrNull { it.id == source?.local } ?: return@LaunchedEffect
        if (list.songs.any { it.id == localNow.id }) touchList(list.id)
    }
    fun openList(id: String) {
        expandMini()
        lyricsOpen = false
        recentsOpen = false
        jump = LibraryJump(jump.version + 1, local = id)
    }
    fun playList(list: LocalPlaylist) {
        if (list.songs.isEmpty()) return
        // Igual que el botón de la vista de lista: con el orden invertido se reproduce la lista invertida.
        val shown = if (list.id in flipped) list.copy(songs = list.songs.reversed()) else list
        player.playLocal(shown, null, (list.id == LIKED_ID) != (list.id in flipped))
    }
    val playingSource = if (mirrored != null) mirrored?.source else source
    val canOpenSource = playingSource?.let { it.local != null || it.item != null || it.kind in listOf("DJ", "Radio") } == true
    fun showLibrary() {
        expandMini()
        lyricsOpen = false
        recentsOpen = false
    }
    fun openArtist(name: String, id: String?) {
        showLibrary()
        if (name.isNotBlank()) jump = LibraryJump(jump.version + 1, artistId = id.orEmpty(), artistName = name)
    }
    fun openSource() {
        val src = playingSource ?: return
        val localId = src.local
        when {
            src.kind == "DJ" -> goSection(6)
            localId != null -> openList(localId)
            src.item != null -> {
                showLibrary()
                jump = LibraryJump(jump.version + 1, item = src.item)
            }
            src.kind == "Radio" -> { showLibrary(); menu.onQuery(src.title) }
        }
    }
    fun openTrack() {
        if (now.id == null) return
        showLibrary()
        menu.onQuery(listOf(now.title, now.artist).filter(String::isNotBlank).joinToString(" "))
    }
    fun heard(): Heard? = now.id?.let { Heard(it, now.title, now.artist, now.artwork, now.artists.firstOrNull()?.second) }
    fun mobile(action: String, value: Any? = null, key: String = "value", onSent: () -> Unit = {}) {
        val link = devices.link
        uiScope.launch {
            try {
                link.command(org.json.JSONObject().put("action", action).apply { if (value != null) put(key, value) })
                onSent()
            } catch (_: TimeoutCancellationException) {
                notices.showSnackbar("El móvil no ha respondido")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                timber.log.Timber.w(error, "No se pudo enviar %s al móvil", action)
                notices.showSnackbar("No se pudo controlar el móvil")
            }
        }
    }
    fun playPause() { if (mirrored != null) mobile("toggle") else player.toggle() }
    fun previous() { if (mirrored != null) mobile("previous") else player.previous() }
    fun swipePrevious() {
        val remote = mirrored
        if (remote == null) player.previousTrack()
        else if (remote.now.index > 0) mobile("skip", remote.now.index - 1, key = "index")
    }
    fun next() { if (mirrored != null) mobile("next") else player.next() }
    fun seek(ms: Long) {
        if (mirrored != null) {
            val mirror = devices.mirror
            mobile("seek", ms, "position") { if (mirror.state.value?.now?.id == now.id) mirror.jump(ms) }
        } else player.seekTo(ms)
    }
    var mobileVolume by remember(devices, mirrored != null) { mutableStateOf<Float?>(null) }
    val remoteLevel = remote?.optDouble("volume", .5)?.toFloat()?.coerceIn(0f, 1f) ?: .5f
    val level = if (mirrored == null) volume else mobileVolume ?: remoteLevel
    val currentMirror = mirrored
    LaunchedEffect(mobileVolume, devices) {
        val target = mobileVolume ?: return@LaunchedEffect
        delay(110)
        mobile("volume", target)
        delay(1000)
        mobileVolume = null
    }
    fun setVolume(value: Float) {
        if (mirrored != null) mobileVolume = value.coerceIn(0f, 1f) else player.setVolume(value)
    }
    fun adjustVolume(delta: Float) {
        if (mirrored != null) setVolume((mobileVolume ?: remoteLevel) + delta) else player.adjustVolume(delta)
    }
    // Pulsar el altavoz silencia; volver a pulsarlo recupera el volumen de antes.
    // Cada salida (este PC o el móvil seguido) recuerda su propio volumen.
    fun toggleMute() {
        val key = if (mirrored == null) "single_unmute" else "single_unmute_mobile"
        if (level > 0f) {
            kv.putString(key, level.toString())
            setVolume(0f)
        } else setVolume(kv.getString(key)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(1f) ?: .5f)
    }
    fun openDevices() {
        keepWorkspaceFocus()
        expandMini()
        saving = null
        lyricsOpen = false
        devicesOpen = true
    }
    fun openSave(track: Heard) {
        keepWorkspaceFocus()
        expandMini()
        devicesOpen = false
        lyricsOpen = false
        saving = track
    }
    fun closePanel(): Boolean = when {
        devicesOpen -> { devicesOpen = false; true }
        saving != null -> { saving = null; true }
        lyricsOpen -> { lyricsOpen = false; true }
        recentsOpen -> { recentsOpen = false; true }
        else -> false
    }
    fun goBack() { if (!closePanel()) backRequest++ }
    fun toggleLyrics() {
        keepWorkspaceFocus()
        expandMini()
        recentsOpen = false
        lyricsOpen = !lyricsOpen
    }
    fun openSearch() {
        restoreWorkspaceFocus = false
        expandMini()
        lyricsOpen = false
        searchRequest = true
    }
    fun shortcuts(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        if (devicesOpen || saving != null) return e.key == Key.Escape && closePanel()
        return when {
            e.key == Key.Escape -> closePanel()
            e.isAltPressed && e.key == Key.DirectionLeft -> { goBack(); true }
            e.isCtrlPressed && e.key == Key.L -> { toggleLyrics(); true }
            e.isCtrlPressed && (e.key == Key.K || e.key == Key.F) -> { openSearch(); true }
            e.isCtrlPressed && e.key == Key.B -> { heard()?.let(::openSave); true }
            e.isCtrlPressed && e.isShiftPressed && e.key == Key.M -> { miniShortcut++; true }
            e.key == Key.MediaPlayPause -> { playPause(); true }
            e.isCtrlPressed && e.key == Key.DirectionRight -> { next(); true }
            e.isCtrlPressed && e.key == Key.DirectionLeft -> { previous(); true }
            e.isCtrlPressed && e.key == Key.DirectionUp -> { adjustVolume(.04f); true }
            e.isCtrlPressed && e.key == Key.DirectionDown -> { adjustVolume(-.04f); true }
            else -> false
        }
    }
    // Cerrar la ventana la lleva a la bandeja; Salir (bandeja) cierra Oto. Sin bandeja ni atajo, cerrar sale.
    var shortcutIssue by remember { mutableStateOf<String?>(null) }
    var shortcut by remember { mutableStateOf(kv.getString("shortcut") ?: "CTRL + ALT + O") }
    var revealRequest by remember { mutableIntStateOf(0) }
    fun show() { shown = true; state.isMinimized = false; revealRequest++ }
    fun toggleShown(force: Boolean = false) { if (force || !shown || state.isMinimized) show() else shown = false }
    DisposableEffect(shortcut) {
        val access = DesktopAccess(shortcut) { force -> java.awt.EventQueue.invokeLater { toggleShown(force) } }
        shortcutIssue = access.issue
        onDispose { access.close() }
    }
    OtoTray(toggle = { toggleShown() }, player = { show() }, library = { show(); goSection(0) },
        lyrics = { show(); expandMini(); devicesOpen = false; saving = null; lyricsOpen = true },
        queue = {
            show(); expandMini(); devicesOpen = false; saving = null; lyricsOpen = false
            rightOpen = true
            saveRight()
        }, quit = quit,
        playing = now.playing, hasSong = now.id != null, previous = ::previous, playPause = ::playPause, next = ::next,
        volume = level, setVolume = ::setVolume, title = now.title, artist = now.artist, artwork = now.artwork)
    Window(onCloseRequest = { if (shouldQuitWhenNoTray(java.awt.SystemTray.isSupported(), supportsDesktopShortcut, shortcutIssue)) quit() else shown = false },
        state = state, visible = shown, title = "Oto", icon = painterResource("oto-icon.png"),
        undecorated = customFrame, onKeyEvent = ::shortcuts) {
        var nativeFrame by remember(window) { mutableStateOf(false) }
        val caption = remember(window) { SingleCaptionZones() }
        SideEffect {
            caption.suspended = sheetOpen
            // En el mini solo se cambia el ancho (Windows no ofrece los bordes de arriba y abajo).
            caption.fixedHeight = mini
            caption.onMiniDoubleClick = if (mini && !sheetOpen) ::expandMini else null
        }
        LaunchedEffect(shown, revealRequest) {
            if (shown) {
                if (customFrame) {
                    kotlinx.coroutines.withTimeoutOrNull(5000) {
                        while (!nativeFrame) {
                            if (window.isShowing) nativeFrame = enableSingleWindowFrame(window, caption)
                            if (!nativeFrame) delay(50)
                        }
                    }
                    if (nativeFrame) window.undecoratedResizerThickness = 0.dp
                }
                window.toFront()
                window.requestFocus()
            }
        }
        DisposableEffect(window) {
            if (AppIcon.windowImages.isNotEmpty()) window.iconImages = AppIcon.windowImages
            // Fondo oscuro en AWT: ningún hueco del área cliente se ve blanco mientras Compose pinta.
            window.background = java.awt.Color(6, 6, 7)
            onDispose { }
        }
        LaunchedEffect(mini, nativeFrame) {
            window.minimumSize = if (mini) Dimension(SingleMiniMinWidth, SingleMiniHeight) else Dimension(SingleMinWidth, SingleMinHeight)
            if (nativeFrame) setSnappable(window, true)
        }
        LaunchedEffect(window, nativeFrame, shown, mini, state.size, state.position, state.placement, window.graphicsConfiguration) {
            if (!nativeFrame || !shown) return@LaunchedEffect
            val rounded = !isZoomed(window) && !isArranged(window) && state.placement == WindowPlacement.Floating
            // Los radios comparten centro con el panel interior cuando hay un margen completo.
            val radius = if (mini) 18.dp else IslandRadius + Gap
            setSingleWindowRounded(window, if (rounded) (radius.value * SingleScale * windowScale(window)).roundToInt() else null)
        }
        fun maximized() = isZoomed(window) || state.placement == WindowPlacement.Maximized
        fun maximize() {
            if (!nativeFrame || !toggleSingleWindowMaximized(window)) {
                state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
            }
        }
        val dockAnchor = remember { Point() }
        suspend fun hideMiniLayout() {
            miniFade.animateTo(1f, tween(95, easing = FastOutSlowInEasing))
            // Presenta el velo antes de que DWM cambie la superficie de la ventana.
            withFrameNanos { }
        }
        fun switchMiniLayout(target: Rectangle, restore: Rectangle?) {
            // Redimensionar la superficie de Skia en cada fotograma causa los saltos.
            window.bounds = target
            miniReturn = restore
        }
        suspend fun revealMiniLayout() {
            repeat(2) { withFrameNanos { } }
            miniFade.animateTo(0f, tween(145, easing = FastOutSlowInEasing))
        }
        LaunchedEffect(miniExpandRequest) {
            if (miniExpandRequest == 0 || miniTransition) return@LaunchedEffect
            val previous = miniReturn ?: return@LaunchedEffect
            miniTransition = true
            try {
                hideMiniLayout()
                val location = window.location
                val work = desktopArea(Point(location.x + window.width / 2, location.y + window.height / 2))
                val restored = Rectangle(previous.x + location.x - miniAnchor.x, previous.y + location.y - miniAnchor.y,
                    previous.width, previous.height)
                switchMiniLayout(restored.fitIn(work), restore = null)
                if (miniFromMaximized && !maximized()) {
                    maximize()
                    kotlinx.coroutines.withTimeoutOrNull(1200) { while (!maximized()) delay(25) }
                }
                miniFromMaximized = false
                revealMiniLayout()
            } finally { miniTransition = false }
        }
        // Si Windows coloca el mini (Snap o maximizar), deja de ser mini.
        LaunchedEffect(mini, miniTransition, state.size, state.position, state.placement) {
            if (!mini || miniTransition) return@LaunchedEffect
            delay(150)
            if (miniReturn != null && (maximized() || isArranged(window) || state.placement != WindowPlacement.Floating)) {
                miniReturn = null
                miniFromMaximized = false
            }
        }
        LaunchedEffect(mini, searchRequest) {
            if (searchRequest && !mini) {
                withFrameNanos { }
                searchFocus.requestFocus()
                recentsOpen = menu.state.value.query.isBlank()
                searchRequest = false
            }
        }
        LaunchedEffect(window) {
            snapshotFlow { Triple(state.position, state.size, state.placement to (state.isMinimized || miniReturn != null || miniTransition)) }
                .collectLatest { (pos, size, mode) ->
                    if (pos !is WindowPosition.Absolute || mode.first != WindowPlacement.Floating || mode.second) return@collectLatest
                    delay(400)
                    if (isArranged(window) || isZoomed(window)) return@collectLatest
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        kv.putLong("single_x", pos.x.value.toLong())
                        kv.putLong("single_y", pos.y.value.toLong())
                        kv.putLong("single_w", size.width.value.toLong())
                        kv.putLong("single_h", size.height.value.toLong())
                    }
                }
        }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        var hoverEpoch by remember { mutableIntStateOf(0) }
        var keyboardNav by remember { mutableStateOf(false) }
        fun toggleMini() {
            if (miniTransition) return
            if (miniReturn != null) { expandMini(); return }
            miniTransition = true
            closePanel()
            // La vista central queda oculta: que el foco no se quede en una fila invisible.
            focusManager.clearFocus()
            uiScope.launch {
                hideMiniLayout()
                // Maximizada o ajustada: primero vuelve a su tamaño flotante y desde ahí se encoge.
                val wasMaximized = maximized()
                if (wasMaximized) {
                    maximize()
                    kotlinx.coroutines.withTimeoutOrNull(1200) { while (maximized()) delay(30) }
                    delay(60)
                }
                val bounds = window.bounds
                val floating = Rectangle(bounds)
                if (isArranged(window)) floating.setSize(maxOf(bounds.width, 1100), maxOf(bounds.height, 720))
                miniFromMaximized = wasMaximized
                window.minimumSize = Dimension(SingleMiniMinWidth, SingleMiniHeight)
                val width = minOf(SingleMiniWidth, bounds.width)
                val anchorX = dockAnchor.x.takeIf { it in 1..bounds.width } ?: bounds.width / 2
                val anchorY = dockAnchor.y.takeIf { it in 1..bounds.height } ?: bounds.height
                val target = Rectangle(bounds.x + anchorX - width / 2, bounds.y + anchorY - SingleMiniHeight,
                    width, SingleMiniHeight).fitIn(desktopArea(Point(bounds.x + anchorX, bounds.y + anchorY - 1)))
                miniAnchor.setLocation(target.location)
                switchMiniLayout(target, restore = floating)
                revealMiniLayout()
            }.invokeOnCompletion { miniTransition = false }
        }
        LaunchedEffect(miniShortcut) { if (miniShortcut > 0) toggleMini() }
        // Fijar: el mini se queda encima de las demás ventanas; la ventana grande nunca.
        var miniPinned by remember { mutableStateOf(kv.getString("mini_pin") == "true") }
        LaunchedEffect(mini, miniPinned) { window.isAlwaysOnTop = mini && miniPinned }
        TaskbarTransport(window, shown && nativeFrame, TaskbarPlayback(now.id != null, now.playing, ::previous, ::playPause, ::next))
        SmoothWheel.Driver(window)
        MaterialTheme(colorScheme = darkColorScheme(primary = accent, onPrimary = Color(0xFF101010), primaryContainer = Color(0xFF303630), secondary = accent, secondaryContainer = Color(0xFF303630), tertiary = accent, background = Color(0xFF0B0B0B), surface = Color(0xFF121212), surfaceVariant = Color(0xFF2A2A2A), surfaceTint = Color.Transparent, surfaceContainerLowest = Color(0xFF0E0E0E), surfaceContainerLow = Color(0xFF151515), surfaceContainer = Color(0xFF1A1A1A), surfaceContainerHigh = Color(0xFF202020), surfaceContainerHighest = Color(0xFF282828)), typography = OtoTypography) {
            val baseDensity = LocalDensity.current
            val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(windowFocused) { if (!windowFocused) hoverEpoch++ }
            CompositionLocalProvider(LocalContentColor provides Color(0xFFEDEDED), LocalHoverEpoch provides hoverEpoch, LocalKeyboardNav provides keyboardNav,
                LocalDensity provides Density(baseDensity.density * SingleScale, baseDensity.fontScale)) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    val fade = miniFade.value
                    if (fade < 1f) drawContent()
                    if (fade > 0f) drawRect(Color(0xFF060607).copy(alpha = fade))
                }.background(Color(0xFF060607))
                    .onPointerEvent(PointerEventType.Press) { keyboardNav = false; if (it.buttons.isBackPressed) goBack() }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key in listOf(Key.Tab, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.Enter, Key.Spacebar)) keyboardNav = true
                        false
                    }
                    .onPointerEvent(PointerEventType.Exit) { hoverEpoch++ }) {
                    val backgroundLayer = rememberGraphicsLayer()
                    Box(Modifier.matchParentSize().drawWithContent {
                        backgroundLayer.record {
                            drawRect(Color(0xFF060607))
                            this@drawWithContent.drawContent()
                            if (backdrop == null) {
                                // Resplandor de reserva sin carátula (como .Root__top-container::before del tema).
                                drawRect(Brush.radialGradient(listOf(accent.copy(alpha = .16f), Color.Transparent), center = Offset(size.width * .14f, size.height * .18f), radius = size.maxDimension * .55f))
                                drawRect(Brush.radialGradient(listOf(accent.copy(alpha = .10f), Color.Transparent), center = Offset(size.width * .86f, size.height * .82f), radius = size.maxDimension * .6f))
                            }
                        }
                        drawLayer(backgroundLayer)
                    }) {
                        if (drawing) Crossfade(backdrop, animationSpec = tween(560), label = "carátula", modifier = Modifier.graphicsLayer { alpha = backdropOpacity }) { image ->
                            if (image != null) Box(Modifier.fillMaxSize()) {
                                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = BackdropFilter)
                                Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(Color(0x260E0E0E), Color(0x8C0E0E0E)), radius = 1400f)))
                            }
                        }
                    }
                    val inset = if (mini) 0.dp else Gap
                    BoxWithConstraints(Modifier.fillMaxSize().padding(inset).focusRequester(workspaceFocus).focusGroup()) {
                        val totalWidth = maxWidth
                        // El centro conserva su espacio. La biblioteca cede antes que el panel de reproducción.
                        val sideRoom = totalWidth - Gap * 2 - CenterMin
                        val bothFit = sideRoom >= 520.dp
                        val rightShown = rightOpen && sideRoom >= RailNarrow + 280.dp
                        val libShown = libraryWide && sideRoom >= 240.dp + (if (rightShown) 280.dp else RailNarrow)
                        val rightEff = if (rightShown) minOf(rightWidth, sideRoom - (if (libShown) 240.dp else RailNarrow)) else RailNarrow
                        val libEff = if (libShown) minOf(libWidth, sideRoom - rightEff) else RailNarrow
                        // Al cambiar el tamaño de la ventana los paneles siguen al instante; el resorte es para plegar y desplegar.
                        val lastTotal = remember { object { var value = totalWidth } }
                        // Plegar o desplegar arrastrando conserva el resorte; el resto del arrastre sigue al puntero.
                        val lastShown = remember { object { var value = libShown to rightShown } }
                        val foldChanged = lastShown.value != (libShown to rightShown)
                        SideEffect { lastShown.value = libShown to rightShown }
                        val resizing = lastTotal.value != totalWidth
                        SideEffect { lastTotal.value = totalWidth }
                        val panelSpec: androidx.compose.animation.core.AnimationSpec<Dp> = if (resizing || draggingPanel && !foldChanged) androidx.compose.animation.core.snap() else spring(.85f, 420f)
                        // El resorte puede rebotar por debajo de 0 al plegar: nunca anchos negativos.
                        val animatedLib = animateDpAsState(libEff, panelSpec, label = "biblioteca").value.coerceAtLeast(0.dp)
                        val animatedRight = animateDpAsState(rightEff, panelSpec, label = "ahoraSuena").value.coerceAtLeast(0.dp)
                        // El dock flota encima: dentro del panel central si cabe entero; si no, a lo ancho de la ventana.
                        // El dock va centrado sobre el panel central con su ancho. Si no cabe entero, ocupa solo
                        // el mínimo que necesita (sin esconder nada) y se sale por los lados lo justo.
                        val centerWidth = totalWidth - animatedLib - animatedRight - Gap * 2
                        val regularDockWidth = maxOf(centerWidth - 24.dp, DockFitWidth).coerceAtMost(totalWidth)
                        val dockWidth = if (mini) totalWidth else regularDockWidth
                        val centerMiddle = animatedLib + Gap + centerWidth / 2
                        val regularDockX = (centerMiddle - regularDockWidth / 2).coerceIn(0.dp, (totalWidth - regularDockWidth).coerceAtLeast(0.dp))
                        val dockX = if (mini) 0.dp else regularDockX
                        val dockLift = if (mini) 0.dp else 12.dp
                        SideEffect {
                            if (!mini) dockAnchor.setLocation(
                                ((dockX + dockWidth / 2 + inset).value * SingleScale).roundToInt(),
                                ((maxHeight - dockLift + inset).value * SingleScale).roundToInt())
                        }
                        val dockBackdrop = with(LocalDensity.current) { Offset(dockX.toPx(), (maxHeight - DockHeight - dockLift).toPx()) }
                        // Lo que hay detrás del dock se graba para su cristal desenfocado.
                        val workspaceLayer = rememberGraphicsLayer()
                        Column(Modifier.fillMaxSize().drawWithContent {
                            val backgroundInset = inset.toPx()
                            workspaceLayer.record {
                                translate(-backgroundInset, -backgroundInset) { drawLayer(backgroundLayer) }
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(workspaceLayer)
                        }, verticalArrangement = Arrangement.spacedBy(Gap)) {
                            // Ambos extremos reservan el mismo ancho para mantener centrado el buscador.
                            if (!mini) Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(116.dp)) { Pill {
                                    TopButton(MobileIcons.settings, "Ajustes") { goSection(4) }
                                    val letter = (session?.name?.firstOrNull() ?: 'O').uppercaseChar().toString()
                                    val profileSource = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
                                    val profileHover by profileSource.collectIsHoveredAsState()
                                    Box(Modifier.size(32.dp).clip(CircleShape)
                                        .background(if (session == null) Color.White.copy(if (profileHover) .14f else .07f) else accent.copy(if (profileHover) .32f else .22f))
                                        .pointerHoverIcon(PointerIcon.Hand).clickable(profileSource, null, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Cuenta") { goSection(5) },
                                        contentAlignment = Alignment.Center) {
                                        if (session == null) Icon(Icons.Rounded.PersonOutline, "Cuenta", Modifier.size(18.dp), tint = Color.White.copy(.8f))
                                        else Text(letter, style = OtoType.rowTitle, color = Color.White)
                                    }
                                    DesktopUpdateButton(accent, size = 32.dp)
                                } }
                                SingleDragArea(Modifier.weight(1f).fillMaxHeight(), customFrame, nativeFrame, caption, 0, ::maximize)
                                SingleDragArea(Modifier.width(54.dp).fillMaxHeight(), customFrame, nativeFrame, caption, 2, ::maximize)
                                val searchWidth = (totalWidth * .38f).coerceIn(220.dp, 520.dp)
                                Pill(Modifier.width(searchWidth)) {
                                    TopButton(MobileIcons.search, "Buscar o ver recientes", onClick = ::openSearch)
                                    BasicTextField(menuState.query, {
                                        lyricsOpen = false
                                        recentsOpen = false
                                        menu.onQuery(it)
                                    },
                                        Modifier.weight(1f).focusRequester(searchFocus)
                                            .semantics { contentDescription = "Buscar música" }
                                            .onFocusChanged {
                                                searchFocused = it.isFocused
                                                if (it.isFocused && menuState.query.isBlank()) recentsOpen = true
                                            }
                                            .onPreviewKeyEvent { e ->
                                                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && menuState.query.isNotBlank()) {
                                                    menu.onQuery("")
                                                    true
                                                } else false
                                            },
                                        textStyle = OtoType.body.copy(color = Color.White), cursorBrush = SolidColor(Color.White), singleLine = true,
                                        decorationBox = { field ->
                                            Box {
                                                if (menuState.query.isBlank()) Text("¿Qué quieres escuchar?", style = OtoType.body, color = Color.White.copy(.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                field()
                                            }
                                        })
                                    if (menuState.query.isNotBlank()) TopButton(Icons.Rounded.Close, "Limpiar búsqueda") { menu.onQuery("") }
                                    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(Color.White.copy(.16f)))
                                    TopButton(Icons.Rounded.Explore, "Explorar", tint = if (activeSection == 1 && activeLocal == null) accent else null) { goSection(1) }
                                }
                                Spacer(Modifier.width(6.dp))
                                Pill {
                                    TopButton(Icons.Rounded.Devices, "Dispositivos", tint = if (mirrored != null || remote != null) accent else null, onClick = ::openDevices)
                                }
                                SingleDragArea(Modifier.weight(1f).fillMaxHeight(), customFrame, nativeFrame, caption, 1, ::maximize)
                                Box(Modifier.width(116.dp).offset(y = (-3).dp), contentAlignment = Alignment.CenterEnd) {
                                    if (customFrame) SingleWindowControls(accent, maximized(), null,
                                        minimize = { state.isMinimized = true }, maximize = ::maximize,
                                        close = { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) })
                                }
                            }
                            // En el mini la vista central sigue compuesta (altura cero y sin foco): al volver está como se dejó.
                            Row((if (mini) Modifier.height(0.dp) else Modifier.weight(1f)).fillMaxWidth().clipToBounds()
                                .focusProperties { onEnter = { if (mini) cancelFocusChange() } }.focusGroup()) {
                                SingleLibraryRail(Modifier.width(animatedLib), animatedLib > 180.dp, accent, activeSection, activeLocal,
                                    likedList, playlists, recentLists, djActive, ::goSection, ::openList, ::playList,
                                    onToggle = {
                                        libraryWide = !libShown
                                        if (libraryWide && rightShown && !bothFit && sideRoom >= 240.dp + RailNarrow) {
                                            rightOpen = false
                                            saveRight()
                                        }
                                        saveLibrary()
                                    })
                                PanelHandle(accent, "Cambiar el ancho de la biblioteca",
                                    start = { libEff },
                                    drag = { width ->
                                        libraryWide = width >= 190.dp
                                        // Con la cola abierta, la biblioteca crece hasta dejarla en su mínimo, y no más allá de lo que se ve.
                                        draggingPanel = true
                                        val limit = if (rightShown) maxOf(240.dp, sideRoom - 280.dp) else 420.dp
                                        if (libraryWide) libWidth = width.coerceIn(240.dp, minOf(420.dp, limit))
                                    }, end = { draggingPanel = false; saveLibrary() })
                                Box(Modifier.weight(1f).fillMaxHeight().island()) {
                                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (lyricsOpen) 0f else 1f }.focusProperties {
                                        onEnter = { if (lyricsOpen) cancelFocusChange() }
                                    }.focusGroup()) {
                                        CompositionLocalProvider(LocalFloatingDockInset provides FloatingScrollEnd) {
                                            LibrarySpace(player, prefs, menu, importer, account, cloud, login, downloads, palette, ::openSave, ::openDevices,
                                                navigation = navRequest, jump = jump, onAccountFromMobile = { devices.requestAccount(); openDevices() },
                                                artworkBackground = artworkBackground, onArtworkBackground = {
                                                    artworkBackground = it
                                                    kv.putString("artwork_background", it.toString())
                                                }, backdropOpacity = backdropOpacity, onBackdropOpacity = {
                                                    backdropOpacity = it
                                                    kv.putString("artwork_opacity", it.toString())
                                                }, shortcut = shortcut, shortcutIssue = shortcutIssue, onShortcut = {
                                                    shortcut = it
                                                    kv.putString("shortcut", it)
                                                },
                                                playerVisible = true, floatingPanel = false, onSource = ::openSource,
                                                remoteLink = devices.link, following = mirrored?.now, showVolume = false, embedded = true,
                                                onEmbeddedSearch = ::openSearch,
                                                onEmbeddedLocation = { section, id -> activeSection = section; activeLocal = id },
                                                embeddedBack = backRequest, embeddedRecents = recentsOpen,
                                                onEmbeddedRecentsDismiss = { recentsOpen = false; recentRowsFocused = false },
                                                onEmbeddedRecentsFocus = { recentRowsFocused = it }, onQuit = quit)
                                        }
                                    }
                                    Reveal(lyricsOpen && drawing, Modifier.fillMaxSize(), enter = fadeIn(tween(260)) + slideInVertically(spring(.8f, 300f)) { it / 8 },
                                        exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 10 }) {
                                        val lyrics by player.lyrics.collectAsState()
                                        val lyricsLoading by player.lyricsLoading.collectAsState()
                                        val repairedLyrics by produceState(lyrics, localNow.id, lyrics) {
                                            value = lyrics
                                            val id = localNow.id ?: return@produceState
                                            val query = LyricsQuery(id, localNow.title, localNow.artist, localNow.album, localNow.durationMs)
                                            lyricsStore.repairNetEaseSpacing(query, lyrics)?.let { value = it }
                                        }
                                        val lyricFocus = remember { FocusRequester() }
                                        LaunchedEffect(Unit) { if (lyricsOpen) lyricFocus.requestFocus() }
                                        // Se cierra con el botón del dock, Ctrl+L o Esc. Solo quedan − / + discretos arriba.
                                        val sizeHover = remember(hoverEpoch) { MutableInteractionSource() }
                                        val sizeHovered by sizeHover.collectIsHoveredAsState()
                                        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0x400F0F11)).background(Brush.verticalGradient(listOf(accent.copy(.18f), accent.copy(.04f))))
                                            .focusRequester(lyricFocus).focusable()
                                            .pointerInput(Unit) { detectTapGestures { } }) {
                                            val side = (maxWidth * .1f).coerceIn(24.dp, 120.dp)
                                            Column(Modifier.fillMaxSize().padding(start = side, end = side, top = 34.dp, bottom = 10.dp)) {
                                                if (mirrored == null) {
                                                    MobileLyrics(localNow.id, repairedLyrics, lyricsLoading, drawing && localNow.playing && !localNow.buffering,
                                                        player::position, player::seekTo, lyricsStore, prefs, lyricsScale * 1.12f, bottomInset = DockHeight + 24.dp)
                                                } else {
                                                    Text("Sonando en el móvil", style = OtoType.body, modifier = Modifier.padding(12.dp))
                                                }
                                            }
                                            val tools by animateFloatAsState(if (sizeHovered) 1f else .35f, tween(160), label = "tamañoLetra")
                                            Row(Modifier.align(Alignment.TopEnd).padding(8.dp).hoverable(sizeHover).graphicsLayer { alpha = tools }) {
                                                TopButton(Icons.Rounded.Remove, "Reducir letra") { changeLyricsScale(-.1f) }
                                                TopButton(Icons.Rounded.Add, "Ampliar letra") { changeLyricsScale(.1f) }
                                            }
                                        }
                                    }
                                }
                                PanelHandle(accent, "Cambiar el ancho de Ahora suena",
                                    start = { rightEff },
                                    reversed = true,
                                    drag = { width ->
                                        // Un arrastre corto desde el panel plegado por falta de sitio no olvida que estaba abierto.
                                        draggingPanel = true
                                        if (rightShown || width >= 230.dp) rightOpen = width >= 230.dp
                                        if (width >= 230.dp) {
                                            // Si no cabe junto a la biblioteca abierta, se pliega la biblioteca (como al pulsar «<<»).
                                            // Solo se guarda lo que el gesto llega a mostrar (la biblioteca abierta cede hasta 240).
                                            val visible = sideRoom - (if (libraryWide) 240.dp else RailNarrow)
                                            rightWidth = width.coerceIn(280.dp, minOf(480.dp, maxOf(280.dp, visible)))
                                        }
                                    }, end = { draggingPanel = false; saveRight() })
                                NowPlayingPanel(Modifier.width(animatedRight).fillMaxHeight(), animatedRight > 180.dp, accent,
                                    now, playingSource?.let { if (it.kind == "Radio") "Radio de ${it.title}" else it.title }, drawing && !mini && localNow.playing && mirrored == null, coverBeat, player,
                                    qualityLabel.takeIf { mirrored == null }, mixingNow && mirrored == null, nextMix?.style,
                                    onSource = if (canOpenSource) ::openSource else null,
                                    onTrack = ::openTrack, onArtist = ::openArtist,
                                    onToggle = {
                                        rightOpen = !rightShown
                                        saveRight()
                                    }) {
                                    if (mirrored != null) {
                                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp + FloatingScrollEnd)) {
                                            RemoteActions(devices.link, queueInitiallyExpanded = true, showTransport = false,
                                                onError = { message -> uiScope.launch { notices.showSnackbar(message) } })
                                        }
                                    } else QueuePane(queue, localNow.id, shuffle, loop, palette, { player.setShuffle(!shuffle) }, { player.cycleLoop() },
                                        player::move, player::remove, player::clearQueue, player::skipTo,
                                        currentIndex = localNow.index, loopMode = loopMode, djActive = djActive, djMode = djMode, djTanda = djTanda,
                                        djFullOnce = djFullOnce, djNextUp = djNextUp, onPlayDjFullOnce = player::playDjFullOnce,
                                        onVetoDjNextUp = player::vetoDjNextUp, nextMix = nextMix,
                                        onToggleAdhd = { player.setDjMode(if (djMode == io.github.iaguito22.oto.dj.DjMode.Adhd) io.github.iaguito22.oto.dj.DjMode.Normal else io.github.iaguito22.oto.dj.DjMode.Adhd) }, refined = true, bottomInset = 16.dp + FloatingScrollEnd, onSkipDjTanda = player::skipDjTanda, onArtist = ::openArtist)
                                }
                            }
                        }
                            Dock(Modifier.align(Alignment.BottomStart).offset(x = dockX, y = -dockLift).width(dockWidth).height(DockHeight).zIndex(5f),
                                mini, customFrame, workspaceLayer, dockBackdrop,
                                now, if (mirrored == null) player.positionMs else devices.mirror.position, drawing, accent, saved,
                                mirrored?.shuffle ?: shuffle, mirrored?.let { if (it.loop) LoopMode.All else LoopMode.Off } ?: loopMode,
                                lyricsOpen, nextMix.takeIf { mirrored == null },
                                onToggle = ::playPause, onPrev = ::previous, onNext = ::next, onSwipePrev = ::swipePrevious, onSeek = ::seek,
                                onShuffle = {
                                    if (currentMirror == null) player.setShuffle(!shuffle) else mobile("shuffle", !currentMirror.shuffle)
                                }, onLoop = {
                                    if (currentMirror == null) player.cycleLoop() else mobile("loop", !currentMirror.loop)
                                },
                                onLyrics = ::toggleLyrics, onSave = { heard()?.let(::openSave) },
                                volume = level, onVolume = ::setVolume, onMute = ::toggleMute, onMini = ::toggleMini,
                                onMinimize = { state.isMinimized = true },
                                onClose = { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) },
                                onDragWindow = { if (nativeFrame) beginSystemMove(window) },
                                onCover = (::openSource).takeIf { canOpenSource }, onArtist = ::openArtist,
                                pinned = miniPinned, onPin = { miniPinned = !miniPinned; kv.putString("mini_pin", miniPinned.toString()) })

                        if (devicesOpen) SingleSheet("Dispositivos", { devicesOpen = false }) {
                            DevicesPane(devices) {
                                devices.close()
                                OtoDirs.keyValue("local_link").putString("secret", null)
                                deviceRevision++
                            }
                        } else saving?.let { track ->
                            SingleSheet("Guardar", { saving = null }) {
                                SaveSpace(track, prefs, palette) { player.reportDjReaction(track.id, it) }
                            }
                        }
                        SnackbarHost(notices, Modifier.align(Alignment.BottomCenter).zIndex(12f).padding(bottom = DockHeight + 28.dp))
                    }
                    if (nativeFrame && !maximized()) {
                        SingleResizeEdges(mini) { edge -> beginSingleWindowResize(window, edge) }
                    }
                }
            }
        }
    }
}

/** Borde entre paneles: se arrastra para cambiar el ancho; por debajo de un mínimo el panel se pliega. */
@Composable
private fun PanelHandle(accent: Color, label: String, start: () -> Dp, reversed: Boolean = false,
    drag: (Dp) -> Unit, end: () -> Unit) {
    val source = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val line by animateFloatAsState(if (dragging) 1f else if (hovered) .55f else 0f, tween(140), label = "borde")
    val currentStart by rememberUpdatedState(start)
    val currentDrag by rememberUpdatedState(drag)
    val currentEnd by rememberUpdatedState(end)
    Box(Modifier.width(Gap).fillMaxHeight().hoverable(source)
        .semantics { contentDescription = label }
        .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR)))
        .pointerInput(Unit) {
            var width = 0.dp
            detectHorizontalDragGestures(
                onDragStart = { dragging = true; width = currentStart() },
                onDragEnd = { dragging = false; currentEnd() },
                onDragCancel = { dragging = false; currentEnd() }) { change, dx ->
                change.consume()
                width += (if (reversed) -dx else dx).toDp()
                currentDrag(width)
            }
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.width(2.dp).fillMaxHeight(.6f).clip(CircleShape).background(accent.copy(alpha = .75f * line)))
    }
}

@Composable
private fun SingleLibraryRail(modifier: Modifier, wide: Boolean, accent: Color, activeSection: Int, activeLocal: String?,
    likedList: LocalPlaylist, playlists: List<LocalPlaylist>, recentLists: List<String>, djActive: Boolean,
    onSection: (Int) -> Unit, onList: (String) -> Unit, onPlay: (LocalPlaylist) -> Unit, onToggle: () -> Unit) {
    // Primero las listas abiertas o reproducidas hace poco; el resto, en su orden.
    val ordered = remember(playlists, recentLists) {
        val ranks = recentLists.withIndex().associate { it.value to it.index }
        playlists.sortedBy { ranks[it.id] ?: Int.MAX_VALUE }
    }
    Column(modifier.fillMaxHeight().island()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = if (wide) 14.dp else 0.dp, end = if (wide) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (wide) Arrangement.spacedBy(2.dp) else Arrangement.Center) {
            if (wide) {
                val titleSource = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
                val titleHover by titleSource.collectIsHoveredAsState()
                Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .pointerHoverIcon(PointerIcon.Hand).clickable(titleSource, null, onClickLabel = "Ir a Tu música") { onSection(0) }.subtleHoverScale(titleSource)
                    .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tu biblioteca", style = OtoType.rowTitle.copy(fontSize = 16.sp),
                        color = if (titleHover || activeSection == 0 && activeLocal == null) Color.White else Color.White.copy(.82f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TopButton(Icons.Rounded.Download, "Descargas", tint = if (activeSection == 3) accent else null) { onSection(3) }
                TopButton(Icons.Rounded.FirstPage, "Plegar la biblioteca", onClick = onToggle)
            } else TopButton(Icons.Rounded.LastPage, "Desplegar la biblioteca", onClick = onToggle)
        }
        val railPadding = if (wide) 12.dp else 8.dp
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = railPadding, end = railPadding, bottom = 12.dp + FloatingScrollEnd),
            verticalArrangement = Arrangement.spacedBy(if (wide) 2.dp else 4.dp)) {
            if (!wide) item { LibraryRow(false, null, MobileIcons.libraryMusic, "Tu música", "", activeSection == 0 && activeLocal == null, accent, null) { onSection(0) } }
            item { LibraryRow(wide, null, Icons.Rounded.GraphicEq, "DJ", if (djActive) "Pinchando" else "Modos y géneros", activeSection == 6, accent, null, special = true) { onSection(6) } }
            item { LibraryRow(wide, null, Icons.Rounded.Favorite, "Me gusta", "Lista · ${songCount(likedList.songs.size)}", activeLocal == LIKED_ID, accent,
                likedList.takeIf { it.songs.isNotEmpty() }?.let { { onPlay(it) } }, special = true) { onList(LIKED_ID) } }
            items(ordered, key = { it.id }) { l ->
                LibraryRow(wide, null, MobileIcons.libraryMusic, l.name, "Lista · ${songCount(l.songs.size)}", activeLocal == l.id, accent,
                    if (l.songs.isNotEmpty()) ({ onPlay(l) }) else null, mosaic = l.takeIf { it.songs.isNotEmpty() }) { onList(l.id) }
            }
        }
    }
}

@Composable
private fun NowPlayingPanel(modifier: Modifier, wide: Boolean, accent: Color, now: io.github.iaguito22.oto.playback.NowPlaying,
    context: String?, pulsing: Boolean, coverBeat: Boolean, player: DesktopPlayerConnection,
    quality: String?, mixing: Boolean, mixStyle: String?,
    onSource: (() -> Unit)?, onTrack: () -> Unit, onArtist: (String, String?) -> Unit,
    onToggle: () -> Unit, queue: @Composable () -> Unit) {
    val pulse = rememberCoverPulse(pulsing && wide, coverBeat, player::beat)
    Column(modifier.island()) {
        if (!wide) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
                TopButton(Icons.Rounded.FirstPage, "Desplegar Ahora suena", onClick = onToggle)
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 10.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            TopButton(Icons.Rounded.LastPage, "Plegar Ahora suena", onClick = onToggle)
            Text(context?.takeIf { it.isNotBlank() && now.id != null }.orEmpty(), style = OtoType.rowTitle.copy(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).trackLink(onSource).padding(start = 4.dp))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val coverSize = minOf(maxWidth - 36.dp, (maxHeight - 132.dp).coerceAtLeast(92.dp))
                .coerceAtLeast(0.dp)
            val showDetails = maxHeight >= 285.dp
            Column(Modifier.fillMaxSize()) {
                if (now.id != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(coverSize).graphicsLayer {
                            val scale = 1f + .012f * pulse.value
                            scaleX = scale; scaleY = scale
                        }.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(.05f)).trackLink(onTrack)) {
                            LibraryArt(now.artwork, Modifier.fillMaxSize(), rounded = false)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(now.title, style = OtoType.title,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TrackArtists(now, Color.White.copy(.62f), onArtist)
                            // Calidad del audio y, si está mezclando, el estilo de la mezcla.
                            if (showDetails && (quality != null || mixing)) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (mixing) {
                                    val beat = rememberInfiniteTransition(label = "mezclando")
                                    val dot by beat.animateFloat(.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "puntoMezcla")
                                    Row(Modifier.clip(CircleShape).background(accent.copy(.18f)).padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(Modifier.size(6.dp).graphicsLayer { alpha = dot }.clip(CircleShape).background(accent))
                                        Text(mixStyle?.let { "Mezclando · $it" } ?: "Mezclando", style = OtoType.rowSub.copy(fontSize = 12.sp), color = accent, maxLines = 1)
                                    }
                                }
                                if (quality != null) Text(quality, style = OtoType.rowSub.copy(fontSize = 12.sp), color = Color.White.copy(.55f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(.07f)).padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(start = 18.dp, end = 14.dp)) { queue() }
            }
        }
    }
}

@Composable
private fun SaveButton(saved: Boolean, accent: Color, onClick: () -> Unit) =
    DockButton(if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
        if (saved) "Guardada · cambiar dónde (Ctrl+B)" else "Guardar (Ctrl+B)", if (saved) accent else Color.White.copy(.7f), onClick = onClick)

@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.height(44.dp).clip(CircleShape).background(Color(0xE6131315)).border(1.dp, Border, CircleShape).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun TopButton(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) =
    DockButton(icon, label, tint ?: Color.White.copy(if (enabled) .78f else .32f), enabled = enabled, onClick = onClick)

@Composable
private fun LibraryRow(wide: Boolean, artwork: String?, fallback: ImageVector, title: String, subtitle: String, active: Boolean, accent: Color,
    onPlay: (() -> Unit)?, special: Boolean = false, mosaic: LocalPlaylist? = null, onClick: () -> Unit) {
    val source = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val keyboard = LocalKeyboardNav.current
    val bg by animateColorAsState(if (active) Color.White.copy(.11f) else if (focused && keyboard) Color.White.copy(.06f) else Color.Transparent, tween(150))
    val label = Modifier.semantics {
        if (!wide) contentDescription = title
        if (onPlay != null) customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction("Reproducir $title") { onPlay(); true })
    }.then(if (onPlay == null) Modifier else Modifier.onPreviewKeyEvent { e ->
        // Con la fila enfocada, P o Mayús+Intro reproduce la lista.
        if (e.type == KeyEventType.KeyDown && (e.key == Key.P || e.key == Key.Enter && e.isShiftPressed)) { onPlay(); true } else false
    })
    Row(Modifier.fillMaxWidth().then(label).clip(RoundedCornerShape(10.dp)).background(bg).hoverable(source)
        .pointerHoverIcon(PointerIcon.Hand).clickable(source, null, onClick = onClick).subtleHoverScale(source, 1.02f)
        .padding(horizontal = if (wide) 6.dp else 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (wide) Arrangement.spacedBy(12.dp) else Arrangement.Center) {
        Box(Modifier.widthIn(max = 48.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(if (special) 10.dp else 6.dp))
            .background(if (special) Brush.linearGradient(listOf(accent.copy(.75f), accent.copy(.25f))) else SolidColor(Color.White.copy(.07f))),
            contentAlignment = Alignment.Center) {
            if (mosaic != null) PlaylistMosaic(mosaic, accent, Modifier.fillMaxSize(), radius = 0.dp)
            else if (artwork != null) LibraryArt(artwork, Modifier.fillMaxSize(), rounded = false)
            else Icon(fallback, null, Modifier.size(22.dp), tint = if (special) Color(0xFF111111) else if (active) accent else Color.White.copy(.7f))
            val play by animateFloatAsState(if ((hovered || focused && keyboard) && onPlay != null) 1f else 0f, tween(140), label = "reproducirLista")
            if (onPlay != null && play > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = play }.background(Color.Black.copy(.45f))
                .focusProperties { canFocus = false }
                .pointerHoverIcon(PointerIcon.Hand).clickable(null, null, onClickLabel = "Reproducir $title", role = androidx.compose.ui.semantics.Role.Button, onClick = onPlay),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, "Reproducir $title", Modifier.size(28.dp), tint = Color.White)
            }
        }
        if (wide) Column(Modifier.weight(1f)) {
            Text(title, style = OtoType.rowTitle, color = if (active) accent else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = OtoType.rowSub, color = Color.White.copy(.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Borde exterior de respaldo cuando Compose recibe la pulsación antes que el marco nativo. */
@Composable
private fun BoxScope.SingleResizeEdges(mini: Boolean, onResize: (Int) -> Unit) {
    val edge = 5.dp
    val corner = 8.dp
    ResizeGrip(Modifier.align(Alignment.CenterStart).padding(vertical = if (mini) 0.dp else corner).fillMaxHeight().width(edge),
        1, java.awt.Cursor.W_RESIZE_CURSOR, onResize)
    ResizeGrip(Modifier.align(Alignment.CenterEnd).padding(vertical = if (mini) 0.dp else corner).fillMaxHeight().width(edge),
        2, java.awt.Cursor.E_RESIZE_CURSOR, onResize)
    if (mini) return
    ResizeGrip(Modifier.align(Alignment.TopCenter).padding(horizontal = corner).fillMaxWidth().height(edge),
        3, java.awt.Cursor.N_RESIZE_CURSOR, onResize)
    ResizeGrip(Modifier.align(Alignment.BottomCenter).padding(horizontal = corner).fillMaxWidth().height(edge),
        6, java.awt.Cursor.S_RESIZE_CURSOR, onResize)
    ResizeGrip(Modifier.align(Alignment.TopStart).size(corner), 4, java.awt.Cursor.NW_RESIZE_CURSOR, onResize)
    ResizeGrip(Modifier.align(Alignment.TopEnd).size(corner), 5, java.awt.Cursor.NE_RESIZE_CURSOR, onResize)
    ResizeGrip(Modifier.align(Alignment.BottomStart).size(corner), 7, java.awt.Cursor.SW_RESIZE_CURSOR, onResize)
    ResizeGrip(Modifier.align(Alignment.BottomEnd).size(corner), 8, java.awt.Cursor.SE_RESIZE_CURSOR, onResize)
}

@Composable
private fun ResizeGrip(modifier: Modifier, edge: Int, cursor: Int, onResize: (Int) -> Unit) {
    val resize by rememberUpdatedState(onResize)
    Box(modifier.zIndex(10f)
        .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(cursor)))
        .pointerInput(edge) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                resize(edge)
            }
        })
}

internal fun Modifier.subtleHoverScale(source: MutableInteractionSource, amount: Float = 1.035f): Modifier = composed {
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else if (hovered) amount else 1f, tween(130), label = "hoverScale")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

internal fun Modifier.trackLink(action: (() -> Unit)?, label: String? = null): Modifier =
    if (action == null) this else composed {
        val source = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
        pointerHoverIcon(PointerIcon.Hand)
            .clickable(source, null, onClickLabel = label, onClick = action)
            .subtleHoverScale(source)
    }

@Composable
private fun TrackArtists(now: io.github.iaguito22.oto.playback.NowPlaying, color: Color, onArtist: (String, String?) -> Unit) {
    val artists = now.artists.ifEmpty { listOf(now.artist to null) }.filter { it.first.isNotBlank() }
    if (artists.isEmpty()) return
    val currentOpen by rememberUpdatedState(onArtist)
    val links = remember(artists) {
        buildAnnotatedString {
            artists.forEachIndexed { index, (name, id) ->
                if (index > 0) append(", ")
                withLink(LinkAnnotation.Clickable(
                    tag = id ?: "artist:$name",
                    styles = TextLinkStyles(hoveredStyle = SpanStyle(color = Color.White, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { currentOpen(name, id) },
                )) { append(name) }
            }
        }
    }
    Text(links, style = OtoType.rowSub, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

internal enum class MiniCoverAction { Toggle, Previous, Next, None }

internal fun miniCoverAction(displacement: Offset, moved: Boolean, threshold: Float): MiniCoverAction {
    if (!moved) return MiniCoverAction.Toggle
    if (kotlin.math.abs(displacement.x) < threshold || kotlin.math.abs(displacement.x) <= kotlin.math.abs(displacement.y)) {
        return MiniCoverAction.None
    }
    return if (displacement.x > 0f) MiniCoverAction.Previous else MiniCoverAction.Next
}

@Composable
private fun MiniArtwork(url: String?, size: Dp, onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit) {
    val toggle by rememberUpdatedState(onToggle)
    val previous by rememberUpdatedState(onPrev)
    val next by rememberUpdatedState(onNext)
    val interaction = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val settling = remember { Animatable(0f) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val hovered by interaction.collectIsHoveredAsState()
    val keyPressed by interaction.collectIsPressedAsState()
    var pointerPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        when {
            dragging -> .97f
            pointerPressed || keyPressed -> .91f
            hovered -> 1.035f
            else -> 1f
        }, spring(.8f, 550f), label = "miniArtworkScale",
    )
    fun settle() {
        settleJob?.cancel()
        settleJob = scope.launch {
            settling.snapTo(offsetX)
            dragging = false
            settling.animateTo(0f, spring(.72f, 480f))
        }
    }
    Box(Modifier.size(size).pointerHoverIcon(PointerIcon.Hand).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(.07f))
        .pointerInput(Unit) {
            // Una sola secuencia decide entre clic y gesto. El clic accesible y de teclado sigue en clickable.
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                down.consume()
                settleJob?.cancel()
                val startOffset = if (dragging) offsetX else settling.value
                offsetX = startOffset
                dragging = false
                pointerPressed = true
                val tapSlop = viewConfiguration.touchSlop.coerceAtMost(7.dp.toPx())
                val limit = this.size.width * .58f
                var moved = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                        val displacement = change.position - down.position
                        moved = moved || displacement.getDistance() > tapSlop
                        change.consume()
                        if (!change.pressed) {
                            when (miniCoverAction(displacement, moved, maxOf(16.dp.toPx(), this.size.width * .25f))) {
                                MiniCoverAction.Toggle -> toggle()
                                MiniCoverAction.Previous -> previous()
                                MiniCoverAction.Next -> next()
                                MiniCoverAction.None -> Unit
                            }
                            break
                        }
                        if (moved) {
                            pointerPressed = false
                            dragging = kotlin.math.abs(displacement.x) > kotlin.math.abs(displacement.y)
                            offsetX = if (dragging) (startOffset + displacement.x * .9f).coerceIn(-limit, limit) else 0f
                        }
                    }
                } finally {
                    pointerPressed = false
                    if (moved || startOffset != 0f) settle()
                }
            }
        }.clickable(interaction, null, onClickLabel = "Reproducir o pausar", role = androidx.compose.ui.semantics.Role.Button, onClick = onToggle)
        .semantics {
            customActions = listOf(
                androidx.compose.ui.semantics.CustomAccessibilityAction("Anterior") { previous(); true },
                androidx.compose.ui.semantics.CustomAccessibilityAction("Siguiente") { next(); true },
            )
        }, contentAlignment = Alignment.Center) {
        Crossfade(url, animationSpec = tween(150), label = "miniArtwork",
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationX = if (dragging) offsetX else settling.value
                rotationZ = translationX / this.size.width.coerceAtLeast(1f) * 13f
                scaleX = scale
                scaleY = scale
            }) { artwork ->
            LibraryArt(artwork, Modifier.fillMaxSize(), rounded = false)
        }
    }
}

/** Botón sin fondo, como los de Spotify: se ilumina al pasar el puntero. */
@Composable
private fun DockButton(icon: ImageVector, label: String, tint: Color, size: Dp = 36.dp, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) .9f else if (hovered) 1.06f else 1f, spring(.55f, 700f))
    val keyboard = LocalKeyboardNav.current
    val ink by animateColorAsState(if ((hovered || focused && keyboard) && enabled && tint.alpha < 1f) tint.copy(alpha = 1f) else tint, tween(120))
    Box(Modifier.size(size).clip(CircleShape).background(if (focused && keyboard) Color.White.copy(.10f) else Color.Transparent)
        .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .clickable(source, null, enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = ink, modifier = Modifier.size(size * .56f).graphicsLayer { scaleX = scale; scaleY = scale })
    }
}

/** Botón de modo (aleatorio, repetir, letra): encendido lleva el acento y un punto debajo. */
@Composable
private fun ToggleDockButton(icon: ImageVector, label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.BottomCenter) {
        DockButton(icon, label, if (on) accent else Color.White.copy(.68f), onClick = onClick)
        val dot by animateFloatAsState(if (on) 1f else 0f, tween(160), label = "punto")
        Box(Modifier.padding(bottom = 1.dp).size(4.dp).graphicsLayer { alpha = dot; scaleX = dot; scaleY = dot }.clip(CircleShape).background(accent))
    }
}

/**
 * Dock flotante de cristal: muestrea lo que tiene detrás (desenfocado) y deja los controles centrados en él.
 * Con la ventana normal no se esconde nada; en el mini se van quitando cosas según el ancho.
 */
@Composable
private fun Dock(modifier: Modifier, mini: Boolean, customFrame: Boolean, workspaceLayer: GraphicsLayer, backdropOffset: Offset,
    now: io.github.iaguito22.oto.playback.NowPlaying, position: StateFlow<Long>, drawing: Boolean,
    accent: Color, saved: Boolean, shuffle: Boolean, loopMode: LoopMode, lyricsOpen: Boolean,
    nextMix: DesktopPlayerConnection.NextMix?,
    onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onSwipePrev: () -> Unit, onSeek: (Long) -> Unit,
    onShuffle: () -> Unit, onLoop: () -> Unit, onLyrics: () -> Unit, onSave: () -> Unit,
    volume: Float, onVolume: (Float) -> Unit, onMute: () -> Unit, onMini: () -> Unit,
    onMinimize: () -> Unit, onClose: () -> Unit, onDragWindow: () -> Unit,
    onCover: (() -> Unit)?, onArtist: (String, String?) -> Unit,
    pinned: Boolean, onPin: () -> Unit) {
    val dim = Color.White.copy(.68f)
    val shape = RoundedCornerShape(16.dp)
    // --cs-glass = acento 55 % con #1a1a1a; --cs-rim = acento 62 % con blanco.
    val glass = androidx.compose.ui.graphics.lerp(Color(0xFF1A1A1A), accent, .55f)
    val rim = androidx.compose.ui.graphics.lerp(Color.White, accent, .62f)
    val hover = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val currentDrag by rememberUpdatedState(onDragWindow)
    val currentMini by rememberUpdatedState(onMini)
    var dockFocused by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier
        .hoverable(hover).onFocusChanged { dockFocused = it.hasFocus }
        .shadow(if (mini) 0.dp else 18.dp, shape, clip = false, ambientColor = Color.Black.copy(.4f), spotColor = Color.Black.copy(.4f)).clip(shape)
        .border(1.dp, Brush.linearGradient(listOf(rim.copy(.34f), rim.copy(.10f), rim.copy(.24f))), shape)) {
        // Cristal: lo de detrás, desenfocado y algo más saturado, con un velo del acento.
        Canvas(Modifier.fillMaxSize().graphicsLayer {
            renderEffect = BlurEffect(45.dp.toPx(), 45.dp.toPx(), TileMode.Clamp)
        }) {
            val saturation = ColorMatrix().apply { setToSaturation(1.9f) }
            val light = ColorMatrix(floatArrayOf(1.04f, 0f, 0f, 0f, 0f, 0f, 1.04f, 0f, 0f, 0f, 0f, 0f, 1.04f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            saturation.timesAssign(light)
            drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size),
                androidx.compose.ui.graphics.Paint().apply { colorFilter = ColorFilter.colorMatrix(saturation) })
            translate(-backdropOffset.x, -backdropOffset.y) { drawLayer(workspaceLayer) }
            drawContext.canvas.restore()
        }
        Box(Modifier.matchParentSize().background(Color(0x571A1A1A))
            .background(Brush.linearGradient(0f to glass.copy(.34f), .4f to glass.copy(.08f), 1f to glass.copy(.20f))))
        Box(Modifier.matchParentSize().drawWithContent {
            // Brillo interior del acento y filos: luz arriba (30 %) y rebote abajo (9 %).
            val glow = 22.dp.toPx()
            drawRect(Brush.verticalGradient(listOf(accent.copy(.18f), Color.Transparent), endY = glow))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(.12f)), startY = size.height - glow))
            drawRect(Brush.horizontalGradient(listOf(accent.copy(.12f), Color.Transparent), endX = glow))
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(.12f)), startX = size.width - glow))
            drawRect(rim.copy(.30f), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
            drawRect(rim.copy(.09f), topLeft = Offset(0f, size.height - 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
        })
        // Solo el cristal libre arrastra la ventana. La carátula y los controles conservan sus gestos.
        if (mini) Box(Modifier.matchParentSize()
            .pointerInput(Unit) { detectDragGestures(onDragStart = { currentDrag() }) { change, _ -> change.consume() } }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentMini() }) })
        val w = maxWidth - 16.dp - if (mini) 24.dp else 0.dp
        val side = maxOf(w * .3f, if (mini) 76.dp else 196.dp).coerceAtMost((w - if (mini) 150.dp else 230.dp) / 2).coerceAtLeast(0.dp)
        val center = w - side * 2
        val showVolumeSlider = !mini || w >= 600.dp
        val showModes = !mini || center >= 228.dp
        val showLyrics = !mini || w >= 430.dp
        val showTimes = !mini || w >= 420.dp
        val showText = !mini || side >= 170.dp
        val loopIcon = if (loopMode == LoopMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat
        val width = maxWidth
        if (mini) {
            val coverOnly = width < 148.dp
            val transport = width >= 286.dp
            val showMiniModes = width >= 510.dp
            val showMiniText = width >= 396.dp
            val playCenter = maxOf(width / 2, 96.dp).coerceAtMost((width - 52.dp).coerceAtLeast(0.dp))
            val groupWidth = if (showMiniModes) 218.dp else if (transport) 138.dp else 50.dp
            Row(Modifier.align(Alignment.TopStart).padding(start = 9.dp, top = 8.dp).height(60.dp)
                .width((playCenter - groupWidth / 2 - 15.dp).coerceAtLeast(60.dp)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MiniArtwork(now.artwork, if (coverOnly) 50.dp else 60.dp, onToggle, onSwipePrev, onNext)
                if (showMiniText) Column(Modifier.weight(1f)) {
                    Text(now.title.ifBlank { "Oto" }, style = OtoType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (now.buffering) Text("Preparando…", style = OtoType.rowSub, color = dim)
                    else TrackArtists(now, dim, onArtist)
                }
            }
            if (!coverOnly) Row(Modifier.align(Alignment.TopStart).offset(x = playCenter - groupWidth / 2, y = 8.dp)
                .width(groupWidth).height(60.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                if (showMiniModes) ToggleDockButton(Icons.Rounded.Shuffle, "Aleatorio", shuffle, accent, onShuffle)
                if (transport) DockButton(Icons.Rounded.SkipPrevious, "Anterior", Color.White.copy(.9f), size = 40.dp, onClick = onPrev)
                DockPlay(now.playing, accent, onToggle)
                if (transport) DockButton(Icons.Rounded.SkipNext, "Siguiente", Color.White.copy(.9f), size = 40.dp, onClick = onNext)
                if (showMiniModes) ToggleDockButton(loopIcon, "Repetir", loopMode != LoopMode.Off, accent, onLoop)
            }
            val showMiniVolumeSlider = width >= 680.dp
            if (width >= 560.dp) Row(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 32.dp).height(60.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val preview = remember { mutableStateOf<Float?>(null) }
                val shownVolume = preview.value ?: volume
                if (now.id != null) SaveButton(saved, accent, onSave)
                DockButton(if (shownVolume <= 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    if (volume <= 0f) "Activar sonido" else "Silenciar", dim, onClick = onMute)
                if (showMiniVolumeSlider) DockSlider(shownVolume, Modifier.width(64.dp), "Volumen", preview, accent = accent, onCommit = onVolume)
            }
        } else Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp).height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(side), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(minOf(60.dp, side)).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(.07f)).trackLink(onCover, "Abrir origen de la reproducción")) {
                    LibraryArt(now.artwork, Modifier.fillMaxSize(), rounded = false)
                }
                if (showText) Column(Modifier.weight(1f, fill = false)) {
                    Text(now.title.ifBlank { "Oto" }, style = OtoType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (now.buffering) Text("Preparando…", style = OtoType.rowSub, color = dim)
                    else TrackArtists(now, dim, onArtist)
                }
                if (now.id != null && showText) SaveButton(saved, accent, onSave)
            }
            Row(Modifier.width(center), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                if (showModes) ToggleDockButton(Icons.Rounded.Shuffle, "Aleatorio", shuffle, accent, onShuffle)
                DockButton(Icons.Rounded.SkipPrevious, "Anterior", Color.White.copy(.9f), size = 44.dp, onClick = onPrev)
                DockPlay(now.playing, accent, onToggle)
                DockButton(Icons.Rounded.SkipNext, "Siguiente", Color.White.copy(.9f), size = 44.dp, onClick = onNext)
                if (showModes) ToggleDockButton(loopIcon, "Repetir", loopMode != LoopMode.Off, accent, onLoop)
            }
            Row(Modifier.width(side), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End)) {
                if (showLyrics) ToggleDockButton(MobileIcons.lyrics, "Letra (Ctrl+L)", lyricsOpen, accent, onLyrics)
                val volumePreview = remember { mutableStateOf<Float?>(null) }
                val shownVolume = volumePreview.value ?: volume
                DockButton(when { shownVolume <= 0f -> Icons.Rounded.VolumeOff; shownVolume < .5f -> Icons.Rounded.VolumeDown; else -> Icons.Rounded.VolumeUp },
                    if (volume <= 0f) "Activar sonido" else "Silenciar", dim, onClick = onMute)
                if (showVolumeSlider) DockSlider(shownVolume, Modifier.widthIn(min = 56.dp, max = 96.dp).weight(1f, fill = false), "Volumen", volumePreview, accent = accent, onCommit = onVolume)
                if (!mini) DockButton(Icons.Rounded.PictureInPictureAlt, "Mini reproductor (Ctrl+Mayús+M)", dim, onClick = onMini)
            }
        }
        // La barra de tiempo va por el borde de abajo del dock, con los tiempos encima de sus extremos.
        if (!mini || w >= 220.dp) DockProgress(now.id, now.durationMs, position, drawing, nextMix, accent, hovered, showTimes,
            Modifier.align(Alignment.BottomCenter).padding(end = if (mini) 24.dp else 0.dp), onSeek)
        // En el mini, los botones de la ventana viven en una franja propia del borde derecho
        // y solo aparecen con el puntero (o el foco) dentro del dock: nunca tapan los controles.
        if (mini) {
            val extras by animateFloatAsState(if (hovered || dockFocused) 1f else 0f, tween(160), label = "extrasMini")
            val glyphSize = if (LocalDesktopUpdate.current?.release != null && customFrame) 14.dp else 18.dp
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).fillMaxHeight().width(24.dp).padding(top = 1.dp, bottom = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                DesktopUpdateButton(accent, 18.dp)
                Column(Modifier.weight(1f).graphicsLayer { alpha = extras; translationX = (1f - extras) * 6f },
                    verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (extras > 0f) {
                        WindowGlyph(Icons.Rounded.OpenInFull, "Abrir Oto", true, accent, currentMini, glyphSize)
                        WindowGlyph(if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, if (pinned) "Dejar de fijar encima" else "Fijar encima de todo",
                            true, accent, onPin, glyphSize, tint = if (pinned) accent else null)
                        if (customFrame) {
                            WindowGlyph(Icons.Rounded.Remove, "Minimizar", true, accent, onMinimize, glyphSize)
                            WindowGlyph(Icons.Rounded.Close, "Cerrar", true, Color(0xFFFF737B), onClose, glyphSize)
                        }
                    }
                }
            }
        }
    }
}

/** Reproducir / pausa: solo el icono, grande y blanco; al pasar el puntero se enciende un resplandor del acento detrás. */
@Composable
private fun DockPlay(playing: Boolean, accent: Color, onClick: () -> Unit) {
    val interaction = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val keyboard = LocalKeyboardNav.current
    val scale by animateFloatAsState(if (pressed) .88f else if (hovered) 1.1f else 1f, spring(.5f, 650f), label = "dockPlay")
    val glow by animateFloatAsState(if (hovered || focused && keyboard) 1f else 0f, tween(180), label = "brilloPlay")
    Box(Modifier.size(50.dp)
        .drawBehind { drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .45f * glow), Color.Transparent)), radius = size.minDimension * .62f) }
        .pointerHoverIcon(PointerIcon.Hand)
        .clickable(interaction, null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        androidx.compose.animation.AnimatedContent(playing, transitionSpec = {
            (fadeIn(tween(140)) + androidx.compose.animation.scaleIn(tween(160), .6f)) togetherWith (fadeOut(tween(100)) + androidx.compose.animation.scaleOut(tween(120), .6f))
        }, label = "iconoPlay") { isPlaying ->
            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (isPlaying) "Pausar" else "Reproducir", tint = Color.White,
                modifier = Modifier.size(39.dp).graphicsLayer { scaleX = scale; scaleY = scale })
        }
    }
}

@Composable
private fun DockProgress(trackId: String?, duration: Long, clock: StateFlow<Long>, drawing: Boolean,
    mix: DesktopPlayerConnection.NextMix?, accent: Color, hovered: Boolean, showTimes: Boolean, modifier: Modifier, onSeek: (Long) -> Unit) {
    if (trackId == null) return
    val previewState = remember(trackId, duration) { mutableStateOf<Float?>(null) }
    var focused by remember { mutableStateOf(false) }
    val preview = previewState.value
    val position = if (drawing) clock.collectAsState().value else clock.value
    val fraction = preview ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val seconds = if (preview == null) position / 1000 else (fraction * duration).toLong() / 1000
    val mark = remember(mix, duration) {
        val start = mix?.startMs
        if (duration <= 0 || start == null) null
        else MixMark(start.toFloat() / duration, (mix.switchMs ?: start).toFloat() / duration, mix.ready)
    }
    val raised by animateFloatAsState(if (hovered || focused || preview != null) 1f else 0f, tween(150), label = "barraTiempo")
    Row(modifier.fillMaxWidth().height(19.dp).padding(horizontal = if (showTimes) 79.dp else 14.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showTimes) Box(Modifier.width(38.dp).align(Alignment.CenterVertically), contentAlignment = Alignment.CenterStart) { DockTime(seconds) }
        if (duration > 0) key(trackId, duration) {
            DockSlider(fraction, Modifier.weight(1f).offset(y = (-3).dp * raised)
                .onFocusChanged { focused = it.hasFocus },
                "Posición", previewState, mark, accent = accent, edge = true,
                onCommit = { onSeek((it * duration).toLong()) })
        } else Spacer(Modifier.weight(1f))
        if (showTimes && duration > 0) Box(Modifier.width(38.dp).align(Alignment.CenterVertically), contentAlignment = Alignment.CenterEnd) { DockTime(duration / 1000) }
    }
}

@Composable
private fun DockSlider(value: Float, modifier: Modifier, label: String, preview: MutableState<Float?>,
    mix: MixMark? = null, accent: Color = Color.White, edge: Boolean = false, onCommit: (Float) -> Unit) {
    val source = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    FineSlider(value, { preview.value = it }, modifier.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed) return@onKeyEvent false
        val next = when (event.key) {
            Key.DirectionLeft, Key.DirectionDown -> value - .03f
            Key.DirectionRight, Key.DirectionUp -> value + .03f
            Key.MoveHome -> 0f
            Key.MoveEnd -> 1f
            else -> return@onKeyEvent false
        }
        onCommit(next.coerceIn(0f, 1f))
        true
    }.focusable(interactionSource = source), label = label, expanded = focused,
        onFinish = { preview.value?.let(onCommit); preview.value = null }, mix = mix, activeColor = accent, bottomAligned = edge)
}

@Composable
private fun DockTime(seconds: Long) {
    Text(remember(seconds) { "%d:%02d".format(seconds / 60, seconds % 60) },
        style = OtoType.rowSub.copy(fontSize = 12.sp), color = Color.White.copy(.68f), maxLines = 1)
}

@Composable
private fun FrameWindowScope.SingleDragArea(modifier: Modifier, custom: Boolean, native: Boolean,
    caption: SingleCaptionZones, index: Int, maximize: () -> Unit) {
    if (!custom) {
        Spacer(modifier)
        return
    }
    val currentMaximize by rememberUpdatedState(maximize)
    val taps = Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentMaximize() }) }
    if (native) {
        val frame = window
        DisposableEffect(caption, index) { onDispose { caption.set(index, null) } }
        Spacer(modifier.onGloballyPositioned {
            val r = it.boundsInWindow()
            caption.set(index, DockRect(r.left.roundToInt(), r.top.roundToInt(), r.width.roundToInt(), r.height.roundToInt()))
        }.then(taps).pointerInput(frame) {
            detectDragGestures(onDragStart = { beginSystemMove(frame) }) { change, _ -> change.consume() }
        })
    } else WindowDraggableArea(modifier.then(taps)) { }
}

/** Minimizar, maximizar y cerrar: tenues y sin fondo; se encienden con el puntero cerca. */
@Composable
private fun SingleWindowControls(accent: Color, maximized: Boolean, extra: (@Composable () -> Unit)?, minimize: () -> Unit, maximize: () -> Unit, close: () -> Unit) {
    val interaction = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(Modifier.hoverable(interaction), verticalAlignment = Alignment.CenterVertically) {
        extra?.invoke()
        WindowGlyph(Icons.Rounded.Remove, "Minimizar", hovered, accent, minimize)
        WindowGlyph(if (maximized) Icons.Rounded.FilterNone else Icons.Rounded.CropSquare,
            if (maximized) "Restaurar" else "Maximizar", hovered, accent, maximize)
        WindowGlyph(Icons.Rounded.Close, "Cerrar", hovered, Color(0xFFFF737B), close)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WindowGlyph(icon: ImageVector, label: String, revealed: Boolean, glow: Color, onClick: () -> Unit, size: Dp = 30.dp, tint: Color? = null) {
    val interaction = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = LocalKeyboardNav.current
    val active = hovered || focused && keyboard
    val ink by animateColorAsState(
        if (active) tint ?: glow else tint ?: Color.White.copy(if (revealed) .9f else .38f), tween(130),
    )
    Box(Modifier.size(size).pointerHoverIcon(PointerIcon.Hand)
        .clickable(interaction, null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(size * .56f).subtleHoverScale(interaction, 1.08f), tint = ink)
    }
}

@Composable
private fun BoxScope.SingleScrim(label: String, dismiss: () -> Unit) {
    Box(Modifier.matchParentSize().zIndex(10f).background(Color.Black.copy(.52f))
        .focusProperties { canFocus = false }
        .clickable(remember(LocalHoverEpoch.current) { MutableInteractionSource() }, null, onClickLabel = label, onClick = dismiss))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.SingleSheet(title: String, dismiss: () -> Unit, body: @Composable () -> Unit) {
    SingleScrim("Cerrar " + title, dismiss)
    val focus = remember { FocusRequester() }
    Surface(Modifier.align(Alignment.Center).zIndex(11f).widthIn(max = 450.dp).fillMaxWidth(.92f).fillMaxHeight(.88f)
        .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
        .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Border)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = OtoType.rowTitle, modifier = Modifier.weight(1f))
                IconButton(onClick = dismiss, modifier = Modifier.focusRequester(focus)) { Icon(Icons.Rounded.Close, "Cerrar " + title) }
            }
            Box(Modifier.weight(1f)) { body() }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** AnimatedVisibility sin receptores implícitos (dentro de Row/Box la llamada es ambigua). */
@Composable
private fun Reveal(visible: Boolean, modifier: Modifier = Modifier, enter: androidx.compose.animation.EnterTransition = fadeIn(), exit: androidx.compose.animation.ExitTransition = fadeOut(), content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(visible, modifier, enter, exit) { content() }
}
