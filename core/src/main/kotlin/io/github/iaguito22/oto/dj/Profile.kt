package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import kotlin.math.E
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Tus gustos, tal y como los ve el DJ: de tus listas, Me gusta y lo que escuchas. Cada lista es una escena (anime,
 * reguetón, lo del gimnasio…): dos artistas se parecen si comparten listas, y más cuanto más pequeña y temática es
 * la lista; una lista de miles de canciones apenas dice nada.
 *
 * Lo que puso el DJ no suma como lo que eliges tú: si contara igual, lo que pone se reforzaría solo y acabaría
 * poniendo siempre lo mismo. Solo corrige, arriba o abajo, según cuánto te quedas cuando lo pone él.
 */
class Profile(lists: List<LocalPlaylist>, liked: List<Heard>, history: List<Heard>, plays: List<Play>, val now: Long) {
    class Artist(val key: String) {
        var name = ""
        var id: String? = null
        /** Las suyas que tienes, por canción ([Track.key]). */
        val songs = LinkedHashMap<String, Track>()
        var liked = 0
        /** Lista → cuánto está en ella. */
        val lists = HashMap<Int, Double>()
        var norm = 0.0
        /** Lo que lo escuchas porque quieres, con olvido de un mes. */
        var organic = 0.0
        /** Lo que te lo puso el DJ y cuánto te quedaste (con el mismo olvido). */
        var djTimes = 0.0
        var djHeard = 0.0
        /** Veces que lo puso el DJ esta semana. */
        var djWeek = 0
        var affinity = 0.0

        override fun toString() = name
    }

    val artists = HashMap<String, Artist>()
    private val byId = HashMap<String, Artist>()

    /** Canción → en cuántas de tus listas está (Me gusta cuenta doble): las de siempre pesan más. */
    val weight = HashMap<String, Double>()

    /** Tus canciones sin versión ([Track.base] y artista): un remix de una tuya no es nada nuevo. */
    val bases = HashSet<String>()

    /** Canción → la última vez que sonó de verdad (más de 30 s). */
    val heardAt = HashMap<String, Long>()

    /** Lo que puso el DJ el último mes, por id y por canción: no lo repite. */
    val djRecent = HashSet<String>()

    /** Los artistas de los que tienes algo, de más a menos tuyos. */
    val core: List<Artist>

    init {
        val all = lists.filter { it.songs.isNotEmpty() } + LocalPlaylist("", "", liked)
        all.forEachIndexed { l, list ->
            val w = 1 / ln(E + list.songs.size / 25.0)
            for (h in list.songs) {
                val t = h.toTrack()
                val a = artist(t.who, t.artist, t.artistId)
                a.songs.getOrPut(t.key) { t }
                bases += t.base + "|" + t.who
                a.lists[l] = (a.lists[l] ?: 0.0) + w
                val isLiked = l == all.lastIndex
                if (isLiked) a.liked++
                weight[t.key] = (weight[t.key] ?: 0.0) + if (isLiked) 2.0 else 1.0
            }
        }
        // Lo último que sonó, por si el diario de escuchas aún está vacío (recién instalado).
        history.forEachIndexed { i, h ->
            val t = h.toTrack()
            heardAt.putIfAbsent(t.key, now - i * 30 * 60_000L)
            artist(t.who, t.artist, t.artistId).organic += 0.3
        }
        for (p in plays) {
            val age = (now - p.at).coerceAtLeast(0)
            val fade = exp(-age / (30.0 * DAY))
            val a = artist(p.who, p.artist, p.artistId)
            if (p.heard) heardAt[p.key] = maxOf(heardAt[p.key] ?: 0, p.at)
            if (p.dj == null) a.organic += p.reward * fade
            else {
                a.djTimes += fade
                a.djHeard += p.reward * fade
                if (age < 30 * DAY) { djRecent += p.id; djRecent += p.key }
                if (age < 7 * DAY) a.djWeek++
            }
        }
        for (a in artists.values) {
            a.norm = a.lists.values.sumOf { it * it }
            // Cuánto te quedas cuando lo pone el DJ, frente a lo normal (0,6): de ×0,5 a ×1,5, sin sumar volumen.
            val rate = djRate(a)
            val base = sqrt(a.songs.size + a.liked.toDouble()) + 1.5 * sqrt(a.organic)
            // Uno que descubrió el DJ y te gustó entra, poquito, en lo tuyo.
            a.affinity = if (base > 0) base * (0.5 + rate) else if (a.djTimes >= 1 && rate > 0.65) 0.6 else 0.0
        }
        core = artists.values.filter { it.songs.isNotEmpty() || it.organic > 0.5 }.sortedByDescending { it.affinity }
    }

    private fun artist(key: String, name: String, id: String?): Artist {
        val a = id?.let(byId::get) ?: artists.getOrPut(key) { Artist(key) }
        if (a.name.isEmpty()) a.name = name
        if (a.id == null && id != null) { a.id = id; byId[id] = a }
        return a
    }

    /** De 0 a 1: cuánto te quedas cuando te lo pone el DJ; sin datos, 0,6. */
    fun djRate(a: Artist) = (a.djHeard + 1.2) / (a.djTimes + 2)

    fun find(id: String?, name: String): Artist? = id?.let(byId::get) ?: artists[artistKey(name)]

    /** De 0 a casi 1: cuánto comparten listas. Una sola lista enorme en común apenas suma. */
    fun sim(a: Artist, b: Artist): Double {
        if (a === b) return 1.0
        val (small, big) = if (a.lists.size <= b.lists.size) a to b else b to a
        var dot = 0.0
        for ((l, v) in small.lists) big.lists[l]?.let { dot += v * it }
        return if (dot == 0.0) 0.0 else dot / sqrt((a.norm + K) * (b.norm + K))
    }

    private companion object {
        // Sin esto, dos artistas con una canción cada uno en la misma lista gigante saldrían idénticos.
        const val K = 0.5
    }
}
