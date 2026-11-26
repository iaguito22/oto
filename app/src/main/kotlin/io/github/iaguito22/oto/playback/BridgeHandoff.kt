package io.github.iaguito22.oto.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** Relevo con reloj inyectable: una espera de red invalida la sincronía, aunque antes estuviese cuadrada. */
internal object BridgeHandoff {
    const val MAX_DRIFT_MS = 35L
    /** Con margen para otro ajuste, un relevo solo empieza así de cuadrado: 16 ms ya se oyen como un golpe doble. */
    const val TIGHT_DRIFT_MS = 10L
    const val LINEAR_SWAP_MS = 24L
    const val MAX_SWAP_MS = 48L
    const val ROOM_MARGIN_MS = 40L
    const val SAMPLE_GAP_MS = 12L
    const val SAMPLE_COUNT = 5
    const val CHECK_RESERVE_MS = MAX_SWAP_MS + ROOM_MARGIN_MS + 2L * SAMPLE_GAP_MS * (SAMPLE_COUNT - 1) + 24L
    const val RECOVERY_FADE_MS = 64L
    const val CONTINUE_DRIFT_MS = 150L
    private const val RECOVERY_WAIT_MS = 7_500L

    data class Snapshot(val ready: Boolean, val errorMs: Long, val remainingMs: Long)
    data class GateSnapshot(val playing: Boolean, val positionMs: Long, val revision: Long)
    enum class Result { Complete, Cancelled, NotReady, OutOfSync, NoRoom }
    enum class Recovery { Resumed, Continued, Loading, Ended, Cancelled }
    data class LiveSwap(val errorMs: Long, val durationMs: Long)

    /** La puerta puede abrirse para el relevo máximo; el relevo real debe acabar en el instante planeado. */
    suspend fun awaitLiveSwap(
        plannedEndMs: Long, positionMs: () -> Long, ready: () -> Boolean, cancelled: () -> Boolean,
        measureError: suspend () -> Long?, durationMs: (Long) -> Long, maxDriftMs: Long,
        measureMs: Long, toleranceMs: Long, nowMs: () -> Long, waitFrame: suspend () -> Unit,
    ): LiveSwap? {
        val deadline = nowMs() + (plannedEndMs - positionMs()).coerceAtLeast(0L) + 2_000L
        var lastPosition = positionMs()
        fun invalid(): Boolean {
            val at = positionMs()
            val failed = cancelled() || !ready() || at < lastPosition || nowMs() > deadline
            lastPosition = at
            return failed
        }
        while (true) {
            if (invalid()) return null
            // Contar también la toma de muestras: una suspensión larga DENTRO de la medida tampoco es válida.
            val measuredFrom = nowMs()
            val error = measureError() ?: return null
            // Medir y esperar el último frame ya consume measureMs+toleranceMs nominales.
            // Otro frame de holgura tolera jitter normal; sigue rechazando una medida congelada 200 ms.
            fun expired() = nowMs() - measuredFrom > measureMs + 2L * toleranceMs
            if (invalid() || expired() || abs(error) > maxDriftMs) return null
            val duration = durationMs(error).coerceAtLeast(1L)
            val startAt = plannedEndMs - duration
            if (positionMs() > startAt + toleranceMs) return null
            val measureAt = startAt - measureMs - toleranceMs
            if (positionMs() < measureAt) {
                // Consumir la reserva sobrante y medir OTRA VEZ junto al inicio, no usar el error previo al wait.
                while (positionMs() < measureAt) {
                    if (invalid()) return null
                    waitFrame()
                }
                continue
            }
            while (positionMs() < startAt) {
                if (invalid() || expired()) return null
                waitFrame()
            }
            if (invalid() || expired() || positionMs() > startAt + toleranceMs) return null
            return LiveSwap(error, duration)
        }
    }

    /**
     * Dónde están la que se va y la que entra durante un puente. Si la cola se corre entera (la radio quita historial
     * por delante al empezar cada canción), las dos se mueven a la vez: no es «cambió de canción». [onIncoming]: la
     * sesión ya saltó a la que entra.
     */
    class QueueSlots(var outgoing: Int, var incoming: Int) {
        var onIncoming = false

        fun follow(currentIndex: Int, currentId: String?, outgoingId: String, incomingId: String) {
            val expected = if (onIncoming) incoming else outgoing
            if (currentIndex == expected || currentId != (if (onIncoming) incomingId else outgoingId)) return
            val shift = currentIndex - expected
            outgoing += shift
            incoming += shift
        }
    }

    /** Lo cambia el listener incluso si BUFFERING -> READY sucede entre dos pasos de la corrutina. */
    class ContinuousPlayback {
        private var playing = false
        private var startedMs = 0L
        var revision = 0L
            private set
        // Una vez estable, sigue estable hasta una parada o salto: el pendiente del AudioTrack oscila unos ms y,
        // justo en el umbral, «estable» alternaba sí/no. La espera salía con el primer sí y la alineación abortaba
        // con el siguiente no (medido el 17/11: puente listo abandonado sin registro, 180 ms tras medir A).
        private var latched = false
        fun update(isPlaying: Boolean, nowMs: Long) {
            if (playing && !isPlaying) revision++
            if (isPlaying && !playing) startedMs = nowMs
            if (!isPlaying || !playing) latched = false
            playing = isPlaying
        }
        fun stable(nowMs: Long, pendingMs: Long?): Boolean {
            if (!playing) return false
            if (!latched && nowMs - startedMs >= (pendingMs ?: 600L).coerceIn(0L, 1_500L) + 200L) latched = true
            return latched
        }
    }

