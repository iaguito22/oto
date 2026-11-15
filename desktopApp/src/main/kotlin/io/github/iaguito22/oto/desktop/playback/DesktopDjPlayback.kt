package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.dj.AdhdPlanner
import io.github.iaguito22.oto.dj.AdhdSegment
import io.github.iaguito22.oto.dj.DjTransitionPlan

internal sealed interface TransitionAction {
    data object None : TransitionAction
    data class StartCrossfade(val durationMs: Long, val plan: DjTransitionPlan? = null) : TransitionAction
    data object FinishTrackNow : TransitionAction
}

/**
 * Planifica cuando arrancar la mezcla entre pistas (modo normal y DJ rapido ADHD)
 * y calcula el tiempo real escuchado dentro del segmento activo.
 */
internal object DesktopTransitionPlanner {
    fun effectiveAdhdCrossfadeMs(configuredFadeSeconds: Int, ramSaver: Boolean): Long {
        if (ramSaver) return 0L
        val configured = configuredFadeSeconds.coerceAtLeast(0) * 1000L
        return if (configured > 0L) configured else AdhdPlanner.DEFAULT_MIX_MS
    }

    fun evaluate(
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        buffering: Boolean,
        configuredFadeSeconds: Int,
        ramSaver: Boolean,
        crossfadeSupported: Boolean,
        nextReady: Boolean,
        adhdSegment: AdhdSegment?,
        plan: DjTransitionPlan? = null,
    ): TransitionAction {
        if (!playing || buffering) return TransitionAction.None
        if (plan != null && crossfadeSupported && !ramSaver && nextReady && plan.fadeDurationMs > 0L) {
            if (positionMs >= plan.outgoingFadeStartMs) {
                return TransitionAction.StartCrossfade(plan.fadeDurationMs, plan)
            }
            if (adhdSegment != null && positionMs >= adhdSegment.endMs) {
                return TransitionAction.FinishTrackNow
            }
            return TransitionAction.None
        }
        if (adhdSegment != null) {
            val canMix = crossfadeSupported && !ramSaver && nextReady
            if (canMix && positionMs >= adhdSegment.fadeOutStartMs) {
                return TransitionAction.StartCrossfade(adhdSegment.mixOutDurationMs)
            }
            if (positionMs >= adhdSegment.endMs) {
                return TransitionAction.FinishTrackNow
            }
            return TransitionAction.None
        }
        val fadeMs = if (ramSaver) 0L else configuredFadeSeconds.coerceAtLeast(0) * 1000L
        if (fadeMs <= 0L || !crossfadeSupported || !nextReady) return TransitionAction.None
        if (durationMs < fadeMs * 3L) return TransitionAction.None
        if (durationMs - positionMs <= fadeMs) return TransitionAction.StartCrossfade(fadeMs)
        return TransitionAction.None
    }

    /**
     * Dónde empezará la mezcla y cuánto durará, con las mismas reglas que [evaluate]: el plan si lo hay, si no el
     * trozo del DJ rápido, si no el fundido al final. null si no habrá mezcla.
     */
    fun mixWindow(
        durationMs: Long,
        configuredFadeSeconds: Int,
        ramSaver: Boolean,
        crossfadeSupported: Boolean,
        adhdSegment: AdhdSegment?,
        plan: DjTransitionPlan?,
    ): Pair<Long, Long>? {
        if (!crossfadeSupported || ramSaver) return null
        if (plan != null && plan.fadeDurationMs > 0L) return plan.outgoingFadeStartMs to plan.fadeDurationMs
        if (adhdSegment != null) return adhdSegment.fadeOutStartMs to adhdSegment.mixOutDurationMs
        val fadeMs = configuredFadeSeconds.coerceAtLeast(0) * 1000L
        if (fadeMs <= 0L || durationMs < fadeMs * 3L) return null
        return (durationMs - fadeMs) to fadeMs
    }

    fun listenedDurationMs(
        completed: Boolean,
        positionMs: Long,
        durationMs: Long,
        adhdSegment: AdhdSegment?,
        startOffsetMs: Long,
    ): Long {
        if (completed) {
            return adhdSegment?.clipDurationMs ?: durationMs.coerceAtLeast(positionMs)
        }
        val base = adhdSegment?.startMs ?: startOffsetMs
        return (positionMs - base).coerceAtLeast(0L)
    }
}
