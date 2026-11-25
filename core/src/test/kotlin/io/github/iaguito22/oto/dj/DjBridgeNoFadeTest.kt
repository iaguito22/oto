package io.github.iaguito22.oto.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresiones de la auditoría del 18/11 (`scratchpad/dj-noche`): parejas que quedaban sin mezcla (`fade=0`) o fuera de
 * frase aunque había un cruce que respetaba los estribillos.
 */
class DjBridgeNoFadeTest {

    @Test
    fun rapidEntryDoesNotReturnToTheIntroBeforeTheChosenSegment() {
        for (start in listOf(5_000L, 8_000L, 16_000L)) {
            val s = DjBridgePlanner.plan(
                outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 8_000L,
                outgoingAudio = metrics("a"), incomingAudio = metrics("b"), djMode = DjMode.Adhd,
                outgoingAdhd = AdhdSegment(20_000L, 105_000L, 85_000L, 97_000L, 8_000L, 180_000L),
                incomingAdhd = AdhdSegment(start, start + 85_000L, 85_000L, start + 77_000L, 8_000L, 180_000L),
            )
            assertTrue(s.reason, s.transitionDurationMs > 0L)
            assertTrue("trozo $start, entrada ${s.entryInMs}", s.entryInMs >= start - 1_500L)
        }
    }

    private fun metrics(
        id: String,
        bpm: Double = 120.0,
        durMs: Long = 180_000L,
        firstSoundMs: Long = 200L,
        kickOnBeat: Double = 0.85,
        camelot: String = "8A",
        keyConfidence: Double = 0.80,
    ): AudioMetrics {
        val bars = ArrayList<BarFeature>()
        var t = 0L
        while (t < durMs) {
            bars += BarFeature(
                startMs = t, endMs = (t + 2_000L).coerceAtMost(durMs), localBpm = bpm, lufs = -11.0,
                lowRms = 0.42, midRms = 0.30, highRms = 0.18, kickSteady = 0.85, drumEnergy = 0.70,
                harshness = 0.12, vocalRatio = 0.15, kickOnBeat = kickOnBeat,
            )
            t += 2_000L
        }
        val beat = (60_000.0 / bpm).toLong()
        return AudioMetrics(
            trackId = id, bpm = bpm, bpmConfidence = 0.90, beatPhaseMs = 0L, downbeatPhaseMs = 0L,
            rmsDb = -11.0, integratedLufs = -11.0, keyName = "A min", camelot = camelot, keyConfidence = keyConfidence,
            beatStrength = 0.70, firstSoundMs = firstSoundMs, lastSoundMs = durMs - 400L, outroStartMs = durMs - 400L,
            durationMs = durMs, beatGrid = (0L until durMs step beat).toList(), analyzedMs = durMs, bars = bars,
        )
    }

    private fun entryOutsideChoruses(spec: DjBridgeSpec, incoming: List<SongSection>) =
        incoming.none { it.isChorus && spec.entryInMs > it.startMs && spec.entryInMs < it.endMs }

    /**
     * La prueba antigua (`outsideRapidDjTheIncomingSongIsNotSkippedPastItsChorus`) solo miraba que B no se saltara su
     * estribillo inicial, y pasaba también sin mezcla: el primer sonido (200 ms) caía dentro del estribillo (0 ms) y se
     * descartaban todas las parejas. Ahora tiene que mezclar entrando por el principio.
     */
    @Test
    fun aPlaylistSongThatOpensWithItsChorusStillGetsAMixFromItsStart() {
        for (chorusB in listOf(SongSection(0L, 60_000L, 1.0), SongSection(300L, 40_000L, 1.0))) {
            val spec = DjBridgePlanner.plan(
                outgoingDurationMs = 180_000L, incomingDurationMs = 200_000L, configuredFadeMs = 10_000L,
                outgoingAudio = metrics("a"), incomingAudio = metrics("b", durMs = 200_000L, firstSoundMs = 900L),
                incomingVocals = listOf(chorusB), isDjSession = false,
            )
            assertTrue("debe mezclar: ${spec.reason} · ${spec.tempoNote}", spec.transitionDurationMs > 0L)
            assertTrue("B empieza por su principio: ${spec.entryInMs}", spec.entryInMs <= chorusB.startMs)
            assertTrue(ChorusSafety.allows(spec, emptyList(), listOf(chorusB)))
            // Cualquier ajuste de fase permitido debe conservar el ataque del estribillo.
            val aligned = spec.style == DjBridgeStyle.BeatMatchLongMix || spec.beatAligned == true
            if (aligned) assertTrue(spec.style.name,
                ChorusSafety.allows(spec.copy(kickShiftMs = spec.kickShiftRangeMs!!.last.toDouble()), emptyList(), listOf(chorusB)))
        }
    }

