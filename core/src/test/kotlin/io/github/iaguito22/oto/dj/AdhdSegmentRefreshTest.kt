package io.github.iaguito22.oto.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdhdSegmentRefreshTest {
    private val duration = 210_000L
    private val partial = AudioMetrics(
        trackId = "dj-refresh",
        durationMs = duration,
        analyzedMs = 60_000L,
        dropMs = 9_000L,
    )
    private val whole = partial.copy(analyzedMs = duration, dropMs = 85_000L)

    @Test
    fun fullAudioReplansTheQueuedEntryWithoutLyrics() {
        val queued = AdhdPlanner.segment(duration, audio = partial)!!
        assertTrue(queued.fromAudio)
        assertFalse(queued.fromLyrics)
        assertEquals(60_000L, queued.audioCoverageMs)
        assertEquals(3_500L, queued.startMs)
        assertFalse(queued.hasBetterAudio(partial))
        assertTrue(queued.hasBetterAudio(whole))

        val refreshed = AdhdPlanner.segment(duration, audio = whole)!!
        assertEquals(duration, refreshed.audioCoverageMs)
        assertEquals(79_500L, refreshed.startMs)
        assertFalse(refreshed.hasBetterAudio(partial))
        assertFalse(refreshed.hasBetterAudio(whole))
    }

    @Test
    fun decodedAudioImprovesALiveCurveWithTheSameCoverage() {
        val liveCurve = whole.copy(analyzedMs = 0L)
        val queued = AdhdPlanner.segment(duration, audio = liveCurve)!!
        assertEquals(duration, queued.audioCoverageMs)
        assertFalse(queued.hasBetterAudio(partial))
        assertTrue(queued.hasBetterAudio(whole))
        assertFalse(AdhdPlanner.segment(duration, audio = whole)!!.hasBetterAudio(liveCurve))
    }

    @Test
    fun fullAudioKeepsThePlayingEntryAndCanOnlyMoveTheExitForwardWithLead() {
        val playing = AdhdPlanner.segment(duration, audio = partial)!!.copy(entrySectionMs = 9_000L)
        val position = 85_000L
        assertTrue(playing.hasBetterAudio(whole))
        val pending = AdhdPlanner.segment(duration, audio = whole)!!
        assertNull(AdhdPlanner.liveExitUpdate(playing, pending, position))

        val refreshed = AdhdPlanner.segment(
            duration,
            audio = whole,
            fixedStartMs = playing.startMs,
            minFadeStartMs = AdhdPlanner.liveMinFadeStartMs(playing, position),
        )!!
        val accepted = AdhdPlanner.liveExitUpdate(playing, refreshed, position)!!
        assertEquals(playing.startMs, accepted.startMs)
        assertEquals(playing.entrySectionMs, accepted.entrySectionMs)
        assertTrue(accepted.endMs > playing.endMs)
        assertTrue(accepted.fadeOutStartMs >= position + AdhdPlanner.LIVE_EXIT_LEAD_MS)
        assertNull(AdhdPlanner.liveExitUpdate(playing, refreshed, position + 1_000L))
        assertNull(AdhdPlanner.liveExitUpdate(playing, playing.copy(endMs = playing.endMs - 1L), 0L))
    }

    @Test
    fun aFinalChorusCanUseTheCleanGapBeforeItWhenThereAreTooFewSections() {
        val chorus = SongSection(68_000L, 95_000L, 1.0)
        val finalChorus = SongSection(155_000L, duration - 1_000L, 1.0)
        val sections = listOf(chorus, finalChorus)
        val queued = AdhdPlanner.segment(duration, sections = sections, audio = whole)!!
        assertFalse(queued.fromLyrics)
        assertEquals(chorus.startMs, queued.startMs)
        assertTrue(queued.fadeOutStartMs >= chorus.endMs + SongSections.VOCAL_TAIL_MS)
        assertTrue(queued.endMs < finalChorus.startMs)
        assertTrue(queued.mixOutDurationMs >= 2_000L)
        assertFalse(SongSections.overlapsChorus(queued.fadeOutStartMs, queued.endMs, sections))
    }

    @Test
    fun aPartialBeatGridKeepsLyricBoundariesBeyondItsCoverage() {
        val bars = (0L until duration step 2_000L).map { t -> BarFeature(t, t + 2_000L, localBpm = 120.0) }
        val audio = partial.copy(bpm = 120.0, bpmConfidence = 0.85, bars = bars)
        val laterChorus = SongSection(80_000L, 102_000L, 1.0)
        val map = PhraseMap.of(duration, listOf(laterChorus), audio)
        assertTrue(map.gridTrusted)
        assertTrue(map.points.any { it.ms == laterChorus.startMs && it.kind == PhraseKind.SectionStart })
        assertTrue(map.points.any { it.ms == laterChorus.endMs + SongSections.VOCAL_TAIL_MS && it.kind == PhraseKind.SectionEnd })
        assertTrue(map.points.none { it.ms >= partial.analyzedMs && it.kind == PhraseKind.Grid })
    }

    @Test
    fun aStartAlreadyPastTheOutroKeepsTheSegmentInsideTheSource() {
        val position = duration - 1_000L
        val playing = AdhdPlanner.segment(
            duration,
            audio = whole.copy(outroStartMs = 155_000L),
            fixedStartMs = position,
            minFadeStartMs = position + AdhdPlanner.LIVE_EXIT_LEAD_MS,
        )!!
        assertEquals(position, playing.startMs)
        assertEquals(duration, playing.fadeOutStartMs)
        assertEquals(duration, playing.endMs)
        assertEquals(0L, playing.mixOutDurationMs)
    }
}
