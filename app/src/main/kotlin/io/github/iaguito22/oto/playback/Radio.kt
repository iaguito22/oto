package io.github.iaguito22.oto.playback

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint

/**
 * Radio de una canción: la mezcla automática de YouTube Music (`RDAMVM` + videoId), página a página.
 * Cuando se acaba, [PlaybackService] siembra otra a partir de la última canción de la cola, así que nunca para.
 */
class Radio(val seedId: String) {
    private var endpoint = WatchEndpoint(videoId = seedId, playlistId = "RDAMVM$seedId")
    private var continuation: String? = null
    private var started = false

    val exhausted get() = started && continuation == null

    suspend fun more(): Result<List<SongItem>> =
        YouTube.next(endpoint, continuation).map { page ->
            // Sin página siguiente la primera vez, la mezcla vino vacía: se repite pidiendo solo por el vídeo.
            if (!started && page.items.size <= 1 && endpoint.playlistId != null) {
                endpoint = WatchEndpoint(videoId = seedId)
                return more()
            }
            started = true
            endpoint = page.endpoint
            continuation = page.continuation
            page.items
        }
}
