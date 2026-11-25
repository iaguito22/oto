package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.dj.ChorusSafety
import io.github.iaguito22.oto.dj.AdhdPlanner
import io.github.iaguito22.oto.dj.AdhdSegment
import io.github.iaguito22.oto.dj.SongSection
import io.github.iaguito22.oto.dj.SongSections
import io.github.iaguito22.oto.dj.DjTransitionPlan
import io.github.iaguito22.oto.dj.DjBridgeStyle
import io.github.iaguito22.oto.dj.DjCrossfadeFallback

internal sealed interface TransitionAction {
    data object None : TransitionAction
    data class StartCrossfade(val durationMs: Long, val plan: DjTransitionPlan? = null, val fallback: Boolean = false) : TransitionAction
    data object FinishTrackNow : TransitionAction
}

/**
 * Planifica cuando arrancar la mezcla entre pistas (modo normal y DJ rapido ADHD)
 * y calcula el tiempo real escuchado dentro del segmento activo.
 */
/**
 * El corte en drop en el PC (Iago, 25/11: «debería dejar sonar el runner up del preestribillo, cortar justo en el peak y
 * no dejar sonar el drop/estribillo, y dejar un poco de silencio con eco y reverb hasta que entra la otra canción, en
 * plan dar incertidumbre»). Antes el PC lo hacía como un fundido de 3,5 s y se oía el drop de A mientras bajaba.
 * Todo va en ms de cada canción ([BandsCue]): A se corta en [cutMs] y queda su eco (a pulso) y una reverb que se apagan
 * antes de que entre B; B suena solo desde un respiro con filtro (riser) justo antes de [entryMs] y entra entera en él.
 */
internal data class DropCutScript(
    val cutMs: Long,
    val gapMs: Long,
    val entryMs: Long,
    /** Dónde arranca B (en silencio) para tener su riser. */
    val bStartMs: Long,
    val outgoing: List<BandsCue>,
    val incoming: List<BandsCue>,
) {
    /** Cuándo (posición de A) hay que arrancar B para que llegue a [entryMs] al acabar el silencio, si tarda [lagMs] en sonar. */
    fun resumeBAtMs(lagMs: Long): Long = cutMs + gapMs - (entryMs - bStartMs) - lagMs
}

