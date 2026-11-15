package io.github.iaguito22.oto.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * La mezcla a tempo («Cuadre de beats») no debe desfasarse: cada golpe de A cae encima de uno de B durante toda la
 * mezcla, aunque los tempos sean distintos. Antes B volvía a su tempo mientras A solo se acercaba a medias y al final
 * los golpes iban a ~100 ms.
 */
class BeatLockTest {
    private val sr = DjDsp.DJ_SAMPLE_RATE

    /** Bombos secos a [bpm] desde [firstMs], en estéreo. */
    private fun kicks(durationSec: Double, bpm: Double, firstMs: Double = 0.0): FloatArray {
        val frames = (durationSec * sr).toInt()
        val out = FloatArray(frames * 2)
        val period = 60.0 / bpm
        var beat = firstMs / 1000.0
        while (beat < durationSec) {
            val start = (beat * sr).toInt()
            val len = (0.09 * sr).toInt()
            for (i in 0 until len) {
                val f = start + i
                if (f >= frames) break
                val t = i.toDouble() / sr
                // Bombo (60 Hz, para el cuadre de fase) y chasquido agudo, que se oye aunque A ya no tenga graves.
                val v = (sin(2.0 * PI * 60.0 * t) * (1.0 - t / 0.09) * 0.6 + sin(2.0 * PI * 1_500.0 * t) * exp(-t / 0.015) * 0.4).toFloat()
                out[f * 2] += v
                out[f * 2 + 1] += v
            }
            beat += period
        }
        return out
    }

    /** Instantes (ms) donde empieza cada golpe en `[fromFrame, toFrame)`. */
    private fun onsets(stereo: FloatArray, fromFrame: Int, toFrame: Int, threshold: Float = 0.03f): List<Double> {
        val res = ArrayList<Double>()
        var quietUntil = fromFrame
        for (f in fromFrame until minOf(toFrame, stereo.size / 2)) {
            val v = abs(stereo[f * 2])
            if (f >= quietUntil && v >= threshold) {
                res += f * 1000.0 / sr
                quietUntil = f + (0.2 * sr).toInt()
            }
        }
        return res
    }

    @Test
    fun kickPhaseShiftFindsTheOffsetAtDifferentTempos() {
        val a = DjDsp.stereoToMono(kicks(12.0, 120.0))
        val b = DjDsp.stereoToMono(kicks(12.0, 124.0, firstMs = 137.0))
        val (shift, corr) = DjDsp.kickPhaseShiftMs(a, b, sr, outStartFrame = sr * 2, inStartFrame = sr * 2, ratio = 120.0 / 124.0, maxShiftMs = 200.0)
        // B a 124 BPM tiene un golpe en 137 + 4·483,9 = 2072,5 ms; la entrada pedida (2000 ms) va 72,5 ms antes.
        assertEquals(72.5, shift, 6.0)
        assertTrue("correlación clara: $corr", corr > 0.5)
    }

    @Test
    fun beatMatchKeepsBothSongsOnTheSameBeatsThroughTheWholeMix() {
        val bpmA = 120.0
        val bpmB = 124.0
        val sliceA = kicks(40.0, bpmA)
        val sliceB = kicks(40.0, bpmB)
        // La entrada pedida cae 90 ms antes de un golpe de B: el renderizado lo tiene que corregir.
        val entryBeatMs = 4 * 60_000.0 / bpmB
        val spec = DjBridgeSpec(
            style = DjBridgeStyle.BeatMatchLongMix,
            preRollStartMs = 16_000L,
            cueOutStartMs = 20_000L,
            cueOutEndMs = 29_000L,
            entryInMs = (entryBeatMs - 90.0).toLong(),
            dryBResumeMs = 18_000L,
            preRollMs = 4_000L,
            postRollMs = 3_000L,
            transitionDurationMs = 9_000L,
            bpmA = bpmA,
            bpmB = bpmB,
            tempoMatchRatio = bpmA / bpmB,
        )
        // Lo mismo dos veces, una solo con A y otra solo con B: el estirado de cada una no depende de la otra.
        val onlyA = DjBridgeRenderer.renderSliceBridge(spec, sliceA, 0L, sliceB, 0L, gainA = 1f, gainB = 0f)
        val onlyB = DjBridgeRenderer.renderSliceBridge(spec, sliceA, 0L, sliceB, 0L, gainA = 0f, gainB = 1f)
        assertEquals(onlyA.totalFrames, onlyB.totalFrames)
        assertEquals("fase corregida", 90.0, onlyA.spec.kickShiftMs, 8.0)

        val from = onlyA.preRollFrames + sr / 4
        val to = onlyA.dryBStartOffsetFrames - sr / 5
        val beatsA = onsets(onlyA.stereoPcm, from, to, threshold = 0.01f)
        val beatsB = onsets(onlyB.stereoPcm, from, to, threshold = 0.01f)
        // Solo donde se oyen las dos (B entra desde casi nada y A se va apagando).
        val lo = maxOf(beatsA.first(), beatsB.first()) - 1.0
        val hi = minOf(beatsA.last(), beatsB.last()) + 1.0
        val both = beatsA.filter { it in lo..hi }
        assertTrue("golpes de las dos a la vez: ${both.size}", both.size >= 10)
        var worst = 0.0
        for (t in both) {
            val nearest = beatsB.minByOrNull { abs(it - t) } ?: continue
            worst = max(worst, abs(nearest - t))
        }
        assertTrue("los golpes de A y B se separan $worst ms", worst <= 8.0)
    }
}
