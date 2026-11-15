package io.github.iaguito22.oto.desktop.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.dj.ArtistInfo
import io.github.iaguito22.oto.dj.Catalog
import io.github.iaguito22.oto.dj.DjMode
import io.github.iaguito22.oto.dj.Play
import io.github.iaguito22.oto.dj.Plays
import io.github.iaguito22.oto.dj.Track
import io.github.iaguito22.oto.dj.toTrack
import io.github.iaguito22.oto.dj.YouTubeCatalog
import io.github.iaguito22.oto.lyrics.LyricsStore
import io.github.iaguito22.oto.toSong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

class DesktopCrossfadeTest {
    @Test fun preparedLocalTrackStartsBeforePreviousEndsAndCanBePaused(): Unit = runBlocking {
        assumeTrue(System.getenv("OTO_AUDIO_INTEGRATION") == "1")
        assumeTrue(System.getProperty("os.name").startsWith("Windows",true))
        val root=Files.createTempDirectory("oto-crossfade").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        OtoDirs.init(root,File(root,"cache"))
        val prefs=Prefs(PreferenceDataStoreFactory.create(scope=scope,produceFile={File(root,"prefs.preferences_pb")}),PreferenceDataStoreFactory.create(scope=scope,produceFile={File(root,"lists.preferences_pb")}))
        val a=Heard("fade-a","Primera","Prueba",null)
        val b=Heard("fade-b","Segunda","Prueba",null)
        val dir=File(root,"downloads").apply { mkdirs() }
        val format=AudioFormat(48000f,16,1,true,false)
        for (name in listOf("a.wav","b.wav")) AudioInputStream(ByteArrayInputStream(ByteArray(48000*2*12)),format,48000L*12).use { AudioSystem.write(it,AudioFileFormat.Type.WAVE,File(dir,name)) }
        File(dir,"index.json").writeText(JSONArray().put(JSONObject().put("id",a.id).put("file","a.wav")).put(JSONObject().put("id",b.id).put("file","b.wav")).toString())
        prefs.setDownloadSong(a,true);prefs.setDownloadSong(b,true);prefs.setSmartMix(false);prefs.setCrossfade(3)
        val downloads=DesktopDownloads(prefs,scope).also { it.start() }
        val player=DesktopPlayerConnection(prefs,Plays(File(root,"plays")),YouTubeCatalog(File(root,"dj")),LyricsStore(File(root,"lyrics")),downloads,scope)
        try {
            delay(150) // Dejar que smartMix=false y crossfade=3 lleguen al reproductor antes de arrancar
            player.playAll(listOf(a.toSong().copy(duration=12),b.toSong().copy(duration=12)),0,null)
            withTimeout(7000) { player.state.first { it.id==a.id && it.playing && !it.buffering } }
            val started=System.nanoTime()
            withTimeout(15_000) { player.state.first { it.id==b.id && it.playing } }
            val elapsed=(System.nanoTime()-started)/1_000_000
            assertTrue("La siguiente debe empezar antes de que acabe la primera: $elapsed ms",elapsed<11_800)
            assertTrue("Las dos pistas deben solaparse",player.crossfadeActive)
            assertTrue("mixing debe estar activo durante el crossfade", player.mixing.value)
            player.toggle()
            withTimeout(4000) { player.state.first { it.id==b.id && !it.playing } }
            assertFalse("Pausar debe detener la pista saliente",player.crossfadeActive)
            assertFalse("mixing debe desactivarse al detener el crossfade", player.mixing.value)
        } finally {
            downloads.stop();scope.cancel();delay(500)
            root.deleteRecursively()
        }
    }

