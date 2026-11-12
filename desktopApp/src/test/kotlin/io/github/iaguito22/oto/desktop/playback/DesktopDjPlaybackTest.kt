package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.dj.AdhdPlanner
import io.github.iaguito22.oto.dj.AdhdSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopDjPlaybackTest {
    private val sampleSegment = AdhdSegment(
        startMs = 45_000L,
        endMs = 105_000L,
        clipDurationMs = 60_000L,
        fadeOutStartMs = 99_000L,
        mixOutDurationMs = 6_000L,
        fullDurationMs = 210_000L,
    )

    @Test
    fun effectiveAdhdCrossfadeRespectsRamSaverAndFallbackDefault() {
        assertEquals(0L, DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(configuredFadeSeconds = 8, ramSaver = true))
        assertEquals(8_000L, DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(configuredFadeSeconds = 8, ramSaver = false))
        assertEquals(AdhdPlanner.DEFAULT_MIX_MS, DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(configuredFadeSeconds = 0, ramSaver = false))
    }

    @Test
    fun adhdStartsCrossfadeAtFadeOutStartWhenMixReady() {
        val beforeFade = DesktopTransitionPlanner.evaluate(
            positionMs = 98_900L,
            durationMs = 210_000L,
            playing = true,
            buffering = false,
            configuredFadeSeconds = 6,
            ramSaver = false,
            crossfadeSupported = true,
            nextReady = true,
            adhdSegment = sampleSegment,
        )
        assertEquals(TransitionAction.None, beforeFade)

        val atFade = DesktopTransitionPlanner.evaluate(
            positionMs = 99_000L,
            durationMs = 210_000L,
            playing = true,
            buffering = false,
            configuredFadeSeconds = 6,
            ramSaver = false,
            crossfadeSupported = true,
            nextReady = true,
            adhdSegment = sampleSegment,
        )
        assertEquals(TransitionAction.StartCrossfade(6_000L), atFade)
    }

    @Test
    fun adhdFinishesClipAtEndMsWhenCrossfadeUnavailable() {
        val beforeEnd = DesktopTransitionPlanner.evaluate(
            positionMs = 100_000L,
            durationMs = 210_000L,
            playing = true,
            buffering = false,
            configuredFadeSeconds = 6,
            ramSaver = true,
            crossfadeSupported = true,
            nextReady = true,
            adhdSegment = sampleSegment,
        )
        assertEquals(TransitionAction.None, beforeEnd)

        val atEndRamSaver = DesktopTransitionPlanner.evaluate(
            positionMs = 105_000L,
            durationMs = 210_000L,
            playing = true,
            buffering = false,
            configuredFadeSeconds = 6,
            ramSaver = true,
            crossfadeSupported = true,
            nextReady = true,
            adhdSegment = sampleSegment,
        )
        assertEquals(TransitionAction.FinishTrackNow, atEndRamSaver)

        val atEndNextNotReady = DesktopTransitionPlanner.evaluate(
            positionMs = 105_000L,
            durationMs = 210_000L,
            playing = true,
            buffering = false,
            configuredFadeSeconds = 6,
            ramSaver = false,
            crossfadeSupported = true,
            nextReady = false,
            adhdSegment = sampleSegment,
        )
        assertEquals(TransitionAction.FinishTrackNow, atEndNextNotReady)
    }

    @Test
    fun normalModeRespectsDurationGuardAndFadeWindow() {
        val tooShort = DesktopTransitionPlanner.evaluate(
            positionMs = 12_000L,
            durationMs = 15_000L,
            playing = true,
            buffering = false,
            configuredFadeSeconds = 6,
            ramSaver = false,
            crossfadeSupported = true,
            nextReady = true,
            adhdSegment = null,
        )
        assertEquals(TransitionAction.None, tooShort)

        val readyToFade = DesktopTransitionPlanner.evaluate(
            positionMs = 175_000L,
            durationMs = 180_000L,
            playing = true,
            buffering = false,
            configuredFadeSeconds = 6,
            ramSaver = false,
            crossfadeSupported = true,
            nextReady = true,
            adhdSegment = null,
        )
        assertEquals(TransitionAction.StartCrossfade(6_000L), readyToFade)
    }

    @Test
    fun listenedDurationSubtractsAdhdIntroOffset() {
        val fastSkip = DesktopTransitionPlanner.listenedDurationMs(
            completed = false,
            positionMs = 52_000L,
            durationMs = 210_000L,
            adhdSegment = sampleSegment,
            startOffsetMs = 45_000L,
        )
        assertEquals(7_000L, fastSkip)

        val fullClip = DesktopTransitionPlanner.listenedDurationMs(
            completed = true,
            positionMs = 105_000L,
            durationMs = 210_000L,
            adhdSegment = sampleSegment,
            startOffsetMs = 45_000L,
        )
        assertEquals(60_000L, fullClip)
    }
}
