package io.github.iaguito22.oto.playback

data class ManualQueueEdit(val removals: List<Int>, val current: Int, val insertion: Int?)

/** Retira las copias de una canción sin interrumpir la que está sonando. */
fun <T> manualQueueEdit(
    queue: List<T>,
    current: Int,
    id: String,
    next: Boolean,
    isManual: (T) -> Boolean,
    idOf: (T) -> String,
): ManualQueueEdit {
    val removals = queue.indices.reversed().filter { it != current && idOf(queue[it]) == id }
    val newCurrent = current - removals.count { it < current }
    if (queue.getOrNull(current)?.let(idOf) == id) return ManualQueueEdit(removals, newCurrent, null)
    val manualEnd = if (next) current else current + queue.drop(current + 1).takeWhile(isManual).size
    val insertion = manualEnd + 1 - removals.count { it <= manualEnd }
    return ManualQueueEdit(removals, newCurrent, insertion)
}
