package io.github.iaguito22.oto.desktop.playback

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.LoopMode
import io.github.iaguito22.oto.PlaylistMode
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.DEFAULT_SMART_MIX
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.QualityMode
import io.github.iaguito22.oto.dj.AdhdPlanner
import io.github.iaguito22.oto.dj.AdhdSegment
import io.github.iaguito22.oto.dj.Dj
import io.github.iaguito22.oto.dj.DjMode
import io.github.iaguito22.oto.dj.DjMood
import io.github.iaguito22.oto.dj.DjReaction
import io.github.iaguito22.oto.dj.Memory
import io.github.iaguito22.oto.dj.Play
import io.github.iaguito22.oto.dj.Plays
import io.github.iaguito22.oto.dj.Profile
import io.github.iaguito22.oto.dj.toTrack
import io.github.iaguito22.oto.dj.YouTubeCatalog
import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.lyrics.LyricsQuery
import io.github.iaguito22.oto.lyrics.LyricsStore
import io.github.iaguito22.oto.importer.songKey
import io.github.iaguito22.oto.playback.Autoplay
import io.github.iaguito22.oto.playback.InnerTubeXPlayer
import io.github.iaguito22.oto.playback.Shuffle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicLong
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

    private val _shuffle = MutableStateFlow(true)
    override val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _loopMode = MutableStateFlow(LoopMode.Off)
    /** El bucle de lo que suena: apagado, la lista entera o una canción (como en el móvil). */
    val loopMode: StateFlow<LoopMode> = _loopMode.asStateFlow()
    private val _loop = MutableStateFlow(false)
    override val loop: StateFlow<Boolean> = _loop.asStateFlow()

    // Como en el móvil: los valores de Ajustes valen para las listas nuevas y cada lista recuerda cómo la dejaste.
    private val _defaultShuffle = MutableStateFlow(true)
    val defaultShuffle: StateFlow<Boolean> = _defaultShuffle.asStateFlow()
    private val _defaultLoop = MutableStateFlow(false)
    private val _modes = MutableStateFlow<Map<String, PlaylistMode>>(emptyMap())
    val modes: StateFlow<Map<String, PlaylistMode>> = _modes.asStateFlow()
    /** La lista que suena (id de lista tuya, álbum, artista…), o null con la radio y el DJ. */
    private var currentListId: String? = null
    /** Lo reciente que entró cada canción de [originalQueue] (ver [Shuffle.Item]). */
    private var ages: List<Float?> = emptyList()
    @Volatile private var liked = emptySet<String>()
    @Volatile private var heardAt = emptyMap<String, Long>()
    /** Lo que sigue a una lista acabada sin bucle (ver [Autoplay]); lo que añade va en [autoIds]. */
    private var auto: Autoplay? = null
    private val autoIds = HashSet<String>()
    private var autoJob: Job? = null

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
    private var djReplenishJob: Job? = null
    @Volatile private var resolvingTrack = false
    private var resolveJob: Job? = null
    private var lyricsJob: Job? = null

    private val _djActive = MutableStateFlow(false)
    val djActive: StateFlow<Boolean> = _djActive.asStateFlow()

    private val _djMode = MutableStateFlow(DjMode.Normal)
    val djMode: StateFlow<DjMode> = _djMode.asStateFlow()

    private val _djMood = MutableStateFlow(DjMood.Auto)
    val djMood: StateFlow<DjMood> = _djMood.asStateFlow()

    private val _adhdSegment = MutableStateFlow<AdhdSegment?>(null)
    val adhdSegment: StateFlow<AdhdSegment?> = _adhdSegment.asStateFlow()

    @Volatile private var smartMixEnabled = DEFAULT_SMART_MIX
    @Volatile private var ramSaverEnabled = false
    @Volatile private var currentTrackStartOffsetMs = 0L
    @Volatile private var finishedAdhdTrackKey: String? = null

    private val activeDeck = MutableStateFlow<DesktopAudioEngine?>(null)
    private var engine: DesktopAudioEngine
        get() = requireNotNull(activeDeck.value)
        set(value) { activeDeck.value=value }
    private fun createDeck(cacheName:String): DesktopAudioEngine {
        lateinit var deck: DesktopAudioEngine
        deck=DesktopAudioEngine(File(OtoDirs.cacheDir,cacheName),scope,
            onEnded={ scope.launch { if(activeDeck.value === deck) onTrackFinished(completedNaturally=true) } },
            onError={ err -> scope.launch { if(activeDeck.value === deck) { Timber.w(err,"Fallo en pista actual");_state.value=_state.value.copy(playing=false,buffering=false,error="No se pudo reproducir. Pulsa para reintentar.") } } })
        return deck
    }
    private val decks = listOf(createDeck("audio"),createDeck("audio-crossfade"))
    val supportsCrossfade: Boolean get() = decks.all { it.supportsCrossfade }
    private data class PreparedNext(
        val currentId:String,
        val next:SongItem,
        val deck:DesktopAudioEngine,
        val quality:Quality?,
        val gain:Float,
        val adhdSegment:AdhdSegment? = null,
    )
    @Volatile private var preparedNext:PreparedNext? = null
    @Volatile private var preparingKey:Pair<String,String>? = null
    @Volatile private var preparingDeck:DesktopAudioEngine? = null
    private val preparationGeneration=AtomicLong()
    @Volatile private var preparation:Job? = null
    @Volatile private var fading:Job? = null
    @Volatile private var outgoing:DesktopAudioEngine? = null
    internal val crossfadeActive: Boolean get() = outgoing != null
    @Volatile private var fadeSeconds = 0
    @Volatile private var fadeIn = 1f
    @Volatile private var fadeOut = 0f
    @Volatile private var outgoingGain = 1f

    init {
        engine=decks.first()
        engine.setGain(volumeGain())
        scope.launch {
            activeDeck.filterNotNull().collectLatest { active -> active.state.collect { es ->
                _positionMs.value = es.positionMs
                val cur = _state.value
                _state.value = cur.copy(
                    playing = es.playing,
                    buffering = resolvingTrack || es.buffering,
                    durationMs = if (es.durationMs > 0L) es.durationMs else cur.durationMs,
                )
                val activeDj = dj
                if (activeDj != null && activeDj.mode == DjMode.Adhd && es.durationMs > 0L && _adhdSegment.value == null && !crossfadeActive) {
                    val curSong = songQueue.getOrNull(currentIndex)
                    if (curSong != null && curSong.id == cur.id) {
                        val fadeMs = DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(fadeSeconds, ramSaverEnabled)
                        val seg = synchronized(activeDj) { activeDj.adhdSegment(curSong.toTrack(), es.durationMs, fadeMs) }
                        _adhdSegment.value = seg
                        if (seg != null && currentTrackStartOffsetMs == 0L && seg.startMs > 5000L && es.positionMs < 3000L && es.playing && !es.buffering) {
                            currentTrackStartOffsetMs = seg.startMs
                            active.seekTo(seg.startMs)
                        }
                    }
                }
            } }
        }
        scope.launch {
            prefs.shuffle.collect {
                _defaultShuffle.value = it
                if (currentListId == null) _shuffle.value = it
            }
        }
        scope.launch {
            prefs.loop.collect {
                _defaultLoop.value = it
                if (currentListId == null && originalQueue != null) applyLoopMode(if (it) LoopMode.All else LoopMode.Off)
            }
        }
        scope.launch { prefs.playlistModes.collect { _modes.value = it } }
        scope.launch { prefs.likedIds.collect { liked = it } }
        scope.launch { prefs.smartMix.collect { smartMixEnabled = it } }
        scope.launch {
            prefs.djMood.collect { mood ->
                _djMood.value = mood
                dj?.let { activeDj -> synchronized(activeDj) { activeDj.setMood(mood) } }
            }
        }
        scope.launch {
            prefs.djMode.collect { mode ->
                if (dj == null) _djMode.value = mode
            }
        }
        scope.launch {
            prefs.history.collect {
                heardAt = withContext(Dispatchers.IO) { runCatching { plays.all() }.getOrDefault(emptyList()) }.filter { it.heard }.associate { it.id to it.at }
            }
        }
        scope.launch {
            kotlinx.coroutines.flow.combine(prefs.crossfade, prefs.ramSaver) { seconds, saving ->
                ramSaverEnabled = saving
                if (saving) 0 else seconds
            }.collect {
                fadeSeconds = it.coerceAtLeast(0)
                if (fadeSeconds == 0 && dj?.mode != DjMode.Adhd) cancelCrossfade()
            }
        }
        scope.launch { watchCrossfade() }
    }

    fun beat(): Float = engine.beat(position())

    /** El diario de escuchas, para las listas «Hecho con lo tuyo». */
    suspend fun allPlays(): List<Play> = withContext(Dispatchers.IO) { runCatching { plays.all() }.getOrDefault(emptyList()) }

    /** Mantiene el volumen del usuario y la normalización separados de la curva del fundido. */
    private fun applyDeckGains() {
        engine.setGain((volumeGain() * currentLoudnessGain * fadeIn).coerceIn(0f,2f))
        outgoing?.setGain((volumeGain() * outgoingGain * fadeOut).coerceIn(0f,2f))
    }

    private fun cancelCrossfade() {
        preparationGeneration.incrementAndGet()
        fading?.cancel(); fading=null
        preparation?.cancel(); preparation=null
        preparingDeck?.takeIf { it !== activeDeck.value && it !== outgoing }?.stop(); preparingDeck=null
        preparedNext?.deck?.takeIf { it !== activeDeck.value }?.stop()
        preparedNext=null; preparingKey=null
        outgoing?.takeIf { it !== activeDeck.value }?.stop(); outgoing=null
        engine.resetMixBands()
        fadeIn=1f;fadeOut=0f
        applyDeckGains()
    }

    /** Prepara la siguiente canción con antelación; si no llega, la transición normal sigue intacta. */
    private suspend fun watchCrossfade() {
        while (scope.coroutineContext[Job]?.isActive != false) {
            val current = songQueue.getOrNull(currentIndex)
            val next = songQueue.getOrNull(currentIndex+1)
            val state = engine.state.value
            val activeAdhd = if (dj?.mode == DjMode.Adhd) _adhdSegment.value else null
            val pcmSupported = supportsCrossfade
            val canPrepareCrossfade = pcmSupported && !ramSaverEnabled && (fadeSeconds > 0 || activeAdhd != null)
            val length = if (activeAdhd != null) DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(fadeSeconds, ramSaverEnabled) else fadeSeconds * 1000L
            val key = if(current!=null && next!=null) current.id to next.id else null
            val minTrackDurationOk = activeAdhd != null || state.durationMs >= length * 3L
            if(!canPrepareCrossfade || length <= 0 || key==null || !state.playing || state.buffering || !minTrackDurationOk) {
                if(fading?.isActive != true && (preparedNext!=null || preparation?.isActive==true)) cancelCrossfade()
            } else if(fading?.isActive != true) {
                if(preparingKey != key) {
                    cancelCrossfade()
                    preparingKey=key
                    val generation=preparationGeneration.get()
                    preparation=scope.launch(Dispatchers.IO) { prepareNext(current!!,next!!,generation) }
                }
            }

            if (fading?.isActive != true && current != null) {
                val candidate = preparedNext?.takeIf { it.currentId == current.id && it.next.id == next?.id }
                val action = DesktopTransitionPlanner.evaluate(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    playing = state.playing,
                    buffering = state.buffering,
                    configuredFadeSeconds = fadeSeconds,
                    ramSaver = ramSaverEnabled,
                    crossfadeSupported = pcmSupported,
                    nextReady = candidate != null,
                    adhdSegment = activeAdhd,
                )
                when (action) {
                    is TransitionAction.StartCrossfade -> if (candidate != null) {
                        fading = scope.launch { performCrossfade(candidate, action.durationMs) }
                    }
                    TransitionAction.FinishTrackNow -> {
                        val trackKey = "${currentIndex}:${current.id}"
                        if (finishedAdhdTrackKey != trackKey) {
                            finishedAdhdTrackKey = trackKey
                            onTrackFinished(completedNaturally = true)
                        }
                    }
                    TransitionAction.None -> Unit
                }
            }
            delay(100)
        }
    }

    private suspend fun prepareNext(current:SongItem,next:SongItem,generation:Long) {
        val deck=decks.first { it !== engine }
        preparingDeck=deck
        deck.stop()
        try {
            val duration=(next.duration ?: 0).takeIf { it>0 }?.times(1000L) ?: 0L
            val activeDj = dj
            val adhdFadeMs = DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(fadeSeconds, ramSaverEnabled)
            var nextSeg = if (activeDj?.mode == DjMode.Adhd && duration > 0L) {
                synchronized(activeDj) { activeDj.adhdSegment(next.toTrack(), duration, adhdFadeMs) }
            } else null
            val initialPos = nextSeg?.startMs ?: 0L
            val local=downloads.localFile(next.id)
            val quality:Quality?
            val gain:Float
            if(local!=null) {
                quality=downloads.quality(next.id); gain=1f
                if(!deck.loadLocalFile(local,duration,playWhenReady=false,initialPositionMs=initialPos)) return
            } else {
                val tier=if(prefs.dataSaver.first() && prefs.quality.first()!=QualityMode.Max) Tier.Low else Tier.High
                val stream=InnerTubeXPlayer.resolve(next.id,tier).getOrThrow()
                currentCoroutineContext().ensureActive()
                quality=stream.quality
                gain=stream.loudnessDb?.let { min(1f,10f.pow(-it.toFloat()/20f)) } ?: 1f
                deck.loadAndPlay(tier.key(next.id),stream.url,stream.headers,stream.contentLength,duration,stream.quality.codec,playWhenReady=false,initialPositionMs=initialPos)
            }
            val ready=withTimeoutOrNull(90_000) { deck.state.first { !it.buffering && it.bufferedFraction>=1f } } != null
            if (ready && activeDj?.mode == DjMode.Adhd && nextSeg == null && deck.state.value.durationMs > 0L) {
                nextSeg = synchronized(activeDj) { activeDj.adhdSegment(next.toTrack(), deck.state.value.durationMs, adhdFadeMs) }
                if (nextSeg != null && nextSeg.startMs > 0L) deck.seekTo(nextSeg.startMs)
            }
            if(ready && preparationGeneration.get()==generation && preparingKey==(current.id to next.id) && activeDeck.value !== deck) preparedNext=PreparedNext(current.id,next,deck,quality,gain,nextSeg)
        } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(error:Exception) { Timber.w(error,"No se pudo preparar la siguiente canción para el fundido") }
        finally {
            if(preparationGeneration.get()==generation) {
                if(preparedNext?.deck !== deck && activeDeck.value !== deck && outgoing !== deck) deck.stop()
                if(preparingDeck === deck) preparingDeck=null
            }
        }
    }

    private suspend fun performCrossfade(next:PreparedNext,length:Long) {
        val old=engine
        val originalIndex=currentIndex
        val oldGain=currentLoudnessGain
        val deck=next.deck
        val (_, startInBands) = MixCurve.bands(0f, smartMixEnabled)
        deck.setMixBands(startInBands.copy(overall = 1f))
        deck.setGain(0f)
        deck.resume()
        val started=withTimeoutOrNull(1500) { deck.state.first { it.playing && !it.buffering && it.positionMs>0 } } != null
        if(!started || engine !== old || !old.state.value.playing || songQueue.getOrNull(originalIndex+1)?.id != next.next.id || currentIndex!=originalIndex) {
            deck.stop(); preparedNext=null; return
        }
        recordPlayProgress(completed=true)
        outgoing=old;outgoingGain=oldGain;fadeOut=1f;fadeIn=0f
        preparedNext=null;preparation=null;preparingKey=null
        currentIndex=originalIndex+1
        currentLoudnessGain=next.gain
        val resolvedNextSeg = next.adhdSegment ?: dj?.takeIf { it.mode == DjMode.Adhd }?.let { activeDj ->
            deck.state.value.durationMs.takeIf { it > 0L }?.let { dur ->
                synchronized(activeDj) {
                    activeDj.adhdSegment(next.next.toTrack(), dur, DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(fadeSeconds, ramSaverEnabled))
                }
            }
        }
        _adhdSegment.value = resolvedNextSeg
        currentTrackStartOffsetMs = resolvedNextSeg?.startMs ?: 0L
        finishedAdhdTrackKey = null
        engine=deck
        val song=next.next
        _currentQuality.value=next.quality
        next.quality?.let { StreamInfo.put(song.id,it) }
        _state.value=NowPlaying(id=song.id,title=song.title,artist=song.artists.joinToString { it.name },artists=song.artists.map { it.name to it.id },album=song.album?.name,artwork=song.thumbnail,playing=true,buffering=false,durationMs=deck.state.value.durationMs,upNext=(songQueue.size-1-currentIndex).coerceAtLeast(0),index=currentIndex)
        syncQueueFlow()
        scope.launch { prefs.addHeard(song.toHeard()) }
        fetchLyrics(song)
        maybeReplenishQueue()
        val start=System.nanoTime()
        try {
            val safeLength = length.coerceAtLeast(200L)
            while(true) {
                val p=((System.nanoTime()-start)/1_000_000.0/safeLength).toFloat().coerceIn(0f,1f)
                val (outBands, inBands) = MixCurve.bands(p, smartMixEnabled)
                fadeOut=outBands.overall; fadeIn=inBands.overall
                old.setMixBands(outBands.copy(overall = 1f))
                deck.setMixBands(inBands.copy(overall = 1f))
                applyDeckGains()
                if(p>=1f || engine !== deck || !deck.state.value.playing) break
                delay(16)
            }
        } finally {
            if(outgoing===old) { old.stop();outgoing=null }
            if(activeDeck.value===deck) { fadeIn=1f;fadeOut=0f;deck.resetMixBands();applyDeckGains() }
        }
    }

    override fun position(): Long = _positionMs.value

    override fun restarts(): Boolean = _positionMs.value > 3500L

    override fun play(song: SongItem) {
        clearDjSession()
        stopAutoplay()
        radio = Radio(song.id)
        originalQueue = null
        currentListId = null
        _shuffle.value = _defaultShuffle.value
        applyLoopMode(LoopMode.Off)
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
        ages = ages + List(added.size) { null }
        songQueue.addAll(if (_shuffle.value) added.shuffled() else added)
        syncQueueFlow()
    }

    fun enqueue(song: SongItem) {
        cancelCrossfade()
        if (songQueue.isEmpty()) { play(song); return }
        songQueue.add(song)
        syncQueueFlow()
    }

    override fun playNext(song: SongItem) {
        cancelCrossfade()
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
        _source.value = when (from) {
            is PlaylistItem -> Source("DE LA PLAYLIST", from.title, from)
            is AlbumItem -> Source("DEL ÁLBUM", from.title, from)
            is ArtistItem -> Source("DEL ARTISTA", from.title, from)
            else -> null
        }
        playList(from?.id, songs, List(songs.size) { null }, start)
    }

    /** Una lista tuya, tal y como se ve ([newestFirst]: lo último que entró, arriba). */
    override fun playLocal(list: LocalPlaylist, start: Int?, newestFirst: Boolean) {
        if (list.songs.isEmpty()) return
        val smart = list.id.startsWith(io.github.iaguito22.oto.dj.Smart.PREFIX)
        _source.value = Source(
            when { smart -> "HECHA CON LO TUYO"; list.id.startsWith("folder:") -> "DE TU CARPETA"; else -> "DESDE TU LISTA" },
            list.name, local = list.id.takeUnless { smart },
        )
        val n = list.songs.size
        val age = { i: Int -> if (n == 1) 0f else (if (newestFirst) i else n - 1 - i) / (n - 1f) }
        playList(list.id, list.songs.map { it.toSong() }, List(n) { age(it) }, start)
    }

    /**
     * Como en el móvil: la lista recupera su aleatorio y su bucle (o los de Ajustes si es nueva). Con aleatorio, la
     * tocada ([start]) suena la primera y el resto se baraja con [Shuffle], que evita repetir artista seguido.
     */
    private fun clearDjSession() {
        dj = null
        djReplenishJob?.cancel()
        djReplenishJob = null
        _djActive.value = false
        _adhdSegment.value = null
        currentTrackStartOffsetMs = 0L
        finishedAdhdTrackKey = null
    }

    private fun playList(listId: String?, items: List<SongItem>, itemAges: List<Float?>, start: Int?) {
        clearDjSession()
        radio = null
        stopAutoplay()
        currentListId = listId
        val mode = listId?.let { _modes.value[it] } ?: PlaylistMode(_defaultShuffle.value, defaultLoopMode())
        _shuffle.value = mode.shuffle
        applyLoopMode(mode.loop)
        if (listId != null && listId !in _modes.value) saveMode(listId, mode)
        originalQueue = items.toList()
        ages = itemAges
        val ordered = if (!mode.shuffle) items else {
            val first = start?.coerceIn(items.indices)
            Shuffle.order(shuffleItems(items, itemAges), first, liked, heardAt).map(items::get)
        }
        songQueue = ordered.toMutableList()
        currentIndex = if (mode.shuffle) 0 else (start ?: 0).coerceIn(items.indices)
        syncQueueFlow()
        startCurrentTrack()
    }

    private fun shuffleItems(items: List<SongItem>, itemAges: List<Float?>) =
        items.mapIndexed { i, s -> Shuffle.Item(s.id, s.artists.joinToString { it.name }, itemAges.getOrNull(i)) }

    override fun playDj(onFail: () -> Unit) = playDj(DjMode.Normal, onFail)

    fun playAdhdDj(onFail: () -> Unit) = playDj(DjMode.Adhd, onFail)

    fun playDj(mode: DjMode, onFail: () -> Unit) {
        scope.launch {
            val mood = _djMood.value
            val newDj = withContext(Dispatchers.Default) {
                val lists = prefs.playlists.first()
                val liked = prefs.liked.first()
                val history = prefs.history.first()
                val profile = Profile(lists, liked, history, plays.all(), System.currentTimeMillis())
                Dj(profile, djCatalog, Memory(File(OtoDirs.filesDir, "dj.json")), scope, mood = mood, mode = mode) { Timber.tag("dj").d(it) }
            }
            val first = newDj.start()
            if (first == null) {
                onFail()
                return@launch
            }
            prefs.setDjMode(mode)
            radio = null
            stopAutoplay()
            djReplenishJob?.cancel()
            djReplenishJob = null
            dj = newDj
            _djActive.value = true
            _djMode.value = mode
            originalQueue = null
            currentListId = null
            applyLoopMode(LoopMode.Off)
            _source.value = Source("DJ", if (mode == DjMode.Adhd) "DJ rápido (ADHD)" else "Mezcla para ti")
            songQueue = mutableListOf(first.toSong())
            currentIndex = 0
            syncQueueFlow()
            startCurrentTrack()
            maybeReplenishQueue()
        }
    }

    fun setDjMood(mood: DjMood) {
        _djMood.value = mood
        dj?.let { activeDj -> synchronized(activeDj) { activeDj.setMood(mood) } }
        scope.launch { prefs.setDjMood(mood) }
    }

    fun setDjMode(mode: DjMode) {
        _djMode.value = mode
        scope.launch { prefs.setDjMode(mode) }
        val activeDj = dj ?: return
        if (activeDj.mode == mode) return
        cancelCrossfade()
        synchronized(activeDj) { activeDj.setMode(mode) }
        _source.value = Source("DJ", if (mode == DjMode.Adhd) "DJ rápido (ADHD)" else "Mezcla para ti")
        if (mode == DjMode.Adhd) {
            val cur = songQueue.getOrNull(currentIndex)
            val dur = _state.value.durationMs
            if (cur != null && dur > 0L) {
                val fadeMs = DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(fadeSeconds, ramSaverEnabled)
                val seg = synchronized(activeDj) { activeDj.adhdSegment(cur.toTrack(), dur, fadeMs) }
                _adhdSegment.value = seg
                if (seg != null && _positionMs.value < seg.startMs) {
                    currentTrackStartOffsetMs = seg.startMs
                    engine.seekTo(seg.startMs)
                }
            }
        } else {
            _adhdSegment.value = null
            finishedAdhdTrackKey = null
        }
    }

    fun reportDjReaction(trackId: String, reaction: DjReaction) {
        val activeDj = dj ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { synchronized(activeDj) { activeDj.react(trackId, reaction) } }
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
        cancelCrossfade()
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
        if (index !in songQueue.indices) return
        cancelCrossfade()
        val removingCurrent = index == currentIndex
        val wasPlaying = _state.value.playing
        if (removingCurrent) recordPlayProgress(completed = false)
        val removed = songQueue.removeAt(index)
        originalQueue?.indexOf(removed)?.takeIf { it >= 0 }?.let { at ->
            originalQueue = originalQueue!!.toMutableList().apply { removeAt(at) }
            ages = ages.toMutableList().apply { if (at < size) removeAt(at) }
        }
        if (index < currentIndex) currentIndex--
        if (songQueue.isEmpty()) {
            resolveJob?.cancel(); lyricsJob?.cancel(); resolvingTrack = false
            clearDjSession()
            engine.stop()
            currentIndex = -1
            _state.value = NowPlaying()
            _lyrics.value = null
            _lyricsLoading.value = false
            _source.value = null
        } else if (removingCurrent) {
            currentIndex = currentIndex.coerceAtMost(songQueue.lastIndex)
            startCurrentTrack(playWhenReady = wasPlaying)
        }
        syncQueueFlow()
    }

    fun clearQueue() {
        cancelCrossfade()
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
        originalQueue = songQueue.toList()
        ages = List(songQueue.size) { null }
        stopAutoplay()
        syncQueueFlow()
    }

    /** El oído percibe la intensidad en escala logarítmica: con el control lineal, 1 % (−40 dB) aún sonaba claro. Potencia 1,5: 50 % ≈ −9 dB, 10 % ≈ −30 dB, 1 % ≈ −60 dB. */
    private fun volumeGain(): Float = _volume.value.let { it * kotlin.math.sqrt(it) }

    fun setVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        _volume.value = clamped
        desktopKv.putString("volume", clamped.toString())
        applyDeckGains()
    }

    fun adjustVolume(delta: Float) {
        setVolume(_volume.value + delta)
    }

    override fun setShuffle(on: Boolean) = setShuffle(on, null)

    /**
     * Pone o quita el aleatorio de [listId] (o de lo que suena) y lo recuerda para esa lista. Si es la que suena, se
     * rehace lo que queda detrás de la actual: lo que ya sonó en esta vuelta va al final y no repite artista seguido.
     * No toca el valor de Ajustes, que es solo para las listas nuevas.
     */
    fun setShuffle(on: Boolean, listId: String?) {
        val id = listId ?: currentListId
        if (id != null) {
            val loop = if (id == currentListId) _loopMode.value else _modes.value[id]?.loop ?: defaultLoopMode()
            saveMode(id, PlaylistMode(on, loop))
        }
        if (listId != null && listId != currentListId) return
        cancelCrossfade()
        val changed = _shuffle.value != on
        _shuffle.value = on
        if (!changed || currentIndex !in songQueue.indices) return
        val current = songQueue[currentIndex]
        val played = songQueue.take(currentIndex + 1)
        val pending = songQueue.drop(currentIndex + 1)
        val (listed, extra) = pending.partition { it.id !in autoIds }
        val next = if (on) {
            if (originalQueue == null) { originalQueue = songQueue.toList(); ages = List(songQueue.size) { null } }
            val original = originalQueue.orEmpty()
            val ageOf = original.withIndex().associate { (i, s) -> s.id to ages.getOrNull(i) }
            val playedIds = played.mapTo(HashSet()) { it.id }
            val order = Shuffle.order(listed.map { Shuffle.Item(it.id, it.artists.joinToString { a -> a.name }, ageOf[it.id]) }, null, liked, heardAt,
                played = playedIds, after = current.artists.joinToString { it.name })
            order.map(listed::get)
        } else {
            val rank = originalQueue.orEmpty().mapIndexed { i, song -> song.id to i }.toMap()
            listed.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        }
        songQueue = (played + next + extra).toMutableList()
        syncQueueFlow()
    }

    override fun setLoop(on: Boolean) = setLoop(if (on) LoopMode.All else LoopMode.Off)

    /** Pasa al siguiente estado del bucle: apagado → lista entera → una canción → apagado. */
    fun cycleLoop() = setLoop(when (_loopMode.value) { LoopMode.Off -> LoopMode.All; LoopMode.All -> LoopMode.One; LoopMode.One -> LoopMode.Off })

    /** Pone el bucle de lo que suena y lo recuerda para esa lista. */
    fun setLoop(mode: LoopMode) {
        applyLoopMode(mode)
        currentListId?.let { id ->
            val shuffle = _modes.value[id]?.shuffle ?: _shuffle.value
            saveMode(id, PlaylistMode(shuffle, mode))
        }
        if (mode != LoopMode.Off) dropAutoplay() else maybeAutoplay()
    }

    private fun applyLoopMode(mode: LoopMode) {
        _loopMode.value = mode
        _loop.value = mode != LoopMode.Off
    }

    private fun defaultLoopMode() = if (_defaultLoop.value) LoopMode.All else LoopMode.Off

    private fun saveMode(id: String, mode: PlaylistMode) {
        _modes.value = _modes.value + (id to mode)
        scope.launch { prefs.setPlaylistMode(id, mode) }
    }

    override fun toggle() {
        val st = _state.value
        if (st.id == null) return
        cancelCrossfade()
        if (st.error != null) {
            startCurrentTrack()
        } else if (st.buffering && resolvingTrack) {
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
        cancelCrossfade()
        engine.seekTo(ms)
    }

    override fun next() {
        cancelCrossfade()
        recordPlayProgress(completed = false)
        advanceToNext()
    }

    override fun previous() {
        cancelCrossfade()
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
        if (completedNaturally && _loopMode.value == LoopMode.One && currentIndex in songQueue.indices) {
            startCurrentTrack()
            return
        }
        if (_loopMode.value != LoopMode.Off && currentIndex == songQueue.lastIndex && songQueue.isNotEmpty()) {
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
                val activeDj = dj
                if (activeDj != null) {
                    val nextTrack = withContext(Dispatchers.IO) { runCatching { activeDj.next() }.getOrNull() }
                    if (nextTrack != null && dj === activeDj) {
                        songQueue.add(nextTrack.toSong())
                        currentIndex++
                        syncQueueFlow()
                        startCurrentTrack()
                        maybeReplenishQueue()
                        return@launch
                    }
                }
                // Una lista sin bucle: lo que ya tenía preparado la reproducción automática (o lo pide ahora).
                if (originalQueue != null && dj == null && radio == null && _loopMode.value == LoopMode.Off) {
                    maybeAutoplay()
                    autoJob?.join()
                    if (currentIndex + 1 < songQueue.size) {
                        currentIndex++
                        syncQueueFlow()
                        startCurrentTrack()
                        maybeReplenishQueue()
                        return@launch
                    }
                }
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

    /**
     * Como en el móvil: una lista sin bucle, al acercarse a su final, sigue con canciones parecidas a ella (ver
     * [Autoplay]). Se piden con [RADIO_AHEAD] por delante, así no hay silencio al acabar.
     */
    private fun maybeAutoplay() {
        if (originalQueue == null || radio != null || dj != null || _loopMode.value != LoopMode.Off || songQueue.isEmpty()) return
        if (songQueue.size - 1 - currentIndex > RADIO_AHEAD) return
        val a = auto ?: run {
            val items = songQueue.filter { it.id !in autoIds }.map { Shuffle.Item(it.id, it.artists.joinToString { a -> a.name }, null) }
            Autoplay(Autoplay.seeds(items, songQueue.last().id, liked)).also { auto = it }
        }
        if (autoJob?.isActive == true) return
        autoJob = scope.launch {
            val exclude = HashSet<String>()
            songQueue.forEach { s -> exclude += s.id; exclude += songKey(s.title, s.artists.joinToString { it.name }) }
            val last = songQueue.lastOrNull()?.id
            repeat(RADIO_PAGES) {
                val fresh = withContext(Dispatchers.IO) { runCatching { a.next(exclude, last) }.getOrDefault(emptyList()) }
                if (auto !== a) return@launch
                if (fresh.isNotEmpty()) {
                    fresh.forEach { autoIds += it.id }
                    songQueue.addAll(fresh)
                    syncQueueFlow()
                    return@launch
                }
            }
        }
    }

    private fun stopAutoplay() {
        autoJob?.cancel(); autoJob = null
        auto = null
        autoIds.clear()
    }

    /** Se puso el bucle con las parecidas ya en la cola: fuera las que aún no han sonado. */
    private fun dropAutoplay() {
        if (auto == null && autoIds.isEmpty()) return
        val ids = HashSet(autoIds)
        stopAutoplay()
        val kept = songQueue.filterIndexed { i, s -> i <= currentIndex || s.id !in ids }
        if (kept.size != songQueue.size) {
            cancelCrossfade()
            songQueue = kept.toMutableList()
            syncQueueFlow()
        }
    }

    private fun maybeReplenishQueue() {
        maybeAutoplay()
        val remaining = songQueue.size - 1 - currentIndex
        if (remaining > 2) return
        val activeDj = dj
        if (activeDj != null) {
            if (djReplenishJob?.isActive == true) return
            djReplenishJob = scope.launch(Dispatchers.IO) {
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
        stopAutoplay(); currentListId = null
        clearDjSession()
        radio = null; originalQueue = songs.toList(); ages = List(songs.size) { null }; songQueue = songs.toMutableList(); currentIndex = index.coerceIn(songs.indices)
        syncQueueFlow(); startCurrentTrack(false, positionMs)
    }

    private fun startCurrentTrack(playWhenReady: Boolean = true, initialPositionMs: Long = 0L) {
        val song = songQueue.getOrNull(currentIndex) ?: return
        cancelCrossfade()
        resolveJob?.cancel()
        engine.stop()
        _currentQuality.value = null
        finishedAdhdTrackKey = null
        resolvingTrack = true
        val durGuess = (song.duration ?: 0).takeIf { it > 0 }?.times(1000L) ?: 0L
        val activeDj = dj
        val adhdSeg = if (activeDj?.mode == DjMode.Adhd && durGuess > 0L) {
            val fadeMs = DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(fadeSeconds, ramSaverEnabled)
            synchronized(activeDj) { activeDj.adhdSegment(song.toTrack(), durGuess, fadeMs) }
        } else null
        _adhdSegment.value = adhdSeg
        val startPosMs = if (initialPositionMs > 0L) initialPositionMs else adhdSeg?.startMs ?: 0L
        currentTrackStartOffsetMs = startPosMs

        resolveJob = scope.launch {
            val dataSaver = prefs.dataSaver.first()
            val qualityMode = prefs.quality.first()
            val tier = if (dataSaver && qualityMode != io.github.iaguito22.oto.QualityMode.Max) Tier.Low else Tier.High

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
                engine.setGain(volumeGain())
                resolvingTrack = false
                engine.loadLocalFile(local, durGuess, playWhenReady, startPosMs)
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
                engine.setGain(gain * volumeGain())
                resolvingTrack = false
                engine.loadAndPlay(
                    cacheKey = tier.key(song.id),
                    streamUrl = stream.url,
                    headers = stream.headers,
                    contentLength = stream.contentLength,
                    durationMs = durGuess,
                    codec = stream.quality.codec,
                    playWhenReady = playWhenReady,
                    initialPositionMs = startPosMs,
                )
            }.onFailure { err ->
                resolvingTrack = false
                Timber.e(err, "No se pudo resolver stream para ${song.id}")
                _state.value = _state.value.copy(playing = false, buffering = false, error = err.message)
            }
        }
    }

    private var lyricsPrefetchJob: Job? = null

    /** Busca ya la letra (y su romanización) de las dos siguientes, para que salga al instante al cambiar. */
    private fun prefetchNextLyrics(current: SongItem) {
        lyricsPrefetchJob?.cancel()
        val upcoming = songQueue.indexOfFirst { it.id == current.id }.takeIf { it >= 0 }
            ?.let { songQueue.drop(it + 1).take(2) }.orEmpty()
        if (upcoming.isEmpty()) return
        lyricsPrefetchJob = scope.launch(Dispatchers.IO) {
            for (next in upcoming) {
                val durMs = (next.duration ?: 0).takeIf { it > 0 }?.times(1000L) ?: continue
                runCatching {
                    val fetched = lyricsStore.get(LyricsQuery(next.id, next.title, next.artists.joinToString { it.name }, next.album?.name, durMs))
                    (fetched.lyrics as? Lyrics.Synced)?.takeIf { it.language != null }?.let { lyricsStore.romanized(next.id, it) }
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            }
        }
    }

    private fun fetchLyrics(song: SongItem) {
        lyricsJob?.cancel()
        // Si ya estaba precargada, aparece en el mismo instante: sin hueco ni «cargando».
        val ready = lyricsStore.peek(song.id)
        _lyrics.value = ready
        _lyricsLoading.value = ready == null
        lyricsJob = scope.launch(Dispatchers.IO) {
            val artist = song.artists.joinToString { it.name }
            if ((song.duration ?: 0) <= 0) {
                kotlinx.coroutines.withTimeoutOrNull(3000) { _state.first { it.id != song.id || it.durationMs > 0 } }
                if (_state.value.id != song.id) return@launch
            }
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
                prefetchNextLyrics(song)
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
        val seg = _adhdSegment.value
        val listenedMs = DesktopTransitionPlanner.listenedDurationMs(
            completed = completed,
            positionMs = _positionMs.value,
            durationMs = dur,
            adhdSegment = seg,
            startOffsetMs = currentTrackStartOffsetMs,
        )
        val activeDj = dj
        if (listenedMs < 2000L && activeDj == null) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val song = songQueue.firstOrNull { it.id == id }?.toTrack()
                val kind = activeDj?.let { synchronized(it) { it.picks.lastOrNull { p -> p.track.id == id }?.kind } }
                if (song != null && listenedMs >= 2000L) {
                    plays.add(Play(id, song.title, song.artist, song.artistId, System.currentTimeMillis(), listenedMs, dur.coerceAtLeast(listenedMs), kind))
                }
                if (kind != null) {
                    synchronized(activeDj) { activeDj.heard(id, listenedMs, dur.coerceAtLeast(listenedMs)) }
                }
                if (id in autoIds && song != null && listenedMs >= 2000L) auto?.heard(id, song.artist, listenedMs, dur)
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
                artistId = s.artists.firstOrNull()?.id,
            )
        }
        _previousEntry.value = if (currentIndex > 0) _queue.value.getOrNull(currentIndex - 1) else null
        _state.value = _state.value.copy(index = currentIndex, upNext = (songQueue.size - currentIndex - 1).coerceAtLeast(0))
    }

    private companion object {
        /** Con cuántas por delante se piden las parecidas al acabar una lista (igual que el móvil). */
        const val RADIO_AHEAD = 2
        const val RADIO_PAGES = 3
    }
}
