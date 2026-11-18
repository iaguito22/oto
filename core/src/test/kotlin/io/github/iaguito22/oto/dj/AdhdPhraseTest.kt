package io.github.iaguito22.oto.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Inicios y finales del DJ rápido en límites de frase: para escucharlos y para las mezclas. */
class AdhdPhraseTest {
    private val beat = 500L // 120 BPM
    private val bar = 2_000L
    private val dur = 210_000L

    /** Compases medidos de 2 s desde [offset] (downbeat del análisis), con algo más de voz donde se canta. */
    private fun audio(offset: Long = 0L, sections: List<SongSection> = emptyList(), bpm: Double? = 120.0, confidence: Double = 0.85): AudioMetrics {
        val bars = ArrayList<BarFeature>()
        if (offset > 0L) bars += BarFeature(0L, offset, vocalRatio = 0.1)
        var t = offset
        while (t < dur) {
            val end = minOf(dur, t + bar)
            val sung = sections.any { t >= it.startMs && end <= it.endMs }
            bars += BarFeature(t, end, localBpm = 120.0, lufs = -11.0, drumEnergy = 0.6, kickSteady = 0.8, vocalRatio = if (sung) 0.5 else 0.1, kickOnBeat = 0.8)
            t = end
        }
        return AudioMetrics(
            trackId = "x", bpm = bpm, bpmConfidence = confidence, beatPhaseMs = offset % beat, downbeatPhaseMs = offset,
            durationMs = dur, analyzedMs = dur, bars = if (bpm == null) emptyList() else bars,
            beatGrid = (0 until (dur / 8_000L).toInt()).map { it * 8_000L + offset % beat },
            firstSoundMs = 0L, lastSoundMs = dur, introEndMs = 0L, outroStartMs = dur,
        )
    }

    /**
     * Estrofa, estribillo, estrofa, estribillo, puente, estribillo. Cada verso entra [pickup] ms antes de su compás
     * (anacrusa) y dura casi dos compases.
     */
    private fun song(pickup: Long): List<SongSection> {
        fun block(firstBar: Int, lines: Int, chorus: Boolean): SongSection {
            val starts = (0 until lines).map { (firstBar + it * 2) * bar - pickup }
            return SongSection(starts.first(), starts.last() + 3_400L, if (chorus) 1.0 else 0.0, starts)
        }
        return listOf(
            block(8, 8, false),   // estrofa 1: 16–48 s
            block(26, 8, true),   // estribillo: 52–84 s
            block(46, 8, false),  // estrofa 2: 92–124 s
            block(64, 8, true),   // estribillo: 128–160 s
            block(84, 4, false),  // puente
            block(94, 4, true),
        )
    }

    private fun onBar(ms: Long, offset: Long = 0L) = abs(((ms - offset) % bar + bar) % bar) <= 1L || abs(((ms - offset) % bar + bar) % bar - bar) <= 1L

    @Test
    fun startsAFewBarsBeforeTheVerseAndLeavesOnTheBarAfterTheChorus() {
        for (pickup in listOf(250L, 1_000L, 1_500L)) {
            val sections = song(pickup)
            val seg = AdhdPlanner.segment(dur, "t", 4_000L, sections = sections, audio = audio(sections = sections))
            assertNotNull(seg); seg!!
            assertTrue("inicio en compás (anacrusa $pickup): ${seg.startMs}", onBar(seg.startMs))
            assertTrue(seg.startOnPhrase && seg.exitOnPhrase)
            // La voz llega con la canción ya arriba: al menos la mezcla antes.
            val voice = sections.first { it.startMs >= seg.startMs }
            assertTrue("margen ${voice.startMs - seg.startMs}", voice.startMs - seg.startMs >= 4_300L)
            // Ninguna voz de antes dentro de la entrada.
            assertTrue(sections.none { seg.startMs > it.startMs && seg.startMs < it.endMs })
            // La sección a la que entra es su compás (la voz entra en anacrusa justo antes).
            assertEquals(voice.startMs + pickup, seg.entrySectionMs)
            // Lleva un estribillo entero y sale en el compás que sigue a la última palabra de uno.
            val chorus = sections.filter { it.isChorus }.first { it.startMs >= seg.startMs }
            assertTrue(chorus.endMs < seg.fadeOutStartMs)
            assertTrue(onBar(seg.fadeOutStartMs))
            val lastSung = sections.last { it.endMs <= seg.fadeOutStartMs }
            assertTrue(lastSung.isChorus)
            assertTrue("sale en el compás siguiente", seg.fadeOutStartMs - lastSung.endMs in 200L..(bar + 200L))
            // El fundido no se come la voz que viene.
            val next = sections.firstOrNull { it.startMs > lastSung.startMs + 1 }
            if (next != null) assertTrue(seg.endMs <= next.startMs)
            assertTrue(seg.clipDurationMs in AdhdPlanner.MIN_CLIP_MS..AdhdPlanner.MAX_CLIP_MS)
        }
    }

