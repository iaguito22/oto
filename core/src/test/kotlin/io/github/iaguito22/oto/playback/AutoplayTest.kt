package io.github.iaguito22.oto.playback

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.importer.songKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AutoplayTest {
    private fun song(id: String, artist: String, title: String = "Canción $id") = SongItem(id = id, title = title, artists = listOf(Artist(artist, null)), thumbnail = "")

    @Test
    fun loQueSaleEnVariasRadiosVaAntes() {
        val a = (1..15).map { song("a$it", "A$it") }
        val b = (1..15).map { song("b$it", "B$it") }
        val both = song("x", "X")
        // En las dos, cuarta: por delante de la primera de la radio principal (la de la última que sonó).
        val out = Autoplay.merge(listOf(a.take(3) + both + a.drop(3), b.take(3) + both + b.drop(3)), emptySet(), emptyMap(), 20)
        assertEquals("x", out.first().id)
        // Y lo que solo sale en una secundaria, detrás de lo de la principal en el mismo puesto.
        assertTrue(out.indexOfFirst { it.id == "a2" } < out.indexOfFirst { it.id == "b2" })
    }

    @Test
    fun niRepetidasNiDeLaColaNiMasDeDosPorArtista() {
        val page = (1..12).map { song("m$it", "Mismo") } + (1..12).map { song("o$it", "Otro $it") } +
            song("v1", "Otro 1", "Canción o1 (Official Video)")
        val exclude = setOf("o2", songKey("Canción o3", "Otro 3"))
        val out = Autoplay.merge(listOf(page), exclude, emptyMap(), 20)
        assertEquals(2, out.count { it.artists.first().name == "Mismo" })
        assertFalse(out.any { it.id == "o2" || it.id == "o3" })
        // El vídeo de una que ya sale es la misma canción.
        assertEquals(1, out.count { songKey(it.title, it.artists.first().name) == songKey("Canción o1", "Otro 1") })
        // Mismo artista nunca seguido.
        assertTrue(out.zipWithNext().none { (x, y) -> x.artists.first().name == y.artists.first().name })
    }

    @Test
    fun loQuePasasDejaDeSalir() {
        val page = (1..6).map { song("p$it", "Pasado") } + (1..20).map { song("o$it", "Otro $it") }
        val out = Autoplay.merge(listOf(page), emptySet(), mapOf("pasado" to 2), 10)
        assertTrue(out.none { it.artists.first().name == "Pasado" })
    }

    @Test
    fun semillasDeArtistasDistintosEmpezandoPorLaUltima() {
        val items = (0 until 30).map { Shuffle.Item("s$it", "Artista ${it % 4}", null) }
        repeat(50) { r ->
            val seeds = Autoplay.seeds(items, "s29", emptySet(), Random(r))
            assertEquals("s29", seeds.first())
            assertEquals(3, seeds.size)
            assertEquals(3, seeds.map { id -> items.first { it.id == id }.artist }.toSet().size)
            // De las que acaban de sonar, no de cualquier parte de la lista.
            assertTrue(seeds.all { it.removePrefix("s").toInt() >= 21 })
        }
    }

    @Test
    fun alAcabarseSiembraDeLoQueOisteEntero() = runBlocking {
        val opened = ArrayList<String>()
        class Fake(seed: String) : Autoplay.Source {
            var pages = 1
            override val exhausted get() = pages == 0
            val items = (1..10).map { song("$seed-$it", "$seed artista $it") }
            override suspend fun more(): Result<List<SongItem>> { pages--; return Result.success(items) }
        }
        val auto = Autoplay(listOf("a")) { opened += it; Fake(it) }
        val first = auto.next(emptySet(), "a")
        auto.heard(first[0].id, "x", 200_000, 210_000)
        auto.next(first.mapTo(HashSet()) { it.id }, "a")
        assertEquals(listOf("a", first[0].id), opened)
    }
}
