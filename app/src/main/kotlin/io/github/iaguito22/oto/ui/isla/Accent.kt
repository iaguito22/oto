package io.github.iaguito22.oto.ui.isla

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.iaguito22.oto.playback.resize
import io.github.iaguito22.oto.ui.theme.DefaultAccent
import io.github.iaguito22.oto.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Carátula pequeña: basta para el color y para el fondo desenfocado, y comparte caché entre los dos. */
fun smallArtwork(url: String) = url.resize(SMALL_ART)

private const val SMALL_ART = 240
private val cache = LruCache<String, Color>(64)
private val blurs = LruCache<String, ImageBitmap>(6)

/**
 * La carátula pequeña ya desenfocada, para el fondo. Se hace aquí, una vez y fuera del hilo de la interfaz: con
 * BlurEffect la GPU desenfocaba la pantalla entera en el fotograma en que cambiaba la canción (10-15 ms de GPU en uno
 * que ya iba cargado). Tres pasadas de caja son casi una gaussiana; el radio es el mismo que tenía el de la GPU (64 dp
 * sobre la carátula estirada a toda la pantalla), contado en píxeles de la pequeña.
 */
suspend fun blurredArtwork(context: Context, artwork: String): ImageBitmap? {
    blurs.get(artwork)?.let { return it }
    return withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context).data(smallArtwork(artwork)).allowHardware(false).build()
        val bitmap = context.imageLoader.execute(request).image?.toBitmap() ?: return@withContext null
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        blur(px, w, h)
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap().also { blurs.put(artwork, it) }
    }
}

/** Desenfoca [px] (ARGB, [w]×[h]) en su sitio. */
internal fun blur(px: IntArray, w: Int, h: Int) {
    val tmp = IntArray(px.size)
    for (r in BLUR_PASSES) {
        box(px, tmp, w, h, r, horizontal = true)
        box(tmp, px, w, h, r, horizontal = false)
    }
}

/** Media móvil de radio [r] a lo largo de cada fila (o columna), con el borde repetido como TileMode.Clamp. */
private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val step = if (horizontal) 1 else w
    val span = 2 * r + 1
    for (line in 0 until lines) {
        val base = if (horizontal) line * w else line
        fun at(i: Int) = src[base + i.coerceIn(0, len - 1) * step]
        var a = 0; var rr = 0; var g = 0; var b = 0
        for (i in -r..r) { val c = at(i); a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
        for (i in 0 until len) {
            // Redondeando: truncar oscurecía un poco en cada pasada (hasta 6 niveles en total).
            val half = span / 2
            dst[base + i * step] = (((a + half) / span) shl 24) or (((rr + half) / span) shl 16) or (((g + half) / span) shl 8) or ((b + half) / span)
            val out = at(i - r)
            val inn = at(i + r + 1)
            a += (inn ushr 24) - (out ushr 24)
            rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (inn and 0xFF) - (out and 0xFF)
        }
    }
}

/** σ ≈ 9,8 px de la carátula de [SMALL_ART]: lo que eran 64 dp de BlurEffect sobre ella estirada a la pantalla. */
private val BLUR_PASSES = intArrayOf(9, 9, 10)

/** El acento de la carátula que suena, como en el tema de Spotify: cambia despacio de una canción a otra. */
@Composable
fun rememberAccent(artwork: String?): State<Color> {
    val context = LocalContext.current
    var target by remember { mutableStateOf(artwork?.let(cache::get) ?: DefaultAccent) }
    LaunchedEffect(artwork) {
        if (artwork == null) {
            target = DefaultAccent
            return@LaunchedEffect
        }
        target = cache.get(artwork) ?: withContext(Dispatchers.Default) { accentOf(context, artwork) }
            ?.also { cache.put(artwork, it) } ?: return@LaunchedEffect
    }
    return animateColorAsState(target, tween(ACCENT_MS, easing = Motion.Standard), label = "accent")
}

private suspend fun accentOf(context: Context, artwork: String): Color? {
    val request = ImageRequest.Builder(context).data(smallArtwork(artwork)).allowHardware(false).build()
    val bitmap = context.imageLoader.execute(request).image?.toBitmap() ?: return null
    val palette = Palette.from(bitmap).maximumColorCount(16).generate()
    val swatch = palette.vibrantSwatch?.takeIf { it.population > 40 }
        ?: palette.lightVibrantSwatch
        ?: palette.dominantSwatch
        ?: return null
    // Sobre el fondo oscuro el acento tiene que leerse: claro siempre, y con color si la carátula lo tiene
    // (una portada en blanco y negro da un acento casi blanco, no uno inventado).
    val hsl = swatch.hsl.copyOf()
    if (hsl[1] > 0.12f) hsl[1] = hsl[1].coerceIn(0.38f, 0.85f)
    hsl[2] = hsl[2].coerceIn(0.62f, 0.76f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private const val ACCENT_MS = 800
