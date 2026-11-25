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

    // «What I've Done» (Iago, 25/11): NetEase a veces no mide las palabras, parte la línea en trozos iguales. Así la
    // última palabra se encendía al final de la nota larga y la letra iba atrasada.
    @Test fun netEaseLinesSplitIntoEqualPiecesAreSungAtSingingPace() {
        val line = LyricsSource.parseYrc("[46440,2640](46440,528,0)There(46968,528,0)’(47496,528,0)s (48024,528,0)no (48552,528,0)alibi").single()
        assertEquals("There’s no alibi", line.text)
        assertEquals(listOf("There’s", "no", "alibi"), line.words.map { it.text })
        assertFalse("tiempos estimados, no medidos", line.real)
        assertEquals(46_440L, line.words.first().start)
        assertTrue("«alibi» empieza mucho antes que el último trozo: ${line.words.last().start}", line.words.last().start < 48_000L)
        assertEquals(49_080L, line.end)
    }

    @Test fun netEaseLinesWithMeasuredWordsAreKept() {
        val line = LyricsSource.parseYrc("[1000,1600](1000,300,0)Keep (1300,450,0)that (1750,90,0)in (1840,760,0)mind").single()
        assertTrue(line.real)
        assertEquals(listOf(1000L, 1300L, 1750L, 1840L), line.words.map { it.start })
    }

    @Test fun lyricsOffsetIsSavedPerSongAndSurvivesARestart() {
        val dir = kotlin.io.path.createTempDirectory("letras").toFile()
        try {
            val store = LyricsStore(dir)
            assertEquals(0L, store.offsetMs("a"))
            kotlinx.coroutines.runBlocking { store.setOffset("a", 900L); store.setOffset("b", -300L); store.setOffset("b", 0L) }
            assertEquals(900L, store.offsets.value["a"])
            val again = LyricsStore(dir)
            assertEquals(900L, again.offsetMs("a"))
            assertEquals(0L, again.offsetMs("b"))
            assertFalse("0 no se guarda", again.offsets.value.containsKey("b"))
            assertEquals("Letra +0,9 s", lyricsOffsetLabel(900L))
            assertEquals("Letra −1,2 s", lyricsOffsetLabel(-1_200L))
            assertEquals("Sincronía: tal cual", lyricsOffsetLabel(0L))
            val shifted = (LyricsSource.parseYrc("[1000,1600](1000,300,0)Keep (1300,450,0)that (1750,90,0)in (1840,760,0)mind").let { Lyrics.Synced(it, "NetEase") }).shifted(900L)
            assertEquals(1_900L, shifted.lines.single().words.first().start)
        } finally {
            dir.deleteRecursively()
        }
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
