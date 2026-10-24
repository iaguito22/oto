package io.github.iaguito22.oto.playback

import io.github.iaguito22.oto.dj.artistKey
import io.github.iaguito22.oto.traced
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Aleatorio como el de Spotify, que no es un barajado sin más: lo que añadiste hace poco y lo que te gusta sale antes,
 * lo que acabas de oír se queda para el final, y el mismo artista no suena dos veces seguidas si se puede evitar.
 * Nada está atado: cualquiera puede salir en cualquier sitio, solo cambia lo probable.
 */
object Shuffle {
    /** Una canción por barajar. [age]: 0 = la última que entró en la lista, 1 = la primera; null si no se sabe. */
    data class Item(val id: String, val artist: String, val age: Float?)

    /**
     * El orden en que sonarán (índices de [items]). [first], si lo hay, va el primero: es la que se tocó.
     * [heardAt]: cuándo sonó de verdad cada una (por id); lo de hace horas se aparta mucho, lo de hace días apenas.
     * [played]: lo que ya sonó en esta misma cola (al barajar a mitad): al final del todo.
     * [after]: el artista de lo que suena justo antes (al barajar a mitad, el de la actual).
     *
     * Muestreo ponderado sin reemplazo (Efraimidis–Spirakis: cada una saca u^(1/peso) y se ordena de mayor a menor) y
     * luego se separan los artistas sin alejarse de ese orden más de lo necesario.
     */
    fun order(
        items: List<Item>,
        first: Int? = null,
        liked: Set<String> = emptySet(),
        heardAt: Map<String, Long> = emptyMap(),
        now: Long = System.currentTimeMillis(),
        played: Set<String> = emptySet(),
        after: String? = null,
        random: Random = Random.Default,
    ): List<Int> = traced("oto.shuffle") {
        val rest = items.indices.filter { it != first }
        // ln(u)/peso ordena igual que u^(1/peso) y no se queda en 0 con pesos pequeños. Cada una saca su número una
        // sola vez (sacándolo al comparar, el orden no sería coherente y la ordenación falla).
        val keys = DoubleArray(items.size)
        for (i in rest) keys[i] = ln(1 - random.nextDouble()) / weight(items[i], liked, heardAt, now, played)
        val ranked = rest.sortedByDescending { keys[it] }
        val out = ArrayList<Int>(items.size)
        first?.let(out::add)
        val lead = first?.let { artistOf(items[it]) } ?: after?.let(::artistKey)
        out += spread(ranked, items, lead)
        out
    }

    fun weight(s: Item, liked: Set<String>, heardAt: Map<String, Long>, now: Long, played: Set<String>): Double {
        var w = 1.0
        s.age?.let { a -> w += 1.5 * (1 - a).toDouble().pow(2) }
        if (s.id in liked) w *= 1.3
        heardAt[s.id]?.let { t ->
            val hours = (now - t).coerceAtLeast(0) / 3_600_000.0
            // Hace una hora: ×0,1; hace un día: ×0,5; hace una semana: casi nada.
            w *= 1 - 0.9 * exp(-hours / 30)
        }
        if (s.id in played) w *= 0.02
        return w
    }

    /**
     * Separa los artistas. Se va eligiendo de una en una, mirando solo las [WINDOW] siguientes del orden barajado: la
     * primera cuyo artista no haya sonado en sus últimas `hueco` canciones. El hueco de cada artista depende de cuánto
     * de lo que queda es suyo (uno que es media lista no puede ir más separado que una sí, una no); si ninguna de la
     * ventana cabe, la que menos lo incumpla.
     */
    private fun spread(ranked: List<Int>, items: List<Item>, lead: String?): List<Int> {
        val artists = items.map(::artistOf)
        val left = HashMap<String, Int>()
        ranked.forEach { left.merge(artists[it], 1, Int::plus) }
        val lastAt = HashMap<String, Int>()
        lead?.let { lastAt[it] = -1 }
        // De delante casi siempre: ArrayDeque quita sin mover toda la lista.
        val pending = ArrayDeque(ranked)
        val out = ArrayList<Int>(ranked.size)
        while (pending.isNotEmpty()) {
            val pos = out.size
            val remaining = pending.size
            var best = 0
            var bestMiss = Int.MAX_VALUE
            for (j in 0 until minOf(WINDOW, remaining)) {
                val a = artists[pending[j]]
                val gap = (remaining / left.getValue(a)).coerceIn(1, MAX_GAP)
                val since = lastAt[a]?.let { pos - it } ?: Int.MAX_VALUE
                val miss = (gap - since).coerceAtLeast(0)
                if (miss < bestMiss) { best = j; bestMiss = miss }
                if (miss == 0) break
            }
            val i = pending.removeAt(best)
            val a = artists[i]
            out += i
            lastAt[a] = pos
            left.merge(a, -1, Int::plus)
        }
        return out
    }

    // «Bad Bunny», «Bad Bunny, Feid» y «BAD BUNNY & Feid» son el mismo artista a efectos de separarlos.
    private fun artistOf(s: Item) = artistKey(s.artist)

    private const val WINDOW = 12
    /** Más allá de esto no se busca separar: con cuatro en medio ya no se nota. */
    private const val MAX_GAP = 4
}
