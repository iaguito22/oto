package io.github.iaguito22.oto.desktop.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Comprueba la sesión, lee la cola vigente y la publica bajo el mismo mutex que la revisión del DJ. */
internal suspend fun <T> publishCurrentDjTail(
    mutex: Mutex,
    stillCurrent: () -> Boolean,
    read: () -> List<T>,
    publish: (List<T>) -> Unit,
) = mutex.withLock {
    if (stillCurrent()) publish(read())
}
