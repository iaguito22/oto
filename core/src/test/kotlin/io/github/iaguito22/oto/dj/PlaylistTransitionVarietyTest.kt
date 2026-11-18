package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.lyrics.Line
import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.lyrics.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTransitionVarietyTest {
    private fun audio(id: String, bpm: Double, lift: Boolean = false, voice: Double = 0.05): AudioMetrics {
        val bars = (0L until 180_000L step 2_000L).map { t ->
            val before = lift && t == 158_000L
            val peak = lift && t == 160_000L
            BarFeature(
                startMs = t, endMs = t + 2_000L, localBpm = bpm,
                lufs = if (before) -14.0 else -10.0,
                lowRms = 0.4, midRms = 0.3, highRms = 0.2,
                kickSteady = 0.85, drumEnergy = if (before) 0.3 else if (peak) 0.8 else 0.65,
                harshness = 0.15, vocalRatio = voice, kickOnBeat = 0.85,
            )
        }
        return AudioMetrics(
            trackId = id, bpm = bpm, bpmConfidence = 0.9, beatPhaseMs = 0L, downbeatPhaseMs = 0L,
            rmsDb = -10.0, integratedLufs = -10.0, camelot = "8A", keyConfidence = 0.9,
            beatStrength = 0.8, firstSoundMs = 0L, lastSoundMs = 180_000L, durationMs = 180_000L,
            analyzedMs = 180_000L, beatGrid = (0L until 180_000L step (60_000.0 / bpm).toLong()).toList(), bars = bars,
        )
    }

    private fun plan(a: AudioMetrics, b: AudioMetrics) = DjBridgePlanner.plan(
        outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 8_000L,
        outgoingAudio = a, incomingAudio = b, isDjSession = false,
        recentStyles = listOf(DjBridgeStyle.SmartFade, DjBridgeStyle.FilterEcho, DjBridgeStyle.SmartFade, DjBridgeStyle.FilterEcho),
    )

    @Test
    fun playlistCanCutToAnEnergeticOpeningWithoutSkippingTheIntro() {
        val s = plan(audio("a", 66.0, lift = true), audio("b", 98.0))
        assertEquals(s.reason, DjBridgeStyle.EnergyDropCut, s.style)
        assertEquals(0L, s.entryInMs)
        assertTrue(s.cueOutEndMs in 159_000L until 160_000L)
        assertTrue(ChorusSafety.allows(s, emptyList(), emptyList()))
    }

    @Test
    fun playlistStillRejectsABlindDropOverDenseVocals() {
        val s = plan(audio("a", 66.0, lift = true, voice = 0.8), audio("b", 98.0))
        assertNotEquals(DjBridgeStyle.EnergyDropCut, s.style)
        assertTrue(s.transitionDurationMs > 0L)
    }

    @Test
    fun playlistCanChooseBeatMixWithReliableCompatibleRhythm() {
        val s = plan(audio("a", 120.0), audio("b", 122.0))
        assertEquals(s.reason, DjBridgeStyle.BeatMatchLongMix, s.style)
        assertTrue(s.entryInMs < 4_000L)
        assertTrue(DjTempoMatch.isStretched(s.tempoMatchRatio))
    }

    @Test
    fun playlistCanUseASharedVocalOpeningButNeverSkipsTheIntroToFindIt() {
        fun lyrics(start: Long) = Lyrics.Synced(
            lines = listOf(Line(start = start, end = start + 900L,
                words = listOf(Word(start, start + 700L, "fuego", true)), real = true)), source = "test",
        )
        for (entry in listOf(0L, 950L, 8_000L)) {
            val s = DjBridgePlanner.plan(
                outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 8_000L,
                outgoingAudio = audio("a", 120.0), incomingAudio = audio("b", 120.0),
                outgoingLyrics = lyrics(160_000L), incomingLyrics = lyrics(entry), isDjSession = false,
            )
            if (entry < 1_500L) {
                assertEquals(s.reason, DjBridgeStyle.LyricLoopCut, s.style)
                assertEquals(0L, s.entryInMs)
            } else {
                assertNotEquals(DjBridgeStyle.LyricLoopCut, s.style)
                assertTrue(s.entryInMs <= 4_000L)
            }
        }
    }

    @Test
    fun aStrongOpeningCanFollowAMeasuredInstrumentalBreak() {
        val a = audio("a", 98.0)
        val withBreak = a.copy(bars = a.bars.map {
            if (it.startMs >= 176_000L) it.copy(lufs = -15.5, drumEnergy = 0.19) else it
        })
        val spec = plan(withBreak, audio("b", 124.0))
        assertEquals(spec.reason, DjBridgeStyle.EnergyDropCut, spec.style)
        assertTrue(spec.reason.contains("descanso instrumental"))
        assertEquals(0L, spec.entryInMs)
        assertTrue(spec.cueOutEndMs in 175_700L..176_300L)
    }

    @Test
    fun beatMixCanKeepTheFirstSyllableWhenTheChorusStartsOnTheBeat() {
        val a = audio("a", 120.0)
        val b = audio("b", 120.0)
        val incomingChorus = listOf(SongSection(0L, 24_000L, 1.0))
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 180_000L, incomingDurationMs = 180_000L, configuredFadeMs = 8_000L,
            outgoingAudio = a, incomingAudio = b, incomingVocals = incomingChorus, isDjSession = false,
            recentStyles = listOf(DjBridgeStyle.SmartFade, DjBridgeStyle.FilterEcho, DjBridgeStyle.SmartFade, DjBridgeStyle.FilterEcho),
        )
        assertEquals(spec.reason, DjBridgeStyle.BeatMatchLongMix, spec.style)
        assertEquals(0L, spec.entryInMs)
        assertEquals(0L..0L, spec.kickShiftRangeMs)
    }
}
