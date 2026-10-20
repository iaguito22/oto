package io.github.iaguito22.oto.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestTest {
    @Test
    fun idaYVuelta() {
        val m = Manifest(
            (1..120).map { Manifest.Entry("id-$it-${"x".repeat(36)}", "PLabcdefghijklmnopqrstuvwxyz0123$it", "Lista número $it ✨ <rara>", if (it % 3 == 0) "Carpeta ${it % 5}" else null, "spotify:playlist:6yKP4u8NPZ38vLNQ3snOI$it") },
            setOf("Vacía"), setOf("d:Carpeta 1", "id-3"), setOf("liked"),
        )
        val s = m.encode()
        println("120 listas: ${s.length} caracteres")
        assertTrue(s.matches(Regex(Manifest.PREFIX + "[A-Za-z0-9_-]+")))
        assertEquals(m, Manifest.decode(s))
    }
}
