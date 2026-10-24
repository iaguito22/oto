package io.github.iaguito22.oto.ui.menu

import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.importer.songKey
import io.github.iaguito22.oto.playback.Radio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Canciones parecidas a una lista, para añadirlas (como las «Recomendadas» del final de una lista de Spotify): la
 * radio de YouTube Music de unas pocas de ella, intercaladas, sin las que ya tiene.
 */
object Suggest {
    // Por lista y tanda: volver a abrirla no vuelve a preguntar.
    private val cache = HashMap<Pair<String, Int>, List<SongItem>>()

    suspend fun forList(list: LocalPlaylist, round: Int): List<SongItem> {
        cache[list.id to round]?.let { return it }
        val songs = list.songs
        if (songs.isEmpty()) return emptyList()
        val rnd = Random(list.id.hashCode() * 31 + round)
        // La última que entró (lo que te gusta ahora) y dos al azar; otra tanda, otras.
        // (Me gusta va al revés: lo último, arriba.)
        val newest = if (round == 0) listOf(if (list.id == LIKED_ID) songs.first() else songs.last()) else emptyList()
        val seeds = (newest + songs.shuffled(rnd)).distinctBy { it.id }.take(SEEDS)
        val pages = withContext(Dispatchers.IO) {
            coroutineScope { seeds.map { s -> async { Radio(s.id).more().getOrDefault(emptyList()) } }.awaitAll() }
        }
        val have = songs.flatMapTo(HashSet()) { listOf(it.id, songKey(it.title, it.artist)) }
        val out = ArrayList<SongItem>()
        val seen = HashSet<String>()
        // Intercaladas: una de cada radio por turnos, así no sale todo del mismo sitio.
        val longest = pages.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (p in pages) {
            val s = p.getOrNull(i) ?: continue
            val key = songKey(s.title, s.artists.joinToString { it.name })
            if (s.id in have || key in have || !seen.add(s.id) || !seen.add(key)) continue
            out += s
        }
        return out.take(COUNT).also { if (it.isNotEmpty()) cache[list.id to round] = it }
    }

    private const val SEEDS = 3
    private const val COUNT = 5
}
