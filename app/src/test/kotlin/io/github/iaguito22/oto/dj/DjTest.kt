package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DjTest {
    /** Sin red: cada artista tiene diez canciones que no tienes y se parece al siguiente. */
    private class Fake : Catalog {
        override suspend fun artist(id: String) = ArtistInfo(
            id, id, (1..10).map { Track("$id-$it", "$id canción $it", listOf(id), listOf(id)) },
            listOf("x$id" to "x$id"), null,
        )
        override suspend fun songs(info: ArtistInfo) = info.top
        override suspend fun radio(id: String) = emptyList<Track>()
        override suspend fun find(name: String) = name
    }

    private val lists = (0 until 6).map { l ->
        LocalPlaylist("l$l", "Lista $l", (0 until 30).map { i -> val a = "a${l * 3 + i % 3}"; Heard("$a-$i", "$a tuya $i", a, null, a) })
    }

    @Test
    fun noRepiteEnUnMes() = runBlocking {
        val plays = ArrayList<Play>()
        val memory = Memory(null)
        val seen = HashSet<String>()
        for (day in 0 until 7) {
            val now = 1_000_000_000_000L + day * DAY
            val dj = Dj(Profile(lists, emptyList(), emptyList(), plays, now), Fake(), memory, this, Random(day))
            var t = dj.start()
            var n = 0
            while (t != null && n < 15) {
                dj.settle()
                assertTrue("${t.title} ya sonó otro día", seen.add(t.key))
                plays += Play(t.id, t.title, t.artist, t.artistId, now + n * 200_000L, 200_000, 200_000, dj.picks.last().kind)
                dj.heard(t.id, 200_000, 200_000)
                t = dj.next()
                n++
            }
            assertEquals(15, n)
        }
    }

    @Test
    fun soloCuentaCuantoTeQuedas() {
        assertEquals(0.0, reward(12_000, 200_000), 0.0)
        assertEquals(1.0, reward(190_000, 200_000), 0.0)
        assertTrue(reward(60_000, 200_000) in 0.3..0.5)
    }
}
