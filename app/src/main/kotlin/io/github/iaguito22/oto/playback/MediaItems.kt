package io.github.iaguito22.oto.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.metrolist.innertube.models.SongItem
import kotlin.math.roundToInt

/**
 * La URI de un MediaItem es su videoId: la URL real caduca, así que se resuelve al abrir el stream
 * (ver [PlaybackService]). El videoId también es la clave de la caché de audio.
 */
fun SongItem.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(id)
    .setCustomCacheKey(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artists.joinToString { it.name })
            .setAlbumTitle(album?.name)
            .setArtworkUri(thumbnail.resize(ARTWORK_PX).toUri())
            .setDurationMs(duration?.let { it * 1000L })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build(),
    )
    .build()

/** Un MediaItem que llega de un MediaController pierde su URI por el camino: se reconstruye desde el mediaId. */
fun MediaItem.withPlaybackUri(): MediaItem =
    buildUpon().setUri(mediaId).setCustomCacheKey(mediaId).build()

// Lo que ocupa la carátula a pantalla completa en un móvil de 1080 px de ancho.
const val ARTWORK_PX = 1080

private val GOOGLEUSERCONTENT_SIZE = Regex("^(https://(?:lh3|yt3)\\.googleusercontent\\.com/[^?]*?)=w(\\d+)-h(\\d+)[^?]*(\\?.*)?$")
private val GGPHT_SIZE = Regex("^(https://yt3\\.ggpht\\.com/[^?=]+)=(?:s\\d+|w\\d+-h\\d+)[^?]*(\\?.*)?$")

/** Pide la carátula al tamaño que se va a pintar (de `YouTubeUtils.resize` de Metrolist). */
fun String.resize(width: Int): String {
    GOOGLEUSERCONTENT_SIZE.matchEntire(this)?.groupValues?.let { g ->
        val height = (width.toDouble() * g[3].toInt() / g[2].toInt()).roundToInt().coerceAtLeast(1)
        return "${g[1]}=w$width-h$height-p-l90-rj${g[4]}"
    }
    GGPHT_SIZE.matchEntire(this)?.groupValues?.let { g -> return "${g[1]}=s$width${g[2]}" }
    return this
}
