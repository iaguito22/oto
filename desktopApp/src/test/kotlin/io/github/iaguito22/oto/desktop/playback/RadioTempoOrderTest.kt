package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.dj.DjTempoMatch
import org.junit.Assert.assertEquals
import org.junit.Test

/** La radio reordena lo que viene por tempo (Iago, 24/11: «se podría manipular como en DJ normal»), sin perder su estilo. */
class RadioTempoOrderTest {
    private val bpm = mapOf("prev" to 100.0, "a" to 128.0, "b" to 128.0, "c" to 100.0, "d" to 128.0, "e" to 100.0)

    /** Encaja si el tempo es el mismo; sin medir, se espera; si no, lejos. */
    private fun rank(prev: String, id: String): Int {
        val x = bpm[prev] ?: return RadioTempoOrder.UNKNOWN
        val y = bpm[id] ?: return RadioTempoOrder.UNKNOWN
        return if (x == y) RadioTempoOrder.FITS else RadioTempoOrder.FAR
    }

    @Test fun adelantaLaQueEncajaDeEntreLasProximas() {
        // De las 4 siguientes se adelanta la que encaja; si ninguna encaja, sigue el orden de la radio.
        assertEquals(listOf("c", "e", "a", "b", "d"), RadioTempoOrder.reorder("prev", listOf("a", "b", "c", "d", "e"), ::rank))
    }

    @Test fun sinTemposMedidosNoCambiaNada() {
        val ids = listOf("x", "y", "z", "w")
        assertEquals(ids, RadioTempoOrder.reorder("prev", ids, ::rank))
    }

    @Test fun noMiraMasAllaDeLasCuatroSiguientes() {
        // La que encaja está la quinta: no se adelanta (la radio va ordenada por parecido a la canción de partida).
        val far = mapOf("prev" to 100.0, "a" to 128.0, "b" to 128.0, "d" to 128.0, "f" to 128.0, "c" to 100.0)
        val ids = listOf("a", "b", "d", "f", "c")
        assertEquals(ids, RadioTempoOrder.reorder("prev", ids) { p, id ->
            if (far[p] == far[id]) RadioTempoOrder.FITS else RadioTempoOrder.FAR
        })
    }

    @Test fun elVeredictoDeTempoYTonoDaElRango() {
        val fits = DjTempoMatch.TempoVerdict(DjTempoMatch.TempoFit.Fits)
        assertEquals(RadioTempoOrder.FITS, RadioTempoOrder.rankOf(fits, harmony = 0.8))
        assertEquals(RadioTempoOrder.FITS_KEY_CLASH, RadioTempoOrder.rankOf(fits, harmony = 0.2))
        assertEquals(RadioTempoOrder.UNKNOWN, RadioTempoOrder.rankOf(DjTempoMatch.TempoVerdict(DjTempoMatch.TempoFit.Unknown), harmony = null))
        assertEquals(RadioTempoOrder.UNKNOWN, RadioTempoOrder.rankOf(DjTempoMatch.TempoVerdict(DjTempoMatch.TempoFit.NotApplicable), harmony = null))
        assertEquals(RadioTempoOrder.FAR, RadioTempoOrder.rankOf(DjTempoMatch.TempoVerdict(DjTempoMatch.TempoFit.TempoFar), harmony = 0.9))
    }
}
