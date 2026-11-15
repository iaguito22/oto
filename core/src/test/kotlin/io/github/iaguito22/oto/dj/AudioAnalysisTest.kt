package io.github.iaguito22.oto.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

class AudioAnalysisTest {
    private fun synthKickTrackPcm16(
        bpm: Double,
        durationSec: Double = 36.0,
        sampleRate: Int = 22_050,
        introSilenceSec: Double = 0.0,
        dropAtSec: Double = 0.0,
        outroSilenceSec: Double = 0.0,
        amplitude: Double = 0.72,
    ): ShortArray {
        val total = (durationSec * sampleRate).roundToInt()
        val out = ShortArray(total)
        val beatPeriodSec = 60.0 / bpm
        val activeEnd = durationSec - outroSilenceSec

        for (i in 0 until total) {
            val t = i.toDouble() / sampleRate
            if (t < introSilenceSec || t > activeEnd) {
                out[i] = 0
                continue
            }
            val posInBeat = (t - introSilenceSec) % beatPeriodSec
            val beatIdx = ((t - introSilenceSec) / beatPeriodSec).toInt()
            val accent = if (beatIdx % 4 == 0) 1.0 else 0.72
            // Bombo sintético (55–95 Hz) + armónico ligero tras el drop.
            val env = exp(-posInBeat / 0.055)
            val kickFreq = 58.0 + 45.0 * exp(-posInBeat / 0.020)
            val kick = sin(2.0 * PI * kickFreq * posInBeat) * env * accent
            val energyScale = if (t < dropAtSec) 0.25 else 1.0
            val pad = if (t >= dropAtSec) 0.14 * sin(2.0 * PI * 220.0 * t) else 0.04 * sin(2.0 * PI * 220.0 * t)
            val sample = ((kick * amplitude * energyScale + pad * amplitude) * 32767.0)
                .roundToInt()
                .coerceIn(-32768, 32767)
            out[i] = sample.toShort()
        }
        return out
    }

    @Test
    fun detectsBpmDownbeatAndIntroDropOutroFromPcm16() {
        val pcm120 = synthKickTrackPcm16(
            bpm = 120.0,
            durationSec = 56.0,
            introSilenceSec = 3.0,
            dropAtSec = 14.0,
            outroSilenceSec = 4.0,
            amplitude = 0.80,
        )
        val m120 = AudioAnalyzer.analyzePcm16("t120", pcm120, sampleRate = 22_050)
        assertNotNull(m120.bpm)
        assertTrue("Expected ~120 BPM, got ${m120.bpm}", abs(m120.bpm!! - 120.0) <= 2.5)
        assertTrue("Expected decent confidence, got ${m120.bpmConfidence}", m120.bpmConfidence >= 0.30)
        assertTrue("Intro end (${m120.introEndMs}) should skip silent intro (~3000ms)", m120.introEndMs in 2_400L..4_200L)
        assertTrue("Drop (${m120.dropMs}) should detect main entrance near ~14s", m120.dropMs in 11_500L..16_500L)
        assertTrue("Outro (${m120.outroStartMs}) should detect trailing silence before 56s", m120.outroStartMs in 50_500L..53_500L)

        // Snap a downbeat (cada 2000 ms a 120 BPM).
        val snapped = m120.snapBeatMs(targetMs = 15_850L, minMs = 14_500L, maxMs = 17_500L, preferDownbeat = true)
        assertTrue("Snapped ms ($snapped) should stay inside bounds", snapped in 14_500L..17_500L)

        val pcm90 = synthKickTrackPcm16(bpm = 90.0, durationSec = 34.0, amplitude = 0.25)
        val m90 = AudioAnalyzer.analyzePcm16("t90", pcm90, sampleRate = 22_050)
        assertNotNull(m90.bpm)
        assertTrue("Expected ~90 BPM, got ${m90.bpm}", abs(m90.bpm!! - 90.0) <= 2.5)
        assertTrue("Loud 120 BPM track should have higher RMS than quiet 90 BPM track", m120.rmsDb > m90.rmsDb + 4.0)
        assertTrue("Loud 120 BPM vibe should score higher energy", m120.toVibeScore().energy > m90.toVibeScore().energy)
    }

