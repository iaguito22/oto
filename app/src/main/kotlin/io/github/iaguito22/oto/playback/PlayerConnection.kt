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
import org.json.JSONArray
import org.json.JSONObject

/**
 * La interfaz habla con [PlaybackService] a través de un MediaController, igual que la notificación o los auriculares.
 * Se conecta mientras la actividad está visible ([connect]/[disconnect]); lo que se pida antes de conectar espera.
 */
class PlayerConnection(private val context: Context, private val prefs: Prefs, private val plays: Plays, private val scope: CoroutineScope) : OtoPlayer {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    /**
     * Con el ordenador sonando y enlazado, la interfaz lo muestra y lo controla a él (como si sonara aquí): las órdenes
     * se le envían y lo nuevo que se ponga se prepara aquí en pausa y se le pasa entero. Ver [showRemote].
     */
    interface Mirror {
        fun send(action: String, fill: (JSONObject) -> Unit = {})
        fun handOff(state: JSONObject)
    }
    private var mirror: Mirror? = null
    val mirrored: Boolean get() = mirror != null

    // Lo que suena en este teléfono, aunque la interfaz muestre el ordenador: es lo que se le cuenta a él.
    private val _localState = MutableStateFlow(NowPlaying())
    val localState: StateFlow<NowPlaying> = _localState.asStateFlow()
    private val _state = MutableStateFlow(NowPlaying())
    override val state: StateFlow<NowPlaying> = _state.asStateFlow()

    // Aparte del estado: se rehace solo cuando cambia la cola, no en cada pausa.
    private var localQueue = emptyList<QueueEntry>()
    private var localPrevious: QueueEntry? = null
    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    override val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()
    // La cola visual solo contiene lo siguiente. Una transferencia necesita también
    // la canción actual y las anteriores, con los índices reales del reproductor. Siempre la de aquí.
    private val _sessionQueue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val sessionQueue: StateFlow<List<QueueEntry>> = _sessionQueue.asStateFlow()
    private val _previous = MutableStateFlow<QueueEntry?>(null)
    override val previousEntry: StateFlow<QueueEntry?> = _previous.asStateFlow()

    // Solo vive en memoria, como la cola: si el servicio muere, se pierden los dos.
    private val _source = MutableStateFlow<Source?>(null)
    private val _shownSource = MutableStateFlow<Source?>(null)
    override val source: StateFlow<Source?> = _shownSource.asStateFlow()
    val localSource: StateFlow<Source?> = _source.asStateFlow()

    private val _shuffle = MutableStateFlow(true)
    private val _shownShuffle = MutableStateFlow(true)
    override val shuffle: StateFlow<Boolean> = _shownShuffle.asStateFlow()
    val localShuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _loopMode = MutableStateFlow(LoopMode.Off)
    private val _shownLoop = MutableStateFlow(LoopMode.Off)
    /** El bucle de la lista que suena (ver [Prefs.playlistModes]). */
    val loopMode: StateFlow<LoopMode> = _shownLoop.asStateFlow()
    val localLoopMode: StateFlow<LoopMode> = _loopMode.asStateFlow()
    override val loop: StateFlow<Boolean> = _shownLoop.map { it != LoopMode.Off }.stateIn(scope, SharingStarted.Eagerly, false)

