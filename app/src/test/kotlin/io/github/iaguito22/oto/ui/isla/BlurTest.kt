package io.github.iaguito22.oto.ui.isla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class BlurTest {
    /** Un punto blanco en medio de negro: lo que se reparte alrededor es el núcleo, igual en las dos direcciones. */
    @Test
    fun comoUnaGaussianaDeSigmaDiez() {
        val n = 121
        val px = IntArray(n * n) { 0xFF000000.toInt() }
        px[60 * n + 60] = 0xFFFFFFFF.toInt()
        // Para que no se pierda en el redondeo, el punto vale mucho: se reparte el rojo de 255 en un cuadrado.
        for (y in 55..65) for (x in 55..65) px[y * n + x] = 0xFFFFFFFF.toInt()
        blur(px, n, n)
        fun red(x: Int, y: Int) = (px[y * n + x] shr 16) and 0xFF
        fun sigma(horizontal: Boolean): Double {
            var sum = 0.0; var m2 = 0.0
            for (i in 0 until n) { val v = (if (horizontal) red(i, 60) else red(60, i)).toDouble(); sum += v; m2 += v * (i - 60) * (i - 60) }
            return sqrt(m2 / sum)
        }
        val sh = sigma(true)
        val sv = sigma(false)
        // El cuadrado de 11 ya tiene σ ≈ 3,2: el total es la suma en cuadratura con la del desenfoque (≈ 9,8).
        assertEquals(sqrt(9.83 * 9.83 + 3.16 * 3.16), sh, 0.6)
        assertEquals(sh, sv, 0.15)
        assertTrue(px.all { (it ushr 24) == 0xFF })
    }
}
