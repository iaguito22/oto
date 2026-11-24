package timber.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimberShimTest {
    private class Capture : Timber.Tree() {
        val lines = ArrayList<Pair<String?, String>>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines += tag to message
        }
    }

    @After
    fun reset() {
        Timber.plant(object : Timber.Tree() {})
    }

    @Test
    fun `tag llega al arbol y solo vale para una linea`() {
        val c = Capture()
        Timber.plant(c)
        Timber.tag("oto.mix").i("Iniciando mezcla %s", "A")
        Timber.i("sin etiqueta")
        assertEquals("oto.mix" to "Iniciando mezcla A", c.lines[0])
        assertNull(c.lines[1].first)
    }

    @Test
    fun `la consola lleva hora y etiqueta`() {
        val line = Timber.DebugTree.format("oto.mix", "hola", java.time.LocalTime.of(3, 4, 5, 6_000_000))
        assertEquals("03:04:05.006 [oto.mix] hola", line)
        assertTrue(Timber.DebugTree.format(null, "x", java.time.LocalTime.of(0, 0)).startsWith("00:00:00.000 x"))
    }
}
