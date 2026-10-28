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
            val finalSat = curSat.coerceIn(targetSatFloor, 0.92f)
            return hslToColor(hue, finalSat, targetLightness)
        }

        val primaryBin = sortedBins.firstOrNull()
        val secondaryBin = sortedBins.firstOrNull { bin ->
            primaryBin == null || abs(bin - primaryBin).let { min(it, numBins - it) } >= 2
        }
        val tertiaryBin = sortedBins.firstOrNull { bin ->
            (primaryBin == null || abs(bin - primaryBin).let { min(it, numBins - it) } >= 2) &&
            (secondaryBin == null || abs(bin - secondaryBin).let { min(it, numBins - it) } >= 2)
        }

        val p1 = if (primaryBin != null) {
            binToColor(primaryBin, targetLightness = 0.74f, targetSatFloor = 0.55f)
        } else {
            Color(0xFFD8B9FF)
        }

        val p2 = if (secondaryBin != null) {
            binToColor(secondaryBin, targetLightness = 0.68f, targetSatFloor = 0.50f)
        } else if (primaryBin != null) {
            // Variante análoga cálida
            val h = (primaryBin * 30f + 35f) % 360f
            hslToColor(h, 0.65f, 0.70f)
        } else {
            Color(0xFFFFB69E)
        }

        val p3 = if (tertiaryBin != null) {
            binToColor(tertiaryBin, targetLightness = 0.64f, targetSatFloor = 0.45f)
        } else if (primaryBin != null) {
            val h = (primaryBin * 30f + 180f) % 360f
            hslToColor(h, 0.50f, 0.64f)
        } else {
            Color(0xFF9ED8FF)
        }

        val deep = if (primaryBin != null) {
            val count = binCount[primaryBin]
            val avgR = (binR[primaryBin] / count).toInt()
            val avgG = (binG[primaryBin] / count).toInt()
            val avgB = (binB[primaryBin] / count).toInt()
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
            hslToColor(hue, 0.38f, 0.06f)
        } else {
            Color(0xFF0C0A14)
        }

        return ArtworkPalette(primary = p1, secondary = p2, tertiary = p3, deep = deep)
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