    /** Esperar al reloj recién recargado antes de medir o corregirlo, conservando el margen del WAV. */
    suspend fun awaitStablePlayback(
        stable: () -> Boolean, roomLeft: () -> Boolean, cancelled: () -> Boolean,
        nowMs: () -> Long, waitFrame: suspend () -> Unit,
    ): Boolean {
        val deadline = nowMs() + RECOVERY_WAIT_MS
        while (true) {
            if (cancelled() || !roomLeft()) return false
            if (stable()) return true
            if (nowMs() >= deadline) return false
            waitFrame()
        }
    }

    /** Drenar significa avance de audio continuo, no simplemente esperar tiempo de pared. */
    suspend fun awaitGateDrain(
        pendingMs: Long?, snapshot: () -> GateSnapshot, roomLeft: () -> Boolean,
        cancelled: () -> Boolean, nowMs: () -> Long, waitFrame: suspend () -> Unit,
    ): Result {
        if (cancelled()) return Result.Cancelled
        val start = snapshot()
        val required = (pendingMs ?: 600L).coerceIn(0L, 1_500L) + 80L
        val deadline = nowMs() + required + 2_000L
        while (true) {
            if (cancelled()) return Result.Cancelled
            val current = snapshot()
            if (!current.playing || current.revision != start.revision || current.positionMs < start.positionMs) return Result.NotReady
            if (!roomLeft()) return Result.NoRoom
            if (current.positionMs - start.positionMs >= required) return Result.Complete
            if (nowMs() >= deadline) return Result.NotReady
            waitFrame()
        }
    }

    /** Se mide DESPUÉS de abrir la puerta PCM; un pico del reloj BT se confirma con cinco muestras. */
    suspend fun transfer(
        snapshot: () -> Snapshot,
        cancelled: () -> Boolean,
        nowMs: () -> Long,
        waitFrame: suspend () -> Unit,
        waitSample: suspend () -> Unit,
        durationMs: (Long) -> Long,
        setGains: (outgoing: Float, incoming: Float) -> Unit,
        startDriftMs: Long = MAX_DRIFT_MS,
        onStartError: (Long) -> Unit = {},
    ): Result {
        var last = snapshot()
        fun invalid(): Result? = when {
            cancelled() -> Result.Cancelled
            !last.ready -> Result.NotReady
            last.remainingMs < ROOM_MARGIN_MS -> Result.NoRoom
            else -> null
        }
        suspend fun robustError(): Pair<Long?, Result?> {
            val errors = LongArray(SAMPLE_COUNT)
            for (i in errors.indices) {
                last = snapshot()
                invalid()?.let { return null to it }
                errors[i] = last.errorMs
                if (i < errors.lastIndex) waitSample()
            }
            errors.sort()
            return ((errors[1] + errors[2] + errors[3]) / 3.0).roundToLong() to null
        }
        val (initialError, failure) = robustError()
        if (failure != null) return failure
        onStartError(initialError!!)
        // Sin tocar ganancias: quien llama puede reajustar y volver a intentarlo.
        if (abs(initialError) > startDriftMs.coerceAtMost(MAX_DRIFT_MS)) return Result.OutOfSync
        val duration = durationMs(initialError).coerceAtLeast(1L)
        if (last.remainingMs < duration + ROOM_MARGIN_MS) return Result.NoRoom
        var start = nowMs()
        while (true) {
            last = snapshot()
            invalid()?.let { return it }
            if (abs(last.errorMs) > MAX_DRIFT_MS) {
                // Mantener las ganancias mientras se confirma el pico; READY y cancelación siguen siendo estrictos.
                val checkStarted = nowMs()
                val (confirmed, error) = robustError()
                if (error != null) return error
                if (abs(confirmed!!) > MAX_DRIFT_MS) return Result.OutOfSync
                // La confirmación mantiene el volumen: no saltar de q=0 a q=1 por el tiempo gastado en medir.
                start += nowMs() - checkStarted
            }
            val elapsed = (nowMs() - start).coerceAtLeast(0L)
            if (last.remainingMs < (duration - elapsed).coerceAtLeast(0L) + ROOM_MARGIN_MS) return Result.NoRoom
            val q = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            val linear = duration == LINEAR_SWAP_MS
            setGains(if (linear) 1f - q else cos(q * PI.toFloat() / 2f), if (linear) q else sin(q * PI.toFloat() / 2f))
            if (q >= 1f) return Result.Complete
            waitFrame()
        }
    }

