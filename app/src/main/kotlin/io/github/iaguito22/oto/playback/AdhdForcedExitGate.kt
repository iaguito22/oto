package io.github.iaguito22.oto.playback

import kotlin.math.abs

/**
 * Árbitro de la salida forzada del DJ rápido frente a Crossfade (sin tocar Crossfade): la salida sin mezcla solo se
 * autoriza cuando la condición «armada ([armAtMs]) y sin relevo» se mantiene durante [confirmMs] seguidos, con la posición
 * avanzando de forma continua (sin búsquedas) y el mismo final publicado por Crossfade. [confirmMs] cubre varias
 * vueltas de Crossfade (cada ≤500 ms): si en ese tiempo no empezó el relevo, lo ha descartado con los datos actuales.
 * Una búsqueda, un plan nuevo, un relevo o dejar de tocar salir reinician la cuenta.
 */
internal class AdhdForcedExitGate(
    private val confirmMs: Long = CONFIRM_MS,
    private val seekToleranceMs: Long = SEEK_TOLERANCE_MS,
) {
    private var dueSinceMs = -1L
    private var lastNowMs = 0L
    private var lastPositionMs = 0L
    private var lastPlannedExitMs: Long? = null
    private var seen = false

    fun reset() {
        dueSinceMs = -1L
        seen = false
    }

    /** true si ya se puede hacer la salida forzada. */
    fun observe(nowMs: Long, positionMs: Long, plannedExitMs: Long?, due: Boolean, handingOver: Boolean): Boolean {
        val continuous = seen &&
            abs((positionMs - lastPositionMs) - (nowMs - lastNowMs)) <= seekToleranceMs &&
            plannedExitMs == lastPlannedExitMs
        dueSinceMs = when {
            !due || handingOver -> -1L
            !continuous || dueSinceMs < 0L -> nowMs
            else -> dueSinceMs
        }
        seen = true
        lastNowMs = nowMs
        lastPositionMs = positionMs
        lastPlannedExitMs = plannedExitMs
        return dueSinceMs >= 0L && nowMs - dueSinceMs >= confirmMs
    }

    companion object {
        const val CONFIRM_MS = 1_500L
        const val SEEK_TOLERANCE_MS = 400L
        /** Holgura por la cadencia del monitor (consulta cada 250 ms). */
        const val ARM_SLACK_MS = 500L

        /**
         * Desde dónde se arma la salida forzada: con tiempo para confirmar ([CONFIRM_MS]) y hacer la bajada ([duckMs])
         * ANTES del objetivo, para que la confirmación no retrase el salto fuera de su frase.
         */
        fun armAtMs(targetMs: Long, duckMs: Long): Long = targetMs - CONFIRM_MS - duckMs - ARM_SLACK_MS

        /**
         * Dónde acaba la bajada y se salta. Si al confirmar aún cabe la bajada antes del objetivo, en el objetivo. Si
         * no (p. ej. se buscó más allá), en la siguiente frase segura desde donde acabaría la bajada ([retarget]).
         */
        fun jumpAtMs(targetMs: Long, positionMs: Long, duckMs: Long, retarget: (Long) -> Long): Long =
            if (positionMs <= targetMs - duckMs) targetMs else retarget(positionMs + duckMs)

        /** Margen por la cadencia de la bajada (pasos de 20 ms) para dar por llegado el punto de salto. */
        const val ARRIVAL_SLACK_MS = 150L

        /** Si ya se puede saltar: sonando y con la posición REAL en el punto de salto (no por tiempo de pared). */
        fun arrived(positionMs: Long, jumpAtMs: Long, playing: Boolean): Boolean =
            playing && positionMs >= jumpAtMs - ARRIVAL_SLACK_MS
    }
}

/**
 * Vigila que la reproducción siga de forma continua mientras hay una salida forzada pendiente (espera y bajada):
 * una pausa, un atasco o una búsqueda la invalidan, porque su punto de salto se calculó con la posición de antes.
 */
internal class PlaybackContinuity(private val toleranceMs: Long = AdhdForcedExitGate.SEEK_TOLERANCE_MS) {
    private var lastNowMs = 0L
    private var lastPositionMs = 0L
    private var seen = false

    /** false si desde la última lectura se pausó, se atascó o se buscó. */
    fun ok(nowMs: Long, positionMs: Long, playing: Boolean): Boolean {
        val continuous = playing && (!seen || abs((positionMs - lastPositionMs) - (nowMs - lastNowMs)) <= toleranceMs)
        seen = true
        lastNowMs = nowMs
        lastPositionMs = positionMs
        return continuous
    }
}
