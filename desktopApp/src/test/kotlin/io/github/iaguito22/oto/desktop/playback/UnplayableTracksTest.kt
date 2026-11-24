package io.github.iaguito22.oto.desktop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Noche del 23/11: una pista no disponible («This track is unavailable») dejaba Oto parado 10 min en una lista y el DJ
 * también se quedaba quieto. Ahora se salta, pero no sin fin: si fallan varias seguidas es YouTube, no la canción.
 */
class UnplayableTracksTest {
    private class StreamResolveException(message: String) : Exception(message)

    @Test
    fun onlyExtractionFailuresMakeATrackUnplayable() {
        assertTrue(UnplayableTracks.isUnplayable(StreamResolveException("This track is unavailable.")))
        assertTrue(UnplayableTracks.isUnplayable(IllegalStateException("No playable clients")))
        // Sin red no es culpa de la canción: no se aparta.
        assertFalse(UnplayableTracks.isUnplayable(java.net.UnknownHostException("music.youtube.com")))
        assertFalse(UnplayableTracks.isUnplayable(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun skipsAFewInARowThenStops() {
        assertTrue(UnplayableTracks.shouldSkip(failuresInARow = 1, hasNext = true))
        assertTrue(UnplayableTracks.shouldSkip(failuresInARow = UnplayableTracks.MAX_SKIPS_IN_A_ROW, hasNext = true))
        assertFalse(UnplayableTracks.shouldSkip(failuresInARow = UnplayableTracks.MAX_SKIPS_IN_A_ROW + 1, hasNext = true))
        assertFalse(UnplayableTracks.shouldSkip(failuresInARow = 1, hasNext = false))
    }

    @Test
    fun theMixGoesToTheFirstPlayableAfterTheCurrent() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(1, UnplayableTracks.nextPlayable(ids, current = 0, unplayable = emptySet()))
        assertEquals(2, UnplayableTracks.nextPlayable(ids, current = 0, unplayable = setOf("b")))
        assertEquals(3, UnplayableTracks.nextPlayable(ids, current = 0, unplayable = setOf("b", "c")))
        assertEquals(-1, UnplayableTracks.nextPlayable(ids, current = 2, unplayable = setOf("d")))
    }
}
