package io.github.iaguito22.oto.desktop

import androidx.compose.foundation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.account.*
import io.github.iaguito22.oto.desktop.playback.*
import io.github.iaguito22.oto.desktop.ui.*
import io.github.iaguito22.oto.importer.Importer
import io.github.iaguito22.oto.lyrics.LyricsStore
import io.github.iaguito22.oto.ui.menu.MenuController
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment

@Composable
fun ApplicationScope.ModularDesktop(player: DesktopPlayerConnection, prefs: Prefs, menu: MenuController,
    importer: Importer, account: Account, cloud: Cloud, login: BrowserLogin, downloads: DesktopDownloads,
    lyricsStore: LyricsStore, quit: () -> Unit) {
    val kv = remember { OtoDirs.keyValue("modular_windows") }
    var shortcut by remember { mutableStateOf(kv.getString("shortcut") ?: "CTRL + ALT + O") }
    var playerOpen by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var devicesOpen by remember { mutableStateOf(false) }
    val connectionScope = rememberCoroutineScope()
    var deviceRevision by remember { mutableIntStateOf(0) }
    val devices = remember(deviceRevision) { DeviceConnection(player, prefs, account, connectionScope) }
    DisposableEffect(devices) { onDispose { devices.close() } }
    var lyricsOpen by remember { mutableStateOf(false) }
    var lyricsHover by remember { mutableStateOf(false) }
    var lyricsScale by remember {
        if (kv.getString("lyrics_scale_default_v2") != "true") {
            val previous = kv.getString("lyrics_scale")?.toFloatOrNull()
            if (previous == null || previous == 1f) kv.putString("lyrics_scale", "1.2")
            kv.putString("lyrics_scale_default_v2", "true")
        }
        mutableFloatStateOf(kv.getString("lyrics_scale")?.toFloatOrNull()?.coerceIn(.7f,1.6f) ?: 1.2f)
    }
    fun changeLyricsScale(delta:Float) { lyricsScale=(lyricsScale+delta).coerceIn(.7f,1.6f); kv.putString("lyrics_scale",lyricsScale.toString()) }
    var form by remember { mutableStateOf(PlayerForm.entries.firstOrNull { it.name == kv.getString("player_form") } ?: PlayerForm.Full) }
    var pointerInside by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    LaunchedEffect(pointerInside, options) {
        if(pointerInside || options) chrome = true else { kotlinx.coroutines.delay(450); chrome = false }
    }
    var artworkBackground by remember { mutableStateOf(kv.getString("artwork_background") == "true") }
    val source by player.source.collectAsState()
    var jump by remember { mutableStateOf(LibraryJump()) }
    val coverBeat by prefs.coverBeat.collectAsState(initial = true)
    val showRemaining by prefs.showRemaining.collectAsState(initial = false)
    var pinned by remember { mutableStateOf(kv.getString("pinned") == "true") }
    var libraryRequest by remember { mutableStateOf(0 to 0) }
    var librarySearchFocused by remember { mutableStateOf(false) }
    var savingTrack by remember { mutableStateOf<Heard?>(null) }
    fun openSave(track: Heard, origin: String) {
        timber.log.Timber.i("Abrir Guardar: %s", origin)
        savingTrack = track
    }
    val nowPlaying by player.state.collectAsState()
    val queue by player.queue.collectAsState()
    val shuffle by player.shuffle.collectAsState()
    val loop by player.loop.collectAsState()
    val lyrics by player.lyrics.collectAsState()
    val lyricsLoading by player.lyricsLoading.collectAsState()
    val volume by player.volume.collectAsState()
    val likedIds by prefs.likedIds.collectAsState(initial = emptySet())
    val likedList by prefs.likedList.collectAsState(initial = LocalPlaylist(LIKED_ID, "Me gusta", emptyList()))
    val playlists by prefs.playlists.collectAsState(initial = emptyList())
    val isLiked = nowPlaying.id != null && nowPlaying.id in likedIds
    val uiScope = rememberCoroutineScope()
    var artwork by remember { mutableStateOf<ImageBitmap?>(null) }
    var palette by remember { mutableStateOf(ArtworkPalette()) }
    LaunchedEffect(nowPlaying.artwork) { val (bmp, pal) = ArtworkLoader.load(nowPlaying.artwork); artwork = bmp; palette = pal }
    val backdrop by produceState<ImageBitmap?>(null, artwork, artworkBackground) { value = if(artworkBackground && artwork != null) blurredBackdrop(artwork!!) else null }
    fun like() { nowPlaying.id?.let { id -> uiScope.launch { prefs.setLiked(Heard(id, nowPlaying.title, nowPlaying.artist, nowPlaying.artwork), !isLiked) } } }
    val playerWindow = rememberModuleState("player", 320, 560)
    val libraryWindow = rememberModuleState("library", 960, 740)
    val queueWindow = rememberModuleState("queue", 420, 580)
    val lyricsWindow = rememberModuleState("lyrics", 480, 680)
    var playerFrame by remember { mutableStateOf<java.awt.Window?>(null) }
    var formRequest by remember { mutableIntStateOf(0) }
    fun changeForm(selected:PlayerForm) {
        form=selected;kv.putString("player_form",selected.name);formRequest++
    }
    fun shortcuts(event: KeyEvent): Boolean {
        if(event.type != KeyEventType.KeyDown) return false
        return when {
            event.isCtrlPressed && event.isShiftPressed && event.key == Key.M -> {changeForm(PlayerForm.entries[(form.ordinal+1)%PlayerForm.entries.size]);true}
            event.isCtrlPressed && (event.key == Key.K || event.key == Key.F) -> { menuOpen = true;true }
            event.isCtrlPressed && event.key == Key.B -> { nowPlaying.id?.let{openSave(Heard(it,nowPlaying.title,nowPlaying.artist,nowPlaying.artwork), "atajo Ctrl+B")};true }
            event.key == Key.F1 -> { libraryRequest=(libraryRequest.first+1) to 0;menuOpen=true;true }
            event.key == Key.F2 -> { libraryRequest=(libraryRequest.first+1) to 1;menuOpen=true;true }
            event.key == Key.Spacebar || event.key == Key.MediaPlayPause -> { player.toggle();true }
            event.isCtrlPressed && event.key == Key.DirectionRight -> { player.next();true }
            event.isCtrlPressed && event.key == Key.DirectionLeft -> { player.previous();true }
            event.isCtrlPressed && event.key == Key.DirectionUp -> { player.adjustVolume(.04f);true }
            event.isCtrlPressed && event.key == Key.DirectionDown -> { player.adjustVolume(-.04f);true }
            else -> false
        }
    }
    DisposableEffect(shortcut) {
        val access = DesktopAccess(shortcut) { force -> java.awt.EventQueue.invokeLater { playerOpen = force || !playerOpen; if (playerOpen) { playerWindow.isMinimized = false; playerFrame?.toFront(); playerFrame?.requestFocus() } } }
        onDispose { access.close() }
    }
    if (java.awt.SystemTray.isSupported()) Tray(icon = painterResource("oto-icon.png"), tooltip = "Oto", onAction = { playerOpen = !playerOpen }, menu = {
        Item("Reproductor", onClick = { playerOpen = true }); Item("Tu música", onClick = { menuOpen = true })
        Item("Letra", onClick = { lyricsOpen = true }); Item("Cola", onClick = { queueOpen = true }); Separator(); Item("Salir de Oto", onClick = quit)
    })
    ModuleWindow("Reproductor", playerWindow, playerOpen, { playerOpen = false }, pinned, accent = palette.primary,
        keys = ::shortcuts, onWindow = { playerFrame = it }, immersive = true, chrome = chrome,
        minimum = if(form == PlayerForm.Vertical) DpSize(96.dp,240.dp) else if(form == PlayerForm.Horizontal) DpSize(280.dp,108.dp) else DpSize(260.dp,370.dp),
        backgroundArtwork = if(artworkBackground) backdrop else null, actionsOnRight = form == PlayerForm.Horizontal, hover = {pointerInside = it}, resizeRequest = formRequest, requestedSize = DpSize(form.width.dp,form.height.dp), extra = {
            Box {
                SmallAction(Icons.Rounded.MoreHoriz,"Opciones") { options = !options }
                PlayerOptions(options,{options=false},form,{ selected -> changeForm(selected)
                },volume,{player.adjustVolume(it-volume)}, {options=false;devicesOpen=true}, {
                    options=false
                    val bounds=GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                    val states=listOfNotNull(playerWindow.takeIf{playerOpen},libraryWindow.takeIf{menuOpen},lyricsWindow.takeIf{lyricsOpen},queueWindow.takeIf{queueOpen})
                    val cols=if(states.size>1)2 else 1;val rows=(states.size+cols-1)/cols
                    val cellW=(bounds.width-72)/cols;val cellH=(bounds.height-72)/rows.coerceAtLeast(1)
                    states.forEachIndexed {i,st -> st.size=DpSize((if(st===playerWindow)form.width else cellW-16).dp,(if(st===playerWindow)minOf(form.height,cellH-16)else cellH-16).dp);st.position=WindowPosition((bounds.x+36+i%cols*cellW).dp,(bounds.y+36+i/cols*cellH).dp)}
                },quit)
            }
            SmallAction(Icons.Rounded.PushPin, if(pinned) "Desfijar" else "Fijar sobre otras ventanas") { pinned = !pinned;kv.putString("pinned",pinned.toString()) }
        }) {
        @Composable fun PlayerContents() {
        val positionMs by player.positionMs.collectAsState()
        PlayerSurface(nowPlaying,positionMs,artwork,form,chrome,player::beat,coverBeat,isLiked,showRemaining,
            player::toggle,player::previous,player::next,player::seekTo,menuOpen,lyricsOpen,queueOpen,
            {menuOpen=!menuOpen},{lyricsOpen=!lyricsOpen},{queueOpen=!queueOpen},::like,
            {nowPlaying.id?.let{openSave(Heard(it,nowPlaying.title,nowPlaying.artist,nowPlaying.artwork), "reproductor")}}, source, {
                val src=source; if(src?.item != null || src?.local != null) { jump=LibraryJump(jump.version+1,src?.item,src?.local);menuOpen=true } else if(src?.kind != "DJ") queueOpen=true
            }, {id,name -> jump=LibraryJump(jump.version+1,artistId=id,artistName=name);menuOpen=true})
        }
        PlayerContents()
    }
    ModuleWindow("Dispositivos", rememberModuleState("devices", 410, 720), devicesOpen, { devicesOpen = false }, minimum=DpSize(360.dp,480.dp), accent=palette.primary, keys = ::shortcuts) { DevicesPane(devices, shortcut, { shortcut = it; kv.putString("shortcut", it) }) { devices.close(); OtoDirs.keyValue("local_link").putString("secret", null); deviceRevision++ } }
    ModuleWindow("Letra", lyricsWindow, lyricsOpen, { lyricsOpen = false }, keys = ::shortcuts, backgroundArtwork = if(artworkBackground) backdrop else null, hover={lyricsHover=it}, extra={
        if(lyricsHover) {
            SmallAction(Icons.Rounded.Remove,"Letra más pequeña") { changeLyricsScale(-.1f) }
            SmallAction(Icons.Rounded.Add,"Letra más grande") { changeLyricsScale(.1f) }
        }
    }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                LibraryArt(nowPlaying.artwork,Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f)) {
                    Text(nowPlaying.title, style = OtoType.rowTitle, maxLines = 1)
                    Text(nowPlaying.artist, style = OtoType.rowSub, color = Color.White.copy(alpha = .5f),maxLines=1)
                }
            }
            val romanize by prefs.romanize.collectAsState(initial=true)
            PlaylistContextMenu(listOf(
                ContextMenuItem(if(romanize) "Ocultar romanización" else "Mostrar romanización") { uiScope.launch { prefs.setRomanize(!romanize) } },
                ContextMenuItem("Letra más grande") { changeLyricsScale(.1f) },
                ContextMenuItem("Letra más pequeña") { changeLyricsScale(-.1f) },
                ContextMenuItem("Restablecer tamaño") { lyricsScale=1.2f; kv.putString("lyrics_scale","1.2") },
            )) {
                MobileLyrics(nowPlaying.id, lyrics, lyricsLoading, nowPlaying.playing && !nowPlaying.buffering, player::position, player::seekTo, lyricsStore, prefs, lyricsScale)
            }
        }
    }
    ModuleWindow("Cola", queueWindow, queueOpen, { queueOpen = false }, keys = ::shortcuts) {
        Box(Modifier.fillMaxSize().padding(start=20.dp,end=20.dp,bottom=20.dp,top=4.dp)) { QueuePane(queue, nowPlaying.id, shuffle, loop, palette, { player.setShuffle(!shuffle) }, { player.setLoop(!loop) }, player::move, player::remove, player::clearQueue, player::skipTo) }
    }
    ModuleWindow("Tu música", libraryWindow, menuOpen, {menuOpen=false}, keys={event -> if(librarySearchFocused && event.key==Key.Spacebar) false else shortcuts(event)}) {
        LibrarySpace(player,prefs,menu,importer,account,cloud,login,downloads,palette,{openSave(it, "biblioteca")},{devicesOpen=true},libraryRequest,jump,artworkBackground,{ artworkBackground=it;kv.putString("artwork_background",it.toString()) },{librarySearchFocused=it},{ devices.requestAccount(); devicesOpen=true })
    }
    ModuleWindow("Guardar",rememberModuleState("save",380,540),savingTrack!=null,{savingTrack=null}) {
        savingTrack?.let { SaveSpace(it,prefs,palette) }
    }
}

