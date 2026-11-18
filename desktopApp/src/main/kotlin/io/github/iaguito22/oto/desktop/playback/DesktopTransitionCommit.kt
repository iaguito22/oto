package io.github.iaguito22.oto.desktop.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All suspension (lock acquisition) precedes validation; commit cannot suspend by construction. */
internal suspend fun <T> commitPreparedTransition(
    mutex: Mutex,
    prepare: () -> T,
    isSafe: () -> Boolean,
    commit: (T) -> Unit,
): Boolean = mutex.withLock {
    val prepared = prepare()
    if (!isSafe()) return@withLock false
    commit(prepared)
    true
}
