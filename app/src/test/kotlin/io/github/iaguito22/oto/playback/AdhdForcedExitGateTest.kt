package io.github.iaguito22.oto.playback

import io.github.iaguito22.oto.dj.AdhdSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdhdForcedExitGateTest {
    /** Avanza el reloj y la posición a la vez (reproducción normal), consultando cada 250 ms como el monitor. */
    private fun AdhdForcedExitGate.play(
        fromNow: Long, fromPos: Long, ms: Long, planned: Long?, due: Boolean = true, handingOver: Boolean = false,
    ): Boolean {
        var fired = false
        var t = 0L
        while (t <= ms) {
            fired = observe(fromNow + t, fromPos + t, planned, due, handingOver)
            t += 250L
        }
        return fired
    }

    @Test
    fun firesOnlyAfterTheConditionHoldsLongerThanSeveralCrossfadeRounds() {
        val gate = AdhdForcedExitGate()
        assertFalse(gate.play(0L, 100_000L, 1_250L, planned = 100_000L))
        assertTrue(gate.play(1_500L, 101_500L, 0L, planned = 100_000L))
    }

    @Test
    fun aSeekPastTheOldPlanRestartsTheCountSoCrossfadeCanReplan() {
        val gate = AdhdForcedExitGate()
        assertFalse(gate.play(0L, 90_000L, 1_000L, planned = 100_000L, due = false))
        // Búsqueda a 110 s con el plan viejo aún publicado: el monitor despierta antes que Crossfade.
        assertFalse(gate.observe(1_250L, 110_000L, 100_000L, due = true, handingOver = false))
        assertFalse(gate.observe(1_500L, 110_250L, 100_000L, due = true, handingOver = false))
        // Crossfade evalúa y publica un final nuevo: la cuenta vuelve a empezar.
        assertFalse(gate.observe(1_750L, 110_500L, 125_000L, due = true, handingOver = false))
        assertFalse(gate.play(2_000L, 110_750L, 1_000L, planned = 125_000L))
        assertTrue(gate.play(3_250L, 112_000L, 0L, planned = 125_000L))
    }

    @Test
    fun aHandoverOrLosingTheConditionResetsTheCount() {
        val gate = AdhdForcedExitGate()
        assertFalse(gate.play(0L, 100_000L, 1_000L, planned = 100_000L))
        assertFalse(gate.observe(1_250L, 101_250L, 100_000L, due = true, handingOver = true))
        assertFalse(gate.play(1_500L, 101_500L, 1_250L, planned = 100_000L))
        assertTrue(gate.play(2_750L, 102_750L, 250L, planned = 100_000L))
        gate.reset()
        assertFalse(gate.observe(3_250L, 103_250L, 100_000L, due = true, handingOver = false))
    }

    /** Simula el monitor (cada 250 ms) desde [fromPos]; devuelve (posición al confirmar, punto de salto). */
    private fun simulate(seg: AdhdSegment, plannedEnd: Long, fromPos: Long, duck: Long = 400L): Pair<Long, Long> {
        val gate = AdhdForcedExitGate()
        val target = seg.forcedExitTargetMs(plannedEnd, emptyList(), guardMs = duck)
        var pos = fromPos
        var now = 0L
        while (true) {
            val armed = pos >= AdhdForcedExitGate.armAtMs(target, duck)
            if (gate.observe(now, pos, plannedEnd, armed, handingOver = false)) {
                val jump = AdhdForcedExitGate.jumpAtMs(target, pos, duck) { seg.forcedExitTargetMs(it, emptyList(), guardMs = duck) }
                return pos to jump
            }
            pos += 250L
            now += 250L
        }
    }

    @Test
    fun theConfirmationHappensBeforeThePhraseSoTheJumpLandsOnIt() {
        // Final previsto 100 s, frase en 104 s: el salto debe caer en 104 s, con la bajada empezando antes.
        val seg = AdhdSegment(20_000L, 100_000L, 80_000L, 96_000L, 4_000L, 200_000L, phrasePointsMs = listOf(96_000L, 104_000L, 112_000L))
        val (confirmedAt, jumpAt) = simulate(seg, plannedEnd = 100_000L, fromPos = 90_000L)
        assertEquals(104_000L, jumpAt)
        assertTrue("confirmó en $confirmedAt, sin sitio para la bajada", confirmedAt <= jumpAt - 400L)
    }

    @Test
    fun afterASeekPastTheTargetTheJumpGoesToTheNextPhraseNotMidPhrase() {
        val seg = AdhdSegment(20_000L, 100_000L, 80_000L, 96_000L, 4_000L, 200_000L, phrasePointsMs = listOf(96_000L, 104_000L, 112_000L))
        val (confirmedAt, jumpAt) = simulate(seg, plannedEnd = 100_000L, fromPos = 106_000L)
        assertTrue(confirmedAt > 104_000L)
        assertEquals(112_000L, jumpAt)
        assertTrue(confirmedAt <= jumpAt - 400L)
    }

    @Test
    fun aSeekAfterConfirmingInvalidatesThePendingJump() {
        // Confirmado el salto en 104 s; esperando (lecturas cada 20 ms) el usuario busca a 110 s.
        val c = PlaybackContinuity()
        var now = 0L
        var pos = 103_000L
        while (pos < 103_400L) { assertTrue(c.ok(now, pos, playing = true)); now += 20L; pos += 20L }
        assertFalse(c.ok(now, 110_000L, playing = true))
        // Y aunque la posición ya «pase» del salto, no se da por llegada sin seguir la reproducción.
        assertTrue(AdhdForcedExitGate.arrived(110_000L, 104_000L, playing = true))
    }

    @Test
    fun aPauseDuringTheDuckInvalidatesItAndAPausedPlayerNeverArrives() {
        val c = PlaybackContinuity()
        assertTrue(c.ok(0L, 103_600L, playing = true))
        assertTrue(c.ok(20L, 103_620L, playing = true))
        // Pausa: la posición se queda quieta mientras pasa el tiempo de la bajada.
        assertFalse(c.ok(40L, 103_620L, playing = false))
        assertFalse(c.ok(640L, 103_620L, playing = true))
        // Sin llegar de verdad al punto (aunque la bajada ya acabara por reloj), no salta.
        assertFalse(AdhdForcedExitGate.arrived(103_620L, 104_000L, playing = true))
        assertFalse(AdhdForcedExitGate.arrived(104_000L, 104_000L, playing = false))
        assertTrue(AdhdForcedExitGate.arrived(103_900L, 104_000L, playing = true))
    }

    @Test
    fun aPauseOrStallIsNotContinuousPlayback() {
        val gate = AdhdForcedExitGate()
        assertFalse(gate.play(0L, 100_000L, 1_000L, planned = 100_000L))
        // Reloj avanza 2 s pero la posición no (pausa sin reset): reinicia.
        assertFalse(gate.observe(3_000L, 101_000L, 100_000L, due = true, handingOver = false))
        assertFalse(gate.play(3_250L, 101_250L, 1_000L, planned = 100_000L))
    }
}