    @Test
    fun versesAtRandomDistancesFromTheBarDoNotTrustTheGrid() {
        val sections = song(250L)
        val consistent = PhraseMap.of(dur, sections, audio(sections = sections))
        assertTrue(consistent.gridTrusted)
        // Los versos caen a 0, 1, 2 y 3 beats de su compás: el compás medido no cuadra con la voz.
        val messy = sections.map { s -> s.copy(lineStartsMs = s.lineStartsMs.mapIndexed { i, l -> l + (i % 4) * beat }) }
        val map = PhraseMap.of(dur, messy, audio(sections = messy))
        assertFalse(map.gridTrusted)
        assertTrue(map.points.none { it.kind == PhraseKind.Grid })
        // Las entradas relativas a cada sección siguen ahí.
        assertTrue(map.points.any { it.kind == PhraseKind.LeadIn })
    }

    @Test
    fun aDownbeatOneBeatOffStillGivesAConsistentLeadIn() {
        val sections = song(250L)
        val a = audio(offset = beat, sections = sections)
        val seg = AdhdPlanner.segment(dur, "t", 4_000L, sections = sections, audio = a)!!
        // Todo cuadra con los compases medidos (aunque vayan un beat movidos): entrada y salida en uno de ellos.
        assertTrue(onBar(seg.startMs, beat) && onBar(seg.fadeOutStartMs, beat))
        val voice = sections.first { it.startMs >= seg.startMs }
        assertTrue(voice.startMs - seg.startMs >= 4_300L)
    }

    @Test
    fun withoutPulseOnlyTheLyricsAnchorAndTheVoiceStillGetsItsLeadIn() {
        val sections = song(250L)
        val map = PhraseMap.of(dur, sections, audio(bpm = null))
        assertFalse(map.gridTrusted)
        assertTrue(map.points.none { it.kind == PhraseKind.Grid })
        val seg = AdhdPlanner.segment(dur, "t", 4_000L, sections = sections, audio = audio(bpm = null))!!
        val voice = sections.first { it.startMs >= seg.startMs }
        assertTrue(voice.startMs - seg.startMs >= 4_000L)
        assertTrue(sections.filter { it.isChorus }.any { it.startMs >= seg.startMs && it.endMs <= seg.fadeOutStartMs })
    }

    @Test
    fun lyricsThatDoNotMatchTheAudioAreNotUsedAsPhraseAnchors() {
        val sections = song(250L)
        // Voz medida justo donde la letra dice silencio (los huecos entre bloques): la letra va desplazada.
        val bounds = listOf(0L) + sections.flatMap { listOf(it.startMs, it.endMs) } + dur
        val gaps = bounds.chunked(2).filter { it.size == 2 }.map { (s0, e0) -> SongSection(s0, e0, 0.0) }
        val a = audio(sections = gaps)
        assertFalse(PhraseMap.lyricsMatchAudio(sections, a, dur))
        assertTrue(PhraseMap.of(dur, sections, a).points.none { it.kind == PhraseKind.SectionStart })
        // Y tampoco elige el trozo: sale del análisis.
        val seg = AdhdPlanner.segment(dur, "t", 4_000L, sections = sections, audio = a)!!
        assertFalse(seg.fromLyrics)
        assertTrue(seg.fromAudio)
    }

