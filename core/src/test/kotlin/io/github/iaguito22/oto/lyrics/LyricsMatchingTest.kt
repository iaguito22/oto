package io.github.iaguito22.oto.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatchingTest {
    @Test fun netEaseKeepsNonBreakingAndLeadingWordSpaces() {
        val line = LyricsSource.parseYrc("[1000,900](1000,100,0)I\u00A0(1100,400,0)can(1500,250,0) hear(1750,150,0) it").single()
        assertEquals("I can hear it", line.text)
        assertEquals(listOf(1000L, 1100L, 1500L, 1750L), line.words.map { it.start })
        assertEquals(listOf(true, true, true, false), line.words.map { it.space })
    }

    @Test fun rejectsDifferentVersionsWithSimilarTitles() {
        assertFalse(LyricsSource.sameTrackTitle("Awaken", "Awaken (Re-Jinxed) [feat. spill tab]"))
        assertFalse(LyricsSource.sameTrackTitle("Awaken (Re-Jinxed)", "Awaken"))
        assertTrue(LyricsSource.sameTrackTitle("Awaken (Re-Jinxed) (con spill tab)", "Awaken (Re-Jinxed) [feat. spill tab]"))
        assertTrue(LyricsSource.sameTrackTitle("Awaken", "Awaken (Official Video)"))
        assertTrue(LyricsSource.sameTrackArtist("League of Legends, Valerie Broussard", "League of Legends Music, Valerie Broussard"))
        assertFalse(LyricsSource.sameTrackArtist("League of Legends", "Another Artist"))
    }
}
