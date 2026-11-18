package io.github.iaguito22.oto.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjTransitionDiagnosticsRegressionTest {
    private val smooth = DjBridgeSpec(
        style = DjBridgeStyle.SmartFade,
        preRollStartMs = 83_000,
        cueOutStartMs = 90_000,
        cueOutEndMs = 96_000,
        entryInMs = 0,
        dryBResumeMs = 13_200,
        transitionDurationMs = 6_000,
    )

    @Test fun optedInSmoothFadeKeepsEvenExactTempoPhaseLockForTheLiveFallback() {
        fun plan(aligned: Boolean?, ratio: Double) = DjTransitionPlanner.fromBridgeSpec(
            smooth.copy(beatAligned = aligned, tempoMatchRatio = ratio), null, null,
        )
        assertFalse(plan(null, 1.0).phaseLock)
        assertFalse(plan(false, 1.0).phaseLock)
        assertTrue(plan(true, 1.0).phaseLock)
        assertEquals(1.0, plan(true, 1.0).outgoingSpeed, 0.0)
        assertEquals(1.0 / 1.04, plan(true, 1.04).outgoingSpeed, 1e-9)
    }

    @Test fun aZeroDurationPlanRetainsPlannerDiagnosticsWithoutAnExecutableWav() {
        val reason = "Sin mezcla: proteger el estribillo (ventana A 90–120 s, B 0–4 s)"
        val plan = DjTransitionPlanner.fromBridgeSpec(
            smooth.copy(transitionDurationMs = 0, reason = reason), null, null,
        )
        assertEquals(0L, plan.fadeDurationMs)
        assertEquals(reason, plan.reason)
        assertNull(plan.bridgeSpec)
    }
}
