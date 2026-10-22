package io.github.iaguito22.oto.playback

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.dj.artistKey
import io.github.iaguito22.oto.importer.Spotify
import io.github.iaguito22.oto.importer.songKey
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * No es una prueba: compara, con tus listas de Spotify, lo que sigue a una lista acabada con la radio de la última
 * canción (lo de antes) y con [Autoplay] (varias radios fundidas). Para cada lista mide, en las 20 primeras y en las
 * 20 siguientes: cuántas son de artistas de la lista, cuántos artistas distintos de la lista salen, cuántas son del
 * artista de la última y cuántas veces se repite artista seguido. Solo corre con OTO_AUTO=<oto-spotify.json>.
 */
class AutoplayHarness {
    private class Stats(val name: String) {
        val fit = ArrayList<Double>(); val fit2 = ArrayList<Double>(); val cover = ArrayList<Int>()
        val lastShare = ArrayList<Double>(); val artists = ArrayList<Int>(); val adjacent = ArrayList<Int>()
        override fun toString() = "%-8s de la lista %.0f %% (luego %.0f %%), artistas de la lista %.1f, del de la última %.0f %%, artistas distintos %.1f, mismo artista seguido %.1f".format(
            name, 100 * fit.average(), 100 * fit2.average(), cover.average(), 100 * lastShare.average(), artists.average(), adjacent.average(),
        )
    }

    @Test
    fun run() = runBlocking {
        val input = System.getenv("OTO_AUTO")
        assumeTrue(input != null)
        val n = System.getenv("OTO_AUTO_N")?.toInt() ?: 12
        val out = StringBuilder()
        val lists = Spotify.parseExport(File(input!!).readText()).filter { !it.liked && it.tracks.size in 20..400 }
            .shuffled(Random(5)).take(n)
        val old = Stats("radio"); val new = Stats("mezcla")
        for (l in lists) {
            val listArtists = l.tracks.mapTo(HashSet()) { artistKey(it.artists.firstOrNull().orEmpty()) }
            val exclude = l.tracks.mapTo(HashSet()) { songKey(it.title, it.artists.joinToString()) }
            // Se buscan en YouTube las nueve últimas, como si la lista hubiera sonado en orden hasta el final.
            val sample = l.tracks.takeLast(9).reversed().mapNotNull { t ->
                YouTube.search("${t.title} ${t.artists.firstOrNull().orEmpty()}", YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    ?.items?.filterIsInstance<SongItem>()?.firstOrNull()
            }
            val last = sample.firstOrNull() ?: continue
            exclude += sample.map { it.id }
            val lastArtist = artistKey(last.artists.firstOrNull()?.name.orEmpty())

            // Antes: la radio de la última, página a página.
            val radio = Radio(last.id)
            val a = ArrayList<SongItem>()
            val seen = HashSet(exclude)
            repeat(4) {
                if (a.size >= 40) return@repeat
                radio.more().getOrNull().orEmpty().forEach { s ->
                    val k = songKey(s.title, s.artists.joinToString { it.name })
                    if (s.id !in seen && seen.add(k)) { seen += s.id; a += s }
                }
            }
            // Ahora: varias radios de la lista, fundidas, en dos tandas.
            val items = sample.reversed().map { Shuffle.Item(it.id, it.artists.joinToString { x -> x.name }, null) }
            val auto = Autoplay(Autoplay.seeds(items, last.id, emptySet(), Random(1)))
            val ex = HashSet(exclude)
            val b = ArrayList<SongItem>()
            repeat(2) {
                val batch = auto.next(ex, last.id)
                batch.forEach { s -> ex += s.id; ex += songKey(s.title, s.artists.joinToString { x -> x.name }) }
                b += batch
            }
            measure(old, a, listArtists, lastArtist)
            measure(new, b, listArtists, lastArtist)
            out.appendLine("## ${l.name} (${l.tracks.size}) — última: ${last.title} · ${last.artists.joinToString { it.name }}")
            out.appendLine("  radio : " + a.take(20).joinToString(" | ") { it.artists.firstOrNull()?.name.orEmpty() + " — " + it.title })
            out.appendLine("  mezcla: " + b.take(20).joinToString(" | ") { it.artists.firstOrNull()?.name.orEmpty() + " — " + it.title })
        }
        out.appendLine()
        out.appendLine(old.toString())
        out.appendLine(new.toString())
        File(System.getenv("OTO_AUTO_OUT") ?: "build/autoplay.txt").writeText(out.toString())
        println(out)
    }

    private fun measure(s: Stats, songs: List<SongItem>, list: Set<String>, last: String) {
        val who = songs.map { artistKey(it.artists.firstOrNull()?.name.orEmpty()) }
        val first = who.take(20)
        if (first.isEmpty()) return
        s.fit += first.count { it in list }.toDouble() / first.size
        who.drop(20).takeIf { it.isNotEmpty() }?.let { r -> s.fit2 += r.count { it in list }.toDouble() / r.size }
        s.cover += first.filter { it in list }.toSet().size
        s.lastShare += first.count { it == last }.toDouble() / first.size
        s.artists += first.toSet().size
        s.adjacent += first.zipWithNext().count { (x, y) -> x == y }
    }
}
