package io.github.iaguito22.oto.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BridgeHandoffTest {
    private class Rig {
        var time = 0L
        var bridgePosition = 10_000L
        var stopped = false
        var cancelled = false
        var seek: Long? = null
        var incomingReady = true
        var ended = false
        var current = BridgeHandoff.Snapshot(true, 3L, 1_000L)
        val gains = mutableListOf<Pair<Float, Float>>()
        var afterTick: () -> Unit = {}
        suspend fun tick() {
            time += 8L
            if (!stopped) bridgePosition += 8L
            current = current.copy(remainingMs = current.remainingMs - 8L)
            afterTick()
        }
        var measured: Long? = null
        suspend fun transfer(startDriftMs: Long = BridgeHandoff.MAX_DRIFT_MS) = BridgeHandoff.transfer(
            snapshot = { current }, cancelled = { cancelled }, nowMs = { time }, waitFrame = ::tick, waitSample = ::tick,
            durationMs = { Crossfade.companionSwapMsFor(it) }, setGains = { a, b -> gains += a to b },
            startDriftMs = startDriftMs, onStartError = { measured = it },
        )
        suspend fun recover() = BridgeHandoff.recover(
            outgoingGain = 0.8f, bridgePositionMs = { bridgePosition }, stopBridge = { stopped = true },
            destinationForBridge = { it + 5_000L }, seekDestination = { seek = it },
            destinationPositionMs = { time }, destinationReady = { incomingReady }, destinationEnded = { ended }, cancelled = { cancelled }, nowMs = { time },
            waitFrame = ::tick, setGains = { a, b -> gains += a to b },
        )
    }

    @Test fun failedEntryReturnsSmoothlyFromItsActualGains() = runBlocking {
        val r = Rig()
        BridgeHandoff.restoreOutgoing(0.6f, 0.8f, { r.cancelled }, { r.time }, r::tick) { a, b -> r.gains += a to b }
        assertEquals(0.6f to 0.8f, r.gains.first())
        assertTrue(r.gains.any { it.first in 0.61f..0.99f && it.second in 0.01f..0.79f })
        assertEquals(1f to 0f, r.gains.last())
        assertEquals(24L, r.time)
    }

    @Test fun reloadedClockIsAllowedToWarmUpBeforeAlignment() = runBlocking {
        val clock = BridgeHandoff.ContinuousPlayback()
        var time = 0L
        clock.update(true, time)
        // Simula el seek de recolocación: a los 520 ms previos aún no es fiable con BT.
        var alignmentStartedAt: Long? = null
        val ready = BridgeHandoff.awaitStablePlayback(
            stable = { clock.stable(time, 1_000L) }, roomLeft = { time < 2_000L }, cancelled = { false },
            nowMs = { time }, waitFrame = { time += 20L },
        )
        if (ready) alignmentStartedAt = time
        assertEquals(1_200L, alignmentStartedAt)
    }

    @Test fun clockWarmupStopsBeforeUsingTimeReservedForTheGate() = runBlocking {
        var time = 0L
        val clock = BridgeHandoff.ContinuousPlayback().apply { update(true, time) }
        assertFalse(BridgeHandoff.awaitStablePlayback(
            stable = { clock.stable(time, 1_000L) }, roomLeft = { time < 600L }, cancelled = { false },
            nowMs = { time }, waitFrame = { time += 20L },
        ))
        assertEquals(600L, time)
    }

    @Test fun failureAfterOverlapReturnsToActualGainsWithoutAJump() = runBlocking {
        val r = Rig()
        r.afterTick = { if (r.time >= 48L) r.current = r.current.copy(ready = false) }
        assertEquals(BridgeHandoff.Result.NotReady, r.transfer())
        val actual = r.gains.last()
        assertTrue(actual.first > 0f && actual.second > 0f)
        r.gains.clear()
        BridgeHandoff.restoreOutgoing(actual.first, actual.second, { false }, { r.time }, r::tick) { a, b -> r.gains += a to b }
        assertEquals(actual, r.gains.first())
        assertEquals(1f to 0f, r.gains.last())
    }

    @Test fun recoveryAfterOverlapFadesBothFromTheirActualGains() = runBlocking {
        val r = Rig()
        r.afterTick = { if (r.time >= 48L) r.current = r.current.copy(ready = false) }
        assertEquals(BridgeHandoff.Result.NotReady, r.transfer())
        val actual = r.gains.last()
        assertTrue(actual.first > 0f && actual.second > 0f)
        r.gains.clear()
        assertEquals(BridgeHandoff.Recovery.Resumed, BridgeHandoff.recover(
            outgoingGain = actual.first, incomingGain = actual.second,
            bridgePositionMs = { r.bridgePosition }, stopBridge = { r.stopped = true },
            destinationForBridge = { it }, seekDestination = { r.seek = it }, destinationPositionMs = { r.time }, destinationReady = { true },
            cancelled = { false }, nowMs = { r.time }, waitFrame = r::tick,
            setGains = { a, b -> r.gains += a to b },
        ))
        assertEquals(actual, r.gains.first())
        val muted = r.gains.indexOf(0f to 0f)
        assertTrue(muted > 0)
        assertTrue(r.gains.take(muted).zipWithNext().all { (a, b) -> b.first <= a.first && b.second <= a.second })
        assertTrue(r.gains.drop(muted).all { it.first == 0f })
    }

    @Test fun healthyNearbyDestinationContinuesWithoutAnotherNetworkSeek() = runBlocking {
        val r = Rig()
        assertEquals(BridgeHandoff.Recovery.Continued, BridgeHandoff.recover(
            outgoingGain = 1.3f, canContinueDestination = { true },
            bridgePositionMs = { r.bridgePosition }, stopBridge = { r.stopped = true },
            destinationForBridge = { it }, seekDestination = { r.seek = it }, destinationPositionMs = { r.time }, destinationReady = { true },
            cancelled = { false }, nowMs = { r.time }, waitFrame = r::tick,
            setGains = { a, b -> r.gains += a to b },
        ))
        assertNull(r.seek)
        assertEquals(1.3f to 0f, r.gains.first())
        assertTrue(r.gains.none { it.first > 0f && it.second > 0f })
        assertEquals(0f to 1f, r.gains.last())
    }

    @Test fun confirmingClockNoiseCannotConsumeTheRemainingFadeRoom() = runBlocking {
        val r = Rig()
        r.current = r.current.copy(remainingMs = 120L)
        r.afterTick = { r.current = r.current.copy(errorMs = if (r.time == 40L) 110L else 3L) }
        assertEquals(BridgeHandoff.Result.NoRoom, r.transfer())
        assertTrue(r.gains.none { it.second == 1f })
    }

    @Test fun briefNetworkStallRetriesExitWhileLocalBridgeKeepsPlaying() = runBlocking {
        val r = Rig()
        r.current = r.current.copy(remainingMs = 3_000L)
        r.afterTick = { r.current = r.current.copy(ready = r.time < 48L || r.time >= 348L) }
        var attempts = 0
        val result = BridgeHandoff.exitWhileRoom(
            attempt = { attempts++; r.transfer() }, roomLeft = { r.current.remainingMs > 400L },
            restoreBridge = {
                val actual = r.gains.last()
                BridgeHandoff.restoreOutgoing(actual.first, actual.second, { false }, { r.time }, r::tick) { a, b -> r.gains += a to b }
            },
            prepareRetry = { while (!r.current.ready) r.tick() },
            cancelled = { false }, nowMs = { r.time }, waitFrame = r::tick,
        )
        assertEquals(BridgeHandoff.Result.Complete, result)
        assertEquals(2, attempts)
        assertTrue(r.time >= 348L)
        assertFalse(r.stopped)
        assertNull(r.seek)
        assertEquals(0f to 1f, r.gains.last())
        // Durante la carga B vuelve a cero y el WAV recupera su nivel, sin buscar ni parar.
        assertTrue(r.gains.drop(2).any { it == 1f to 0f })
    }

    @Test fun exitRetriesStopWhenTheLocalMarginRunsOut() = runBlocking {
        val r = Rig()
        r.current = r.current.copy(remainingMs = 200L)
        var attempts = 0
        assertEquals(BridgeHandoff.Result.NotReady, BridgeHandoff.exitWhileRoom(
            attempt = { attempts++; BridgeHandoff.Result.NotReady }, roomLeft = { r.current.remainingMs > 100L },
            restoreBridge = {}, prepareRetry = { repeat(16) { r.tick() } },
            cancelled = { false }, nowMs = { r.time }, waitFrame = r::tick,
        ))
        assertEquals(1, attempts)
        assertTrue(r.current.remainingMs <= 100L)
    }

    @Test fun rebufferDuringFinalFadeRestartsItAfterPlaybackReturns() = runBlocking {
        val r = Rig()
        val timeline = mutableListOf<Pair<Long, Float>>()
        val result = BridgeHandoff.recover(
            outgoingGain = 1f, canContinueDestination = { true },
            bridgePositionMs = { r.bridgePosition }, stopBridge = { r.stopped = true },
            destinationForBridge = { it }, seekDestination = { r.seek = it },
            destinationPositionMs = { r.time }, destinationReady = { r.time < 80L || r.time >= 240L },
            cancelled = { false }, nowMs = { r.time }, waitFrame = r::tick,
            setGains = { _, b -> timeline += r.time to b },
        )
        assertEquals(BridgeHandoff.Recovery.Continued, result)
        assertNull(r.seek)
        assertTrue(timeline.filter { it.first in 80L until 240L }.all { it.second == 0f })
        assertTrue(timeline.any { it.first > 240L && it.second in 0.01f..0.99f })
        assertTrue(r.time >= 304L)
        assertEquals(1f, timeline.last().second)
    }

    @Test fun permanentStallDuringFinalFadeHandsLoadingBackWithoutHanging() = runBlocking {
        val r = Rig()
        assertEquals(BridgeHandoff.Recovery.Loading, BridgeHandoff.recover(
            outgoingGain = 1f, canContinueDestination = { true },
            bridgePositionMs = { r.bridgePosition }, stopBridge = { r.stopped = true },
            destinationForBridge = { it }, seekDestination = { r.seek = it }, destinationPositionMs = { r.time }, destinationReady = { r.time < 80L },
            cancelled = { false }, nowMs = { r.time }, waitFrame = r::tick, setGains = { a, b -> r.gains += a to b },
        ))
        assertTrue(r.time >= 7_564L)
        assertNull(r.seek)
        assertEquals(0f to 1f, r.gains.last())
    }

    @Test fun rebufferBetweenFadeTicksAlsoRestartsItsRamp() = runBlocking {
        val r = Rig()
        assertEquals(BridgeHandoff.Recovery.Continued, BridgeHandoff.recover(
            outgoingGain = 1f, canContinueDestination = { true },
            bridgePositionMs = { r.bridgePosition }, stopBridge = { r.stopped = true },
            destinationForBridge = { it }, seekDestination = { r.seek = it }, destinationPositionMs = { r.time }, destinationReady = { true },
            destinationRevision = { if (r.time < 80L) 0L else 1L },
            cancelled = { false }, nowMs = { r.time }, waitFrame = r::tick, setGains = { _, _ -> },
        ))
        assertTrue(r.time >= 152L)
    }

    @Test fun readyButStationaryOutputCannotConsumeRecoveryFade() = runBlocking {
        val r = Rig()
        val timeline = mutableListOf<Pair<Long, Float>>()
        assertEquals(BridgeHandoff.Recovery.Continued, BridgeHandoff.recover(
            outgoingGain = 1f, canContinueDestination = { true },
            bridgePositionMs = { r.bridgePosition }, stopBridge = { r.stopped = true },
            destinationForBridge = { it }, seekDestination = { r.seek = it }, destinationReady = { true },
            destinationPositionMs = { (r.time - 400L).coerceAtLeast(0L) },
            cancelled = { false }, nowMs = { r.time }, waitFrame = r::tick,
            setGains = { _, b -> timeline += r.time to b },
        ))
        assertTrue(timeline.filter { it.first in 64L..400L }.all { it.second == 0f })
        assertTrue(timeline.any { it.first > 400L && it.second in 0.01f..0.99f })
        assertTrue(r.time >= 464L)
    }

    @Test fun audioTimestampJumpCannotCompleteRecoveryFadeInOneTick() = runBlocking {
        val r = Rig()
        val timeline = mutableListOf<Pair<Long, Float>>()
        assertEquals(BridgeHandoff.Recovery.Continued, BridgeHandoff.recover(
            outgoingGain = 1f, canContinueDestination = { true },
            bridgePositionMs = { r.bridgePosition }, stopBridge = { r.stopped = true },
            destinationForBridge = { it }, seekDestination = { r.seek = it }, destinationReady = { true },
            destinationPositionMs = { r.time + if (r.time >= 80L) 500L else 0L },
            cancelled = { false }, nowMs = { r.time }, waitFrame = r::tick,
            setGains = { _, b -> timeline += r.time to b },
        ))
        assertTrue(timeline.first { it.first == 80L }.second < 0.5f)
        assertEquals(128L, r.time)
    }

    // Medido en el Nothing: aceptado a ±8 ms al acabar de ajustar, y al relevar (tras drenar la puerta) ya iba a −16 ms.
    @Test fun withRoomToRealignAStartBeyondTheTightLimitIsRetriedWithoutTouchingGains() = runBlocking {
        val r = Rig()
        r.current = r.current.copy(errorMs = -16L)
        assertEquals(BridgeHandoff.Result.OutOfSync, r.transfer(BridgeHandoff.TIGHT_DRIFT_MS))
        assertEquals(-16L, r.measured)
        assertTrue(r.gains.isEmpty())
    }

    @Test fun withoutRoomTheSameStartStillHandsOverLoosely() = runBlocking {
        val r = Rig()
        r.current = r.current.copy(errorMs = -16L)
        assertEquals(BridgeHandoff.Result.Complete, r.transfer())
        assertEquals(0f, r.gains.last().first, 1e-4f)
        assertEquals(1f, r.gains.last().second, 1e-4f)
    }

    @Test fun tightLimitAcceptsAWellAlignedStart() = runBlocking {
        val r = Rig()
        r.current = r.current.copy(errorMs = 9L)
        assertEquals(BridgeHandoff.Result.Complete, r.transfer(BridgeHandoff.TIGHT_DRIFT_MS))
        assertEquals(9L, r.measured)
    }

    @Test fun retryFitsOnlyWithTimeForAPulseAndItsSettle() {
        // BT: 470 ms en el AudioTrack -> asentado 680 ms. Medida del relevo 48 + restaurar 24 + medir 48 + holgura,
        // y el reajuste (asentar 680 + 40 + medir 48 + pulso 320) dentro del límite recortado: 1100 ms ya no bastan
        // (Sol/Opus R1: la última medida caía fuera del límite y forzaba la recuperación).
        // El pulso, a la velocidad máxima: en la entrada se acelera el propio WAV (Sol R3).
        val need = 3 * Crossfade.ALIGN_MEASURE_MS + BridgeHandoff.LINEAR_SWAP_MS + Crossfade.RETRY_SLACK_MS +
            680L + 40L + (320L * (1f + Crossfade.BRIDGE_PULSE_MAX_NUDGE)).toLong() + Crossfade.RETRY_GUARD_MS
        assertFalse(Crossfade.companionRetryFits(wavPosMs = 13_000L, maxWavPosMs = 14_100L, pendingMs = 470L))
        assertTrue(Crossfade.companionRetryFits(wavPosMs = 13_000L, maxWavPosMs = 13_000L + need, pendingMs = 470L))
        assertFalse(Crossfade.companionRetryFits(wavPosMs = 13_001L, maxWavPosMs = 13_000L + need, pendingMs = 470L))
        // Más búfer pendiente pide más asentado.
        assertFalse(Crossfade.companionRetryFits(wavPosMs = 13_000L, maxWavPosMs = 13_000L + need, pendingMs = 900L))
    }

    // Ciclo entero: el intento estricto falla sin tocar ganancias, el reintento cabe y el último relevo es permisivo.
    @Test fun strictAttemptRetriesThenHandsOverLooselyWithinTheMargin() = runBlocking {
        val r = Rig()
        r.current = r.current.copy(errorMs = -16L, remainingMs = 3_000L)
        // El pulso sobre el propio WAV puede gastar hasta 1,25× su duración de posición.
        // Reservar ese avance también aquí, para que el primer intento siga siendo estricto.
        val maxWav = 1_600L
        assertTrue(Crossfade.companionRetryFits(r.time, maxWav, 470L))
        var retries = 0
        val tolerances = mutableListOf<Long>()
        val result = BridgeHandoff.exitWhileRoom(
            attempt = {
                val strict = Crossfade.companionRetryFits(r.time, maxWav, 470L)
                val tol = if (strict) BridgeHandoff.TIGHT_DRIFT_MS else BridgeHandoff.MAX_DRIFT_MS
                tolerances += tol
                r.transfer(tol)
            },
            roomLeft = { r.time < maxWav },
            restoreBridge = { repeat(3) { r.tick() } },
            // El reajuste gasta pulso + asentado sin pasar del límite y deja −14 ms (sigue fuera de lo estricto).
            prepareRetry = { retries++; while (r.time < 1_150L) r.tick(); r.current = r.current.copy(errorMs = -14L) },
            cancelled = { false }, nowMs = { r.time }, waitFrame = r::tick,
        )
        assertEquals(BridgeHandoff.Result.Complete, result)
        assertEquals(1, retries)
        assertEquals(listOf(BridgeHandoff.TIGHT_DRIFT_MS, BridgeHandoff.MAX_DRIFT_MS), tolerances)
        assertTrue(r.time < maxWav + 100L)
    }

    // Sol R2: la confirmación de 220 ms + medida no puede empezar si acaba pasado el límite que guarda puerta y relevo.
    @Test fun settledConfirmationOnlyStartsIfItEndsBeforeTheLimit() {
        val need = Crossfade.SETTLED_CONFIRM_GAP_MS + Crossfade.ALIGN_MEASURE_MS + Crossfade.FRAME_MS
        assertTrue(Crossfade.companionConfirmFits(wavPosMs = 1_000L, maxWavPosMs = 1_000L + need))
        assertFalse(Crossfade.companionConfirmFits(wavPosMs = 1_001L, maxWavPosMs = 1_000L + need))
        assertFalse(Crossfade.companionConfirmFits(wavPosMs = 1_000L, maxWavPosMs = 1_100L))
    }

    // Sol R2: el reajuste en vivo, con su asentado y medida fuera de plazo, acaba antes del cambio planeado.
    @Test fun liveRealignFinishesBeforeThePlannedChange() {
        for (left in listOf(900L, 1_100L, 1_300L, 1_800L)) {
            val budget = Crossfade.companionLiveRealignBudgetMs(left) ?: continue
            assertTrue(budget >= Crossfade.LIVE_REALIGN_MIN_NUDGE_MS)
            assertTrue(budget + Crossfade.SPEED_SETTLE_MS + Crossfade.ALIGN_MEASURE_MS + Crossfade.FRAME_MS +
                Crossfade.LIVE_REALIGN_SAFETY_MS <= left)
        }
        assertNull(Crossfade.companionLiveRealignBudgetMs(900L))
        assertNotNull(Crossfade.companionLiveRealignBudgetMs(1_800L))
    }

    // Sol R3: +90 ms con pulso de 420 ms a ~1,19× sobre el propio WAV gastaba ~500 ms de posición, no 420.
    @Test fun pulseOnTheBridgeItselfIsBudgetedAtItsSpeed() {
        val avail = 420L
        val room = Crossfade.companionPulseRoomMs(avail, sameClock = true)
        assertTrue(room * (1f + Crossfade.BRIDGE_PULSE_MAX_NUDGE) <= avail + 1f)
        assertEquals(avail, Crossfade.companionPulseRoomMs(avail, sameClock = false))
    }

    // Sol R3: la puerta en vivo se drenaba después del instante previsto (~0,6 s tarde con BT).
    @Test fun liveGateOpensEarlyEnoughToSwapOnTime() {
        val lead = Crossfade.companionLiveGateLeadMs(470L)
        assertTrue(lead >= 470L + Crossfade.GATE_MARGIN_MS + Crossfade.ALIGN_MEASURE_MS +
            Crossfade.companionSwapMsFor(Crossfade.LIVE_SOFT_ACCEPT_MS))
        assertTrue(Crossfade.companionLiveGateLeadMs(null) > Crossfade.companionLiveGateLeadMs(0L))
    }

    // Sol R4: incluir toda la entrada (también seek grueso y asentados), no solo el reajuste final.
    @Test fun initialLiveAlignmentLeavesRoomForBluetoothGateAndLongSwap() {
        val at = 20_000L
        var position = at - Crossfade.companionLivePrepareLeadMs()
        position += 2L * Crossfade.SETTLE_MS // arranque y posible recolocación
        val initialGate = at - Crossfade.companionLiveGateLeadMs(1_500L)
        val budget = Crossfade.companionLiveRealignBudgetMs(initialGate - position)!!
            .coerceAtMost(Crossfade.ALIGN_BUDGET_MS)
        assertEquals(Crossfade.ALIGN_BUDGET_MS, budget)
        // Incluso gastando el ajuste completo y su último asentado, la puerta aún no ha llegado.
        position += budget + Crossfade.SPEED_SETTLE_MS + Crossfade.ALIGN_MEASURE_MS + Crossfade.FRAME_MS
        assertTrue(position <= initialGate - Crossfade.LIVE_REALIGN_SAFETY_MS)
        for (pending in listOf(0L, 470L, 1_500L)) {
            val gateAt = at - Crossfade.companionLiveGateLeadMs(pending)
            for (error in listOf(0L, 16L, 120L, 150L, -150L)) {
                val end = maxOf(position, gateAt) + pending + Crossfade.GATE_MARGIN_MS +
                    Crossfade.ALIGN_MEASURE_MS + Crossfade.companionSwapMsFor(error) + Crossfade.FRAME_MS
                assertTrue("pending=$pending error=$error ends at $end, planned=$at", end <= at)
            }
        }
    }

    @Test fun lateLiveAlignmentIsLimitedBeforeItCanConsumeTheGateReserve() {
        val leftToGate = 1_900L
        val budget = Crossfade.companionLiveRealignBudgetMs(leftToGate)!!
        assertTrue(budget < 1_900L)
        assertTrue(budget + Crossfade.SPEED_SETTLE_MS + Crossfade.ALIGN_MEASURE_MS +
            Crossfade.FRAME_MS <= leftToGate - Crossfade.LIVE_REALIGN_SAFETY_MS)
    }

    // Sol R5: end<=at permitía adelantar B 276 ms. Comprobar ambos límites y la medida cercana al relevo.
    @Test fun liveSwapUsesOnlyItsRealDurationAndEndsAtThePlannedTime() = runBlocking {
        for (pending in listOf(0L, 470L, 1_500L)) {
            for (error in listOf(0L, 16L, 120L, 150L, -150L)) {
                val endAt = 20_000L
                var position = endAt - Crossfade.companionLiveGateLeadMs(pending) + pending + Crossfade.GATE_MARGIN_MS
                var lastMeasuredAt = 0L
                var measures = 0
                val swap = BridgeHandoff.awaitLiveSwap(
                    plannedEndMs = endAt, positionMs = { position }, ready = { true }, cancelled = { false },
                    measureError = { measures++; position += Crossfade.ALIGN_MEASURE_MS; lastMeasuredAt = position; error },
                    durationMs = Crossfade::companionSwapMsFor, maxDriftMs = Crossfade.LIVE_SOFT_ACCEPT_MS,
                    measureMs = Crossfade.ALIGN_MEASURE_MS, toleranceMs = Crossfade.FRAME_MS,
                    nowMs = { position }, waitFrame = { position += Crossfade.FRAME_MS },
                )!!
                val end = position + swap.durationMs
                assertTrue("pending=$pending error=$error end=$end", end in endAt..(endAt + Crossfade.FRAME_MS))
                assertTrue(position - lastMeasuredAt <= Crossfade.ALIGN_MEASURE_MS + Crossfade.FRAME_MS)
                if (error == 0L) assertTrue(measures >= 2)
            }
        }
    }

    private suspend fun liveWaitFailure(kind: String): BridgeHandoff.LiveSwap? {
        var time = 0L
        var measures = 0
        return BridgeHandoff.awaitLiveSwap(
            plannedEndMs = 500L, positionMs = { if (kind == "stalled") 0L else time },
            ready = { kind != "buffering" || time < 100L },
            cancelled = { kind == "cancelled" && time >= 100L },
            measureError = { measures++; time += 48L; if (kind == "drift" && measures > 1) 151L else 0L },
            durationMs = Crossfade::companionSwapMsFor, maxDriftMs = Crossfade.LIVE_SOFT_ACCEPT_MS,
            measureMs = 48L, toleranceMs = 16L, nowMs = { time }, waitFrame = { time += 16L },
        )
    }

    @Test fun liveSwapWaitCancelsWithoutTransferringWhenPlaybackIsInterrupted() = runBlocking {
        for (kind in listOf("buffering", "cancelled", "stalled", "drift")) {
            assertNull(kind, liveWaitFailure(kind))
        }
    }

    @Test fun temporaryAudioClockStallAfterLastMeasurementCannotUseItsOldError() = runBlocking {
        var time = 0L
        var position = 0L
        var measures = 0
        var stalled = false
        val swap = BridgeHandoff.awaitLiveSwap(
            plannedEndMs = 500L, positionMs = { position }, ready = { true }, cancelled = { false },
            measureError = { measures++; time += 48L; position += 48L; 0L },
            durationMs = Crossfade::companionSwapMsFor, maxDriftMs = Crossfade.LIVE_SOFT_ACCEPT_MS,
            measureMs = 48L, toleranceMs = 16L, nowMs = { time },
            waitFrame = {
                if (measures >= 2 && !stalled) {
                    // A conserva isPlaying pero su posición se para 200 ms; T sigue avanzando.
                    time += 216L
                    position += 16L // A reanuda y alcanza startAt, aunque T ya se adelantó 200 ms.
                    stalled = true
                } else {
                    time += 16L
                    position += 16L
                }
            },
        )
        assertTrue(stalled)
        assertNull(swap)
        assertTrue(position >= 476L)
        assertEquals(200L, time - position)
    }

    @Test fun schedulerPauseInsideMeasurementExpiresItToo() = runBlocking {
        var time = 0L
        assertNull(BridgeHandoff.awaitLiveSwap(
            plannedEndMs = 500L, positionMs = { time }, ready = { true }, cancelled = { false },
            measureError = { time += 200L; 0L }, durationMs = Crossfade::companionSwapMsFor,
            maxDriftMs = Crossfade.LIVE_SOFT_ACCEPT_MS, measureMs = 48L, toleranceMs = 16L,
            nowMs = { time }, waitFrame = { time += 16L },
        ))
    }

    @Test fun normalSchedulerJitterDoesNotExpireTheLiveSwapMeasurement() = runBlocking {
        // Cuatro delay(12) con 1–2ms extra cada uno y un delay(16) que vuelve en17ms.
        for (measureDuration in listOf(52L, 56L)) {
            for (error in listOf(0L, 16L, 120L, 150L)) {
                val endAt = 20_000L
                var time = endAt - Crossfade.companionLiveGateLeadMs(470L) + 470L + Crossfade.GATE_MARGIN_MS
                val swap = BridgeHandoff.awaitLiveSwap(
                    plannedEndMs = endAt, positionMs = { time }, ready = { true }, cancelled = { false },
                    measureError = { time += measureDuration; error }, durationMs = Crossfade::companionSwapMsFor,
                    maxDriftMs = Crossfade.LIVE_SOFT_ACCEPT_MS, measureMs = 48L, toleranceMs = 16L,
                    nowMs = { time }, waitFrame = { time += 17L },
                )
                assertNotNull("measurement=$measureDuration error=$error", swap)
                val end = time + swap!!.durationMs
                assertTrue("measurement=$measureDuration error=$error end=$end", end in endAt..(endAt + 16L))
            }
        }
    }

    @Test fun alignedPlayersTransferNormally() = runBlocking {
        val r = Rig()
        assertEquals(BridgeHandoff.Result.Complete, r.transfer())
        assertEquals(1f to 0f, r.gains.first())
        assertEquals(0f to 1f, r.gains.last())
    }

    @Test fun staleAlignmentAfterOpeningPcmGateIsRejected() = runBlocking {
        val r = Rig()
        // El alineador dejó 3 ms, pero durante la espera de la puerta la sesión perdió 250 ms.
        r.current = r.current.copy(errorMs = 253L)
        assertEquals(BridgeHandoff.Result.OutOfSync, r.transfer())
        assertTrue(r.gains.isEmpty())
    }

    @Test fun closePositionsWhileBufferingAreNotSynchronization() = runBlocking {
        val r = Rig()
        r.current = r.current.copy(ready = false, errorMs = 0L)
        assertEquals(BridgeHandoff.Result.NotReady, r.transfer())
        assertTrue(r.gains.isEmpty())
    }

    @Test fun stallDuringTransferStopsBeforeAnyMoreOverlap() = runBlocking {
        val r = Rig()
        r.afterTick = { if (r.time >= 40L) r.current = r.current.copy(ready = false) }
        assertEquals(BridgeHandoff.Result.NotReady, r.transfer())
        assertEquals(listOf(1f to 0f), r.gains)
    }

    @Test fun resumedButDisplacedPlayerDoesNotCompleteTransfer() = runBlocking {
        val r = Rig()
        r.afterTick = { if (r.time >= 40L) r.current = r.current.copy(errorMs = -180L) }
        assertEquals(BridgeHandoff.Result.OutOfSync, r.transfer())
        assertEquals(0f, r.gains.last().second)
    }

    @Test fun insufficientLocalAudioUsesRecoveryInsteadOfForcedTransfer() = runBlocking {
        val r = Rig()
        r.current = r.current.copy(remainingMs = 50L)
        assertEquals(BridgeHandoff.Result.NoRoom, r.transfer())
        assertTrue(r.gains.isEmpty())
    }

    @Test fun cancellationDuringTransferDoesNotCompleteIt() = runBlocking {
        val r = Rig()
        r.afterTick = { if (r.time >= 40L) r.cancelled = true }
        assertEquals(BridgeHandoff.Result.Cancelled, r.transfer())
        assertEquals(0f, r.gains.last().second)
    }

    @Test fun recoverySeeksToLastAudiblePointAndWaitsWithoutOverlapping() = runBlocking {
        val r = Rig()
        r.incomingReady = false
        r.afterTick = { if (r.time >= 2_000L) r.incomingReady = true }
        assertEquals(BridgeHandoff.Recovery.Resumed, r.recover())
        assertTrue(r.stopped)
        assertEquals(15_064L, r.seek)
        assertTrue(r.time >= 2_000L)
        assertTrue(r.gains.none { (a, b) -> a > 0f && b > 0f })
        assertEquals(0f to 1f, r.gains.last())
    }

    @Test fun longNetworkOutageLeavesSessionLoadingAtTheContinuationPoint() = runBlocking {
        val r = Rig()
        r.incomingReady = false
        assertEquals(BridgeHandoff.Recovery.Loading, r.recover())
        assertEquals(15_064L, r.seek)
        assertTrue(r.time in 7_500L..7_600L)
        assertEquals(0f to 1f, r.gains.last())
    }

    @Test fun pausingOrChangingTrackWhileLoadingDoesNotResumeOrReseek() = runBlocking {
        val r = Rig()
        r.incomingReady = false
        r.afterTick = { if (r.time >= 200L) r.cancelled = true }
        assertEquals(BridgeHandoff.Recovery.Cancelled, r.recover())
        assertEquals(15_064L, r.seek)
        assertTrue(r.gains.all { it.second == 0f })
    }

    @Test fun cancellingBeforeRecoveryDoesNotSeek() = runBlocking {
        val r = Rig()
        r.cancelled = true
        assertEquals(BridgeHandoff.Recovery.Cancelled, r.recover())
        assertNull(r.seek)
        assertTrue(r.gains.isEmpty())
    }

    @Test fun oneNoisyBluetoothReadingDoesNotAbortAHealthyTransfer() = runBlocking {
        val r = Rig()
        r.afterTick = { r.current = r.current.copy(errorMs = if (r.time == 40L) 110L else 3L) }
        assertEquals(BridgeHandoff.Result.Complete, r.transfer())
        assertTrue(r.gains.any { it.second in 0.1f..0.9f })
        assertEquals(0f to 1f, r.gains.last())
    }

    @Test fun initialBluetoothOutlierIsFilteredBeforeSelectingTheCurve() = runBlocking {
        val r = Rig()
        r.current = r.current.copy(errorMs = 90L)
        r.afterTick = { r.current = r.current.copy(errorMs = 3L) }
        assertEquals(BridgeHandoff.Result.Complete, r.transfer())
    }

    @Test fun runningOutOfRoomDuringTransferDoesNotForceIt() = runBlocking {
        val r = Rig()
        r.afterTick = { if (r.time >= 40L) r.current = r.current.copy(remainingMs = 10L) }
        assertEquals(BridgeHandoff.Result.NoRoom, r.transfer())
    }

    @Test fun recoveryAtEndOfQueueDoesNotWaitSevenSeconds() = runBlocking {
        val r = Rig()
        r.incomingReady = false
        r.ended = true
        assertEquals(BridgeHandoff.Recovery.Ended, r.recover())
        assertEquals(64L, r.time)
        assertTrue(r.gains.all { it.second == 0f })
    }

    @Test fun readyDestinationResumesWithoutNetworkWait() = runBlocking {
        val r = Rig()
        assertEquals(BridgeHandoff.Recovery.Resumed, r.recover())
        assertEquals(128L, r.time)
    }

    @Test fun briefRebufferBetweenSamplesRestartsClockWarmup() {
        val clock = BridgeHandoff.ContinuousPlayback()
        clock.update(true, 0L)
        assertTrue(clock.stable(1_000L, 500L))
        clock.update(false, 1_001L)
        clock.update(true, 1_002L)
        assertEquals(1L, clock.revision)
        assertFalse(clock.stable(1_003L, 500L))
        assertFalse(clock.stable(1_701L, 500L))
        assertTrue(clock.stable(1_702L, 500L))
    }

    @Test fun pendingJitterAtTheThresholdDoesNotUndoStability() {
        val clock = BridgeHandoff.ContinuousPlayback()
        clock.update(true, 0L)
        assertTrue(clock.stable(700L, 500L))
        // El pendiente sube unos ms justo después: antes volvía a «inestable» y abortaba la alineación del puente.
        assertTrue(clock.stable(702L, 520L))
        assertTrue(clock.stable(720L, 1_500L))
        // Una parada real sí obliga a esperar de nuevo.
        clock.update(false, 800L)
        assertFalse(clock.stable(800L, 500L))
        clock.update(true, 810L)
        assertFalse(clock.stable(1_000L, 500L))
        assertTrue(clock.stable(1_510L, 500L))
    }

    private suspend fun gateWait(advance: Boolean = true, invalidate: Boolean = false, room: Boolean = true): Pair<BridgeHandoff.Result, Long> {
        var time = 0L
        var position = 1_000L
        var revision = 0L
        val result = BridgeHandoff.awaitGateDrain(500L,
            snapshot = { BridgeHandoff.GateSnapshot(true, position, revision) },
            roomLeft = { room }, cancelled = { false }, nowMs = { time },
            waitFrame = {
                time += 20L
                if (advance) position += 20L
                if (invalidate && time == 200L) revision++
            },
        )
        return result to time
    }

    @Test fun gateDrainsOnlyWithAudibleProgress() = runBlocking {
        val (result, time) = gateWait()
        assertEquals(BridgeHandoff.Result.Complete, result)
        assertEquals(580L, time)
    }

    @Test fun wallTimeWhileAudioClockIsStoppedDoesNotDrainGate() = runBlocking {
        assertEquals(BridgeHandoff.Result.NotReady, gateWait(advance = false).first)
    }

    @Test fun briefBufferingDuringGateWaitInvalidatesItEvenIfItAlreadyResumed() = runBlocking {
        val (result, time) = gateWait(invalidate = true)
        assertEquals(BridgeHandoff.Result.NotReady, result)
        assertEquals(200L, time)
    }

    @Test fun gateCannotSilentlySucceedWithoutEnoughLocalAudio() = runBlocking {
        assertEquals(BridgeHandoff.Result.NoRoom, gateWait(room = false).first)
    }
}
