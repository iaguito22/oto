package io.github.iaguito22.oto.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Cuadre de tempo: límite de estiramiento, bombo al pulso obligatorio y vuelta lenta a 1× con B ya sola. */
class DjTempoMatchTest {
    private val rate = 22_050

    private fun bars(
        durMs: Long,
        bpm: Double,
        kickOnBeat: Double,
        vocalRatio: Double = 0.10,
        kickSteady: Double = 0.85,
    ): List<BarFeature> {
        val out = ArrayList<BarFeature>()
        var t = 0L
        while (t < durMs) {
            out += BarFeature(
                startMs = t, endMs = (t + 2_000L).coerceAtMost(durMs), localBpm = bpm, lufs = -11.0,
                lowRms = 0.42, midRms = 0.30, highRms = 0.18, kickSteady = kickSteady, drumEnergy = 0.70,
                harshness = 0.12, vocalRatio = vocalRatio, kickOnBeat = kickOnBeat,
            )
            t += 2_000L
        }
        return out
    }

    private fun metrics(id: String, bpm: Double, kickOnBeat: Double, vocalRatio: Double = 0.10, durMs: Long = 180_000L) =
        AudioMetrics(
            trackId = id, bpm = bpm, bpmConfidence = 0.90, beatPhaseMs = 0L, downbeatPhaseMs = 0L, rmsDb = -11.0,
            integratedLufs = -11.0, keyName = "A min", camelot = "8A", keyConfidence = 0.80, beatStrength = 0.70,
            firstSoundMs = 200L, lastSoundMs = durMs - 400L, outroStartMs = durMs - 400L, durationMs = durMs,
            beatGrid = listOf(0L, 60_000L, 120_000L, 170_000L), analyzedMs = durMs,
            bars = bars(durMs, bpm, kickOnBeat, vocalRatio),
        )

    private fun plan(a: AudioMetrics, b: AudioMetrics) = DjBridgePlanner.plan(
        outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 8_000L,
        outgoingAudio = a, incomingAudio = b, isDjSession = true,
    )

    @Test
    fun limitsDependOnVocalsInTheStretchedPart() {
        assertEquals(8.0, DjTempoMatch.maxStretchPct(0.05), 1e-9)
        assertEquals(6.0, DjTempoMatch.maxStretchPct(0.50), 1e-9)
        assertEquals(DjTempoMatch.MAX_RATIO, DjTempoMatch.clampRatio(1.20), 1e-9)
        assertEquals(DjTempoMatch.MIN_RATIO, DjTempoMatch.clampRatio(0.80), 1e-9)
    }

    @Test
    fun returnGlideIsLongerTheMoreItStretchedAndMeasuredInBeats() {
        assertEquals(0L, DjTempoMatch.returnGlideMs(120.0, 1.0))
        assertEquals(4_000L, DjTempoMatch.returnGlideMs(120.0, 1.015)) // 8 beats a 120
        assertEquals(6_000L, DjTempoMatch.returnGlideMs(120.0, 0.97)) // 12 beats
        assertEquals(8_000L, DjTempoMatch.returnGlideMs(120.0, 1.06)) // 16 beats
        assertEquals(10_000L, DjTempoMatch.returnGlideMs(80.0, 1.06)) // tope
        assertEquals(3_000L, DjTempoMatch.returnGlideMs(180.0, 1.01)) // mínimo
    }

    @Test
    fun noKickOnTheBeatMeansNoBeatMatch() {
        val withKicks = plan(metrics("a", 124.0, 0.80), metrics("b", 120.0, 0.80))
        assertEquals(DjBridgeStyle.BeatMatchLongMix, withKicks.style)
        assertNotEquals(1.0, withKicks.tempoMatchRatio, 1e-6)

        // Mismo pulso fiable y `kickSteady` alto, pero sin bombo en el beat (hi-hats/bajo): no se cuadra ni se estira.
        val noKickB = plan(metrics("a", 124.0, 0.80), metrics("b2", 120.0, 0.10))
        assertNotEquals(DjBridgeStyle.BeatMatchLongMix, noKickB.style)
        assertTrue(noKickB.style != DjBridgeStyle.FilterEcho || noKickB.tempoMatchRatio == 1.0)
        val noKickA = plan(metrics("a2", 124.0, 0.10), metrics("b", 120.0, 0.80))
        assertNotEquals(DjBridgeStyle.BeatMatchLongMix, noKickA.style)
    }

