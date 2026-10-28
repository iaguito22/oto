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
import io.github.iaguito22.oto.LoopMode
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.dj.Plays
import io.github.iaguito22.oto.dj.Smart
import io.github.iaguito22.oto.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
class PlayerConnection(private val context: Context, private val prefs: Prefs, private val plays: Plays, private val scope: CoroutineScope) {
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

    private val _loop = MutableStateFlow(LoopMode.Off)
    /** Las listas en bucle (ver [Prefs.loop]). */
    val loop: StateFlow<LoopMode> = _loop.asStateFlow()

    // Lo que el aleatorio tiene en cuenta, siempre a mano: lo que te gusta y cuándo sonó de verdad cada una.
    @Volatile private var liked = emptySet<String>()
    @Volatile private var heardAt = emptyMap<String, Long>()
    private var shuffling: Job? = null

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
        // El historial cambia con cada canción: es cuando el diario de escuchas tiene algo nuevo.
        scope.launch {
            prefs.history.collect {
                heardAt = withContext(Dispatchers.IO) { plays.all() }.filter { it.heard }.associate { it.id to it.at }
            }
        }
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
        // Una lista que aún se estaba barajando ya no tiene que ponerse.
        shuffling?.cancel()
        _source.value = Source("Radio", song.title)
        original = null
        it.repeatMode = repeatModeOf(_loop.value)
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

    /**
     * Con el aleatorio puesto, la tocada ([start]) suena la primera y el resto barajado; «Reproducir», todo barajado.
     * Barajar una lista de miles lleva unos milisegundos: se hace fuera del hilo de la interfaz.
     */
    private fun playList(items: List<MediaItem>, itemAges: List<Float?>, start: Int?) {
        original = items
        ages = itemAges
        shuffling?.cancel()
        if (!_shuffle.value) return withController { setList(it, items, start ?: 0) }
        val shuffleItems = shuffleItems(items.indices.toList())
        shuffling = scope.launch {
            val order = Shuffle.order(shuffleItems, start, liked, heardAt)
            withContext(Dispatchers.Main) { withController { setList(it, order.map(items::get), 0) } }
        }
    }

    private fun setList(c: MediaController, items: List<MediaItem>, start: Int) {
        // Antes que la cola: el servicio mira el bucle al recibirla para decidir si seguirá con parecidas.
        c.repeatMode = repeatModeOf(_loop.value)
        c.setMediaItems(items, start, 0)
        c.prepare()
        c.play()
    }

    /**
     * Pone o quita el aleatorio; si suena una lista, se rehace lo que queda detrás de la actual. Al ponerlo, lo que ya
     * sonó de la lista en esta vuelta se queda para el final, y la siguiente no es del mismo artista que la actual.
     */
    fun setShuffle(on: Boolean) {
        _shuffle.value = on
        scope.launch { prefs.setShuffle(on) }
        val items = original ?: return
        shuffling?.cancel()
        withController { c ->
            val current = c.currentMediaItem ?: return@withController
            val at = items.indexOfFirst { it.mediaId == current.mediaId }
            if (at < 0) return@withController
            if (!on) return@withController c.replaceMediaItems(c.currentMediaItemIndex + 1, c.mediaItemCount, items.drop(at + 1))
            val played = (0 until c.currentMediaItemIndex).mapTo(HashSet()) { c.getMediaItemAt(it).mediaId }
            val others = items.indices.filter { it != at }
            val shuffleItems = shuffleItems(others)
            shuffling = scope.launch {
                val order = Shuffle.order(shuffleItems, null, liked, heardAt, played = played, after = current.mediaMetadata.artist?.toString())
                withContext(Dispatchers.Main) {
                    withController { c2 ->
                        // Si entretanto cambió de canción, lo barajado ya no vale.
                        if (c2.currentMediaItem?.mediaId != current.mediaId) return@withController
                        c2.replaceMediaItems(c2.currentMediaItemIndex + 1, c2.mediaItemCount, order.map { items[others[it]] })
                    }
                }
            }
        }
    }

    /** Pasa al siguiente estado del bucle: apagado -> lista entera -> una canción -> apagado. */
    fun cycleLoop() {
        val next = when (_loop.value) {
            LoopMode.Off -> LoopMode.All
            LoopMode.All -> LoopMode.One
            LoopMode.One -> LoopMode.Off
        }
        setLoop(next)
    }

    /** Pone el bucle en el modo indicado; vale ya para lo que suena y para lo que venga después. */
    fun setLoop(mode: LoopMode) {
        _loop.value = mode
        scope.launch { prefs.setLoop(mode) }
        withController { it.repeatMode = repeatModeOf(mode) }
    }

    private fun repeatModeOf(mode: LoopMode) = when (mode) {
        LoopMode.Off -> Player.REPEAT_MODE_OFF
        LoopMode.All -> Player.REPEAT_MODE_ALL
        LoopMode.One -> Player.REPEAT_MODE_ONE
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
        shuffling?.cancel()
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

    /** Mueve [from] justo detrás de [after] (o de la que suena, si es null), o justo delante de [before]. */
    fun place(from: Int, after: Int? = null, before: Int? = null) = withController {
        val to = when {
            before != null -> if (from < before) before - 1 else before
            else -> (after ?: it.currentMediaItemIndex).let { a -> if (from < a) a else a + 1 }
        }
        if (from != to) it.moveMediaItem(from, to)
    }

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
    /** A la anterior si esta acaba de empezar; si no (o no hay anterior), al principio de esta. Ver [restarts]. */
    fun previous() = withController { if (restarts()) it.seekTo(0) else it.seekToPreviousMediaItem() }
    /** Si «anterior» ahora volvería al principio de esta: pasados [RESTART_MS], o sin anterior. Barata, se lee en cada fotograma. */
    fun restarts(): Boolean = controller?.let { it.currentPosition > RESTART_MS || !it.hasPreviousMediaItem() } ?: true
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

/** Pasado esto, «anterior» vuelve al principio de la que suena en vez de cambiar de canción. */
private const val RESTART_MS = 3_000L
