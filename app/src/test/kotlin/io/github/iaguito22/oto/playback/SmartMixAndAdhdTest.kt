package io.github.iaguito22.oto.playback

import io.github.iaguito22.oto.dj.AdhdPlanner
import io.github.iaguito22.oto.dj.AdhdSegment
import io.github.iaguito22.oto.dj.DjMood
import io.github.iaguito22.oto.dj.DjPickInfo
import io.github.iaguito22.oto.dj.Kind
import io.github.iaguito22.oto.dj.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SmartMixAndAdhdTest {
    @Test
    fun equalPowerPreservedAcrossWholeFade() {
        for (smart in listOf(false, true)) {
            for (i in 0..100) {
                val p = i / 100f
                val outG = SmartMixMath.outgoingGain(p, smart)
                val inG = SmartMixMath.incomingGain(p, smart)
                val power = outG * outG + inG * inG
                assertEquals("Potencia constante en p=$p (smart=$smart)", 1f, power, 1e-4f)
            }
        }
    }

    @Test
    fun smartMixUsesGentleSCurveAndBassSwapProfile() {
        assertEquals(0f, SmartMixMath.smoothStep(0f), 1e-5f)
        assertEquals(0.5f, SmartMixMath.smoothStep(0.5f), 1e-5f)
        assertEquals(1f, SmartMixMath.smoothStep(1f), 1e-5f)
        assertTrue(SmartMixMath.smoothStep(0.1f) < 0.1f)
        assertTrue(SmartMixMath.smoothStep(0.9f) > 0.9f)

        // Sin mezcla inteligente no se recortan graves.
        assertEquals(0f, SmartMixMath.outgoingBassCut(0.5f, smartMix = false), 1e-5f)
        assertEquals(0f, SmartMixMath.incomingBassCut(0.1f, smartMix = false), 1e-5f)

        // Con mezcla inteligente: entrante entra sin graves y los recupera, saliente los recoge antes.
        assertEquals(0f, SmartMixMath.outgoingBassCut(0f, smartMix = true), 1e-5f)
        assertEquals(1f, SmartMixMath.outgoingBassCut(0.8f, smartMix = true), 1e-5f)
        assertEquals(1f, SmartMixMath.incomingBassCut(0.1f, smartMix = true), 1e-5f)
        assertEquals(0f, SmartMixMath.incomingBassCut(0.9f, smartMix = true), 1e-5f)
        assertTrue(SmartMixMath.outgoingBassCut(0.45f, smartMix = true) > 0.5f)
        assertTrue(SmartMixMath.incomingBassCut(0.45f, smartMix = true) < 0.6f)
    }

    @Test
    fun bassSwapFilterAttenuatesLowFrequenciesMoreThanHighs() {
        val sampleRate = 48_000
        val frames = 16_384 // ~0.34s para que converja el suavizado del corte
        val lowRms = filteredRmsPcm16(sampleRate, frames, freqHz = 60.0)
        val highRms = filteredRmsPcm16(sampleRate, frames, freqHz = 4_000.0)
        assertTrue("60 Hz debe atenuarse claramente frente a 4 kHz (low=$lowRms, high=$highRms)", lowRms < highRms * 0.45)
    }

    @Test
    fun bassSwapFilterProcessesPcmFloatCleanly() {
        val filter = BassSwapFilter()
        filter.configure(48_000, 2)
        assertTrue(filter.isBypassed())

        filter.targetCut = 1f
        assertFalse(filter.isBypassed())

        val frames = 4_096
        val src = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.nativeOrder())
        val dst = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.nativeOrder())
        for (f in 0 until frames) {
            val s = (0.5 * sin(2.0 * PI * 70.0 * f / 48_000.0)).toFloat()
            src.putFloat(s)
            src.putFloat(s)
        }
        src.flip()
        filter.processPcmFloat(src, dst)
        dst.flip()
        assertEquals(frames * 2 * 4, dst.remaining())
        while (dst.hasRemaining()) {
            val v = dst.getFloat()
            assertFalse(v.isNaN() || v.isInfinite())
        }
        filter.resetAll()
        assertTrue(filter.isBypassed())
    }

    @Test
    fun adhdPlaybackPlansTransitionAndMeasuresRealListenTime() {
        val seg = AdhdPlanner.segment(durationMs = 240_000L, trackId = "track-adhd-1", crossfadeMs = 4_000L, mood = DjMood.Energia)!!
        val plan = AdhdPlayback.transitionPlan(seg)!!
        assertEquals(seg.endMs, plan.transitionAtMs)
        assertEquals(seg.mixOutDurationMs, plan.fadeDurationMs)
        assertNull(AdhdPlayback.transitionPlan(null))

        assertTrue(AdhdPlayback.shouldSkipIntro(seg, currentPositionMs = 0L))
        assertFalse(AdhdPlayback.shouldSkipIntro(seg, currentPositionMs = seg.startMs))

        // En ADHD, posMs incluye la intro saltada: debe usarse accumMs o descontar startMs.
        val fastSkipMs = AdhdPlayback.effectiveListenedMs(posMs = seg.startMs + 6_000L, accumMs = 6_000L, segment = seg)
        assertEquals(6_000L, fastSkipMs)

        val fallbackListenMs = AdhdPlayback.effectiveListenedMs(posMs = seg.startMs + 48_000L, accumMs = 0L, segment = seg)
        assertEquals(48_000L, fallbackListenMs)

        // En modo normal (segment = null) conserva la posicion absoluta.
        assertEquals(120_000L, AdhdPlayback.effectiveListenedMs(posMs = 120_000L, accumMs = 40_000L, segment = null))
    }

    @Test
    fun bassSwapFilterRecoversFromNonFiniteFloatSamples() {
        val filter = BassSwapFilter()
        filter.configure(48_000, 1)
        filter.targetCut = 0.8f
        filter.resetState()

        val frames = 512
        val src = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
        val dst = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
        for (f in 0 until frames) {
            val s = if (f == 10) Float.NaN else (0.5 * sin(2.0 * PI * 440.0 * f / 48_000.0)).toFloat()
            src.putFloat(s)
        }
        src.flip()
        filter.processPcmFloat(src, dst)
        dst.flip()
        while (dst.hasRemaining()) {
            val v = dst.getFloat()
            assertFalse("La salida no debe ser NaN ni infinita", v.isNaN() || v.isInfinite())
        }
    }

    @Test
    fun echoOutFilterSweepCutsLowsAndProducesEchoTail() {
        val sampleRate = 48_000
        val filter = EchoOutFilterSweepFilter()
        filter.configure(sampleRate, 1)
        assertTrue(filter.isBypassed())

        filter.delayMs = 100
        filter.feedback = 0.55f
        filter.targetCutoffHz = 2_200f
        filter.targetDry = 1f
        filter.targetWet = 0.8f
        filter.targetFeed = 1f
        filter.resetState()
        assertFalse(filter.isBypassed())

        // 1) Comprobar atenuación pasa-altos: 60 Hz mucho menor que 5000 Hz.
        val lowRms = runSweepTone(sampleRate, 12_000, 60.0, 2_200f)
        val highRms = runSweepTone(sampleRate, 12_000, 5_000.0, 2_200f)
        assertTrue("El pasa-altos debe atenuar 60 Hz frente a 5 kHz (low=$lowRms, high=$highRms)", lowRms < highRms * 0.2)

        // 2) Alimentar un pulso de 100 ms a 2 kHz y comprobar que luego con silencio sigue saliendo cola de eco.
        filter.resetState()
        val pulseFrames = sampleRate / 10 // 100 ms
        val pulseSrc = ByteBuffer.allocate(pulseFrames * 2).order(ByteOrder.nativeOrder())
        val pulseDst = ByteBuffer.allocate(pulseFrames * 2).order(ByteOrder.nativeOrder())
        for (f in 0 until pulseFrames) {
            val s = (0.7 * sin(2.0 * PI * 2_500.0 * f / sampleRate)).toFloat()
            pulseSrc.putShort((s * 32767f).toInt().toShort())
        }
        pulseSrc.flip()
        filter.processPcm16(pulseSrc, pulseDst)

        filter.targetDry = 0f
        filter.targetFeed = 0f
        val tailFrames = sampleRate / 5 // 200 ms de silencio
        val tailSrc = ByteBuffer.allocate(tailFrames * 2).order(ByteOrder.nativeOrder())
        val tailDst = ByteBuffer.allocate(tailFrames * 2).order(ByteOrder.nativeOrder())
        repeat(tailFrames) { tailSrc.putShort(0) }
        tailSrc.flip()
        filter.processPcm16(tailSrc, tailDst)
        tailDst.flip()

        var tailSumSq = 0.0
        var count = 0
        while (tailDst.hasRemaining()) {
            val v = tailDst.getShort() / 32768.0
            tailSumSq += v * v
            count++
        }
        val tailRms = sqrt(tailSumSq / count)
        assertTrue("Debe sonar la cola de eco tras cortar la señal seca (tailRms=$tailRms)", tailRms > 0.02)

        filter.resetAll()
        assertTrue(filter.isBypassed())
    }

    @Test
    fun djSessionCodecRoundTripsNextUpAndHandlesCorruptInput() {
        val picks = listOf(
            DjPickInfo(
                track = Track(
                    id = "id1",
                    title = "Primera",
                    artists = listOf("Artista A", "Artista B"),
                    artistIds = listOf("arA", null),
                    artwork = "https://img/1.jpg",
                    seconds = 215,
                ),
                kind = Kind.FAMILIAR,
                tanda = "Urbano · como Artista A",
                reason = "Clásico tuyo",
            ),
            DjPickInfo(
                track = Track(
                    id = "id2",
                    title = "Puente",
                    artists = listOf("Artista C"),
                    artistIds = listOf("arC"),
                    artwork = null,
                    seconds = 190,
                ),
                kind = Kind.BRIDGE,
                tanda = "Pop · como Artista C",
                reason = "Puente suave",
                isBridge = true,
                bridgeFrom = "Urbano · como Artista A",
                bridgeTo = "Pop · como Artista C",
            ),
        )
        val encoded = DjSessionCodec.encodeNextUp(picks)
        val decoded = DjSessionCodec.decodeNextUp(encoded)
        assertEquals(2, decoded.size)
        assertEquals(picks[0], decoded[0])
        assertEquals(picks[1], decoded[1])

        assertTrue(DjSessionCodec.decodeNextUp(null).isEmpty())
        assertTrue(DjSessionCodec.decodeNextUp("").isEmpty())
        assertTrue(DjSessionCodec.decodeNextUp("{not-json").isEmpty())
    }

    @Test
    fun crossfadeLeadConvergesTowardMeasuredStartupLagInsteadOfDiverging() {
        var lead = 200L
        // Si tail tarda ~70 ms reales en empezar a sonar (player avanza 550 ms, tail 480 ms),
        // lead debe converger hacia 70 ms y no crecer hasta 1000 ms.
        repeat(8) {
            lead = Crossfade.nextLeadMs(lead, playerMovedMs = 550L, tailMovedMs = 480L)
        }
        assertTrue("El adelanto debe converger hacia ~70 ms (fue $lead ms)", lead in 68L..75L)
    }

    @Test
    fun trimmedMedianErrorMsRejectsBluetoothTimestampOutlierSpikes() {
        org.junit.Assert.assertEquals(0L, Crossfade.trimmedMedianErrorMs(longArrayOf()))
        org.junit.Assert.assertEquals(10L, Crossfade.trimmedMedianErrorMs(longArrayOf(8L, 9L, 80L, 10L, 11L)))
        org.junit.Assert.assertEquals(-5L, Crossfade.trimmedMedianErrorMs(longArrayOf(-4L, -6L, 50L, -5L, -7L)))
    }

    @Test
    fun swapDurationUsesTightWindowOnlyWhenPhaseDriftIsSmall() {
        org.junit.Assert.assertEquals(24L, Crossfade.companionSwapMsFor(0L))
        org.junit.Assert.assertEquals(24L, Crossfade.companionSwapMsFor(10L))
        org.junit.Assert.assertEquals(24L, Crossfade.companionSwapMsFor(-10L))
        org.junit.Assert.assertEquals(48L, Crossfade.companionSwapMsFor(11L))
        org.junit.Assert.assertEquals(48L, Crossfade.companionSwapMsFor(-25L))
        // Desfase grande (ya no se descarta la mezcla): relevo largo, entre 90 y 300 ms.
        org.junit.Assert.assertEquals(90L, Crossfade.companionSwapMsFor(36L))
        org.junit.Assert.assertEquals(150L, Crossfade.companionSwapMsFor(-60L))
        org.junit.Assert.assertEquals(300L, Crossfade.companionSwapMsFor(149L))
    }

    private fun runSweepTone(sampleRate: Int, frames: Int, freqHz: Double, cutoffHz: Float): Double {
        val filter = EchoOutFilterSweepFilter()
        filter.configure(sampleRate, 1)
        filter.targetCutoffHz = cutoffHz
        filter.targetDry = 1f
        filter.targetWet = 0f
        filter.targetFeed = 0f
        filter.resetState()

        val src = ByteBuffer.allocate(frames * 2).order(ByteOrder.nativeOrder())
        val dst = ByteBuffer.allocate(frames * 2).order(ByteOrder.nativeOrder())
        for (f in 0 until frames) {
            val s = (0.7 * sin(2.0 * PI * freqHz * f / sampleRate)).toFloat()
            src.putShort((s * 32767f).toInt().toShort())
        }
        src.flip()
        filter.processPcm16(src, dst)
        dst.flip()
        dst.position((frames / 2) * 2)
        var sumSq = 0.0
        var count = 0
        while (dst.hasRemaining()) {
            val v = dst.getShort() / 32768.0
            sumSq += v * v
            count++
        }
        return sqrt(sumSq / count)
    }

    private fun filteredRmsPcm16(sampleRate: Int, frames: Int, freqHz: Double): Double {
        val filter = BassSwapFilter()
        filter.configure(sampleRate, 1)
        filter.targetCut = 1f
        // Pre-asentar el suavizado como ocurre tras unos milisegundos de cruce.
        filter.resetState()

        val src = ByteBuffer.allocate(frames * 2).order(ByteOrder.nativeOrder())
        val dst = ByteBuffer.allocate(frames * 2).order(ByteOrder.nativeOrder())
        for (f in 0 until frames) {
            val s = (0.7 * sin(2.0 * PI * freqHz * f / sampleRate)).toFloat()
            src.putShort((s * 32767f).toInt().toShort())
        }
        src.flip()
        filter.processPcm16(src, dst)
        dst.flip()

        // Medir la segunda mitad cuando el estado transitorio ya se estabilizó.
        val skipBytes = (frames / 2) * 2
        dst.position(skipBytes)
        var sumSq = 0.0
        var count = 0
        while (dst.hasRemaining()) {
            val v = dst.getShort() / 32768.0
            sumSq += v * v
            count++
        }
        return sqrt(sumSq / count)
    }
}