@Composable
private fun rememberModuleState(id: String, w: Int, h: Int): WindowState {
    val kv = remember { OtoDirs.keyValue("modular_windows") }
    val initialPosition = remember {
        val point = java.awt.MouseInfo.getPointerInfo()?.location ?: java.awt.Point(0, 0)
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }.firstOrNull { it.contains(point) }
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        val x = when(id) { "player" -> bounds.x + bounds.width - w - 36; "library" -> bounds.x + 48; else -> bounds.x + (bounds.width-w)/2 }
        WindowPosition(x.coerceAtLeast(bounds.x).dp, (bounds.y + 56).dp)
    }
    val state = rememberWindowState(position = initialPosition, size = DpSize(kv.getLong("${id}_w", w.toLong()).coerceIn(if(id=="player")116 else 260, 2400).toInt().dp, kv.getLong("${id}_h", h.toLong()).coerceIn(if(id=="player")108 else 220, 1600).toInt().dp))
    LaunchedEffect(state.size, state.position) {
        if (state.position is WindowPosition.Absolute) {
            val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
            val bounds = screens.firstOrNull { it.contains(state.position.x.value.toInt(), state.position.y.value.toInt()) } ?: screens.first()
            val width = state.size.width.value.coerceAtMost((bounds.width - 24).toFloat())
            val height = state.size.height.value.coerceAtMost((bounds.height - 24).toFloat())
            val x = state.position.x.value.coerceIn(bounds.x.toFloat(), (bounds.x + bounds.width - width).coerceAtLeast(bounds.x.toFloat()))
            val y = state.position.y.value.coerceIn(bounds.y.toFloat(), (bounds.y + bounds.height - height).coerceAtLeast(bounds.y.toFloat()))
            if (state.size != DpSize(width.dp,height.dp) || state.position.x != x.dp || state.position.y != y.dp) {
                state.size = DpSize(width.dp,height.dp); state.position = WindowPosition(x.dp,y.dp); return@LaunchedEffect
            }
        }
        kotlinx.coroutines.delay(400)
        kv.putLong("${id}_w", state.size.width.value.toLong()); kv.putLong("${id}_h", state.size.height.value.toLong())
        if (state.position is WindowPosition.Absolute) {
            kv.putLong("${id}_x", state.position.x.value.toLong()); kv.putLong("${id}_y", state.position.y.value.toLong())
        }
    }
    LaunchedEffect(Unit) {
        val x = kv.getLong("${id}_x", Long.MIN_VALUE); val y = kv.getLong("${id}_y", Long.MIN_VALUE)
        if (x != Long.MIN_VALUE && y != Long.MIN_VALUE && GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.contains(x.toInt(), y.toInt()) }) state.position = WindowPosition(x.toInt().dp, y.toInt().dp)
    }
    return state
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipArea(tooltip = { Surface(color = Color(0xFF30362F), shape = RoundedCornerShape(8.dp)) { Text(label, Modifier.padding(8.dp), style = OtoType.rowSub) } }) {
        HoverAction(icon,label,onClick=onClick)
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ModuleWindow(title: String, state: WindowState, visible: Boolean, close: () -> Unit, pinned: Boolean = false, accent: Color = Color(0xFFE2EADB),
    keys: (KeyEvent) -> Boolean = { false },
    immersive:Boolean=false, chrome:Boolean=true, hover:(Boolean)->Unit={}, minimum:DpSize=DpSize(260.dp,250.dp), resizeRequest:Int=0, requestedSize:DpSize?=null,
    backgroundArtwork:ImageBitmap?=null, actionsOnRight:Boolean=false, onWindow: (java.awt.Window) -> Unit = {}, extra: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    if (!visible && title != "Reproductor") return
    Window(onCloseRequest = close, state = state, visible = visible, title = "Oto · $title", undecorated = true, transparent = true, alwaysOnTop = pinned,
        icon = painterResource("oto-icon.png"), onKeyEvent = { if (keys(it)) true else if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false }) {
        val density = LocalDensity.current
        SideEffect { window.minimumSize = java.awt.Dimension(if(title == "Tu música") 640 else minimum.width.value.toInt(), if(title == "Tu música") 480 else minimum.height.value.toInt()); onWindow(window) }
        LaunchedEffect(resizeRequest) {
            if(resizeRequest>0 && requestedSize!=null) {
                java.awt.EventQueue.invokeLater {
                    window.minimumSize=java.awt.Dimension(minimum.width.value.toInt(),minimum.height.value.toInt())
                    window.setSize(requestedSize.width.value.toInt(),requestedSize.height.value.toInt())
                    state.size=requestedSize
                    timber.log.Timber.d("Vista aplicada: mínimo=%s tamaño=%s",window.minimumSize,window.size)
                }
            }
        }
        LaunchedEffect(pinned) {
            if (title == "Reproductor" && System.getenv("HYPRLAND_INSTANCE_SIGNATURE") != null) {
                kotlinx.coroutines.delay(200)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ProcessBuilder("hyprctl", "dispatch", "hl.dsp.window.pin({action=\"${if(pinned) "on" else "off"}\",window=\"title:Oto · Reproductor\"})").start().waitFor() } }
            }
        }
        MaterialTheme(colorScheme = darkColorScheme(primary = accent, onPrimary = Color(0xFF101010), primaryContainer = Color(0xFF303630), secondary = accent, secondaryContainer = Color(0xFF303630), tertiary = accent, background = Color(0xFF171A18), surface = Color(0xFF171A18), surfaceVariant = Color(0xFF343A34), surfaceTint = Color.Transparent, surfaceContainerLowest = Color(0xFF101310), surfaceContainerLow = Color(0xFF1C201C), surfaceContainer = Color(0xFF222722), surfaceContainerHigh = Color(0xFF292F29), surfaceContainerHighest = Color(0xFF343A34)), typography = OtoTypography) {
            Surface(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(22.dp)), color = Color(0xFF171A18), shape = RoundedCornerShape(22.dp)) {
                val chromeAlpha by animateFloatAsState(if(chrome)1f else 0f,tween(180),label="windowChrome")
                Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Enter){hover(true)}.onPointerEvent(PointerEventType.Exit){hover(false)}) {
                    if(backgroundArtwork != null) PlayerBackdrop(backgroundArtwork)
                    Box(Modifier.fillMaxSize().padding(top=if(immersive)0.dp else 44.dp)) { content() }
                    if(title=="Tu música") Canvas(Modifier.align(Alignment.TopStart).padding(start=12.dp,top=20.dp).size(48.dp)) {
                        drawCircle(Color.White,radius=size.minDimension*.47f,center=Offset(size.width*.5f,size.height*.5f),style=Stroke(width=size.minDimension*.045f))
                        drawCircle(Color.White,radius=size.minDimension*.17f,center=Offset(size.width*.33f,size.height*.33f))
                    }
                    WindowDraggableArea(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(if(immersive)36.dp else 44.dp)) {
                        BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer {alpha=chromeAlpha}) {
                            val showTitle=!immersive && title != "Tu música" && title != "Letra" && title != "Cola" && maxWidth>200.dp
                            Row(Modifier.fillMaxSize().background(Color.Transparent).padding(start=if(showTitle)18.dp else if(title=="Letra")12.dp else 0.dp,end=2.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.End) {
                                if(showTitle)Text(title,Modifier.weight(1f),style=OtoType.rowSub,color=Color.White.copy(alpha=.4f))
                                if(!showTitle && actionsOnRight) Spacer(Modifier.weight(1f))
                                if(chrome) {extra()}
                                if(!showTitle && !actionsOnRight) Spacer(Modifier.weight(1f))
                                if(chrome) SmallAction(Icons.Rounded.Close,"Cerrar $title",close)
                            }
                        }
                    }
                    Box(Modifier.align(Alignment.BottomEnd).size(16.dp).graphicsLayer{alpha=if(immersive)chromeAlpha else .5f}.pointerInput(minimum) {
                        detectDragGestures {change,delta -> change.consume();with(density){state.size=DpSize((state.size.width+delta.x.toDp()).coerceIn(if(title=="Tu música")640.dp else minimum.width,2400.dp),(state.size.height+delta.y.toDp()).coerceIn(if(title=="Tu música")480.dp else minimum.height,1600.dp))}}
                    }) {Text("⌟",color=Color.White.copy(alpha=.25f))}
                }
            }
        }
    }
}