    @Test
    fun stretchLimitIsLowerWhenTheIncomingSings() {
        // 120 → 112,5 BPM: B se estira un 6,7 %.
        val instrumental = plan(metrics("a", 120.0, 0.80), metrics("b", 112.5, 0.80, vocalRatio = 0.05))
        assertEquals(DjBridgeStyle.BeatMatchLongMix, instrumental.style)
        val singing = plan(metrics("a", 120.0, 0.80), metrics("b", 112.5, 0.80, vocalRatio = 0.60))
        assertNotEquals(DjBridgeStyle.BeatMatchLongMix, singing.style)
        // 9 %: nunca.
        val far = plan(metrics("a", 120.0, 0.80), metrics("b", 110.0, 0.80, vocalRatio = 0.05))
        assertNotEquals(DjBridgeStyle.BeatMatchLongMix, far.style)
    }

    @Test
    fun incomingReturnsToItsTempoSlowlyAfterTheOutgoingIsGone() {
        val spec = plan(metrics("a", 124.0, 0.80), metrics("b", 118.0, 0.80))
        assertEquals(DjBridgeStyle.BeatMatchLongMix, spec.style)
        val ratio = spec.tempoMatchRatio
        val glide = DjTempoMatch.returnGlideMs(spec.bpmA, ratio)
        assertTrue("glide=$glide", glide >= 5_000L)

        val sr = 48_000
        val originA = (spec.preRollStartMs - 500L).coerceAtLeast(0L)
        val framesA = (((spec.cueOutEndMs - originA + 1_500L) * sr) / 1_000L).toInt()
        val sliceA = FloatArray(framesA * 2) { i -> (0.3 * sin(2.0 * PI * 330.0 * (i / 2) / sr)).toFloat() }
        val originB = (spec.entryInMs - 1_000L).coerceAtLeast(0L)
        val framesB = (((spec.dryBResumeMs + spec.postRollMs - originB + 2_500L) * sr) / 1_000L).toInt()
        val sliceB = FloatArray(framesB * 2) { i -> (0.3 * sin(2.0 * PI * 220.0 * (i / 2) / sr)).toFloat() }
        val rendered = DjBridgeRenderer.renderSliceBridge(spec, sliceA, originA, sliceB, originB, sampleRate = sr)

        // El tramo entre el pre-roll y el B seco = cruce + vuelta (B sola) a 1×.
        val transitionMs = rendered.dryBStartOffsetMs - rendered.preRollMs
        assertTrue(
            "transition=$transitionMs cruce=${spec.transitionDurationMs} vuelta=$glide",
            transitionMs >= spec.transitionDurationMs + glide - 400L,
        )
        // La carátula cambia a mitad del cruce, no a mitad de todo el tramo.
        val expectedSwitch = rendered.preRollMs + spec.transitionDurationMs / 2
        assertTrue("switch=${rendered.audibleSwitchMs} esperado≈$expectedSwitch", abs(rendered.audibleSwitchMs - expectedSwitch) <= 30L)
        // Se decodifica bastante B para el cruce estirado y la vuelta.
        assertTrue(spec.dryBResumeMs >= rendered.handoffBTrackMs)
    }

