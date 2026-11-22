package io.github.iaguito22.oto.desktop.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All suspension (lock acquisition) precedes validation; commit cannot suspend by construction. */
internal suspend fun <T> commitPreparedTransition(
    mutex: Mutex,
    indexGate: IndexGate,
    prepare: () -> T,
    isSafe: () -> Boolean,
    commit: (T) -> Unit,
): Boolean = mutex.withLock {
    val prepared = prepare()
    indexGate.locked {
        if (!isSafe()) return@locked false
        commit(prepared)
        true
    }
}
