package io.github.iaguito22.oto.desktop.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class DjQueuePublicationTest {
    @Test fun aRefillCannotOverwriteAReviewWithItsOldSnapshot() = runBlocking {
        val mutex = Mutex()
        var current = listOf("B", "C", "D")
        var published = emptyList<String>()
        val prepared = CompletableDeferred<List<String>>()
        val reviewed = CompletableDeferred<Unit>()
        val refill = launch {
            prepared.complete(mutex.withLock { current.toList() })
            reviewed.await()
            publishCurrentDjTail(mutex, { true }, { current }) {
                assertTrue("la lectura y publicación conservan el mutex", mutex.isLocked)
                published = it
            }
        }
        assertEquals(listOf("B", "C", "D"), prepared.await())
        mutex.withLock {
            current = listOf("B", "C", "E")
            published = current
        }
        reviewed.complete(Unit)
        refill.join()
        assertEquals(listOf("B", "C", "E"), published)
    }

    @Test fun aReplacedSessionCannotPublish() = runBlocking {
        var published = false
        publishCurrentDjTail(Mutex(), { false }, { listOf("old") }) { published = true }
        assertFalse(published)
    }
}