    /**
     * Estribillo (o partes de un bloque repetido, reguetón) hasta 4,5 s antes del final de A: no cabían los 6 s del
     * respaldo y quedaba sin mezcla. Ahora un fundido corto sobre la cola, sin pisar nada protegido.
     */
    @Test
    fun aChorusEndingNearTheEndLeavesAShortFadeOverTheTail() {
        val single = listOf(SongSection(90_000L, 175_500L, 1.0))
        val parts = listOf(
            SongSection(90_000L, 120_000L, 1.0, part = true, seamFreeMs = 1_500L),
            SongSection(121_000L, 150_000L, 1.0, part = true, seamFreeMs = 1_500L),
            SongSection(151_000L, 175_500L, 1.0, part = true),
        )
        for (sections in listOf(single, parts)) for (dj in listOf(false, true)) {
            val spec = DjBridgePlanner.plan(
                outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 10_000L,
                outgoingAudio = metrics("a"), incomingAudio = metrics("b"),
                outgoingVocals = sections, isDjSession = dj, djMode = DjMode.Normal,
            )
            val label = "dj=$dj partes=${sections.size}: ${spec.reason} ${spec.cueOutStartMs}..${spec.cueOutEndMs}"
            assertTrue(label, spec.transitionDurationMs >= DjBridgePlanner.MIN_FALLBACK_FADE_MS)
            assertTrue(label, spec.cueOutStartMs >= 175_500L + SongSections.VOCAL_TAIL_MS)
            assertTrue(label, spec.cueOutEndMs <= 180_000L)
            assertTrue(label, ChorusSafety.allows(spec, sections, emptyList()))
        }
    }

