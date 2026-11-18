package io.github.iaguito22.oto.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/**
 * Fundido suave a tempo (opt-in del plan con `beatAligned = true`) y velocidad que de verdad lleva el WAV.
 * Señales sintéticas: miden fase, continuidad y tramo seco; no dicen nada de cómo suena con música real.
 */
class DjBridgeTempoLockTest {
    private val sr = 48_000

    /** Bombos secos con chasquido agudo a [bpm] desde [firstMs], estéreo a 48 kHz. */
    private fun kicks(durationSec: Double, bpm: Double, firstMs: Double = 0.0): FloatArray {
        val frames = (durationSec * sr).toInt()
        val out = FloatArray(frames * 2)
        var beat = firstMs / 1000.0
        while (beat < durationSec) {
            val start = (beat * sr).toInt()
            val len = (0.09 * sr).toInt()
            for (i in 0 until len) {
                val f = start + i
                if (f >= frames) break
                val t = i.toDouble() / sr
                val v = (sin(2.0 * PI * 60.0 * t) * (1.0 - t / 0.09) * 0.6 + sin(2.0 * PI * 1_500.0 * t) * exp(-t / 0.015) * 0.4).toFloat()
                out[f * 2] += v
                out[f * 2 + 1] += v
            }
            beat += 60.0 / bpm
        }
        return out
    }

    private fun tone(durationSec: Double, hz: Double, amp: Double): FloatArray {
        val frames = (durationSec * sr).toInt()
        return FloatArray(frames * 2) { i -> (amp * sin(2.0 * PI * hz * (i / 2) / sr)).toFloat() }
    }

    private fun onsets(stereo: FloatArray, fromFrame: Int, toFrame: Int, threshold: Float): List<Double> {
        val res = ArrayList<Double>()
        var quietUntil = fromFrame
        for (f in maxOf(0, fromFrame) until minOf(toFrame, stereo.size / 2)) {
            if (f >= quietUntil && abs(stereo[f * 2]) >= threshold) {
                res += f * 1000.0 / sr
                quietUntil = f + (0.2 * sr).toInt()
            }
        }
        return res
    }

    private fun spec(
        bpmA: Double,
        bpmB: Double,
        ratio: Double,
        beatAligned: Boolean?,
        entryInMs: Long,
        style: DjBridgeStyle = DjBridgeStyle.SmartFade,
    ) = DjBridgeSpec(
        style = style, preRollStartMs = 16_000L, cueOutStartMs = 20_000L, cueOutEndMs = 27_000L,
        entryInMs = entryInMs, dryBResumeMs = 30_000L, preRollMs = 4_000L, postRollMs = 3_000L,
        transitionDurationMs = 7_000L, bpmA = bpmA, bpmB = bpmB, tempoMatchRatio = ratio, echoDelayMs = 0.0,
        beatAligned = beatAligned,
    )

    @Test
    fun lockedSmartFadeKeepsBothSongsOnTheSameBeats() {
        val bpmA = 120.0
        val bpmB = 124.0
        val ratio = bpmA / bpmB
        val sliceA = kicks(40.0, bpmA)
        val sliceB = kicks(40.0, bpmB)
        // La entrada pedida cae 90 ms antes de un golpe de B: la fase se mide en el PCM y se corrige.
        val s = spec(bpmA, bpmB, ratio, beatAligned = true, entryInMs = (4 * 60_000.0 / bpmB - 90.0).toLong())
        val onlyA = DjBridgeRenderer.renderSliceBridge(s, sliceA, 0L, sliceB, 0L, gainA = 1f, gainB = 0f, sampleRate = sr)
        val onlyB = DjBridgeRenderer.renderSliceBridge(s, sliceA, 0L, sliceB, 0L, gainA = 0f, gainB = 1f, sampleRate = sr)
        assertEquals(onlyA.totalFrames, onlyB.totalFrames)
        assertEquals("velocidad aplicada", ratio, onlyB.spec.tempoMatchRatio, 1e-9)
        assertEquals("fase corregida", 90.0, onlyB.spec.kickShiftMs, 8.0)
        assertTrue(onlyB.spec.beatAligned == true)

        val from = onlyA.preRollFrames + sr / 4
        val to = onlyA.preRollFrames + (0.85 * 7.0 * sr).toInt()
        val beatsA = onsets(onlyA.stereoPcm, from, to, 0.004f)
        val beatsB = onsets(onlyB.stereoPcm, from, to, 0.004f)
        val lo = maxOf(beatsA.first(), beatsB.first()) - 1.0
        val hi = minOf(beatsA.last(), beatsB.last()) + 1.0
        val both = beatsA.filter { it in lo..hi }
        assertTrue("golpes de las dos a la vez: ${both.size}", both.size >= 8)
        val worst = both.maxOf { t -> beatsB.minOf { abs(it - t) } }
        assertTrue("en el fundido a tempo los golpes se separan $worst ms", worst <= 12.0)
    }

