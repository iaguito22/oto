package io.github.iaguito22.oto.desktop.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class DesktopAudioMixTest {
    @Test
    fun `classic equal power curve preserves total energy and neutral bands`() {
        val (outStart, inStart) = MixCurve.bands(0f, smartMix = false)
        assertEquals(1f, outStart.overall, 1e-4f)
        assertEquals(0f, inStart.overall, 1e-4f)
        assertTrue(outStart.isNeutral)
        assertTrue(inStart.isNeutral)

        for (step in 0..20) {
            val p = step / 20f
            val (outB, inB) = MixCurve.bands(p, smartMix = false)
            val power = outB.overall * outB.overall + inB.overall * inB.overall
            assertEquals("Energía constante en p=$p", 1f, power, 1e-4f)
            assertTrue(outB.isNeutral)
            assertTrue(inB.isNeutral)
        }
    }

    @Test
    fun `smart mix applies s-curve equal power and progressive bass swap`() {
        val (outStart, inStart) = MixCurve.bands(0f, smartMix = true)
        assertEquals(1f, outStart.overall, 1e-4f)
        assertEquals(0f, inStart.overall, 1e-4f)

        val (outEnd, inEnd) = MixCurve.bands(1f, smartMix = true)
        assertEquals(0f, outEnd.overall, 1e-4f)
        assertEquals(1f, inEnd.overall, 1e-4f)

        // Curva S: entrada mas gradual al principio que la curva sinusoidal directa
        val (_, inSmartEarly) = MixCurve.bands(0.10f, smartMix = true)
        val (_, inClassicEarly) = MixCurve.bands(0.10f, smartMix = false)
        assertTrue(inSmartEarly.overall < inClassicEarly.overall)

        // Relevo de graves: al 25% la entrante aun contiene el grave y muestra agudos; al 70% ya mando el grave
        val (out25, in25) = MixCurve.bands(0.25f, smartMix = true)
        assertTrue("La entrante debe entrar por agudos antes que por graves", in25.high > 0.9f && in25.low <= 0.15f)
        assertTrue("La saliente conserva algo de cuerpo al inicio", out25.low > 0.7f)

        val (out65, in65) = MixCurve.bands(0.65f, smartMix = true)
        assertTrue("La saliente debe haber soltado los graves al 65%", out65.low == 0f)
        assertTrue("La entrante ya debe haber recuperado casi todo el grave", in65.low > 0.95f)

        for (step in 0..20) {
            val p = step / 20f
            val (outB, inB) = MixCurve.bands(p, smartMix = true)
            val power = outB.overall * outB.overall + inB.overall * inB.overall
            assertEquals("Energía S-curve constante en p=$p", 1f, power, 1e-4f)
        }
    }

    @Test
    fun `stereo band filter keeps flat unity gain when bands are neutral`() {
        for (freq in listOf(70.0, 440.0, 2400.0, 6000.0)) {
            val pcm = sineStereoPcm(freqHz = freq, frames = 9600)
            val filtered = pcm.copyOf()
            StereoBandFilter().processPcm16(filtered, filtered.size, MixBands.NEUTRAL, 1f)
            val ratio = rms(filtered, skipFrames = 2400) / rms(pcm, skipFrames = 2400)
            assertEquals("Ganancia plana en $freq Hz", 1.0, ratio, 0.02)
        }
    }

    @Test
    fun `stereo band filter cuts low bass while preserving highs`() {
        val lowPcm = sineStereoPcm(freqHz = 70.0, frames = 9600)
        val highPcm = sineStereoPcm(freqHz = 6000.0, frames = 9600)
        val bassCut = MixBands(overall = 1f, low = 0f, mid = 1f, high = 1f)

        val lowFiltered = lowPcm.copyOf()
        StereoBandFilter().processPcm16(lowFiltered, lowFiltered.size, bassCut, 1f)

        val highFiltered = highPcm.copyOf()
        StereoBandFilter().processPcm16(highFiltered, highFiltered.size, bassCut, 1f)

        val lowRatio = rms(lowFiltered, skipFrames = 2400) / rms(lowPcm, skipFrames = 2400)
        val highRatio = rms(highFiltered, skipFrames = 2400) / rms(highPcm, skipFrames = 2400)

        assertTrue("El corte de graves debe atenuar 70 Hz (ratio=$lowRatio)", lowRatio < 0.15)
        assertTrue("El corte de graves debe respetar 6 kHz (ratio=$highRatio)", highRatio > 0.85)
    }

    @Test
    fun `canUseFfmpegPcm requires both ffmpeg and supported PCM line`() {
        assertTrue(canUseFfmpegPcm(ffmpegAvailable = true, lineSupported = true))
        assertFalse(canUseFfmpegPcm(ffmpegAvailable = false, lineSupported = true))
        assertFalse(canUseFfmpegPcm(ffmpegAvailable = true, lineSupported = false))
    }

    @Test
    fun `stereo band filter ramps master gain smoothly without step clicks`() {
        val filter = StereoBandFilter()
        val chunk1 = sineStereoPcm(freqHz = 440.0, frames = 2048, frameOffset = 0)
        filter.processPcm16(chunk1, chunk1.size, MixBands.NEUTRAL, masterGain = 1.0f)

        val chunk2 = sineStereoPcm(freqHz = 440.0, frames = 2048, frameOffset = 2048)
        filter.processPcm16(chunk2, chunk2.size, MixBands.NEUTRAL, masterGain = 0.0f)

        // Primera muestra de chunk2 debe seguir cerca de la ultima muestra de chunk1 (rampa suave, sin salto brusco)
        val lastL = sampleLeft(chunk1, 2047)
        val firstL = sampleLeft(chunk2, 0)
        assertTrue("Salto inicial entre bloques demasiado brusco: last=$lastL first=$firstL", kotlin.math.abs(firstL - lastL) < 2_500)
        // Al final de chunk2 (~42 ms) la ganancia suavizada ya debe haberse atenuado notablemente
        val endRms = rms(chunk2, skipFrames = 1500)
        val startRms = rms(chunk1, skipFrames = 200)
        assertTrue("La rampa debe atenuar progresivamente (end=$endRms vs start=$startRms)", endRms < startRms * 0.25)
    }

    @Test
    fun `echo out curve and high pass filter cut bass and sustain echo tail`() {
        val startFrame = MixCurve.echoOut(0f)
        val midFrame = MixCurve.echoOut(0.55f)
        val endFrame = MixCurve.echoOut(0.95f)
        assertTrue(midFrame.outBands.highPassHz > startFrame.outBands.highPassHz)
        assertTrue(midFrame.outBands.echoWet > 0f)
        assertTrue(endFrame.outEnvelope < 0.05f)
        assertTrue(endFrame.inEnvelope > 0.95f)

        // Verificar que el HPF de StereoBandFilter atenúa 70 Hz y deja pasar 6 kHz
        val hpfOnly = MixBands(overall = 1f, low = 1f, mid = 1f, high = 1f, highPassHz = 1_200f)
        val lowPcm = sineStereoPcm(freqHz = 70.0, frames = 9600)
        val highPcm = sineStereoPcm(freqHz = 6_000.0, frames = 9600)
        val lowOut = lowPcm.copyOf().also { StereoBandFilter().processPcm16(it, it.size, hpfOnly, 1f) }
        val highOut = highPcm.copyOf().also { StereoBandFilter().processPcm16(it, it.size, hpfOnly, 1f) }
        assertTrue(rms(lowOut, 2400) / rms(lowPcm, 2400) < 0.10)
        assertTrue(rms(highOut, 2400) / rms(highPcm, 2400) > 0.85)

        // Alimentar una señal con eco activo y luego silencio: la cola de eco debe mantener energía audible
        val echoFilter = StereoBandFilter()
        val burst = sineStereoPcm(freqHz = 880.0, frames = 24_000) // 500 ms
        val activeEcho = MixBands(overall = 1f, highPassHz = 800f, echoWet = 0.65f, echoFeedback = 0.55f, echoDelayMs = 200)
        echoFilter.processPcm16(burst, burst.size, activeEcho, 1f)

        val silenceInput = ByteArray(14_400 * 4) // 300 ms de silencio tras el corte seco
        val tailBands = MixBands(overall = 0f, highPassHz = 1_600f, echoWet = 0.70f, echoFeedback = 0.55f, echoDelayMs = 200)
        echoFilter.processPcm16(silenceInput, silenceInput.size, tailBands, 1f)
        assertTrue("La cola de eco debe seguir sonando tras cortar el seco", rms(silenceInput, skipFrames = 100) > 400.0)
    }

    @Test
    fun `wsola time stretch adjusts duration without clipping and returns cleanly to unity`() {
        val stretcher = WsolaTimeStretch()
        val input = sineStereoPcm(freqHz = 330.0, frames = 48_000) // 1.0 s a 48 kHz
        stretcher.targetTempo = 1.06f
        var outFastFrames = 0
        val chunkBytes = 8192
        var offset = 0
        while (offset < input.size) {
            val len = minOf(chunkBytes, input.size - offset)
            val slice = input.copyOfRange(offset, offset + len)
            stretcher.process(slice, slice.size) { _, outLen ->
                outFastFrames += outLen / 4
                assertTrue("Salida PCM no alineada: $outLen", outLen % 4 == 0)
            }
            offset += len
        }
        stretcher.flush { _, outLen -> outFastFrames += outLen / 4 }
        assertTrue("Tempo 1.06x debe acortar la salida (out=$outFastFrames vs in=48000)", outFastFrames in 43_000..47_000)

        stretcher.reset(0.94f)
        var outSlowFrames = 0
        offset = 0
        while (offset < input.size) {
            val len = minOf(chunkBytes, input.size - offset)
            val slice = input.copyOfRange(offset, offset + len)
            stretcher.process(slice, slice.size) { _, outLen -> outSlowFrames += outLen / 4 }
            offset += len
        }
        stretcher.flush { _, outLen -> outSlowFrames += outLen / 4 }
        assertTrue("Tempo 0.94x debe alargar la salida (out=$outSlowFrames vs in=48000)", outSlowFrames in 49_000..53_500)

        // Tras reset + tempo 1.0f debe pasar directo sin alterar longitud ni estado
        stretcher.reset(1.0f)
        var unityFrames = 0
        stretcher.process(input.copyOf(), input.size) { _, outLen -> unityFrames += outLen / 4 }
        assertEquals(48_000, unityFrames)

        // Retorno dinámico de 1.05x -> 1.0f debe reenganchar sin saltos en sourceFramesConsumed
        stretcher.reset(1.05f)
        var lastConsumed = 0L
        val longInput = sineStereoPcm(freqHz = 110.0, frames = 96_000)
        offset = 0
        while (offset < longInput.size) {
            if (offset >= longInput.size / 4 && stretcher.targetTempo != 1.0f) {
                stretcher.targetTempo = 1.0f
            }
            val len = minOf(chunkBytes, longInput.size - offset)
            val slice = longInput.copyOfRange(offset, offset + len)
            stretcher.process(slice, slice.size) { _, _ -> }
            assertTrue("sourceFramesConsumed debe ser monótono", stretcher.sourceFramesConsumed >= lastConsumed)
            lastConsumed = stretcher.sourceFramesConsumed
            offset += len
        }
        assertTrue("Debe haber reenganchado a 1.0x consumiendo el audio completo ($lastConsumed)", lastConsumed == 96_000L)
    }

    private fun sampleLeft(pcm: ByteArray, frameIndex: Int): Int {
        val i = frameIndex * 4
        return (((pcm[i + 1].toInt() and 255) shl 8) or (pcm[i].toInt() and 255)).toShort().toInt()
    }

    private fun sineStereoPcm(freqHz: Double, frames: Int, frameOffset: Int = 0): ByteArray {
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val s = (sin(2.0 * PI * freqHz * (i + frameOffset) / 48_000.0) * 16_000.0).roundToInt().coerceIn(-32768, 32767)
            val pos = i * 4
            out[pos] = s.toByte()
            out[pos + 1] = (s shr 8).toByte()
            out[pos + 2] = s.toByte()
            out[pos + 3] = (s shr 8).toByte()
        }
        return out
    }

    private fun rms(pcm: ByteArray, skipFrames: Int): Double {
        var sum = 0.0
        var count = 0
        var i = skipFrames * 4
        while (i + 3 < pcm.size) {
            val left = (((pcm[i + 1].toInt() and 255) shl 8) or (pcm[i].toInt() and 255)).toShort().toDouble()
            sum += left * left
            count++
            i += 4
        }
        return sqrt(sum / count.coerceAtLeast(1))
    }
}
