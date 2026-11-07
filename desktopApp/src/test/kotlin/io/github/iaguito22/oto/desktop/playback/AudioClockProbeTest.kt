package io.github.iaguito22.oto.desktop.playback

import org.junit.Assume.assumeTrue
import org.junit.Test
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Sonda (OTO_AUDIO_PROBE=1, suena silencio): cómo avanza longFramePosition de la línea de Java Sound
 * respecto al reloj y a lo escrito, con la misma línea que usa [DesktopAudioEngine] en Windows.
 */
class AudioClockProbeTest {
    @Test fun lineClockAgainstWallClock() {
        assumeTrue(System.getenv("OTO_AUDIO_PROBE") == "1")
        val format = AudioFormat(48000f, 16, 2, true, false)
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format, 19200)
        println("mixer=${AudioSystem.getMixerInfo().joinToString { it.name }}")
        println("buffer=${line.bufferSize} bytes")
        val silence = ByteArray(8192)
        var written = 0L
        line.write(silence, 0, silence.size); written += silence.size / 4
        line.start()
        val start = System.nanoTime()
        var lastPos = -1L
        var steps = 0
        var stepSum = 0L
        while (System.nanoTime() - start < 3_000_000_000L) {
            line.write(silence, 0, silence.size); written += silence.size / 4
            val pos = line.longFramePosition
            val wall = (System.nanoTime() - start) / 1_000_000
            if (pos != lastPos) { if (lastPos >= 0) { steps++; stepSum += pos - lastPos }; lastPos = pos }
            if (wall % 500 < 4) println("t=${wall}ms pos=${pos * 1000 / 48000}ms escrito=${written * 1000 / 48000}ms pendiente=${(written - pos) * 1000 / 48000}ms")
        }
        // Granularidad con lecturas seguidas (sin escribir).
        val g = ArrayList<Long>()
        var p = line.longFramePosition
        val t0 = System.nanoTime()
        while (System.nanoTime() - t0 < 200_000_000L) { val q = line.longFramePosition; if (q != p) { g += (q - p) * 1000 / 48; p = q } }
        println("saltos de posición (µs·1000/48): ${g.take(20)} media=${if (g.isEmpty()) 0 else g.sum() / g.size}")
        line.stop(); line.close()
    }
}
