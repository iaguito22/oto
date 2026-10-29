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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** El acento de reserva del móvil mientras no hay carátula (o si no tiene color). */
val DefaultAccent = Color(0xFF8FD3DB)

/** El rojo suave del móvil para borrar y para los avisos de error. */
val Danger = Color(0xFFFF8A80)

/**
 * Tokens de color de Oto Desktop, los mismos roles que `OtoColors` en el móvil: fondo casi negro, blancos con alfa y
 * **un solo acento** que sale de la carátula que suena.
 */
@Immutable
data class DesktopColors(
    val bgDeep: Color = Color(0xFF0B0B0B),
    val veil: Color = Color(0x800B0B0B),
    val bgSurface: Color = Color(0xFF121212),
    val bgCard: Color = Color(0x0DFFFFFF),
    val bgCardHover: Color = Color(0x1AFFFFFF),
    val text: Color = Color.White,
    val textMuted: Color = Color(0x9EFFFFFF),
    val textFaint: Color = Color(0x5CFFFFFF),
    val accent: Color = DefaultAccent,
    val accentWarm: Color = DefaultAccent,
    val accentCool: Color = DefaultAccent,
    val onAccent: Color = Color(0xFF101010),
    val glass: Color = Color(0x8F181818),
    val field: Color = Color(0x14FFFFFF),
    val border: Color = Color(0x17FFFFFF),
)

val LocalDesktopColors = staticCompositionLocalOf { DesktopColors() }

/**
 * La carátula da un único acento ([primary]); [secondary] y [tertiary] se quedan igual para que ninguna vista
 * reintroduzca un segundo color fijo. [deep] es el fondo neutro, no un color sacado de la portada.
 */
data class ArtworkPalette(
    val primary: Color = DefaultAccent,
    val secondary: Color = DefaultAccent,
    val tertiary: Color = DefaultAccent,
    val deep: Color = Color(0xFF0B0B0B),
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

        // 12 cubetas de tono (cada una cubre 30 grados del círculo cromático)
        val numBins = 12
        val binWeight = FloatArray(numBins)
        val binR = LongArray(numBins)
        val binG = LongArray(numBins)
        val binB = LongArray(numBins)
        val binCount = IntArray(numBins)

        var totalR = 0L; var totalG = 0L; var totalB = 0L; var totalCount = 0
        val step = max(1, (w * h) / 1600)
        var i = 0

        while (i < pixels.size) {
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val rf = r / 255f
            val gf = g / 255f
            val bf = b / 255f
            val mx = max(rf, max(gf, bf))
            val mn = min(rf, min(gf, bf))
            val delta = mx - mn
            val lum = (mx + mn) / 2f
            val sat = if (delta < 0.001f) 0f else delta / (1f - abs(2f * lum - 1f))

            totalR += r; totalG += g; totalB += b; totalCount++

            // Buscamos colores vivos con presencia cromática (descartando blancos quemados o negros puros)
            if (lum in 0.12f..0.88f && sat > 0.18f) {
                val hue = when {
                    delta < 0.001f -> 0f
                    mx == rf -> 60f * (((gf - bf) / delta) % 6f)
                    mx == gf -> 60f * (((bf - rf) / delta) + 2f)
                    else -> 60f * (((rf - gf) / delta) + 4f)
                }.let { if (it < 0f) it + 360f else it }

                val bin = ((hue / 30f).toInt()).coerceIn(0, numBins - 1)
                // Ponderar por saturación y equilibrio de luminosidad
                val weight = sat * (1f - abs(lum - 0.5f) * 0.9f)
                binWeight[bin] += weight
                binR[bin] += r
                binG[bin] += g
                binB[bin] += b
                binCount[bin]++
            }
            i += step
        }

        // Ordenamos las cubetas por peso acumulado
        val sortedBins = (0 until numBins)
            .filter { binCount[it] > 3 }
            .sortedByDescending { binWeight[it] }

        fun binToColor(binIdx: Int, targetLightness: Float, targetSatFloor: Float): Color {
            val count = binCount[binIdx]
            val avgR = (binR[binIdx] / count).toInt()
            val avgG = (binG[binIdx] / count).toInt()
            val avgB = (binB[binIdx] / count).toInt()
            val rf = avgR / 255f
            val gf = avgG / 255f
            val bf = avgB / 255f
            val mx = max(rf, max(gf, bf))
            val mn = min(rf, min(gf, bf))
            val delta = mx - mn
            val hue = when {
                delta < 0.001f -> 0f
                mx == rf -> 60f * (((gf - bf) / delta) % 6f)
                mx == gf -> 60f * (((bf - rf) / delta) + 2f)
                else -> 60f * (((rf - gf) / delta) + 4f)
            }.let { if (it < 0f) it + 360f else it }
            val curLum = (mx + mn) / 2f
            val curSat = if (delta < 0.001f) 0f else delta / (1f - abs(2f * curLum - 1f))
            val finalSat = curSat.coerceIn(targetSatFloor, 0.85f)
            return hslToColor(hue, finalSat, targetLightness)
        }

        val primaryBin = sortedBins.firstOrNull()
        // Como en el móvil: el acento tiene que leerse sobre el fondo oscuro, claro y con color si la portada lo tiene.
        val accent = if (primaryBin != null) {
            binToColor(primaryBin, targetLightness = 0.70f, targetSatFloor = 0.38f)
        } else {
            DefaultAccent
        }
        return ArtworkPalette(primary = accent, secondary = accent, tertiary = accent, deep = Color(0xFF0B0B0B))
    }

    private fun hslToColor(h: Float, s: Float, l: Float): Color {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(
            red = (r + m).coerceIn(0f, 1f),
            green = (g + m).coerceIn(0f, 1f),
            blue = (b + m).coerceIn(0f, 1f),
        )
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
