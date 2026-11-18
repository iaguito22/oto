package io.github.iaguito22.oto.dj

import kotlin.math.roundToLong

object ChorusSafety {
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
