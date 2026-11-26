package io.github.iaguito22.oto.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.github.iaguito22.oto.playback.ArtworkRetry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Como `AsyncImage`, pero si la imagen no llega (mala conexión) la vuelve a pedir sola, cada vez más espaciado y en
 * cuanto vuelve la red ([ArtworkRetry]). Antes se quedaba vacía hasta que algo rehacía la pantalla.
 */
@Composable
fun RetryingImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: ColorFilter? = null,
) {
    var attempt by remember(model) { mutableIntStateOf(0) }
    var failed by remember(model) { mutableStateOf(false) }
    if (failed) LaunchedEffect(model, attempt) {
        val seen = ArtworkRetry.network.value
        withTimeoutOrNull(ArtworkRetry.delayMs(attempt)) { ArtworkRetry.network.first { it != seen } }
        failed = false
        attempt++
    }
    key(model, attempt) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            colorFilter = colorFilter,
            onError = { if (model != null && attempt < ArtworkRetry.MAX_ATTEMPTS - 1) failed = true },
        )
    }
}
