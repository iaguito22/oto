package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.dj.DjBridgeSpec
import io.github.iaguito22.oto.dj.DjBridgeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * El corte en drop en el PC (Iago, 25/11): deja sonar la subida, corta justo antes del drop, no deja sonar el drop y deja
 * un silencio con eco y reverb hasta que entra la otra. Antes era un fundido de 3,5 s y se oía el drop mientras bajaba.
 */
class DropCutTest {
    private fun spec(cut: Long = 60_000L, pickup: Long = 500L, gap: Long = 2_000L, entry: Long = 30_000L) = DjBridgeSpec(
        style = DjBridgeStyle.EnergyDropCut, preRollStartMs = cut - pickup - 7_000L, cueOutStartMs = cut - pickup, cueOutEndMs = cut,
        entryInMs = entry, dryBResumeMs = entry + 8_000L, preRollMs = 7_000L, postRollMs = 7_000L,
        transitionDurationMs = pickup + gap + 750L, bpmA = 120.0, bpmB = 128.0, tempoMatchRatio = 1.0, bassSwapFrac = 0.5f,
        echoDelayMs = 500.0, reverbRoom = 0.48f, cutoffStartHz = 65f, cutoffEndHz = 2_400f, pickupMs = pickup, silenceMs = gap,
        kickShiftMs = 0.0, incomingGainMatch = 1f, compatibilityScore = 0.8, reason = "corte",
    )

    @Test
    fun theOutgoingSongGoesSilentExactlyAtTheCutAndLeavesItsTail() {
        val s = DesktopTransitionPlanner.dropCutScript(spec())
        val before = BandsCue.at(s.outgoing, 59_999L)!!.first
        val after = BandsCue.at(s.outgoing, 60_000L)!!.first
        assertEquals(1f, before.overall, 0f)
        assertEquals("el drop no suena", 0f, after.overall, 0f)
        assertTrue("queda el eco", after.echoWet >= 0.5f)
        assertTrue("y la reverb", after.reverbWet > 0.1f)
        // Antes del corte ya se llenan el eco y la reverb (sin oírse) para que repitan lo último de la subida.
        assertTrue(before.echoWet > 0f && before.echoWet < 0.01f)
        // Al acabar el silencio la cola ya casi no suena (respiro antes de B) y luego nada.
        val end = BandsCue.at(s.outgoing, 62_000L)!!.first
        assertTrue("cola al entrar B: ${end.echoWet}", end.echoWet < 0.05f && end.reverbWet < 0.05f)
        assertEquals(0f, BandsCue.at(s.outgoing, 62_400L)!!.first.echoWet, 0f)
    }

    @Test
    fun theIncomingSongIsSilentUntilItsRiserAndEntersWholeAtItsEntry() {
        val s = DesktopTransitionPlanner.dropCutScript(spec())
        assertEquals(0f, BandsCue.at(s.incoming, s.bStartMs)!!.first.overall, 0f)
        assertEquals(0f, BandsCue.at(s.incoming, 29_000L)!!.first.overall, 0f)
        val riser = BandsCue.at(s.incoming, 29_800L)!!.first
        assertTrue("riser bajo y sin agudos: $riser", riser.overall in 0.05f..0.6f && riser.high < 1f)
        assertEquals(MixBands.NEUTRAL, BandsCue.at(s.incoming, 30_000L)!!.first)
        // B arranca antes de su riser, y a tiempo: si tarda 200 ms en sonar, llega a su entrada al acabar el silencio.
        assertTrue(s.bStartMs < 30_000L - 300L)
        val resumeAt = s.resumeBAtMs(200L)
        assertEquals(60_000L + 2_000L, resumeAt + 200L + (30_000L - s.bStartMs))
    }

    @Test
    fun anEntryAtTheVeryStartOfTheSongHasNoRiser() {
        val s = DesktopTransitionPlanner.dropCutScript(spec(entry = 100L))
        assertEquals(0L, s.bStartMs)
        assertEquals(MixBands.NEUTRAL, BandsCue.at(s.incoming, 100L)!!.first)
    }

    @Test
    fun rampsGoStepByStepAndBoundariesAreExact() {
        val cues = listOf(
            BandsCue(1_000L, MixBands(overall = 0f)),
            BandsCue(2_000L, MixBands(overall = 1f), ramp = true),
            BandsCue(3_000L, MixBands(overall = 0.5f)),
        )
        assertNull(BandsCue.at(cues, 999L))
        val (mid, next) = BandsCue.at(cues, 1_500L)!!
        assertEquals(0.5f, mid.overall, 0.01f)
        assertEquals("en una rampa se vuelve a mirar cada 10 ms", 1_510L, next)
        assertEquals(3_000L, BandsCue.at(cues, 2_500L)!!.second)
        assertNull(BandsCue.at(cues, 3_000L)!!.second)
    }

    @Test
    fun afterTheCutOnlyAnEchoAndReverbTailRemainsAndFades() {
        val filter = StereoBandFilter()
        val sr = 48_000
        fun tone(ms: Int): ByteArray {
            val frames = sr * ms / 1000
            val out = ByteArray(frames * 4)
            for (i in 0 until frames) {
                val v = (sin(2 * PI * 220 * i / sr) * 12_000).toInt()
                out[i * 4] = v.toByte(); out[i * 4 + 1] = (v shr 8).toByte(); out[i * 4 + 2] = v.toByte(); out[i * 4 + 3] = (v shr 8).toByte()
            }
            return out
        }
        fun peak(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
            var m = 0
            var i = from - from % 4
            while (i < to) { m = maxOf(m, abs(((b[i + 1].toInt() shl 8) or (b[i].toInt() and 255)).toShort().toInt())); i += 4 }
            return m
        }
        val fill = MixBands(echoWet = 0.001f, echoFeedback = 0.5f, echoDelayMs = 300, reverbWet = 0.001f)
        val tail = MixBands(overall = 0f, echoWet = 0.6f, echoFeedback = 0.5f, echoDelayMs = 300, reverbWet = 0.18f)
        val a = tone(500)
        filter.processPcm16(a, a.size, fill, 1f)
        // Corte a mitad de un bloque: el principio con lo de antes, desde el corte con la cola.
        val b = tone(1_200)
        val cutByte = sr / 10 * 4 // a los 100 ms
        filter.processPcm16(b, cutByte, fill, 1f)
        filter.processPcm16(b, b.size, tail, 1f, startByte = cutByte)
        assertTrue("antes del corte suena", peak(b, 0, cutByte) > 8_000)
        // Pasado el suavizado (5 ms) solo queda la cola: suena (eco a 300 ms) pero menos que el seco.
        val echoZone = peak(b, cutByte + sr / 1000 * 4 * 300, cutByte + sr / 1000 * 4 * 400)
        assertTrue("eco tras el corte: $echoZone", echoZone in 1_000..11_000)
        // Y se apaga: dos segundos después, mucho menos.
        val later = tone(2_000).also { filter.processPcm16(it, it.size, tail, 1f) }
        assertTrue("la cola se apaga: ${peak(later, later.size - sr / 5 * 4, later.size)} < $echoZone",
            peak(later, later.size - sr / 5 * 4, later.size) < echoZone / 3)
    }
}
