package io.github.iaguito22.oto.playback

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.toSong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    @Test fun manualQueueMovesAllCopiesAndKeepsTheCurrentSong() {
        val queue = listOf("c", "a", "manual", "b", "c", "c").toMutableList()
        val edit = manualQueueEdit(queue, 1, "c", next = false, isManual = { it == "manual" }, idOf = { it })
        edit.removals.forEach { queue.removeAt(it) }
        queue.add(checkNotNull(edit.insertion), "c")
        assertEquals(listOf("a", "manual", "c", "b"), queue)
        assertEquals(0, edit.current)
        val current = manualQueueEdit(queue + "a", 0, "a", next = true, isManual = { false }, idOf = { it })
        assertEquals(listOf(4), current.removals)
        assertNull(current.insertion)
    }

    @Test fun radioCapsUpcomingKeepsPageOverflowAndRemembersTrimmedHistory() {
        val radio = RadioQueue("seed", { it: String -> RadioTrackKey(it, it) }, { it.id })
        val items = (0..70).map { "s$it" }
        assertEquals(RadioQueue.Trim(5, 71), radio.trim(items, 20))
        assertFalse(radio.needsRefill(items.size, 20))
        assertTrue(radio.needsRefill(items.size, 62))
        val queue = items.subList(5, 21)
        val page = listOf("s0", "s20", "new1", "new1") + (2..60).map { "new$it" }
        radio.offer(page.map { Heard(it, it, "", null).toSong() })
        assertEquals((1..50).map { "new$it" }, radio.take(queue, 15))
        assertEquals((51..60).map { "new$it" }, radio.take(queue, 15))
    }

    @Test fun radioUsesContinuationThenStartsFromTheLastSong() = runBlocking {
        val opened = mutableListOf<String>()
        val radio = RadioQueue("seed", { it: String -> RadioTrackKey(it, it) }, { it.id }) { seed ->
            opened += seed
            object : Autoplay.Source {
                private var page = 0
                override val exhausted get() = page == 2
                override suspend fun more() = Result.success(listOf(Heard("$seed-${++page}", "", "", null).toSong()))
            }
        }
        repeat(3) { radio.fetch(listOf("last")).getOrThrow().let(radio::offer) }
        assertEquals(listOf("seed", "last"), opened)
        assertEquals(listOf("seed-1", "seed-2", "last-1"), radio.take(listOf("last"), 0))
    }

    @Test fun radioDefersTheTailWhenGoingBackAndRestoresItsOrder() {
        val radio = RadioQueue("seed", { it: String -> RadioTrackKey(it, it) }, { it.id })
        val queue = (0..52).map { "s$it" }
        assertEquals(RadioQueue.Trim(0, 51), radio.trim(queue, 0))
        radio.offer(listOf(Heard("fresh", "", "", null).toSong()))
        assertEquals(listOf("s51", "s52", "fresh"), radio.take(queue.take(51), 3))
    }

    @Test fun manualEditsKeepDeferredSongsInOrderWhenTheRadioWindowIsFull() {
        val radio = RadioQueue("seed", { it: String -> RadioTrackKey(it, it) }, { it.id })
        val queue = (0..50).map { "s$it" }
        val first = radio.withDeferred(queue) + "manual1"
        assertEquals(RadioQueue.Trim(0, 51), radio.trimEditedQueue(first, 0))
        val second = radio.withDeferred(queue) + "manual2"
        assertEquals(RadioQueue.Trim(0, 51), radio.trimEditedQueue(second, 0))
        assertEquals(listOf("manual1", "manual2"), radio.take(queue, 2))
    }
}
