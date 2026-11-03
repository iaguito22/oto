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
import io.github.iaguito22.oto.PlaylistMode
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La interfaz habla con [PlaybackService] a través de un MediaController, igual que la notificación o los auriculares.
 * Se conecta mientras la actividad está visible ([connect]/[disconnect]); lo que se pida antes de conectar espera.
 */
class PlayerConnection(private val context: Context, private val prefs: Prefs, private val plays: Plays, private val scope: CoroutineScope) : OtoPlayer {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(NowPlaying())
    override val state: StateFlow<NowPlaying> = _state.asStateFlow()

    // Aparte del estado: se rehace solo cuando cambia la cola, no en cada pausa.
    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    override val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()
    // La cola visual solo contiene lo siguiente. Una transferencia necesita también
    // la canción actual y las anteriores, con los índices reales del reproductor.
    private val _sessionQueue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val sessionQueue: StateFlow<List<QueueEntry>> = _sessionQueue.asStateFlow()
    private val _previous = MutableStateFlow<QueueEntry?>(null)
    override val previousEntry: StateFlow<QueueEntry?> = _previous.asStateFlow()

    // Solo vive en memoria, como la cola: si el servicio muere, se pierden los dos.
    private val _source = MutableStateFlow<Source?>(null)
    override val source: StateFlow<Source?> = _source.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    override val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _loopMode = MutableStateFlow(LoopMode.Off)
    /** El bucle de la lista que suena (ver [Prefs.playlistModes]). */
    val loopMode: StateFlow<LoopMode> = _loopMode.asStateFlow()
    override val loop: StateFlow<Boolean> = _loopMode.map { it != LoopMode.Off }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _defaultShuffle = MutableStateFlow(false)
    val defaultShuffle: StateFlow<Boolean> = _defaultShuffle.asStateFlow()
    private val _defaultLoop = MutableStateFlow(false)
    private val _modes = MutableStateFlow<Map<String, PlaylistMode>>(emptyMap())
    val modes: StateFlow<Map<String, PlaylistMode>> = _modes.asStateFlow()

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
    private var currentListId: String? = null

    init {
        scope.launch {
            prefs.shuffle.collect {
                _defaultShuffle.value = it
                if (currentListId == null) _shuffle.value = it
            }
        }
        scope.launch {
            prefs.loop.collect {
                _defaultLoop.value = it
                if (currentListId == null && original != null) _loopMode.value = if (it) LoopMode.All else LoopMode.Off
            }
        }
        scope.launch { prefs.playlistModes.collect { _modes.value = it } }
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
    override fun play(song: SongItem) = withController {
        // Una lista que aún se estaba barajando ya no tiene que ponerse.
        shuffling?.cancel()
        currentListId = null
        _source.value = Source("Radio", song.title)
        original = null
        _loopMode.value = LoopMode.Off
        it.repeatMode = Player.REPEAT_MODE_OFF
        it.setMediaItem(song.toMediaItem())
        it.prepare()
        it.play()
    }

    /** Suena la lista entera (un álbum, una playlist), sin radio: desde [start] o, si es null, desde el principio. */
    override fun playAll(songs: List<SongItem>, start: Int?, from: YTItem?) {
        _source.value = from?.let { f -> Source(when (f) { is AlbumItem -> "Álbum"; is ArtistItem -> "Artista"; else -> "Lista" }, f.title, f) }
        playList(from?.id, songs.map(SongItem::toMediaItem), List(songs.size) { null }, start)
    }

    /** Empieza al instante y añade más canciones del artista cuando llegan sus álbumes. */
    fun playArtist(songs: List<SongItem>, start: Int?, artist: ArtistItem, albums: List<AlbumItem>) {
        playAll(songs, start, artist)
        scope.launch(Dispatchers.Main) {
            val more = ArtistTracks.more(artist, songs, albums)
            shuffling?.join()
            if ((_source.value?.item as? ArtistItem)?.id != artist.id || currentListId != artist.id) return@launch
            withController { c ->
                if ((_source.value?.item as? ArtistItem)?.id != artist.id || currentListId != artist.id) return@withController
                val present = (0 until c.mediaItemCount).mapTo(HashSet()) { c.getMediaItemAt(it).mediaId }
                val extra = more.filter { present.add(it.id) }.map(SongItem::toMediaItem)
                if (extra.isNotEmpty()) {
                    original = original.orEmpty() + extra
                    ages = ages.orEmpty() + List(extra.size) { null }
                    c.addMediaItems(if (_shuffle.value) extra.shuffled() else extra)
                }
            }
        }
    }

    /** Recibe una sesión sin sonar hasta colocar la posición y confirmar la transferencia. */
    fun prepareTransfer(songs: List<SongItem>, index: Int, positionMs: Long, shuffled: Boolean) = withController { c ->
        shuffling?.cancel(); currentListId = null
        _shuffle.value = shuffled
        val items = songs.map { song -> song.toMediaItem().buildUpon().setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(Bundle().apply {putBoolean("oto_exact_transfer",true)}).build()).build() }
        original = items; ages = List(items.size) { null }
        c.playWhenReady = false
        c.setMediaItems(items, index.coerceIn(items.indices), positionMs)
        c.prepare()
    }

