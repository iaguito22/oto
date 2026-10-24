package io.github.iaguito22.oto.playback

import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.dj.artistKey
import io.github.iaguito22.oto.importer.songKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow
import kotlin.random.Random

/**
 * Lo que suena cuando se acaba una lista (como la reproducción automática de Spotify). No es solo la radio de la última
 * canción, que se va enseguida hacia su artista: son las radios de la última y de otras dos de las que acaban de sonar
 * (el ambiente de ahora, no toda la lista: en una lista variada eso alternaría géneros), y lo que sale en más de una va
 * antes. Mientras suena aprende como el DJ: un artista que pasas deja de salir y lo que oyes entero siembra las radios
 * siguientes.
 *
 * [next] no es seguro entre hilos (una cada vez); [heard] sí se puede llamar mientras tanto, desde el principal.
 */
class Autoplay(seeds: List<String>, private val open: (String) -> Source = { Radio(it).asSource() }) {
    /** Una radio, página a página (ver [Radio]): para poder probar sin red. */
    interface Source {
        val exhausted: Boolean
        suspend fun more(): Result<List<SongItem>>
    }

    private val radios = ArrayList(seeds.map(open))
    private val used = HashSet(seeds)
    /** Artista → veces que lo pasaste. */
    private val skipped = ConcurrentHashMap<String, Int>()
    /** Lo que oíste entero: de ahí salen las radios siguientes. */
    private val kept = CopyOnWriteArrayList<String>()

    /**
     * Las siguientes, ya mezcladas y sin nada de [exclude] (ids y [songKey] de lo que está en la cola o sonó hace
     * poco). Vacío si la red falla. [last]: la última de la cola, por si hay que sembrar de nuevo y no oíste ninguna entera.
     */
    suspend fun next(exclude: Set<String>, last: String?, count: Int = BATCH): List<SongItem> {
        if (radios.all { it.exhausted }) reseed(last)
        val pages = coroutineScope {
            radios.map { r -> async { if (r.exhausted) emptyList() else r.more().getOrNull().orEmpty() } }.awaitAll()
        }
        return merge(pages, exclude, skipped, count)
    }

    /** Cuánto se oyó de una de las suyas ([artist]: todos, como en la cola): menos de 30 s es pasarla; casi entera, quedársela. */
    fun heard(id: String, artist: String, ms: Long, durMs: Long) {
        if (ms < SKIP_MS && (durMs <= 0 || ms < durMs / 2)) skipped.merge(artistKey(artist), 1, Int::plus)
        else if (durMs > 0 && ms >= durMs * 0.6) kept += id
    }

    /** Las radios se acabaron: otras, desde lo último que oíste entero o, si nada, desde la última de la cola. */
    private fun reseed(last: String?) {
        val fresh = kept.asReversed().filter { it !in used }.take(SEEDS).ifEmpty { listOfNotNull(last?.takeIf { it !in used }) }
        if (fresh.isEmpty()) return
        used += fresh
        radios.clear()
        fresh.mapTo(radios, open)
    }

    companion object {
        const val BATCH = 20
        const val SEEDS = 3
        /** De cuántas de las últimas salen las otras semillas. */
        private const val RECENT = 8
        private const val SKIP_MS = 30_000L
        /** Rango a partir del cual una de la radio ya cuenta la mitad (fusión por rangos recíprocos). */
        private const val K = 8.0
        private const val PER_ARTIST = 2
        private const val GAP = 3

        /**
         * De dónde salen las radios: la última de la lista ([items], en el orden en que suenan) y otras [SEEDS] − 1
         * de las [RECENT] anteriores, cada una de un artista distinto; más probable lo más cercano al final y lo que
         * te gusta.
         */
        fun seeds(items: List<Shuffle.Item>, last: String?, liked: Set<String>, random: Random = Random.Default): List<String> {
            val out = ArrayList<String>()
            val artists = HashSet<String>()
            val end = items.indexOfLast { it.id == last }.takeIf { it >= 0 } ?: items.lastIndex
            items.getOrNull(end)?.let { out += it.id; artists += artistKey(it.artist) }
            val pool = items.subList((end - RECENT).coerceAtLeast(0), end.coerceAtLeast(0)).withIndex().toMutableList()
            while (out.size < SEEDS && pool.isNotEmpty()) {
                val options = pool.filter { artistKey(it.value.artist) !in artists }.ifEmpty { break }
                val w = options.map { (i, s) -> (1.0 + i) * if (s.id in liked) 1.5 else 1.0 }
                var r = random.nextDouble() * w.sum()
                val pick = options[w.indices.firstOrNull { r -= w[it]; r <= 0 } ?: options.lastIndex]
                out += pick.value.id
                artists += artistKey(pick.value.artist)
                pool -= pick
            }
            return out
        }

        /**
         * Funde varias radios: cada canción suma 1/(K + su puesto) en cada radio en que sale (en la de la primera, la
         * última que sonó, el doble: de ahí se sigue), así que lo que sale en varias gana. Luego, no más de [PER_ARTIST] por artista en cada tanda, menos de los que pasaste, y nunca el
         * mismo artista a menos de [GAP] canciones si se puede evitar.
         */
        fun merge(
            pages: List<List<SongItem>>,
            exclude: Set<String>,
            skipped: Map<String, Int>,
            count: Int,
        ): List<SongItem> {
            class Scored(val song: SongItem, val artist: String, var score: Double)
            val byKey = LinkedHashMap<String, Scored>()
            for ((r, page) in pages.withIndex()) for ((rank, s) in page.withIndex()) {
                if (s.id in exclude) continue
                val name = s.artists.joinToString { it.name }
                val key = songKey(s.title, name)
                if (key in exclude) continue
                byKey.getOrPut(key) { Scored(s, artistKey(name), 0.0) }.score += (if (r == 0) 2.0 else 1.0) / (K + rank)
            }
            for (c in byKey.values) skipped[c.artist]?.let { c.score *= 0.25.pow(it) }
            val ranked = ArrayDeque(byKey.values.sortedByDescending { it.score })
            val out = ArrayList<SongItem>()
            val perArtist = HashMap<String, Int>()
            val recent = ArrayDeque<String>()
            while (out.size < count && ranked.isNotEmpty()) {
                val window = minOf(ranked.size, 10)
                val i = (0 until window).firstOrNull { ranked[it].artist !in recent && (perArtist[ranked[it].artist] ?: 0) < PER_ARTIST }
                    ?: (0 until window).firstOrNull { (perArtist[ranked[it].artist] ?: 0) < PER_ARTIST }
                if (i == null) { ranked.removeFirst(); continue }
                val c = ranked.removeAt(i)
                out += c.song
                perArtist.merge(c.artist, 1, Int::plus)
                recent.addLast(c.artist)
                if (recent.size >= GAP) recent.removeFirst()
            }
            return out
        }
    }
}

fun Radio.asSource() = object : Autoplay.Source {
    override val exhausted get() = this@asSource.exhausted
    override suspend fun more() = this@asSource.more()
}
