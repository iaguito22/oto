package io.github.iaguito22.oto.desktop.wallpaper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Dibuja cada estilo fuera de pantalla tras 20 s de música sintética (con el giro ya avanzado) y deja las capturas en
 * build/fondo-estilos para mirarlas. Así se ven fallos que solo salen con el tiempo, como barras y tapas que se separan.
 */
class WallpaperRenderTest {
    @Test fun cadaEstiloSeDibujaTrasVeinteSegundos() {
        val out = File("build/fondo-estilos").apply { mkdirs() }
        for (estilo in EXT.keys) {
            val sp = Spectrum()
            val sc = WallpaperScene().apply { audio = sp; p = WallpaperSettings(estilo = estilo, giro = 1f); playing = true }
            sc.ensureGeometry(700f, 700f, 1f)
            for (f in 0 until 1200) {   // 20 s a 60 fps: graves con golpe, voz y algo de agudos
                val t = f / 60.0; val hit = (f % 30) < 4
                for (i in sp.samples.indices) { val x = t + i / 48_000.0
                    sp.samples[i] = ((if (hit) .7 else .25) * sin(2 * PI * 55 * x) + .2 * sin(2 * PI * 440 * x) + .08 * sin(2 * PI * 5000 * x)).toFloat() }
                sp.update(true, 1 / 60f, .5f); sc.step(1 / 60f)
            }
            val scene = ImageComposeScene(700, 700, Density(1f)) {
                Canvas(Modifier.fillMaxSize().background(Color(0xFF101014))) { drawViz(sc, false); drawFront(sc) }
            }
            val png = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$estilo.png").writeBytes(png); scene.close()
            assertTrue(png.size > 2000)
        }
    }
}
