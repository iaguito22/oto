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

    private fun sineStereoPcm(freqHz: Double, frames: Int): ByteArray {
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val s = (sin(2.0 * PI * freqHz * i / 48_000.0) * 16_000.0).roundToInt().coerceIn(-32768, 32767)
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
