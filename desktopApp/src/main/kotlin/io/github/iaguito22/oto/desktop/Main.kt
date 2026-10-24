package io.github.iaguito22.oto.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.account.Account
import io.github.iaguito22.oto.account.Cloud
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
import io.github.iaguito22.oto.desktop.ui.ArtworkLoader
import io.github.iaguito22.oto.desktop.ui.ArtworkPalette
import io.github.iaguito22.oto.desktop.ui.DesktopMenuSheet
import io.github.iaguito22.oto.desktop.ui.DesktopStage
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
        val windowState = rememberWindowState(
            size = DpSize(1340.dp, 840.dp),
            position = WindowPosition.Aligned( androidx.compose.ui.Alignment.Center ),
        )

        var menuOpen by remember { mutableStateOf(false) }
        var lastWheelToggleMs by remember { mutableLongStateOf(0L) }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Oto",
            onPreviewKeyEvent = { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    when {
                        ev.key == Key.Escape && menuOpen -> {
                            menuOpen = false
                            true
                        }
                        ev.isCtrlPressed && ev.key == Key.K -> {
                            menuOpen = !menuOpen
                            true
                        }
                        ev.key == Key.Spacebar && !menuOpen -> {
                            player.toggle()
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
            val lyrics by player.lyrics.collectAsState()
            val lyricsLoading by player.lyricsLoading.collectAsState()
            val positionMs by player.positionMs.collectAsState()
            val quality by player.currentQuality.collectAsState()
            val likedIds by prefs.likedIds.collectAsState(initial = emptySet())
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
                    .onPointerEvent(PointerEventType.Scroll) { ev ->
                        val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        val now = System.currentTimeMillis()
                        if (now - lastWheelToggleMs > 360L) {
                            if (!menuOpen && dy > 0.7f) {
                                menuOpen = true
                                lastWheelToggleMs = now
                            } else if (menuOpen && dy < -1.8f) {
                                // Con scroll fuerte hacia arriba cierra el menú si estás arriba
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
                    onTogglePlay = player::toggle,
                    onPrev = player::previous,
                    onNext = player::next,
                    onSeek = player::seekTo,
                    onToggleLike = {
                        val id = nowPlaying.id ?: return@DesktopStage
                        val heard = Heard(id, nowPlaying.title, nowPlaying.artist, nowPlaying.artwork)
                        uiScope.launch { prefs.setLiked(heard, !isLiked) }
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
                    onPlaySong = player::play,
                    onPlayAll = { songs, i, item -> player.playAll(songs, i, item) },
                    onPlayLocal = { list, idx -> player.playLocal(list, idx, list.id == LIKED_ID) },
                    onPlayDj = { player.playDj {} },
                    onSkipToQueue = player::skipTo,
                )
            }
        }
    }
}
