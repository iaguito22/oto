package io.github.iaguito22.oto.desktop.playback

import kotlin.random.Random

/** Keep the chosen occurrence first and shuffle every other occurrence exactly once. */
internal fun <T> playbackOrder(items: List<T>, start: Int?, shuffle: Boolean, random: Random = Random.Default): Pair<List<T>, Int> {
    require(items.isNotEmpty())
    val index = (start ?: 0).coerceIn(items.indices)
    if (!shuffle) return items to index
    if (start == null) return items.shuffled(random) to 0
    val remaining = items.filterIndexed { i, _ -> i != index }.shuffled(random)
    return (listOf(items[index]) + remaining) to 0
}