    /** Una lista tuya, tal y como se ve ([newestFirst]: lo último que entró, arriba). */
    override fun playLocal(list: LocalPlaylist, start: Int?, newestFirst: Boolean) {
        if (list.songs.isEmpty()) return
        // Las hechas con lo tuyo no se guardan: no hay a dónde volver desde lo que suena.
        val smart = list.id.startsWith(Smart.PREFIX)
        _source.value = Source(
            when { smart -> "Hecha con lo tuyo"; list.id.startsWith("folder:") -> "Tu carpeta"; list.id == "downloads" -> "Tus descargas"; else -> "Tu lista" },
            list.name, local = list.id.takeUnless { smart },
        )
        val n = list.songs.size
        val age = { i: Int -> if (n == 1) 0f else (if (newestFirst) i else n - 1 - i) / (n - 1f) }
        playList(list.id, list.songs.map { h -> h.toSong().toMediaItem() }, List(n) { age(it) }, start)
    }

    /**
     * Con el aleatorio puesto, la tocada ([start]) suena la primera y el resto barajado; «Reproducir», todo barajado.
     * Barajar una lista de miles lleva unos milisegundos: se hace fuera del hilo de la interfaz.
     */
    private fun playList(listId: String?, items: List<MediaItem>, itemAges: List<Float?>, start: Int?) {
        currentListId = listId
        val mode = listId?.let { _modes.value[it] } ?: PlaylistMode(_defaultShuffle.value, defaultLoopMode())
        _shuffle.value = mode.shuffle
        _loopMode.value = mode.loop
        if (listId != null && listId !in _modes.value) saveMode(listId, mode)
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
        // La app ya entrega la cola ordenada; Auto puede haber activado el aleatorio nativo antes.
        c.shuffleModeEnabled = false
        c.repeatMode = repeatModeOf(_loopMode.value)
        c.setMediaItems(items, start, 0)
        c.prepare()
        c.play()
    }

    /**
     * Pone o quita el aleatorio de [listId] (o de la lista que suena); si es la que suena, se rehace lo que queda
     * detrás de la actual. Al ponerlo, lo que ya sonó de la lista en esta vuelta se queda para el final, y la
     * siguiente no es del mismo artista que la actual.
     */
    override fun setShuffle(on: Boolean) = setShuffle(on, null)

