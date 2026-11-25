package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.dj.SongSection
import io.github.iaguito22.oto.dj.AdhdPlanner
import io.github.iaguito22.oto.dj.AdhdSegment
import io.github.iaguito22.oto.dj.DjCrossfadeFallback
import io.github.iaguito22.oto.dj.DjTransitionPlan
import io.github.iaguito22.oto.dj.DjTransitionStyle
import io.github.iaguito22.oto.dj.DjBridgeSpec
import io.github.iaguito22.oto.dj.DjBridgeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun readyDeckCanMixEvenWhenAnalysisRejectedTheDjPlan() {
        val rejected = DjTransitionPlan(DjTransitionStyle.SmoothEqualPower, 0L, outgoingFadeStartMs = 210_000L, incomingStartMs = 0L)
        val previous = DesktopTransitionPlanner.evaluate(
            positionMs = 208_000L, durationMs = 210_000L, playing = true, buffering = false,
            configuredFadeSeconds = 6, ramSaver = false, crossfadeSupported = true,
            nextReady = true, adhdSegment = null, plan = rejected,
        )
        val window = DjCrossfadeFallback.window(210_000L, 210_000L, 6_000L, emptyList())
        assertEquals(TransitionAction.None, previous)
        // El fundido configurado (sin plan) no se recorta al respaldo de 3 s.
        assertEquals(TransitionAction.StartCrossfade(6_000L),
            DesktopTransitionPlanner.withFallback(TransitionAction.StartCrossfade(6_000L), window, 204_000L, true, null))
        assertEquals(TransitionAction.StartCrossfade(2_000L, fallback = true),
            DesktopTransitionPlanner.withFallback(previous, window, 208_000L, true, rejected))
        assertEquals(TransitionAction.None,
            DesktopTransitionPlanner.withFallback(previous, window, 208_000L, false, rejected))
    }

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

    /**
     * Noche del 23/11: en DJ rápido 16 de 48 mezclas salieron como fundido de respaldo de ~3 s. El plan puede mezclar
     * hasta 6 s después del final del trozo (para empezar en frase), pero el respaldo abría su ventana 3 s ANTES de ese
     * final y se adelantaba a un plan que aún iba a sonar.
     */
    @Test
    fun fallbackWaitsForAPlannedMixThatStartsAfterTheSegmentEnd() {
        val plan = DjTransitionPlan(DjTransitionStyle.EchoOutFilterSweep, 6_800L, outgoingFadeStartMs = 107_000L, incomingStartMs = 30_000L)
        val window = DjCrossfadeFallback.window(210_000L, sampleSegment.endMs, 6_000L, emptyList())!!
        val at = window.startMs + 500L
        val planned = DesktopTransitionPlanner.evaluate(
            positionMs = at, durationMs = 210_000L, playing = true, buffering = false,
            configuredFadeSeconds = 6, ramSaver = false, crossfadeSupported = true,
            nextReady = true, adhdSegment = sampleSegment, plan = plan,
        )
        assertEquals(TransitionAction.None, planned)
        assertEquals(TransitionAction.None, DesktopTransitionPlanner.withFallback(planned, window, at, true, plan))
        // Llegado su momento, la mezcla planificada sale tal cual.
        assertEquals(
            TransitionAction.StartCrossfade(6_800L, plan),
            DesktopTransitionPlanner.evaluate(
                positionMs = 107_000L, durationMs = 210_000L, playing = true, buffering = false,
                configuredFadeSeconds = 6, ramSaver = false, crossfadeSupported = true,
                nextReady = true, adhdSegment = sampleSegment, plan = plan,
            ),
        )
        // Si su momento pasa sin que pueda empezar (p. ej. un estribillo), el respaldo vuelve a valer.
        val longWindow = DjCrossfadeFallback.Window(106_000L, 112_000L)
        assertEquals(
            TransitionAction.StartCrossfade(4_500L, fallback = true),
            DesktopTransitionPlanner.withFallback(TransitionAction.None, longWindow, 107_500L, true, plan),
        )
    }

    @Test
    fun fallbackReasonSaysWhyThePlanDidNotMix() {
        assertEquals("sin plan (la siguiente aún se preparaba)", DesktopTransitionPlanner.fallbackReason(null, 100_000L))
        val noMix = DjTransitionPlan(DjTransitionStyle.SmoothEqualPower, 0L, outgoingFadeStartMs = 210_000L, incomingStartMs = 0L, reason = "Sin mezcla: estribillo")
        assertEquals("el plan no mezcla: Sin mezcla: estribillo", DesktopTransitionPlanner.fallbackReason(noMix, 100_000L))
        val late = DjTransitionPlan(DjTransitionStyle.SmoothEqualPower, 6_000L, outgoingFadeStartMs = 150_000L, incomingStartMs = 0L)
        assertEquals("el plan no pudo empezar a 150 s", DesktopTransitionPlanner.fallbackReason(late, 152_000L))
    }

    /**
     * Noche del 23/11: al empezar una mezcla se apuntaba la canción entera (25 min de más en 137 de DJ normal). Ahora
     * cuenta hasta la mitad de la mezcla, como el móvil (que apunta donde estaba al cambiar).
     */
    @Test
    fun mixOutCountsUntilMiddleOfTheMixNotTheWholeSong() {
        // DJ normal: sale a 127,2 s de 179,6 con 8 s de mezcla.
        assertEquals(
            131_200L,
            DesktopTransitionPlanner.mixOutListenedMs(127_200L, 8_000L, 179_600L, adhdSegment = null, startOffsetMs = 0L),
        )
        // Entró a mitad (plan del DJ): cuenta desde ahí.
        assertEquals(
            111_200L,
            DesktopTransitionPlanner.mixOutListenedMs(127_200L, 8_000L, 179_600L, adhdSegment = null, startOffsetMs = 20_000L),
        )
        // DJ rápido: desde el principio del trozo, no el trozo entero.
        assertEquals(
            40_000L,
            DesktopTransitionPlanner.mixOutListenedMs(82_000L, 6_000L, 210_000L, adhdSegment = sampleSegment, startOffsetMs = 45_000L),
        )
        // Nunca más que la canción.
        assertEquals(
            179_600L,
            DesktopTransitionPlanner.mixOutListenedMs(178_000L, 8_000L, 179_600L, adhdSegment = null, startOffsetMs = 0L),
        )
    }

    @Test
    fun transitionPlanDrivesBeatSnappedCrossfadeStartAndEchoOut() {
        val plan = DjTransitionPlan(
            style = DjTransitionStyle.BeatMatchLongMix,
            fadeDurationMs = 7_500L,
            incomingTempoNudge = 1.03,
            outgoingFadeStartMs = 171_200L,
            incomingStartMs = 1_200L,
            incomingGainMatch = 0.92f,
            useBassSwap = true,
        )
        val beforeSnap = DesktopTransitionPlanner.evaluate(
            positionMs = 171_000L,
            durationMs = 180_000L,
            playing = true,
            buffering = false,
            configuredFadeSeconds = 6,
            ramSaver = false,
            crossfadeSupported = true,
            nextReady = true,
            adhdSegment = null,
            plan = plan,
        )
        assertEquals(TransitionAction.None, beforeSnap)

        val atSnap = DesktopTransitionPlanner.evaluate(
            positionMs = 171_250L,
            durationMs = 180_000L,
            playing = true,
            buffering = false,
            configuredFadeSeconds = 6,
            ramSaver = false,
            crossfadeSupported = true,
            nextReady = true,
            adhdSegment = null,
            plan = plan,
        )
        assertEquals(TransitionAction.StartCrossfade(7_500L, plan), atSnap)

        val echoPlan = plan.copy(
            style = DjTransitionStyle.EchoOutFilterSweep,
            fadeDurationMs = 2_800L,
            outgoingFadeStartMs = 98_000L,
            useHighPassSweep = true,
            echoOutWet = 0.55f,
        )
        val adhdEcho = DesktopTransitionPlanner.evaluate(
            positionMs = 98_100L,
            durationMs = 210_000L,
            playing = true,
            buffering = false,
            configuredFadeSeconds = 4,
            ramSaver = false,
            crossfadeSupported = true,
            nextReady = true,
            adhdSegment = sampleSegment,
            plan = echoPlan,
        )
        assertEquals(TransitionAction.StartCrossfade(2_800L, echoPlan), adhdEcho)
    }

    @Test
    fun rapidSegmentTimerCannotOverrideALaterSafePlanButANoMixPlanStillLeavesAtTheSegment() {
        fun evaluate(plan: DjTransitionPlan) = DesktopTransitionPlanner.evaluate(
            positionMs = 106_000L, durationMs = 210_000L, playing = true, buffering = false,
            configuredFadeSeconds = 6, ramSaver = false, crossfadeSupported = true,
            nextReady = true, adhdSegment = sampleSegment, plan = plan,
        )
        val delayed = DjTransitionPlan(style = DjTransitionStyle.SmoothEqualPower,
            fadeDurationMs = 6_000L, outgoingFadeStartMs = 125_000L, incomingStartMs = 0L)
        assertEquals(TransitionAction.None, evaluate(delayed))
        // Sin mezcla, el trozo sale por su salida corta (antes sonaba hasta el final de la canción).
        assertEquals(TransitionAction.FinishTrackNow, evaluate(delayed.copy(fadeDurationMs = 0L)))
        assertEquals(null, DesktopTransitionPlanner.mixWindow(210_000L, 6, false, true,
            sampleSegment, delayed.copy(fadeDurationMs = 0L)))
    }

    @Test
    fun aNoMixPlanStillWaitsForTheChorusToEndBeforeTheShortExit() {
        val noMix = DjTransitionPlan(style = DjTransitionStyle.SmoothEqualPower,
            fadeDurationMs = 0L, outgoingFadeStartMs = 210_000L, incomingStartMs = 0L)
        val chorus = listOf(SongSection(90_000L, 130_000L, 1.0))
        fun at(position: Long) = DesktopTransitionPlanner.evaluate(
            positionMs = position, durationMs = 210_000L, playing = true, buffering = false,
            configuredFadeSeconds = 6, ramSaver = false, crossfadeSupported = true,
            nextReady = true, adhdSegment = sampleSegment, plan = noMix, outgoingSections = chorus,
        )
        assertEquals(TransitionAction.None, at(106_000L))
        assertEquals(TransitionAction.None, at(130_200L))
        assertEquals(TransitionAction.FinishTrackNow, at(130_600L))
    }

    @Test
    fun rapidFallbackWithNoDeckOrRamSaverWaitsForLateLyrics() {
        val chorus = listOf(SongSection(90_000L, 130_000L, 1.0))
        for (ramSaver in listOf(false, true)) {
            fun at(position: Long) = DesktopTransitionPlanner.evaluate(
                positionMs = position, durationMs = 210_000L, playing = true, buffering = false,
                configuredFadeSeconds = 6, ramSaver = ramSaver, crossfadeSupported = true,
                nextReady = false, adhdSegment = sampleSegment, outgoingSections = chorus,
            )
            assertEquals(TransitionAction.None, at(106_000L))
            assertEquals(TransitionAction.None, at(130_200L))
            assertEquals(TransitionAction.FinishTrackNow, at(130_600L))
        }
    }

    @Test
    fun seekingPastTheSegmentEndIntoANewChorusDoesNotTriggerAForcedExit() {
        val oldSegment = sampleSegment.copy(endMs = 90_000L, fadeOutStartMs = 84_000L)
        val chorus = listOf(SongSection(100_000L, 130_000L, 1.0))
        fun at(position: Long) = DesktopTransitionPlanner.evaluate(
            positionMs = position, durationMs = 210_000L, playing = true, buffering = false,
            configuredFadeSeconds = 6, ramSaver = true, crossfadeSupported = true,
            nextReady = false, adhdSegment = oldSegment, outgoingSections = chorus,
        )
        assertEquals(TransitionAction.None, at(110_000L))
        assertEquals(TransitionAction.None, at(99_900L))
        assertEquals(TransitionAction.FinishTrackNow, at(130_600L))
    }

    @Test
    fun preparedPlanIsNotRepeatedlyStartedWhenSeekingIntoAChorus() {
        val plan = DjTransitionPlan(style = DjTransitionStyle.SmoothEqualPower,
            fadeDurationMs = 6_000L, outgoingFadeStartMs = 90_000L, incomingStartMs = 0L)
        assertEquals(TransitionAction.None, DesktopTransitionPlanner.evaluate(
            positionMs = 110_000L, durationMs = 210_000L, playing = true, buffering = false,
            configuredFadeSeconds = 6, ramSaver = false, crossfadeSupported = true,
            nextReady = true, adhdSegment = sampleSegment, plan = plan,
            outgoingSections = listOf(SongSection(100_000L, 130_000L, 1.0)),
        ))
    }

    /**
     * Corte en drop (Battlecry → Energy, 24/11): A se corta justo antes de su estribillo; lo que sigue del cruce (respiro y
     * entrada de B) ya no suena en A y no puede impedir que la mezcla empiece. Antes se miraba el cruce entero desde la
     * posición en vivo, que llega unos ms tarde: no empezaba nunca y A sonaba hasta el final.
     */
    @Test
    fun elCorteEnDropEmpiezaAunqueDetrasVengaElEstribillo() {
        val spec = DjBridgeSpec(
            style = DjBridgeStyle.EnergyDropCut, preRollStartMs = 96_200L, cueOutStartMs = 100_000L, cueOutEndMs = 102_000L,
            entryInMs = 60_000L, dryBResumeMs = 64_000L, transitionDurationMs = 3_250L,
        )
        val plan = DjTransitionPlan(DjTransitionStyle.EnergyDropCut, 3_250L, outgoingFadeStartMs = 100_000L, incomingStartMs = 60_000L, bridgeSpec = spec)
        val chorus = listOf(SongSection(102_300L, 130_000L, repeat = 1.0))
        for (late in listOf(0L, 100L, 250L)) {
            val action = DesktopTransitionPlanner.evaluate(
                positionMs = 100_000L + late, durationMs = 223_000L, playing = true, buffering = false, configuredFadeSeconds = 10,
                ramSaver = false, crossfadeSupported = true, nextReady = true, adhdSegment = null, plan = plan, outgoingSections = chorus,
            )
            assertTrue("con $late ms de retraso: $action", action is TransitionAction.StartCrossfade)
        }
        // Muy tarde (p. ej. tras volver atrás en la barra hasta justo antes del estribillo), ya no: cortaría en él.
        val tooLate = DesktopTransitionPlanner.evaluate(
            positionMs = 101_500L, durationMs = 223_000L, playing = true, buffering = false, configuredFadeSeconds = 10,
            ramSaver = false, crossfadeSupported = true, nextReady = true, adhdSegment = null, plan = plan, outgoingSections = chorus,
        )
        assertTrue("$tooLate", tooLate !is TransitionAction.StartCrossfade)
    }
}
