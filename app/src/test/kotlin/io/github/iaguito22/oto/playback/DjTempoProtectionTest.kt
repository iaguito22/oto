package io.github.iaguito22.oto.playback

import org.junit.Assert.*
import org.junit.Test

class DjTempoProtectionTest {
    @Test fun aManualSongDoesNotExposeTheDjPrefix() {
        val protected = androidDjTempoProtected(listOf("A", "manual", "B", "C", "D"), 0, listOf("B", "C", "D"))
        assertTrue(protected.containsAll(listOf("A", "manual", "B", "C")))
        assertFalse("D" in protected)
    }

    @Test fun protectionFollowsThePreparedCount() {
        assertEquals(setOf("A", "B"), androidDjTempoProtected(listOf("A", "B"), 0, listOf("B")))
        val upcoming = listOf("B", "C", "D", "E", "F", "G")
        val protected = androidDjTempoProtected(listOf("A", "manual") + upcoming, 0, upcoming)
        assertTrue(protected.containsAll(upcoming.dropLast(1)))
        assertFalse("G" in protected)
    }
}
