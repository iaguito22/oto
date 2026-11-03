package io.github.iaguito22.oto.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatchingTest {
    @Test fun rejectsDifferentVersionsWithSimilarTitles() {
        assertFalse(LyricsSource.sameTrackTitle("Awaken", "Awaken (Re-Jinxed) [feat. spill tab]"))
        assertFalse(LyricsSource.sameTrackTitle("Awaken (Re-Jinxed)", "Awaken"))
        assertTrue(LyricsSource.sameTrackTitle("Awaken (Re-Jinxed) (con spill tab)", "Awaken (Re-Jinxed) [feat. spill tab]"))
        assertTrue(LyricsSource.sameTrackTitle("Awaken", "Awaken (Official Video)"))
        assertTrue(LyricsSource.sameTrackArtist("League of Legends, Valerie Broussard", "League of Legends Music, Valerie Broussard"))
        assertFalse(LyricsSource.sameTrackArtist("League of Legends", "Another Artist"))
    }
}