    @Test fun djRealAudioSessionAnalyzesPcmCrossfadesAndSkipsTanda(): Unit = runBlocking {
        assumeTrue(System.getenv("OTO_AUDIO_INTEGRATION") == "1")
        assumeTrue(System.getProperty("os.name").startsWith("Windows", true))
        val root = Files.createTempDirectory("oto-dj-real").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        OtoDirs.init(root, File(root, "cache"))
        val prefs = Prefs(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "prefs.preferences_pb") }),
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "lists.preferences_pb") }),
        )
        val plays = Plays(File(root, "plays"))
        val dir = File(root, "downloads").apply { mkdirs() }
        val rock = (1..9).map { idx ->
            Heard("dj-r-$idx", "Rock $idx", "Band Rock", null, "art-rock")
        }
        val chill = (1..9).map { idx ->
            Heard("dj-c-$idx", "Chill $idx", "Band Chill", null, "art-chill")
        }
        val jazz = (1..6).map { idx ->
            Heard("dj-j-$idx", "Jazz $idx", "Band Jazz", null, "art-jazz")
        }
        writeQuietBeatWav(File(dir, "rock.wav"), seconds = 18, bpm = 120.0)
        writeQuietBeatWav(File(dir, "chill.wav"), seconds = 18, bpm = 92.0)
        writeQuietBeatWav(File(dir, "jazz.wav"), seconds = 18, bpm = 104.0)
        val index = JSONArray()
        val now = System.currentTimeMillis()
        (rock + chill + jazz).forEachIndexed { i, h ->
            val fname = when {
                h.id.startsWith("dj-r-") -> "rock.wav"
                h.id.startsWith("dj-c-") -> "chill.wav"
                else -> "jazz.wav"
            }
            index.put(JSONObject().put("id", h.id).put("file", fname))
            prefs.setDownloadSong(h, true)
            prefs.setLiked(h, true)
            prefs.addHeard(h)
            plays.add(Play(h.id, h.title, h.artist, h.artistId, now - (i + 1) * 3_600_000L, 17_000L, 18_000L))
        }
        File(dir, "index.json").writeText(index.toString())
        prefs.createPlaylist("Rock Live", rock)
        prefs.createPlaylist("Chill Live", chill)
        prefs.createPlaylist("Jazz Live", jazz)
        val genreJson = JSONObject()
        rock.forEach { h ->
            val t = h.toTrack()
            genreJson.put("s:${t.key}", JSONArray().put("Rock").put(now))
            genreJson.put("a:${t.who}", JSONArray().put("Rock").put(now))
        }
        chill.forEach { h ->
            val t = h.toTrack()
            genreJson.put("s:${t.key}", JSONArray().put("Chill").put(now))
            genreJson.put("a:${t.who}", JSONArray().put("Chill").put(now))
        }
        jazz.forEach { h ->
            val t = h.toTrack()
            genreJson.put("s:${t.key}", JSONArray().put("Jazz").put(now))
            genreJson.put("a:${t.who}", JSONArray().put("Jazz").put(now))
        }
        File(root, "dj-genres.json").writeText(genreJson.toString())
        prefs.setSmartMixChoice(5)

        val offlineCatalog = object : Catalog {
            override suspend fun artist(id: String): ArtistInfo? = null
            override suspend fun songs(info: ArtistInfo): List<Track> = emptyList()
            override suspend fun radio(id: String): List<Track> = emptyList()
            override suspend fun find(name: String): String? = null
        }
        val downloads = DesktopDownloads(prefs, scope).also { it.start() }
        val player = DesktopPlayerConnection(prefs, plays, offlineCatalog, LyricsStore(File(root, "lyrics")), downloads, scope)
        player.setVolume(0.15f)
        try {
            delay(200)
            val seed = rock.first().toSong().copy(duration = 18)
            player.playDjFrom(seed, DjMode.Normal) {}
            withTimeout(10_000) {
                player.djActive.first { it }
                player.state.first { it.id == seed.id && it.playing && !it.buffering }
                player.djNextUp.first { it.size >= 3 }
            }
            val started = System.nanoTime()
            val expectedSecondId = player.djNextUp.value.first().track.id
            // Esperar a que arranque el fundido real antes de que terminen los 18 s (SmartMix 5 s -> ~13 s)
            withTimeout(20_000) { player.state.first { it.id == expectedSecondId && it.playing && !it.buffering } }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("El crossfade real del DJ debe arrancar antes del fin de pista: $elapsedMs ms", elapsedMs < 17_000)
            assertTrue("Las dos pistas DJ deben solaparse durante el fundido", player.crossfadeActive)
            assertTrue("mixing debe ser true durante el fundido DJ", player.mixing.value)
            // Esperar fin del fundido para comprobar transición limpia y caché de análisis PCM
            withTimeout(6_000) { while (player.crossfadeActive) delay(50) }
            assertFalse("mixing debe volver a false al finalizar el fundido DJ", player.mixing.value)
            delay(200)
            assertTrue("La segunda pista debe seguir sonando sin cortes ni silencios", player.state.value.playing)
            assertTrue("positionMs debe avanzar tras el fundido", player.position() > 1_000L)
            val cacheFile = File(root, "cache/dj/audio-analysis.json")
            assertTrue("AudioAnalysisCache debe persistir métricas reales en $cacheFile", cacheFile.exists() && cacheFile.length() > 10L)

            // Probar salto de ambiente real en vivo
            val tandaBefore = player.djTanda.value
            val idBeforeSkip = player.state.value.id
            player.skipDjTanda()
            withTimeout(10_000) {
                player.state.first { it.id != null && it.id != idBeforeSkip && it.playing && !it.buffering }
            }
            assertNotNull("djTanda debe estar sincronizada tras saltar ambiente", player.djTanda.value)
            assertNotEquals("El ambiente debe haber cambiado tras skipDjTanda", tandaBefore, player.djTanda.value)
            assertFalse("djTanda solo debe mostrar género sin 'Como ...'", player.djTanda.value!!.contains("como", ignoreCase = true))
        } finally {
            downloads.stop()
            scope.cancel()
            delay(500)
            root.deleteRecursively()
        }
    }

    private fun writeQuietBeatWav(file: File, seconds: Int, bpm: Double) {
        val sr = 48_000
        val total = sr * seconds
        val pcm = ByteArray(total * 2)
        val beatSamples = (sr * 60.0 / bpm).toInt().coerceAtLeast(1)
        for (i in 0 until total) {
            val beatPos = (i % beatSamples).toDouble() / sr
            val kickEnv = exp(-beatPos * 22.0)
            val sample = (kickEnv * sin(2.0 * PI * 75.0 * i / sr) * 900.0 + sin(2.0 * PI * 220.0 * i / sr) * 250.0)
                .roundToInt().coerceIn(-32768, 32767)
            pcm[i * 2] = sample.toByte()
            pcm[i * 2 + 1] = (sample shr 8).toByte()
        }
        val format = AudioFormat(48_000f, 16, 1, true, false)
        AudioInputStream(ByteArrayInputStream(pcm), format, total.toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
        }
    }
}
