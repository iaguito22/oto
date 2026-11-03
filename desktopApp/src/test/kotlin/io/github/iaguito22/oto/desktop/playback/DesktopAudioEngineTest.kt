package io.github.iaguito22.oto.desktop.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.*

class DesktopAudioEngineTest {
    @Test fun volumeChangesKeepTheSameDecoderAndPosition(): Unit = runBlocking {
        assumeTrue(System.getenv("OTO_AUDIO_INTEGRATION") == "1")
        val root = Files.createTempDirectory("oto-volume").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = DesktopAudioEngine(root, scope, {}, {})
        try {
            val format = AudioFormat(48000f, 16, 1, true, false)
            val file = File(root, "silencio.wav")
            AudioInputStream(ByteArrayInputStream(ByteArray(48000 * 2 * 6)), format, 48000L * 6).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
            }
            engine.loadLocalFile(file, 6000)
            withTimeout(6000) { engine.state.first { !it.buffering && it.positionMs > 200 } }
            val before = decoders()
            assertEquals(1, before.size)
            val position = engine.state.value.positionMs
            engine.setGain(.3f)
            delay(400)
            assertEquals("Cambiar volumen no debe reiniciar el decodificador", before, decoders())
            assertTrue(engine.state.value.positionMs >= position)
        } finally { engine.stop(); scope.cancel(); delay(200); root.deleteRecursively() }
    }
    @Test fun windowsLocalAudioPausesSeeksResumesAndEndsWithoutOrphans(): Unit = runBlocking {
        assumeTrue(System.getenv("OTO_AUDIO_INTEGRATION") == "1")
        val root = Files.createTempDirectory("oto-audio ñ ").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ended = CompletableDeferred<Unit>()
        val error = CompletableDeferred<Throwable>()
        val engine = DesktopAudioEngine(root, scope, { ended.complete(Unit) }, { error.complete(it) })
        try {
            val format = AudioFormat(48000f, 16, 1, true, false)
            val file = File(root, "silencio.wav")
            AudioInputStream(ByteArrayInputStream(ByteArray(48000 * 2 * 3)), format, 48000L * 3).use {
                AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
            }
            assertTrue(engine.loadLocalFile(file, 3000, playWhenReady = false, initialPositionMs = 500))
            delay(200)
            assertFalse(engine.state.value.playing)
            engine.resume()
            withTimeout(6000) { engine.state.first { !it.buffering && it.positionMs > 650 } }
            engine.pause()
            val paused = engine.state.value.positionMs
            delay(200)
            assertEquals(paused, engine.state.value.positionMs)
            assertEquals(0, players())
            engine.seekTo(2400)
            engine.resume()
            withTimeout(6000) { ended.await() }
            assertFalse(error.isCompleted)
            assertFalse(engine.state.value.playing)
        } finally {
            engine.stop(); scope.cancel(); delay(200)
            assertEquals(0, players())
            root.deleteRecursively()
        }
    }

    @Test fun invalidLocalAudioReportsFailureAndClearsPlaying(): Unit = runBlocking {
        assumeTrue(System.getenv("OTO_AUDIO_INTEGRATION") == "1")
        val root = Files.createTempDirectory("oto-audio-error").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val error = CompletableDeferred<Throwable>()
        val engine = DesktopAudioEngine(root, scope, {}, { error.complete(it) })
        try {
            assertTrue(engine.loadLocalFile(File(root, "invalid.webm").apply { writeText("audio inválido") }, 1000))
            withTimeout(6000) { error.await() }
            assertFalse(engine.state.value.playing)
            assertFalse(engine.state.value.buffering)
        } finally { engine.stop(); scope.cancel(); root.deleteRecursively() }
    }

    private fun players() = ProcessHandle.current().descendants().use { processes ->
        processes.filter { it.info().command().orElse("").endsWith("ffplay.exe", true) && it.isAlive }.count().toInt()
    }
    private fun decoders() = ProcessHandle.current().descendants().use { processes ->
        processes.filter { p -> p.isAlive && p.info().command().orElse("").let { it.endsWith("ffplay.exe",true) || it.endsWith("ffmpeg.exe",true) } }
            .map { it.pid() }.toList().toSet()
    }
}
