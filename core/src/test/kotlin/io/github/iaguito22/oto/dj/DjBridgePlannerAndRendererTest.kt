package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.lyrics.Line
import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.lyrics.Word
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class DjBridgePlannerAndRendererTest {
    private fun synthMusicalPcm16Stereo(
        durationSec: Double,
        bpm: Double = 120.0,
        leadSilenceSec: Double = 0.0,
        tailSilenceSec: Double = 0.0,
        sr: Int = 44_100,
    ): ShortArray {
        val frames = (durationSec * sr).toInt()
        val out = ShortArray(frames * 2)
        val beatPeriod = 60.0 / bpm
        val leadEnd = leadSilenceSec
        val tailStart = durationSec - tailSilenceSec
        for (f in 0 until frames) {
            val t = f.toDouble() / sr
            if (t < leadEnd || t > tailStart) continue
            val inBeat = (t % beatPeriod)
            val kick = if (inBeat < 0.14) sin(2.0 * PI * 62.0 * inBeat) * (1.0 - inBeat / 0.14) * 0.55 else 0.0
            val chord = (sin(2.0 * PI * 261.63 * t) + 0.7 * sin(2.0 * PI * 329.63 * t) + 0.6 * sin(2.0 * PI * 392.00 * t)) * 0.14
            val v = ((kick + chord).coerceIn(-0.98, 0.98) * 32767.0).toInt().toShort()
            out[f * 2] = v
            out[f * 2 + 1] = v
        }
        return out
    }

    private fun synthStereoFloat(durationSec: Double, bpm: Double, freqHz: Double, sr: Int = DjDsp.DJ_SAMPLE_RATE): FloatArray {
        val frames = (durationSec * sr).toInt()
        val out = FloatArray(frames * 2)
        val beatPeriod = 60.0 / bpm
        for (f in 0 until frames) {
            val t = f.toDouble() / sr
            val inBeat = t % beatPeriod
            val kick = if (inBeat < 0.11) (sin(2.0 * PI * 58.0 * inBeat) * (1.0 - inBeat / 0.11) * 0.50) else 0.0
            val tone = sin(2.0 * PI * freqHz * t) * 0.25
            val l = (kick + tone).toFloat()
            val r = (kick + tone * 0.92).toFloat()
            out[f * 2] = l
            out[f * 2 + 1] = r
        }
        return out
    }

    private fun metricsWithBars(
        id: String,
        bpm: Double,
        camelot: String = "8A",
        lufs: Double = -11.0,
        durMs: Long = 180_000L,
        harsh: Double = 0.12,
        highRms: Double = 0.18,
        lowRms: Double = 0.42,
        drumEnergy: Double = 0.70,
        kickSteady: Double = 0.85,
        overrideBars: Map<Long, BarFeature> = emptyMap(),
    ): AudioMetrics {
        val bars = ArrayList<BarFeature>()
        var t = 0L
        while (t < durMs) {
            val ov = overrideBars[t]
            bars += ov ?: BarFeature(
                startMs = t,
                endMs = (t + 2_000L).coerceAtMost(durMs),
                localBpm = bpm,
                lufs = lufs,
                lowRms = lowRms,
                midRms = 0.30,
                highRms = highRms,
                kickSteady = kickSteady,
                drumEnergy = drumEnergy,
                harshness = harsh,
                vocalRatio = 0.15,
            )
            t += 2_000L
        }
        return AudioMetrics(
            trackId = id,
            bpm = bpm,
            bpmConfidence = 0.90,
            beatPhaseMs = 0L,
            downbeatPhaseMs = 0L,
            rmsDb = lufs,
            integratedLufs = lufs,
            keyName = "A min",
            camelot = camelot,
            keyConfidence = 0.80,
            beatStrength = drumEnergy,
            firstSoundMs = 200L,
            lastSoundMs = durMs - 400L,
            outroStartMs = durMs - 400L,
            durationMs = durMs,
            beatGrid = listOf(0L, 60_000L, 120_000L, 170_000L),
            analyzedMs = durMs,
            bars = bars,
        )
    }

    @Test
    fun audioAnalyzerV4ProducesBarsLufsKeyAndRoundtripsJson() {
        val pcm = synthMusicalPcm16Stereo(durationSec = 24.0, bpm = 120.0, leadSilenceSec = 1.2, tailSilenceSec = 1.2)
        val m = AudioAnalyzer.analyzePcm16("t-v4", pcm, sampleRate = 44_100, channels = 2)
        assertTrue("should build Beat/Window BarFeature list", m.bars.isNotEmpty())
        assertTrue("expected valid integratedLufs, got ${m.integratedLufs}", m.integratedLufs > -28.0 && m.integratedLufs <= 0.0)
        assertTrue("expected Camelot key for C major triad", m.camelot.isNotBlank())
        assertTrue("expected intro silence detected around 1.2s, got ${m.firstSoundMs}", m.firstSoundMs >= 800L)
        assertTrue("expected tail silence before 24s, got ${m.lastSoundMs}", m.lastSoundMs <= 23_400L)

        val decoded = AudioMetrics.fromJson(m.toJson())
        assertNotNull(decoded)
        assertEquals(m.bars.size, decoded!!.bars.size)
        assertEquals(m.camelot, decoded.camelot)
        assertEquals(m.integratedLufs, decoded.integratedLufs, 0.2)
    }

    @Test
    fun djNormalMixesNearTheEdgesButNotGluedToThem() {
        val a = metricsWithBars("a", bpm = 122.0, camelot = "8A")
        val b = metricsWithBars("b", bpm = 122.0, camelot = "8A")
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = a,
            incomingAudio = b,
            djMode = DjMode.Normal,
            isDjSession = true,
            autoLength = true,
        )
        assertEquals(DjBridgeStyle.BeatMatchLongMix, spec.style)
        // Hasta 75 s antes del final de A y 45 s dentro de B (lo pidió Iago: no hace falta ir pegado a los bordes).
        assertTrue("DJ Normal exit must stay in last 75s of A, got ${spec.cueOutStartMs}", spec.cueOutStartMs >= 105_000L)
        assertTrue("DJ Normal entry must stay in first 45s of B, got ${spec.entryInMs}", spec.entryInMs <= 45_000L)
    }

    @Test
    fun halfAnalysedSongDoesNotPutTheMixBeforeTheWindow() {
        // Medido en el móvil: con solo los primeros 56 s analizados de 185, el plan ponía el cambio en 54 s
        // sonando ya en 108 s (DJ rápido, trozo 108–184 s), y no había mezcla hasta acabar el análisis.
        val full = metricsWithBars("a", bpm = 118.0, durMs = 185_000L)
        // Como sale del analizador con solo el principio: el «último sonido» es donde se acaba lo analizado.
        val partial = full.copy(
            bars = full.bars.filter { it.endMs <= 56_000L }, analyzedMs = 56_000L,
            lastSoundMs = 55_600L, outroStartMs = 55_600L,
        )
        val b = metricsWithBars("b", bpm = 125.0)
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 185_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = partial,
            incomingAudio = b,
            outgoingAdhd = AdhdSegment(
                startMs = 108_000L, endMs = 184_000L, clipDurationMs = 76_000L, fadeOutStartMs = 172_000L,
                mixOutDurationMs = 8_000L, fullDurationMs = 185_000L,
            ),
            djMode = DjMode.Adhd,
            isDjSession = true,
            autoLength = true,
        )
        assertTrue("el cambio debe quedar en el trozo que suena, salió en ${spec.cueOutStartMs}", spec.cueOutStartMs >= 150_000L)
    }

    @Test
    fun partialAnalysisDoesNotKnowTheEndYet() {
        val head = metricsWithBars("a", bpm = 118.0, durMs = 56_000L)
        val part = head.asPartOf(185_000L)
        assertEquals(185_000L, part.durationMs)
        assertEquals(56_000L, part.analyzedMs)
        assertEquals(185_000L, part.lastSoundMs)
        assertEquals(185_000L, part.outroStartMs)
        assertTrue(!part.coversWholeSong)
        // Entera: se queda como estaba.
        val whole = metricsWithBars("b", bpm = 118.0, durMs = 185_000L).asPartOf(185_000L)
        assertEquals(185_000L - 400L, whole.lastSoundMs)
        assertTrue(whole.coversWholeSong)
    }

    @Test
    fun playlistSessionKeepsSafeStylesAndEntersAtStartOfB() {
        val a = metricsWithBars("a", bpm = 120.0)
        val b = metricsWithBars("b", bpm = 90.0)
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = a,
            incomingAudio = b,
            isDjSession = false,
            autoLength = true,
        )
        assertTrue(
            "playlist must stay on safe styles, got ${spec.style}",
            spec.style in listOf(DjBridgeStyle.BeatMatchLongMix, DjBridgeStyle.FilterEcho, DjBridgeStyle.SmartFade),
        )
        assertTrue("playlist must enter near track start, got ${spec.entryInMs}", spec.entryInMs <= 5_000L)
    }

    @Test
    fun timbreShockForcesFilterEchoOverBeatMatchEvenWithSameBpmAndKey() {
        val softPianoA = metricsWithBars("piano", bpm = 124.0, camelot = "8A", harsh = 0.06, highRms = 0.08, lowRms = 0.35)
        val distortedGuitarB = metricsWithBars("rock", bpm = 124.0, camelot = "8A", harsh = 0.78, highRms = 0.65, lowRms = 0.14)
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = softPianoA,
            incomingAudio = distortedGuitarB,
            djMode = DjMode.Normal,
            isDjSession = true,
        )
        assertEquals(DjBridgeStyle.FilterEcho, spec.style)
    }

    @Test
    fun antiSoloGuardAvoidsGuitarSoloBarsWhenCleanBarsExist() {
        val soloBar = BarFeature(
            startMs = 156_000L,
            endMs = 158_000L,
            localBpm = 120.0,
            lufs = -10.5,
            lowRms = 0.06,
            midRms = 0.22,
            highRms = 0.72,
            kickSteady = 0.05,
            drumEnergy = 0.30,
            harshness = 0.72,
            vocalRatio = 0.0,
        )
        val cleanBars = hashMapOf<Long, BarFeature>()
        var t = 150_000L
        while (t < 180_000L) {
            cleanBars[t] = if (t in 150_000L..162_000L) {
                soloBar.copy(startMs = t, endMs = (t + 2_000L).coerceAtMost(180_000L))
            } else {
                BarFeature(
                    startMs = t,
                    endMs = (t + 2_000L).coerceAtMost(180_000L),
                    localBpm = 120.0,
                    lufs = -11.0,
                    lowRms = 0.48,
                    midRms = 0.28,
                    highRms = 0.14,
                    kickSteady = 0.90,
                    drumEnergy = 0.78,
                    harshness = 0.10,
                    vocalRatio = 0.08,
                )
            }
            t += 2_000L
        }
        val a = metricsWithBars("a-solo", bpm = 120.0, overrideBars = cleanBars)
        val b = metricsWithBars("b-clean", bpm = 120.0)
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = a,
            incomingAudio = b,
            djMode = DjMode.Normal,
            isDjSession = true,
        )
        assertTrue("expected clean non-solo bar >= 164s, got ${spec.cueOutStartMs}", spec.cueOutStartMs >= 164_000L)
    }

    @Test
    fun lyricLoopRequiresLufsParityAndMarksDoubleSpeedShortPhrase() {
        val outLyrics = Lyrics.Synced(
            lines = listOf(
                Line(
                    start = 160_000L,
                    end = 162_500L,
                    words = listOf(
                        Word(160_000L, 160_350L, "todo", true),
                        Word(160_380L, 160_740L, "fuego", true),
                    ),
                    real = true,
                ),
            ),
            source = "test",
        )
        val inLyrics = Lyrics.Synced(
            lines = listOf(
                Line(
                    start = 8_000L,
                    end = 10_200L,
                    words = listOf(
                        Word(8_000L, 8_420L, "fuego", true),
                        Word(8_460L, 9_100L, "en la noche", true),
                    ),
                    real = true,
                ),
            ),
            source = "test",
        )

        val aMatched = metricsWithBars("la", bpm = 115.0, lufs = -10.5)
        val bMismatchLufs = metricsWithBars("lb-loud", bpm = 115.0, lufs = -17.5)
        val mismatchedSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = aMatched,
            incomingAudio = bMismatchLufs,
            outgoingLyrics = outLyrics,
            incomingLyrics = inLyrics,
            djMode = DjMode.Normal,
            isDjSession = true,
        )
        assertNotEquals("must reject lyric_loop on large LUFS mismatch", DjBridgeStyle.LyricLoopCut, mismatchedSpec.style)

        val bMatched = metricsWithBars("lb-match", bpm = 115.0, lufs = -10.8)
        val matchedSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = aMatched,
            incomingAudio = bMatched,
            outgoingLyrics = outLyrics,
            incomingLyrics = inLyrics,
            djMode = DjMode.Normal,
            isDjSession = true,
        )
        assertEquals(DjBridgeStyle.LyricLoopCut, matchedSpec.style)
        val loopSpec = matchedSpec.lyricLoop
        assertNotNull(loopSpec)
        assertTrue("short single matched word should trigger doubleSpeedTail", loopSpec!!.doubleSpeedTail)
        assertTrue("Song B must enter on the shared phrase", loopSpec.inEntryMs in 7_800L..8_000L)
    }

    @Test
    fun allFiveStylesRenderSampleAccurateWavBelowCeiling() {
        val sliceA = synthStereoFloat(durationSec = 24.0, bpm = 122.0, freqHz = 220.0)
        val sliceB = synthStereoFloat(durationSec = 24.0, bpm = 124.0, freqHz = 330.0)
        val tempDir = Files.createTempDirectory("oto-bridge-test").toFile()
        try {
            val baseSpec = DjBridgeSpec(
                style = DjBridgeStyle.BeatMatchLongMix,
                preRollStartMs = 4_500L,
                cueOutStartMs = 6_000L,
                cueOutEndMs = 14_000L,
                entryInMs = 3_000L,
                dryBResumeMs = 14_000L,
                preRollMs = 1_500L,
                postRollMs = 2_500L,
                transitionDurationMs = 8_000L,
                bpmA = 122.0,
                bpmB = 124.0,
                tempoMatchRatio = 122.0 / 124.0,
                pickupMs = 480L,
                silenceMs = 180L,
                lyricLoop = LyricLoopPlanSpec(
                    phraseText = "fuego",
                    outLoopStartMs = 6_000L,
                    outLoopEndMs = 6_450L,
                    inPhraseStartMs = 3_100L,
                    inPhraseEndMs = 3_600L,
                    inEntryMs = 3_000L,
                    repeats = 4,
                    beatMs = 491.8,
                    loopDurationMs = 450L,
                    doubleSpeedTail = true,
                    confidence = 0.9,
                ),
            )
            for (style in DjBridgeStyle.entries) {
                val spec = when (style) {
                    DjBridgeStyle.EnergyDropCut -> baseSpec.copy(style = style, transitionDurationMs = 1_000L, cueOutEndMs = 6_500L)
                    DjBridgeStyle.LyricLoopCut -> baseSpec.copy(style = style, transitionDurationMs = 3_000L)
                    else -> baseSpec.copy(style = style)
                }
                val rendered = DjBridgeRenderer.renderSliceBridge(
                    spec = spec,
                    sliceA = sliceA,
                    sliceAOriginMs = 0L,
                    sliceB = sliceB,
                    sliceBOriginMs = 0L,
                    gainA = 0.9f,
                    gainB = 0.85f,
                )
                assertTrue("$style preRoll", rendered.preRollFrames > 0)
                assertTrue("$style dryBStartOffset", rendered.dryBStartOffsetFrames > rendered.preRollFrames)
                var peak = 0f
                for (s in rendered.stereoPcm) {
                    assertFalse("$style produced non-finite sample", s.isNaN() || s.isInfinite())
                    val a = abs(s)
                    if (a > peak) peak = a
                }
                assertTrue("$style exceeded full scale: $peak", peak <= 1.0f)
                val wav = File(tempDir, "${style.id}.wav")
                rendered.writeWavPcm16(wav)
                val expectedBytes = 44L + rendered.totalFrames.toLong() * 4L
                assertEquals("$style wav size", expectedBytes, wav.length())
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun djTransitionPlanDelegatesToV2BridgeSpecWithLyricsPeek() {
        val songA = Track(id = "trackA", title = "Song A", artists = listOf("Art A"), artistIds = listOf("a1"), seconds = 180)
        val songB = Track(id = "trackB", title = "Song B", artists = listOf("Art A"), artistIds = listOf("a1"), seconds = 180)
        val profile = Profile(
            lists = listOf(LocalPlaylist("p1", "Mix", listOf(Heard("trackA", "Song A", "Art A", null, "a1"), Heard("trackB", "Song B", "Art A", null, "a1")))),
            liked = emptyList(),
            history = emptyList(),
            plays = emptyList(),
            now = 1_000_000L,
        )
        val cache = AudioAnalysisCache()
        cache.put(metricsWithBars("trackA", bpm = 122.0, camelot = "8A"))
        cache.put(metricsWithBars("trackB", bpm = 122.0, camelot = "8A"))
        val dj = Dj(
            profile = profile,
            catalog = EmptyCatalog,
            memory = Memory(null),
            scope = CoroutineScope(SupervisorJob()),
            random = Random(42),
            analysisCache = cache,
        )
        val plan = dj.transitionPlan(songA, songB, outgoingDurationMs = 180_000L, configuredFadeMs = 8_000L, autoLength = true)
        assertNotNull(plan.bridgeSpec)
        assertEquals(DjTransitionStyle.BeatMatchLongMix, plan.style)
    }

    @Test
    fun balancedSelectionAndRecentVarietyPreventFilterEchoMonopoly() {
        // 1. Baladas con pequeña variación tímbrica natural y mismo tempo: no deben disparar FilterEcho.
        val balladA = metricsWithBars("balA", bpm = 67.3, camelot = "8B", harsh = 0.14, highRms = 0.16, lowRms = 0.34, drumEnergy = 0.32)
        val balladB = metricsWithBars("balB", bpm = 65.7, camelot = "8B", harsh = 0.30, highRms = 0.24, lowRms = 0.22, drumEnergy = 0.32)
        val balladSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = balladA,
            incomingAudio = balladB,
            djMode = DjMode.Adhd,
            isDjSession = true,
            autoLength = true,
        )
        assertNotEquals("ballads in same style should not fall into FilterEcho", DjBridgeStyle.FilterEcho, balladSpec.style)
        assertEquals(DjBridgeStyle.BeatMatchLongMix, balladSpec.style)

        // 2. Temas melódicos con distinto BPM pero textura amable y sin cambio de tanda -> SmartFade.
        val calmA = metricsWithBars("calmA", bpm = 115.0, camelot = "8A", harsh = 0.14, highRms = 0.14, lowRms = 0.32, drumEnergy = 0.26, kickSteady = 0.40)
        val calmB = metricsWithBars("calmB", bpm = 74.0, camelot = "9A", harsh = 0.18, highRms = 0.16, lowRms = 0.30, drumEnergy = 0.24, kickSteady = 0.35)
        val calmSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = calmA,
            incomingAudio = calmB,
            djMode = DjMode.Normal,
            isDjSession = true,
            autoLength = true,
        )
        assertEquals(DjBridgeStyle.SmartFade, calmSpec.style)

        // 3. Temas con distinto BPM pero entrada fuerte en drop -> EnergyDropCut.
        // A sube hacia un estribillo en 150 s: el corte va justo antes, no dentro.
        val liftOverrides = mapOf(
            148_000L to BarFeature(148_000L, 150_000L, 66.0, -14.0, 0.30, 0.25, 0.15, 0.4, 0.40, 0.15, 0.1),
            150_000L to BarFeature(150_000L, 152_000L, 66.0, -10.0, 0.45, 0.30, 0.24, 0.85, 0.70, 0.20, 0.05),
        )
        val popA = metricsWithBars("popA", bpm = 66.0, camelot = "8A", harsh = 0.16, highRms = 0.20, lowRms = 0.36, drumEnergy = 0.55, overrideBars = liftOverrides)
        val dropOverrides = mapOf(
            20_000L to BarFeature(20_000L, 22_000L, 98.0, -13.5, 0.22, 0.25, 0.15, 0.4, 0.32, 0.15, 0.1),
            22_000L to BarFeature(22_000L, 24_000L, 98.0, -10.0, 0.45, 0.30, 0.24, 0.85, 0.68, 0.20, 0.05),
        )
        val popDropB = metricsWithBars("popDropB", bpm = 98.0, camelot = "8A", lufs = -10.8, harsh = 0.20, highRms = 0.24, lowRms = 0.40, drumEnergy = 0.64, overrideBars = dropOverrides)
        val dropSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = popA,
            incomingAudio = popDropB,
            djMode = DjMode.Adhd,
            isDjSession = true,
            autoLength = true,
        )
        assertEquals(DjBridgeStyle.EnergyDropCut, dropSpec.style)
        assertTrue("el corte va antes de la subida de A: ${dropSpec.cueOutEndMs}", dropSpec.cueOutEndMs in 149_500L until 150_000L)

        // 4. Con historial repetido, evita encadenar la misma transición cuando hay otra alternativa sólida.
        val sameTempoWithDropA = metricsWithBars("syncA", bpm = 124.0, camelot = "8A", drumEnergy = 0.58, overrideBars = liftOverrides)
        val sameTempoWithDropB = metricsWithBars("syncB", bpm = 122.5, camelot = "8A", lufs = -10.6, drumEnergy = 0.68, overrideBars = dropOverrides)
        val freshSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = sameTempoWithDropA,
            incomingAudio = sameTempoWithDropB,
            djMode = DjMode.Adhd,
            isDjSession = true,
            autoLength = true,
        )
        assertEquals(DjBridgeStyle.BeatMatchLongMix, freshSpec.style)
        val rotatedSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = sameTempoWithDropA,
            incomingAudio = sameTempoWithDropB,
            djMode = DjMode.Adhd,
            isDjSession = true,
            autoLength = true,
            recentStyles = listOf(DjBridgeStyle.BeatMatchLongMix),
        )
        assertEquals(DjBridgeStyle.EnergyDropCut, rotatedSpec.style)
    }

    @Test
    fun lyricLoopCutMatchesLrcEstimatedLyricsAndRendersDoubleSpeedWithTempoRatio() {
        val sr = 48_000
        val beatMs = 500L // 120 BPM
        val outAudio = AudioMetrics(
            trackId = "lrc-a",
            bpm = 120.0,
            bpmConfidence = 0.88,
            beatPhaseMs = 0L,
            downbeatPhaseMs = 0L,
            rmsDb = -9.5,
            dynamicRangeDb = 6.0,
            beatStrength = 0.82,
            introEndMs = 4_000L,
            dropMs = 24_000L,
            outroStartMs = 170_000L,
            durationMs = 190_000L,
            beatGrid = (0L..188_000L step 8_000L).toList(),
            analyzedMs = 190_000L,
        )
        val inAudio = AudioMetrics(
            trackId = "lrc-b",
            bpm = 126.0,
            bpmConfidence = 0.88,
            beatPhaseMs = 0L,
            downbeatPhaseMs = 0L,
            rmsDb = -9.5,
            dynamicRangeDb = 6.0,
            beatStrength = 0.82,
            introEndMs = 4_000L,
            dropMs = 20_000L,
            outroStartMs = 170_000L,
            durationMs = 190_000L,
            beatGrid = (0L..188_000L step 8_000L).toList(),
            analyzedMs = 190_000L,
        )
        val lyricsA = io.github.iaguito22.oto.lyrics.Lyrics.Synced(
            lines = listOf(
                io.github.iaguito22.oto.lyrics.Line(
                    start = 158_000L,
                    end = 159_400L,
                    words = listOf(
                        io.github.iaguito22.oto.lyrics.Word(158_000L, 158_350L, "when", true),
                        io.github.iaguito22.oto.lyrics.Word(158_350L, 158_800L, "night", true),
                        io.github.iaguito22.oto.lyrics.Word(158_800L, 159_350L, "falls", false),
                    ),
                    real = false,
                ),
                io.github.iaguito22.oto.lyrics.Line(
                    start = 161_000L,
                    end = 161_420L,
                    words = listOf(
                        io.github.iaguito22.oto.lyrics.Word(161_000L, 161_420L, "forever", false),
                    ),
                    real = false,
                ),
            ),
            source = "lrclib",
        )
        val lyricsB = io.github.iaguito22.oto.lyrics.Lyrics.Synced(
            lines = listOf(
                io.github.iaguito22.oto.lyrics.Line(
                    start = 16_000L,
                    end = 17_400L,
                    words = listOf(
                        io.github.iaguito22.oto.lyrics.Word(16_000L, 16_450L, "night", true),
                        io.github.iaguito22.oto.lyrics.Word(16_450L, 16_950L, "falls", true),
                        io.github.iaguito22.oto.lyrics.Word(16_950L, 17_400L, "again", false),
                    ),
                    real = false,
                ),
                io.github.iaguito22.oto.lyrics.Line(
                    start = 20_000L,
                    end = 20_420L,
                    words = listOf(
                        io.github.iaguito22.oto.lyrics.Word(20_000L, 20_420L, "forever", false),
                    ),
                    real = false,
                ),
            ),
            source = "lrclib",
        )

        val match = DjLyricMatcher.findSharedLoopMatch(
            outLyrics = lyricsA,
            inLyrics = lyricsB,
            outMinMs = 140_000L,
            outMaxMs = 180_000L,
            inMinMs = 8_000L,
            inMaxMs = 40_000L,
            bpmA = 120.0,
        )
        assertNotNull("LRC real=false multi-word hook should match", match)
        assertEquals("night falls", match!!.phraseText)

        // Single line-boundary word (< 560 ms) triggers double-speed 2.0x tail roll.
        val fastLyricsA = io.github.iaguito22.oto.lyrics.Lyrics.Synced(
            lines = listOf(lyricsA.lines[1]),
            source = "lrclib",
        )
        val fastLyricsB = io.github.iaguito22.oto.lyrics.Lyrics.Synced(
            lines = listOf(lyricsB.lines[1]),
            source = "lrclib",
        )
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 190_000L,
            incomingDurationMs = 190_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = outAudio,
            incomingAudio = inAudio,
            outgoingLyrics = fastLyricsA,
            incomingLyrics = fastLyricsB,
            djMode = DjMode.Normal,
            isDjSession = true,
            autoLength = true,
            recentStyles = emptyList(),
        )
        assertNotNull(spec)
        assertEquals(DjBridgeStyle.LyricLoopCut, spec!!.style)
        assertTrue("Expected doubleSpeedTail=true on short LRC line-edge word", spec.lyricLoop?.doubleSpeedTail == true)
        assertEquals(beatMs, outAudio.beatPeriodMs!!.toLong())
        assertTrue("Expected B tempoMatchRatio near 120/126 (was ${spec.tempoMatchRatio})", spec.tempoMatchRatio in 0.94..0.97)

        val originA = (spec.preRollStartMs - 1_000L).coerceAtLeast(0L)
        val sliceDurationMsA = (spec.cueOutEndMs - originA + 2_000L).coerceAtLeast(12_000L)
        val sliceFramesA = ((sliceDurationMsA * sr) / 1_000L).toInt()
        val slicePcmA = FloatArray(sliceFramesA * 2) { i ->
            (0.45 * kotlin.math.sin(2.0 * kotlin.math.PI * 330.0 * (i / 2) / sr)).toFloat()
        }
        val originB = (spec.entryInMs - 1_500L).coerceAtLeast(0L)
        val sliceDurationMsB = (spec.dryBResumeMs + spec.postRollMs - originB + 2_500L).coerceAtLeast(14_000L)
        val sliceFramesB = ((sliceDurationMsB * sr) / 1_000L).toInt()
        val slicePcmB = FloatArray(sliceFramesB * 2) { i ->
            (0.45 * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * (i / 2) / sr)).toFloat()
        }
        val outFile = java.io.File.createTempFile("lyric_loop_test", ".wav")
        try {
            val rendered = DjBridgeRenderer.renderSliceBridge(
                spec = spec,
                sliceA = slicePcmA,
                sliceAOriginMs = originA,
                sliceB = slicePcmB,
                sliceBOriginMs = originB,
                gainA = 1.0f,
                gainB = 1.0f,
                sampleRate = sr,
            )
            assertNotNull(rendered)
            rendered!!.writeWavPcm16(outFile)
            assertTrue("Rendered WAV must exist and be non-empty", outFile.length() > 44L)
            val peak = rendered.stereoPcm.maxOf { kotlin.math.abs(it) }
            assertTrue("Peak $peak must stay below ceiling", peak <= 1.0f)
        } finally {
            outFile.delete()
        }
    }

    @Test
    fun energyDropCutRendersGapEchoesAndPreDownbeatRiserCleanly() {
        val sr = 48_000
        fun synthBars(durationMs: Long, dropAtMs: Long, bpm: Double): List<BarFeature> =
            (0L until durationMs step 2_000L).map { s ->
                val hot = s >= dropAtMs
                BarFeature(
                    startMs = s,
                    endMs = minOf(durationMs, s + 2_000L),
                    localBpm = bpm,
                    lufs = if (hot) -7.0 else -16.5,
                    lowRms = if (hot) 0.52 else 0.08,
                    midRms = if (hot) 0.34 else 0.14,
                    highRms = if (hot) 0.24 else 0.09,
                    kickSteady = if (hot) 0.90 else 0.25,
                    drumEnergy = if (hot) 0.88 else 0.18,
                    harshness = 0.25,
                    vocalRatio = 0.0,
                    chroma = List(12) { if (it == 0) 1f else 0.1f },
                )
            }

        val outAudio = AudioMetrics(
            trackId = "drop-a",
            bpm = 120.0,
            bpmConfidence = 0.90,
            beatPhaseMs = 0L,
            downbeatPhaseMs = 0L,
            rmsDb = -9.0,
            dynamicRangeDb = 7.0,
            beatStrength = 0.85,
            introEndMs = 4_000L,
            dropMs = 32_000L,
            outroStartMs = 152_000L,
            durationMs = 180_000L,
            beatGrid = (0L..176_000L step 8_000L).toList(),
            analyzedMs = 180_000L,
            bars = synthBars(180_000L, dropAtMs = 144_000L, bpm = 120.0),
        )
        val inAudio = AudioMetrics(
            trackId = "drop-b",
            bpm = 120.0,
            bpmConfidence = 0.90,
            beatPhaseMs = 0L,
            downbeatPhaseMs = 0L,
            rmsDb = -9.0,
            dynamicRangeDb = 7.0,
            beatStrength = 0.88,
            introEndMs = 4_000L,
            dropMs = 24_000L,
            outroStartMs = 160_000L,
            durationMs = 180_000L,
            beatGrid = (0L..176_000L step 8_000L).toList(),
            analyzedMs = 180_000L,
            bars = synthBars(180_000L, dropAtMs = 24_000L, bpm = 120.0),
        )
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = outAudio,
            incomingAudio = inAudio,
            outgoingLyrics = null,
            incomingLyrics = null,
            djMode = DjMode.Adhd,
            isDjSession = true,
            autoLength = true,
            recentStyles = listOf(DjBridgeStyle.BeatMatchLongMix),
        )
        assertNotNull(spec)
        assertEquals(DjBridgeStyle.EnergyDropCut, spec!!.style)
        assertTrue("silenceMs=${spec.silenceMs} should be in 1200..2700", spec.silenceMs in 1_200L..2_700L)

        val originA = (spec.preRollStartMs - 1_000L).coerceAtLeast(0L)
        val sliceFramesA = (((spec.cueOutEndMs - originA + 1_500L) * sr) / 1_000L).toInt()
        val slicePcmA = FloatArray(sliceFramesA * 2) { i ->
            (0.5 * kotlin.math.sin(2.0 * kotlin.math.PI * 520.0 * (i / 2) / sr)).toFloat()
        }
        val originB = (spec.entryInMs - 1_500L).coerceAtLeast(0L)
        val sliceFramesB = (((spec.dryBResumeMs + spec.postRollMs - originB + 2_500L) * sr) / 1_000L).toInt()
        val slicePcmB = FloatArray(sliceFramesB * 2) { i ->
            (0.5 * kotlin.math.sin(2.0 * kotlin.math.PI * 180.0 * (i / 2) / sr)).toFloat()
        }
        val rendered = DjBridgeRenderer.renderSliceBridge(
            spec = spec,
            sliceA = slicePcmA,
            sliceAOriginMs = originA,
            sliceB = slicePcmB,
            sliceBOriginMs = originB,
            gainA = 1.0f,
            gainB = 1.0f,
            sampleRate = sr,
        )
        assertNotNull(rendered)
        val peak = rendered!!.stereoPcm.maxOf { kotlin.math.abs(it) }
        assertTrue("Peak $peak must stay below 1.0f", peak <= 1.0f)
        val expectedSwitch = rendered.preRollMs + rendered.spec.pickupMs + rendered.spec.silenceMs
        assertTrue(
            "audibleSwitchMs=${rendered.audibleSwitchMs} should match ~$expectedSwitch ms",
            kotlin.math.abs(rendered.audibleSwitchMs - expectedSwitch) <= 5L,
        )
    }

    @Test
    fun beatMatchAcceptsDenseModerateConfidenceGridAndCloseVocalPairs() {
        val denseA = AudioMetrics(
            trackId = "hollywood",
            bpm = 117.72,
            bpmConfidence = 0.21,
            beatPhaseMs = 0L,
            downbeatPhaseMs = 0L,
            rmsDb = -10.5,
            integratedLufs = -10.5,
            keyName = "A min",
            camelot = "8A",
            keyConfidence = 0.75,
            beatStrength = 0.46,
            firstSoundMs = 200L,
            lastSoundMs = 178_000L,
            outroStartMs = 165_000L,
            durationMs = 180_000L,
            beatGrid = (0 until 21).map { it * 509L },
            analyzedMs = 180_000L,
            bars = metricsWithBars("h-bars", 117.72, drumEnergy = 0.34, kickSteady = 0.50).bars,
        )
        val denseB = AudioMetrics(
            trackId = "alibi",
            bpm = 126.29,
            bpmConfidence = 0.59,
            beatPhaseMs = 0L,
            downbeatPhaseMs = 0L,
            rmsDb = -10.2,
            integratedLufs = -10.2,
            keyName = "A min",
            camelot = "8A",
            keyConfidence = 0.80,
            beatStrength = 0.58,
            firstSoundMs = 200L,
            lastSoundMs = 178_000L,
            outroStartMs = 165_000L,
            durationMs = 180_000L,
            beatGrid = (0 until 20).map { it * 475L },
            analyzedMs = 180_000L,
            bars = metricsWithBars("a-bars", 126.29, drumEnergy = 0.38, kickSteady = 0.55).bars,
        )
        val denseSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = denseA,
            incomingAudio = denseB,
            outgoingLyrics = null,
            incomingLyrics = null,
            djMode = DjMode.Normal,
            isDjSession = true,
            autoLength = true,
        )
        assertNotNull(denseSpec)
        assertEquals(DjBridgeStyle.BeatMatchLongMix, denseSpec!!.style)

        // Close BPM vocal tracks (like Otra Vez 93.1 -> Me Porto Bonito 92.0) should still choose BeatMatchLongMix.
        val vocalBar = BarFeature(
            startMs = 0L,
            endMs = 2_000L,
            localBpm = 93.0,
            lufs = -10.5,
            lowRms = 0.38,
            midRms = 0.30,
            highRms = 0.20,
            kickSteady = 0.68,
            drumEnergy = 0.44,
            harshness = 0.14,
            vocalRatio = 0.65,
        )
        val vocalA = metricsWithBars(
            id = "otra-vez",
            bpm = 93.08,
            camelot = "6A",
            drumEnergy = 0.44,
            kickSteady = 0.68,
            overrideBars = (0L until 180_000L step 2_000L).associateWith { vocalBar.copy(startMs = it, endMs = it + 2_000L) },
        )
        val vocalB = metricsWithBars(
            id = "bonito",
            bpm = 92.01,
            camelot = "7A",
            drumEnergy = 0.44,
            kickSteady = 0.68,
            overrideBars = (0L until 180_000L step 2_000L).associateWith { vocalBar.copy(startMs = it, endMs = it + 2_000L, localBpm = 92.01) },
        )
        val vocalLyricsA = Lyrics.Synced(
            lines = listOf(Line(155_000L, 160_000L, listOf(Word(155_000L, 156_500L, "una", true), Word(156_500L, 160_000L, "noche", false)), real = true)),
            source = "lrclib",
        )
        val vocalLyricsB = Lyrics.Synced(
            lines = listOf(Line(5_000L, 10_000L, listOf(Word(5_000L, 7_000L, "hoy", true), Word(7_000L, 10_000L, "suena", false)), real = true)),
            source = "lrclib",
        )
        val closeVocalSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = vocalA,
            incomingAudio = vocalB,
            outgoingLyrics = vocalLyricsA,
            incomingLyrics = vocalLyricsB,
            djMode = DjMode.Normal,
            isDjSession = true,
            autoLength = true,
        )
        assertNotNull(closeVocalSpec)
        assertEquals(DjBridgeStyle.BeatMatchLongMix, closeVocalSpec!!.style)
    }

    @Test
    fun filterEchoUsesStraightPulseAndSeparatesFarTempoDrums() {
        val sr = DjDsp.DJ_SAMPLE_RATE
        // Pair far in BPM (~32.6% diff, 92.3 vs 122.42) with recent SmartFade so FilterEcho competes cleanly.
        val outA = metricsWithBars("nmc", bpm = 92.30, camelot = "4A", drumEnergy = 0.30, kickSteady = 0.40)
        val inB = metricsWithBars("mojabi", bpm = 122.42, camelot = "9A", drumEnergy = 0.55, kickSteady = 0.70)
        val lyricsA = Lyrics.Synced(
            lines = listOf(Line(160_000L, 165_000L, listOf(Word(160_000L, 162_500L, "nadie", true), Word(162_500L, 165_000L, "sabe", false)), real = true)),
            source = "lrclib",
        )
        val lyricsB = Lyrics.Synced(
            lines = listOf(Line(4_000L, 9_000L, listOf(Word(4_000L, 6_500L, "desierto", true), Word(6_500L, 9_000L, "rojo", false)), real = true)),
            source = "lrclib",
        )
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = outA,
            incomingAudio = inB,
            outgoingLyrics = lyricsA,
            incomingLyrics = lyricsB,
            djMode = DjMode.Normal,
            isDjSession = true,
            autoLength = true,
            recentStyles = listOf(DjBridgeStyle.SmartFade, DjBridgeStyle.SmartFade),
        )
        assertNotNull(spec)
        if (spec!!.style == DjBridgeStyle.FilterEcho) {
            // Para BPM lejanos (~32.6% diff), FilterEcho debe ir con filtro limpio sin añadir eco descolocado.
            assertEquals("Incompatible BPM FilterEcho should omit rhythmic echo delay", 0.0, spec.echoDelayMs, 0.5)
            assertTrue("Incompatible BPM echo duration ${spec.transitionDurationMs}ms must stay <= 6800ms", spec.transitionDurationMs <= 6_800L)
        }

        // Cuando dos temas compatibles en tempo con rejilla sólida disparan FilterEcho (p.ej. choque tímbrico), el eco va a 1 negra exacta (beatMs).
        val cleanPianoA = metricsWithBars("clean-piano", bpm = 100.0, camelot = "8A", harsh = 0.05, highRms = 0.08, lowRms = 0.35, drumEnergy = 0.25, kickSteady = 0.35)
            .copy(bpmConfidence = 0.90, beatGrid = (0L..20_000L step 600L).toList())
        val rockGuitarB = metricsWithBars("rock-guitar", bpm = 100.0, camelot = "8A", harsh = 0.78, highRms = 0.65, lowRms = 0.14, drumEnergy = 0.55)
            .copy(bpmConfidence = 0.90, beatGrid = (0L..20_000L step 600L).toList())
        val lockedEchoSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = cleanPianoA,
            incomingAudio = rockGuitarB,
            djMode = DjMode.Normal,
            isDjSession = true,
        )
        assertEquals(DjBridgeStyle.FilterEcho, lockedEchoSpec.style)
        assertEquals("Quarter-note beat pulse delay (1.0 * beatA = 600ms)", 600.0, lockedEchoSpec.echoDelayMs, 10.0)

        // Filter-only render (echoDelayMs == 0.0) must render cleanly without errors or early B bleed on far tempos.
        val forcedFilterOnly = DjBridgeSpec(
            style = DjBridgeStyle.FilterEcho,
            preRollStartMs = 163_000L,
            cueOutStartMs = 165_000L,
            cueOutEndMs = 170_200L,
            entryInMs = 5_000L,
            dryBResumeMs = 10_200L,
            preRollMs = 2_000L,
            postRollMs = 2_000L,
            transitionDurationMs = 5_200L,
            bpmA = 92.30,
            bpmB = 122.42,
            tempoMatchRatio = 1.0,
            echoDelayMs = 0.0,
        )
        val filterOnlyA = FloatArray(((((forcedFilterOnly.cueOutEndMs - (forcedFilterOnly.preRollStartMs - 500L) + 1_000L) * sr) / 1_000L).toInt()) * 2)
        val filterOnlyB = synthStereoFloat(9.0, bpm = 122.42, freqHz = 440.0, sr = sr)
        val filterRendered = DjBridgeRenderer.renderSliceBridge(
            spec = forcedFilterOnly,
            sliceA = filterOnlyA,
            sliceAOriginMs = forcedFilterOnly.preRollStartMs - 500L,
            sliceB = filterOnlyB,
            sliceBOriginMs = forcedFilterOnly.entryInMs - 500L,
            gainA = 1.0f,
            gainB = 1.0f,
            sampleRate = sr,
        )
        assertNotNull(filterRendered)

        // Explicitly render a far-tempo FilterEcho spec and verify B does not enter underneath A drums in the first 35%.
        val forcedFarEcho = DjBridgeSpec(
            style = DjBridgeStyle.FilterEcho,
            preRollStartMs = 163_000L,
            cueOutStartMs = 165_000L,
            cueOutEndMs = 170_200L,
            entryInMs = 5_000L,
            dryBResumeMs = 10_200L,
            preRollMs = 2_000L,
            postRollMs = 2_000L,
            transitionDurationMs = 5_200L,
            bpmA = 92.30,
            bpmB = 122.42,
            tempoMatchRatio = 1.0,
            echoDelayMs = 325.0,
        )
        val originA = forcedFarEcho.preRollStartMs - 500L
        val framesA = (((forcedFarEcho.cueOutEndMs - originA + 1_000L) * sr) / 1_000L).toInt()
        val silentA = FloatArray(framesA * 2) // A silent so only B leaks if B enters early
        val originB = forcedFarEcho.entryInMs - 500L
        val framesB = (((forcedFarEcho.dryBResumeMs + forcedFarEcho.postRollMs - originB + 1_000L) * sr) / 1_000L).toInt()
        val toneB = synthStereoFloat(framesB.toDouble() / sr, bpm = 122.42, freqHz = 440.0, sr = sr)

        val rendered = DjBridgeRenderer.renderSliceBridge(
            spec = forcedFarEcho,
            sliceA = silentA,
            sliceAOriginMs = originA,
            sliceB = toneB,
            sliceBOriginMs = originB,
            gainA = 1.0f,
            gainB = 1.0f,
            sampleRate = sr,
        )
        assertNotNull(rendered)
        val mixStart = (rendered!!.preRollMs * sr / 1_000L).toInt()
        val earlyCheckFrames = ((forcedFarEcho.transitionDurationMs * 0.45) * sr / 1_000L).toInt()
        var earlyMaxB = 0f
        for (i in 0 until earlyCheckFrames) {
            val idx = (mixStart + i) * 2
            if (idx + 1 < rendered.stereoPcm.size) {
                earlyMaxB = maxOf(earlyMaxB, abs(rendered.stereoPcm[idx]), abs(rendered.stereoPcm[idx + 1]))
            }
        }
        assertTrue("Incoming B should stay silent while far-tempo A echoes fade out, got peak $earlyMaxB", earlyMaxB < 1e-4f)
    }

    @Test
    fun lyricLoopCutDoesNotRejectWhenIncomingAudioIsNull() {
        val outA = metricsWithBars("ll-out", bpm = 120.0, camelot = "8A")
        val lyricsA = Lyrics.Synced(
            lines = listOf(
                Line(
                    start = 158_000L,
                    end = 159_500L,
                    words = listOf(
                        Word(158_000L, 158_500L, "sube", true),
                        Word(158_500L, 159_000L, "la", true),
                        Word(159_000L, 159_500L, "mano", false),
                    ),
                    real = false,
                ),
                Line(
                    start = 161_500L,
                    end = 162_200L,
                    words = listOf(Word(161_500L, 162_200L, "arriba", false)),
                    real = false,
                ),
            ),
            source = "lrclib",
        )
        val lyricsB = Lyrics.Synced(
            lines = listOf(
                Line(
                    start = 10_000L,
                    end = 11_500L,
                    words = listOf(
                        Word(10_000L, 10_500L, "sube", true),
                        Word(10_500L, 11_000L, "la", true),
                        Word(11_000L, 11_500L, "mano", false),
                    ),
                    real = false,
                ),
                Line(
                    start = 13_500L,
                    end = 14_200L,
                    words = listOf(Word(13_500L, 14_200L, "arriba", false)),
                    real = false,
                ),
            ),
            source = "lrclib",
        )
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = outA,
            incomingAudio = null,
            outgoingLyrics = lyricsA,
            incomingLyrics = lyricsB,
            djMode = DjMode.Normal,
            isDjSession = true,
            autoLength = true,
        )
        assertNotNull(spec)
        assertEquals(DjBridgeStyle.LyricLoopCut, spec!!.style)
    }

    @Test
    fun beatMatchAlignsSlowHalfBeatOffsetAndFilterEchoOmitsEchoOnGenreOrHarmonyClash() {
        val sr = DjDsp.DJ_SAMPLE_RATE
        // 1) Con cambio de tanda o armonía chocante, FilterEcho omite el eco (solo filtro limpio) para no emborronar B.
        val piano8A = metricsWithBars("piano-8a", bpm = 100.0, camelot = "8A", harsh = 0.05, highRms = 0.08, lowRms = 0.35, drumEnergy = 0.25, kickSteady = 0.35)
            .copy(bpmConfidence = 0.90, beatGrid = (0L..20_000L step 600L).toList())
        val guitar8A = metricsWithBars("guitar-8a", bpm = 100.0, camelot = "8A", harsh = 0.78, highRms = 0.65, lowRms = 0.14, drumEnergy = 0.55)
            .copy(bpmConfidence = 0.90, beatGrid = (0L..20_000L step 600L).toList())
        val tandaShiftSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = piano8A,
            incomingAudio = guitar8A,
            tandaChanged = true,
            djMode = DjMode.Normal,
            isDjSession = true,
        )
        assertEquals(DjBridgeStyle.FilterEcho, tandaShiftSpec.style)
        assertEquals("Genre shift FilterEcho should stay filter-only", 0.0, tandaShiftSpec.echoDelayMs, 0.5)

        val guitarClash2B = guitar8A.copy(camelot = "2B")
        val harmonyClashSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = piano8A,
            incomingAudio = guitarClash2B,
            tandaChanged = false,
            djMode = DjMode.Normal,
            isDjSession = true,
        )
        assertEquals(DjBridgeStyle.FilterEcho, harmonyClashSpec.style)
        assertEquals("Harmonic clash FilterEcho should stay filter-only", 0.0, harmonyClashSpec.echoDelayMs, 0.5)

        // 2) snapToBeat debe aceptar rejillas densas con confianza moderada (>= 0.18) y desfases de medio pulso.
        val grid92 = (0L..30_000L step 652L).toList()
        val moderateAudio = metricsWithBars("mod-92", bpm = 92.0, camelot = "8A")
            .copy(bpmConfidence = 0.22, beatStrength = 0.42, beatGrid = grid92, beatPhaseMs = 0L)
        val snapped = DjBridgePlanner.snapToBeat(6_520L + 300L, moderateAudio, lo = 4_000L, hi = 12_000L)
        assertTrue("snapToBeat should snap moderate-confidence half-beat within 25ms of grid beat (got $snapped)", kotlin.math.abs(snapped - 6_520L) <= 25L || kotlin.math.abs(snapped - 7_172L) <= 25L)

        // 3) En BPM lento (92 <-> 99 BPM, medio beat ~326 ms > antiguo tope de 260 ms) con bajo continuo debajo,
        // `kickPhaseShiftMs` / `renderSliceBridge` deben encontrar el desfase ~305 ms en lugar de rendirse en 0 ms.
        fun kickWithSubBass(durSec: Double, bpm: Double, phaseMs: Double): FloatArray {
            val frames = (durSec * sr).toInt()
            val out = FloatArray(frames * 2)
            val period = 60.0 / bpm
            for (f in 0 until frames) {
                val t = f.toDouble() / sr
                // Subgrave continuo 52 Hz que confundía el rectificador abs() anterior.
                var v = (kotlin.math.sin(2.0 * kotlin.math.PI * 52.0 * t) * 0.22).toFloat()
                val rel = t - phaseMs / 1000.0
                if (rel >= 0.0) {
                    val inBeat = rel % period
                    if (inBeat < 0.08) {
                        v += (kotlin.math.sin(2.0 * kotlin.math.PI * 65.0 * inBeat) * (1.0 - inBeat / 0.08) * 0.70).toFloat()
                    }
                }
                out[f * 2] = v
                out[f * 2 + 1] = v
            }
            return out
        }

        val sliceA = kickWithSubBass(18.0, bpm = 92.0, phaseMs = 0.0)
        // B a 99 BPM con entrada en 5000 ms, pero sus bombos van ~305 ms más tarde respecto al compás 6 de A (3913 ms, casi a contratiempo).
        val sliceB = kickWithSubBass(18.0, bpm = 99.0, phaseMs = 5_000.0 + 305.0 - kotlin.math.floor(5_000.0 / (60_000.0 / 99.0)) * (60_000.0 / 99.0))
        val slowSpec = DjBridgeSpec(
            style = DjBridgeStyle.BeatMatchLongMix,
            preRollStartMs = 1_913L,
            cueOutStartMs = 3_913L,
            cueOutEndMs = 12_913L,
            entryInMs = 5_000L,
            dryBResumeMs = 14_500L,
            preRollMs = 2_000L,
            postRollMs = 2_000L,
            transitionDurationMs = 9_000L,
            bpmA = 92.0,
            bpmB = 99.0,
            tempoMatchRatio = 92.0 / 99.0,
        )
        val slowRendered = DjBridgeRenderer.renderSliceBridge(slowSpec, sliceA, 0L, sliceB, 0L, sampleRate = sr)
        val alignedPhase = kotlin.math.abs(slowRendered.spec.kickShiftMs)
        assertTrue(
            "Slow 92<->99 BPM half-beat offset (~305ms) must align instead of returning 0ms (got ${slowRendered.spec.kickShiftMs}ms)",
            alignedPhase in 275.0..335.0,
        )

        // 4) FilterEcho bloqueado en pulso ajusta kickShiftMs y devuelve handoffBTrackMs coherente con ese desplazamiento.
        val lockedEchoRenderSpec = DjBridgeSpec(
            style = DjBridgeStyle.FilterEcho,
            preRollStartMs = 2_200L,
            cueOutStartMs = 4_200L,
            cueOutEndMs = 10_200L,
            entryInMs = 5_000L,
            dryBResumeMs = 9_500L,
            preRollMs = 2_000L,
            postRollMs = 2_000L,
            transitionDurationMs = 6_000L,
            bpmA = 100.0,
            bpmB = 100.0,
            tempoMatchRatio = 1.0,
            echoDelayMs = 600.0,
        )
        val echoA = kickWithSubBass(16.0, bpm = 100.0, phaseMs = 0.0)
        // B a 100 BPM desfasada +180 ms respecto a los beats múltiplos de 600 ms de A.
        val echoB = kickWithSubBass(16.0, bpm = 100.0, phaseMs = 5_000.0 + 180.0 - 4_800.0)
        val echoRendered = DjBridgeRenderer.renderSliceBridge(lockedEchoRenderSpec, echoA, 0L, echoB, 0L, sampleRate = sr)
        assertTrue(
            "Tempo-locked FilterEcho should align B kick phase onto A's beat grid (got ${echoRendered.spec.kickShiftMs}ms)",
            kotlin.math.abs(kotlin.math.abs(echoRendered.spec.kickShiftMs) - 180.0) <= 25.0,
        )
        assertTrue("handoffBTrackMs should advance well past entryInMs", echoRendered.handoffBTrackMs >= 8_000L)
    }

    @Test
    fun lyricLoopCutRejectsIncompatibleTempoOrHarmonyAndKeepsNaturalTiming() {
        val sr = DjDsp.DJ_SAMPLE_RATE
        val outA = metricsWithBars("ll-a", bpm = 90.0, camelot = "8A", drumEnergy = 0.55)
        val inFast = metricsWithBars("ll-fast", bpm = 130.0, camelot = "8A", drumEnergy = 0.55)
        val inClash = metricsWithBars("ll-clash", bpm = 120.0, camelot = "3B", drumEnergy = 0.55)
        val out120 = metricsWithBars("ll-120", bpm = 120.0, camelot = "8A", drumEnergy = 0.55)

        val lyrA = Lyrics.Synced(
            lines = listOf(
                Line(
                    start = 152_000L,
                    end = 153_200L,
                    words = listOf(
                        Word(152_000L, 152_420L, "forever", false),
                        Word(152_420L, 152_820L, "more", false),
                    ),
                    real = false,
                ),
            ),
            source = "lrclib",
        )
        val lyrB = Lyrics.Synced(
            lines = listOf(
                Line(
                    start = 16_000L,
                    end = 17_200L,
                    words = listOf(
                        Word(16_000L, 16_420L, "forever", false),
                        Word(16_420L, 16_820L, "more", false),
                    ),
                    real = false,
                ),
            ),
            source = "lrclib",
        )

        val tempoMismatch = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = outA,
            incomingAudio = inFast,
            outgoingLyrics = lyrA,
            incomingLyrics = lyrB,
            isDjSession = true,
        )
        assertNotEquals(DjBridgeStyle.LyricLoopCut, tempoMismatch.style)

        val harmonyClash = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = out120,
            incomingAudio = inClash,
            outgoingLyrics = lyrA,
            incomingLyrics = lyrB,
            isDjSession = true,
        )
        assertNotEquals(DjBridgeStyle.LyricLoopCut, harmonyClash.style)

        // Cuando sí coinciden tempo y armonía, se renderiza 1.0x rítmico sin chipmunk y sin NaNs.
        val validSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = out120,
            incomingAudio = metricsWithBars("ll-in-ok", bpm = 122.0, camelot = "8A", drumEnergy = 0.55),
            outgoingLyrics = lyrA,
            incomingLyrics = lyrB,
            isDjSession = true,
        )
        assertEquals(DjBridgeStyle.LyricLoopCut, validSpec.style)
        val pcmA = synthStereoFloat(20.0, bpm = 120.0, freqHz = 440.0, sr = sr)
        val pcmB = synthStereoFloat(20.0, bpm = 122.0, freqHz = 330.0, sr = sr)
        val originA = (validSpec.preRollStartMs - 1_500L).coerceAtLeast(0L)
        val originB = (validSpec.entryInMs - 2_000L).coerceAtLeast(0L)
        val rendered = DjBridgeRenderer.renderSliceBridge(validSpec, pcmA, originA, pcmB, originB, sampleRate = sr)
        assertTrue(rendered.stereoPcm.none { it.isNaN() || it.isInfinite() })
        assertTrue(rendered.stereoPcm.maxOf { abs(it) } <= 1.0f)
        assertTrue(rendered.audibleSwitchMs > rendered.preRollMs)
    }

    @Test
    fun beatMatchRejectsHarmonyClashAndCoversScaledDryBResume() {
        val outA = metricsWithBars("bm-4a", bpm = 120.0, camelot = "4A", drumEnergy = 0.65, kickSteady = 0.85)
        val clashB = metricsWithBars("bm-10b", bpm = 124.0, camelot = "10B", drumEnergy = 0.65, kickSteady = 0.85)
        val clashSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = outA,
            incomingAudio = clashB,
            isDjSession = true,
        )
        assertNotEquals(DjBridgeStyle.BeatMatchLongMix, clashSpec.style)
        assertNotEquals(DjBridgeStyle.SmartFade, clashSpec.style)

        // En pareja compatible rápida (8A -> 9A), BeatMatchLongMix proyecta dryBResumeMs multiplicado por ratio + planeo.
        val goodB = metricsWithBars("bm-9a", bpm = 112.0, camelot = "9A", drumEnergy = 0.65, kickSteady = 0.85)
        val goodSpec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L,
            incomingDurationMs = 180_000L,
            configuredFadeMs = 8_000L,
            outgoingAudio = metricsWithBars("bm-8a", bpm = 120.0, camelot = "8A", drumEnergy = 0.65, kickSteady = 0.85),
            incomingAudio = goodB,
            isDjSession = true,
        )
        assertEquals(DjBridgeStyle.BeatMatchLongMix, goodSpec.style)
        assertTrue(
            "dryBResumeMs debe cubrir transitionDur*ratio + 1600ms + postRoll",
            goodSpec.dryBResumeMs - goodSpec.entryInMs >= (goodSpec.transitionDurationMs * goodSpec.tempoMatchRatio).toLong() + 1_600L,
        )
    }

    @Test
    fun energyDropCutPreservesBrightPickupAndDucksEchoBeforeDrop() {
        val sr = DjDsp.DJ_SAMPLE_RATE
        val spec = DjBridgeSpec(
            style = DjBridgeStyle.EnergyDropCut,
            preRollStartMs = 1_000L,
            cueOutStartMs = 2_000L,
            cueOutEndMs = 2_500L,
            entryInMs = 3_000L,
            dryBResumeMs = 5_000L,
            preRollMs = 1_000L,
            postRollMs = 1_000L,
            transitionDurationMs = 3_250L,
            bpmA = 120.0,
            bpmB = 120.0,
            pickupMs = 500L,
            silenceMs = 2_000L,
            echoDelayMs = 500.0,
            reverbRoom = 0.48f,
        )
        val framesA = sr * 6
        val brightA = FloatArray(framesA * 2) { i ->
            val t = (i / 2).toDouble() / sr
            (0.45 * sin(2.0 * PI * 3_500.0 * t)).toFloat()
        }
        val silentB = FloatArray(sr * 6 * 2)
        val rendered = DjBridgeRenderer.renderSliceBridge(spec, brightA, 0L, silentB, 0L, sampleRate = sr)

        fun rmsWindow(startSec: Double, endSec: Double): Double {
            val s = ((startSec * sr).toInt()).coerceIn(0, rendered.totalFrames)
            val e = ((endSec * sr).toInt()).coerceIn(s + 1, rendered.totalFrames)
            var sum = 0.0
            for (f in s until e) {
                val x = rendered.stereoPcm[f * 2].toDouble()
                sum += x * x
            }
            return kotlin.math.sqrt(sum / (e - s))
        }

        val pickupRms = rmsWindow(1.05, 1.45)
        val echoBloomRms = rmsWindow(1.70, 2.40)
        val breathRms = rmsWindow(3.25, 3.45)

        assertTrue("Pickup debe mantener brillo intacto sin apagar (pickupRms=$pickupRms)", pickupRms >= 0.25)
        assertTrue("Eco ping-pong + reverb debe sonar con cuerpo tras el corte (echoBloomRms=$echoBloomRms)", echoBloomRms >= 0.025)
        assertTrue("El respiro final (-36 dB) debe quedar muy por debajo del eco (breath=$breathRms vs echo=$echoBloomRms)", breathRms < echoBloomRms * 0.12)
    }

    private object EmptyCatalog : Catalog {
        override suspend fun artist(id: String): ArtistInfo? = null
        override suspend fun songs(info: ArtistInfo): List<Track> = emptyList()
        override suspend fun radio(id: String): List<Track> = emptyList()
        override suspend fun find(name: String): String? = null
    }
}
