package io.github.iaguito22.oto.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.application
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.account.Account
import io.github.iaguito22.oto.account.Cloud
import io.github.iaguito22.oto.desktop.playback.DesktopDownloads
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
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
    if (System.getenv("OTO_DOCK_HARNESS") == "1") {
        prepareDesktopWindows()
        application { DockHarness() }
        return
    }
    val instance = DesktopInstance.acquire() ?: return
    Runtime.getRuntime().addShutdownHook(Thread { instance.close() })
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
    val downloads = DesktopDownloads(prefs, appScope)
    downloads.start()
    val player = DesktopPlayerConnection(prefs, plays, djCatalog, lyricsStore, downloads, appScope)
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

    prepareDesktopWindows()
    application {
        ModularDesktop(player, prefs, menu, importer, account, cloud, login, downloads, lyricsStore, ::exitApplication)
    }
}

private fun prepareDesktopWindows() {
    if (System.getenv("HYPRLAND_INSTANCE_SIGNATURE") != null) {
        runCatching { ProcessBuilder("hyprctl", "eval", "hl.window_rule({ name = \"oto-modular\", match = { class = \"io-github-iaguito22-oto-desktop-MainKt\" }, float = true })").start().waitFor() }
    }
}
