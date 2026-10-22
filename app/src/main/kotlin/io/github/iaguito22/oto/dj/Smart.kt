package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.traced
import kotlin.random.Random

/**
 * Listas que se hacen solas con lo tuyo, para Explorar: lo que más oyes, mezclas alrededor de tus artistas, lo que
 * hace tiempo que no suena y lo que te puso el DJ y oíste entero. Cambian cada día (el azar va por fecha), no a cada
 * vistazo. Una que se queda corta no sale: con poco que escuchar no es una lista.
 */
object Smart {
    const val PREFIX = "smart:"

    fun lists(lists: List<LocalPlaylist>, liked: List<Heard>, history: List<Heard>, plays: List<Play>, now: Long): List<LocalPlaylist> =
        traced("oto.smart") { build(lists, liked, history, plays, now) }

    private fun build(lists: List<LocalPlaylist>, liked: List<Heard>, history: List<Heard>, plays: List<Play>, now: Long): List<LocalPlaylist> {
        val profile = Profile(lists, liked, history, plays, now)
        val day = now / DAY
        val known = HashMap<String, Heard>()
        (history + liked + lists.flatMap { it.songs }).forEach { known.putIfAbsent(it.id, it) }
        fun heard(p: Play) = known[p.id] ?: Heard(p.id, p.title, p.artist, null, p.artistId)
        val saved = (liked + lists.flatMap { it.songs }).distinctBy { it.toTrack().key }
        val savedKeys = saved.mapTo(HashSet()) { it.toTrack().key }

        val out = ArrayList<LocalPlaylist>()

        // En bucle: lo que has elegido oír más de una vez este mes, por cuánto te quedas.
        plays.filter { it.at > now - 30 * DAY && it.heard && (it.dj == null || it.reward >= 0.6) }
            .groupBy { it.key }.values
            .filter { it.size >= 2 }
            .sortedByDescending { g -> g.sumOf { it.reward } }
            .take(MAX).map { heard(it.maxBy { p -> p.at }) }
            .takeIf { it.size >= 5 }
            ?.let { out += LocalPlaylist(PREFIX + "loop", "En bucle", it) }

        // Mezclas: un artista tuyo y los que comparten listas con él, con tus canciones de todos ellos.
        val used = HashSet<Profile.Artist>()
        for (seed in profile.core) {
            if (out.count { it.id.startsWith(PREFIX + "mix") } == 3) break
            if (seed in used) continue
            val near = profile.core.asSequence().filter { it !== seed && it !in used }
                .map { it to profile.sim(seed, it) }.filter { it.second >= NEAR }
                .sortedByDescending { it.second }.take(5).map { it.first }.toList()
            if (near.isEmpty()) continue
            val rnd = Random(day * 31 + seed.key.hashCode())
            val pools = (listOf(seed, seed) + near).map { a -> ArrayDeque(a.songs.values.shuffled(rnd)) }
            val songs = ArrayList<Heard>()
            val seen = HashSet<String>()
            while (songs.size < MAX && pools.any { it.isNotEmpty() }) {
                for (p in pools) {
                    val t = p.removeFirstOrNull() ?: continue
                    if (seen.add(t.key)) songs += Heard(t.id, t.title, t.artists.joinToString(", "), t.artwork, t.artistId)
                }
            }
            if (songs.size < 15) continue
            used += seed; used += near
            out += LocalPlaylist(PREFIX + "mix:" + seed.key, "Mezcla de ${seed.name}", songs.take(MAX))
        }

        // Hace tiempo que no: de lo guardado, lo que no suena desde hace mes y medio, antes lo que más guardas.
        val rnd = Random(day)
        saved.filter { (profile.heardAt[it.toTrack().key] ?: 0) < now - 45 * DAY }
            .sortedByDescending { (profile.weight[it.toTrack().key] ?: 0.0) + rnd.nextDouble() * 1.5 }
            .take(MAX)
            .takeIf { it.size >= 10 }
            ?.let { out += LocalPlaylist(PREFIX + "forgotten", "Hace tiempo que no", it) }

        // Del DJ: lo nuevo que te puso y oíste entero, sin guardarlo aún.
        plays.filter { it.dj != null && it.reward >= 0.85 && it.key !in savedKeys }
            .sortedByDescending { it.at }.distinctBy { it.key }
            .take(MAX).map(::heard)
            .takeIf { it.size >= 5 }
            ?.let { out += LocalPlaylist(PREFIX + "dj", "Lo mejor del DJ", it) }

        return out
    }

    private const val MAX = 50
    private const val NEAR = 0.12
}
