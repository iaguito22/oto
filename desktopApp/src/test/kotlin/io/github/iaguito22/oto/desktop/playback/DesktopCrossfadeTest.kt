package io.github.iaguito22.oto.desktop.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.dj.Plays
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
        prefs.setDownloadSong(a,true);prefs.setDownloadSong(b,true);prefs.setCrossfade(3)
        val downloads=DesktopDownloads(prefs,scope).also { it.start() }
        val player=DesktopPlayerConnection(prefs,Plays(File(root,"plays")),YouTubeCatalog(File(root,"dj")),LyricsStore(File(root,"lyrics")),downloads,scope)
        try {
            player.playAll(listOf(a.toSong().copy(duration=12),b.toSong().copy(duration=12)),0,null)
            withTimeout(7000) { player.state.first { it.id==a.id && it.playing && !it.buffering } }
            val started=System.nanoTime()
            withTimeout(15_000) { player.state.first { it.id==b.id && it.playing } }
            val elapsed=(System.nanoTime()-started)/1_000_000
            assertTrue("La siguiente debe empezar antes de que acabe la primera: $elapsed ms",elapsed<11_800)
            assertTrue("Las dos pistas deben solaparse",player.crossfadeActive)
            player.toggle()
            withTimeout(4000) { player.state.first { it.id==b.id && !it.playing } }
            assertFalse("Pausar debe detener la pista saliente",player.crossfadeActive)
        } finally {
            downloads.stop();scope.cancel();delay(500)
            root.deleteRecursively()
        }
    }
}
