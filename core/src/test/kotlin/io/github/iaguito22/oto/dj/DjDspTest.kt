package io.github.iaguito22.oto.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class DjDspTest {
    private fun sineStereo(freqHz: Double, sr: Int = 48_000, sec: Double = 0.8, amp: Float = 0.5f): FloatArray {
        val frames = (sr * sec).roundToInt()
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = (amp * sin(2.0 * PI * freqHz * i / sr)).toFloat()
            out[i * 2] = v
            out[i * 2 + 1] = v
        }
        return out
    }

    private fun rmsRange(stereo: FloatArray, startFrame: Int, endFrame: Int): Double {
        var s = 0.0
        var n = 0
        for (f in startFrame until endFrame) {
            val l = stereo[f * 2].toDouble()
            s += l * l
            n++
        }
        return sqrt(s / maxOf(1, n))
    }

    @Test
    fun lr4ThreeBandPreservesFlatMagnitudeAcrossSpectrumAndSeparatesBands() {
        val sr = 48_000
        for (freq in listOf(60.0, 240.0, 900.0, 2800.0, 7500.0)) {
            val src = sineStereo(freq, sr, sec = 0.6, amp = 0.5f)
            val bands = DjDsp.lr4ThreeBandStereo(src, sr, lowHz = 240.0, highHz = 2800.0)
            val recon = FloatArray(src.size) { i -> bands.low[i] + bands.mid[i] + bands.high[i] }
            val inRms = rmsRange(src, 8_000, src.size / 2)
            val outRms = rmsRange(recon, 8_000, src.size / 2)
            val dbErr = abs(20.0 * log10(outRms / inRms))
            assertTrue("Flat sum at $freq Hz (err=$dbErr dB)", dbErr < 0.08)
        }

        // Separación de bandas a 60 Hz y 6000 Hz.
        val lowSig = sineStereo(60.0, sr, sec = 0.5, amp = 0.5f)
        val lowSplit = DjDsp.lr4ThreeBandStereo(lowSig, sr)
        assertTrue(rmsRange(lowSplit.low, 8_000, lowSig.size / 2) > rmsRange(lowSplit.mid, 8_000, lowSig.size / 2) * 8.0)

        val hiSig = sineStereo(6000.0, sr, sec = 0.5, amp = 0.5f)
        val hiSplit = DjDsp.lr4ThreeBandStereo(hiSig, sr)
        assertTrue(rmsRange(hiSplit.high, 8_000, hiSig.size / 2) > rmsRange(hiSplit.mid, 8_000, hiSig.size / 2) * 6.0)
    }

    @Test
    fun bs1770LufsMatchesReferenceFullScaleSine() {
        val sr = 48_000
        val fullSine = sineStereo(997.0, sr, sec = 1.5, amp = 1.0f)
        val lufs = DjDsp.integratedLufsStereo(fullSine, sr)
        // ITU-R BS.1770-4: tono 997 Hz a 0 dBFS en 1 canal = -3.01 LKFS; en L+R dual-mono = ~0.00 LKFS.
        assertEquals(0.0, lufs, 0.20)

        val monoRef = FloatArray((sr * 1.5).roundToInt()) { i -> sin(2.0 * PI * 997.0 * i / sr).toFloat() }
        assertEquals(-3.01, DjDsp.integratedLufsMono(monoRef, sr), 0.20)

        val quiet = sineStereo(997.0, sr, sec = 1.5, amp = 0.1f)
        val lufsQuiet = DjDsp.integratedLufsStereo(quiet, sr)
        assertEquals(lufs - 20.0, lufsQuiet, 0.2)
    }

    @Test
    fun fftDetectsExactDominantFrequencyBin() {
        val sr = 22_050
        val n = 2048
        val fft = DjDsp.Radix2Fft(n)
        val win = DjDsp.hannWindow(n)
        val frame = DoubleArray(n) { i -> sin(2.0 * PI * 440.0 * i / sr) * win[i] }
        val mag = DoubleArray(n / 2 + 1)
        fft.magnitudeSpectrum(frame, mag)
        val peakBin = mag.indices.maxByOrNull { mag[it] }!!
        val peakFreq = peakBin.toDouble() * sr / n
        assertEquals(440.0, peakFreq, 12.0)
    }

    @Test
    fun wsolaTimeStretchChangesDurationWithoutShiftingPitch() {
        val sr = 48_000
        val src = sineStereo(440.0, sr, sec = 1.2, amp = 0.5f)
        val srcFrames = src.size / 2
        val speed = FloatArray(srcFrames) { 1.08f }
        val stretched = DjDsp.timeStretchStereo(src, sr, speed)
        val outFrames = stretched.size / 2
        assertEquals(srcFrames / 1.08, outFrames.toDouble(), srcFrames * 0.03)

        // Verifica con FFT en el centro que el tono sigue siendo 440 Hz.
        val fft = DjDsp.Radix2Fft(4096)
        val win = DjDsp.hannWindow(4096)
        val center = outFrames / 2 - 2048
        val frame = DoubleArray(4096) { i -> stretched[(center + i) * 2].toDouble() * win[i] }
        val mag = DoubleArray(2049)
        fft.magnitudeSpectrum(frame, mag)
        val peakBin = mag.indices.maxByOrNull { mag[it] }!!
        val peakFreq = peakBin.toDouble() * sr / 4096
        assertEquals(440.0, peakFreq, 15.0)
    }

    @Test
    fun truePeakLimiterEnforcesCeilingAndKickCorrelationFindsOffset() {
        val sr = 48_000
        val loud = sineStereo(300.0, sr, sec = 0.4, amp = 1.8f)
        val limited = DjDsp.truePeakLimiterStereo(loud, sr, ceilingDb = -0.8)
        val maxAbs = limited.maxOf { abs(it) }
        assertTrue("Max peak should stay under 0.995 (got $maxAbs)", maxAbs <= 0.995f)

        // Bombo sintético en Out a 1.0 s y en In adelantado +36 ms respecto al ancla -> detecta el lag.
        val frames = sr * 4
        val out = FloatArray(frames)
        val inn = FloatArray(frames)
        for (beat in listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0)) {
            val sOut = (beat * sr).toInt()
            val sIn = ((beat + 0.036) * sr).toInt()
            for (k in 0 until 2200) {
                val kick = (exp(-k / 450.0) * sin(2.0 * PI * 62.0 * k / sr)).toFloat()
                if (sOut + k < frames) out[sOut + k] += kick
                if (sIn + k < frames) inn[sIn + k] += kick
            }
        }
        val lag = DjDsp.kickCorrelationShiftMs(out, inn, sr, anchorOutFrame = (1.5 * sr).toInt(), anchorInFrame = (1.5 * sr).toInt())
        assertEquals(36.0, lag, 6.0)
    }

    @Test
    fun correlateShiftMsMeasuresPositiveAndNegativeSubsampleShiftsAndRejectsSilence() {
        val sr = 48_000
        val durSec = 3.0
        val n = (sr * durSec).toInt()
        val stereo = FloatArray(n * 2)
        // Señal rica con pulsos + armónicos modulados (no periódica pura para evitar ambigüedad de ciclo).
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = if ((t * 3.1).toInt() % 2 == 0) 0.7 else 0.25
            val s = (env * (0.45 * sin(2.0 * PI * (190.0 + 95.0 * t) * t) + 0.35 * sin(2.0 * PI * 730.0 * t * (1.0 + 0.1 * sin(t))))).toFloat()
            stereo[i * 2] = s
            stereo[i * 2 + 1] = s
        }
        val ref12k = DjDsp.stereoToMono12k(stereo)

        // Simular que live(1000 ms) coincide con ref(1073.5 ms) -> shift esperado +73.5 ms.
        val sr12 = DjDsp.SYNC_TAP_SAMPLE_RATE
        val shiftSamplesPos = ((73.5 / 1000.0) * sr12).toInt()
        val livePos = FloatArray(sr12) { k -> ref12k[((500 * sr12 / 1000) + shiftSamplesPos + k).coerceIn(0, ref12k.lastIndex)] }
        val posRes = DjDsp.correlateShiftMs(
            liveMono12k = livePos,
            liveOriginMs = 10_500L,
            refMono12k = ref12k,
            refOriginMs = 10_000L,
            anchorMs = 10_700L,
            windowMs = 380L,
        )
        assertTrue(posRes != null)
        assertEquals(73.5, posRes!!.first, 0.35)
        assertTrue(posRes.second > 0.97)

        // Simular desplazamiento negativo (-42 ms).
        val shiftSamplesNeg = ((-42.0 / 1000.0) * sr12).toInt()
        val liveNeg = FloatArray(sr12) { k -> ref12k[((800 * sr12 / 1000) + shiftSamplesNeg + k).coerceIn(0, ref12k.lastIndex)] }
        val negRes = DjDsp.correlateShiftMs(
            liveMono12k = liveNeg,
            liveOriginMs = 10_800L,
            refMono12k = ref12k,
            refOriginMs = 10_000L,
            anchorMs = 11_000L,
            windowMs = 380L,
        )
        assertTrue(negRes != null)
        assertEquals(-42.0, negRes!!.first, 0.35)
        assertTrue(negRes.second > 0.97)

        // Silencio se rechaza limpio.
        val silence = FloatArray(sr12)
        val silRes = DjDsp.correlateShiftMs(silence, 10_500L, ref12k, 10_000L, anchorMs = 10_700L)
        assertTrue(silRes == null)
    }

    @Test
    fun djBridgeStyleFromIdMapsKnownAndUnknownIds() {
        for (style in DjBridgeStyle.entries) {
            assertEquals(style, DjBridgeStyle.fromId(style.id))
        }
        assertEquals(null, DjBridgeStyle.fromId(null))
        assertEquals(null, DjBridgeStyle.fromId(""))
        assertEquals(null, DjBridgeStyle.fromId("unknown_style"))
    }

    @Test
    fun linearPhaseThreeBandPreservesUnityZeroPhaseAndSeparatesBands() {
        val sr = 48_000
        val frames = sr * 2
        val interiorStart = frames / 4
        val interiorEnd = (frames * 3) / 4

        fun sineStereo(freqHz: Double, amp: Float = 0.8f): FloatArray {
            val out = FloatArray(frames * 2)
            val w = 2.0 * kotlin.math.PI * freqHz / sr
            for (i in 0 until frames) {
                val s = (amp * kotlin.math.sin(w * i)).toFloat()
                out[i * 2] = s
                out[i * 2 + 1] = s
            }
            return out
        }

        fun interiorRmsL(buf: FloatArray): Double {
            var sum = 0.0
            var count = 0
            for (i in interiorStart until interiorEnd) {
                val v = buf[i * 2].toDouble()
                sum += v * v
                count++
            }
            return kotlin.math.sqrt(sum / count)
        }

        fun interiorCorrAtLag(a: FloatArray, b: FloatArray, lagFrames: Int): Double {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in interiorStart until interiorEnd) {
                val x = a[i * 2].toDouble()
                val y = b[(i + lagFrames) * 2].toDouble()
                dot += x * y
                na += x * x
                nb += y * y
            }
            return dot / kotlin.math.sqrt(na * nb).coerceAtLeast(1e-12)
        }

        val sub = sineStereo(60.0)
        val mid = sineStereo(700.0)
        val high = sineStereo(7_500.0)
        val mix = FloatArray(frames * 2) { i -> (sub[i] + mid[i] + high[i]) * 0.3f }

        val mixBands = DjDsp.linearPhaseThreeBandStereo(mix, sr)
        var maxUnityErr = 0f
        for (i in interiorStart * 2 until interiorEnd * 2) {
            val err = kotlin.math.abs((mixBands.low[i] + mixBands.mid[i] + mixBands.high[i]) - mix[i])
            if (err > maxUnityErr) maxUnityErr = err
        }
        assertTrue("linearPhaseThreeBand unity error $maxUnityErr too large", maxUnityErr < 1e-4f)

        val subBands = DjDsp.linearPhaseThreeBandStereo(sub, sr)
        val subLag0 = interiorCorrAtLag(subBands.low, sub, 0)
        val subLag1 = interiorCorrAtLag(subBands.low, sub, 1)
        assertTrue("Low band must be zero-phase at 60 Hz (corr0=$subLag0, corr1=$subLag1)", subLag0 > 0.9999 && subLag0 > subLag1)
        assertTrue("Low band must isolate 60 Hz from mid/high", interiorRmsL(subBands.mid) / interiorRmsL(sub) < 0.03)
        assertTrue("Low band must isolate 60 Hz from high", interiorRmsL(subBands.high) / interiorRmsL(sub) < 0.01)

        val highBands = DjDsp.linearPhaseThreeBandStereo(high, sr)
        val highLag0 = interiorCorrAtLag(highBands.high, high, 0)
        val highLag1 = interiorCorrAtLag(highBands.high, high, 1)
        assertTrue("High band must be zero-phase at 7.5 kHz (corr0=$highLag0, corr1=$highLag1)", highLag0 > 0.9999 && highLag0 > highLag1)
        assertTrue("High band must isolate 7.5 kHz from low", interiorRmsL(highBands.low) / interiorRmsL(high) < 0.01)
        assertTrue("High band must isolate 7.5 kHz from mid", interiorRmsL(highBands.mid) / interiorRmsL(high) < 0.03)
    }
}
