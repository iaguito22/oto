package io.github.iaguito22.oto.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp

/**
 * Escala única de Caelestia (la misma del shell y del tema de Spotify): radios y espacios salen de aquí,
 * y los radios anidados son concéntricos (dentro = fuera − margen).
 */
object Radius {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 28.dp
}

object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 28.dp
}

object Motion {
    // Efectos (color, opacidad) y movimientos (tamaño, posición), en ms.
    const val EFFECTS_FAST = 150
    const val EFFECTS = 200
    const val SPATIAL_FAST = 350
    const val SPATIAL = 500
    const val SPATIAL_SLOW = 650

    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // Lo que sigue al dedo va con muelles, no con curvas: así se puede interrumpir sin saltos.
    fun <T> spatial() = spring<T>(dampingRatio = 0.78f, stiffness = 380f)
    fun <T> spatialFast() = spring<T>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)
    fun <T> effects() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}
