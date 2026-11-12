package io.github.iaguito22.oto.playback

import io.github.iaguito22.oto.dj.AdhdPlanner
import io.github.iaguito22.oto.dj.AdhdSegment
import io.github.iaguito22.oto.dj.DjMood
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
