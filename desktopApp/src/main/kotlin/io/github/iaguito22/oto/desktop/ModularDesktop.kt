package io.github.iaguito22.oto.desktop

import androidx.compose.foundation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.PushPin as OutlinedPushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.TransformOrigin
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
    // Compose termina su sesión al desmontar la última Window. La bandeja usa AWT,
    // así que necesitamos una ventana invisible que mantenga la sesión hasta Salir.
    Window(onCloseRequest = quit, visible = false, title = "Oto · Sesión",
        state = rememberWindowState(width = 1.dp, height = 1.dp), undecorated = true) { }
    val kv = remember { OtoDirs.keyValue("modular_windows") }
    val welcomePrefs = remember { OtoDirs.keyValue("desktop_welcome") }
    var welcomeOpen by remember { mutableStateOf(welcomePrefs.getString("seen_version") != "1") }
    fun closeWelcome() { welcomePrefs.putString("seen_version", "1"); welcomeOpen = false }
    if (welcomeOpen) WelcomeWindow(::closeWelcome)
    DisposableEffect(login) { onDispose { login.cancel() } }
    var shortcut by remember { mutableStateOf(kv.getString("shortcut") ?: "CTRL + ALT + O") }
    var shortcutIssue by remember { mutableStateOf<String?>(null) }
    var form by remember { mutableStateOf(PlayerForm.entries.firstOrNull { it.name == kv.getString("player_form") } ?: PlayerForm.Full) }
    val docking = remember {
        val layout = decodeDockLayout(kv.getString("docking_v1")) ?: initialDockLayout()
        layout.minimums[DockId.Player] = form.minimumSize
        layout.defaults[DockId.Player] = form.width
        DockSession(layout).apply {
            if(layout.bounds.isEmpty()) open(DockId.Player)
            layout.preparePlayerOpening(form)
        }
    }
    var playerOpen by remember { DockVisibility(docking,DockId.Player) }
    LaunchedEffect(docking.revision) {
        kotlinx.coroutines.delay(400)
        kv.putString("docking_v1",docking.layout.encode())
    }
    DisposableEffect(docking) { onDispose { kv.putString("docking_v1",docking.layout.encode()) } }
    var menuOpen by remember { DockVisibility(docking,DockId.Library) }
    LaunchedEffect(Unit) {
        if(System.getenv("OTO_MOTION_PROBE")=="1" && java.io.File(OtoDirs.filesDir,"motion-probe.marker").isFile) {
            kotlinx.coroutines.delay(4000)
            repeat(5) {
                menuOpen=false
                kotlinx.coroutines.delay(1200)
                menuOpen=true
                kotlinx.coroutines.delay(2200)
            }
            kotlinx.coroutines.delay(1000)
            quit()
        }
    }
    var queueOpen by remember { DockVisibility(docking,DockId.Queue) }
    var devicesOpen by remember { mutableStateOf(false) }
    val connectionScope = rememberCoroutineScope()
    var deviceRevision by remember { mutableIntStateOf(0) }
    val devices = remember(deviceRevision) { DeviceConnection(player, prefs, account, connectionScope) }
    DisposableEffect(devices) { onDispose { devices.close() } }
    var lyricsOpen by remember { DockVisibility(docking,DockId.Lyrics) }
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
    var pointerInside by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    LaunchedEffect(pointerInside, options) {
        if(pointerInside || options) chrome = true else { kotlinx.coroutines.delay(450); chrome = false }
    }
    var artworkBackground by remember { mutableStateOf(kv.getString("artwork_background") != "false") }
    var backdropOpacity by remember { mutableFloatStateOf(kv.getString("artwork_opacity")?.toFloatOrNull()?.coerceIn(0f,1f) ?: .75f) }
    var windowsHidden by remember { mutableStateOf(false) }
    fun closePrimary(id: DockId) {
        val other = if(id == DockId.Player) DockId.Library else DockId.Player
        if(other !in docking.layout.bounds) windowsHidden = true else docking.close(id)
    }
    fun showOrHide(force: Boolean = false) {
        windowsHidden = if(force) false else !windowsHidden
        if (!windowsHidden && docking.layout.bounds.isEmpty()) docking.open(DockId.Player)
    }
    val source by player.source.collectAsState()
    var jump by remember { mutableStateOf(LibraryJump()) }
    val coverBeat by prefs.coverBeat.collectAsState(initial = true)
    val showRemaining by prefs.showRemaining.collectAsState(initial = false)
    var pinned by remember { mutableStateOf(kv.getString("pinned") == "true") }
    val playerSolo = playerOpen && docking.layout.group(DockId.Player).members.size == 1
    LaunchedEffect(playerSolo) { if(!playerSolo) { pinned=false; kv.putString("pinned","false") } }
    var libraryRequest by remember { mutableStateOf(0 to 0) }
    var librarySearchFocused by remember { mutableStateOf(false) }
    var savingTrack by remember { mutableStateOf<Heard?>(null) }
    fun openSave(track: Heard, origin: String) {
        timber.log.Timber.i("Abrir Guardar: %s", origin)
        savingTrack = track
    }
    val nowPlaying by player.state.collectAsState()
    val queue by player.queue.collectAsState()
    val ramSaver by prefs.ramSaver.collectAsState(initial = false)
    LaunchedEffect(ramSaver) { RamSaver.apply(ramSaver) }
    val nothingShown = windowsHidden || (!playerOpen && !menuOpen && !lyricsOpen && !queueOpen && !devicesOpen && savingTrack == null)
    LaunchedEffect(ramSaver, nothingShown) { if (ramSaver && nothingShown) RamSaver.whileHidden() }
    val nextArtwork = if(ramSaver) emptyList() else queue.filter { it.index > nowPlaying.index }.take(2).mapNotNull { it.artwork }
    LaunchedEffect(nextArtwork) {
        nextArtwork.forEach { ArtworkLoader.load(it) }
    }
    val shuffle by player.shuffle.collectAsState()
    val loop by player.loop.collectAsState()
    val loopMode by player.loopMode.collectAsState()
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
    val playerWindow = rememberDockState(docking,DockId.Player)
    val libraryWindow = rememberDockState(docking,DockId.Library)
    val queueWindow = rememberDockState(docking,DockId.Queue)
    val lyricsWindow = rememberDockState(docking,DockId.Lyrics)
    var playerFrame by remember { mutableStateOf<java.awt.Window?>(null) }
    LaunchedEffect(playerOpen, windowsHidden, form) {
        if (playerOpen && !windowsHidden) {
            val minimum = form.minimumSize
            playerFrame?.minimumSize = java.awt.Dimension(minimum.first, minimum.second)
            docking.layout.preparePlayerOpening(form)
            val rect = docking.layout.bounds.getValue(DockId.Player)
            docking.layout.constrain(DockId.Player, desktopArea(java.awt.Point(rect.x, rect.y)))
        }
    }
    val soloPlayerBounds = if(playerOpen && playerSolo) docking.layout.bounds[DockId.Player] else null
    LaunchedEffect(form, soloPlayerBounds?.width, soloPlayerBounds?.height) {
        val bounds = soloPlayerBounds ?: return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        kv.putLong("form_${form.name}_w", bounds.width.toLong())
        kv.putLong("form_${form.name}_h", bounds.height.toLong())
    }
    fun changeForm(selected:PlayerForm) {
        if (selected == form) {
            docking.layout.preparePlayerOpening(form)
            return
        }
        if (playerOpen && docking.layout.group(DockId.Player).members.size == 1) {
            docking.layout.bounds[DockId.Player]?.let { bounds ->
                kv.putLong("form_${form.name}_w", bounds.width.toLong())
                kv.putLong("form_${form.name}_h", bounds.height.toLong())
            }
        }
        // Ignore delayed native geometry notifications while separating and resizing.
        docking.geometrySettlesAt = System.nanoTime() + 400_000_000L
        if (playerOpen && docking.layout.group(DockId.Player).members.size > 1) {
            val old = docking.layout.bounds.getValue(DockId.Player)
            docking.layout.close(DockId.Player)
            docking.layout.create(DockId.Player, old.copy(x=old.x+32,y=old.y+32))
        }
        form=selected;kv.putString("player_form",selected.name)
        val minimum = selected.minimumSize
        // Actualizar el límite nativo antes de reducir la geometría: de lo contrario
        // AWT conserva la altura mínima del formato anterior al pasar a horizontal.
        playerFrame?.minimumSize = java.awt.Dimension(minimum.first, minimum.second)
        docking.layout.setMinimum(DockId.Player,minimum.first,minimum.second,selected.width)
        if(playerOpen) {
            val size = selected.openingSize(
                kv.getLong("form_${selected.name}_w", selected.width.toLong()).toInt(),
                kv.getLong("form_${selected.name}_h", selected.height.toLong()).toInt())
            docking.layout.resize(DockId.Player,size.first,size.second)
            val r = docking.layout.bounds.getValue(DockId.Player)
            docking.layout.constrain(DockId.Player, desktopArea(java.awt.Point(r.x,r.y)))
        }
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
    @Composable fun WindowOptions(open: Boolean, setOpen: (Boolean) -> Unit) {
        Box {
            SmallAction(Icons.Rounded.MoreHoriz,"Opciones") { setOpen(!open); docking.trace("options=${!open}") }
            PlayerOptions(open,{setOpen(false)},form,::changeForm,volume,{player.adjustVolume(it-volume)},
                {setOpen(false);devicesOpen=true},
                {setOpen(false);docking.layout.organize(desktopArea())},quit, { playerFrame?.bounds }, welcome = {setOpen(false);welcomeOpen=true})
        }
    }
    DisposableEffect(shortcut) {
        val access = DesktopAccess(shortcut) { force -> java.awt.EventQueue.invokeLater { showOrHide(force) } }
        shortcutIssue = access.issue
        onDispose { access.close() }
    }
    OtoTray(toggle={showOrHide()}, player={windowsHidden=false; playerOpen=true}, library={windowsHidden=false; menuOpen=true},
        lyrics={windowsHidden=false; if(!playerOpen && !menuOpen) playerOpen=true; lyricsOpen=true},
        queue={windowsHidden=false; if(!playerOpen && !menuOpen) playerOpen=true; queueOpen=true}, quit=quit,
        playing=nowPlaying.playing, hasSong=nowPlaying.id != null, previous=player::previous, playPause=player::toggle, next=player::next,
        volume=volume, setVolume=player::setVolume)
    ModuleWindow("Reproductor", playerWindow, playerOpen && !windowsHidden, { closePrimary(DockId.Player) }, pinned && playerSolo, accent = palette.primary, dock=docking, dockId=DockId.Player,
        keys = ::shortcuts, onWindow = { playerFrame = it }, immersive = true, chrome = chrome,
        minimum = if(form == PlayerForm.Vertical) DpSize(96.dp,240.dp) else if(form == PlayerForm.Horizontal) DpSize(280.dp,108.dp) else DpSize(260.dp,370.dp),
        backgroundArtwork = if(artworkBackground) backdrop else null, backgroundOpacity=backdropOpacity, actionsOnRight = form == PlayerForm.Horizontal, hover = {pointerInside = it}, extra = {
            WindowOptions(options,{options=it})
            if(playerSolo && form != PlayerForm.Vertical) PinAction(pinned) { pinned = !pinned;kv.putString("pinned",pinned.toString()) }
        }) {
        @Composable fun PlayerContents() {
        val quality by player.currentQuality.collectAsState()
        val qualityLabel = quality?.let { q -> listOfNotNull(q.codec.uppercase(),q.bitrate?.let { "${(it+500)/1000} kbps" },q.sampleRate?.let { if(it%1000==0) "${it/1000} kHz" else "${it/1000f} kHz" }).joinToString(" · ") }
        PlayerSurface(nowPlaying,player.positionMs,artwork,form,chrome,player::beat,coverBeat,isLiked,showRemaining,
            player::toggle,player::previous,player::next,player::seekTo,menuOpen,lyricsOpen,queueOpen,
            {menuOpen=!menuOpen},{lyricsOpen=!lyricsOpen},{queueOpen=!queueOpen},::like,
            {nowPlaying.id?.let{openSave(Heard(it,nowPlaying.title,nowPlaying.artist,nowPlaying.artwork), "reproductor")}}, source, {
                val src=source; if(src?.item != null || src?.local != null) { jump=LibraryJump(jump.version+1,src?.item,src?.local);menuOpen=true } else if(src?.kind != "DJ") queueOpen=true
            }, {id,name -> jump=LibraryJump(jump.version+1,artistId=id,artistName=name);menuOpen=true},
            qualityLabel = qualityLabel,
            shuffle = shuffle, loopMode = loopMode, onShuffle = { player.setShuffle(!shuffle) },
            onLoop = { player.setLoop(when(loopMode) { io.github.iaguito22.oto.LoopMode.Off -> io.github.iaguito22.oto.LoopMode.All
                io.github.iaguito22.oto.LoopMode.All -> io.github.iaguito22.oto.LoopMode.One
                io.github.iaguito22.oto.LoopMode.One -> io.github.iaguito22.oto.LoopMode.Off }) },
            verticalPin =if(playerSolo && form==PlayerForm.Vertical) ({
                HoverAction(pinIcon(pinned),if(pinned) "Desfijar" else "Fijar sobre otras ventanas",enabled=chrome,
                    onClick={pinned=!pinned;kv.putString("pinned",pinned.toString())},
                    tint=pinTint(pinned),size=28.dp)
            }) else null)
        }
        PlayerContents()
    }
    ModuleWindow("Dispositivos", rememberModuleState("devices", 410, 720), devicesOpen && !windowsHidden, { devicesOpen = false }, minimum=DpSize(360.dp,480.dp), accent=palette.primary, keys = ::shortcuts) { DevicesPane(devices) { devices.close(); OtoDirs.keyValue("local_link").putString("secret", null); deviceRevision++ } }
    ModuleWindow("Letra", lyricsWindow, lyricsOpen && !windowsHidden, { lyricsOpen = false }, dock=docking, dockId=DockId.Lyrics, keys = ::shortcuts, chrome=lyricsHover, backgroundArtwork = if(artworkBackground) backdrop else null, backgroundOpacity=backdropOpacity, hover={lyricsHover=it}, extra={
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
    ModuleWindow("Cola", queueWindow, queueOpen && !windowsHidden, { queueOpen = false }, dock=docking, dockId=DockId.Queue, keys = ::shortcuts, backgroundArtwork = backdrop, backgroundOpacity=backdropOpacity) {
        Box(Modifier.fillMaxSize().padding(start=20.dp,end=20.dp,bottom=20.dp,top=4.dp)) { QueuePane(queue, nowPlaying.id, shuffle, loop, palette, { player.setShuffle(!shuffle) }, { player.cycleLoop() }, player::move, player::remove, player::clearQueue, player::skipTo, currentIndex = nowPlaying.index, loopMode = loopMode) }
    }
    ModuleWindow("Tu música", libraryWindow, menuOpen && !windowsHidden, {closePrimary(DockId.Library)}, dock=docking, dockId=DockId.Library, backgroundArtwork = backdrop, backgroundOpacity=backdropOpacity,
        extra={
            SmallAction(Icons.Rounded.PlayArrow,if(playerOpen) "Cerrar reproductor" else "Abrir reproductor",active=playerOpen) { docking.toggle(DockId.Player,DockId.Library) }
            SmallAction(MobileIcons.lyrics,if(lyricsOpen) "Cerrar letra" else "Abrir letra",active=lyricsOpen) { docking.toggle(DockId.Lyrics,DockId.Library) }
            SmallAction(MobileIcons.queueMusic,if(queueOpen) "Cerrar cola" else "Abrir cola",active=queueOpen) { docking.toggle(DockId.Queue,DockId.Library) }        },keys={event -> if(librarySearchFocused && event.key==Key.Spacebar) false else shortcuts(event)}) {
        LibrarySpace(player,prefs,menu,importer,account,cloud,login,downloads,palette,{openSave(it, "biblioteca")},{devicesOpen=true},libraryRequest,jump,artworkBackground,{ artworkBackground=it;kv.putString("artwork_background",it.toString()) },{librarySearchFocused=it},{ devices.requestAccount(); devicesOpen=true }, backdrop != null, backdropOpacity,{backdropOpacity=it;kv.putString("artwork_opacity",it.toString())}, { val src=source; if(src?.item != null || src?.local != null) jump=LibraryJump(jump.version+1,src?.item,src?.local) else if(src?.kind != "DJ") queueOpen=true }, shortcut,shortcutIssue,{shortcut=it;kv.putString("shortcut",it)},playerVisible=playerOpen,remoteLink=devices.link)
    }
    ModuleWindow("Guardar",rememberModuleState("save",380,540),savingTrack!=null && !windowsHidden,{savingTrack=null}) {
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

/** Como el corazón de Me gusta: hueca al estar suelta, rellena al fijarla, sin recuadro. */
private fun pinIcon(pinned: Boolean): ImageVector = if(pinned) Icons.Rounded.PushPin else Icons.Outlined.OutlinedPushPin
private fun pinTint(pinned: Boolean) = Color.White.copy(alpha=if(pinned) .95f else .55f)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PinAction(pinned: Boolean, onClick: () -> Unit) {
    val label = if(pinned) "Desfijar" else "Fijar sobre otras ventanas"
    key(LocalWindowTooltipEpoch.current) {
        TooltipArea(tooltip = { Surface(color = Color(0xFF30362F), shape = RoundedCornerShape(8.dp)) { Text(label, Modifier.padding(8.dp), style = OtoType.rowSub) } }) {
            HoverAction(pinIcon(pinned),label,onClick=onClick,size=LocalWindowActionSize.current,tint=pinTint(pinned))
        }
    }
}

private val LocalWindowActionSize = staticCompositionLocalOf { 36.dp }
internal val LocalWindowTooltipEpoch = staticCompositionLocalOf { 0 }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SmallAction(icon: ImageVector, label: String, active:Boolean=false, onClick: () -> Unit) {
    key(LocalWindowTooltipEpoch.current) {
        TooltipArea(tooltip = { Surface(color = Color(0xFF30362F), shape = RoundedCornerShape(8.dp)) { Text(label, Modifier.padding(8.dp), style = OtoType.rowSub) } }) {
            // Abierto: el icono se ve más claro, sin recuadro.
            val on by animateFloatAsState(if(active) 1f else 0f, tween(180), label="activo")
            HoverAction(icon,label,onClick=onClick,size=LocalWindowActionSize.current,tint=Color.White.copy(alpha=.45f+.47f*on))
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ModuleWindow(title: String, state: WindowState, visible: Boolean, close: () -> Unit, pinned: Boolean = false, accent: Color = Color(0xFFE2EADB),
    dock: DockSession? = null, dockId: DockId? = null,
    keys: (KeyEvent) -> Boolean = { false },
    immersive:Boolean=false, chrome:Boolean=true, hover:(Boolean)->Unit={}, minimum:DpSize=DpSize((dockId?.minWidth ?: 260).dp,(dockId?.minHeight ?: 250).dp),
    backgroundArtwork:ImageBitmap?=null, backgroundOpacity:Float=1f, actionsOnRight:Boolean=false, onWindow: (java.awt.Window) -> Unit = {}, extra: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    var mounted by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) mounted = true
    }
    val retainedState = remember { object { var value=state } }
    if(visible) retainedState.value=state
    val windowState=retainedState.value
    if (!visible && !mounted) return
    val registered = dock == null || dockId == null || dockId in dock.layout.bounds
    dock?.revision
    val group = if(registered && dock != null && dockId != null) dock.layout.groups.firstOrNull { dockId in it.members } else null
    val groupIndex = group?.members?.indexOf(dockId) ?: 0
    val revealSide = remember { mutableIntStateOf(0) }
    if (group != null && group.members.size > 1) {
        val leaderIndex = group.members.indexOf(group.leader)
        revealSide.intValue = if(groupIndex < leaderIndex) -1 else if(groupIndex > leaderIndex) 1
            else if(groupIndex == 0) -1 else if(groupIndex == group.members.lastIndex) 1
            else if(groupIndex <= group.members.lastIndex / 2) -1 else 1
    } else if (visible) revealSide.intValue = 0
    val leftEdge = groupIndex == 0
    val rightEdge = group == null || groupIndex == group.members.lastIndex
    val groupBounds = group?.let { g ->
        val first = dock!!.layout.bounds[g.members.first()]
        if(first == null || g.members.any { it !in dock.layout.bounds }) null
        else DockRect(first.x, first.y, g.members.sumOf { dock.layout.bounds[it]?.width ?: 0 }, first.height)
    }
    val ownBounds = dockId?.let { dock?.layout?.bounds?.get(it) }
    val shape = RoundedCornerShape(22.dp)
    val highlighted = dockId != null && (dock?.preview?.target == dockId || dock?.reorderPreview?.second == dockId)
    val compactLibrary = title == "Tu música" && windowState.size.width <= 800.dp
    key(dockId ?: title) {
    Window(onCloseRequest = close, state = windowState, visible = true, title = "Oto · $title", undecorated = true, transparent = true, alwaysOnTop = pinned,
        icon = painterResource("oto-icon.png"), onKeyEvent = { if (keys(it)) true else if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false }) {
        SmoothWheel.Driver(window)
        val reveal = remember { Animatable(0f) }
        var warming by remember { mutableStateOf(visible) }
        val warmAlpha=remember { mutableFloatStateOf(.01f) }
        val firstDraw = remember { java.util.concurrent.atomic.AtomicLong(0L) }
        LaunchedEffect(visible) {
            val requestedAt=System.nanoTime()
            if (visible && reveal.value == 0f) {
                warming=true
                // El reloj puede avanzar mientras otra ventana se dibuja. Esperar
                // a que esta haya grabado su contenido, incluidos los textos.
                var warmFrame=0
                while(firstDraw.get()==0L && System.nanoTime()-requestedAt<1_200_000_000L) {
                    withFrameNanos { }
                    warmAlpha.floatValue=if(++warmFrame%2==0) .01f else .015f
                }
                // Completar la primera composición y el primer dibujo antes de empezar:
                // su coste no debe consumir la duración de la animación de entrada.
                val started = withFrameNanos { it }
                var previous = started
                var stableFrames = 0
                do {
                    val now = withFrameNanos { it }
                    warmAlpha.floatValue=if(++warmFrame%2==0) .01f else .015f
                    stableFrames = if(now - previous < 20_000_000L) stableFrames + 1 else 0
                    previous = now
                } while (previous - started < 1_200_000_000L &&
                    (previous - started < 150_000_000L || stableFrames < 3))
                // Si el conjunto se está apartando para hacerle sitio, entrar cuando termine.
                if (dock != null && dockId != null) dock.startPendingShift(dockId)
                while (dock != null && System.nanoTime() < dock.shiftEndsAt) withFrameNanos { }
            }
            warming=false
            val timings = if (System.getenv("OTO_MOTION_TRACE") == "1") mutableListOf<Double>() else null
            var previousFrame = System.nanoTime()
            val animationStarted=System.currentTimeMillis()
            reveal.animateTo(if (visible) 1f else 0f,
                // Entrada con rebote; salida con un leve impulso previo (ease-in-back) hacia el mismo lado.
                if (visible) spring(dampingRatio=.7f,stiffness=210f) else tween(300,easing=CubicBezierEasing(.42f,0f,.72f,-.32f))) {
                if (timings != null) {
                    val now = System.nanoTime()
                    timings.add((now - previousFrame) / 1e6)
                    previousFrame = now
                }
            }
            if (timings != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val file = java.io.File(OtoDirs.filesDir, "motion-frames.jsonl")
                val record = org.json.JSONObject().put("window",title).put("opening",visible)
                    .put("animationStartedMs",animationStarted)
                    .put("firstDrawDelayMs",firstDraw.get().takeIf { visible && it>=requestedAt }?.let { (it-requestedAt)/1e6 })
                    .put("callbackIntervalsMs",org.json.JSONArray(timings))
                file.appendText(record.toString()+"\n")
            }
            if (!visible) mounted = false
        }
        if(registered) BindDock(dock, dockId, windowState)
        LaunchedEffect(window) { WindowWorkTrace.measure("$title/icons") { useSharpTaskbarIcons(window) } }
        val taskbarLeader = group == null || group.leader == dockId
        LaunchedEffect(window, taskbarLeader, visible) {
            if (visible && dock != null) { kotlinx.coroutines.delay(250); WindowWorkTrace.measure("$title/taskbar") { updateTaskbarEntry(window, taskbarLeader) } }
        }
        val density = LocalDensity.current
        var tooltipEpoch by remember { mutableIntStateOf(0) }
        LaunchedEffect(window) {
            var wasInside = false
            while (true) {
                val point = java.awt.MouseInfo.getPointerInfo()?.location
                val inside = point != null && window.isShowing && window.bounds.contains(point)
                if (wasInside && !inside) tooltipEpoch++
                wasInside = inside
                kotlinx.coroutines.delay(100)
            }
        }
        SideEffect {
            val minSize = java.awt.Dimension(if(title == "Tu música") 640 else minimum.width.value.toInt(), if(title == "Tu música") 480 else minimum.height.value.toInt())
            if (window.minimumSize != minSize) window.minimumSize = minSize
            onWindow(window)
        }
        LaunchedEffect(pinned) {
            if (title == "Reproductor" && System.getenv("HYPRLAND_INSTANCE_SIGNATURE") != null) {
                kotlinx.coroutines.delay(200)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ProcessBuilder("hyprctl", "dispatch", "hl.dsp.window.pin({action=\"${if(pinned) "on" else "off"}\",window=\"title:Oto · Reproductor\"})").start().waitFor() } }
            }
        }
        MaterialTheme(colorScheme = darkColorScheme(primary = accent, onPrimary = Color(0xFF101010), primaryContainer = Color(0xFF303630), secondary = accent, secondaryContainer = Color(0xFF303630), tertiary = accent, background = Color(0xFF171A18), surface = Color(0xFF171A18), surfaceVariant = Color(0xFF343A34), surfaceTint = Color.Transparent, surfaceContainerLowest = Color(0xFF101310), surfaceContainerLow = Color(0xFF1C201C), surfaceContainer = Color(0xFF222722), surfaceContainerHigh = Color(0xFF292F29), surfaceContainerHighest = Color(0xFF343A34)), typography = OtoTypography) {
            Surface(Modifier.fillMaxSize().graphicsLayer {
                val side=revealSide.intValue
                // La traslación lateral no necesita un búfer adicional del tamaño de toda
                // la ventana; dejar que Compose lo elija evita copiarlo en cada fotograma.
                compositingStrategy=CompositingStrategy.Auto
                // Un alfa no nulo y un cambio mínimo de matriz ejercitan el renderizado
                // y los cálculos de posición antes de la transición visible.
                val preparingStep=if(warming && warmAlpha.floatValue>.01f) 1f else 0f
                val r=reveal.value
                // Las ventanas del conjunto salen deslizándose desde el borde del reproductor.
                // Solo traslación, sin alfa ni escala: con alfa, toda la ventana se componía en
                // un búfer aparte en cada fotograma y la animación perdía fluidez.
                alpha=if(warming) warmAlpha.floatValue else if(side == 0) r.coerceIn(0f,1f) else 1f
                transformOrigin=TransformOrigin(if(side < 0) 1f else if(side > 0) 0f else .5f,.5f)
                scaleX=if(warming) 1f-preparingStep*.0001f else if(side == 0) .92f + .08f*r else 1f
                scaleY=if(warming) 1f-preparingStep*.0001f else if(side == 0) .96f + .04f*r else 1f
                translationX=if(warming) preparingStep*.25f else -side*size.width*(1f-r).coerceAtLeast(-.04f)
                translationY=if(warming) 0f else if(side == 0) 20.dp.toPx()*(1f-r) else 0f
            }.drawWithContent {
                WindowWorkTrace.measure("$title/draw") { drawContent() }
                firstDraw.compareAndSet(0L,System.nanoTime())
            }.then(
                if(group == null || group.members.size == 1 || highlighted) Modifier.border(if(highlighted) 3.dp else 1.dp, if(highlighted) accent else Color.White.copy(alpha=.12f), shape)
                else Modifier.drawWithContent {
                    drawContent()
                    val half = .5.dp.toPx()
                    val r = 22.dp.toPx().coerceAtMost(size.minDimension / 2)
                    fun outline() {
                        drawRoundRect(Color.White.copy(alpha=.12f), topLeft=Offset(half,half),
                            size=Size(size.width-2*half,size.height-2*half), cornerRadius=CornerRadius((r-half).coerceAtLeast(0f)),
                            style=Stroke(width=1.dp.toPx()))
                    }
                    clipRect(bottom=r) { outline() }
                    clipRect(top=size.height-r) { outline() }
                    if(leftEdge) clipRect(top=r,bottom=size.height-r,right=r) { outline() }
                    if(rightEdge) clipRect(left=size.width-r,top=r,bottom=size.height-r) { outline() }
                }
            ), color = Color(0xFF171A18), shape = shape) {
                val chromeAlpha by animateFloatAsState(if(chrome)1f else 0f,tween(180),label="windowChrome")
                CompositionLocalProvider(LocalWindowTooltipEpoch provides tooltipEpoch) {
                Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { if(registered && dock != null && dockId != null) dock.raise(dockId) }.onPointerEvent(PointerEventType.Enter){hover(true)}.onPointerEvent(PointerEventType.Exit){hover(false)}) {
                    if(backgroundArtwork != null) {
                        if(groupBounds != null && ownBounds != null && group != null && group.members.size > 1) {
                            Box(Modifier.fillMaxSize().clip(androidx.compose.ui.graphics.RectangleShape)) {
                                Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded=true).offset(x=(groupBounds.x-ownBounds.x).dp, y=(groupBounds.y-ownBounds.y).dp).requiredSize(groupBounds.width.dp,groupBounds.height.dp)) {
                                    PlayerBackdrop(backgroundArtwork,backgroundOpacity,extended=groupBounds.width > groupBounds.height * 1.3f)
                                }
                            }
                        } else PlayerBackdrop(backgroundArtwork,backgroundOpacity)
                    }
                    val feedback = remember { SnackbarHostState() }
                    val feedbackOwner = dock?.layout?.bounds?.keys?.maxByOrNull { it.rank }
                    val message = dock?.message.orEmpty()
                    LaunchedEffect(message,feedbackOwner) {
                        if(message.isNotBlank() && dockId == feedbackOwner) {
                            feedback.showSnackbar(message)
                            if(dock?.message == message) dock.message=""
                        }
                    }
                    Box(Modifier.fillMaxSize().padding(top=if(immersive)0.dp else 44.dp)) { content() }
                    if(title=="Tu música") Canvas(Modifier.align(Alignment.TopStart)
                        .padding(start=if(compactLibrary)14.dp else 12.dp,top=if(compactLibrary)12.dp else 20.dp)
                        .size(if(compactLibrary)34.dp else 48.dp)) {
                        drawCircle(Color.White,radius=size.minDimension*.47f,center=Offset(size.width*.5f,size.height*.5f),style=Stroke(width=size.minDimension*.045f))
                        drawCircle(Color.White,radius=size.minDimension*.17f,center=Offset(size.width*.33f,size.height*.33f))
                    }
                    DockDragArea(if(registered) dock else null, dockId, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(if(immersive)36.dp else 44.dp)) {
                        BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer {alpha=chromeAlpha}) {
                            val showTitle=!immersive && title != "Tu música" && title != "Letra" && title != "Cola" && maxWidth>200.dp
                            val narrowHeader = maxWidth < 180.dp
                            CompositionLocalProvider(LocalWindowActionSize provides if(narrowHeader)30.dp else 36.dp) {
                            Row(Modifier.fillMaxSize().background(Color.Transparent).padding(start=if(title=="Tu música") {if(compactLibrary)62.dp else 72.dp} else if(showTitle)18.dp else if(title=="Letra")12.dp else 0.dp,end=2.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.End) {
                                if(showTitle)Text(title,Modifier.weight(1f),style=OtoType.rowSub,color=Color.White.copy(alpha=.4f))
                                if(!showTitle && actionsOnRight) Spacer(Modifier.weight(1f))
                                if(chrome) {extra()}
                                if(!showTitle && !actionsOnRight) Spacer(Modifier.weight(1f))
                                if(chrome && registered && !narrowHeader && dock != null && dockId != null) {
                                    if(dock.layout.group(dockId).leader == dockId) key(tooltipEpoch) { androidx.compose.foundation.TooltipArea(tooltip = {
                                        Surface(color = Color(0xFF30362F), shape = RoundedCornerShape(8.dp)) {
                                            Column(Modifier.widthIn(max=260.dp).padding(horizontal=12.dp, vertical=10.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                                Text("Ventana principal del conjunto", style = OtoType.rowTitle, color = accent)
                                                Text("Es la que aparece en la barra de tareas y arrastra a las demás. " +
                                                    "Para cambiar el orden, arrastra la barra de otra ventana hasta esta.", style = OtoType.rowSub, color = Color.White.copy(alpha=.75f))
                                            }
                                        }
                                    }) { Text("·",color=accent,modifier=Modifier.padding(horizontal=4.dp)) } }
                                    if(dock.supportsMaximize) SmallAction(if(dock.layout.group(dockId).maximized) Icons.Rounded.FilterNone else Icons.Rounded.CropSquare,
                                        if(dock.layout.group(dockId).maximized) "Restaurar conjunto" else "Maximizar conjunto") { dock.maximize(dockId) }
                                }
                                if(chrome) SmallAction(Icons.Rounded.Close,"Cerrar $title",onClick=close)
                            }
                            }
                        }
                    }
                    Box(Modifier.align(Alignment.BottomEnd).size(16.dp).graphicsLayer{alpha=if(immersive)chromeAlpha else .5f}.pointerInput(minimum) {
                        detectDragGestures {change,delta -> change.consume();if(dock != null && dockId != null) { val r=dock.layout.bounds[dockId]; if(r != null) with(density) { dock.layout.resize(dockId, r.width+delta.x.toDp().value.toInt(), r.height+delta.y.toDp().value.toInt()) } } else if(dock == null) with(density){windowState.size=DpSize((windowState.size.width+delta.x.toDp()).coerceIn(if(title=="Tu música")640.dp else minimum.width,2400.dp),(windowState.size.height+delta.y.toDp()).coerceIn(if(title=="Tu música")480.dp else minimum.height,1600.dp))}}
                    }) {Text("⌟",color=Color.White.copy(alpha=.25f))}
                    if (registered && group != null && dock != null && dockId != null && group.members.size > 1) {
                        @Composable fun DividerHandle(left: DockId, align: Alignment) {
                            val interaction=remember(left,align) { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            val hovered by interaction.collectIsHoveredAsState()
                            Box(Modifier.align(align).fillMaxHeight().padding(vertical=40.dp).width(14.dp)
                                .hoverable(interaction).pointerInput(left,dock) {
                                    var remainder=0f
                                    fun finish() { dock.geometrySettlesAt=System.nanoTime()+250_000_000L; dock.draggingGroup=false }
                                    detectDragGestures(onDragStart={ remainder=0f; dock.draggingGroup=true },onDragEnd={ finish() },onDragCancel={ finish() }) { change,delta ->
                                        change.consume()
                                        remainder += delta.x / density.density
                                        val step=remainder.toInt()
                                        if(step != 0 && left in dock.layout.bounds) { dock.layout.resizeDivider(left,step); remainder-=step }
                                    }
                                },contentAlignment=Alignment.Center) {
                                if(hovered) Box(Modifier.width(3.dp).height(38.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha=.5f)))
                            }
                        }
                        if(groupIndex > 0) DividerHandle(group.members[groupIndex-1],Alignment.CenterStart)
                        if(groupIndex < group.members.lastIndex) DividerHandle(dockId,Alignment.CenterEnd)
                    }
                    if(dockId == feedbackOwner && dock != null) OtoSnackbarHost(feedback,Modifier.align(Alignment.BottomCenter).padding(18.dp))
                }
                }
            }
        }
    }
    }
}