    @Test
    fun aBetterAnalysisImprovesEvenALyricsSegment() {
        val sections = song(250L)
        val partial = audio(sections = sections).copy(analyzedMs = 60_000L)
        val seg = AdhdPlanner.segment(dur, "t", 4_000L, sections = sections, audio = partial)!!
        assertTrue(seg.fromLyrics)
        assertEquals(60_000L, seg.audioCoverageMs)
        assertFalse(seg.improvesWith(sections, partial))
        assertFalse(seg.improvesWith(sections, partial.copy(analyzedMs = 70_000L)))
        assertTrue(seg.improvesWith(sections, audio(sections = sections)))
        val whole = AdhdPlanner.segment(dur, "t", 4_000L, sections = sections, audio = audio(sections = sections))!!
        assertFalse(whole.improvesWith(sections, audio(sections = sections)))
        // Sin letra usada, la letra que llega sí mejora.
        val blind = AdhdPlanner.segment(dur, "t", 4_000L, audio = audio(sections = sections))!!
        assertTrue(blind.improvesWith(sections, audio(sections = sections)))
    }

    @Test
    fun thePlayingSongOnlyMovesItsExitForward() {
        val sections = song(250L)
        val start = 40_000L
        val minFade = 115_000L
        val seg = AdhdPlanner.segment(dur, "t", 4_000L, sections = sections, audio = audio(sections = sections), fixedStartMs = start, minFadeStartMs = minFade)!!
        assertEquals(start, seg.startMs)
        assertTrue(seg.fadeOutStartMs >= minFade)
        assertTrue(seg.exitOnPhrase)
        assertTrue(sections.none { seg.fadeOutStartMs > it.startMs && seg.fadeOutStartMs < it.endMs })
    }

    @Test
    fun aForcedExitWaitsForTheNextPhrase() {
        val seg = AdhdSegment(20_000L, 100_000L, 80_000L, 96_000L, 4_000L, dur, phrasePointsMs = listOf(96_000L, 104_000L, 112_000L))
        assertEquals(104_000L, seg.phraseExitAt(100_500L))
        assertEquals(130_000L, seg.phraseExitAt(130_000L)) // nada cerca: no espera
        // Entrar tarde alarga hasta el primer límite que da el mínimo.
        val late = seg.startingAt(45_000L)
        assertTrue(late.endMs >= 45_000L + AdhdPlanner.MIN_CLIP_MS - late.mixOutDurationMs)
        assertEquals(112_000L, late.fadeOutStartMs)
    }

    @Test
    fun lateDataNeverPullsThePlayingExitEarlier() {
        val sections = song(250L)
        // Salida adivinada en 130 s; a los 60 s llega letra+análisis que preferirían salir antes.
        val old = AdhdSegment(40_000L, 134_000L, 94_000L, 130_000L, 4_000L, dur)
        val minFade = AdhdPlanner.liveMinFadeStartMs(old, positionMs = 60_000L)
        assertEquals(130_000L, minFade)
        val fresh = AdhdPlanner.segment(dur, "t", 4_000L, sections = sections, audio = audio(sections = sections), fixedStartMs = old.startMs, minFadeStartMs = minFade)
        if (fresh != null && AdhdPlanner.isLiveExitUpdate(old, fresh, 60_000L)) {
            assertTrue(fresh.fadeOutStartMs >= old.fadeOutStartMs && fresh.endMs >= old.endMs)
        }
        // Una salida más temprana (aunque esté en frase) se rechaza.
        val earlier = old.copy(endMs = 94_000L, clipDurationMs = 54_000L, fadeOutStartMs = 90_000L, exitOnPhrase = true)
        assertFalse(AdhdPlanner.isLiveExitUpdate(old, earlier, 60_000L))
        // Cerca del final: el margen de preparación manda sobre la salida vieja.
        assertEquals(130_000L + 5_000L, AdhdPlanner.liveMinFadeStartMs(old, positionMs = 115_000L))
    }

