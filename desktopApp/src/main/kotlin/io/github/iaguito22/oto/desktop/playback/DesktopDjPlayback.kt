package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.dj.ChorusSafety
import io.github.iaguito22.oto.dj.AdhdPlanner
import io.github.iaguito22.oto.dj.AdhdSegment
import io.github.iaguito22.oto.dj.SongSection
import io.github.iaguito22.oto.dj.SongSections
import io.github.iaguito22.oto.dj.DjTransitionPlan
import io.github.iaguito22.oto.dj.DjBridgeStyle

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
        outgoingSections: List<SongSection> = emptyList(),
    ): TransitionAction {
        if (!playing || buffering) return TransitionAction.None
        // Plan sin mezcla (no cabe sin pisar un estribillo): en DJ rápido el trozo sigue saliendo por su salida corta,
        // que ya respeta estribillos y uniones; sin trozo, hasta el final natural. Antes también el DJ rápido esperaba al
        // final y la canción sonaba entera (medido en el móvil el 17/11; Android ya sale por el trozo).
        if (plan?.bridgeSpec?.style == DjBridgeStyle.OutroTail) return TransitionAction.None
        if (plan != null && plan.fadeDurationMs <= 0L) {
            return if (adhdSegment != null && phraseSafeExit(adhdSegment, adhdSegment.endMs, outgoingSections, durationMs) < durationMs &&
                forcedExitDue(adhdSegment, positionMs, outgoingSections, durationMs)) TransitionAction.FinishTrackNow
            else TransitionAction.None
        }
        if (plan != null && positionMs < plan.outgoingFadeStartMs) return TransitionAction.None
        if (plan != null && crossfadeSupported && !ramSaver && nextReady && plan.fadeDurationMs > 0L) {
            if (positionMs >= plan.outgoingFadeStartMs && ChorusSafety.allowsLive(positionMs, plan.fadeDurationMs, 1.0, outgoingSections)) {
                return TransitionAction.StartCrossfade(plan.fadeDurationMs, plan)
            }
            if (adhdSegment != null && forcedExitDue(adhdSegment, positionMs, outgoingSections, durationMs)) {
                return TransitionAction.FinishTrackNow
            }
            return TransitionAction.None
        }
        if (adhdSegment != null) {
            val safeStart = SongSections.safeExitAt(adhdSegment.fadeOutStartMs, outgoingSections, durationMs, adhdSegment.mixOutDurationMs)
            val safeEnd = phraseSafeExit(adhdSegment, adhdSegment.endMs, outgoingSections, durationMs)
            val canMix = crossfadeSupported && !ramSaver && nextReady && adhdSegment.mixOutDurationMs > 0L &&
                safeStart + adhdSegment.mixOutDurationMs <= durationMs
            if (canMix && positionMs >= safeStart && !SongSections.overlapsChorus(positionMs, positionMs + adhdSegment.mixOutDurationMs, outgoingSections)) {
                return TransitionAction.StartCrossfade(adhdSegment.mixOutDurationMs)
            }
            if (safeEnd < durationMs && forcedExitDue(adhdSegment, positionMs, outgoingSections, durationMs)) {
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
        outgoingSections: List<SongSection> = emptyList(),
    ): Pair<Long, Long>? {
        if (!crossfadeSupported || ramSaver) return null
        if (plan != null) return if (plan.fadeDurationMs > 0L &&
            plan.bridgeSpec?.style != DjBridgeStyle.OutroTail
        ) plan.outgoingFadeStartMs to plan.fadeDurationMs else null
        if (adhdSegment != null) {
            val at = SongSections.safeExitAt(adhdSegment.fadeOutStartMs, outgoingSections, durationMs, adhdSegment.mixOutDurationMs)
            return if (at + adhdSegment.mixOutDurationMs <= durationMs) at to adhdSegment.mixOutDurationMs else null
        }
        val fadeMs = configuredFadeSeconds.coerceAtLeast(0) * 1000L
        if (fadeMs <= 0L || durationMs < fadeMs * 3L) return null
        return (durationMs - fadeMs) to fadeMs
    }

    /**
     * Salida forzada (sin mezcla) del trozo: fuera de estribillos y, si hay un límite de frase unos compases después,
     * en él, para que el salto no caiga a mitad de compás.
     */
    internal fun phraseSafeExit(seg: AdhdSegment, fromMs: Long, outgoingSections: List<SongSection>, durationMs: Long): Long =
        seg.forcedExitTargetMs(fromMs, outgoingSections, durationMs, 260L)

    /**
     * El objetivo sale del final PREVISTO (no de la posición: si no, cada lectura pasada de un límite buscaba el
     * siguiente y la salida se iba alejando). Llegado el objetivo, se sale en cuanto no se esté dentro de un estribillo.
     */
    internal fun forcedExitDue(seg: AdhdSegment, positionMs: Long, outgoingSections: List<SongSection>, durationMs: Long): Boolean =
        seg.forcedExitDue(positionMs, seg.endMs, outgoingSections, durationMs, 260L)

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