    // Posición del ordenador: llega cada medio segundo y entre medias se calcula.
    private var remotePosition = 0L
    private var remoteAt = 0L
    private var remoteIndex = 0
    // Recién enviada una canción, el ordenador tarda un poco: no volver a la anterior mientras.
    private var pendingId: String? = null
    private var pendingUntil = 0L

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
        scope.launch(Dispatchers.Main) { _source.collect { if (mirror == null) _shownSource.value = it } }
        scope.launch(Dispatchers.Main) { _shuffle.collect { if (mirror == null) _shownShuffle.value = it } }
        scope.launch(Dispatchers.Main) { _loopMode.collect { if (mirror == null) _shownLoop.value = it } }
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
        start(it)
    }

    /** Arranca lo recién puesto; con el ordenador al mando, se queda aquí en pausa y suena allí. */
    private fun start(c: MediaController) {
        val m = mirror
        if (m == null) { c.prepare(); c.play(); return }
        c.playWhenReady = false
        val id = c.currentMediaItem?.mediaId ?: return
        m.handOff(transferState(c, playing = true))
        // Se ve ya la nueva, preparándose, sin esperar a que el ordenador conteste.
        pendingId = id
        pendingUntil = android.os.SystemClock.elapsedRealtime() + 12_000
        publish(c)
        _state.value = _localState.value.copy(playing = false, buffering = true)
        _queue.value = localQueue; _previous.value = localPrevious
        _shownSource.value = _source.value; _shownShuffle.value = _shuffle.value; _shownLoop.value = _loopMode.value
        remotePosition = 0; remoteAt = android.os.SystemClock.elapsedRealtime(); remoteIndex = c.currentMediaItemIndex
    }

    /** Lo que hay aquí, en el formato del canal local (ver LinkedPlayback «transfer»). */
    fun transferState(p: Player, playing: Boolean): JSONObject = JSONObject()
        .put("id", p.currentMediaItem?.mediaId).put("index", p.currentMediaItemIndex).put("position", p.currentPosition.coerceAtLeast(0))
        .put("playing", playing).put("shuffle", _shuffle.value).put("loop", _loopMode.value != LoopMode.Off)
        .put("queue", JSONArray((0 until p.mediaItemCount).map { i ->
            val m = p.getMediaItemAt(i).mediaMetadata
            JSONObject().put("id", p.getMediaItemAt(i).mediaId).put("title", m.title?.toString().orEmpty())
                .put("artist", m.artist?.toString().orEmpty()).put("artwork", m.artworkUri?.toString())
        }))

    /** Muestra y controla lo que suena en el ordenador ([state]: su estado periódico). */
    fun showRemote(state: JSONObject, target: Mirror) {
        mirror = target
        val id = state.optString("id").takeUnless { it.isBlank() || it == "null" }
        val now = android.os.SystemClock.elapsedRealtime()
        if (pendingId != null) { if (id != pendingId && now < pendingUntil) return; pendingId = null }
        fun JSONObject.text(key: String) = optString(key).takeUnless { it == "null" }.orEmpty()
        val array = state.optJSONArray("queue")
        val all = (0 until (array?.length() ?: 0)).map { i -> array!!.getJSONObject(i).let { QueueEntry(i, it.text("id"), it.text("title"), it.text("artist"), it.text("artwork").ifBlank { null }) } }
        val index = state.optInt("index").coerceAtLeast(0)
        val loop = state.optBoolean("loop")
        val artist = state.text("artist")
        _state.value = NowPlaying(
            id = id, title = state.text("title"), artist = artist, artists = listOf(artist to null),
            artwork = state.text("artwork").ifBlank { null }, playing = state.optBoolean("playing"), buffering = state.optBoolean("buffering"),
            durationMs = state.optLong("duration").coerceAtLeast(0), upNext = (all.size - index - 1).coerceAtLeast(0), index = index,
        )
        _queue.value = all.drop(index + 1) + (if (loop) all.take(index) else emptyList())
        _previous.value = all.getOrNull(index - 1)
        _shownShuffle.value = state.optBoolean("shuffle")
        _shownLoop.value = if (loop) LoopMode.All else LoopMode.Off
        _shownSource.value = state.text("sourceTitle").takeIf { it.isNotBlank() }?.let { Source(state.text("sourceKind").ifBlank { "Lista" }, it) }
        remotePosition = state.optLong("position").coerceAtLeast(0); remoteAt = now; remoteIndex = index
    }

    /** Vuelve a mostrar lo de este teléfono. */
    fun hideRemote() {
        if (mirror == null) return
        mirror = null; pendingId = null
        _state.value = _localState.value
        _queue.value = localQueue; _previous.value = localPrevious
        _shownSource.value = _source.value; _shownShuffle.value = _shuffle.value; _shownLoop.value = _loopMode.value
    }

    /** Lo que llega del otro dispositivo se hace siempre en este teléfono, aunque se muestre el ordenador. */
    fun <T> here(block: () -> T): T { val m = mirror; mirror = null; try { return block() } finally { mirror = m } }

    private fun remotePosition(): Long {
        val s = _state.value
        val run = if (s.playing) android.os.SystemClock.elapsedRealtime() - remoteAt else 0L
        return (remotePosition + run).coerceIn(0, s.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }

    private fun songJson(song: SongItem) = JSONObject().put("id", song.id).put("title", song.title)
        .put("artist", song.artists.joinToString { it.name }).put("artwork", song.thumbnail)

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
        start(c)
    }

    /**
     * Pone o quita el aleatorio de [listId] (o de la lista que suena); si es la que suena, se rehace lo que queda
     * detrás de la actual. Al ponerlo, lo que ya sonó de la lista en esta vuelta se queda para el final, y la
     * siguiente no es del mismo artista que la actual.
     */
    override fun setShuffle(on: Boolean) = setShuffle(on, null)

    fun setShuffle(on: Boolean, listId: String?) {
        if (listId == null) mirror?.let { m -> _shownShuffle.value = on; return m.send("shuffle") { it.put("value", on) } }
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
        // El ordenador solo tiene bucle de lista: se enciende o se apaga.
        if (mirror != null) return setLoop(if (_shownLoop.value == LoopMode.Off) LoopMode.All else LoopMode.Off)
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
        mirror?.let { m -> _shownLoop.value = mode; return m.send("loop") { it.put("value", mode != LoopMode.Off) } }
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
    override fun playDj(onFail: () -> Unit) { mirror?.let { return it.send("dj") }; playDjHere(onFail) }

    private fun playDjHere(onFail: () -> Unit) = withController { c ->
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
    override fun playNext(song: SongItem) {
        mirror?.let { m -> return m.send("addToQueue") { it.put("next", true).put("song", songJson(song)) } }
        withController { if (it.mediaItemCount == 0) play(song) else it.addMediaItem(it.currentMediaItemIndex + 1, song.toMediaItem()) }
    }

    /** Añade una canción recibida del otro dispositivo al final de la cola. */
    fun enqueue(song: SongItem) {
        mirror?.let { m -> return m.send("addToQueue") { it.put("next", false).put("song", songJson(song)) } }
        withController { if (it.mediaItemCount == 0) play(song) else it.addMediaItem(song.toMediaItem()) }
    }

    /** Mueve una de la cola a otro sitio ([from] y [to]: posiciones en la lista del reproductor, como [QueueEntry.index]). */
    override fun move(from: Int, to: Int) {
        mirror?.let { m -> if (from != to) m.send("move") { it.put("from", from).put("to", to) }; return }
        withController { if (from != to) it.moveMediaItem(from, to) }
    }

    /** Mueve [from] justo detrás de [after] (o de la que suena, si es null), o justo delante de [before]. */
    override fun place(from: Int, after: Int?, before: Int?) {
        fun target(current: Int) = when {
            before != null -> if (from < before) before - 1 else before
            else -> (after ?: current).let { a -> if (from < a) a else a + 1 }
        }
        if (mirror != null) return move(from, target(remoteIndex))
        withController { val to = target(it.currentMediaItemIndex); if (from != to) it.moveMediaItem(from, to) }
    }

    /** Quita una de la cola, también de la lista que se rebaraja al tocar el aleatorio (si no, volvería). */
    override fun remove(index: Int) {
        mirror?.let { m -> if (index != remoteIndex) m.send("remove") { it.put("index", index) }; return }
        removeHere(index)
    }

    private fun removeHere(index: Int) = withController {
        if (index == it.currentMediaItemIndex) return@withController
        val id = it.getMediaItemAt(index).mediaId
        original?.indexOfFirst { m -> m.mediaId == id }?.takeIf { at -> at >= 0 }?.let { at ->
            original = original!!.filterIndexed { i, _ -> i != at }
            ages = ages.filterIndexed { i, _ -> i != at }
        }
        it.removeMediaItem(index)
    }

    override fun toggle() {
        mirror?.let { m ->
            // Se nota al instante; la confirmación llega con su siguiente estado.
            _state.value = _state.value.let { s -> remotePosition = remotePosition(); remoteAt = android.os.SystemClock.elapsedRealtime(); s.copy(playing = !s.playing) }
            return m.send("toggle")
        }
        withController { if (it.isPlaying) it.pause() else it.play() }
    }
    override fun next() { mirror?.let { return it.send("next") }; withController { it.seekToNextMediaItem() } }
    /** A la anterior si esta acaba de empezar; si no (o no hay anterior), al principio de esta. Ver [restarts]. */
    override fun previous() {
        mirror?.let { return it.send("previous") }
        withController { if (restarts()) it.seekTo(0) else it.seekToPreviousMediaItem() }
    }
    /** Si «anterior» ahora volvería al principio de esta: pasados [RESTART_MS], o sin anterior. Barata, se lee en cada fotograma. */
    override fun restarts(): Boolean {
        if (mirror != null) return remotePosition() > RESTART_MS || remoteIndex <= 0
        return controller?.let { it.currentPosition > RESTART_MS || !it.hasPreviousMediaItem() } ?: true
    }
    override fun seekTo(ms: Long) {
        mirror?.let { m -> remotePosition = ms.coerceAtLeast(0); remoteAt = android.os.SystemClock.elapsedRealtime(); return m.send("seek") { it.put("position", ms.coerceAtLeast(0)) } }
        withController { it.seekTo(ms) }
    }
    override fun skipTo(index: Int) {
        mirror?.let { m -> return m.send("skip") { it.put("index", index) } }
        withController { it.seekToDefaultPosition(index) }
    }

    /** Posición en ms; barata, pensada para leerse en cada fotograma. */
    override fun position(): Long = if (mirror != null) remotePosition() else controller?.currentPosition ?: 0L

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
        _localState.value = NowPlaying(
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
        if (mirror == null) _state.value = _localState.value
    }

    private fun publishQueue(p: Player) {
        // En bucle, detrás de la última vuelve la primera.
        val wrap = if (p.repeatMode == Player.REPEAT_MODE_ALL) (0 until p.currentMediaItemIndex) else IntRange.EMPTY
        val all = (0 until p.mediaItemCount).map { entry(p, it) }
        _sessionQueue.value = all
        localQueue = ((p.currentMediaItemIndex + 1 until p.mediaItemCount) + wrap).mapNotNull { all.getOrNull(it) }
        localPrevious = all.getOrNull(p.currentMediaItemIndex - 1)
        if (mirror == null) { _queue.value = localQueue; _previous.value = localPrevious }
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