    /** Sin cola instrumental que alcance el mínimo, sigue sin mezcla, y el motivo dice qué lado lo impidió. */
    @Test
    fun withoutRoomTheNoMixReasonSaysWhichSideBlockedIt() {
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L, incomingDurationMs = 5_000L, configuredFadeMs = 10_000L,
            outgoingAudio = metrics("a"),
            outgoingVocals = listOf(SongSection(90_000L, 178_000L, 1.0)), isDjSession = false,
        )
        assertEquals(0L, spec.transitionDurationMs)
        assertTrue(spec.reason, spec.reason.contains("descartes por estribillo A") && spec.reason.contains("ventana A"))
        val live = DjTransitionPlanner.fromBridgeSpec(spec, null, null)
        assertTrue("el plan sin puente conserva el motivo: ${live.reason}", live.reason.contains("descartes por estribillo"))
    }

    /**
     * DJ rápido con toda la salida del trozo dentro de un estribillo que dura hasta casi el final (escucha del 24/11:
     * «Sin mezcla: proteger el estribillo completo» y luego un fundido de respaldo a 153 de 160 s o a 199 de 204 s, en
     * pleno estribillo). Si el trozo ya ha sonado bastante, se sale antes de ese estribillo.
     */
    @Test
    fun aRapidExcerptLeavesBeforeAChorusThatFillsItsExitWindow() {
        val duration = 200_000L
        val excerpt = AdhdSegment(40_000L, 120_000L, 80_000L, 114_000L, 4_000L, duration)
        val chorus = listOf(SongSection(95_000L, 192_000L, 1.0))
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = duration, incomingDurationMs = 180_000L, configuredFadeMs = 8_000L,
            outgoingAudio = metrics("a", durMs = duration), incomingAudio = metrics("b"),
            outgoingVocals = chorus, outgoingAdhd = excerpt, djMode = DjMode.Adhd,
        )
        val label = "${spec.reason}: ${spec.cueOutStartMs}..${spec.cueOutEndMs}"
        assertTrue(label, spec.transitionDurationMs > 0L)
        assertTrue(label, spec.cueOutEndMs <= 95_000L)
        assertTrue(label, spec.cueOutStartMs >= 40_000L + DjBridgePlanner.ADHD_EARLY_EXIT_MIN_LISTEN_MS)
        assertTrue(label, ChorusSafety.allows(spec, chorus, emptyList()))
    }

    /**
     * DJ rápido sin ninguna salida limpia (escucha del 24/11: casi todas «FUERA de frase» y salidas a mitad de letra):
     * entre las relajadas, mejor una fuera de frase pero sin voz que una en frase que corta la letra de A.
     */
    @Test
    fun withoutACleanExitARapidMixPrefersMissingThePhraseToCuttingTheLyrics() {
        val duration = 200_000L
        // Los límites de frase del trozo caen todos dentro de un verso (95–112 s); después, hasta 126 s, no se canta.
        val excerpt = AdhdSegment(
            40_000L, 120_000L, 80_000L, 108_000L, 4_000L, duration,
            phrasePointsMs = listOf(100_000L, 104_000L, 108_000L),
        )
        val verse = listOf(SongSection(95_000L, 112_000L, 0.0), SongSection(126_000L, 150_000L, 0.0))
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = duration, incomingDurationMs = 180_000L, configuredFadeMs = 8_000L,
            outgoingAudio = metrics("a", durMs = duration), incomingAudio = metrics("b"),
            outgoingVocals = verse, outgoingAdhd = excerpt, djMode = DjMode.Adhd,
        )
        val label = "${spec.style} ${spec.reason} ${spec.tempoNote}: ${spec.cueOutStartMs}..${spec.cueOutEndMs}"
        assertTrue(label, spec.transitionDurationMs > 0L)
        assertFalse("sale cortando la letra: $label", verse.any { spec.cueOutStartMs in (it.startMs + 250L)..(it.endMs - 250L) })
    }

    /**
     * DJ normal (escucha del 24/11: «entrada en letra» en Wanda, Ay Vamos, Safaera): si B canta casi desde el principio
     * y tiene un hueco sin voz dentro de su ventana de entrada, entra ahí y no con la voz encima.
     */
    @Test
    fun theIncomingSongEntersWhereItIsNotSinging() {
        val incomingVerses = listOf(SongSection(2_000L, 20_000L, 0.0), SongSection(30_000L, 60_000L, 0.0))
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 200_000L, incomingDurationMs = 180_000L, configuredFadeMs = 8_000L,
            outgoingAudio = metrics("a", durMs = 200_000L), incomingAudio = metrics("b", bpm = 96.0),
            incomingVocals = incomingVerses, djMode = DjMode.Normal,
        )
        val entry = spec.entryInMs
        val sung = incomingVerses.sumOf { s -> (minOf(s.endMs, entry + 6_000L) - maxOf(s.startMs, entry)).coerceAtLeast(0L) }
        assertTrue("${spec.style} ${spec.reason}: entra a $entry ms con $sung ms cantados en sus 6 primeros s", sung <= 2_000L)
    }

    @Test
    fun aRapidExcerptDoesNotTurnIntoAnOutroAtTheEndOfTheTrack() {
        val duration = 180_000L
        val excerpt = AdhdSegment(20_000L, 102_000L, 82_000L, 98_000L, 4_000L, duration)
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = duration, incomingDurationMs = 180_000L, configuredFadeMs = 8_000L,
            outgoingAudio = metrics("a"), incomingAudio = metrics("b"),
            outgoingVocals = listOf(SongSection(55_000L, duration, 1.0)),
            outgoingAdhd = excerpt, djMode = DjMode.Adhd,
        )
        assertEquals(0L, spec.transitionDurationMs)
        assertFalse(spec.style == DjBridgeStyle.OutroTail)
    }

    @Test
    fun finalChorusKeepsItsAudioAndConnectsToTheStartOfTheNextChorus() {
        val outgoingChorus = listOf(SongSection(90_000L, 180_000L, 1.0))
        val incomingChorus = listOf(SongSection(0L, 20_000L, 1.0))
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 10_000L,
            outgoingAudio = metrics("a"), incomingAudio = metrics("b"),
            outgoingVocals = outgoingChorus, incomingVocals = incomingChorus, isDjSession = false,
        )
        assertEquals(DjBridgeStyle.OutroTail, spec.style)
        assertEquals(180_000L, spec.cueOutEndMs)
        assertEquals(0L, spec.entryInMs)
        assertTrue(ChorusSafety.allows(spec, outgoingChorus, incomingChorus))
        assertFalse(ChorusSafety.allows(spec.copy(entryInMs = 100L), outgoingChorus, incomingChorus))

        val sr = 48_000
        val originA = 180_000L - 10_000L
        val a = FloatArray(sr * 10 * 2) { if (it % 2 == 0) 0.34f else 0f }
        val b = FloatArray(sr * 10 * 2) { if (it % 2 == 1) 0.25f else 0f }
        val bridge = DjBridgeRenderer.renderSliceBridge(spec, a, originA, b, 12L)
        val switchMs = 180_000L - spec.preRollStartMs
        assertEquals(switchMs, bridge.audibleSwitchMs)
        fun sample(ms: Long, channel: Int) = bridge.stereoPcm[((ms * sr) / 1_000L).toInt() * 2 + channel]
        assertEquals(DjDsp.softClip(0.34f), sample(switchMs - 30L, 0), 0.001f)
        assertEquals(0f, sample(switchMs - 30L, 1), 0.001f)
        assertEquals(0f, sample(switchMs + 5L, 1), 0.001f)
        assertEquals(DjDsp.softClip(0.25f), sample(switchMs + 30L, 1), 0.001f)
        assertTrue(sample(switchMs + 30L, 0) in 0.015f..0.08f)
        assertEquals(bridge.dryBStartOffsetMs - switchMs, bridge.handoffBTrackMs)
        assertEquals(0f, sample(bridge.dryBStartOffsetMs + 30L, 0), 0.001f)
        assertEquals(DjDsp.softClip(0.25f), sample(bridge.dryBStartOffsetMs + 30L, 1), 0.001f)
    }

    @Test
    fun outroCanUseMeasuredTrailingSilenceAndStopsDoingSoIfNewLyricsProtectIt() {
        val a = metrics("a").copy(lastSoundMs = 176_500L)
        val vocalTail = listOf(SongSection(90_000L, 176_600L, 1.0))
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 10_000L,
            outgoingAudio = a, incomingAudio = metrics("b"), outgoingVocals = vocalTail, isDjSession = false,
        )
        assertEquals(DjBridgeStyle.OutroTail, spec.style)
        assertEquals(180_000L, spec.cueOutEndMs)
        assertEquals(177_200L, spec.cueOutStartMs + spec.outroIncomingOffsetMs!!)
        assertTrue(ChorusSafety.allows(spec, vocalTail, emptyList()))
        assertFalse(ChorusSafety.allows(spec, listOf(SongSection(90_000L, 179_000L, 1.0)), emptyList()))
    }

    /**
     * DJ rápido: el trozo sale en el compás que sigue a la última palabra del estribillo (+250 ms, `AdhdPlanner`), dentro
     * de la cola vocal de 600 ms que protege el planificador. Antes se descartaba su propia salida y la mezcla quedaba
     * «FUERA de frase» (49 de 85 el 18/11) o de respaldo; ahora arranca al acabar la cola y cuenta como en frase.
     */
    @Test
    fun rapidDjExitRightAfterAChorusWaitsForTheTailAndStaysOnPhrase() {
        val chorusA = SongSection(70_000L, 98_000L, 1.0)
        val exit = 98_250L
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 10_000L,
            outgoingAudio = metrics("a"), incomingAudio = metrics("b"),
            outgoingVocals = listOf(chorusA), djMode = DjMode.Adhd,
            outgoingAdhd = AdhdSegment(20_000L, 102_250L, 82_250L, exit, 4_000L, 180_000L, phrasePointsMs = listOf(exit)),
            incomingAdhd = AdhdSegment(30_000L, 115_000L, 85_000L, 111_000L, 4_000L, 180_000L, phrasePointsMs = listOf(30_000L)),
        )
        val note = spec.tempoNote
        assertTrue("$note · ${spec.style}", note.contains("en frase"))
        assertTrue("arranque ${spec.cueOutStartMs}", spec.cueOutStartMs in 98_600L..99_600L)
        assertTrue(ChorusSafety.allows(spec, listOf(chorusA), emptyList()))
        assertTrue(spec.transitionDurationMs > 0L)
    }

    /** La nota de tempo dice lo que se aplica; la diferencia de la pareja va aparte («estira 2,6 %» con el puente a 1×). */
    @Test
    fun tempoNoteReportsTheAppliedStretch() {
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 10_000L,
            outgoingAudio = metrics("a", kickOnBeat = 0.05), incomingAudio = metrics("b", bpm = 123.0, kickOnBeat = 0.05),
        )
        assertEquals(1.0, spec.tempoMatchRatio, 1e-9)
        assertTrue(spec.tempoNote, spec.tempoNote.startsWith("estira 0"))
        assertTrue(spec.tempoNote, spec.tempoNote.contains("pareja 2"))
    }

    /** Ninguna de las entradas añadidas (tras un estribillo de B) ni el ajuste al beat meten a B en un estribillo. */
    @Test
    fun djNormalNeverEntersInsideAnIncomingChorus() {
        val chorusesB = listOf(SongSection(9_900L, 24_000L, 1.0), SongSection(33_100L, 50_000L, 1.0))
        for (recent in listOf(emptyList(), DjBridgeStyle.entries.toList())) {
            val spec = DjBridgePlanner.plan(
                outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 10_000L,
                outgoingAudio = metrics("a"), incomingAudio = metrics("b"),
                incomingVocals = chorusesB, recentStyles = recent,
            )
            assertTrue("${spec.style} entra en ${spec.entryInMs}", entryOutsideChoruses(spec, chorusesB))
            assertTrue(spec.transitionDurationMs > 0L)
        }
    }

    /**
     * Fundido suave a tempo (opción del renderizador): con el cuadre cerrado por armonía pero bombo al pulso en las dos,
     * el fundido entra a tempo y en fase. La reserva de B es la del render (cruce estirado + vuelta, o cruce + fase).
     */
    @Test
    fun smartFadeLocksTempoOnlyWithKicksAndWithinTheLimit() {
        // Armonía 0,39 (< 0,45): cuadre cerrado; el eco no tiene premio por choque fuerte (≥ 0,36).
        fun plan(bpmB: Double, kick: Double = 0.85, vocalsB: List<SongSection> = emptyList()) = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 10_000L,
            outgoingAudio = metrics("a", kickOnBeat = kick, camelot = "8A", keyConfidence = 0.4),
            incomingAudio = metrics("b", bpm = bpmB, kickOnBeat = kick, camelot = "2A", keyConfidence = 0.4),
            incomingVocals = vocalsB,
        )
        for (bpmB in listOf(120.0, 123.0)) {
            val spec = plan(bpmB)
            assertEquals("${spec.reason} · ${spec.tempoNote}", DjBridgeStyle.SmartFade, spec.style)
            assertEquals(spec.tempoNote, true, spec.beatAligned)
            assertEquals(DjHarmonic.bpmRelation(120.0, bpmB).ratioOutOverIn, spec.tempoMatchRatio, 1e-9)
            assertTrue(spec.tempoNote, spec.tempoNote.contains("fundido a tempo"))
            val used = DjTempoMatch.lockedCrossSourceMs(spec.transitionDurationMs, 120.0, spec.tempoMatchRatio)
            assertEquals(spec.entryInMs + used + spec.postRollMs, spec.dryBResumeMs)
        }
        // Sin bombo al pulso: a 1× y sin fase.
        val noKick = plan(123.0, kick = 0.05)
        assertEquals(DjBridgeStyle.SmartFade, noKick.style)
        assertEquals(false, noKick.beatAligned)
        assertEquals(1.0, noKick.tempoMatchRatio, 1e-9)
        assertEquals(noKick.entryInMs + noKick.transitionDurationMs + noKick.postRollMs, noKick.dryBResumeMs)
        // 7 % de estiramiento: vale instrumental (8 %), no con B cantando todo el cruce (6 %).
        val bpm7 = 120.0 / 1.07
        val instrumental = plan(bpm7)
        if (instrumental.style == DjBridgeStyle.SmartFade) assertEquals(instrumental.tempoNote, true, instrumental.beatAligned)
        val sung = plan(bpm7, vocalsB = listOf(SongSection(0L, 60_000L, 0.0)))
        assertTrue(sung.tempoNote, sung.style != DjBridgeStyle.SmartFade || sung.beatAligned == false)
        assertTrue(sung.tempoNote, !DjTempoMatch.isStretched(sung.tempoMatchRatio) || DjTempoMatch.stretchPct(sung.tempoMatchRatio) <= 6.0)
    }

    /** Filtro sin alinear: la reserva de B es lo que gasta el render (entra al 48 % del cruce + 1,8 s de cola). */
    @Test
    fun unalignedFilterEchoReservesWhatTheRenderUses() {
        var seen = 0
        for (bpmB in listOf(96.0, 100.0, 140.0, 150.0)) {
            val spec = DjBridgePlanner.plan(
                outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 10_000L,
                outgoingAudio = metrics("a"), incomingAudio = metrics("b", bpm = bpmB), tandaChanged = true,
            )
            if (spec.style != DjBridgeStyle.FilterEcho || spec.beatAligned != false) continue
            seen++
            val used = Math.round(spec.transitionDurationMs * 0.52) + 1_800L
            assertEquals(spec.tempoNote, spec.entryInMs + used + spec.postRollMs, spec.dryBResumeMs)
        }
        assertTrue("ningún filtro sin alinear en el barrido", seen > 0)
    }
}
