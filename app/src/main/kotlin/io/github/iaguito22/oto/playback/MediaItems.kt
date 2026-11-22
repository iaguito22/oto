package io.github.iaguito22.oto.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaMetadata
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.Heard
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
            // Cada artista con su id, para abrir su página desde la pantalla principal.
            .setExtras(Bundle().apply {
                putStringArray(ARTIST_NAMES, artists.map { it.name }.toTypedArray())
                putStringArray(ARTIST_IDS, artists.map { it.id.orEmpty() }.toTypedArray())
            })
            .build(),
    )
    .build()

/** Lo que se guarda de lo que suena (historial, me gusta, listas). */
fun MediaItem.toHeard(): Heard {
    val meta = mediaMetadata
    return Heard(
        mediaId, meta.title?.toString().orEmpty(), meta.artist?.toString().orEmpty(), meta.artworkUri?.toString(),
        meta.extras?.getStringArray(ARTIST_IDS)?.firstOrNull()?.ifEmpty { null },
    )
}

/** Un MediaItem que llega de un MediaController pierde su URI por el camino: se reconstruye desde el mediaId. */
fun MediaItem.withPlaybackUri(): MediaItem =
    buildUpon().setUri(mediaId).setCustomCacheKey(mediaId).build()

/** Puesta por la reproducción automática al acabarse una lista (ver PlaybackService), no por ti. */
fun MediaItem.markAuto(): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle.EMPTY).apply { putBoolean(AUTO, true) }
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

val MediaItem.isAuto get() = mediaMetadata.extras?.getBoolean(AUTO) == true

fun MediaItem.markManual(): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle.EMPTY).apply {
        remove(AUTO)
        putBoolean(MANUAL, true)
    }
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

fun MediaItem.clearManual(): MediaItem {
    val extras = Bundle(mediaMetadata.extras).apply { remove(MANUAL) }
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

/** Índices de la Timeline recorridos como los reproducir? el jugador, sin repetir la lista. */
@OptIn(UnstableApi::class)
internal fun Player.queueOrder(shuffled: Boolean = shuffleModeEnabled): List<Int> {
    if (!shuffled) return (0 until mediaItemCount).toList()
    val timeline = currentTimeline
    return buildList {
        var index = timeline.getFirstWindowIndex(true)
        while (index >= 0) {
            add(index)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
    }
}

internal fun pendingManualIndices(player: Player, order: List<Int>, current: Int): Set<Int> =
    if (current < 0) emptySet() else order.drop(current + 1).takeWhile { player.getMediaItemAt(it).isManual }.toSet()

/** Reconstruye los índices tras quitar entradas físicas y añadir las nuevas al final de la Timeline. */
internal fun shuffledQueueAfterEdit(order: List<Int>, removed: BooleanArray, insertion: Int, added: Int): IntArray {
    var size = 0
    val remainingIndices = IntArray(order.size) { if (removed[it]) -1 else size++ }
    val shuffled = ArrayList<Int>(size + added)
    for (index in order) {
        val remaining = remainingIndices[index]
        if (remaining >= 0) shuffled.add(remaining)
    }
    if (added > 0) shuffled.addAll(insertion, (size until size + added).toList())
    return shuffled.toIntArray()
}

val MediaItem.isManual get() = mediaMetadata.extras?.getBoolean(MANUAL) == true

private const val MANUAL = "oto_manual"
const val AUTO = "oto_auto"
const val ARTIST_NAMES = "oto_artist_names"
const val ARTIST_IDS = "oto_artist_ids"
// ARTWORK_PX y String.resize viven en :core (StreamInfo.kt).