    fun setShuffle(on: Boolean, listId: String?) {
        val id = listId ?: currentListId
        if (id != null) {
            val loop = if (id == currentListId) _loopMode.value else _modes.value[id]?.loop ?: defaultLoopMode()
            saveMode(id, PlaylistMode(on, loop))
        }
        if (listId != null && listId != currentListId) return
        _shuffle.value = on
        val items = original ?: return
        shuffling?.cancel()
        withController { c ->
            c.shuffleModeEnabled = false
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
        val next = when (_loopMode.value) {
            LoopMode.Off -> LoopMode.All
            LoopMode.All -> LoopMode.One
            LoopMode.One -> LoopMode.Off
        }
        setLoop(next)
    }

    /** Pone el bucle de la lista que suena en el modo indicado y lo recuerda para esa lista. */
    override fun setLoop(on: Boolean) = setLoop(if (on) LoopMode.All else LoopMode.Off)

    fun setLoop(mode: LoopMode) {
        _loopMode.value = mode
        currentListId?.let { id ->
            val shuffle = _modes.value[id]?.shuffle ?: _shuffle.value
            saveMode(id, PlaylistMode(shuffle, mode))
        }
        if (original != null) withController { it.repeatMode = repeatModeOf(mode) }
    }

    private fun defaultLoopMode() = if (_defaultLoop.value) LoopMode.All else LoopMode.Off

    private fun saveMode(id: String, mode: PlaylistMode) {
        _modes.value = _modes.value + (id to mode)
        scope.launch { prefs.setPlaylistMode(id, mode) }
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
    override fun playDj(onFail: () -> Unit) = withController { c ->
        shuffling?.cancel()
        val before = _source.value
        val beforeId = currentListId
        currentListId = null
        _source.value = Source("DJ", "Mezcla para ti")
        original = null
        _loopMode.value = LoopMode.Off
        c.repeatMode = Player.REPEAT_MODE_OFF
        val result = c.sendCustomCommand(SessionCommand(DJ_COMMAND, Bundle.EMPTY), Bundle.EMPTY)
        result.addListener({
            if (runCatching { result.get().resultCode }.getOrNull() != SessionResult.RESULT_SUCCESS) {
                currentListId = beforeId
                _source.value = before
                onFail()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Detrás de la que suena. */
    override fun playNext(song: SongItem) = withController {
        if (it.mediaItemCount == 0) play(song) else it.addMediaItem(it.currentMediaItemIndex + 1, song.toMediaItem())
    }

    /** Mueve una de la cola a otro sitio ([from] y [to]: posiciones en la lista del reproductor, como [QueueEntry.index]). */
    override fun move(from: Int, to: Int) = withController { if (from != to) it.moveMediaItem(from, to) }

    /** Mueve [from] justo detrás de [after] (o de la que suena, si es null), o justo delante de [before]. */
    override fun place(from: Int, after: Int?, before: Int?) = withController {
        val to = when {
            before != null -> if (from < before) before - 1 else before
            else -> (after ?: it.currentMediaItemIndex).let { a -> if (from < a) a else a + 1 }
        }
        if (from != to) it.moveMediaItem(from, to)
    }

    /** Quita una de la cola, también de la lista que se rebaraja al tocar el aleatorio (si no, volvería). */
    override fun remove(index: Int) = withController {
        if (index == it.currentMediaItemIndex) return@withController
        val id = it.getMediaItemAt(index).mediaId
        original?.indexOfFirst { m -> m.mediaId == id }?.takeIf { at -> at >= 0 }?.let { at ->
            original = original!!.filterIndexed { i, _ -> i != at }
            ages = ages.filterIndexed { i, _ -> i != at }
        }
        it.removeMediaItem(index)
    }

    override fun toggle() = withController { if (it.isPlaying) it.pause() else it.play() }
    override fun next() = withController { it.seekToNextMediaItem() }
    /** A la anterior si esta acaba de empezar; si no (o no hay anterior), al principio de esta. Ver [restarts]. */
    override fun previous() = withController { if (restarts()) it.seekTo(0) else it.seekToPreviousMediaItem() }
    /** Si «anterior» ahora volvería al principio de esta: pasados [RESTART_MS], o sin anterior. Barata, se lee en cada fotograma. */
    override fun restarts(): Boolean = controller?.let { it.currentPosition > RESTART_MS || !it.hasPreviousMediaItem() } ?: true
    override fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    override fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index) }

    /** Posición en ms; barata, pensada para leerse en cada fotograma. */
    override fun position(): Long = controller?.currentPosition ?: 0L

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
        val all = (0 until p.mediaItemCount).map { entry(p, it) }
        _sessionQueue.value = all
        _queue.value = ((p.currentMediaItemIndex + 1 until p.mediaItemCount) + wrap).mapNotNull { all.getOrNull(it) }
        _previous.value = all.getOrNull(p.currentMediaItemIndex - 1)
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