    @Test
    fun analysisCachePersistsAndLoadsByTrackId() {
        val dir = Files.createTempDirectory("oto-audio-cache").toFile()
        try {
            val file = File(dir, "audio-analysis.json")
            val cache1 = AudioAnalysisCache(file)
            val m = AudioMetrics(
                trackId = "track-xyz",
                bpm = 124.0,
                bpmConfidence = 0.85,
                beatPhaseMs = 120L,
                downbeatPhaseMs = 600L,
                rmsDb = -10.5,
                dynamicRangeDb = 6.5,
                beatStrength = 0.78,
                introEndMs = 2_400L,
                dropMs = 28_000L,
                outroStartMs = 175_000L,
                durationMs = 185_000L,
            )
            cache1.put(m)
            cache1.save()

            val cache2 = AudioAnalysisCache(file)
            val loaded = cache2.get("track-xyz")
            assertNotNull(loaded)
            assertEquals(124.0, loaded!!.bpm!!, 0.01)
            assertEquals(-10.5, loaded.rmsDb, 0.01)
            assertEquals(28_000L, loaded.dropMs)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun adhdPlannerUsesAudioMetricsWhenLyricsSectionsAreAbsent() {
        val audio = AudioMetrics(
            trackId = "no-lyrics-1",
            bpm = 124.0,
            bpmConfidence = 0.82,
            beatPhaseMs = 0L,
            downbeatPhaseMs = 0L,
            rmsDb = -9.0,
            dynamicRangeDb = 7.0,
            beatStrength = 0.80,
            introEndMs = 4_000L,
            dropMs = 36_000L,
            outroStartMs = 190_000L,
            durationMs = 210_000L,
        )
        val seg = AdhdPlanner.segment(
            durationMs = 210_000L,
            trackId = "no-lyrics-1",
            mood = DjMood.Energia,
            sections = emptyList(),
            audio = audio,
        )
        assertNotNull(seg)
        assertTrue("Segment should be flagged as fromAudio when lyrics missing", seg!!.fromAudio)
        assertTrue("Start (${seg.startMs}) should anchor near drop (36s), avoiding silent intro", seg.startMs in 30_000L..42_000L)
        assertTrue("End (${seg.endMs}) must stay before outroStartMs (190s)", seg.endMs <= 190_000L)
    }

    @Test
    fun transitionPlannerSelectsBeatMatchVsEchoOutFilterSweep() {
        val grid = (0L..200_000L step 8_000L).toList()
        val m122 = AudioMetrics("a", bpm = 122.0, bpmConfidence = 0.9, beatPhaseMs = 0L, downbeatPhaseMs = 0L, rmsDb = -10.0, introEndMs = 1_500L, outroStartMs = 190_000L, durationMs = 200_000L, beatGrid = grid)
        val m126 = AudioMetrics("b", bpm = 126.0, bpmConfidence = 0.9, beatPhaseMs = 0L, downbeatPhaseMs = 0L, rmsDb = -14.0, introEndMs = 3_200L, outroStartMs = 195_000L, durationMs = 200_000L, beatGrid = grid)
        val m84 = AudioMetrics("c", bpm = 84.0, bpmConfidence = 0.9, beatPhaseMs = 0L, downbeatPhaseMs = 0L, rmsDb = -12.0, introEndMs = 1_000L, outroStartMs = 190_000L, durationMs = 200_000L, beatGrid = grid)

        // ±3.3% BPM y mismo género -> mezcla larga con tempo-nudge y bass-swap.
        val longMix = DjTransitionPlanner.plan(
            outgoingDurationMs = 200_000L,
            configuredFadeMs = 6_000L,
            outgoingAudio = m122,
            incomingAudio = m126,
            outgoingGenre = "Electronica",
            incomingGenre = "Electronica",
            tandaChanged = false,
        )
        assertEquals(DjTransitionStyle.BeatMatchLongMix, longMix.style)
        assertTrue(longMix.useBassSwap)
        assertFalse(longMix.useHighPassSweep)
        assertTrue(longMix.phaseLock)
        assertEquals("La duración de Ajustes se respeta", 6_000L, longMix.fadeDurationMs)
        assertTrue("Tempo nudge should approach outgoing/incoming ratio", longMix.incomingTempoNudge in 0.94..1.00)
        assertEquals("La saliente se pone al tempo de la entrante", 126.0 / 122.0, longMix.outgoingSpeed, 0.001)
        assertTrue("Incoming start should skip intro silence", longMix.incomingStartMs >= 3_200L)
        assertTrue("Gain match should boost quieter incoming track", longMix.incomingGainMatch > 1.05f)

        // Con los beats cuadrados, la saliente repite su último compás desde un beat (y el fundido empieza ahí).
        assertEquals(4, longMix.loopBeats)
        assertEquals(longMix.loopStartMs, longMix.outgoingFadeStartMs)
        val beat122 = 60_000.0 / 122.0
        val offBeat = ((longMix.loopStartMs % 8_000L) / beat122).let { kotlin.math.abs(it - kotlin.math.round(it)) * beat122 }
        assertTrue("El bucle empieza en un beat ($offBeat ms fuera)", offBeat <= 1.0)
        // (La rejilla de prueba no cae en beats: el periodo local sale algo distinto, pero a menos del 1 %.)
        assertEquals(beat122, longMix.loopBeatMs, beat122 * 0.01)

        // >10% BPM con el pulso de la saliente claro -> bucle con roll y filtro, sin eco.
        val rolled = DjTransitionPlanner.plan(
            outgoingDurationMs = 200_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = m122,
            incomingAudio = m84,
            outgoingGenre = "Electronica",
            incomingGenre = "Electronica",
        )
        assertEquals(DjTransitionStyle.LoopRollOut, rolled.style)
        assertTrue(rolled.useHighPassSweep)
        assertEquals(0f, rolled.echoOutWet)
        assertEquals(4, rolled.loopBeats)
        assertEquals("Ya no se acorta a ~3 s", 8_000L, rolled.fadeDurationMs)

        // >10% BPM sin pulso claro en la saliente -> filtro + eco a tempo de la otra no; eco solo con pulso fiable.
        val echoBpm = DjTransitionPlanner.plan(
            outgoingDurationMs = 200_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = m122.copy(bpmConfidence = 0.5, beatGrid = emptyList()),
            incomingAudio = m84,
            outgoingGenre = "Electronica",
            incomingGenre = "Electronica",
        )
        assertEquals(DjTransitionStyle.EchoOutFilterSweep, echoBpm.style)
        assertTrue(echoBpm.useHighPassSweep)
        assertTrue(echoBpm.echoOutWet > 0f)
        assertEquals(0, echoBpm.loopBeats)
        // Eco a tempo exacto: un beat de 122 BPM (492 ms), no un valor recortado.
        assertEquals(492L, echoBpm.echoDelayMs)

        // Pulso dudoso (sin rejilla): sin cuadrar beats ni eco, pero igual de larga.
        val doubtful = DjTransitionPlanner.plan(
            outgoingDurationMs = 200_000L,
            configuredFadeMs = 10_000L,
            outgoingAudio = m122.copy(beatGrid = emptyList()),
            incomingAudio = m126,
        )
        assertEquals(DjTransitionStyle.EchoOutFilterSweep, doubtful.style)
        assertEquals(0f, doubtful.echoOutWet)
        assertEquals(10_000L, doubtful.fadeDurationMs)

        // Salto de tanda con tempo parecido y pulso claro: se cuadran beats igual (como haría un DJ).
        val echoGenre = DjTransitionPlanner.plan(
            outgoingDurationMs = 200_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = m122,
            incomingAudio = m126,
            outgoingGenre = "Electronica",
            incomingGenre = "Rock",
            tandaChanged = true,
        )
        assertEquals(DjTransitionStyle.BeatMatchLongMix, echoGenre.style)
        assertEquals(8_000L, echoGenre.fadeDurationMs)
    }

    @Test
    fun lyricsMoveTheMixToTheInstrumentalTail() {
        // La voz acaba a 180 s de 200: con 10 s de fundido se empieza al acabar la voz y se alarga sobre la cola.
        val plan = DjTransitionPlanner.plan(
            outgoingDurationMs = 200_000L,
            configuredFadeMs = 10_000L,
            outgoingVocalEndMs = 180_000L,
        )
        assertEquals(180_600L, plan.outgoingFadeStartMs)
        assertEquals(19_400L, plan.fadeDurationMs)
    }

    @Test
    fun beatGridGivesLocalPhase() {
        // 120 BPM (500 ms) pero el tema se adelanta 40 ms a mitad: la rejilla local lo sigue.
        val m = AudioMetrics("g", bpm = 120.0, bpmConfidence = 0.9, beatGrid = listOf(100L, 8_100L, 16_140L, 24_140L))
        assertEquals(16_140.0, m.nearestBeatMs(16_200L)!!, 0.5)
        assertEquals(0.12, m.beatOffset(16_200L)!!, 0.001)
        assertEquals(-0.08, m.beatOffset(8_060L)!!, 0.001)
        assertTrue(m.beatReliable)
        assertFalse(m.copy(beatGrid = emptyList()).beatReliable)
    }

    @Test
    fun analyzerFindsTheBeatGridOfAClickTrack() {
        // Clic de bombo a 120 BPM empezando en 250 ms, 40 s, 22,05 kHz.
        val rate = 22_050
        val samples = ShortArray(rate * 40)
        var beat = 0.25
        while (beat < 40.0) {
            val s0 = (beat * rate).toInt()
            for (i in 0 until 1_500) {
                if (s0 + i >= samples.size) break
                val env = kotlin.math.exp(-i / 300.0)
                samples[s0 + i] = (20_000 * env * kotlin.math.sin(2 * Math.PI * 60.0 * i / rate)).toInt().toShort()
            }
            beat += 0.5
        }
        val m = AudioAnalyzer.analyzePcm16("click", samples, rate)
        assertNotNull(m.bpm)
        assertEquals(120.0, m.bpm!!, 0.5)
        assertTrue("Rejilla vacía", m.beatGrid.isNotEmpty())
        // Cada ancla cae sobre un golpe (múltiplo de 500 ms + 250), con el retraso propio del detector (< 40 ms).
        m.beatGrid.forEach { a ->
            val off = ((a - 250L) % 500L + 500L) % 500L
            val d = minOf(off, 500L - off)
            assertTrue("Ancla $a a $d ms del golpe", d <= 40L)
        }
    }

    @Test
    fun transitionsAndAdhdPickLowEnergyValleysInsteadOfChorusPeaks() {
        // Pista saliente (180s): estribillo final muy intenso (166..180s = 88) pero puente suave en 150..162s (= 22).
        val outCurve = MutableList(180) { sec ->
            when (sec) {
                in 0..3 -> 0
                in 150..162 -> 22
                in 163..179 -> 88
                else -> 58
            }
        }
        val outAudio = AudioMetrics(
            trackId = "out-intense-end",
            bpm = 122.0,
            bpmConfidence = 0.9,
            rmsDb = -9.5,
            introEndMs = 3_000L,
            dropMs = 30_000L,
            outroStartMs = 179_000L,
            durationMs = 180_000L,
            energyCurve = outCurve,
            energyCurveStepMs = 1_000L,
        )

        // Pista entrante (180s): respiración suave pre-drop en 20..28s (= 18), drop/estribillo en 30..85s (= 90),
        // puente relajado post-estribillo en 98..112s (= 20), segundo estribillo en 115..160s (= 92).
        val inCurve = MutableList(180) { sec ->
            when (sec) {
                in 0..3 -> 0
                in 20..28 -> 18
                in 30..85 -> 90
                in 98..112 -> 20
                in 115..165 -> 92
                else -> 50
            }
        }
        val inAudio = AudioMetrics(
            trackId = "in-valleys",
            bpm = 122.0,
            bpmConfidence = 0.9,
            rmsDb = -10.0,
            introEndMs = 3_000L,
            dropMs = 30_000L,
            outroStartMs = 176_000L,
            durationMs = 180_000L,
            energyCurve = inCurve,
            energyCurveStepMs = 1_000L,
        )

        // 1. Modo normal: saliente evita el estribillo final (163..179s) y cruza en el valle suave (150..162s).
        val normalPlan = DjTransitionPlanner.plan(
            outgoingDurationMs = 180_000L,
            configuredFadeMs = 6_000L,
            outgoingAudio = outAudio,
            incomingAudio = inAudio,
            outgoingGenre = "Pop",
            incomingGenre = "Pop",
        )
        assertTrue(
            "Outgoing fade start (${normalPlan.outgoingFadeStartMs}ms) should land in soft valley 150..162s, not intense end",
            normalPlan.outgoingFadeStartMs in 149_500L..157_500L,
        )

        // 2. Modo ADHD: entra en la respiración previa suave (20..26s, antes del drop en 30s)
        //    y sale en el valle post-estribillo (98..112s) en vez de encima del segundo estribillo (115s+).
        val adhdSeg = AdhdPlanner.segment(
            durationMs = 180_000L,
            trackId = "in-valleys",
            crossfadeMs = 5_000L,
            mood = DjMood.Auto,
            sections = emptyList(),
            audio = inAudio,
        )!!
        assertTrue(
            "ADHD start (${adhdSeg.startMs}ms) should enter in soft pre-drop breath (19..26s), before drop at 30s",
            adhdSeg.startMs in 19_000L..26_500L,
        )
        assertTrue(
            "ADHD fadeOut (${adhdSeg.fadeOutStartMs}ms) should exit in soft post-chorus valley (96..109s), not over 115s+ chorus",
            adhdSeg.fadeOutStartMs in 96_000L..109_500L,
        )
    }

    private fun steady(id: String, bpm: Double, dur: Long = 200_000L, curve: List<Int> = emptyList()) = AudioMetrics(
        id, bpm = bpm, bpmConfidence = 0.95, beatPhaseMs = 0L, downbeatPhaseMs = 0L, rmsDb = -10.0,
        introEndMs = 0L, outroStartMs = dur, durationMs = dur,
        energyCurve = curve, energyCurveStepMs = 1_000L,
        beatGrid = (0L..dur step 8_000L).toList(),
    )

    @Test
    fun theDjPicksTheLengthInBeats() {
        val plan = DjTransitionPlanner.plan(
            outgoingDurationMs = 200_000L,
            configuredFadeMs = 10_000L,
            outgoingAudio = steady("a", 120.0),
            incomingAudio = steady("b", 120.0),
            autoLength = true,
        )
        assertEquals(DjTransitionStyle.BeatMatchLongMix, plan.style)
        // 16 o 32 beats de 500 ms, y empieza en un tiempo fuerte (compás de 2 s).
        assertTrue("dura ${plan.fadeDurationMs}", plan.fadeDurationMs == 8_000L || plan.fadeDurationMs == 16_000L)
        assertEquals(0L, plan.outgoingFadeStartMs % 2_000L)
        assertEquals(plan.outgoingFadeStartMs, plan.loopStartMs)
        // Cerca del final: lo que queda tras la mezcla es poco.
        assertTrue("empieza en ${plan.outgoingFadeStartMs}", plan.outgoingFadeStartMs + plan.fadeDurationMs >= 190_000L)
    }

    @Test
    fun twoVoicesAreNotMixedTogether() {
        // La saliente canta hasta 176 s; la entrante, desde 3 s. El DJ espera a la cola instrumental (o repite un compás
        // sin voz) para que no se oigan las dos voces a la vez.
        val outVoice = listOf(SongSection(10_000L, 60_000L, 0.0), SongSection(70_000L, 120_000L, 1.0), SongSection(130_000L, 176_000L, 1.0))
        val inVoice = listOf(SongSection(3_000L, 40_000L, 0.0))
        val plan = DjTransitionPlanner.plan(
            outgoingDurationMs = 200_000L,
            configuredFadeMs = 10_000L,
            outgoingAudio = steady("a", 120.0),
            incomingAudio = steady("b", 120.0),
            outgoingVocals = outVoice,
            incomingVocals = inVoice,
            autoLength = true,
        )
        assertTrue("empieza en ${plan.outgoingFadeStartMs}, con la voz aún sonando", plan.outgoingFadeStartMs >= 176_000L)

        // Sin pulso que cuadrar (fundido con filtro): tampoco se pisan.
        val noBeat = DjTransitionPlanner.plan(
            outgoingDurationMs = 200_000L,
            configuredFadeMs = 10_000L,
            outgoingVocals = outVoice,
            incomingVocals = listOf(SongSection(0L, 40_000L, 0.0)),
            autoLength = true,
        )
        assertTrue("empieza en ${noBeat.outgoingFadeStartMs}", noBeat.outgoingFadeStartMs >= 172_000L)
    }

    @Test
    fun djRapidoLeavesWhenTheChorusEnds() {
        // Estribillo fuerte de 80 a 100 s; después baja. El trozo acaba en 104 s: la mezcla empieza al acabar el
        // estribillo, no en medio.
        val curve = List(200) { sec -> if (sec in 80..99) 88 else if (sec in 100..130) 30 else 55 }
        val out = steady("a", 120.0, curve = curve)
        val seg = AdhdSegment(startMs = 20_000L, endMs = 104_000L, clipDurationMs = 84_000L, fadeOutStartMs = 94_000L, mixOutDurationMs = 10_000L, fullDurationMs = 200_000L)
        val plan = DjTransitionPlanner.plan(
            outgoingDurationMs = 200_000L,
            configuredFadeMs = 10_000L,
            outgoingAudio = out,
            incomingAudio = steady("b", 120.0),
            outgoingAdhd = seg,
            autoLength = true,
        )
        assertTrue("empieza en ${plan.outgoingFadeStartMs}", plan.outgoingFadeStartMs in 99_000L..104_000L)
    }

    @Test
    fun audioAnalyzerRecoversBpmFromLaterChorusWhenMiddleWindowIsCalm() {
        val sr = 12_000
        val totalSec = 205
        val totalSamples = sr * totalSec
        val pcm = ShortArray(totalSamples)
        val calmEndSec = 122.0
        val beatPeriodSec = 60.0 / 128.0
        val omegaPad1 = 2.0 * kotlin.math.PI * 220.0
        val omegaPad2 = 2.0 * kotlin.math.PI * 330.0
        val omegaKick = 2.0 * kotlin.math.PI * 68.0
        val omegaClick = 2.0 * kotlin.math.PI * 440.0

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sr
            val sample = if (t < calmEndSec) {
                val pad = 2_400.0 * kotlin.math.sin(omegaPad1 * t) + 1_600.0 * kotlin.math.sin(omegaPad2 * t)
                pad
            } else {
                val chorusT = t - calmEndSec
                val localT = chorusT % beatPeriodSec
                val kick = kotlin.math.exp(-localT / 0.04) * kotlin.math.sin(omegaKick * localT) * 20_000.0
                val click = kotlin.math.exp(-localT / 0.008) * kotlin.math.sin(omegaClick * localT) * 7_500.0
                kick + click
            }
            pcm[i] = sample.toInt().coerceIn(-32_767, 32_767).toShort()
        }

        val metrics = AudioAnalyzer.analyzePcm16(
            trackId = "late-chorus-128",
            samples = pcm,
            sampleRate = sr,
            channels = 1,
        )
        val bpm = metrics.bpm
        assertNotNull("Expected late-chorus 128 BPM to be detected instead of null", bpm)
        val octDiff = minOf(
            kotlin.math.abs(bpm!! - 128.0),
            kotlin.math.abs(bpm * 2.0 - 128.0),
            kotlin.math.abs(bpm * 0.5 - 128.0),
        )
        assertTrue(
            "Detected BPM ($bpm) should match 128 BPM (octDiff=$octDiff)",
            octDiff < 3.0,
        )
        assertTrue("Expected non-empty beatGrid across chorus", metrics.beatGrid.size >= 8)
    }
}
