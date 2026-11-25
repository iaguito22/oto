package io.github.iaguito22.oto.dj

import kotlin.math.roundToLong

object ChorusSafety {
    /**
     * Lo que aún suena de A durante el cruce de [plan]: en un puente, hasta su corte (lo de después, respiro y entrada, ya
     * es B); si no, el cruce entero. Mirar el cruce entero impedía un corte en drop justo antes del estribillo de A
     * (Battlecry → Energy, 24/11: en el PC no empezaba nunca; en el móvil se aplazaba hasta pasado el estribillo).
     */
    fun outgoingSpanMs(plan: DjTransitionPlan): Long =
        plan.bridgeSpec?.let { (it.cueOutEndMs - it.cueOutStartMs).coerceIn(0L, plan.fadeDurationMs) } ?: plan.fadeDurationMs

    fun allowsLive(sourcePositionMs: Long, fadeMs: Long, sourceSpeed: Double, outgoing: List<SongSection>): Boolean =
        !SongSections.overlapsChorus(sourcePositionMs, sourcePositionMs + (fadeMs * sourceSpeed).roundToLong(), outgoing)

    fun allows(spec: DjBridgeSpec, outgoing: List<SongSection>, incoming: List<SongSection>): Boolean {
        if (spec.transitionDurationMs <= 0L) return true
        val entry = spec.entryInMs + spec.kickShiftMs.roundToLong()
        val overlapStart = if (spec.style == DjBridgeStyle.OutroTail) {
            spec.outroIncomingOffsetMs?.let { spec.cueOutStartMs + it } ?: spec.cueOutEndMs
        } else spec.cueOutStartMs
        return (overlapStart == spec.cueOutEndMs ||
            !SongSections.overlapsChorus(overlapStart, spec.cueOutEndMs, outgoing)) &&
            incoming.none { it.isChorus && entry > it.startMs && entry < it.endMs }
    }

    fun allows(plan: DjTransitionPlan, outgoing: List<SongSection>, incoming: List<SongSection>): Boolean {
        if (plan.fadeDurationMs <= 0L) return true
        plan.bridgeSpec?.let { return allows(it, outgoing, incoming) }
        return !SongSections.overlapsChorus(plan.outgoingFadeStartMs,
            plan.outgoingFadeStartMs + (plan.fadeDurationMs * plan.outgoingSpeed).roundToLong(), outgoing) &&
            incoming.none { it.isChorus && plan.incomingStartMs > it.startMs && plan.incomingStartMs < it.endMs }
    }
}
