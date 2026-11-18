package io.github.iaguito22.oto.desktop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMixGainTest {
    @Test
    fun theMatchOnlyLastsTheMixAndEndsAtTheSongsOwnGain() {
        // Antes: la entrante se quedaba a `gain × match` toda la canción.
        assertEquals(0.8f * 0.7f, incomingMixGain(0.8f, 0.7f, 0f), 1e-4f)
        assertEquals(0.8f * 0.7f, incomingMixGain(0.8f, 0.7f, 0.5f), 1e-4f)
        assertEquals(0.8f, incomingMixGain(0.8f, 0.7f, 1f), 1e-4f)
        val mid = incomingMixGain(0.8f, 0.7f, 0.8f)
        assertTrue(mid > 0.56f && mid < 0.8f)
    }

    @Test
    fun aQuieterIncomingSongIsNeverPushedAboveUnity() {
        // ×1,45 sobre una canción sin normalizar recortaba la salida de 16 bits.
        for (p in listOf(0f, 0.3f, 0.6f, 0.9f, 1f)) {
            assertTrue(incomingMixGain(1f, 1.45f, p) <= 1f)
        }
        assertEquals(0.6f * 1.45f, incomingMixGain(0.6f, 1.45f, 0f), 1e-4f)
    }
}
