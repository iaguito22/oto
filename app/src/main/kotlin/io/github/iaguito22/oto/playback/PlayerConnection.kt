package io.github.iaguito22.oto.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.dj.Smart
import io.github.iaguito22.oto.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Lo que la interfaz necesita saber de lo que suena. La posición no va aquí: se lee por fotograma con [position]. */
data class NowPlaying(
    val id: String? = null,
    val title: String = "",
    val artist: String = "",
    /** Los artistas uno a uno (nombre e id de YouTube Music, si lo hay). */
    val artists: List<Pair<String, String?>> = emptyList(),
    val album: String? = null,
    val artwork: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val upNext: Int = 0,
    val index: Int = 0,
    val error: String? = null,
)

/**
 * De dónde sale lo que suena: un álbum, una lista o la radio de una canción. [item] (de YouTube) o [local] (el id de
 * una lista tuya) es lo que se abre al pedirlo.
 */
data class Source(val kind: String, val title: String, val item: YTItem? = null, val local: String? = null)

/** Una canción de la cola; [index] es su posición en la lista del reproductor. */
data class QueueEntry(val index: Int, val id: String, val title: String, val artist: String, val artwork: String?, val auto: Boolean = false)

/**
 * La interfaz habla con [PlaybackService] a través de un MediaController, igual que la notificación o los auriculares.
 * Se conecta mientras la actividad está visible ([connect]/[disconnect]); lo que se pida antes de conectar espera.
 */
class PlayerConnection(private val context: Context, private val prefs: Prefs, private val scope: CoroutineScope) {
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

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _loop = MutableStateFlow(false)
    /** Las listas en bucle (ver [Prefs.loop]). */
    val loop: StateFlow<Boolean> = _loop.asStateFlow()

    // Lo que el aleatorio tiene en cuenta, siempre a mano.
    @Volatile private var liked = emptySet<String>()
    @Volatile private var heard = emptySet<String>()

    /**
     * La lista que suena, en su orden, para barajarla o volver a él. [ages]: lo reciente que entró cada una (ver
     * [Shuffle.Item]). null con la radio, que no se baraja.
     */
    private var original: List<MediaItem>? = null
    private var ages: List<Float?> = emptyList()

