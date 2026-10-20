package io.github.iaguito22.oto.playback

import kotlin.math.pow
import kotlin.random.Random

/**
 * Aleatorio como el de Spotify, que no es un barajado sin más: lo que añadiste hace poco y lo que te gusta sale antes,
 * lo que acabas de oír se queda para el final, y el mismo artista no suena dos veces seguidas si se puede evitar.
 */
object Shuffle {
    /** Una canción por barajar. [age]: 0 = la última que entró en la lista, 1 = la primera; null si no se sabe. */
    data class Item(val id: String, val artist: String, val age: Float?)

    /**
     * El orden en que sonarán (índices de [items]). [first], si lo hay, va el primero: es la que se tocó.
     * Muestreo ponderado sin reemplazo (Efraimidis–Spirakis): cada una saca u^(1/peso) y se ordena de mayor a menor.
     */
    fun order(
        items: List<Item>,
        first: Int? = null,
        liked: Set<String> = emptySet(),
        heard: Set<String> = emptySet(),
        random: Random = Random.Default,
    ): List<Int> {
        val keyed = items.indices.filter { it != first }.map { i ->
            val s = items[i]
            var w = 1.0
            s.age?.let { a -> w += 1.5 * (1 - a).toDouble().pow(2) }
            if (s.id in liked) w *= 1.3
            if (s.id in heard) w *= 0.4
            i to random.nextDouble().pow(1 / w)
        }
        val out = ArrayList<Int>(items.size)
        first?.let(out::add)
        keyed.sortedByDescending { it.second }.mapTo(out) { it.first }
        spread(out, items)
        return out
    }

    /** Si dos seguidas son del mismo artista, la segunda se cambia por la primera de otro que venga poco después. */
    private fun spread(order: MutableList<Int>, items: List<Item>) {
        fun artist(i: Int) = items[order[i]].artist.substringBefore(',').trim().lowercase()
        for (i in 1 until order.size) {
            if (artist(i) != artist(i - 1)) continue
            val swap = (i + 1 until minOf(order.size, i + WINDOW)).firstOrNull { artist(it) != artist(i - 1) } ?: continue
            order[i] = order[swap].also { order[swap] = order[i] }
        }
    }

    private const val WINDOW = 8
}
