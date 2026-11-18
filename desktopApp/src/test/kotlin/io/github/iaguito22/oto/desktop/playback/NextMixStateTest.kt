package io.github.iaguito22.oto.desktop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextMixStateTest {
    @Test
    fun aPreparedPairWithoutMixDoesNotStayPreparing() {
        // Plan con fundido 0 (estribillo protegido) o enlace al final: mixWindow da null.
        assertNull(nextMixState("b", prepared = true, window = null, style = "Fundido suave", preparing = true))
    }

    @Test
    fun preparingAndReadyAreStillAnnounced() {
        assertEquals(false, nextMixState("b", prepared = false, window = null, style = null, preparing = true)?.ready)
        val ready = nextMixState("b", prepared = true, window = 100_000L to 10_000L, style = "Cuadre de beats", preparing = false)!!
        assertEquals(true, ready.ready)
        assertEquals(105_000L, ready.switchMs)
        assertEquals("Cuadre de beats", ready.style)
        assertNull(nextMixState("b", prepared = false, window = null, style = null, preparing = false))
    }
}
