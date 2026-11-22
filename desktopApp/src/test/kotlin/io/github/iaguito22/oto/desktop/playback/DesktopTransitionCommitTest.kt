package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.dj.ChorusSafety
import io.github.iaguito22.oto.dj.SongSection
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopTransitionCommitTest {
    @Test fun retainedDjMutexCannotApproveAnExpiredAudioWindow() = runTest {
        val mutex = Mutex(locked = true)
        var sourceNow = 90_000L
        var committed = false
        var prepared = false
        val chorus = listOf(SongSection(97_000L, 120_000L, 1.0))
        val result = async {
            commitPreparedTransition(mutex, IndexGate(),
                prepare = { prepared = true },
                isSafe = { ChorusSafety.allowsLive(sourceNow, 6_000L, 1.0, chorus) },
                commit = { committed = true })
        }
        runCurrent()
        assertFalse(prepared)
        assertFalse(committed)
        // Simulate A advancing while retuneDjTempo owns the mutex.
        sourceNow = 92_000L
        mutex.unlock()
        assertFalse(result.await())
        assertTrue(prepared)
        assertFalse(committed)
    }

    @Test fun safeCommitRunsOnlyAfterMetadataAndLatestValidation() = runTest {
        val events = mutableListOf<String>()
        assertTrue(commitPreparedTransition(Mutex(), IndexGate(),
            prepare = { events += "prepare"; 42 },
            isSafe = { events += "validate"; true },
            commit = { events += "commit:$it" }))
        assertEquals(listOf("prepare", "validate", "commit:42"), events)
    }
}