    @Test
    fun lockedSmartFadeReturnsToOneXAndHandsOverOnTheIncomingBeats() {
        val bpmA = 120.0
        val bpmB = 124.0
        val ratio = bpmA / bpmB
        val sliceA = kicks(40.0, bpmA)
        val sliceB = kicks(60.0, bpmB)
        val s = spec(bpmA, bpmB, ratio, beatAligned = true, entryInMs = (4 * 60_000.0 / bpmB).toLong())
        val r = DjBridgeRenderer.renderSliceBridge(s, sliceA, 0L, sliceB, 0L, gainA = 0f, gainB = 1f, sampleRate = sr)
        // La vuelta a 1× hace el tramo más largo que el cruce, y el cambio audible sigue a mitad del cruce.
        assertTrue("tramo con vuelta: ${r.dryBStartOffsetMs - r.preRollMs} ms", r.dryBStartOffsetMs - r.preRollMs > 7_000L + 2_000L)
        assertTrue("cambio audible a mitad del cruce", abs(r.preRollMs + 3_500L - r.audibleSwitchMs) <= 2L)
        // El tramo seco empieza dentro de lo que el plan reserva con `lockedCrossSourceMs` (acuerdo con el planificador).
        val budget = DjTempoMatch.lockedCrossSourceMs(s.transitionDurationMs, bpmA, ratio)
        assertTrue(
            "B consumida ${r.handoffBTrackMs - s.entryInMs} ms, reserva $budget ms",
            r.handoffBTrackMs - s.entryInMs in (7_000L * ratio).toLong()..budget,
        )
        val periodB = 60_000.0 / bpmB
        val dry = onsets(r.stereoPcm, r.dryBStartOffsetFrames + sr / 50, r.totalFrames, 0.02f)
        assertTrue("golpes en el tramo seco: ${dry.size}", dry.size >= 3)
        for (t in dry) {
            val inB = r.handoffBTrackMs + (t - r.dryBStartOffsetMs)
            val k = kotlin.math.round(inB / periodB)
            assertTrue("golpe seco a ${"%.1f".format(inB - k * periodB)} ms de su sitio en B", abs(inB - k * periodB) <= 4.0)
        }
        // Sin hueco ni golpe doble en la costura con el tramo seco.
        val before = onsets(r.stereoPcm, r.dryBStartOffsetFrames - 2 * sr, r.dryBStartOffsetFrames, 0.02f).last()
        assertEquals(periodB, dry.first() - before, 6.0)
    }

    @Test
    fun lockedSmartFadeSeamIsContinuousOnASustainedTone() {
        // B sostenida (sin ataques que tapen un salto) y A callada: la costura con el tramo seco no puede tener ni
        // escalón de nivel ni muestra suelta.
        val bpmA = 120.0
        val bpmB = 124.0
        val sliceA = FloatArray(40 * sr * 2)
        val sliceB = tone(60.0, 220.0, 0.4)
        val s = spec(bpmA, bpmB, bpmA / bpmB, beatAligned = true, entryInMs = 2_000L)
        val r = DjBridgeRenderer.renderSliceBridge(s, sliceA, 0L, sliceB, 0L, gainA = 1f, gainB = 1f, sampleRate = sr)
        assertTrue(DjTempoMatch.isStretched(r.spec.tempoMatchRatio))
        val seam = r.dryBStartOffsetFrames
        fun maxStep(from: Int, to: Int): Float {
            var m = 0f
            for (f in from + 1 until to) m = maxOf(m, abs(r.stereoPcm[f * 2] - r.stereoPcm[(f - 1) * 2]))
            return m
        }
        fun rmsDb(from: Int, to: Int): Double {
            var acc = 0.0
            for (f in from until to) acc += r.stereoPcm[f * 2].toDouble() * r.stereoPcm[f * 2]
            return 10.0 * log10(acc / (to - from) + 1e-12)
        }
        val win = sr / 50
        val reference = maxStep(seam + sr / 2, seam + sr)
        val atSeam = maxStep(seam - win, seam + win)
        assertTrue("salto en la costura $atSeam (referencia $reference)", atSeam <= reference * 1.6f + 0.002f)
        val lvlBefore = rmsDb(seam - 4 * win, seam)
        val lvlAfter = rmsDb(seam, seam + 4 * win)
        assertTrue("escalón de nivel ${"%.2f".format(lvlAfter - lvlBefore)} dB", abs(lvlAfter - lvlBefore) <= 1.0)
        // Tampoco dentro de la vuelta a 1× (WSOLA): ni huecos ni picos sueltos.
        val glideFrom = r.preRollFrames + 7 * sr
        val glideStep = maxStep(glideFrom, seam - win)
        assertTrue("salto dentro de la vuelta $glideStep", glideStep <= reference * 1.6f + 0.002f)
        val quietest = (glideFrom until seam - win step win).minOf { rmsDb(it, it + win) }
        assertTrue("hueco en la vuelta: ${"%.1f".format(quietest)} dB", quietest >= rmsDb(seam, seam + sr / 2) - 1.5)
    }

