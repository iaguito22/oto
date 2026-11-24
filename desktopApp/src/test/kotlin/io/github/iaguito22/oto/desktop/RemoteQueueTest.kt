package io.github.iaguito22.oto.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/** La cola del móvil vista desde el ordenador: lo que viene detrás de lo que suena, como la cola de aquí. */
class RemoteQueueTest {
    @Test fun empiezaDetrasDeLaQueSuena() {
        // El móvil va por la 6 de 10: no se vuelven a ver la 1 a la 5 como si la cola empezara de nuevo.
        assertEquals(listOf(6, 7, 8, 9), remoteUpcoming(count = 10, current = 5, loop = false))
    }

    @Test fun enBucleDetrasDeLaUltimaVuelveLaPrimera() {
        assertEquals(listOf(6, 7, 8, 9, 0, 1, 2, 3, 4), remoteUpcoming(count = 10, current = 5, loop = true))
    }

    @Test fun sinSaberLaQueSuenaSeVeEntera() {
        assertEquals(listOf(0, 1, 2), remoteUpcoming(count = 3, current = -1, loop = false))
    }

    @Test fun enLaUltimaNoQuedaNada() {
        assertEquals(emptyList<Int>(), remoteUpcoming(count = 3, current = 2, loop = false))
    }
}
