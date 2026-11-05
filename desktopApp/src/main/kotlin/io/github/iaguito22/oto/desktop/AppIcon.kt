package io.github.iaguito22.oto.desktop

import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * El icono de Oto a tamaños exactos. AWT reduce el PNG de 256 px sin suavizado (bandeja) o con un
 * único tamaño intermedio (ventana), y en pantallas 2K con escala 125–150 % se ve dentado o borroso.
 * Aquí se reduce por pasos con interpolación bilineal y se entrega ya al tamaño que pide Windows.
 */
internal object AppIcon {
    private val source: BufferedImage? by lazy {
        runCatching { Thread.currentThread().contextClassLoader.getResourceAsStream("oto-icon.png")?.use(ImageIO::read) }.getOrNull()
    }
    private val cache = HashMap<Int, BufferedImage>()

    /** Reducción progresiva (mitades) y paso final bilineal: sin dientes ni desenfoque. */
    @Synchronized fun scaled(size: Int): BufferedImage? {
        val src = source ?: return null
        cache[size]?.let { return it }
        var current = src
        var w = src.width
        while (w / 2 >= size) { w /= 2; current = draw(current, w) }
        if (w != size) current = draw(current, size)
        cache[size] = current
        return current
    }

    private fun draw(image: BufferedImage, size: Int): BufferedImage {
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(image, 0, 0, size, size, null)
        } finally { g.dispose() }
        return out
    }

    /** Todos los tamaños de Windows (barra de tareas, Alt+Tab, título) de 100 % a 200 %. */
    val windowImages: List<Image> by lazy { listOf(16, 20, 24, 32, 40, 48, 64, 96, 128, 256).mapNotNull(::scaled) }

    /** La bandeja dibuja 16 px lógicos por la escala de la pantalla principal: dárselo ya a ese tamaño. */
    fun tray(): Image? {
        val scale = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
        }.getOrDefault(1.0)
        // Mismo redondeo que WTrayIconPeer (Region.clipScale): así se copia píxel a píxel.
        return scaled(Math.round(16 * scale).toInt().coerceAtLeast(16))
    }
}
