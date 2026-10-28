package io.github.iaguito22.oto.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.account.Account
import io.github.iaguito22.oto.account.Cloud
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.window.WindowPlacement
import io.github.iaguito22.oto.desktop.ui.CueInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
import io.github.iaguito22.oto.desktop.ui.ArtworkLoader
import io.github.iaguito22.oto.desktop.ui.ArtworkPalette
import io.github.iaguito22.oto.desktop.ui.DesktopMenuSheet
import io.github.iaguito22.oto.desktop.ui.DesktopStage
import io.github.iaguito22.oto.desktop.ui.SaveToModal
import io.github.iaguito22.oto.dj.Plays
import io.github.iaguito22.oto.dj.YouTubeCatalog
import io.github.iaguito22.oto.importer.Importer
import io.github.iaguito22.oto.lyrics.LyricsStore
import io.github.iaguito22.oto.playback.InnerTubeXPlayer
import io.github.iaguito22.oto.ui.menu.MenuController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    Timber.plant(Timber.DebugTree())
    InnerTubeXPlayer.initialize(null)

    val systemLocale = Locale.getDefault()
    YouTube.locale = YouTubeLocale(
        gl = systemLocale.country.ifBlank { "ES" },
        hl = systemLocale.language.ifBlank { "es" },
    )

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs()
    val account = Account(prefs, appScope)
    val cloud = Cloud(prefs, account, appScope)
    val importer = Importer(prefs, appScope)
    val login = BrowserLogin(account, appScope)
    val plays = Plays(File(OtoDirs.filesDir, "plays.jsonl"))
    val djCatalog = YouTubeCatalog(File(OtoDirs.cacheDir, "dj"))
    val lyricsStore = LyricsStore(File(OtoDirs.cacheDir, "lyrics"))
    val player = DesktopPlayerConnection(prefs, plays, djCatalog, lyricsStore, appScope)
    val menu = MenuController(appScope)

    if (account.restore() == null) {
        val visitor = OtoDirs.keyValue("oto_visitor")
        val saved = visitor.getString("visitorData", null)
        if (saved != null) {
            YouTube.visitorData = saved
        } else {
            appScope.launch(Dispatchers.IO) {
                YouTube.visitorData()
                    .onSuccess {
                        YouTube.visitorData = it
                        visitor.putString("visitorData", it)
                    }
                    .onFailure { Timber.w(it, "No se pudo pedir visitorData en Desktop") }
            }
        }
    }
    cloud.start()
    appScope.launch(Dispatchers.IO) { OtoDirs.trimDj(File(OtoDirs.cacheDir, "dj")) }

    application {
        val desktopKv = remember { OtoDirs.keyValue("desktop") }
        val savedW = remember { desktopKv.getLong("win_w", 1340L).coerceIn(800L, 3840L) }
        val savedH = remember { desktopKv.getLong("win_h", 840L).coerceIn(600L, 2160L) }
        val wasFullscreen = remember { desktopKv.getString("win_fullscreen") == "true" }
        val windowState = rememberWindowState(
            size = DpSize(savedW.toInt().dp, savedH.toInt().dp),
            position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
            placement = if (wasFullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating,
        )

        LaunchedEffect(windowState.size, windowState.placement) {
            if (windowState.placement == WindowPlacement.Floating) {
                desktopKv.putLong("win_w", windowState.size.width.value.toLong())
                desktopKv.putLong("win_h", windowState.size.height.value.toLong())
                desktopKv.putString("win_fullscreen", "false")
            } else if (windowState.placement == WindowPlacement.Fullscreen) {
                desktopKv.putString("win_fullscreen", "true")
            }
        }

        var menuOpen by remember { mutableStateOf(false) }
        var menuTab by remember { mutableIntStateOf(0) } // 0 = Tu música, 1 = Explorar
        var savingTrack by remember { mutableStateOf<Heard?>(null) }
        var lastWheelToggleMs by remember { mutableLongStateOf(0L) }
        var lastActivityMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var isIdle by remember { mutableStateOf(false) }
        var cue by remember { mutableStateOf<CueInfo?>(null) }
        var lastNonZeroVolume by remember { mutableFloatStateOf(0.8f) }

        LaunchedEffect(lastActivityMs, menuOpen, savingTrack) {
            if (menuOpen || savingTrack != null) {
                isIdle = false
            } else {
                isIdle = false
                delay(2600L)
                isIdle = true
            }
        }

        LaunchedEffect(cue) {
            if (cue != null) {
                delay(1400L)
                cue = null
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Oto",
            onPreviewKeyEvent = { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    lastActivityMs = System.currentTimeMillis()
                    when {
                        ev.key == Key.Escape && savingTrack != null -> {
                            savingTrack = null
                            true
                        }
                        ev.key == Key.Escape && menuOpen -> {
                            menuOpen = false
                            true
                        }
                        (ev.isCtrlPressed && ev.key == Key.K) || (ev.isCtrlPressed && ev.key == Key.F) -> {
                            menuOpen = !menuOpen
                            true
                        }
                        ev.key == Key.F11 -> {
                            val isFull = windowState.placement == WindowPlacement.Fullscreen
                            windowState.placement = if (isFull) WindowPlacement.Floating else WindowPlacement.Fullscreen
                            cue = if (!isFull) CueInfo(Icons.Rounded.Fullscreen, "Pantalla completa") else CueInfo(Icons.Rounded.FullscreenExit, "Modo ventana")
                            true
                        }
                        ev.key == Key.F1 -> {
                            menuTab = 0
                            menuOpen = true
                            true
                        }
                        ev.key == Key.F2 -> {
                            menuTab = 1
                            menuOpen = true
                            true
                        }
                        ev.key == Key.Tab && menuOpen && menu.state.value.query.isEmpty() -> {
                            menuTab = (menuTab + 1) % 2
                            true
                        }
                        (ev.key == Key.Spacebar && !menuOpen) || ev.key == Key.MediaPlayPause -> {
                            val willPlay = !player.state.value.playing
                            player.toggle()
                            cue = if (willPlay) CueInfo(Icons.Rounded.PlayArrow, "Reanudado") else CueInfo(Icons.Rounded.Pause, "Pausado")
                            true
                        }
                        (!menuOpen && !ev.isCtrlPressed && ev.key == Key.N) || (!menuOpen && ev.isCtrlPressed && ev.key == Key.DirectionRight) -> {
                            player.next()
                            cue = CueInfo(Icons.Rounded.SkipNext, "Siguiente")
                            true
                        }
                        (!menuOpen && !ev.isCtrlPressed && ev.key == Key.P) || (!menuOpen && ev.isCtrlPressed && ev.key == Key.DirectionLeft) -> {
                            player.previous()
                            cue = CueInfo(Icons.Rounded.SkipPrevious, "Anterior")
                            true
                        }
                        !menuOpen && !ev.isCtrlPressed && ev.key == Key.DirectionRight -> {
                            player.seekTo(player.position() + 5000L)
                            cue = CueInfo(Icons.Rounded.FastForward, "+5s")
                            true
                        }
                        !menuOpen && !ev.isCtrlPressed && ev.key == Key.DirectionLeft -> {
                            player.seekTo((player.position() - 5000L).coerceAtLeast(0L))
                            cue = CueInfo(Icons.Rounded.FastRewind, "-5s")
                            true
                        }
                        !menuOpen && ev.key == Key.DirectionUp -> {
                            player.adjustVolume(0.05f)
                            true
                        }
                        !menuOpen && ev.key == Key.DirectionDown -> {
                            player.adjustVolume(-0.05f)
                            true
                        }
                        !menuOpen && ev.key == Key.M -> {
                            if (player.volume.value > 0.001f) {
                                lastNonZeroVolume = player.volume.value
                                player.setVolume(0f)
                                cue = CueInfo(Icons.AutoMirrored.Rounded.VolumeMute, "Silenciado")
                            } else {
                                val restoreVol = if (lastNonZeroVolume > 0.05f) lastNonZeroVolume else 0.8f
                                player.setVolume(restoreVol)
                                cue = CueInfo(Icons.AutoMirrored.Rounded.VolumeUp, "Sonido activo")
                            }
                            true
                        }
                        !menuOpen && !ev.isCtrlPressed && ev.key == Key.L -> {
                            val cur = player.state.value
                            val sid = cur.id
                            if (sid != null) {
                                appScope.launch {
                                    val h = Heard(sid, cur.title, cur.artist, cur.artwork)
                                    val wasLiked = prefs.likedIds.firstOrNull()?.contains(sid) ?: false
                                    prefs.setLiked(h, !wasLiked)
                                    cue = if (wasLiked) {
                                        CueInfo(Icons.Rounded.FavoriteBorder, "Retirado de Me gusta")
                                    } else {
                                        CueInfo(Icons.Rounded.Favorite, "Guardado en Me gusta")
                                    }
                                }
                            }
                            true
                        }
                        !menuOpen && (ev.key == Key.B || (ev.isCtrlPressed && ev.key == Key.D)) -> {
                            val cur = player.state.value
                            val sid = cur.id
                            if (sid != null) {
                                savingTrack = Heard(sid, cur.title, cur.artist, cur.artwork)
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            },
        ) {
            val nowPlaying by player.state.collectAsState()
            val source by player.source.collectAsState()
            val queue by player.queue.collectAsState()
            val shuffle by player.shuffle.collectAsState()
            val loop by player.loop.collectAsState()
            val lyrics by player.lyrics.collectAsState()
            val lyricsLoading by player.lyricsLoading.collectAsState()
            val positionMs by player.positionMs.collectAsState()
            val quality by player.currentQuality.collectAsState()
            val volume by player.volume.collectAsState()
            val likedIds by prefs.likedIds.collectAsState(initial = emptySet())
            val likedList by prefs.likedList.collectAsState(initial = LocalPlaylist(LIKED_ID, "Me gusta", emptyList()))
            val playlists by prefs.playlists.collectAsState(initial = emptyList())
            val uiScope = rememberCoroutineScope()

            var artwork by remember { mutableStateOf<ImageBitmap?>(null) }
            var palette by remember { mutableStateOf(ArtworkPalette()) }

            LaunchedEffect(nowPlaying.artwork) {
                val (bmp, pal) = ArtworkLoader.load(nowPlaying.artwork)
                artwork = bmp
                palette = pal
            }

            val isLiked = nowPlaying.id != null && nowPlaying.id in likedIds

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onPointerEvent(PointerEventType.Move) {
                        lastActivityMs = System.currentTimeMillis()
                    }
                    .onPointerEvent(PointerEventType.Press) {
                        lastActivityMs = System.currentTimeMillis()
                    }
                    .onPointerEvent(PointerEventType.Scroll) { ev ->
                        lastActivityMs = System.currentTimeMillis()
                        val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        val now = System.currentTimeMillis()
                        if (now - lastWheelToggleMs > 280L) {
                            if (!menuOpen && dy > 0.5f) {
                                menuOpen = true
                                lastWheelToggleMs = now
                            } else if (menuOpen && dy < -0.7f) {
                                menuOpen = false
                                lastWheelToggleMs = now
                            }
                        }
                    },
            ) {
                DesktopStage(
                    nowPlaying = nowPlaying,
                    positionMs = positionMs,
                    quality = quality,
                    source = source,
                    artwork = artwork,
                    palette = palette,
                    liked = isLiked,
                    lyrics = lyrics,
                    lyricsLoading = lyricsLoading,
                    volume = volume,
                    cue = cue,
                    isIdle = isIdle,
                    menuOpen = menuOpen,
                    onTogglePlay = {
                        val willPlay = !player.state.value.playing
                        player.toggle()
                        cue = if (willPlay) CueInfo(Icons.Rounded.PlayArrow, "Reanudado") else CueInfo(Icons.Rounded.Pause, "Pausado")
                    },
                    onPrev = {
                        player.previous()
                        cue = CueInfo(Icons.Rounded.SkipPrevious, "Anterior")
                    },
                    onNext = {
                        player.next()
                        cue = CueInfo(Icons.Rounded.SkipNext, "Siguiente")
                    },
                    onSeek = player::seekTo,
                    onAdjustVolume = player::adjustVolume,
                    onSaveTo = {
                        val id = nowPlaying.id ?: return@DesktopStage
                        savingTrack = Heard(id, nowPlaying.title, nowPlaying.artist, nowPlaying.artwork)
                    },
                    onOpenArtist = { id, name ->
                        if (!id.isNullOrBlank()) {
                            menu.openArtist(id, name)
                        } else {
                            menu.onQuery(name)
                        }
                        menuOpen = true
                    },
                    onToggleLike = {
                        val id = nowPlaying.id ?: return@DesktopStage
                        val heard = Heard(id, nowPlaying.title, nowPlaying.artist, nowPlaying.artwork)
                        val willBeLiked = !isLiked
                        uiScope.launch {
                            prefs.setLiked(heard, willBeLiked)
                            cue = if (willBeLiked) CueInfo(Icons.Rounded.Favorite, "Guardado en Me gusta") else CueInfo(Icons.Rounded.FavoriteBorder, "Retirado de Me gusta")
                        }
                    },
                    onOpenSource = {
                        val src = source
                        when {
                            src?.item != null -> menu.open(src.item!!)
                            src?.local != null -> menu.openLocal(src.local!!)
                            else -> menu.openLocal(LIKED_ID)
                        }
                        menuOpen = true
                    },
                    onOpenMenu = { menuOpen = true },
                )

                DesktopMenuSheet(
                    open = menuOpen,
                    isIdle = isIdle,
                    onToggleOpen = { menuOpen = it },
                    menu = menu,
                    prefs = prefs,
                    importer = importer,
                    account = account,
                    cloud = cloud,
                    login = login,
                    palette = palette,
                    currentMediaId = nowPlaying.id,
                    queue = queue,
                    shuffle = shuffle,
                    loop = loop,
                    onSetShuffle = player::setShuffle,
                    onSetLoop = player::setLoop,
                    onMoveQueue = player::move,
                    onRemoveQueue = player::remove,
                    onClearQueue = player::clearQueue,
                    onPlaySong = player::play,
                    onPlayAll = { songs, i, item -> player.playAll(songs, i, item) },
                    onPlayLocal = { list, idx -> player.playLocal(list, idx, list.id == LIKED_ID) },
                    onPlayDj = { player.playDj {} },
                    onSkipToQueue = player::skipTo,
                    selectedTab = menuTab,
                    onSelectTab = { menuTab = it },
                    onSaveTrack = { savingTrack = it },
                )

                // Modal emergente de guardado en listas (accesible desde Stage, Menú y atajo de teclado B)
                if (savingTrack != null) {
                    SaveToModal(
                        track = savingTrack!!,
                        playlists = playlists,
                        likedList = likedList,
                        accent = palette.primary,
                        onToggleLike = { h -> uiScope.launch { prefs.toggleLike(h) } },
                        onTogglePlaylist = { pid, h ->
                            uiScope.launch {
                                val pl = playlists.firstOrNull { it.id == pid }
                                if (pl != null && pl.songs.any { it.id == h.id }) {
                                    prefs.removeFromPlaylist(pid, h.id)
                                } else {
                                    prefs.addToPlaylist(pid, h)
                                }
                            }
                        },
                        onCreateAndAdd = { name, h ->
                            uiScope.launch {
                                val pid = prefs.createPlaylist(name)
                                prefs.addToPlaylist(pid, h)
                            }
                        },
                        onDismiss = { savingTrack = null },
                    )
                }
            }
        }
    }
}
