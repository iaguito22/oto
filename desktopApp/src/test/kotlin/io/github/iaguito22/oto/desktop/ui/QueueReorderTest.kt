package io.github.iaguito22.oto.desktop.ui

import io.github.iaguito22.oto.playback.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueReorderTest {
    // Las dos apariciones de una canción tienen claves distintas, incluso si se filtran otras filas.
    private val first = row("a#0", 2, "a")
    private val middle = row("b#0", 4, "b")
    private val last = row("a#1", 7, "a")
    private val original = listOf(first, middle, last)

    @Test
    fun movesAcrossSeveralRowsAndCommitsAbsoluteIndicesOnce() {
        val reorder = QueueReorder(original)
        reorder.start(first.key)
        reorder.move(first.key, middle.key)
        reorder.move(first.key, last.key)

        assertEquals(listOf(middle, last, first), reorder.rows)
        assertEquals(2 to 7, reorder.release(first.key))
        assertEquals(listOf(middle, last, first), reorder.rows)
        assertNull(reorder.release(first.key))
    }

    @Test
    fun movesUpAndIgnoresListHeadersAndOtherRowsAsDragSources() {
        val reorder = QueueReorder(original)
        reorder.start(last.key)
        reorder.move(last.key, "now_playing")
        reorder.move(first.key, middle.key)
        assertEquals(original, reorder.rows)

        reorder.move(last.key, first.key)
        assertEquals(listOf(last, first, middle), reorder.rows)
        assertEquals(7 to 2, reorder.release(last.key))
    }

    @Test
    fun cancellationAndReturningToTheOriginDoNotCommit() {
        val reorder = QueueReorder(original)
        reorder.start(first.key)
        reorder.move(first.key, last.key)
        reorder.cancel(first.key)
        assertNull(reorder.release(first.key))
        assertEquals(original, reorder.rows)

        reorder.start(first.key)
        reorder.move(first.key, last.key)
        reorder.move(first.key, middle.key)
        assertNull(reorder.release(first.key))
        assertEquals(original, reorder.rows)

        reorder.start(last.key)
        reorder.move(last.key, middle.key)
        assertEquals(7 to 4, reorder.release(last.key))
    }

    @Test
    fun swappingIdenticalEntriesAllowsAnotherDragWithoutAQueueEmission() {
        val duplicates = listOf(first, last)
        val reorder = QueueReorder(duplicates)
        reorder.start(first.key)
        reorder.move(first.key, last.key)
        assertEquals(2 to 7, reorder.release(first.key))

        assertEquals(duplicates, reorder.rows)
        reorder.start(last.key)
        reorder.move(last.key, first.key)
        assertEquals(7 to 2, reorder.release(last.key))
    }

    private fun row(key: String, index: Int, id: String) =
        QueueRowItem(key, QueueEntry(index, id, id, "", null))
}
