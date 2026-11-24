package io.github.iaguito22.oto.desktop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Noche del 23/11: el DJ del PC solo conocía el tempo de lo ya sonado (378 de ~1.700 canciones), así que elegía la
 * siguiente sin mirar tempo ni tono (salto mediano 19 %, como barajar) y la revisión por tempo de la cola no cambió
 * nada en toda la noche. Ahora la cola se analiza por adelantado, como hace el móvil al precargarla.
 */
class DjScoutTest {
    private val none: (String) -> Boolean = { false }

    @Test
    fun picksTheNearestUnknownOfTheQueue() {
        val known = setOf("b")
        assertEquals(
            "c",
            DjScout.pick(upcoming = listOf("a", "b", "c", "d"), skip = setOf("a"), known = { it in known }, local = none, networkBudgetLeft = 2),
        )
    }

    @Test
    fun nothingToDoWhenEverythingIsKnownOrSkipped() {
        assertNull(DjScout.pick(listOf("a", "b"), skip = setOf("a"), known = { it == "b" }, local = none, networkBudgetLeft = 3))
    }

    @Test
    fun withoutBudgetOnlyDownloadedSongsAreAnalyzed() {
        assertNull(DjScout.pick(listOf("a", "b"), skip = emptySet(), known = none, local = none, networkBudgetLeft = 0))
        assertEquals("b", DjScout.pick(listOf("a", "b"), skip = emptySet(), known = none, local = { it == "b" }, networkBudgetLeft = 0))
    }
}
