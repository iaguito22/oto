package io.github.iaguito22.oto.lyrics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrclibConsensusTest {
    private fun item(id: Long, firstAt: String, duration: Double = 163.0) = JSONObject()
        .put("id", id).put("duration", duration)
        .put("syncedLyrics", "[$firstAt]I am a little bit of loneliness, a little bit of disregard\n[00:40.98]But it's like")

    @Test
    fun aShiftedExactMatchLosesToTheVersionsThatAgree() {
        // «Faint» (medido 19/11): la exacta de LRCLIB a 30,58 s; casi todas las demás a ~29,0 s.
        val exact = item(13606, "00:30.58", 164.0)
        val found = listOf(item(1, "00:29.40"), item(2, "00:29.04"), item(3, "00:29.01"), item(4, "00:16.46"), item(5, "00:30.47"))
        val picked = LyricsSource.pickLrclib(exact, found, 162)!!.optLong("id")
        assertTrue("eligió $picked", picked in setOf(1L, 2L, 3L))
    }

    @Test
    fun anExactMatchThatAgreesIsKept() {
        val exact = item(10, "00:29.20", 162.0)
        val found = listOf(item(1, "00:29.40"), item(2, "00:29.04"), item(3, "00:29.01"))
        assertEquals(10L, LyricsSource.pickLrclib(exact, found, 162)!!.optLong("id"))
    }

    @Test
    fun withoutEnoughVersionsTheExactOneStays() {
        val exact = item(10, "00:30.58")
        assertEquals(10L, LyricsSource.pickLrclib(exact, listOf(item(1, "00:29.00")), 162)!!.optLong("id"))
    }
}