    /** Deshacer una entrada incompleta sin saltar de volumen en el finally. */
    suspend fun restoreOutgoing(
        outgoingGain: Float, incomingGain: Float, cancelled: () -> Boolean,
        nowMs: () -> Long, waitFrame: suspend () -> Unit,
        setGains: (outgoing: Float, incoming: Float) -> Unit,
    ) {
        val start = nowMs()
        while (!cancelled()) {
            val q = ((nowMs() - start).toFloat() / LINEAR_SWAP_MS).coerceIn(0f, 1f)
            setGains(outgoingGain + (1f - outgoingGain) * q, incomingGain * (1f - q))
            if (q >= 1f) return
            waitFrame()
        }
    }

    /** Un fallo breve de B no debe cortar el WAV local mientras aún puede cubrir otro intento. */
    suspend fun exitWhileRoom(
        attempt: suspend () -> Result, roomLeft: () -> Boolean,
        restoreBridge: suspend () -> Unit, prepareRetry: suspend () -> Unit,
        cancelled: () -> Boolean, nowMs: () -> Long, waitFrame: suspend () -> Unit,
    ): Result {
        val deadline = nowMs() + RECOVERY_WAIT_MS
        while (true) {
            if (cancelled()) return Result.Cancelled
            val result = attempt()
            if (result == Result.Complete || result == Result.Cancelled) return result
            if (cancelled()) return Result.Cancelled
            if (!roomLeft() || nowMs() >= deadline) return result
            restoreBridge()
            if (cancelled()) return Result.Cancelled
            prepareRetry()
            if (cancelled()) return Result.Cancelled
            if (!roomLeft() || nowMs() >= deadline) return result
            waitFrame()
        }
    }

    /** Apagar el par desde sus ganancias reales; continuar B si es seguro o buscar el último punto del WAV. */
    suspend fun recover(
        outgoingGain: Float,
        incomingGain: Float = 0f,
        canContinueDestination: () -> Boolean = { false },
        bridgePositionMs: () -> Long,
        stopBridge: () -> Unit,
        destinationForBridge: (Long) -> Long,
        seekDestination: (Long) -> Unit,
        destinationReady: () -> Boolean,
        destinationPositionMs: () -> Long,
        destinationRevision: () -> Long = { 0L },
        destinationEnded: () -> Boolean = { false },
        cancelled: () -> Boolean,
        nowMs: () -> Long,
        waitFrame: suspend () -> Unit,
        setGains: (outgoing: Float, incoming: Float) -> Unit,
    ): Recovery {
        val start = nowMs()
        while (true) {
            if (cancelled()) return Recovery.Cancelled
            val q = ((nowMs() - start).toFloat() / RECOVERY_FADE_MS).coerceIn(0f, 1f)
            setGains(outgoingGain * (1f - q), incomingGain * (1f - q))
            if (q >= 1f) break
            waitFrame()
        }
        stopBridge()
        if (cancelled()) return Recovery.Cancelled
        // Con B ya estable y cerca, conservar su audio evita descargar otra vez en una red lenta.
        // El WAV ya está apagado: esta pequeña discontinuidad no solapa dos copias desfasadas.
        val continued = canContinueDestination() && destinationReady()
        if (!continued) {
            // Leer DESPUÉS de apagar el WAV: no repetir lo que avanzó durante su salida.
            seekDestination(destinationForBridge(bridgePositionMs()).coerceAtLeast(0L))
        }
        val waitStart = nowMs()
        while (true) {
            if (cancelled()) return Recovery.Cancelled
            if (destinationEnded()) return Recovery.Ended
            if (destinationReady()) break
            if (nowMs() - waitStart >= RECOVERY_WAIT_MS) {
                setGains(0f, 1f)
                return Recovery.Loading
            }
            waitFrame()
        }
        var revision = destinationRevision()
        var lastPosition = destinationPositionMs()
        var lastTime = nowMs()
        var playedMs = 0L
        var rampPlaying = false
        val fadeDeadline = nowMs() + RECOVERY_WAIT_MS
        while (true) {
            if (cancelled()) return Recovery.Cancelled
            if (destinationEnded()) return Recovery.Ended
            val nextRevision = destinationRevision()
            val ready = destinationReady()
            val at = nowMs()
            val position = destinationPositionMs()
            if (!ready || nextRevision != revision || position < lastPosition) {
                revision = nextRevision
                playedMs = 0L
                rampPlaying = false
                setGains(0f, 0f)
            } else {
                if (rampPlaying) {
                    // El reloj sigue la salida audible. Un salto de AudioTimestamp no puede consumir de golpe la rampa.
                    playedMs += minOf((position - lastPosition).coerceAtLeast(0L), (at - lastTime).coerceAtLeast(0L))
                }
                rampPlaying = true
                val q = (playedMs.toFloat() / RECOVERY_FADE_MS).coerceIn(0f, 1f)
                setGains(0f, q)
                if (q >= 1f) return if (continued) Recovery.Continued else Recovery.Resumed
            }
            lastPosition = position
            lastTime = at
            if (at >= fadeDeadline) {
                setGains(0f, 1f)
                return Recovery.Loading
            }
            waitFrame()
        }
    }
}
