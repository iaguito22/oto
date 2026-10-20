package io.github.iaguito22.oto.dj

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.importer.Spotify
import io.github.iaguito22.oto.importer.songKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * No es una prueba: monta el DJ con tus listas de Spotify y lo pone a pinchar [días] sesiones seguidas, una por día,
 * con un oyente simulado (se queda con lo tuyo más que con lo nuevo). Escribe cada sesión y cuánto se repite de un
 * día a otro. Solo corre con OTO_DJ=<oto-spotify.json>; OTO_DJ_DIR: dónde guardar la caché y lo escrito.
 */
class DjHarness {
    @Test
    fun run() = runBlocking {
        val input = System.getenv("OTO_DJ")
        assumeTrue(input != null)
        val dir = File(System.getenv("OTO_DJ_DIR") ?: "build/dj").apply { mkdirs() }
        val days = System.getenv("OTO_DJ_DAYS")?.toInt() ?: 10
        val length = System.getenv("OTO_DJ_SONGS")?.toInt() ?: 40
        val sources = Spotify.parseExport(File(input!!).readText())
        val heard = { t: io.github.iaguito22.oto.importer.SourceTrack ->
            Heard("sp:" + songKey(t.title, t.artists.joinToString()), t.title, t.artists.joinToString(), null)
        }
        val lists = sources.filter { !it.liked }.map { LocalPlaylist(it.id, it.name, it.tracks.map(heard)) }
        val liked = sources.filter { it.liked }.flatMap { it.tracks.map(heard) }

        File(dir, "plays.jsonl").delete()
        File(dir, "dj.json").delete()
        val plays = Plays(File(dir, "plays.jsonl"))
        val catalog = Resolving(YouTubeCatalog(File(dir, "cache")))
        val random = Random(System.getenv("OTO_DJ_SEED")?.toInt() ?: 7)
        val out = StringBuilder()
        val sessions = ArrayList<List<Dj.Pick>>()
        val start = System.currentTimeMillis()

        for (day in 0 until days) {
            val now = start + day * DAY
            val t0 = System.currentTimeMillis()
            val profile = Profile(lists, liked, emptyList(), plays.all(), now)
            val build = System.currentTimeMillis() - t0
            val memory = Memory(File(dir, "dj.json"))
            out.appendLine("## Día ${day + 1} (perfil en $build ms, ${profile.core.size} artistas)")
            val dj = Dj(profile, catalog, memory, this, random) { out.appendLine("  · $it") }
            var t = dj.start()
            var i = 0
            while (t != null && i < length) {
                dj.settle()
                val p = dj.picks.last()
                // El oyente: lo tuyo lo oye casi siempre; lo nuevo, algo más de la mitad de las veces.
                val stay = random.nextDouble() < when (p.kind) { Kind.FAMILIAR -> 0.85; Kind.DEEP -> 0.65; else -> 0.55 }
                val ms = if (stay) 200_000L else 12_000L
                plays.add(Play(t.id, t.title, t.artist, t.artistId, now + i * 210_000L, ms, 200_000L, p.kind))
                dj.heard(t.id, ms, 200_000L)
                val mark = when (p.kind) { Kind.FAMILIAR -> "  tuya"; Kind.DEEP -> "  suya"; Kind.NEW -> " nueva"; Kind.BRIDGE -> "puente" }
                out.appendLine("$mark ${if (stay) "✓" else "✗"} ${t.artists.joinToString()} — ${t.title}   ← ${p.vias.take(3).joinToString()}")
                t = dj.next()
                i++
            }
            sessions += dj.picks.toList()
            out.appendLine(stats(dj.picks, sessions))
        }
        val days2 = sessions.flatMap { s -> s.map { it.track.artist }.toSet() }.groupingBy { it }.eachCount()
        out.appendLine("Artistas que más días salen: " + days2.entries.sortedByDescending { it.value }.take(15).joinToString { "${it.key} (${it.value})" })
        val songs = sessions.flatten().groupBy { it.track.key }.filter { it.value.size > 1 }
        out.appendLine("Canciones repetidas: " + songs.entries.joinToString { "${it.value.first().track.title} — ${it.value.first().track.artist} ×${it.value.size}" })
        File(dir, "dj-out.txt").writeText(out.toString())
        println(out)
    }

    private fun stats(s: List<Dj.Pick>, all: List<List<Dj.Pick>>): String {
        val kinds = s.groupingBy { it.kind }.eachCount()
        val artists = s.map { it.track.who }.toSet()
        val before = all.dropLast(1)
        val songsBefore = before.flatten().map { it.track.key }.toSet()
        val artistsBefore = before.takeLast(7).flatten().map { it.track.who }.toSet()
        val repeatedSongs = s.count { it.track.key in songsBefore }
        val repeatedArtists = artists.count { it in artistsBefore }
        return "→ ${s.size} canciones, ${artists.size} artistas; $kinds; repetidas de días anteriores: $repeatedSongs; " +
            "artistas ya puestos la última semana: $repeatedArtists/${artists.size}\n"
    }

    /** Las tuyas de Spotify no tienen id de YouTube: para pedir su radio se buscan. */
    private class Resolving(private val inner: Catalog) : Catalog by inner {
        override suspend fun radio(id: String): List<Track> {
            if (!id.startsWith("sp:")) return inner.radio(id)
            val (title, artist) = id.removePrefix("sp:").split('|').let { it[0] to it.getOrElse(1) { "" } }
            val hit = YouTube.search("$title $artist", YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items?.filterIsInstance<SongItem>()?.firstOrNull()
                ?: return emptyList()
            return inner.radio(hit.id)
        }
    }
}
