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
    fun withAllGenresTheDjRarelyHopsBetweenGenres() = runBlocking {
        val catalog = object : Catalog {
            override suspend fun artist(id: String) = null
            override suspend fun songs(info: ArtistInfo) = emptyList<Track>()
            override suspend fun radio(id: String) = emptyList<Track>()
            override suspend fun find(name: String) = null
        }
        fun genreOf(t: Track) = when {
            t.title.startsWith("urb") -> "Urbano latino"
            t.title.startsWith("rock") || t.title == "mix rock" -> "Rock"
            t.title == "mix pop" -> "Pop"
            else -> null
        }
        // Como una biblioteca de verdad: cada estilo repartido en varias listas (no todos sus artistas se «parecen»).
        fun list(id: String, prefix: String, artists: List<String>) = LocalPlaylist(id, id, (1..8).map { i ->
            val a = artists[(i - 1) % artists.size]
            Heard("$prefix-$id-$i", "$prefix $id $i", a, null, a)
        })
        val wide = Profile(
            listOf(
                list("u1", "urb", listOf("Duki", "Myke")), list("u2", "urb", listOf("Eladio", "Quevedo")),
                list("u3", "urb", listOf("Bad Bunny", "Feid")),
                list("r1", "rock", listOf("Muse", "Arctic")), list("r2", "rock", listOf("Blur", "Oasis")),
                list("r3", "rock", listOf("Pixies", "Strokes")),
                LocalPlaylist("m", "mixta", listOf(Heard("urb-m", "urb m", "Feid", null, "Feid"), Heard("rock-m", "rock m", "Muse", null, "Muse"))),
            ),
            emptyList(), emptyList(), emptyList(), now, ZoneId.of("UTC"),
        )
        var hops = 0
        var pairs = 0
        for (seed in 1..8) {
            val dj = Dj(wide, catalog, Memory(null), this, Random(seed), mode = DjMode.Adhd, genreOf = ::genreOf)
            val picks = listOfNotNull(dj.start()) + (1..14).mapNotNull { dj.next() }
            val genres = picks.mapNotNull(::genreOf)
            hops += genres.zipWithNext().count { (a, b) -> a != b }
            pairs += (genres.size - 1).coerceAtLeast(0)
        }
        // Con «Todo» sigue pudiendo cambiar de estilo (puentes, saltos), pero no a cada tanda. Antes: 5 de 112.
        assertTrue("cambios de género con «Todo»: $hops de $pairs", hops <= 2)
    }

    @Test
    fun withAllGenresALongSessionStillChangesStyleNowAndThen() = runBlocking {
        val catalog = object : Catalog {
            override suspend fun artist(id: String) = null
            override suspend fun songs(info: ArtistInfo) = emptyList<Track>()
            override suspend fun radio(id: String) = emptyList<Track>()
            override suspend fun find(name: String) = null
        }
        fun genreOf(t: Track) = when {
            t.title.startsWith("urb") -> "Urbano latino"
            t.title.startsWith("rock") -> "Rock"
            else -> null
        }
        fun list(id: String, prefix: String, artists: List<String>) = LocalPlaylist(id, id, (1..14).map { i ->
            val a = artists[(i - 1) % artists.size]
            Heard("$prefix-$id-$i", "$prefix $id $i", a, null, a)
        })
        val wide = Profile(
            listOf(
                list("u1", "urb", listOf("Duki", "Myke")), list("u2", "urb", listOf("Eladio", "Quevedo")),
                list("u3", "urb", listOf("Bad Bunny", "Feid")), list("u4", "urb", listOf("Rauw", "Mora")),
                list("r1", "rock", listOf("Muse", "Arctic")), list("r2", "rock", listOf("Blur", "Oasis")),
                list("r3", "rock", listOf("Pixies", "Strokes")), list("r4", "rock", listOf("Cure", "Smiths")),
            ),
            emptyList(), emptyList(), emptyList(), now, ZoneId.of("UTC"),
        )
        var sessionsThatChanged = 0
        var shortRuns = 0
        for (seed in 1..6) {
            val dj = Dj(wide, catalog, Memory(null), this, Random(seed), mode = DjMode.Adhd, genreOf = ::genreOf)
            val picks = listOfNotNull(dj.start()) + (1..45).mapNotNull { dj.next() }
            assertEquals("sesión entera (semilla $seed)", 46, picks.size)
            val genres = picks.mapNotNull(::genreOf)
            assertEquals(picks.size, genres.size)
            // Tramos seguidos de un mismo género.
            val runs = genres.fold(mutableListOf<Int>()) { acc, g ->
                if (acc.isEmpty() || genres[acc.sum() - 1] != g) acc += 1 else acc[acc.lastIndex]++
                acc
            }
            if (runs.size > 1) sessionsThatChanged++
            shortRuns += runs.dropLast(1).count { it < 6 }
        }
        // En ~45 trozos (≈ 1 h de DJ rápido) cambia de estilo alguna vez, y no tras tandas sueltas.
        assertTrue("sesiones que cambian de estilo: $sessionsThatChanged de 6", sessionsThatChanged >= 4)
        assertTrue("tramos cortos de un género: $shortRuns", shortRuns <= 2)
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