    init {
        scope.launch { prefs.shuffle.collect { _shuffle.value = it } }
        scope.launch { prefs.loop.collect { _loop.value = it } }
        scope.launch { prefs.likedIds.collect { liked = it } }
        scope.launch { prefs.history.collect { h -> heard = h.mapTo(HashSet()) { it.id } } }
    }

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
        original = null
        it.repeatMode = Player.REPEAT_MODE_OFF
        it.setMediaItem(song.toMediaItem())
        it.prepare()
        it.play()
    }

    /** Suena la lista entera (un álbum, una playlist), sin radio: desde [start] o, si es null, desde el principio. */
    fun playAll(songs: List<SongItem>, start: Int?, from: YTItem? = null) {
        _source.value = from?.let { f -> Source(when (f) { is AlbumItem -> "Álbum"; is ArtistItem -> "Artista"; else -> "Lista" }, f.title, f) }
        playList(songs.map(SongItem::toMediaItem), List(songs.size) { null }, start)
    }

    /** Una lista tuya, tal y como se ve ([newestFirst]: lo último que entró, arriba). */
    fun playLocal(list: LocalPlaylist, start: Int?, newestFirst: Boolean) {
        if (list.songs.isEmpty()) return
        // Las hechas con lo tuyo no se guardan: no hay a dónde volver desde lo que suena.
        val smart = list.id.startsWith(Smart.PREFIX)
        _source.value = Source(
            when { smart -> "Hecha con lo tuyo"; list.id.startsWith("folder:") -> "Tu carpeta"; list.id == "downloads" -> "Tus descargas"; else -> "Tu lista" },
            list.name, local = list.id.takeUnless { smart },
        )
        val n = list.songs.size
        val age = { i: Int -> if (n == 1) 0f else (if (newestFirst) i else n - 1 - i) / (n - 1f) }
        playList(list.songs.map { h -> h.toSong().toMediaItem() }, List(n) { age(it) }, start)
    }

    /** Con el aleatorio puesto, la tocada ([start]) suena la primera y el resto barajado; «Reproducir», todo barajado. */
    private fun playList(items: List<MediaItem>, itemAges: List<Float?>, start: Int?) = withController {
        original = items
        ages = itemAges
        val order = if (_shuffle.value) Shuffle.order(shuffleItems(items.indices.toList()), start, liked, heard) else null
        // Antes que la cola: el servicio mira el bucle al recibirla para decidir si seguirá con parecidas.
        it.repeatMode = if (_loop.value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        it.setMediaItems(order?.map(items::get) ?: items, if (order != null) 0 else start ?: 0, 0)
        it.prepare()
        it.play()
    }

    /** Pone o quita el aleatorio; si suena una lista, se rehace lo que queda detrás de la actual. */
    fun setShuffle(on: Boolean) {
        _shuffle.value = on
        scope.launch { prefs.setShuffle(on) }
        val items = original ?: return
        withController { c ->
            val cur = c.currentMediaItemIndex
            val at = items.indexOfFirst { it.mediaId == c.currentMediaItem?.mediaId }
            if (at < 0) return@withController
            val rest = if (on) {
                val others = items.indices.filter { it != at }
                Shuffle.order(shuffleItems(others), null, liked, heard).map { items[others[it]] }
            } else items.drop(at + 1)
            c.replaceMediaItems(cur + 1, c.mediaItemCount, rest)
        }
    }

    /** Pone o quita el bucle; si suena una lista, vale ya (si no, desde la próxima). */
    fun setLoop(on: Boolean) {
        _loop.value = on
        scope.launch { prefs.setLoop(on) }
        if (original != null) withController { it.repeatMode = if (on) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF }
    }

    private fun shuffleItems(indices: List<Int>) = indices.map { i ->
        val m = original!![i]
        Shuffle.Item(m.mediaId, m.mediaMetadata.artist?.toString().orEmpty(), ages.getOrNull(i))
    }

    /**
     * El DJ (ver [io.github.iaguito22.oto.dj.Dj]): lo elige el servicio, de una en una. [onFail]: aún no sabe nada de
     * ti (no hay listas, Me gusta ni historial).
     */
    fun playDj(onFail: () -> Unit) = withController { c ->
        val before = _source.value
        _source.value = Source("DJ", "Mezcla para ti")
        original = null
        c.repeatMode = Player.REPEAT_MODE_OFF
        val result = c.sendCustomCommand(SessionCommand(DJ_COMMAND, Bundle.EMPTY), Bundle.EMPTY)
        result.addListener({
            if (runCatching { result.get().resultCode }.getOrNull() != SessionResult.RESULT_SUCCESS) {
                _source.value = before
                onFail()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Detrás de la que suena. */
    fun playNext(song: SongItem) = withController {
        if (it.mediaItemCount == 0) play(song) else it.addMediaItem(it.currentMediaItemIndex + 1, song.toMediaItem())
    }

    /** Mueve una de la cola a otro sitio ([from] y [to]: posiciones en la lista del reproductor, como [QueueEntry.index]). */
    fun move(from: Int, to: Int) = withController { if (from != to) it.moveMediaItem(from, to) }

    /** Quita una de la cola, también de la lista que se rebaraja al tocar el aleatorio (si no, volvería). */
    fun remove(index: Int) = withController {
        if (index == it.currentMediaItemIndex) return@withController
        val id = it.getMediaItemAt(index).mediaId
        original?.indexOfFirst { m -> m.mediaId == id }?.takeIf { at -> at >= 0 }?.let { at ->
            original = original!!.filterIndexed { i, _ -> i != at }
            ages = ages.filterIndexed { i, _ -> i != at }
        }
        it.removeMediaItem(index)
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
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_REPEAT_MODE_CHANGED)) publishQueue(player)
        }
    }

    private fun publish(p: Player) {
        val item = p.currentMediaItem
        // Los del propio MediaItem, no p.mediaMetadata: al pasar de canción, el controlador da un instante el id
        // nuevo con los metadatos de la anterior (y la carátula vieja entraba como si fuera la nueva).
        val meta = item?.mediaMetadata ?: p.mediaMetadata
        _state.value = NowPlaying(
            id = item?.mediaId,
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            artists = meta.extras?.let { e ->
                val names = e.getStringArray(ARTIST_NAMES) ?: return@let null
                val ids = e.getStringArray(ARTIST_IDS)
                names.mapIndexed { i, n -> n to ids?.getOrNull(i)?.ifEmpty { null } }
            } ?: listOf(meta.artist?.toString().orEmpty() to null),
            album = meta.albumTitle?.toString(),
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
        // En bucle, detrás de la última vuelve la primera.
        val wrap = if (p.repeatMode == Player.REPEAT_MODE_ALL) (0 until p.currentMediaItemIndex) else IntRange.EMPTY
        _queue.value = ((p.currentMediaItemIndex + 1 until p.mediaItemCount) + wrap).map { entry(p, it) }
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
            auto = item.isAuto,
        )
    }
}
