package io.github.iaguito22.oto.dj

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

class RecapTest {
    private fun at(month: Int, day: Int, hour: Int) = LocalDateTime.of(2026, month, day, hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun play(id: String, artist: String, at: Long, ms: Long, dur: Long = 200_000, dj: Kind? = null) =
        Play(id, "Canción $id", artist, null, at, ms, dur, dj)

    @Test fun countsWhatSoundedAndRanksIt() {
        val plays = listOf(
            play("a", "NewJeans", at(10, 1, 22), 200_000),
            play("a", "NewJeans", at(11, 2, 22), 180_000),
            play("b", "NewJeans", at(11, 3, 22), 120_000),
            play("c", "Alan Walker", at(11, 4, 9), 240_000, dur = 240_000),
            // Saltada: no cuenta.
            play("d", "Otro", at(11, 4, 10), 5_000),
            // Más larga que la canción (repetición en bucle mal medida): se corta a la duración.
            play("e", "Alan Walker", at(11, 5, 9), 900_000, dur = 100_000, dj = Kind.NEW),
        )
        val r = Recap.of(plays, ZoneOffset.UTC)
        assertEquals(5, r.plays)
        assertEquals((200_000 + 180_000 + 120_000 + 240_000 + 100_000) / 60_000L, r.minutes)
        assertEquals("a", r.topSongs.first().id); assertEquals(2, r.topSongs.first().times)
        assertEquals("NewJeans", r.topArtists.first().name)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 11)), r.months.map { it.month })
        assertEquals(22, r.hour)
        assertEquals(1, r.djFinds)
        // NewJeans y Alan Walker; el de la saltada no cuenta.
        assertEquals(2, r.artists)
    }

    @Test fun emptyWithoutPlays() {
        val r = Recap.of(emptyList())
        assertTrue(r.empty); assertNull(r.hour); assertTrue(r.topSongs.isEmpty())
    }
}
