package io.github.iaguito22.oto.playback

import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ShuffledQueueEditTest {
    @Test fun insertedEntriesPlayNextEvenWhenAppendedToTheTimeline() {
        val order = listOf(3, 5, 0, 4, 1, 2)
        val removed = booleanArrayOf(false, false, true, true, false, false)
        val shuffled = ShuffleOrder.DefaultShuffleOrder(shuffledQueueAfterEdit(order, removed, 3, 2), 0)

        // La actual era 4 y pasa a 2 tras los borrados. Los nuevos temas físicos son 4 y 5.
        assertEquals(4, shuffled.getNextIndex(2))
        assertEquals(5, shuffled.getNextIndex(4))
        assertEquals(1, shuffled.getNextIndex(5))
        assertEquals(0, shuffled.getPreviousIndex(2))
    }

    @Test fun trimmingShuffledHistoryPreservesPendingEntriesBeforeTheCurrentPhysicalIndex() {
        // 4 es la actual. Los índices físicos 0 y 2 siguen pendientes.
        val order = listOf(1, 3, 4, 2, 0, 5)
        val removed = booleanArrayOf(false, true, false, true, false, true)
        val shuffled = ShuffleOrder.DefaultShuffleOrder(shuffledQueueAfterEdit(order, removed, 0, 0), 0)

        assertEquals(2, shuffled.firstIndex)
        assertEquals(1, shuffled.getNextIndex(2))
        assertEquals(0, shuffled.getNextIndex(1))
    }
}
