package io.github.iaguito22.oto.ui

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.importer.Spotify
import io.github.iaguito22.oto.ui.menu.Search
import io.github.iaguito22.oto.ui.menu.Taste
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * No es una prueba: busca de verdad una consulta por línea y escribe lo que enseñaría el Menú, para compararlo a ojo
 * con Spotify. Solo corre con OTO_SEARCH=<consultas.txt>; con OTO_TASTE=<oto-spotify.json>, con sus gustos.
 */
class SearchHarness {
    @Test
    fun run() = runBlocking(Dispatchers.IO) {
        val input = System.getenv("OTO_SEARCH")
        assumeTrue(input != null)
        val taste = System.getenv("OTO_TASTE")?.let { f ->
            Taste.of(Spotify.parseExport(File(f).readText()).asSequence().flatMap { it.tracks }.map { Heard("", it.title, it.artists.joinToString(), null) })
        } ?: Taste()
        val out = StringBuilder()
        for (q in File(input!!).readLines().filter { it.isNotBlank() }) {
            val t0 = System.currentTimeMillis()
            val r = Search.run(q, taste)
            out.appendLine("## $q (${System.currentTimeMillis() - t0} ms)")
            out.appendLine("  " + (r.getOrNull()?.take(8)?.joinToString(" · ") { show(it) } ?: r.exceptionOrNull().toString()))
        }
        File(File(input).parentFile, "search-out.txt").writeText(out.toString())
    }

    private fun show(i: YTItem) = when (i) {
        is SongItem -> "♪ ${i.title} — ${i.artists.joinToString { it.name }}${if (i.isVideoSong) " [vídeo]" else ""}"
        is AlbumItem -> "💿 ${i.title} — ${i.artists?.joinToString { it.name }}"
        is ArtistItem -> "👤 ${i.title}"
        is PlaylistItem -> "≡ ${i.title}"
        else -> "? ${i.title}"
    }
}
