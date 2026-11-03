package io.github.iaguito22.oto.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryActionsTest {
    @Test fun identificaLasAccionesSinConfundirlasConCanciones() {
        assertEquals("lista|con separador" to false, Library.listAction("play|lista|con separador"))
        assertEquals("lista|con separador" to true, Library.listAction("shuffle|lista|con separador"))
        assertNull(Library.listAction("shuffle|"))
        assertNull(Library.listAction("in|lista|cancion"))
        assertNull(Library.listAction("dj"))
    }
}
