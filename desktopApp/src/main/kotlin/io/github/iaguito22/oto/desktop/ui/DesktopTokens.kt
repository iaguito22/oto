package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Rubik variable, la misma del móvil y del shell de Caelestia. */
val OtoFontFamily = FontFamily(
    Font(resource = "fonts/rubik.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/rubik.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/rubik.ttf", weight = FontWeight.SemiBold),
    Font(resource = "fonts/rubik.ttf", weight = FontWeight.Bold),
    Font(resource = "fonts/rubik.ttf", weight = FontWeight.ExtraBold),
)

/** La escala exacta de `OtoType` del móvil (v1.2.2). */
object OtoType {
    val body = TextStyle(fontFamily = OtoFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = OtoFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.07.em)
    val rowTitle = TextStyle(fontFamily = OtoFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp)
    val rowSub = TextStyle(fontFamily = OtoFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp)
    val section = TextStyle(fontFamily = OtoFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp, letterSpacing = (-0.3).sp)
    val title = TextStyle(fontFamily = OtoFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.4).sp)
    val display = TextStyle(fontFamily = OtoFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp)
    val lyric = TextStyle(fontFamily = OtoFontFamily, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 33.sp, letterSpacing = (-0.4).sp)
}

val OtoTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = OtoFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = OtoFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = OtoFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = OtoFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = OtoFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = OtoFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = OtoFontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = OtoFontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = OtoFontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = OtoFontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = OtoFontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = OtoFontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = OtoFontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = OtoFontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = OtoFontFamily),
    )
}

/** Radios y espacios de la escala única del móvil; los anidados son concéntricos. */
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
    const val EFFECTS_FAST = 150
    const val EFFECTS = 200
    const val SPATIAL_FAST = 350
    const val SPATIAL = 500
    const val SPATIAL_SLOW = 650

    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> spatial() = spring<T>(dampingRatio = 0.78f, stiffness = 380f)
    fun <T> spatialFast() = spring<T>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)
    fun <T> effects() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** Familia común de muelles con rebote: hover, pulsaciones, selectores y entradas. */
    fun <T> hover() = spring<T>(dampingRatio = 0.6f, stiffness = 520f)
    fun <T> press() = spring<T>(dampingRatio = 0.9f, stiffness = 1400f)
    fun <T> release() = spring<T>(dampingRatio = 0.42f, stiffness = 600f)
    fun <T> pop() = spring<T>(dampingRatio = 0.38f, stiffness = 520f)
    fun <T> slide() = spring<T>(dampingRatio = 0.74f, stiffness = 260f)
    fun <T> toggle() = spring<T>(dampingRatio = 0.6f, stiffness = 600f)
}