    @Test
    fun smartFadeWithoutThePlanFlagStaysAtOneXAndReportsIt() {
        // Ratio pedido pero sin `beatAligned`: el fundido de siempre, y el WAV dice 1× (antes repetía el del plan).
        val sliceA = kicks(40.0, 120.0)
        val sliceB = kicks(40.0, 124.0)
        val s = spec(120.0, 124.0, 120.0 / 124.0, beatAligned = null, entryInMs = 2_000L)
        val r = DjBridgeRenderer.renderSliceBridge(s, sliceA, 0L, sliceB, 0L, sampleRate = sr)
        assertEquals(1.0, r.spec.tempoMatchRatio, 0.0)
        assertEquals(0.0, r.spec.kickShiftMs, 0.0)
        assertEquals("sin vuelta a 1×: cruce exacto", 7_000L, r.dryBStartOffsetMs - r.preRollMs)
        assertEquals(s.entryInMs + 7_000L, r.handoffBTrackMs)
    }

    @Test
    fun incoherentOrShortLockedPlansAreNotForced() {
        val sliceA = kicks(40.0, 120.0)
        // `beatAligned` con un ratio que no corresponde a los BPM (B iría a 1× a 124 sobre 120): no se cuadra a medias.
        val incoherent = spec(120.0, 124.0, 1.0, beatAligned = true, entryInMs = 2_000L)
        val r1 = DjBridgeRenderer.renderSliceBridge(incoherent, sliceA, 0L, kicks(40.0, 124.0), 0L, sampleRate = sr)
        assertEquals(1.0, r1.spec.tempoMatchRatio, 0.0)
        assertFalse(r1.spec.beatAligned == true)
        assertEquals(0.0, r1.spec.kickShiftMs, 0.0)
        // Más allá del 8 %: tampoco (el renderizador no recorta en silencio a otro tempo).
        val far = spec(120.0, 108.0, 120.0 / 108.0, beatAligned = true, entryInMs = 2_000L)
        assertFalse(DjBridgeRenderer.smartFadeTempoLocked(far))
        // Tramo de B que no alcanza cruce + vuelta + tramo seco: fundido a 1× en vez de un relevo sin tramo seco.
        val short = spec(120.0, 124.0, 120.0 / 124.0, beatAligned = true, entryInMs = 2_000L)
        val r2 = DjBridgeRenderer.renderSliceBridge(short, sliceA, 0L, kicks(12.0, 124.0), 0L, sampleRate = sr)
        assertEquals(1.0, r2.spec.tempoMatchRatio, 0.0)
        assertFalse(r2.spec.beatAligned == true)
    }

    @Test
    fun reportedSpeedFollowsWhatEachStyleRenders() {
        val sliceA = kicks(40.0, 120.0)
        val sliceB = kicks(40.0, 124.0)
        val ratio = 120.0 / 124.0
        // Eco sin alinear por el plan: B entra a 1× aunque el ratio venga relleno.
        val echo = spec(120.0, 124.0, ratio, beatAligned = false, entryInMs = 2_000L, style = DjBridgeStyle.FilterEcho)
        assertEquals(1.0, DjBridgeRenderer.renderSliceBridge(echo, sliceA, 0L, sliceB, 0L, sampleRate = sr).spec.tempoMatchRatio, 0.0)
        // Corte al drop: nunca estira.
        val drop = spec(120.0, 124.0, ratio, beatAligned = null, entryInMs = 2_000L, style = DjBridgeStyle.EnergyDropCut)
            .copy(pickupMs = 500L, silenceMs = 1_000L, transitionDurationMs = 2_250L)
        assertEquals(1.0, DjBridgeRenderer.appliedTempoRatio(drop, smartFadeLock = false), 0.0)
        // Cuadre de beats: la del plan.
        val beat = spec(120.0, 124.0, ratio, beatAligned = null, entryInMs = 2_000L, style = DjBridgeStyle.BeatMatchLongMix)
        assertEquals(ratio, DjBridgeRenderer.renderSliceBridge(beat, sliceA, 0L, sliceB, 0L, sampleRate = sr).spec.tempoMatchRatio, 1e-9)
        // Sin BPM de B, el cuadre va a 1× y lo dice.
        assertEquals(1.0, DjBridgeRenderer.appliedTempoRatio(beat.copy(bpmB = 0.0), smartFadeLock = false), 0.0)
    }

    @Test
    fun lockedCrossBudgetMatchesTheBeatMatchAccounting() {
        val ratio = 120.0 / 124.0
        assertEquals(
            Math.round(7_000.0 * ratio) + DjTempoMatch.bridgeTailSourceMs(120.0, ratio),
            DjTempoMatch.lockedCrossSourceMs(7_000L, 120.0, ratio),
        )
        assertEquals(7_000L + DjTempoMatch.MAX_PHASE_SHIFT_MS, DjTempoMatch.lockedCrossSourceMs(7_000L, 120.0, 1.0))
    }
}
