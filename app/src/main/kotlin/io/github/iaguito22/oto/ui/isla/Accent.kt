package io.github.iaguito22.oto.ui.isla

import android.content.Context
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
