package io.github.iaguito22.oto.playback

import io.github.iaguito22.oto.dj.DjTransitionPlan
import io.github.iaguito22.oto.dj.DjTransitionStyle
import org.junit.Assert.*
import org.junit.Test

class MixUiPublicationTest {
    private val pair = "A" to "B"

    @Test fun waitingForDurationKeepsPreparingNotice() {
        val pending = MixUi(-1, -1, null, MixUiState.Preparing, pair)
        assertEquals(pending, publishedMixUi(pair, true, pending))
    }

    @Test fun liveTransitionsKeepTheirOwnNameWithoutABridge() {
        for (label in listOf("Bucle y roll", "Filtro + eco")) {
            val live = MixUi(1000, 4000, null, MixUiState.Live, pair, label)
            assertEquals(label, publishedMixUi(pair, true, live)?.styleLabel)
        }
    }

    @Test fun changedQueueWaitsForItsOwnPlan() {
        val old = MixUi(1000, 4000, null, MixUiState.Ready, "A" to "C")
        val result = publishedMixUi(pair, true, old)
        assertEquals(pair, result?.pair)
        assertEquals(MixUiState.Preparing, result?.state)
        assertNull(result?.styleLabel)
    }

    @Test fun disabledAndRepeatOneHideWithoutDestroyingCachedPlan() {
        val ready = MixUi(1000, 4000, null, MixUiState.Ready, pair)
        assertNull(publishedMixUi(pair, false, ready))
        assertEquals(ready, publishedMixUi(pair, true, ready))
        assertNull(publishedMixUi(null, true, ready))
    }

    @Test fun rejectedPairStaysHiddenAndPendingPairShowsPreparingEvenWhileMixing() {
        val rejected = MixUi(-1, -1, null, MixUiState.None, pair)
        assertNull(publishedMixUi(pair, true, rejected))
        assertEquals(MixUiState.Preparing, publishedMixUi(pair, true, null)?.state)
    }

    @Test fun repeatAllWrapAndShufflePublishPreparationForActualNext() {
        for ((current, next) in listOf(1 to -1, -1 to 0)) {
            val allowed = mixNext(current, next)
            assertFalse(allowed)
            assertNull(publishedMixUi(pair, allowed, null))
        }
        for ((current, next) in listOf(3 to 0, 1 to 4, 1 to 2)) {
            assertEquals(MixUiState.Preparing, publishedMixUi(pair, mixNext(current, next), null)?.state)
        }
    }

    @Test fun reenableWhilePausedInvalidatesDisabledDecisionAndDedup() {
        val cache = MixUiPublication()
        val enabled = MixUiConfiguration(true, 10_000, "Normal")
        val disabled = MixUiConfiguration(false, 0, "Normal")
        assertTrue(cache.configure(enabled))
        cache.latest = MixUi(1000, 4000, null, MixUiState.Ready, pair)
        assertFalse(cache.configure(enabled))
        assertNotNull(cache.latest)
        assertTrue(cache.configure(disabled))
        // Crossfade's disabled decision, followed by pause: no further step to replace it.
        cache.latest = MixUi(-1, -1, null, MixUiState.None, pair)
        assertTrue(cache.configure(enabled))
        assertEquals(MixUiState.Preparing, publishedMixUi(pair, true, cache.latest)?.state)
        // A classic fade cached while disabled must be invalidated as well.
        cache.configure(disabled)
        cache.latest = MixUi(1000, 4000, null, MixUiState.Live, pair, "Fundido suave")
        assertTrue(cache.configure(enabled))
        assertNull(cache.latest)
    }

    @Test fun lateBridgeCannotReplaceCommittedLiveNoticeOrItsTiming() {
        val live = MixUi(1000, 4000, null, MixUiState.Live, pair, "Bucle y roll")
        val bridge = MixUi(2000, 6000, null, MixUiState.Ready, pair, "Cuadre de beats")
        val pending = bridge.copy(state = MixUiState.Preparing)
        assertEquals(live, selectedMixUi(live, pending, bridge, null, true, true))
        assertEquals(live, selectedMixUi(live, pending, null, null, true, true))
    }

    @Test fun lateRenderAndLoadedBridgeFollowSelectedPath() {
        val live = MixUi(1000, 4000, null, MixUiState.Live, pair, "Filtro + eco")
        val ready = MixUi(2000, 6000, null, MixUiState.Ready, pair, "Cuadre de beats")
        val pending = ready.copy(state = MixUiState.Preparing)
        assertEquals(live, selectedMixUi(live, pending, null, null, false, false))
        assertEquals(pending, selectedMixUi(live, pending, null, null, false, true))
        assertEquals(ready, selectedMixUi(live, pending, ready, null, false, true))
        val loaded = ready.copy(startMs = 1500, styleLabel = "Fundido suave")
        assertEquals(loaded, selectedMixUi(live, pending, ready, loaded, false, true))
    }

