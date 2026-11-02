package io.github.iaguito22.oto.desktop.playback

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.QualityMode
import io.github.iaguito22.oto.dj.Dj
import io.github.iaguito22.oto.dj.Memory
import io.github.iaguito22.oto.dj.Play
import io.github.iaguito22.oto.dj.Plays
import io.github.iaguito22.oto.dj.Profile
import io.github.iaguito22.oto.dj.toTrack
import io.github.iaguito22.oto.dj.YouTubeCatalog
import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.lyrics.LyricsQuery
import io.github.iaguito22.oto.lyrics.LyricsStore
import io.github.iaguito22.oto.playback.InnerTubeXPlayer
import io.github.iaguito22.oto.playback.NowPlaying
import io.github.iaguito22.oto.playback.OtoPlayer
import io.github.iaguito22.oto.playback.Quality
import io.github.iaguito22.oto.playback.QueueEntry
import io.github.iaguito22.oto.playback.Radio
import io.github.iaguito22.oto.playback.Source
import io.github.iaguito22.oto.playback.StreamInfo
import io.github.iaguito22.oto.playback.Tier
import io.github.iaguito22.oto.toHeard
import io.github.iaguito22.oto.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.math.min
import kotlin.math.pow

/**
 * Implementación de [OtoPlayer] para Linux y Windows.
 * Comparte al 100% la lógica de negocio de `:core`:
 * - Resolución de streams con `InnerTubeXPlayer.resolve(videoId, tier)` y `StreamInfo.put(id, quality)`.
 * - Cola inteligente con `Radio`, `Shuffle`, `Dj` (`YouTubeCatalog` + `Plays`).
 * - Letras sincronizadas palabra a palabra / línea a línea con `LyricsStore`.
 * - Historial y progreso guardados en `Prefs` (`DataStore`) y `Plays` (`plays.jsonl`).
 */
