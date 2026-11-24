package io.github.iaguito22.oto.desktop.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.dj.Plays
import io.github.iaguito22.oto.dj.YouTubeCatalog
import io.github.iaguito22.oto.lyrics.LyricsStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.*

/**
 * El bucle con audio de verdad: canciones descargadas (archivos locales de silencio), sin red. Abre la tarjeta de
 * sonido, así que solo corre con `OTO_AUDIO_INTEGRATION=1`.
 */
class DesktopLoopAudioTest {
    private fun withPlayer(seconds: Int, fadeSeconds: Int, test: suspend (DesktopPlayerConnection) -> Unit) = runBlocking {
        assumeTrue(System.getenv("OTO_AUDIO_INTEGRATION") == "1")
        val root = Files.createTempDirectory("oto-loop").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        OtoDirs.init(root, File(root, "cache"))
        val dir = File(root, "downloads").apply { mkdirs() }
        val format = AudioFormat(48000f, 16, 1, true, false)
        val index = JSONArray()
        for (id in listOf("a", "b")) {
            val pcm = ByteArray(48000 * 2 * seconds)   // silencio: no se oye nada por los altavoces
            AudioInputStream(ByteArrayInputStream(pcm), format, 48000L * seconds).use { AudioSystem.write(it, AudioFileFormat.Type.WAVE, File(dir, "$id.wav")) }
            index.put(JSONObject().put("id", id).put("title", id).put("artist", "x").put("file", "$id.wav"))
        }
        File(dir, "index.json").writeText(index.toString())
        val prefs = Prefs(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "prefs.preferences_pb") }),
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "lists.preferences_pb") }),
        )
        prefs.setCrossfade(fadeSeconds)
        list.songs.forEach { prefs.setDownloadSong(it, true) }   // si no, las descargas las borran por no pedidas
        val player = DesktopPlayerConnection(prefs, Plays(File(root, "plays")), YouTubeCatalog(File(root, "dj")),
            LyricsStore(File(root, "lyrics")), DesktopDownloads(prefs, scope).also { it.start() }, scope)
        delay(300)   // que lleguen los ajustes (fundido) al reproductor
        try { test(player) } finally { player.setPlaying(false); scope.cancel(); delay(300); root.deleteRecursively() }
    }

    /** Lo que suena cada 250 ms durante [ms]. */
    private suspend fun trace(player: DesktopPlayerConnection, ms: Long): List<String?> {
        val out = ArrayList<String?>()
        repeat((ms / 250).toInt()) { out += player.state.value.id?.takeIf { player.state.value.playing }; delay(250) }
        return out
    }
    private fun List<String?>.runs() = fold(mutableListOf<String?>()) { acc, id -> if (acc.lastOrNull() != id) acc += id; acc }

    private val list = LocalPlaylist("L", "Lista", listOf(Heard("a", "a", "x", null), Heard("b", "b", "x", null)))

    @Test fun laListaEnteraVuelveAEmpezar() = withPlayer(seconds = 4, fadeSeconds = 0) { player ->
        player.playLocal(list, null, false)
        player.setLoop(LoopMode.All)
        val runs = trace(player, 12_500).runs().filterNotNull()
        assertEquals("se oyó ${runs}", listOf(runs[0], runs[1], runs[0]), runs.take(3))
    }

    @Test fun unaCancionSeRepite() = withPlayer(seconds = 4, fadeSeconds = 0) { player ->
        player.playLocal(list, null, false)
        player.setLoop(LoopMode.One)
        val t = trace(player, 10_000)
        val ids = t.filterNotNull()
        assertEquals("se oyó ${t.runs()}", listOf(ids.first()), ids.distinct())
        assertEquals("se paró: ${t.takeLast(6)}", ids.first(), t.last())
    }

    @Test fun conFundidoLaListaEnteraVuelveAEmpezar() = withPlayer(seconds = 12, fadeSeconds = 3) { player ->
        player.playLocal(list, null, false)
        player.setLoop(LoopMode.All)
        val runs = trace(player, 30_000).runs().filterNotNull()
        assertEquals("se oyó ${runs}", listOf(runs[0], runs[1], runs[0]), runs.take(3))
    }

    @Test fun conFundidoUnaCancionSeRepite() = withPlayer(seconds = 12, fadeSeconds = 3) { player ->
        player.playLocal(list, null, false)
        player.setLoop(LoopMode.One)
        val t = trace(player, 16_000)
        val ids = t.filterNotNull()
        assertEquals("se oyó ${t.runs()}", listOf(ids.first()), ids.distinct())
        assertEquals("se paró: ${t.takeLast(6)}", ids.first(), t.last())
    }
}
