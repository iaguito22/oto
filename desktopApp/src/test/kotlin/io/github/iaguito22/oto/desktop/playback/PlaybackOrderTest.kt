package io.github.iaguito22.oto.desktop.playback

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PlaybackOrderTest {
    @Test fun selectedSongStartsAndAllOtherOccurrencesRemainInShuffledQueue() {
        val songs = listOf("a", "b", "a", "c", "d", "e")
        val (queue, index) = playbackOrder(songs, 2, true, Random(42))
        assertEquals(0, index)
        assertEquals("a", queue[index])
        assertEquals(songs.groupingBy { it }.eachCount(), queue.groupingBy { it }.eachCount())
        assertNotEquals(songs.filterIndexed { i, _ -> i != 2 }, queue.drop(1))
    }

    @Test fun normalOrderKeepsSelectedIndex() {
        assertEquals(listOf("a", "b", "c") to 1, playbackOrder(listOf("a", "b", "c"), 1, false))
    }
}