internal object DesktopTransitionPlanner {
    /** El corte en drop del plan ([DjBridgeStyle.EnergyDropCut]) como cambios de mezcla en cada canción. */
    fun dropCutScript(spec: io.github.iaguito22.oto.dj.DjBridgeSpec): DropCutScript {
        val cut = spec.cueOutEndMs
        val pickup = spec.pickupMs.coerceAtLeast(250L)
        val gap = spec.silenceMs.coerceIn(400L, 3_200L)
        val bpm = when {
            spec.bpmA <= 20.0 -> 120.0
            spec.bpmA < 85.0 -> spec.bpmA * 2.0
            spec.bpmA > 168.0 -> spec.bpmA * 0.5
            else -> spec.bpmA
        }
        val beat = 60_000.0 / bpm
        // El eco repite el último beat antes del corte: cabe en lo que suena desde que empieza la mezcla.
        val echoDelay = minOf(spec.echoDelayMs.toLong(), pickup - 120L).coerceIn(200L, 950L).toInt()
        val breath = (beat * 0.9).toLong().coerceIn(200L, gap / 2)
        val riser = beat.toLong().coerceIn(300L, 700L).coerceAtMost(gap / 2)
        val fill = MixBands(echoWet = 0.001f, echoFeedback = 0.5f, echoDelayMs = echoDelay, reverbWet = 0.001f)
        // Como el puente del móvil (`DjBridgeRenderer.renderEnergyDropCut`: 0,72 eco + 0,22 reverb): la cola, por debajo del seco.
        val tail = MixBands(overall = 0f, echoWet = 0.6f, echoFeedback = 0.5f, echoDelayMs = echoDelay, reverbWet = 0.18f)
        val ducked = tail.copy(echoWet = 0.02f, reverbWet = 0.01f)
        val outgoing = listOf(
            BandsCue(cut - pickup - 50L, fill),
            BandsCue(cut, tail),
            BandsCue(cut + gap - breath, tail),
            BandsCue(cut + gap, ducked, ramp = true),
            BandsCue(cut + gap + 300L, ducked.copy(echoWet = 0f, reverbWet = 0f), ramp = true),
        )
        val entry = spec.entryInMs
        val riserFrom = entry - riser
        val bStart = (riserFrom - 250L).coerceAtLeast(0L)
        val incoming = buildList {
            add(BandsCue(0L, MixBands(overall = 0f)))
            if (riserFrom > bStart) {
                add(BandsCue(riserFrom, MixBands(overall = 0.12f, low = 0.15f, mid = 0.35f, high = 0.1f)))
                add(BandsCue(entry - 15L, MixBands(overall = 0.55f, low = 0.5f, mid = 1f, high = 1f), ramp = true))
            }
            add(BandsCue(entry, MixBands.NEUTRAL))
        }
        return DropCutScript(cut, gap, entry, bStart, outgoing, incoming)
    }

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
            if (positionMs >= plan.outgoingFadeStartMs && liveSafe(plan, positionMs, outgoingSections)) {
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

    /** El bucle de mezclas mira cada 100 ms: llegar hasta aquí de tarde no mueve la mezcla (el plan ya se comprobó desde su inicio). */
    const val LATE_OK_MS = 400L

    /** Si la mezcla de [plan] puede empezar en [positionMs] sin que A suene dentro de un estribillo. */
    internal fun liveSafe(plan: DjTransitionPlan, positionMs: Long, outgoingSections: List<SongSection>): Boolean {
        val from = if (positionMs - plan.outgoingFadeStartMs in 0L..LATE_OK_MS) plan.outgoingFadeStartMs else positionMs
        return ChorusSafety.allowsLive(plan, from, 1.0, outgoingSections)
    }

    fun withFallback(
        planned: TransitionAction,
        window: DjCrossfadeFallback.Window?,
        positionMs: Long,
        ready: Boolean,
        plan: DjTransitionPlan?,
    ): TransitionAction {
        if (window == null || !ready) return planned
        // Cualquier mezcla ya planificada (plan del DJ, trozo del DJ rápido o fundido configurado) va primero.
        if (planned is TransitionAction.StartCrossfade) return planned
        if (positionMs < window.startMs) return planned
        // Un plan que mezcla y aún no ha llegado a su momento manda: en DJ rápido puede empezar hasta 6 s después del
        // final del trozo (para caer en frase) y el respaldo, que abre 3 s antes de ese final, se le adelantaba
        // (16 de 48 mezclas la noche del 23/11). Pasado su momento sin poder empezar, el respaldo vuelve a valer.
        if (plan != null && plan.fadeDurationMs > 0L && plan.bridgeSpec?.style != DjBridgeStyle.OutroTail &&
            positionMs < plan.outgoingFadeStartMs
        ) return planned
        val remaining = window.endMs - positionMs
        return if (remaining >= 100L) TransitionAction.StartCrossfade(remaining, fallback = true) else planned
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

    /** Por qué una mezcla sale como fundido de respaldo (para el registro). */
    fun fallbackReason(plan: DjTransitionPlan?, positionMs: Long): String = when {
        plan == null -> "sin plan (la siguiente aún se preparaba)"
        plan.fadeDurationMs <= 0L || plan.bridgeSpec?.style == DjBridgeStyle.OutroTail -> "el plan no mezcla: ${plan.reason}"
        positionMs >= plan.outgoingFadeStartMs -> "el plan no pudo empezar a ${plan.outgoingFadeStartMs / 1000} s"
        else -> "el plan empezaba a ${plan.outgoingFadeStartMs / 1000} s"
    }

    /**
     * Lo oído de la saliente cuando empieza una mezcla en [mixStartMs] de [mixLengthMs]: hasta la mitad del cruce (ahí
     * ya manda la entrante), desde donde empezó a sonar. Antes se apuntaba la canción (o el trozo) entera aunque la
     * mezcla saliera mucho antes; el móvil ya apuntaba la posición al cambiar.
     */
    fun mixOutListenedMs(
        mixStartMs: Long,
        mixLengthMs: Long,
        durationMs: Long,
        adhdSegment: AdhdSegment?,
        startOffsetMs: Long,
    ): Long {
        val until = (mixStartMs + mixLengthMs.coerceAtLeast(0L) / 2).let { if (durationMs > 0L) it.coerceAtMost(durationMs) else it }
        return listenedDurationMs(completed = false, positionMs = until, durationMs = durationMs, adhdSegment = adhdSegment, startOffsetMs = startOffsetMs)
    }
}
