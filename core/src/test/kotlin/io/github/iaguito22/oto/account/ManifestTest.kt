package io.github.iaguito22.oto.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

class ManifestTest {
    @Test
    fun idaYVuelta() {
        // Ids de verdad (aleatorios, no se comprimen): con la primera versión 70 listas ya eran 7800 caracteres.
        val ids = (1..400).map { UUID(Random.nextLong(), Random.nextLong()).toString() }
        val m = Manifest(ids.map(Manifest::ref), setOf("Vacía"), setOf("d:Carpeta 1", ids[3]), setOf("liked"))
        val s = m.encode()
        println("400 listas: ${s.length} caracteres")
        assertTrue(s.length < 5000)
        assertTrue(s.matches(Regex(Manifest.PREFIX + "[A-Za-z0-9_-]+")))
        assertEquals(m, Manifest.decode(s))
        assertEquals(400, ids.map(Manifest::ref).toSet().size)
    }

    @Test
    fun primeraVersionSeSigueLeyendo() {
        val old = Manifest(emptyList(), setOf("Vacía"), emptySet(), emptySet(), listOf(Manifest.Entry("a", "PLx", "Una", "C", null)))
        val v1 = org.json.JSONObject().put("v", 1)
            .put("l", org.json.JSONArray().put(org.json.JSONObject().put("i", "a").put("y", "PLx").put("n", "Una").put("d", "C")))
            .put("folders", org.json.JSONArray().put("Vacía"))
        val bytes = java.io.ByteArrayOutputStream().also { o ->
            java.util.zip.DeflaterOutputStream(o).use { it.write(v1.toString().toByteArray()) }
        }.toByteArray()
        val d = Manifest.decode(Manifest.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))!!
        assertEquals(old.old, d.old)
        assertEquals(listOf(Manifest.ref("a")), d.order)
        assertEquals(setOf("Vacía"), d.folders)
    }

    @Test
    fun descripcionDeCadaLista() {
        val m = ListMeta(UUID.randomUUID().toString(), "Carpeta ✨", "spotify:playlist:6yKP4u8NPZ38vLNQ3snOIa")
        val raw = """{"description":"Lista de Oto. ${m.encode()}","x":1}"""
        assertEquals(m, ListMeta.find(raw))
        assertEquals(null, ListMeta.find("""{"description":"${Manifest.PREFIX}abc"}"""))
    }
}
