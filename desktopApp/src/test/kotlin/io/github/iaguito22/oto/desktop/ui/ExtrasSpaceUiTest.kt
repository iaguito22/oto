package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.desktop.wallpaper.WallpaperStore
import io.github.iaguito22.oto.desktop.wallpaper.wallpaperSupported
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Ajustes → Extras dibujado fuera de pantalla, con clics simulados (sin mover el ratón de nadie).
 * Deja capturas en build/fondo-ui para mirarlas.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ExtrasSpaceUiTest {
    private val out = File("build/fondo-ui").apply { mkdirs() }

    private fun ImageComposeScene.shot(name: String) {
        render(System.nanoTime())
        File(out, "$name.png").writeBytes(render(System.nanoTime() + 1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    private fun ImageComposeScene.click(x: Float, y: Float) {
        sendPointerEvent(PointerEventType.Move, Offset(x, y)); sendPointerEvent(PointerEventType.Press, Offset(x, y)); sendPointerEvent(PointerEventType.Release, Offset(x, y))
        render(System.nanoTime())
    }

    @Test fun extrasSeVeYSeEnciendeYApaga() {
        val dir = createTempDirectory("oto-extras").toFile()
        OtoDirs.init(File(dir, "files"), File(dir, "cache"))
        assertEquals("apagado por defecto", false, WallpaperStore.enabled.value)
        var back = false
        val scene = ImageComposeScene(760, 1500, Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF151A18))) { ExtrasSpace(Color(0xFF8FD0C6), onBack = { back = true }) }
        }
        try {
            scene.shot("1-inicio")
            // fila «Fondo de escritorio vivo»: debajo de la cabecera
            scene.click(380f, Y_TOGGLE)
            scene.shot("2-tras-clic")
            assertEquals("solo Windows permite encender el fondo", wallpaperSupported, WallpaperStore.enabled.value)
            scene.click(380f, Y_TOGGLE)
            assertEquals("y lo apaga", false, WallpaperStore.enabled.value)
            // apartado «Fondo» (plegado) → se abre
            scene.click(380f, Y_FONDO)
            scene.shot("3-fondo-abierto")
            scene.click(30f, 30f)
            assertTrue("la cabecera vuelve a Ajustes", back)
        } finally {
            scene.close()
            WallpaperStore.setEnabled(false)
            dir.deleteRecursively()
        }
    }

    companion object { const val Y_TOGGLE = 120f; const val Y_FONDO = 1248f }
}
