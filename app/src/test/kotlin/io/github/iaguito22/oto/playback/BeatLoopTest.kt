package io.github.iaguito22.oto.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BeatLoopTest {
    private val rate = 1_000

    private fun run(loop: BeatLoopAudioProcessor, input: ShortArray, chunk: Int = 37): ShortArray {
        val out = ArrayList<Short>()
        var i = 0
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (k in 0 until n) buf.putShort(input[i + k])
            buf.flip()
            loop.queueInput(buf)
            val o = loop.output
            while (o.hasRemaining()) out += o.getShort()
            i += n
        }
        return out.toShortArray()
    }

    private fun ready(startUs: Long): BeatLoopAudioProcessor = BeatLoopAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        flush()
        startHintUs = startUs
        onSinkFlush()
        onFirstBuffer(longArrayOf(startUs + 1_000_000_000_000L, startUs))
    }

    @Test
    fun repeatsTheBarFromTheSongPosition() {
        // El audio empieza en el segundo 10 de la canción; bucle desde 10,1 s de 50 ms (50 muestras a 1 kHz).
        val loop = ready(10_000_000L)
        loop.arm(startMs = 10_100.0, lenMs = 50.0, beatMs = 12.5)
        val input = ShortArray(400) { (it * 10).toShort() }
        val out = run(loop, input)
        assertEquals(input.size, out.size)
        // Antes del bucle y su primera vuelta: la canción tal cual.
        for (i in 0 until 150) assertEquals("muestra $i", input[i], out[i])
        // Después, se repite lo de 100..149 (salvo el fundido corto de 6 muestras en cada vuelta).
        for (i in 150 until 400) {
            val k = (i - 100) % 50
            if (k < 6) continue
            assertEquals("muestra $i", input[100 + k], out[i])
        }
        assertEquals(10_100_000L, loop.loopStartedUs)
        assertEquals(10_100_000L + 50_000L, loop.loopStartedUs + loop.loopLenUs)
        // La posición que se oye vuelve atrás con el bucle (para medir la fase).
        assertEquals(10_110.0, loop.audiblePositionMs(10_210L), 0.001)
    }

    @Test
    fun rollPlaysShorterPieces() {
        val loop = ready(0L)
        loop.arm(startMs = 100.0, lenMs = 80.0, beatMs = 20.0)
        val input = ShortArray(260) { (it * 10).toShort() }
        val first = run(loop, input)
        loop.division = 4
        val more = run(loop, ShortArray(200) { ((260 + it) * 10).toShort() })
        // Con el bucle partido en 4 (un beat de 20 muestras), se repiten las 20 primeras del bucle.
        for (i in 20 until 200) {
            val pos = 260 + i - 100
            val k = pos % 20
            if (k < 6) continue
            assertEquals("muestra ${260 + i}", input[100 + k], more[i])
        }
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun aMissedStartWaitsForTheNextBeat() {
        // El audio ya va por 105 ms cuando se pide empezar en 100: empieza en el siguiente beat (110).
        val loop = ready(105_000L)
        loop.arm(startMs = 100.0, lenMs = 40.0, beatMs = 10.0)
        run(loop, ShortArray(100) { it.toShort() })
        assertEquals(110_000L, loop.loopStartedUs)
    }
}
