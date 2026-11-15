package io.github.iaguito22.oto.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CaptureGateTest {
    private fun pcm16(frames: Int, value: Short = 10_000): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { b.putShort(value) }
        b.flip()
        return b
    }

    private fun sample(b: ByteBuffer, frame: Int): Int = b.duplicate().order(ByteOrder.LITTLE_ENDIAN).getShort(frame * 4).toInt()

    @Test
    fun openGateLeavesAudioUntouched() {
        val g = CaptureGate().apply { configure(C.ENCODING_PCM_16BIT, 2, 48_000) }
        val b = pcm16(1_000)
        g.apply(b, 0, b.limit())
        assertEquals(10_000, sample(b, 999))
    }

    @Test
    fun closedFromTheStartIsSilentAtOnce() {
        val g = CaptureGate().apply { configure(C.ENCODING_PCM_16BIT, 2, 48_000) }
        g.set(open = false)
        val b = pcm16(1_000)
        g.apply(b, 0, b.limit())
        assertEquals(0, sample(b, 0))
        assertEquals(0, sample(b, 999))
    }

    @Test
    fun closingAndOpeningRampInThirtyMs() {
        val g = CaptureGate().apply { configure(C.ENCODING_PCM_16BIT, 2, 48_000) }
        g.open = false
        val b = pcm16(4_800) // 100 ms
        g.apply(b, 0, b.limit())
        // A mitad de la rampa (15 ms = 720 tramas) va por la mitad; pasada la rampa, silencio.
        assertTrue(sample(b, 720) in 4_000..6_000)
        assertEquals(0, sample(b, 1_500))
        g.open = true
        val c = pcm16(4_800)
        g.apply(c, 0, c.limit())
        assertTrue(sample(c, 10) < 1_000)
        assertEquals(10_000, sample(c, 2_000))
    }

    @Test
    fun eachSongGetsItsLevelFromItsFirstSample() {
        val g = CaptureGate().apply { configure(C.ENCODING_PCM_16BIT, 2, 48_000) }
        g.periodIds = mapOf("pA" to "a", "pB" to "b")
        g.levels = mapOf("a" to 0.5f, "b" to 0.25f)
        g.onStreamRead(1_000L, "pA")
        g.onStreamRead(2_000L, "pB")
        g.onStreamOutput(1_000L)
        val a = pcm16(100)
        g.apply(a, 0, a.limit())
        assertEquals(5_000, sample(a, 0))
        // Frontera: la siguiente canción sale ya a su nivel, sin rampa desde la anterior.
        g.onStreamOutput(2_000L)
        val b = pcm16(100)
        g.apply(b, 0, b.limit())
        assertEquals(2_500, sample(b, 0))
        // Nivel desconocido: se queda el último.
        g.onStreamRead(3_000L, "pC")
        g.onStreamOutput(3_000L)
        val c = pcm16(100)
        g.apply(c, 0, c.limit())
        assertEquals(2_500, sample(c, 99))
    }

    @Test
    fun floatAudioIsGatedToo() {
        val g = CaptureGate().apply { configure(C.ENCODING_PCM_FLOAT, 1, 48_000) }
        g.set(open = false)
        val b = ByteBuffer.allocate(400).order(ByteOrder.LITTLE_ENDIAN)
        repeat(100) { b.putFloat(0.5f) }
        b.flip()
        g.apply(b, 0, b.limit())
        assertEquals(0f, b.duplicate().order(ByteOrder.LITTLE_ENDIAN).getFloat(396), 0f)
    }
}
