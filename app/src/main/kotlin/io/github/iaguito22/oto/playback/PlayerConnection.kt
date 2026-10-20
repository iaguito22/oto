package io.github.iaguito22.oto.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lo que la interfaz necesita saber de lo que suena. La posición no va aquí: se lee por fotograma con [position]. */
data class NowPlaying(
    val id: String? = null,
    val title: String = "",
    val artist: String = "",
    val artwork: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val upNext: Int = 0,
    val index: Int = 0,
    val error: String? = null,
)

/** De dónde sale lo que suena: un álbum, una lista o la radio de una canción. [item] es lo que se abre al pedirlo. */
data class Source(val kind: String, val title: String, val item: YTItem? = null)

/** Una canción de la cola; [index] es su posición en la lista del reproductor. */
data class QueueEntry(val index: Int, val id: String, val title: String, val artist: String, val artwork: String?)

/**
 * La interfaz habla con [PlaybackService] a través de un MediaController, igual que la notificación o los auriculares.
 * Se conecta mientras la actividad está visible ([connect]/[disconnect]); lo que se pida antes de conectar espera.
 */
class PlayerConnection(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    // Aparte del estado: se rehace solo cuando cambia la cola, no en cada pausa.
    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()
    private val _previous = MutableStateFlow<QueueEntry?>(null)
    val previousEntry: StateFlow<QueueEntry?> = _previous.asStateFlow()

    // Solo vive en memoria, como la cola: si el servicio muere, se pierden los dos.
    private val _source = MutableStateFlow<Source?>(null)
    val source: StateFlow<Source?> = _source.asStateFlow()

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            publish(c)
            publishQueue(c)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
    }

    /** Suena esta canción y, detrás, su radio. */
    fun play(song: SongItem) = withController {
        _source.value = Source("Radio", song.title)
        it.setMediaItem(song.toMediaItem())
        it.prepare()
        it.play()
    }

    /** Suena la lista entera (un álbum, una playlist) desde [start], sin radio. */
    fun playAll(songs: List<SongItem>, start: Int, from: YTItem? = null) = withController {
        _source.value = from?.let { f -> Source(if (f is AlbumItem) "Álbum" else "Lista", f.title, f) }
        it.setMediaItems(songs.map(SongItem::toMediaItem), start, 0)
        it.prepare()
        it.play()
    }

    /** Detrás de la que suena. */
    fun playNext(song: SongItem) = withController {
        if (it.mediaItemCount == 0) play(song) else it.addMediaItem(it.currentMediaItemIndex + 1, song.toMediaItem())
    }

    fun toggle() = withController { if (it.isPlaying) it.pause() else it.play() }
    fun next() = withController { it.seekToNextMediaItem() }
    /** Siempre a la anterior (la gota la enseña antes de soltar); sin anterior, al principio de esta. */
    fun previous() = withController { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index) }

    /** Posición en ms; barata, pensada para leerse en cada fotograma. */
    fun position(): Long = controller?.currentPosition ?: 0L

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action) ?: run { pending += action; connect() }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) publishQueue(player)
        }
    }

    private fun publish(p: Player) {
        val item = p.currentMediaItem
        val meta = p.mediaMetadata
        _state.value = NowPlaying(
            id = item?.mediaId,
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            artwork = meta.artworkUri?.toString(),
            playing = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            durationMs = p.duration.takeIf { it > 0 } ?: meta.durationMs ?: 0L,
            upNext = (p.mediaItemCount - p.currentMediaItemIndex - 1).coerceAtLeast(0),
            index = p.currentMediaItemIndex,
            error = p.playerError?.errorCodeName,
        )
    }

    private fun publishQueue(p: Player) {
        _queue.value = (p.currentMediaItemIndex + 1 until p.mediaItemCount).map { entry(p, it) }
        _previous.value = (p.currentMediaItemIndex - 1).takeIf { it >= 0 }?.let { entry(p, it) }
    }

    private fun entry(p: Player, i: Int): QueueEntry {
        val item = p.getMediaItemAt(i)
        return QueueEntry(
            index = i,
            id = item.mediaId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            artwork = item.mediaMetadata.artworkUri?.toString(),
        )
    }
}
