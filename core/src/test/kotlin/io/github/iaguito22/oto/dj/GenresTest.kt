package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

class GenresTest {
    private val now = 1_780_000_000_000L

    /** Dos listas: reguetón (tres artistas) y rock (tres). «mixto» tiene una de cada. */
    private fun profile(): Profile {
        fun songs(prefix: String, artists: List<String>) = (1..12).map { i ->
            val a = artists[(i - 1) % artists.size]
            Heard("$prefix-$i", "$prefix tema $i", a, null, a)
        }
        val urb = songs("urb", listOf("Duki", "Myke", "Eladio")) + Heard("mix-1", "mix pop", "Mixto", null, "Mixto")
        val rock = songs("rock", listOf("Muse", "Arctic", "Blur")) + Heard("mix-2", "mix rock", "Mixto", null, "Mixto")
        return Profile(listOf(LocalPlaylist("u", "urbano", urb), LocalPlaylist("r", "rock", rock)), emptyList(), emptyList(), emptyList(), now, ZoneId.of("UTC"))
    }

    /** Género por título: lo que lleva «urb» es urbano, «rock» rock, «mix pop» pop. */
    private fun store() = GenreStore(
        file = null,
        songLookup = { title, _ ->
            when {
                title.startsWith("urb") -> "Urbano latino"
                title.startsWith("rock") || title == "mix rock" -> "Rock"
                title == "mix pop" -> "Pop"
                else -> null
            }
        },
        artistLookup = { null },
        now = { now },
    )

    @Test
    fun genresComeFromSongsAndRankByWhatYouListen() = runBlocking {
        val p = profile()
        val g = store()
        g.fill(p, max = 100)
        val top = g.top(p)
        assertEquals(setOf("Urbano latino", "Rock"), top.map { it.name }.toSet())
        val rock = top.first { it.name == "Rock" }
        // Por canción: la de rock de «Mixto» cuenta como rock y la pop no.
        assertTrue(artistKey("Mixto").let { k -> p.artists.getValue(k).songs.keys.any { it in rock.songs } })
        assertEquals(13, rock.songs.size)
    }

    @Test
    fun familiesGroupItunesNames() {
        assertEquals("Urbano latino", GenreStore.family("Urbano latino"))
        assertEquals("Hip-hop/Rap", GenreStore.family("Rap alternativo"))
        assertEquals("R&B", GenreStore.family("R&B/Soul"))
        assertEquals("K-Pop", GenreStore.family("K-Pop"))
        assertEquals("Pop latino", GenreStore.family("Pop latino"))
        assertEquals("Salsa y Tropical".let { "Latino" }, GenreStore.family("Salsa y Tropical"))
    }

    @Test
    fun djSticksToTheChosenGenre() = runBlocking {
        val p = profile()
        val g = store()
        g.fill(p, max = 100)
        val rock = g.top(p).first { it.name == "Rock" }
        val catalog = object : Catalog {
            override suspend fun artist(id: String) = null
            override suspend fun songs(info: ArtistInfo) = emptyList<Track>()
            override suspend fun radio(id: String) = emptyList<Track>()
            override suspend fun find(name: String) = null
        }
        val dj = Dj(p, catalog, Memory(null), this, Random(3), genre = rock, genreOf = { g.genreOf(it, p) })
        val picks = listOfNotNull(dj.start()) + (1..6).mapNotNull { dj.next() }
        val rockish = picks.count { g.genreOf(it, p) == "Rock" }
        assertTrue("con Rock elegido, casi todo es rock ($rockish de ${picks.size})", rockish >= picks.size - 1)
    }

    @Test
    fun skippingTheTandaMovesAway() = runBlocking {
        val p = profile()
        val catalog = object : Catalog {
            override suspend fun artist(id: String) = null
            override suspend fun songs(info: ArtistInfo) = emptyList<Track>()
            override suspend fun radio(id: String) = emptyList<Track>()
            override suspend fun find(name: String) = null
        }
        val dj = Dj(
            p,
            catalog,
            Memory(null),
            this,
            Random(5),
            genreOf = { t ->
                when {
                    t.title.startsWith("urb") -> "Urbano latino"
                    t.title.startsWith("rock") -> "Rock"
                    else -> null
                }
            },
        )
        val first = dj.start()!!
        val before = dj.tanda
        val wasUrban = first.title.startsWith("urb")
        dj.skipTanda()
        val next = dj.next()!!
        assertNotEquals(before, dj.tanda)
        // Las dos listas no comparten nada: saltar el ambiente lleva a la otra.
        assertEquals(!wasUrban, next.title.startsWith("urb"))
    }
}
