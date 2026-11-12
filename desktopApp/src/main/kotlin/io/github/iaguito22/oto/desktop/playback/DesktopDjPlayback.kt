package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.dj.AdhdPlanner
import io.github.iaguito22.oto.dj.AdhdSegment

internal sealed interface TransitionAction {
    data object None : TransitionAction
    data class StartCrossfade(val durationMs: Long) : TransitionAction
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
    ): TransitionAction {
        if (!playing || buffering) return TransitionAction.None
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
