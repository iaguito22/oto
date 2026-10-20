package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTest {
    private val now = 1_800_000_000_000L
    private fun song(a: String, i: Int) = Heard("$a$i", "Canción $i de $a", a, null)

    @Test
    fun mezclasEnBucleYOlvidadas() {
        // Dos escenas: A, B y C comparten listas; X e Y, otras.
        val lists = listOf(
            LocalPlaylist("1", "Uno", (1..8).flatMap { listOf(song("A", it), song("B", it), song("C", it)) }),
            LocalPlaylist("2", "Dos", (1..8).flatMap { listOf(song("A", it + 10), song("B", it + 10)) }),
            LocalPlaylist("3", "Tres", (1..10).flatMap { listOf(song("X", it), song("Y", it)) }),
        )
        // A1 la que más; otras cuatro, dos veces cada una (con menos de cinco no hay lista).
        val plays = (0 until 6).map { d -> Play("A1", "Canción 1 de A", "A", null, now - d * DAY, 200_000, 200_000) } +
            (2..5).flatMap { i -> List(2) { d -> Play("B$i", "Canción $i de B", "B", null, now - d * DAY, 200_000, 200_000) } } +
            listOf(Play("X1", "Canción 1 de X", "X", null, now - 60 * DAY, 200_000, 200_000))
        val out = Smart.lists(lists, emptyList(), emptyList(), plays, now)
        val names = out.map { it.name }
        println(names)
        assertTrue("En bucle" in names)
        assertEquals("A1", out.first { it.name == "En bucle" }.songs.first().id)
        val mixes = out.filter { it.id.startsWith(Smart.PREFIX + "mix") }
        assertEquals(2, mixes.size)
        // Una mezcla no cruza de escena.
        mixes.forEach { m -> assertTrue(m.songs.map { it.artist }.toSet().let { it.all { a -> a in "ABC" } || it.all { a -> a in "XY" } }) }
        // Lo que sonó hoy no está entre lo olvidado; lo de hace dos meses, puede.
        assertTrue(out.first { it.name == "Hace tiempo que no" }.songs.none { it.id == "A1" })
    }
}
