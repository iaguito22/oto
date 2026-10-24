package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.iaguito22.oto.MemCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import org.jetbrains.skia.Image
import kotlin.math.max
import kotlin.math.min

/**
 * Tokens de color de Oto Desktop (estética Caelestia + degradado vivo de la carátula como en el móvil).
 */
@Immutable
data class DesktopColors(
    val bgDeep: Color = Color(0xFF0A090F),
    val bgSurface: Color = Color(0xFF14121D),
    val bgCard: Color = Color(0x16FFFFFF),
    val bgCardHover: Color = Color(0x24FFFFFF),
    val text: Color = Color(0xFFF6F3FF),
    val textMuted: Color = Color(0xA6F6F3FF),
    val textFaint: Color = Color(0x5CF6F3FF),
    val accent: Color = Color(0xFFD8B9FF),
    val accentWarm: Color = Color(0xFFFFB69E),
    val accentCool: Color = Color(0xFF9ED8FF),
    val onAccent: Color = Color(0xFF140E21),
    val border: Color = Color(0x1FFFFFFF),
)

val LocalDesktopColors = staticCompositionLocalOf { DesktopColors() }

data class ArtworkPalette(
    val primary: Color = Color(0xFFD8B9FF),
    val secondary: Color = Color(0xFFFFB69E),
    val tertiary: Color = Color(0xFF9ED8FF),
    val deep: Color = Color(0xFF0C0A14),
)

/**
 * Cargador de carátulas en memoria + extractor de colores dominantes para el fondo degradado vivo
 * y el acento dinámico (sin depender de `androidx.palette` de Android).
 */
object ArtworkLoader {
    private val bitmapCache = MemCache<String, ImageBitmap>(96)
    private val paletteCache = MemCache<String, ArtworkPalette>(96)

    suspend fun load(url: String?): Pair<ImageBitmap?, ArtworkPalette> {
        if (url.isNullOrBlank()) return null to ArtworkPalette()
        val hiUrl = upscaleThumb(url)
        bitmapCache.get(hiUrl)?.let { bmp ->
            return bmp to (paletteCache.get(hiUrl) ?: ArtworkPalette())
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = URI.create(hiUrl).toURL().openStream().use { it.readBytes() }
                val skiaImg = Image.makeFromEncoded(bytes)
                val bmp = skiaImg.toComposeImageBitmap()
                val pal = extractPalette(bmp)
                bitmapCache.put(hiUrl, bmp)
                paletteCache.put(hiUrl, pal)
                bmp to pal
            }.getOrDefault(null to ArtworkPalette())
        }
    }

    private fun upscaleThumb(url: String): String =
        url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")

    private fun extractPalette(bmp: ImageBitmap): ArtworkPalette {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return ArtworkPalette()
        val pixels = IntArray(w * h)
        bmp.readPixels(pixels, 0, 0, w, h)

        var r1 = 0L; var g1 = 0L; var b1 = 0L; var c1 = 0
        var r2 = 0L; var g2 = 0L; var b2 = 0L; var c2 = 0
        var r3 = 0L; var g3 = 0L; var b3 = 0L; var c3 = 0

        val step = max(1, (w * h) / 900)
        var i = 0
        while (i < pixels.size) {
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx.toFloat()
            val lum = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
            if (lum in 0.15f..0.88f && sat > 0.18f) {
                when (i % 3) {
                    0 -> { r1 += r; g1 += g; b1 += b; c1++ }
                    1 -> { r2 += r; g2 += g; b2 += b; c2++ }
                    else -> { r3 += r; g3 += g; b3 += b; c3++ }
                }
            }
            i += step
        }

        fun lift(r: Long, g: Long, b: Long, count: Int, fallback: Color): Color {
            if (count == 0) return fallback
            val rf = ((r / count) * 0.72f + 255f * 0.28f).toInt().coerceIn(90, 250)
            val gf = ((g / count) * 0.72f + 245f * 0.28f).toInt().coerceIn(90, 245)
            val bf = ((b / count) * 0.72f + 255f * 0.28f).toInt().coerceIn(110, 255)
            return Color(rf, gf, bf)
        }

        val p1 = lift(r1, g1, b1, c1, Color(0xFFD8B9FF))
        val p2 = lift(r2, g2, b2, c2, Color(0xFFFFB69E))
        val p3 = lift(r3, g3, b3, c3, Color(0xFF9ED8FF))
        val deep = Color(
            red = (p1.red * 0.14f).coerceIn(0.03f, 0.16f),
            green = (p1.green * 0.12f).coerceIn(0.02f, 0.14f),
            blue = (p1.blue * 0.18f).coerceIn(0.04f, 0.20f),
        )
        return ArtworkPalette(primary = p1, secondary = p2, tertiary = p3, deep = deep)
    }
}

@Composable
fun rememberAnimatedPalette(target: ArtworkPalette): ArtworkPalette {
    val p1 = animateColorAsState(target.primary, tween(900)).value
    val p2 = animateColorAsState(target.secondary, tween(900)).value
    val p3 = animateColorAsState(target.tertiary, tween(900)).value
    val deep = animateColorAsState(target.deep, tween(900)).value
    return ArtworkPalette(primary = p1, secondary = p2, tertiary = p3, deep = deep)
}