class DesktopPlayerConnection(
    private val prefs: Prefs,
    private val plays: Plays,
    private val djCatalog: YouTubeCatalog,
    private val lyricsStore: LyricsStore,
    private val downloads: DesktopDownloads,
    private val scope: CoroutineScope,
) : OtoPlayer {

    private val _state = MutableStateFlow(NowPlaying())
    override val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val _source = MutableStateFlow<Source?>(null)
    override val source: StateFlow<Source?> = _source.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    override val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    private val _previousEntry = MutableStateFlow<QueueEntry?>(null)
    override val previousEntry: StateFlow<QueueEntry?> = _previousEntry.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    override val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _loop = MutableStateFlow(false)
    override val loop: StateFlow<Boolean> = _loop.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _currentQuality = MutableStateFlow<Quality?>(null)
    val currentQuality: StateFlow<Quality?> = _currentQuality.asStateFlow()

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()

    private val desktopKv = OtoDirs.keyValue("desktop")
    private val _volume = MutableStateFlow(
        desktopKv.getString("volume")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f
    )
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private var currentLoudnessGain: Float = 1.0f
    private var songQueue = mutableListOf<SongItem>()
    private var originalQueue: List<SongItem>? = null
    private var currentIndex = -1
    private var radio: Radio? = null
    private var dj: Dj? = null
    @Volatile private var resolvingTrack = false
    private var resolveJob: Job? = null
    private var lyricsJob: Job? = null

    private val engine = DesktopAudioEngine(
        cacheDir = File(OtoDirs.cacheDir, "audio"),
        scope = scope,
        onEnded = { scope.launch { onTrackFinished(completedNaturally = true) } },
        onError = { err ->
            Timber.w(err, "Fallo en pista actual")
            scope.launch { _state.value = _state.value.copy(playing = false, buffering = false, error = "No se pudo reproducir. Pulsa para reintentar.") }
        },
    )

    init {
        engine.setGain(_volume.value)
        scope.launch {
            engine.state.collect { es ->
                _positionMs.value = es.positionMs
                val cur = _state.value
                _state.value = cur.copy(
                    playing = es.playing,
                    buffering = resolvingTrack || es.buffering,
                    durationMs = if (es.durationMs > 0L) es.durationMs else cur.durationMs,
                )
            }
        }
        scope.launch {
            prefs.shuffle.collect { _shuffle.value = it }
        }
        scope.launch {
            prefs.loop.collect { _loop.value = it }
        }
    }

    fun beat(): Float = engine.beat(position())

    override fun position(): Long = _positionMs.value

    override fun restarts(): Boolean = _positionMs.value > 3500L

    override fun play(song: SongItem) {
        dj = null
        radio = Radio(song.id)
        originalQueue = null
        _source.value = Source("Radio", song.title)
        songQueue = mutableListOf(song)
        currentIndex = 0
        syncQueueFlow()
        startCurrentTrack()
        scope.launch(Dispatchers.IO) {
            val more = radio?.more()?.getOrDefault(emptyList()).orEmpty().filter { it.id != song.id }
            if (more.isNotEmpty() && currentIndex == 0 && songQueue.firstOrNull()?.id == song.id) {
                songQueue.addAll(more)
                syncQueueFlow()
            }
        }
    }

    fun appendArtistTracks(artistId:String, tracks:List<SongItem>) {
        if ((_source.value?.item as? ArtistItem)?.id != artistId || tracks.isEmpty()) return
        val seen = songQueue.mapTo(HashSet()) { it.id }
        val added = tracks.filter { seen.add(it.id) }
        if (added.isEmpty()) return
        originalQueue = (originalQueue ?: songQueue.toList()) + added
        songQueue.addAll(if (_shuffle.value) added.shuffled() else added)
        syncQueueFlow()
    }

    override fun playNext(song: SongItem) {
        if (songQueue.isEmpty()) {
            play(song)
            return
        }
        val insertIdx = (currentIndex + 1).coerceIn(0, songQueue.size)
        songQueue.add(insertIdx, song)
        syncQueueFlow()
    }

    override fun playAll(songs: List<SongItem>, start: Int?, from: YTItem?) {
        if (songs.isEmpty()) return
        dj = null
        radio = null
        _source.value = when (from) {
            is PlaylistItem -> Source("DE LA PLAYLIST", from.title, from)
            is AlbumItem -> Source("DEL ÁLBUM", from.title, from)
            is ArtistItem -> Source("DEL ARTISTA", from.title, from)
            else -> null
        }
        originalQueue = songs.toList()
        val ordered = if (start == null && _shuffle.value) songs.shuffled() else songs
        songQueue = ordered.toMutableList()
        currentIndex = (start ?: 0).coerceIn(0, songQueue.lastIndex)
        syncQueueFlow()
        startCurrentTrack()
    }

    override fun playLocal(list: LocalPlaylist, start: Int?, newestFirst: Boolean) {
        val base = if (newestFirst) list.songs.reversed() else list.songs
        val items = base.map { it.toSong() }
        if (items.isEmpty()) return
        dj = null
        radio = null
        _source.value = Source("DESDE TU LISTA", list.name, local = list.id)
        originalQueue = items.toList()
        val ordered = if (start == null && _shuffle.value) items.shuffled() else items
        songQueue = ordered.toMutableList()
        currentIndex = (start ?: 0).coerceIn(0, songQueue.lastIndex)
        syncQueueFlow()
        startCurrentTrack()
    }

    override fun playDj(onFail: () -> Unit) {
        scope.launch {
            val newDj = withContext(Dispatchers.Default) {
                val lists = prefs.playlists.first()
                val liked = prefs.liked.first()
                val history = prefs.history.first()
                val profile = Profile(lists, liked, history, plays.all(), System.currentTimeMillis())
                Dj(profile, djCatalog, Memory(File(OtoDirs.filesDir, "dj.json")), scope, log = { Timber.tag("dj").d(it) })
            }
            val first = newDj.start()
            if (first == null) {
                onFail()
                return@launch
            }
            radio = null
            dj = newDj
            originalQueue = null
            _source.value = Source("DJ", "Mezcla para ti")
            songQueue = mutableListOf(first.toSong())
            currentIndex = 0
            syncQueueFlow()
            startCurrentTrack()
        }
    }

    override fun skipTo(index: Int) {
        if (index !in songQueue.indices) return
        recordPlayProgress(completed = false)
        currentIndex = index
        syncQueueFlow()
        startCurrentTrack()
    }

    override fun move(from: Int, to: Int) {
        if (from !in songQueue.indices || to !in songQueue.indices || from == to) return
        val item = songQueue.removeAt(from)
        songQueue.add(to, item)
        currentIndex = when (currentIndex) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) currentIndex - 1 else currentIndex + 1
            else -> currentIndex
        }
        syncQueueFlow()
    }

    override fun place(from: Int, after: Int?, before: Int?) {
        val target = after ?: before ?: return
        move(from, target)
    }

    override fun remove(index: Int) {
        if (index !in songQueue.indices || index == currentIndex) return
        songQueue.removeAt(index)
        if (index < currentIndex) currentIndex--
        syncQueueFlow()
    }

    fun clearQueue() {
        val cur = currentIndex
        if (cur in songQueue.indices) {
            val curSong = songQueue[cur]
            songQueue.clear()
            songQueue.add(curSong)
            currentIndex = 0
        } else {
            songQueue.clear()
            currentIndex = -1
        }
        syncQueueFlow()
    }

    fun setVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        _volume.value = clamped
        desktopKv.putString("volume", clamped.toString())
        engine.setGain(currentLoudnessGain * clamped)
    }

    fun adjustVolume(delta: Float) {
        setVolume(_volume.value + delta)
    }

    override fun setShuffle(on: Boolean) {
        val changed = _shuffle.value != on
        _shuffle.value = on
        if (changed && currentIndex in songQueue.indices) {
            val played = songQueue.take(currentIndex + 1)
            val pending = songQueue.drop(currentIndex + 1)
            val next = if (on) {
                if (originalQueue == null) originalQueue = songQueue.toList()
                pending.shuffled()
            } else {
                val rank = originalQueue.orEmpty().mapIndexed { i, song -> song.id to i }.toMap()
                pending.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
            }
            songQueue = (played + next).toMutableList()
            syncQueueFlow()
        }
        scope.launch { prefs.setShuffle(on) }
    }

    override fun setLoop(on: Boolean) {
        _loop.value = on
        scope.launch { prefs.setLoop(on) }
    }

    override fun toggle() {
        val st = _state.value
        if (st.id == null) return
        if (st.buffering && resolvingTrack) {
            resolveJob?.cancel()
            resolvingTrack = false
            engine.stop()
            _state.value = st.copy(playing = false, buffering = false)
        } else if (st.playing) {
            engine.pause()
        } else {
            if (engine.state.value.durationMs > 0L) {
                engine.resume()
            } else {
                startCurrentTrack()
            }
        }
    }

    override fun seekTo(ms: Long) {
        engine.seekTo(ms)
    }

    override fun next() {
        recordPlayProgress(completed = false)
        advanceToNext()
    }

    override fun previous() {
        if (restarts()) {
            engine.seekTo(0L)
            return
        }
        if (currentIndex > 0) {
            recordPlayProgress(completed = false)
            currentIndex--
            syncQueueFlow()
            startCurrentTrack()
        } else {
            engine.seekTo(0L)
        }
    }

    private fun onTrackFinished(completedNaturally: Boolean) {
        recordPlayProgress(completed = completedNaturally)
        if (_loop.value && currentIndex == songQueue.lastIndex && songQueue.isNotEmpty()) {
            currentIndex = 0
            syncQueueFlow()
            startCurrentTrack()
            return
        }
        advanceToNext()
    }

    private fun advanceToNext() {
        if (currentIndex + 1 < songQueue.size) {
            currentIndex++
            syncQueueFlow()
            startCurrentTrack()
            maybeReplenishQueue()
        } else {
            scope.launch {
                val cur = songQueue.getOrNull(currentIndex) ?: return@launch
                val more = withContext(Dispatchers.IO) {
                    (radio ?: Radio(cur.id).also { radio = it }).more().getOrDefault(emptyList())
                }.filter { cand -> songQueue.none { it.id == cand.id } }
                if (more.isNotEmpty()) {
                    songQueue.addAll(more)
                    currentIndex++
                    syncQueueFlow()
                    startCurrentTrack()
                }
            }
        }
    }

    private fun maybeReplenishQueue() {
        val remaining = songQueue.size - 1 - currentIndex
        if (remaining > 2) return
        val activeDj = dj
        if (activeDj != null) {
            scope.launch(Dispatchers.IO) {
                val t = runCatching { activeDj.next() }.getOrNull()
                if (t != null && dj === activeDj) {
                    songQueue.add(t.toSong())
                    syncQueueFlow()
                }
            }
        } else if (radio != null) {
            scope.launch(Dispatchers.IO) {
                val more = radio?.more()?.getOrDefault(emptyList()).orEmpty()
                    .filter { cand -> songQueue.none { it.id == cand.id } }
                if (more.isNotEmpty()) {
                    songQueue.addAll(more)
                    syncQueueFlow()
                }
            }
        }
    }

    fun prepareTransfer(songs: List<SongItem>, index: Int, positionMs: Long, shuffled: Boolean) {
        engine.pause()
        _shuffle.value = shuffled
        scope.launch { prefs.setShuffle(shuffled) }
        radio = null; dj = null; originalQueue = songs.toList(); songQueue = songs.toMutableList(); currentIndex = index.coerceIn(songs.indices)
        syncQueueFlow(); startCurrentTrack(false, positionMs)
    }

    private fun startCurrentTrack(playWhenReady: Boolean = true, initialPositionMs: Long = 0L) {
        val song = songQueue.getOrNull(currentIndex) ?: return
        resolveJob?.cancel()
        engine.stop()
        _currentQuality.value = null
        resolvingTrack = true
        resolveJob = scope.launch {
            val dataSaver = prefs.dataSaver.first()
            val qualityMode = prefs.quality.first()
            val tier = if (dataSaver && qualityMode != io.github.iaguito22.oto.QualityMode.Max) Tier.Low else Tier.High
            val durGuess = (song.duration ?: 0).takeIf { it > 0 }?.times(1000L) ?: 0L

            _state.value = NowPlaying(
                id = song.id,
                title = song.title,
                artist = song.artists.joinToString { it.name },
                artists = song.artists.map { it.name to it.id },
                album = song.album?.name,
                artwork = song.thumbnail,
                playing = playWhenReady,
                buffering = true,
                durationMs = durGuess,
                upNext = (songQueue.size - 1 - currentIndex).coerceAtLeast(0),
                index = currentIndex,
            )
            prefs.addHeard(song.toHeard())
            fetchLyrics(song)

            // Si está descargada, suena del archivo: ni red ni caché de reproducción.
            val local = downloads.localFile(song.id)
            if (local != null) {
                downloads.quality(song.id)?.let { quality ->
                    StreamInfo.put(song.id, quality)
                    _currentQuality.value = quality
                }
                currentLoudnessGain = 1f
                engine.setGain(_volume.value)
                resolvingTrack = false
                engine.loadLocalFile(local, durGuess, playWhenReady, initialPositionMs)
                return@launch
            }

            val resolved = withContext(Dispatchers.IO) {
                InnerTubeXPlayer.resolve(song.id, tier)
            }
            resolved.onSuccess { stream ->
                StreamInfo.put(song.id, stream.quality)
                _currentQuality.value = stream.quality
                // Normalización igual que PlaybackService: solo atenúa picos fuertes (loudnessDb > 0)
                val gain = stream.loudnessDb?.let { min(1f, 10f.pow(-it.toFloat() / 20f)) } ?: 1f
                currentLoudnessGain = gain
                engine.setGain(gain * _volume.value)
                resolvingTrack = false
                engine.loadAndPlay(
                    cacheKey = tier.key(song.id),
                    streamUrl = stream.url,
                    headers = stream.headers,
                    contentLength = stream.contentLength,
                    durationMs = durGuess,
                    codec = stream.quality.codec,
                    playWhenReady = playWhenReady,
                    initialPositionMs = initialPositionMs,
                )
            }.onFailure { err ->
                resolvingTrack = false
                Timber.e(err, "No se pudo resolver stream para ${song.id}")
                _state.value = _state.value.copy(playing = false, buffering = false, error = err.message)
            }
        }
    }

    private fun fetchLyrics(song: SongItem) {
        lyricsJob?.cancel()
        _lyrics.value = null
        _lyricsLoading.value = true
        lyricsJob = scope.launch(Dispatchers.IO) {
            val artist = song.artists.joinToString { it.name }
            val durMs = ((song.duration ?: 0).takeIf { it > 0 }?.times(1000L)) ?: _state.value.durationMs
            val query = LyricsQuery(
                id = song.id,
                title = song.title,
                artist = artist,
                album = song.album?.name,
                durationMs = durMs,
            )
            fun rank(value: Lyrics?) = when(value) { is Lyrics.Synced -> if(value.wordByWord) 3 else 2; is Lyrics.Plain -> 1; else -> 0 }
            try {
                var fetched = lyricsStore.get(query)
                if (_state.value.id == song.id) { _lyrics.value = fetched.lyrics; _lyricsLoading.value = false }
                for (wait in listOf(20_000L, 90_000L)) {
                    if (fetched.final) break
                    kotlinx.coroutines.delay(wait)
                    fetched = lyricsStore.get(query)
                    if (_state.value.id != song.id) break
                    if (rank(fetched.lyrics) > rank(_lyrics.value)) _lyrics.value = fetched.lyrics
                }
                if (fetched.final && _state.value.id == song.id) _lyrics.value?.let { lyricsStore.put(song.id, it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "No se pudo buscar la letra")
            } finally {
                if (_state.value.id == song.id) _lyricsLoading.value = false
            }
        }
    }

    private fun recordPlayProgress(completed: Boolean) {
        val cur = _state.value
        val id = cur.id ?: return
        val dur = cur.durationMs
        val pos = if (completed && dur > 0L) dur else _positionMs.value
        if (pos < 2000L) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val song = songQueue.firstOrNull { it.id == id }?.toTrack()
                val kind = if (dj != null) dj?.picks?.lastOrNull { it.track.id == id }?.kind else null
                if (song != null && (kind != null || pos >= 1_000)) {
                    plays.add(Play(id, song.title, song.artist, song.artistId, System.currentTimeMillis(), pos, dur.coerceAtLeast(pos), kind))
                }
                if (kind != null) dj?.heard(id, pos, dur.coerceAtLeast(pos))
            }
        }
    }

    private fun syncQueueFlow() {
        _queue.value = songQueue.mapIndexed { idx, s ->
            QueueEntry(
                index = idx,
                id = s.id,
                title = s.title,
                artist = s.artists.joinToString { it.name },
                artwork = s.thumbnail,
            )
        }
        _previousEntry.value = if (currentIndex > 0) _queue.value.getOrNull(currentIndex - 1) else null
    }
}