    private fun synth(bpm: Double, seconds: Double, voice: (t: Double, posInBeat: Double, beat: Double) -> Double): ShortArray {
        val n = (seconds * rate).roundToInt()
        val beat = 60.0 / bpm
        return ShortArray(n) { i ->
            val t = i.toDouble() / rate
            (voice(t, t % beat, beat) * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun kick(pos: Double): Double {
        val env = exp(-pos / 0.055)
        return sin(2.0 * PI * (58.0 + 45.0 * exp(-pos / 0.020)) * pos) * env
    }

    private fun hat(pos: Double): Double = (if (((pos * 9973.0).toInt() and 1) == 0) 1.0 else -1.0) * exp(-pos / 0.012)

    @Test
    fun analysisMeasuresKickOnTheBeat() {
        // Bombo con su clic de baqueta (agudo corto), como uno real; un bombo sin nada de agudos da ~0,4 (sigue pasando).
        val fourOnFloor = synth(120.0, 40.0) { t, pos, _ -> 0.7 * kick(pos) + 0.15 * hat(pos) + 0.05 * sin(2.0 * PI * 220.0 * t) }
        val m = AudioAnalyzer.analyzePcm16("kick", fourOnFloor, rate)
        val kick = DjTempoMatch.kickOnBeat(m.bars, 8_000L, 32_000L)
        assertTrue("4 al suelo: kickOnBeat=$kick", kick >= 0.6)

        // Hi-hats a negras y un bajo que golpea a contratiempo: hay pulso, pero no bombo en el beat.
        val offbeatBass = synth(120.0, 40.0) { _, pos, beat ->
            val off = (pos + beat / 2) % beat
            0.35 * hat(pos) + 0.6 * kick(off)
        }
        val o = AudioAnalyzer.analyzePcm16("offbeat", offbeatBass, rate)
        val off = DjTempoMatch.kickOnBeat(o.bars, 8_000L, 32_000L)
        // Si el analizador toma el bajo como el beat, la rejilla se corre medio beat: entonces es «bombo» de verdad
        // para cuadrar (cae en la rejilla). Lo que no puede pasar es que unos hi-hats solos cuenten como bombo.
        val hatsOnly = synth(120.0, 40.0) { t, pos, _ -> 0.4 * hat(pos) + 0.15 * sin(2.0 * PI * 55.0 * t) }
        val h = AudioAnalyzer.analyzePcm16("hats", hatsOnly, rate)
        val hats = DjTempoMatch.kickOnBeat(h.bars, 8_000L, 32_000L)
        assertTrue("solo hi-hats: kickOnBeat=$hats", hats < DjTempoMatch.MIN_KICK_ON_BEAT)
        // Según dónde ponga el beat el analizador: en los hi-hats → el bajo cae a contratiempo y no hay bombo al pulso;
        // en el bajo → ese golpe ES el pulso (y los hi-hats su contratiempo).
        val phase = o.beatGrid.map { (it % 500L).toDouble() }.map { if (it > 250.0) 500.0 - it else it }.average()
        if (phase < 100.0) assertTrue("rejilla en los hi-hats: kickOnBeat=$off", off < DjTempoMatch.MIN_KICK_ON_BEAT)
        else assertTrue("rejilla en el bajo: kickOnBeat=$off", off >= DjTempoMatch.MIN_KICK_ON_BEAT)
        assertTrue(abs(kick - hats) > 0.3)
    }

    @Test
    fun houseWithOffbeatBassStillHasKickOnTheBeat() {
        // 4×4 con bajo a contratiempo (notas cortas pero sostenidas 150 ms) y charles a contratiempo: típico house.
        val house = synth(124.0, 40.0) { t, pos, beat ->
            val off = (pos + beat / 2) % beat
            val bassEnv = if (off < 0.15) (off / 0.005).coerceAtMost(1.0) else exp(-(off - 0.15) / 0.03)
            0.65 * kick(pos) + 0.12 * hat(pos) + 0.45 * sin(2.0 * PI * 55.0 * t) * bassEnv + 0.2 * hat(off)
        }
        val m = AudioAnalyzer.analyzePcm16("house", house, rate)
        val v = DjTempoMatch.kickOnBeat(m.bars, 8_000L, 32_000L)
        assertTrue("house: kickOnBeat=$v (BPM ${m.bpm})", v >= DjTempoMatch.MIN_KICK_ON_BEAT)
    }

    @Test
    fun sustainedBassOrPianoOnTheBeatIsNotAKick() {
        // Nota grave a negras con ataque rápido pero sostenida (bajo / mano izquierda de piano), sin batería.
        val notes = doubleArrayOf(55.0, 49.0, 41.2, 46.2)
        val bass = synth(120.0, 40.0) { t, pos, beat ->
            val f = notes[((t / beat).toInt() / 4) % notes.size]
            val env = (pos / 0.004).coerceAtMost(1.0) * exp(-pos / 1.2)
            0.6 * sin(2.0 * PI * f * t) * env + 0.15 * sin(2.0 * PI * f * 4 * t) * env
        }
        val m = AudioAnalyzer.analyzePcm16("bass", bass, rate)
        val v = DjTempoMatch.kickOnBeat(m.bars, 8_000L, 32_000L)
        assertTrue("bajo sostenido: kickOnBeat=$v", v < DjTempoMatch.MIN_KICK_ON_BEAT)
    }

    @Test
    fun percussiveBassOnEqualEighthsIsNotAKickOnTheBeat() {
        // Bajo percusivo en corcheas iguales (mismo golpe en el beat y en el contratiempo), sin batería.
        val eighths = synth(120.0, 40.0) { _, pos, beat ->
            val half = beat / 2
            val p8 = pos % half
            val bassHit = sin(2.0 * PI * 70.0 * p8) * exp(-p8 / 0.050)
            0.6 * bassHit
        }
        val m = AudioAnalyzer.analyzePcm16("eighths", eighths, rate)
        val v = DjTempoMatch.kickOnBeat(m.bars, 8_000L, 32_000L)
        // Si el analizador toma la corchea como beat (240 BPM), cada golpe cae en su beat y no hay contratiempo: eso sí es
        // un pulso con golpe para cuadrar. Lo que no puede pasar es aceptarlo a 120 con corcheas iguales.
        val bpm = m.bpm ?: 0.0
        assertTrue("el analizador lo toma a 120 (medido): $bpm", bpm in 100.0..140.0)
        assertTrue("corcheas iguales a $bpm BPM: kickOnBeat=$v", v < DjTempoMatch.MIN_KICK_ON_BEAT)
    }

    @Test
    fun halfOrDoubleTempoIsNotStretched() {
        val spec = plan(metrics("a", 120.0, 0.8), metrics("b", 240.0, 0.8))
        assertEquals("a doble tempo con bombo se cuadra", DjBridgeStyle.BeatMatchLongMix, spec.style)
        assertTrue("ratio=${spec.tempoMatchRatio}", !DjTempoMatch.isStretched(spec.tempoMatchRatio))
        // En vivo también se cuadra la fase (sobre el periodo más corto).
        assertTrue(DjTransitionPlanner.fromBridgeSpec(spec, null, null).phaseLock)
        assertEquals(0.0, DjTempoMatch.stretchPct(DjHarmonic.bpmRelation(120.0, 240.0).ratioOutOverIn), 1e-9)
    }

    @Test
    fun stretchedMixAlwaysFitsInsideTheIncomingSong() {
        // B corta: cruce estirado + vuelta + tramo seco tienen que caber; si no, otro estilo.
        for (durB in listOf(20_000L, 30_000L, 40_000L, 60_000L, 180_000L)) {
            val spec = DjBridgePlanner.plan(
                outgoingDurationMs = 180_000L, incomingDurationMs = durB, configuredFadeMs = 8_000L,
                outgoingAudio = metrics("a", 124.0, 0.85), incomingAudio = metrics("b$durB", 116.0, 0.85, vocalRatio = 0.05, durMs = durB),
                isDjSession = true,
            )
            if (durB == 180_000L) assertEquals("B larga: se cuadra", DjBridgeStyle.BeatMatchLongMix, spec.style)
            if (durB == 20_000L) {
                // 8 s de cruce ×1,07 + ~9 s de vuelta y cola + 5,2 s de tramo seco: no caben en 20 s.
                assertTrue("B de 20 s: no cabe el cruce estirado", spec.style != DjBridgeStyle.BeatMatchLongMix)
                assertTrue(spec.tempoNote, spec.tempoNote.contains("no cabe"))
            }
            if (spec.style == DjBridgeStyle.BeatMatchLongMix ||
                (spec.style == DjBridgeStyle.FilterEcho && spec.beatAligned == true)
            ) {
                val needs = spec.entryInMs + (spec.transitionDurationMs * spec.tempoMatchRatio).toLong() +
                    DjTempoMatch.bridgeTailSourceMs(spec.bpmA, spec.tempoMatchRatio) + DjBridgePlanner.POST_ROLL_MS
                assertTrue("B de $durB ms: hace falta hasta $needs", needs <= durB)
            }
        }
    }

    @Test
    fun unmeasuredOrSyntheticBarsNeverEnableBeatMatch() {
        // Sin medir (-1) cuenta como 0 aunque `kickSteady` sea alto.
        assertEquals(0.0, DjTempoMatch.kickOnBeat(bars(60_000L, 120.0, kickOnBeat = -1.0, kickSteady = 0.95), 0L, 60_000L), 1e-9)
        val unmeasured = plan(metrics("a", 124.0, -1.0), metrics("b", 120.0, -1.0))
        assertNotEquals(DjBridgeStyle.BeatMatchLongMix, unmeasured.style)

        // Análisis a medias de A (solo su primer minuto, con bombo): el final se rellena con compases sintéticos, que
        // no pueden dar bombo para cuadrar al final de A.
        val full = metrics("a", 124.0, 0.90)
        val partialA = full.copy(analyzedMs = 60_000L, bars = full.bars.filter { it.endMs <= 60_000L })
        val p = plan(partialA, metrics("b", 120.0, 0.90))
        assertNotEquals(DjBridgeStyle.BeatMatchLongMix, p.style)
        assertTrue(p.style != DjBridgeStyle.FilterEcho || p.beatAligned != true)
    }

    @Test
    fun liveEchoAtTempoAlsoLocksThePhase() {
        val base = DjBridgeSpec(
            style = DjBridgeStyle.FilterEcho, preRollStartMs = 16_000L, cueOutStartMs = 20_000L, cueOutEndMs = 27_000L,
            entryInMs = 4_000L, dryBResumeMs = 14_000L, transitionDurationMs = 7_000L, bpmA = 124.0, bpmB = 120.0,
            tempoMatchRatio = 124.0 / 120.0,
        )
        val aligned = DjTransitionPlanner.fromBridgeSpec(base.copy(beatAligned = true), null, null)
        assertTrue("eco a tempo: fase cuadrada en vivo", aligned.phaseLock)
        assertEquals(120.0 / 124.0, aligned.outgoingSpeed, 1e-6)
        val notAligned = DjTransitionPlanner.fromBridgeSpec(base.copy(beatAligned = false, tempoMatchRatio = 1.0), null, null)
        assertTrue(!notAligned.phaseLock)
        assertEquals(1.0, notAligned.incomingTempoNudge, 1e-9)
    }

    @Test
    fun livePhaseErrorWorksAtSameHalfAndDoubleTempo() {
        // Mismo tempo: como antes ((inOff − outOff)·P).
        assertEquals(60.0, DjTempoMatch.phaseErrorMs(0.0, 500.0, 0.12, 500.0), 1e-9)
        assertEquals(-100.0, DjTempoMatch.phaseErrorMs(0.30, 500.0, 0.10, 500.0), 1e-9)
        // B al doble (periodo 250): sus golpes caen sobre los de A cada dos; un adelanto de 40 ms se ve igual.
        assertEquals(40.0, DjTempoMatch.phaseErrorMs(0.0, 500.0, 0.16, 250.0), 1e-9)
        // B a la mitad (periodo 1000) con 70 ms de retraso respecto a un golpe de A.
        assertEquals(-70.0, DjTempoMatch.phaseErrorMs(0.0, 500.0, -0.07, 1000.0), 1e-9)
        // B a la mitad a medio beat de A (250 ms): el error es de 250 (el máximo), no 0.
        assertEquals(250.0, abs(DjTempoMatch.phaseErrorMs(0.0, 500.0, 0.25, 1000.0)), 1e-9)
        // Simulación de la corrección en vivo a doble tempo: con 120 ms de desfase inicial, empujando la entrante hacia
        // −error converge a menos de 10 ms (como hace el bucle de fase).
        var offsetMs = 120.0
        repeat(40) {
            val err = DjTempoMatch.phaseErrorMs(0.0, 500.0, ((offsetMs / 250.0 + 0.5) % 1.0) - 0.5, 250.0)
            offsetMs -= err * 0.3
        }
        assertTrue("desfase final $offsetMs", abs(DjTempoMatch.phaseErrorMs(0.0, 500.0, ((offsetMs / 250.0 + 0.5) % 1.0) - 0.5, 250.0)) < 10.0)
    }

    @Test
    fun theReasonForNoBeatMatchIsLogged() {
        val noKick = plan(metrics("a", 124.0, 0.80), metrics("b2", 120.0, 0.10))
        assertTrue(noKick.tempoNote, noKick.tempoNote.contains("bombo A 0.80 B 0.10") || noKick.tempoNote.contains("bombo A 0,80 B 0,10"))
        assertTrue(noKick.tempoNote, noKick.tempoNote.contains("sin alinear"))
        assertTrue(noKick.tempoNote, noKick.tempoNote.contains("descartes de cuadre"))
    }

    @Test
    fun theNoteExplainsAClosedGateAndTheChosenLimit() {
        // Tonalidades que chocan (y B sin bombo, para que tampoco haya eco alineado): el cuadre ni se intenta y la nota
        // dice por qué, en vez de «0 descartes».
        val clash = plan(metrics("a", 124.0, 0.80), metrics("b", 120.0, 0.10).copy(keyName = "F# maj", camelot = "2B"))
        assertNotEquals(DjBridgeStyle.BeatMatchLongMix, clash.style)
        assertTrue(clash.tempoNote, clash.tempoNote.contains("cuadre cerrado: armonía"))
        // Cuadre elegido: la nota lleva el límite de ESA pareja (instrumental: 8 %).
        val ok = plan(metrics("a", 124.0, 0.80), metrics("b", 120.0, 0.80))
        assertEquals(DjBridgeStyle.BeatMatchLongMix, ok.style)
        assertTrue(ok.tempoNote, ok.tempoNote.contains("lím 8 %") && ok.tempoNote.contains(", cuadre"))
    }

    @Test
    fun liveAlignedEchoAtOneXStillLocksThePhase() {
        val a = metrics("a", 120.0, 0.80)
        for (bpmB in listOf(120.0, 60.0, 240.0)) {
            val b = metrics("b", bpmB, 0.80)
            val spec = DjBridgeSpec(
                style = DjBridgeStyle.FilterEcho, preRollStartMs = 16_000L, cueOutStartMs = 20_000L, cueOutEndMs = 27_000L,
                entryInMs = 4_000L, dryBResumeMs = 14_000L, transitionDurationMs = 7_000L, bpmA = 120.0, bpmB = bpmB,
                tempoMatchRatio = 1.0, beatAligned = true,
            )
            val live = DjTransitionPlanner.fromBridgeSpec(spec, a, b)
            assertEquals(1.0, live.incomingTempoNudge, 1e-9)
            assertTrue("eco alineado a 1× con B a $bpmB BPM corrige la fase", DjTempoMatch.livePhaseLock(live))
            val loose = DjTransitionPlanner.fromBridgeSpec(spec.copy(beatAligned = false), a, b)
            assertTrue("eco sin alinear no toca la fase", !DjTempoMatch.livePhaseLock(loose))
            assertTrue("sin análisis no hay fase", !DjTempoMatch.livePhaseLock(DjTransitionPlanner.fromBridgeSpec(spec, null, null)))
        }
    }

    @Test
    fun legacyPlannerAlsoRequiresKicks() {
        fun legacy(a: AudioMetrics, b: AudioMetrics) = DjTransitionPlanner.plan(
            outgoingDurationMs = 180_000L, configuredFadeMs = 8_000L, outgoingAudio = a, incomingAudio = b,
        )
        val noBarsA = metrics("a", 124.0, 0.9).copy(bars = emptyList())
        val noBarsB = metrics("b", 122.0, 0.9).copy(bars = emptyList())
        assertNotEquals(DjTransitionStyle.BeatMatchLongMix, legacy(noBarsA, noBarsB).style)
    }

    @Test
    fun filterEchoRendererObeysThePlanWhenItSaysNotAligned() {
        // 124 → 120 BPM (antes el renderizador lo daba por cuadrado por los BPM), pero el plan dice que no (sin bombo).
        val sr = 48_000
        val spec = DjBridgeSpec(
            style = DjBridgeStyle.FilterEcho, preRollStartMs = 16_000L, cueOutStartMs = 20_000L, cueOutEndMs = 27_000L,
            entryInMs = 4_000L, dryBResumeMs = 14_000L, preRollMs = 4_000L, postRollMs = 2_000L,
            transitionDurationMs = 7_000L, bpmA = 124.0, bpmB = 120.0, tempoMatchRatio = 1.0, echoDelayMs = 0.0,
            beatAligned = false,
        )
        val sliceA = FloatArray(40 * sr * 2)
        val sliceB = FloatArray(30 * sr * 2) { i -> (0.4 * sin(2.0 * PI * 220.0 * (i / 2) / sr)).toFloat() }
        val r = DjBridgeRenderer.renderSliceBridge(spec, sliceA, 0L, sliceB, 0L, sampleRate = sr)
        assertEquals("sin corrección de fase", 0.0, r.spec.kickShiftMs, 1e-9)
        // B no entra en el primer instante del cruce: el primer 40 % sin B (A está en silencio).
        val from = r.preRollFrames
        val to = from + (0.40 * 7.0 * sr).toInt()
        var peak = 0f
        for (f in from until to) peak = maxOf(peak, abs(r.stereoPcm[f * 2]))
        assertTrue("B ya suena al principio del cruce: pico $peak", peak < 0.01f)
    }

    @Test
    fun filterEchoStretchedRespectsTheVocalLimit() {
        // Choque de textura (fuerza el filtro con eco) a 120 → 112 BPM (7,1 %), con bombo en las dos.
        fun echoPair(vocals: Double): DjBridgeSpec {
            val a = metrics("piano", 120.0, 0.8).let { m ->
                m.copy(bars = m.bars.map { it.copy(harshness = 0.05, highRms = 0.08, lowRms = 0.35, drumEnergy = 0.25) })
            }
            val b = metrics("rock", 112.0, 0.8, vocalRatio = vocals).let { m ->
                m.copy(bars = m.bars.map { it.copy(harshness = 0.78, highRms = 0.65, lowRms = 0.14, drumEnergy = 0.55) })
            }
            return plan(a, b)
        }
        val singing = echoPair(0.60)
        if (singing.style == DjBridgeStyle.FilterEcho) {
            assertTrue("B canta: no se estira al 7 %", singing.beatAligned != true && singing.tempoMatchRatio == 1.0)
        } else {
            assertNotEquals(DjBridgeStyle.BeatMatchLongMix, singing.style)
        }
        val instrumental = echoPair(0.05)
        assertEquals("el choque de textura fuerza el filtro con eco", DjBridgeStyle.FilterEcho, instrumental.style)
        assertTrue("instrumental: se cuadra", instrumental.beatAligned == true && DjTempoMatch.isStretched(instrumental.tempoMatchRatio))
    }

    /** Bombos secos con chasquido agudo a [bpm] desde [firstMs], en estéreo a 48 kHz. */
    private fun kicks48(durationSec: Double, bpm: Double, firstMs: Double = 0.0): FloatArray {
        val sr = 48_000
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

    private fun onsets(stereo: FloatArray, fromFrame: Int, toFrame: Int, threshold: Float, sr: Int = 48_000): List<Double> {
        val res = ArrayList<Double>()
        var quietUntil = fromFrame
        for (f in fromFrame until minOf(toFrame, stereo.size / 2)) {
            if (f >= quietUntil && abs(stereo[f * 2]) >= threshold) {
                res += f * 1000.0 / sr
                quietUntil = f + (0.2 * sr).toInt()
            }
        }
        return res
    }

    @Test
    fun filterEchoStretchedKeepsBothOnTheSameBeats() {
        val sr = 48_000
        val bpmA = 120.0
        val bpmB = 125.0
        val sliceA = kicks48(40.0, bpmA)
        val sliceB = kicks48(40.0, bpmB)
        val spec = DjBridgeSpec(
            style = DjBridgeStyle.FilterEcho, preRollStartMs = 16_000L, cueOutStartMs = 20_000L, cueOutEndMs = 28_000L,
            entryInMs = (4 * 60_000.0 / bpmB - 60.0).toLong(), dryBResumeMs = 22_000L, preRollMs = 4_000L, postRollMs = 2_000L,
            transitionDurationMs = 8_000L, bpmA = bpmA, bpmB = bpmB, tempoMatchRatio = bpmA / bpmB, echoDelayMs = 0.0,
            beatAligned = true,
        )
        val onlyA = DjBridgeRenderer.renderSliceBridge(spec, sliceA, 0L, sliceB, 0L, gainA = 1f, gainB = 0f, sampleRate = sr)
        val onlyB = DjBridgeRenderer.renderSliceBridge(spec, sliceA, 0L, sliceB, 0L, gainA = 0f, gainB = 1f, sampleRate = sr)
        val from = onlyA.preRollFrames + sr / 4
        val to = onlyA.preRollFrames + (0.85 * 8.0 * sr).toInt()
        val beatsA = onsets(onlyA.stereoPcm, from, to, 0.004f)
        val beatsB = onsets(onlyB.stereoPcm, from, to, 0.004f)
        val lo = maxOf(beatsA.first(), beatsB.first()) - 1.0
        val hi = minOf(beatsA.last(), beatsB.last()) + 1.0
        val both = beatsA.filter { it in lo..hi }
        assertTrue("golpes de las dos a la vez: ${both.size}", both.size >= 8)
        val worst = both.maxOf { t -> beatsB.minOf { abs(it - t) } }
        assertTrue("en el filtro con eco los golpes se separan $worst ms", worst <= 10.0)
    }

    @Test
    fun longReturnGlideHandsOverToTheDryIncomingOnItsBeats() {
        // 8 %: vuelta de 16 beats. El tramo seco de B que sigue al puente tiene que caer en los golpes de B.
        val sr = 48_000
        val bpmA = 120.0
        val bpmB = 111.2
        val sliceA = kicks48(40.0, bpmA)
        val sliceB = kicks48(60.0, bpmB)
        val ratio = DjTempoMatch.clampRatio(bpmA / bpmB)
        val spec = DjBridgeSpec(
            style = DjBridgeStyle.BeatMatchLongMix, preRollStartMs = 16_000L, cueOutStartMs = 20_000L, cueOutEndMs = 28_000L,
            entryInMs = (4 * 60_000.0 / bpmB).toLong(), dryBResumeMs = 30_000L, preRollMs = 4_000L, postRollMs = 3_000L,
            transitionDurationMs = 8_000L, bpmA = bpmA, bpmB = bpmB, tempoMatchRatio = ratio,
        )
        assertEquals(16, DjTempoMatch.returnGlideBeats(ratio))
        val r = DjBridgeRenderer.renderSliceBridge(spec, sliceA, 0L, sliceB, 0L, gainA = 0f, gainB = 1f, sampleRate = sr)
        val periodB = 60_000.0 / bpmB
        // Golpes del tramo seco: su instante en B (según `handoffBTrackMs`) tiene que ser un golpe de B.
        val dry = onsets(r.stereoPcm, r.dryBStartOffsetFrames + sr / 50, r.totalFrames, 0.02f)
        assertTrue("golpes en el tramo seco: ${dry.size}", dry.size >= 3)
        for (t in dry) {
            val inB = r.handoffBTrackMs + (t - r.dryBStartOffsetMs)
            val k = kotlin.math.round(inB / periodB)
            assertTrue("golpe seco a ${"%.1f".format(inB - k * periodB)} ms de su sitio en B", abs(inB - k * periodB) <= 4.0)
        }
        // Y sin hueco ni golpe doble en la costura: el intervalo entre el último golpe antes y el primero después ≈ 1 beat.
        val before = onsets(r.stereoPcm, r.dryBStartOffsetFrames - 2 * sr, r.dryBStartOffsetFrames, 0.02f).last()
        val after = dry.first()
        assertEquals(periodB, after - before, 6.0)
    }
}
