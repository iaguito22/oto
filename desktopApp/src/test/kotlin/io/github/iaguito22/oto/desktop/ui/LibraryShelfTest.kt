package io.github.iaguito22.oto.desktop.ui

import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryShelfTest {
    private fun list(id: String, folder: String? = null) = LocalPlaylist(id, id, emptyList(), folder = folder)

    @Test fun foldersSitWhereTheirFirstListIsAndEmptyOnesGoFirst() {
        val shelf = shelfOf(listOf(list("a"), list("b", "F"), list("c"), list("d", "F")), setOf("F", "Vacía"))
        assertEquals(listOf("d:Vacía", "a", "d:F", "c"), shelf.map { it.key })
        assertEquals(listOf("b", "d"), shelf[2].lists.map { it.id })
    }

    @Test fun movingAFolderCarriesItsListsLikeMobile() {
        val shelf = shelfOf(listOf(list("a"), list("b", "F"), list("c"), list("d", "F")), emptySet())
        val row = listOf(shelf[2], shelf[1], shelf[0]) // c, F, a
        assertEquals(listOf("c", "b", "d", "a"), reorderedIds(shelf, row))
    }

    @Test fun reorderingOnlyThePinnedRowKeepsTheRestInPlaceAndSavesSpots() {
        val shelf = shelfOf(listOf(list("a"), list("b"), list("c")), emptySet())
        // Fila de arriba: Me gusta, c (fijada), DJ, a (fijada). b no está en ella.
        val row = listOf(Shelved(null, emptyList(), special = LIKED_ID), shelf[2], Shelved(null, emptyList(), special = "dj"), shelf[0])
        assertEquals(listOf("c", "b", "a"), reorderedIds(shelf, row))
        assertEquals(mapOf(LIKED_ID to 0, "dj" to 2), spotsOf(row))
    }
}
