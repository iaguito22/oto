package io.github.iaguito22.oto.playback

import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CaptureGateTest {
    private class PartialSink(private val bytesPerWrite: Int) {
        val buffers = mutableListOf<ByteBuffer>()
        val samples = mutableListOf<Int>()
        val audibleSamples = mutableListOf<Int>()
        var volume = 1f
        var flushes = 0
        val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "handleBuffer" -> {
                    val b = args!![0] as ByteBuffer
                    buffers += b
                    val end = b.position() + minOf(bytesPerWrite, b.remaining())
                    while (b.position() + 2 <= end) {
                        val sample = b.order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                        samples += sample
                        audibleSamples += (sample * volume).toInt()
                    }
                    !b.hasRemaining()
                }
                "flush" -> { flushes++; null }
                else -> error("Llamada inesperada al sink: ${method.name}")
            }
        } as AudioSink
    }

    @Test fun sinkKeepsTheSameGatedCopyAcrossPartialWritesOfReadOnlyPcm() {
        val gate = CaptureGate().apply { configure(C.ENCODING_PCM_16BIT, 2, 48_000); set(false) }
        val output = PartialSink(4)
        val sink = CaptureGateSink(output.delegate, gate)
        val original = pcm16(8).asReadOnlyBuffer()
        assertTrue(!sink.handleBuffer(original, 0L, 1))
        assertEquals(4, original.position())
        gate.open = true // La copia pendiente debe conservarse, no procesarse otra vez.
        while (original.hasRemaining()) sink.handleBuffer(original, 0L, 1)
        assertTrue(output.buffers.all { it === output.buffers.first() })
        assertTrue(output.buffers.none { it === original })
        assertTrue(output.samples.all { it == 0 })
        assertEquals(10_000, sample(original, 0))
    }

    @Test fun sinkFlushStartsRecoveryFadeOnlyOnceAcrossPartialWrites() {
        val gate = CaptureGate().apply { configure(C.ENCODING_PCM_16BIT, 2, 1_000) }
        val output = PartialSink(16)
        val sink = CaptureGateSink(output.delegate, gate)
        gate.fadeInAfterFlush(64L)
        sink.flush()
        val original = pcm16(80).asReadOnlyBuffer()
        while (original.hasRemaining()) sink.handleBuffer(original, 0L, 1)
        assertEquals(1, output.flushes)
        assertEquals(0, output.samples[0])
        assertEquals(5_000, output.samples[32 * 2])
        assertEquals(5_000, output.samples[32 * 2 + 1])
        assertEquals(10_000, output.samples.last())
        assertEquals(10_000, sample(original, 0))
    }

    @Test fun rebufferDuringPartialSinkWriteUsesOutputRampWithoutALaterPcmDip() = runBlocking {
        val gate = CaptureGate().apply { configure(C.ENCODING_PCM_16BIT, 2, 1_000) }
        val output = PartialSink(32) // 8 ms por escritura.
        val sink = CaptureGateSink(output.delegate, gate)
        val original = pcm16(256).asReadOnlyBuffer()
        assertTrue(!sink.handleBuffer(original, 0L, 1))
        var time = 0L
        var audiblePosition = 0L
        var seeks = 0
        fun playing() = time < 80L || time >= 240L
        val result = BridgeHandoff.recover(
            outgoingGain = 1f, canContinueDestination = { true }, bridgePositionMs = { time }, stopBridge = {},
            destinationForBridge = { it }, seekDestination = { seeks++ }, destinationReady = ::playing,
            destinationPositionMs = { audiblePosition }, cancelled = { false }, nowMs = { time },
            waitFrame = {
                time += 8L
                if (playing()) {
                    audiblePosition += 8L
                    if (original.hasRemaining()) sink.handleBuffer(original, 0L, 1)
                }
            },
            setGains = { _, b -> output.volume = b },
        )
        assertEquals(BridgeHandoff.Recovery.Continued, result)
        assertEquals(0, seeks)
        assertTrue(original.hasRemaining()) // El mismo PCM estaba pendiente antes y después de recargar.
        assertTrue(output.buffers.all { it === original })
        assertTrue(output.samples.all { it == 10_000 })
        assertTrue(output.audibleSamples.any { it in 1..9_999 })
        val recoveredAt = output.audibleSamples.size
        while (original.hasRemaining()) sink.handleBuffer(original, 0L, 1)
        assertTrue(output.audibleSamples.drop(recoveredAt).all { it == 10_000 })
        assertEquals(10_000, sample(original, 0))
    }

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

    @Test fun recoveryFadeStartsInPcmAfterFlushEvenIfPlaybackStartsMuchLater() {
        val g = CaptureGate().apply { configure(C.ENCODING_PCM_16BIT, 2, 48_000) }
        g.fadeInAfterFlush(64L)
        // Armar no modifica el audio anterior a la búsqueda.
        val old = pcm16(100)
        g.apply(old, 0, old.limit())
        assertEquals(10_000, sample(old, 0))
        g.onFlush()
        assertTrue(!g.passthrough())
        // El reloj de pared no interviene: se cuentan muestras que llegarán al altavoz.
        val first = pcm16(1_536)
        g.apply(first, 0, first.limit())
        assertEquals(0, sample(first, 0))
        assertTrue(sample(first, 1_535) in 4_980..5_000)
        val second = pcm16(2_000)
        g.apply(second, 0, second.limit())
        assertTrue(sample(second, 0) in 4_990..5_010)
        assertEquals(10_000, sample(second, 1_999))
        assertTrue(g.passthrough())
    }

    @Test fun seekWithoutFlushDoesNotLeaveAFadeArmedForTheNextSeek() {
        val g = CaptureGate().apply { configure(C.ENCODING_PCM_16BIT, 1, 48_000) }
        g.fadeInAfterFlush(64L)
        g.clearPendingFade()
        g.onFlush()
        val b = pcm16(100)
        g.apply(b, 0, b.limit())
        assertEquals(10_000, sample(b, 0))
    }

    @Test fun floatRecoveryFadeIsAppliedOncePerFrameToEveryChannel() {
        val g = CaptureGate().apply { configure(C.ENCODING_PCM_FLOAT, 2, 1_000) }
        g.fadeInAfterFlush(64L)
        g.onFlush()
        val b = ByteBuffer.allocate(80 * 8).order(ByteOrder.LITTLE_ENDIAN)
        repeat(160) { b.putFloat(0.5f) }; b.flip()
        g.apply(b, 0, b.limit())
        assertEquals(0f, b.getFloat(0), 0f)
        assertEquals(0.25f, b.getFloat(32 * 8), 0.001f)
        assertEquals(b.getFloat(32 * 8), b.getFloat(32 * 8 + 4), 0f)
        assertEquals(0.5f, b.getFloat(79 * 8), 0f)
    }
}