    @Test fun bridgeAvailabilityUsesTheSameAlignmentBudgetBeforeAndAfterPreroll() {
        fun offset(position: Long) = bridgeStartOffsetMs(position, 10_000, 5000, 1200, 250)
        assertEquals(0L, offset(9000))
        assertNotNull(offset(10_000)) // Past the old pre-roll margin, still usable.
        assertEquals(3800L, offset(13_550)) // Last usable position.
        assertNull(offset(13_551))
        assertNull(offset(30_000)) // A slow render must never announce Ready here.
        val live = MixUi(12_000, 15_000, null, MixUiState.Live, pair, "Filtro + eco")
        val pending = live.copy(state = MixUiState.Preparing)
        val ready = live.copy(state = MixUiState.Ready)
        val usable = offset(30_000) != null
        assertEquals(live, selectedMixUi(live, pending, ready.takeIf { usable }, null, false, usable))
    }

    @Test fun pausedDjModeAndShuffleChangesRepublishTheCurrentEligibility() {
        val cache = MixUiPublication()
        cache.configure(MixUiConfiguration(true, 10_000, "Adhd"))
        cache.latest = MixUi(1000, 4000, null, MixUiState.Ready, pair)
        assertTrue(cache.configure(MixUiConfiguration(false, 0, "Normal")))
        assertNull(publishedMixUi(pair, false, cache.latest))
        assertTrue(cache.configure(MixUiConfiguration(true, 10_000, "Adhd")))
        assertEquals(MixUiState.Preparing, publishedMixUi(pair, true, cache.latest)?.state)
        cache.latest = MixUi(1000, 4000, null, MixUiState.Ready, pair)
        assertEquals(cache.latest, publishedMixUi(pair, mixNext(0, 3), cache.latest))
        assertEquals(cache.latest, publishedMixUi(pair, mixNext(0, 1), cache.latest))
    }

    @Test fun liveNameTracksThePlanExecutedAfterAnalysisChanges() {
        val initial = DjTransitionPlan(DjTransitionStyle.EchoOutFilterSweep, 8000,
            outgoingFadeStartMs = 100_000, incomingStartMs = 0)
        val analyzed = initial.copy(style = DjTransitionStyle.LoopRollOut)
        val pending = MixUi(100_000, 104_000, null, MixUiState.Preparing, pair)
        fun selected(plan: DjTransitionPlan): MixUi = selectedMixUi(
            liveMixUi(pair, plan.outgoingFadeStartMs, plan.fadeDurationMs, plan, true),
            pending, null, null, committedLive = true, canPrepare = true)
        assertEquals("Filtro + eco", selected(initial).styleLabel)
        assertEquals("Bucle y roll", selected(analyzed).styleLabel)
    }

    @Test fun smartPlanNotKnownDoesNotInventAProgressMarker() {
        val pending = liveMixUi(pair, 100_000, 10_000, null, true)
        assertEquals(MixUiState.Preparing, pending.state)
        assertEquals(-1L, pending.startMs)
        assertNull(pending.styleLabel)
        assertEquals("Fundido suave", liveMixUi(pair, 100_000, 10_000, null, false).styleLabel)
    }

    @Test fun provisionalLivePlanWaitsForWholeAnalysisBeforeNamingTheStyle() {
        fun audio(analyzed: Long) = io.github.iaguito22.oto.dj.AudioMetrics(trackId = "x", durationMs = 200_000, analyzedMs = analyzed)
        val half = DjTransitionPlan(DjTransitionStyle.EchoOutFilterSweep, 8000, outgoingFadeStartMs = 100_000, incomingStartMs = 0,
            outgoingAudio = audio(200_000), incomingAudio = audio(60_000))
        val whole = half.copy(incomingAudio = audio(200_000))
        val pending = MixUi(-1, -1, null, MixUiState.Preparing, pair)
        fun shown(plan: DjTransitionPlan, committed: Boolean = false) = selectedMixUi(
            liveMixUi(pair, plan.outgoingFadeStartMs, plan.fadeDurationMs, plan, true), pending, null, null,
            committedLive = committed, canPrepare = false, provisional = provisionalLivePlan(plan, true))
        assertEquals(MixUiState.Preparing, shown(half).state)
        assertNull(shown(half).styleLabel)
        assertEquals("Filtro + eco", shown(whole).styleLabel)
        // Once the live handoff is committed, its real name is shown even with partial analysis.
        assertEquals("Filtro + eco", shown(half, committed = true).styleLabel)
        // Classic fade (smart mix off) is never provisional.
        assertFalse(provisionalLivePlan(half, false))
        assertFalse(provisionalLivePlan(null, true))
    }
}
