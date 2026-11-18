package io.github.iaguito22.oto.dj

import org.junit.Assert.*
import org.junit.Test

class ChorusSafetyTest {
    private val oldPlan = DjBridgePlanner.plan(180_000L, 180_000L, 10_000L,
        outgoingAdhd = AdhdSegment(0L, 100_000L, 100_000L, 90_000L, 10_000L, 180_000L), djMode = DjMode.Adhd)

    @Test fun preparedAndRenderedBridgesAreRejectedWhenChorusArrivesLate() {
        assertTrue(ChorusSafety.allows(oldPlan, emptyList(), emptyList()))
        val late = listOf(SongSection(oldPlan.cueOutStartMs - 1_000L, oldPlan.cueOutEndMs + 20_000L, 1.0))
        assertFalse(ChorusSafety.allows(oldPlan, late, emptyList()))
        assertFalse(ChorusSafety.allows(DjTransitionPlanner.fromBridgeSpec(oldPlan, null, null), late, emptyList()))
        assertFalse(ChorusSafety.allows(oldPlan.copy(cueOutStartMs = late[0].endMs + 100L), late, emptyList()))
    }

    @Test fun postRenderPhaseCorrectionCannotMoveBIntoAChorus() {
        val chorus = listOf(SongSection(20_000L, 40_000L, 1.0))
        val before = oldPlan.copy(entryInMs = 19_900L, kickShiftMs = 0.0)
        assertTrue(ChorusSafety.allows(before, emptyList(), chorus))
        assertFalse(ChorusSafety.allows(before.copy(kickShiftMs = 250.0), emptyList(), chorus))
    }

    @Test fun bufferingCanConsumeASafeLiveWindow() {
        val chorus = listOf(SongSection(97_000L, 120_000L, 1.0))
        assertTrue(ChorusSafety.allowsLive(90_000L, 6_000L, 1.0, chorus))
        assertFalse(ChorusSafety.allowsLive(92_000L, 6_000L, 1.0, chorus))
        assertFalse(ChorusSafety.allowsLive(90_000L, 6_000L, 1.25, chorus))
        assertTrue(ChorusSafety.allowsLive(120_600L, 6_000L, 1.25, chorus))
    }

    @Test fun forcedExitUsesActualPositionAfterSeekOrPollingOvershoot() {
        val chorus = listOf(SongSection(100_000L, 130_000L, 1.0))
        assertEquals(130_600L, SongSections.safeExitAt(maxOf(90_000L, 110_000L), chorus, 180_000L, 260L))
        assertEquals(130_600L, SongSections.safeExitAt(maxOf(90_000L, 99_900L), chorus, 180_000L, 260L))
    }
}
