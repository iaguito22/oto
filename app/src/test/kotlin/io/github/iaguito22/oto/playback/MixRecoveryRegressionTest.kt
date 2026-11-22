package io.github.iaguito22.oto.playback

import io.github.iaguito22.oto.dj.DjTransitionPlan
import io.github.iaguito22.oto.dj.DjTransitionStyle
import io.github.iaguito22.oto.dj.DjBridgeSpec
import io.github.iaguito22.oto.dj.DjBridgeStyle
import io.github.iaguito22.oto.dj.SongSection
import io.github.iaguito22.oto.dj.SongSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixRecoveryRegressionTest {
    private val pair = "RITMO" to 12
    private val attempt = MixAttemptRecovery()

    @Test fun lateBridgeCanStillUseAQuickerSimpleFade() {
        val window = io.github.iaguito22.oto.dj.DjCrossfadeFallback.Window(197_000L, 200_000L)
        assertNull(Crossfade.liveMixPreparationMs(197_250L, window.startMs, window.endMs, null, 200L, 600L))
        assertEquals(197_450L, Crossfade.fallbackDeckStartMs(197_250L, window, null, 200L, 600L))
        assertNull(Crossfade.fallbackDeckStartMs(199_400L, window, null, 200L, 600L))
    }

    @Test fun failedPairRearmsOnlyAfterARewindWithRoomAndNextPairsStartFresh() {
        attempt.watch(Triple(pair.first, pair.second, "No Lie"))
        attempt.skip()
        assertFalse(attempt.rearm(canPrepare = true)) // Pausing/resuming alone is not a rewind.

        attempt.seek(pair, 168_000, 169_000)
        assertFalse(attempt.rearm(canPrepare = true))
        attempt.seek(pair, 169_000, 164_000)
        assertFalse(attempt.rearm(canPrepare = liveAt(164_000, 165_200) != null))
        assertTrue(attempt.skipped)

        attempt.seek(pair, 164_000, 140_000)
        assertTrue(attempt.rearm(canPrepare = liveAt(140_000, 165_200) != null))
        assertFalse(attempt.skipped)
        assertFalse(attempt.rearm(canPrepare = true))

        attempt.skip()
        attempt.watch(Triple("No Lie", 13, "No Fue"))
        assertFalse(attempt.skipped)
        attempt.watch(Triple("No Fue", 14, "Sobrio"))
        assertFalse(attempt.skipped)
        assertFalse(attempt.rearm(canPrepare = true))
    }

    @Test fun aDifferentQueueOccurrenceOrIncomingTrackCannotInheritFailureOrRewind() {
        attempt.watch(Triple(pair.first, pair.second, "old B"))
        attempt.skip()
        attempt.seek(pair.first to 13, 168_000, 140_000)
        assertFalse(attempt.rearm(canPrepare = true))
        attempt.seek(pair, 168_000, 140_000)
        attempt.watch(Triple(pair.first, pair.second, "new B"))
        assertFalse(attempt.skipped)
        assertFalse(attempt.rearm(canPrepare = true))
    }

    @Test fun seekPastTheLiveGateCannotAdvertiseLiveEvenWithAPreparedDeck() {
        val ids = "Ghetto SpongeBob" to "B"
        val live = liveMixUi(ids, 83_630, 12_000, null, smart = false)
        val pending = live.copy(state = MixUiState.Preparing)
        for (deck in listOf(null, 78_000L)) {
            val canLive = liveAt(93_540, 83_630, deck) != null
            assertFalse(canLive)
            assertEquals(MixUiState.None, selectedMixUi(
                live, pending, ready = null, committedBridge = null, committedLive = deck != null,
                canPrepare = false, provisional = true, canLive = canLive,
            ).state)
        }
        val ready = live.copy(state = MixUiState.Ready)
        assertEquals(ready, selectedMixUi(live, pending, ready, null, false, false, canLive = false))
        assertEquals(pending, selectedMixUi(live, pending, null, null, false, true, canLive = false))
    }

    @Test fun latestDynamicStartStillReservesSettlingAndPcmGateTime() {
        val fadeStart = 165_200L
        val pending = 1_000L
        val lead = 200L
        val lastPosition = fadeStart - Crossfade.companionLiveGateLeadMs(pending) + Crossfade.FRAME_MS -
            (Crossfade.DYNAMIC_PREPARE_AHEAD_MS - lead + Crossfade.SETTLE_MS + Crossfade.ALIGN_MEASURE_MS)
        assertEquals(lastPosition + Crossfade.DYNAMIC_PREPARE_AHEAD_MS, liveAt(lastPosition, fadeStart))
        assertNull(liveAt(lastPosition + 1, fadeStart))
        assertNotNull(liveAt(lastPosition + 1, fadeStart, deckStart = lastPosition - 500))
    }

    @Test fun dropBridgeCanUseAPickupBeforeChorusEvenIfLiveFadeWouldMissTheSongEnd() {
        val duration = 172_981L
        val spec = DjBridgeSpec(
            style = DjBridgeStyle.EnergyDropCut,
            preRollStartMs = 114_716L,
            cueOutStartMs = 121_716L,
            cueOutEndMs = 122_216L,
            entryInMs = 500L,
            dryBResumeMs = 8_000L,
            transitionDurationMs = 3_076L,
        )
        val outgoing = listOf(SongSection(122_216L, 172_220L, repeat = 1.0))
        val liveStart = SongSections.safeExitAt(spec.cueOutStartMs, outgoing, duration, spec.transitionDurationMs)
        assertEquals(172_820L, liveStart)
        assertNull(Crossfade.liveMixPreparationMs(100_000L, liveStart, duration, null, 200L, 1_000L))
        assertTrue(spec.fitsSource(duration, outgoing, emptyList()))
        assertFalse(spec.copy(cueOutEndMs = 122_217L).fitsSource(duration, outgoing, emptyList()))
        assertFalse(spec.fitsSource(duration, outgoing, listOf(SongSection(0L, 1_000L, repeat = 1.0))))
    }

    @Test fun repeatedLateSafeExitPublishesOnceAndAZeroFadeRetainsItsReason() {
        val notice = MixUi(-1, -1, null, MixUiState.None, "A" to "B")
        val dedup = MixNoticeDedup()
        assertTrue(dedup.accept(notice, 3, "salida segura tarde"))
        repeat(120) { assertFalse(dedup.accept(notice, 3, "salida segura tarde")) }

        val reason = "Sin mezcla: proteger el estribillo (ventana A 90–120 s, B 0–4 s)"
        val plan = DjTransitionPlan(DjTransitionStyle.SmoothEqualPower, 0L,
            outgoingFadeStartMs = 120_000, incomingStartMs = 0, reason = reason)
        assertNull(plan.bridgeSpec)
        val diagnostic = plan.decisionDescription(120_000, 180_000)
        assertTrue(diagnostic.contains(reason))
        assertTrue(diagnostic.contains("B=0/180000 ms"))
        assertTrue(dedup.accept(notice, 3, reason))
        assertFalse(dedup.accept(notice, 3, reason))
        assertTrue(dedup.accept(notice, 5, reason))
    }

    @Test fun outroBridgeMustStillReachTheRealEndOfTheOutgoingTrack() {
        val spec = DjBridgeSpec(
            style = DjBridgeStyle.OutroTail,
            preRollStartMs = 172_350L,
            cueOutStartMs = 179_350L,
            cueOutEndMs = 180_000L,
            entryInMs = 0L,
            dryBResumeMs = 8_450L,
            transitionDurationMs = 1_900L,
            outroIncomingOffsetMs = 650L,
        )
        val chorus = listOf(SongSection(90_000L, 180_000L, repeat = 1.0))
        assertTrue(spec.fitsSource(180_000L, chorus, emptyList()))
        assertFalse(spec.fitsSource(182_000L, chorus, emptyList()))
    }

    private fun liveAt(position: Long, fadeStart: Long, deckStart: Long? = null): Long? =
        Crossfade.liveMixPreparationMs(position, fadeStart, fadeStart + 12_000, deckStart, 200, 1_000)
}
