package io.github.iaguito22.oto.desktop

import kotlin.math.roundToInt

/**
 * Zonas de Snap de Windows. El Snap lo hace Windows (arrastre con su propio bucle, ver [beginSystemMove]);
 * Oto solo reconoce el hueco en el que Windows dejó la ventana arrastrada y ajusta el conjunto entero a él.
 */
internal enum class SnapZone { Left, Right, Top, TopLeft, TopRight, BottomLeft, BottomRight }

/**
 * Una pantalla en coordenadas de AWT ([bounds], [work] sin barra de tareas) y su escala.
 * En Windows, AWT (JDK 21) deja el origen de cada pantalla en píxeles físicos y escala solo el
 * tamaño (medido con dos monitores: el 2K vertical al 125 % sale en (-1440,-1941) con 1152×2048).
 * Por eso entre pantallas de escala distinta quedan huecos y solapes en AWT que no existen de verdad.
 */
internal data class SnapScreen(val bounds: DockRect, val work: DockRect, val scale: Double) {
    val physical: DockRect get() = DockRect(bounds.x, bounds.y, (bounds.width * scale).roundToInt(), (bounds.height * scale).roundToInt())
    fun toPhysical(x: Int, y: Int) = java.awt.Point(bounds.x + ((x - bounds.x) * scale).roundToInt(), bounds.y + ((y - bounds.y) * scale).roundToInt())
    fun toAwt(px: Int, py: Int) = java.awt.Point(bounds.x + ((px - bounds.x) / scale).roundToInt(), bounds.y + ((py - bounds.y) / scale).roundToInt())
}

/**
 * Dónde se queda un conjunto soltado con su esquina en (px, py) físicos y [width]×[height] lógicos:
 * la pantalla que más tapa, como hace Windows con una ventana a caballo (le da esa escala).
 */
internal fun dropScreen(screens: List<SnapScreen>, px: Int, py: Int, width: Int, height: Int): SnapScreen {
    fun overlap(s: SnapScreen): Long {
        val p = s.physical
        val w = width.scaled(s.scale); val h = height.scaled(s.scale)
        val ix = (minOf(px + w, p.x + p.width) - maxOf(px, p.x)).coerceAtLeast(0)
        val iy = (minOf(py + h, p.y + p.height) - maxOf(py, p.y)).coerceAtLeast(0)
        return ix.toLong() * iy
    }
    val best = screens.maxBy(::overlap)
    return if (overlap(best) > 0) best else screenAtPhysical(screens, px, py)
}

/** La pantalla que contiene el punto físico o, si cae fuera de todas, la más cercana. */
internal fun screenAtPhysical(screens: List<SnapScreen>, px: Int, py: Int): SnapScreen {
    val area = nearestArea(screens.map { it.physical }, px, py)
    return screens.first { it.physical == area }
}

internal data class SnapTarget(val zone: SnapZone, val screen: SnapScreen, val rect: DockRect)

/** El hueco que ocupa cada zona dentro de la zona útil de la pantalla. */
internal fun snapRect(zone: SnapZone, work: DockRect): DockRect {
    val halfW = work.width / 2; val halfH = work.height / 2
    val rightX = work.x + halfW; val bottomY = work.y + halfH
    return when (zone) {
        SnapZone.Top -> work
        SnapZone.Left -> DockRect(work.x, work.y, halfW, work.height)
        SnapZone.Right -> DockRect(rightX, work.y, work.width - halfW, work.height)
        SnapZone.TopLeft -> DockRect(work.x, work.y, halfW, halfH)
        SnapZone.TopRight -> DockRect(rightX, work.y, work.width - halfW, halfH)
        SnapZone.BottomLeft -> DockRect(work.x, bottomY, halfW, work.height - halfH)
        SnapZone.BottomRight -> DockRect(rightX, bottomY, work.width - halfW, work.height - halfH)
    }
}

/**
 * Windows colocó él mismo una ventana (Win+flechas): si su rectángulo es una zona de Snap de alguna
 * pantalla, devolverla para ajustar el conjunto entero a ella.
 */
internal fun matchSnap(screens: List<SnapScreen>, actual: DockRect, tolerance: Int = 12): SnapTarget? {
    fun close(a: DockRect, b: DockRect) = kotlin.math.abs(a.x - b.x) <= tolerance && kotlin.math.abs(a.y - b.y) <= tolerance &&
        kotlin.math.abs(a.width - b.width) <= tolerance && kotlin.math.abs(a.height - b.height) <= tolerance
    for (s in screens) for (zone in SnapZone.entries) {
        val r = snapRect(zone, s.work)
        if (close(actual, r)) return SnapTarget(zone, s, r)
    }
    return null
}
