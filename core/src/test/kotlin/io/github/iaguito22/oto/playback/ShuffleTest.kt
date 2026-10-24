package io.github.iaguito22.oto.playback

import io.github.iaguito22.oto.dj.artistKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleTest {
    private val now = 1_000_000_000_000L

    /** 60 canciones: un artista con 20 (un tercio), dos con 8, y 24 sueltos. */
    private val items = List(60) { i ->
        val artist = when {
            i < 20 -> "Bad Bunny" + if (i % 3 == 0) ", Feid" else ""
            i < 28 -> "Rosalía"
            i < 36 -> "BAD BUNNY & Jhayco".takeIf { i % 2 == 0 } ?: "Quevedo"
            else -> "Solo $i"
        }
        Shuffle.Item("s$i", artist, i / 59f)
    }

    private fun runs(n: Int = 400, block: (Random) -> List<Int>) = (0 until n).map { block(Random(it)) }

    @Test
    fun todasUnaVezYLaTocadaPrimero() {
        for (order in runs { Shuffle.order(items, first = 17, random = it) }) {
            assertEquals(17, order.first())
            assertEquals(items.indices.toSet(), order.toSet())
            assertEquals(items.size, order.size)
        }
    }

    @Test
    fun casiNuncaElMismoArtistaSeguido() {
        // Barajando sin más, con un tercio de un artista salen unas 8 parejas seguidas por vuelta.
        val adjacent = runs { Shuffle.order(items, random = it) }.map { o ->
            o.zipWithNext().count { (a, b) -> artistKey(items[a].artist) == artistKey(items[b].artist) }
        }
        assertTrue("media ${adjacent.average()}", adjacent.average() < 0.5)
    }

    @Test
    fun loQueAcabaDeSonarAlFinal() {
        val heard = mapOf("s40" to now - 3_600_000L, "s41" to now - 2 * 3_600_000L)
        val pos = runs { Shuffle.order(items, heardAt = heard, now = now, random = it) }.map { o -> o.indexOf(40) }
        assertTrue("posición media ${pos.average()}", pos.average() > 40)
        // Pero no está atada al final: alguna vez sale en la primera mitad.
        assertTrue(pos.any { it < 30 })
    }

    @Test
    fun loDeHaceUnaSemanaCasiNoCuenta() {
        val heard = mapOf("s40" to now - 7 * 24 * 3_600_000L)
        val a = runs(2000) { Shuffle.order(items, heardAt = heard, now = now, random = it) }.map { it.indexOf(40) }.average()
        val b = runs(2000) { Shuffle.order(items, random = it) }.map { it.indexOf(40) }.average()
        assertTrue("$a frente a $b", a - b < 3)
    }

    @Test
    fun loYaSonadoEnLaColaVaDetras() {
        // Detrás del todo, salvo cuando hace falta alguna para separar a un artista que es casi media lista.
        val played = (36 until 46).map { "s$it" }.toSet()
        val atEnd = runs { Shuffle.order(items, played = played, random = it) }.map { o -> o.takeLast(16).count { it in 36 until 46 } }
        assertTrue("${atEnd.average()}", atEnd.average() >= 8)
    }

    @Test
    fun loNuevoYLoQueTeGustaAntesSinAtarlo() {
        val liked = setOf("s45")
        val orders = runs(2000) { Shuffle.order(items, liked = liked, random = it) }
        // [age] 0 es lo último que entró: s36 es la más nueva de las sueltas y s59 la más vieja.
        val newest = orders.map { it.indexOf(36) }.average()
        val oldest = orders.map { it.indexOf(59) }.average()
        val fav = orders.map { it.indexOf(45) }.average()
        val plain = orders.map { it.indexOf(44) }.average()
        assertTrue("nueva $newest, vieja $oldest", newest < oldest)
        assertTrue("me gusta $fav, otra $plain", fav < plain)
        // La más vieja no va siempre al final: sale en el primer cuarto bastantes veces.
        assertTrue(orders.count { it.indexOf(59) < 15 } > 300)
    }

    @Test
    fun noSigueAlArtistaDeLaQueSuena() {
        val hits = runs { Shuffle.order(items, after = "Bad Bunny", random = it) }.count { artistKey(items[it.first()].artist) == "bad bunny" }
        assertTrue("$hits", hits < 10)
    }

    @Test
    fun milesSinTardar() {
        val big = List(10_000) { Shuffle.Item("x$it", "Artista ${it % 700}", it / 9999f) }
        Shuffle.order(big, random = Random(0))
        val t = System.nanoTime()
        Shuffle.order(big, random = Random(1))
        val ms = (System.nanoTime() - t) / 1e6
        assertTrue("$ms ms", ms < 300)
    }
}