    @Test
    fun anAbandonedMixFallsBackToAForcedExitOnAPhrase() {
        // Final previsto 100 s (fin de la mezcla que no llegó); la siguiente frase está en 104 s.
        val seg = AdhdSegment(20_000L, 100_000L, 80_000L, 96_000L, 4_000L, dur, phrasePointsMs = listOf(96_000L, 104_000L, 112_000L))
        assertEquals(104_000L, seg.forcedExitTargetMs(100_000L, emptyList(), guardMs = 300L))
        assertFalse(seg.forcedExitDue(100_200L, 100_000L, emptyList(), guardMs = 300L))
        assertTrue(seg.forcedExitDue(104_100L, 100_000L, emptyList(), guardMs = 300L))
        // El objetivo es fijo: pasado 104 s no se va a 112 s.
        assertTrue(seg.forcedExitDue(108_000L, 100_000L, emptyList(), guardMs = 300L))
        // Un estribillo justo después: la frase de 104 s cae dentro, así que sale antes de él (100 s), no dentro.
        val chorus = listOf(SongSection(101_000L, 118_000L, 1.0))
        assertEquals(100_000L, seg.forcedExitTargetMs(100_000L, chorus, guardMs = 300L))
        // Si ya se pasó (p. ej. tras buscar) y está dentro del estribillo, espera a que acabe.
        assertFalse(seg.forcedExitDue(110_000L, 100_000L, chorus, guardMs = 300L))
        // Final previsto dentro del estribillo: el objetivo es tras él.
        assertTrue(seg.forcedExitTargetMs(105_000L, chorus, guardMs = 300L) >= 118_000L)
    }

    @Test
    fun aLongFinalChorusCanEndTheExcerptInAVerifiedBreath() {
        val duration = 180_000L
        val breaths = listOf(100_000L..101_700L, 110_000L..111_500L)
        val sections = listOf(SongSection(0L, duration, 1.0, breathsMs = breaths))
        val segment = AdhdPlanner.segment(duration, "final-chorus", sections = sections, fixedStartMs = 15_000L)!!
        assertEquals(0L, segment.mixOutDurationMs)
        assertTrue(segment.endMs < 112_000L)
        assertTrue(segment.allowsForcedExitAt(segment.endMs, sections, guardMs = 400L, duckBefore = true))
        assertFalse(segment.allowsForcedExitAt(101_900L, sections, guardMs = 400L, duckBefore = true))
        assertEquals(100_400L, segment.forcedExitTargetMs(100_200L, sections, guardMs = 400L, duckBefore = true))
        assertEquals(110_400L, segment.forcedExitTargetMs(105_000L, sections, guardMs = 400L, duckBefore = true))
        assertEquals(duration, segment.forcedExitTargetMs(112_000L, sections, guardMs = 400L, duckBefore = true))
    }

    @Test
    fun aChorusWithoutMeasuredBreathsCanLeaveBeforeTheNextLineInsteadOfAtTheEnd() {
        val duration = 109_761L
        val lines = listOf(5_000L, 78_000L, 92_000L, 103_000L, 109_000L)
        val sections = listOf(SongSection(5_000L, duration, 1.0, lineStartsMs = lines))
        val current = AdhdSegment(19_769L, duration, duration - 19_769L, duration, 0L, duration)
        val segment = AdhdPlanner.segment(duration, "line-timed-chorus", crossfadeMs = 10_000L, sections = sections,
            fixedStartMs = current.startMs, minFadeStartMs = AdhdPlanner.liveMinFadeStartMs(current, 20_000L))!!
        val cut = segment.forcedPhraseCutMs!!
        assertEquals(cut, segment.endMs)
        assertEquals(0L, segment.mixOutDurationMs)
        assertTrue(cut + AdhdPlanner.PHRASE_CUT_LEAD_MS in lines)
        assertTrue(cut <= duration - AdhdPlanner.FALLBACK_END_MARGIN_MS)
        assertEquals(cut, AdhdPlanner.liveExitUpdate(current, segment, 20_000L)?.endMs)
        assertEquals(cut, segment.forcedExitTargetMs(cut - 400L, sections, guardMs = 400L, duckBefore = true))
        assertTrue(segment.allowsForcedExitAt(cut, sections, guardMs = 400L, duckBefore = true))
        assertFalse(segment.allowsForcedExitAt(cut - 1_000L, sections, guardMs = 400L, duckBefore = true))
        val afterSeek = cut + 1_000L
        assertTrue(segment.forcedExitTargetMs(afterSeek, sections, guardMs = 400L, duckBefore = true) in afterSeek until duration)

        val provisional = current.copy(endMs = cut + 2_000L, fadeOutStartMs = cut - 3_000L, mixOutDurationMs = 5_000L)
        assertEquals(cut, AdhdPlanner.liveExitUpdate(provisional, segment, 20_000L)?.endMs)
    }

