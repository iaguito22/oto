package io.github.iaguito22.oto.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Iago, 26/11: con mala conexión la carátula no salía, y a veces tampoco luego con buena, hasta tocar algo.
// Una carátula que falla se vuelve a pedir sola, cada vez más espaciada y enseguida al volver la red.
class ArtworkRetryTest {
    @Test fun aCoverThatFailsIsAskedAgainUntilItArrives() = runBlocking {
        var asked = 0
        val got = ArtworkRetry.retrying(MutableStateFlow(0), delayMs = { 1L }) { asked++; if (asked < 3) null else "carátula" }
        assertEquals("carátula", got)
        assertEquals(3, asked)
    }

    @Test fun whenTheNetworkComesBackItAsksAtOnce() = runBlocking {
        val network = MutableStateFlow(0)
        var asked = 0
        withTimeout(2_000L) {
            launch { kotlinx.coroutines.delay(50L); network.value = 1 }
            val got = ArtworkRetry.retrying(network, delayMs = { 60_000L }) { asked++; if (asked < 2) null else "carátula" }
            assertEquals("carátula", got)
        }
    }

    @Test fun itGivesUpAfterAWhile() = runBlocking {
        var asked = 0
        assertNull(ArtworkRetry.retrying(MutableStateFlow(0), delayMs = { 1L }) { asked++; null })
        assertEquals(ArtworkRetry.MAX_ATTEMPTS, asked)
    }

    @Test fun waitsGrowButStayShort() {
        assertEquals(1_000L, ArtworkRetry.delayMs(0))
        assertTrue(ArtworkRetry.delayMs(1) > ArtworkRetry.delayMs(0))
        assertEquals(30_000L, ArtworkRetry.delayMs(20))
    }
}