    @Test
    fun aPendingNaturalEndCanAdoptAnEarlierSafeExitWithTimeToPrepare() {
        val current = AdhdSegment(42_000L, dur, dur - 42_000L, dur, 0L, dur, entrySectionMs = 44_000L)
        val fresh = current.copy(endMs = 125_000L, fadeOutStartMs = 121_000L, mixOutDurationMs = 4_000L)
        assertEquals(95_000L, AdhdPlanner.liveMinFadeStartMs(current, 75_000L))
        assertEquals(44_000L, AdhdPlanner.liveExitUpdate(current, fresh, 75_000L)?.entrySectionMs)
        assertFalse(AdhdPlanner.isLiveExitUpdate(current, fresh, 110_000L))
        assertFalse(AdhdPlanner.isLiveExitUpdate(current.copy(endMs = 135_000L), fresh, 75_000L))
    }

    @Test
    fun anEarlierCleanFallbackSurvivesASlightlyDifferentEntry() {
        val planned = AdhdSegment(20_000L, 77_000L, 57_000L, 74_000L, 3_000L, dur, fallbackExit = true)
        val actual = planned.startingAt(23_000L)
        assertEquals(77_000L, actual.endMs)
        assertEquals(74_000L, actual.fadeOutStartMs)
        assertTrue(actual.fallbackExit)
    }

    @Test
    fun djRapidoMixesStartOnAPhraseAndNeverInsideTheVerseOfA() {
        val sectionsA = song(250L)
        val sectionsB = song(500L)
        val a = audio(sections = sectionsA)
        val b = audio(sections = sectionsB)
        val segA = AdhdPlanner.segment(dur, "a", 8_000L, sections = sectionsA, audio = a)!!
        val segB = AdhdPlanner.segment(dur, "b", 8_000L, sections = sectionsB, audio = b)!!
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = dur, incomingDurationMs = dur, configuredFadeMs = 8_000L,
            outgoingAudio = a, incomingAudio = b, outgoingVocals = sectionsA, incomingVocals = sectionsB,
            outgoingAdhd = segA, incomingAdhd = segB, djMode = DjMode.Adhd,
        )
        assertTrue(spec.transitionDurationMs > 0L)
        assertTrue("A: ${spec.cueOutStartMs} fuera de los bloques", sectionsA.none { spec.cueOutStartMs > it.startMs + 250 && spec.cueOutStartMs < it.endMs - 250 })
        val tol = 300L
        val phraseA = segA.phrasePointsMs + segA.fadeOutStartMs
        val ref = if (spec.style == DjBridgeStyle.EnergyDropCut) spec.cueOutEndMs else spec.cueOutStartMs
        assertTrue("A en frase: $ref (${spec.style})", spec.style == DjBridgeStyle.EnergyDropCut || phraseA.any { abs(it - ref) <= tol })
        assertTrue("B en frase: ${spec.entryInMs}", (segB.phrasePointsMs + segB.startMs).any { abs(it - spec.entryInMs) <= tol })
        assertTrue(sectionsB.none { spec.entryInMs > it.startMs + 250 && spec.entryInMs < it.endMs - 250 })
        assertTrue(spec.tempoNote.orEmpty(), spec.tempoNote.orEmpty().contains("en frase"))
    }
}
