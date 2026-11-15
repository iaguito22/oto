package io.github.iaguito22.oto.desktop.playback

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import io.github.iaguito22.oto.DEFAULT_SMART_MIX
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.LoopMode
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.PlaylistMode
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.QualityMode
import io.github.iaguito22.oto.dj.AdhdPlanner
import io.github.iaguito22.oto.dj.AdhdSegment
import io.github.iaguito22.oto.dj.AudioAnalysisCache
import io.github.iaguito22.oto.dj.AudioAnalyzer
import io.github.iaguito22.oto.dj.Catalog
import io.github.iaguito22.oto.dj.Dj
import io.github.iaguito22.oto.dj.DjGenre
import io.github.iaguito22.oto.dj.DjMode
import io.github.iaguito22.oto.dj.DjMood
import io.github.iaguito22.oto.dj.DjPickInfo
import io.github.iaguito22.oto.dj.DjReaction
import io.github.iaguito22.oto.dj.DjTransitionPlan
import io.github.iaguito22.oto.dj.DjTransitionPlanner
import io.github.iaguito22.oto.dj.DjTransitionStyle
import io.github.iaguito22.oto.dj.GenreStore
import io.github.iaguito22.oto.dj.Memory
import io.github.iaguito22.oto.dj.Play
import io.github.iaguito22.oto.dj.Plays
import io.github.iaguito22.oto.dj.Profile
import io.github.iaguito22.oto.dj.SongSection
import io.github.iaguito22.oto.dj.SongSections
import io.github.iaguito22.oto.dj.label
import io.github.iaguito22.oto.dj.toTrack
import io.github.iaguito22.oto.importer.songKey
import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.lyrics.LyricsQuery
import io.github.iaguito22.oto.lyrics.LyricsStore
import io.github.iaguito22.oto.playback.Autoplay
import io.github.iaguito22.oto.playback.InnerTubeXPlayer
import io.github.iaguito22.oto.playback.NowPlaying
import io.github.iaguito22.oto.playback.OtoPlayer
import io.github.iaguito22.oto.playback.Quality
import io.github.iaguito22.oto.playback.QueueEntry
import io.github.iaguito22.oto.playback.Radio
import io.github.iaguito22.oto.playback.Shuffle
import io.github.iaguito22.oto.playback.Source
import io.github.iaguito22.oto.playback.StreamInfo
import io.github.iaguito22.oto.playback.Tier
import io.github.iaguito22.oto.toHeard
import io.github.iaguito22.oto.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Implementación de [OtoPlayer] para Linux y Windows.
 * Comparte al 100% la lógica de negocio de `:core`:
 * - Resolución de streams con `InnerTubeXPlayer.resolve(videoId, tier)` y `StreamInfo.put(id, quality)`.
 * - Cola inteligente con `Radio`, `Shuffle`, `Dj` (`YouTubeCatalog`, `Plays`, `AudioAnalysisCache`).
 * - Letras sincronizadas palabra a palabra / línea a línea con `LyricsStore`.
 * - Historial y progreso guardados en `Prefs` (`DataStore`) y `Plays` (`plays.jsonl`).
 */
class DesktopPlayerConnection(
    private val prefs: Prefs,
    private val plays: Plays,
    private val djCatalog: Catalog,
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
    private val djMutex = Mutex()
    private val djGeneration = AtomicLong()
    private var djReplenishJob: Job? = null
    private val manualQueueIds = HashSet<String>()
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

    private val _djFullOnce = MutableStateFlow(false)
    /** `true` si la pista actual tiene anulado el recorte ADHD («Oír esta entera»). */
    val djFullOnce: StateFlow<Boolean> = _djFullOnce.asStateFlow()
    @Volatile private var fullOnceTrackId: String? = null

    private val _djNextUp = MutableStateFlow<List<DjPickInfo>>(emptyList())
    /** Próximos 2–3 temas preparados por el DJ (con motivo, tanda y puente). */
    val djNextUp: StateFlow<List<DjPickInfo>> = _djNextUp.asStateFlow()

    /** El género de tus canciones (de iTunes, guardado): los géneros del DJ. */
    val genres = GenreStore(File(OtoDirs.filesDir, "dj-genres.json"))
    /** Métricas reales del audio cacheadas por `track.id` (BPM, fase, loudness, drop). */
    private val analysisCache = AudioAnalysisCache(File(OtoDirs.cacheDir, "dj/audio-analysis.json"))
    /** Tus gustos tal como los vio el DJ al empezar: para saber tus géneros sin rehacerlos. */
    @Volatile private var djProfile: Profile? = null
    private val _djGenre = MutableStateFlow<String?>(null)
    /** El género al que se ciñe el DJ (null: todo lo tuyo). */
    val djGenre: StateFlow<String?> = _djGenre.asStateFlow()
    private val _djTanda = MutableStateFlow<String?>(null)
    /** La tanda que pinchaba el DJ al poner lo que suena (p. ej. «Urbano latino · como Duki»). */
    val djTanda: StateFlow<String?> = _djTanda.asStateFlow()
    private val djTandas = ConcurrentHashMap<String, String>()

    @Volatile private var smartMixEnabled = DEFAULT_SMART_MIX
    @Volatile private var ramSaverEnabled = false
    @Volatile private var currentTrackStartOffsetMs = 0L
    @Volatile private var finishedAdhdTrackKey: String? = null

    private val activeDeck = MutableStateFlow<DesktopAudioEngine?>(null)
    private var engine: DesktopAudioEngine
        get() = requireNotNull(activeDeck.value)
        set(value) { activeDeck.value = value }

    private fun createDeck(cacheName: String): DesktopAudioEngine {
        lateinit var deck: DesktopAudioEngine
        deck = DesktopAudioEngine(
            cacheDir = File(OtoDirs.cacheDir, cacheName),
            scope = scope,
            onEnded = { scope.launch { if (activeDeck.value === deck) onTrackFinished(completedNaturally = true) } },
            onError = { err ->
                scope.launch {
                    if (activeDeck.value === deck) {
                        Timber.w(err, "Fallo en pista actual")
                        _state.value = _state.value.copy(playing = false, buffering = false, error = "No se pudo reproducir. Pulsa para reintentar.")
                    }
                }
            },
        ).apply {
            createAnalysisConsumer = { trackId, _ ->
                if (analysisCache.get(trackId) != null) null
                else object : DesktopAudioEngine.PcmAnalysisConsumer {
                    private val session = AudioAnalyzer.Session(trackId, 48_000, 1)
                    override fun onSamples(samples: FloatArray, count: Int) {
                        session.feedSamples(samples, count)
                    }

                    override fun onFinish(bassPulses: FloatArray, durationMs: Long) {
                        val metrics = session.finish().let {
                            if (durationMs > 0L && it.durationMs <= 0L) it.copy(durationMs = durationMs) else it
                        }
                        analysisCache.put(metrics, persist = true)
                    }
                }
            }
        }
        return deck
    }

    private val decks = listOf(createDeck("audio"), createDeck("audio-crossfade"))
    val supportsCrossfade: Boolean get() = decks.all { it.supportsCrossfade }

    private data class PreparedNext(
        val currentId: String,
        val next: SongItem,
        val deck: DesktopAudioEngine,
        val quality: Quality?,
        val gain: Float,
        val adhdSegment: AdhdSegment? = null,
        val plan: DjTransitionPlan? = null,
    )

    @Volatile private var preparedNext: PreparedNext? = null
    @Volatile private var preparingKey: Pair<String, String>? = null
    @Volatile private var failedPrepareKey: Pair<String, String>? = null
    @Volatile private var failedPrepareAtMs: Long = 0L
    @Volatile private var preparingDeck: DesktopAudioEngine? = null
    private val preparationGeneration = AtomicLong()
    @Volatile private var preparation: Job? = null
    @Volatile private var fading: Job? = null
    @Volatile private var tempoReturnJob: Job? = null
    @Volatile private var finishFadeJob: Job? = null
    @Volatile private var outgoing: DesktopAudioEngine? = null
    private val _mixing = MutableStateFlow(false)
    /** `true` mientras hay un crossfade/transición en curso ([crossfadeActive]). */
    val mixing: StateFlow<Boolean> = _mixing.asStateFlow()

    /**
     * La próxima mezcla, para la cola y la barra de tiempo: mientras se prepara la siguiente (bajarla y analizarla)
     * solo se sabe que se está preparando; lista, dónde empieza, dónde pasa a oírse la siguiente y con qué estilo.
     */
    data class NextMix(val nextId: String, val startMs: Long?, val switchMs: Long?, val style: String?, val ready: Boolean)
    private val _nextMix = MutableStateFlow<NextMix?>(null)
    val nextMix: StateFlow<NextMix?> = _nextMix.asStateFlow()
    internal val crossfadeActive: Boolean get() = outgoing != null

    private fun setOutgoing(deck: DesktopAudioEngine?) {
        outgoing = deck
        _mixing.value = deck != null
    }
    @Volatile private var fadeSeconds = 0
    @Volatile private var fadeIn = 1f
    @Volatile private var fadeOut = 0f
    @Volatile private var outgoingGain = 1f

    init {
        engine = decks.first()
        decks.forEach { it.setGain(volumeGain()) }
        scope.launch {
            activeDeck.filterNotNull().collectLatest { active ->
                active.state.collect { es ->
                    _positionMs.value = es.positionMs
                    val cur = _state.value
                    _state.value = cur.copy(
                        playing = es.playing,
                        buffering = resolvingTrack || es.buffering,
                        durationMs = if (es.durationMs > 0L) es.durationMs else cur.durationMs,
                    )
                    val activeDj = dj
                    if (
                        activeDj != null &&
                        activeDj.mode == DjMode.Adhd &&
                        es.durationMs > 0L &&
                        _adhdSegment.value == null &&
                        !crossfadeActive &&
                        fullOnceTrackId != cur.id
                    ) {
                        val curSong = songQueue.getOrNull(currentIndex)
                        if (curSong != null && curSong.id == cur.id) {
                            val fadeMs = DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(fadeSeconds, ramSaverEnabled)
                            val seg = djMutex.withLock {
                                if (dj === activeDj && activeDj.mode == DjMode.Adhd && fullOnceTrackId != curSong.id) {
                                    activeDj.adhdSegment(
                                        curSong.toTrack(),
                                        es.durationMs,
                                        fadeMs,
                                        sectionsOf(curSong.id),
                                        analysisCache.get(curSong.id),
                                    )
                                } else null
                            }
                            if (curSong.id == _state.value.id && _adhdSegment.value == null && fullOnceTrackId != curSong.id) {
                                _adhdSegment.value = seg
                                if (seg != null && currentTrackStartOffsetMs == 0L && seg.startMs > 5000L && es.positionMs < 3000L && es.playing && !es.buffering) {
                                    currentTrackStartOffsetMs = seg.startMs
                                    active.seekTo(seg.startMs)
                                }
                            }
                        }
                    }
                }
            }
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
        scope.launch { prefs.djGenre.collect { _djGenre.value = it } }
        scope.launch {
            prefs.djMood.collect { mood ->
                _djMood.value = mood
                dj?.let { activeDj ->
                    djMutex.withLock {
                        if (dj === activeDj) activeDj.setMood(mood)
                    }
                }
            }
        }
        scope.launch {
            prefs.djMode.collect { mode ->
                if (dj == null) _djMode.value = mode
            }
        }
        scope.launch {
            prefs.history.collect {
                heardAt = withContext(Dispatchers.IO) { runCatching { plays.all() }.getOrDefault(emptyList()) }
                    .filter { it.heard }.associate { it.id to it.at }
            }
        }
        scope.launch {
            // La mezcla inteligente, si está activada, con su duración; si no, el fundido normal.
            kotlinx.coroutines.flow.combine(prefs.mixSeconds, prefs.ramSaver) { seconds, saving ->
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
        engine.setGain((volumeGain() * currentLoudnessGain * fadeIn).coerceIn(0f, 2f))
        outgoing?.setGain((volumeGain() * outgoingGain * fadeOut).coerceIn(0f, 2f))
    }

    private fun cancelCrossfade() {
        preparationGeneration.incrementAndGet()
        finishFadeJob?.cancel(); finishFadeJob = null
        tempoReturnJob?.cancel(); tempoReturnJob = null
        fading?.cancel(); fading = null
        preparation?.cancel(); preparation = null
        preparingDeck?.takeIf { it !== activeDeck.value && it !== outgoing }?.let { d ->
            d.stop()
            d.setGain(volumeGain())
        }
        preparingDeck = null
        preparedNext?.deck?.takeIf { it !== activeDeck.value && it !== outgoing }?.let { d ->
            d.stop()
            d.setGain(volumeGain())
        }
        preparedNext = null; preparingKey = null
        outgoing?.takeIf { it !== activeDeck.value }?.let { d ->
            d.stop()
            d.setGain(volumeGain())
        }
        setOutgoing(null)
        engine.resetMixBands()
        fadeIn = 1f; fadeOut = 0f
        applyDeckGains()
    }

    /** Prepara la siguiente canción con antelación; si no llega, la transición normal sigue intacta. */
    private suspend fun watchCrossfade() {
        while (scope.coroutineContext[Job]?.isActive != false) {
            val current = songQueue.getOrNull(currentIndex)
            val next = songQueue.getOrNull(currentIndex + 1)
            val state = engine.state.value
            val activeAdhd = if (dj?.mode == DjMode.Adhd && fullOnceTrackId != current?.id) _adhdSegment.value else null
            val pcmSupported = supportsCrossfade
            val canPrepareCrossfade = pcmSupported && !ramSaverEnabled && (fadeSeconds > 0 || activeAdhd != null)
            val length = if (activeAdhd != null) {
                DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(fadeSeconds, ramSaverEnabled)
            } else fadeSeconds * 1000L
            val key = if (current != null && next != null) current.id to next.id else null
            val minTrackDurationOk = activeAdhd != null || state.durationMs >= length * 3L

            if (!canPrepareCrossfade || length <= 0 || key == null || !state.playing || state.buffering || !minTrackDurationOk) {
                if (fading?.isActive != true && (preparedNext != null || preparation?.isActive == true)) cancelCrossfade()
            } else if (fading?.isActive != true) {
                val backoffActive = failedPrepareKey == key && (System.currentTimeMillis() - failedPrepareAtMs) < 5_000L
                if (preparingKey != key && !backoffActive) {
                    cancelCrossfade()
                    preparingKey = key
                    val generation = preparationGeneration.get()
                    preparation = scope.launch(Dispatchers.IO) { prepareNext(current!!, next!!, generation) }
                }
            }

            if (fading?.isActive != true && finishFadeJob?.isActive != true && current != null) {
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
                    plan = candidate?.plan,
                )
                when (action) {
                    is TransitionAction.StartCrossfade -> if (candidate != null) {
                        fading = scope.launch { performCrossfade(candidate, action.durationMs) }
                    }
                    TransitionAction.FinishTrackNow -> {
                        val trackKey = "$currentIndex:${current.id}"
                        if (finishedAdhdTrackKey != trackKey) {
                            finishedAdhdTrackKey = trackKey
                            finishFadeJob = scope.launch {
                                // Atenuación breve para no cortar en seco si la siguiente no estaba lista.
                                val steps = 6
                                for (step in 1..steps) {
                                    if (engine.state.value.idOrNull() != current.id) return@launch
                                    fadeIn = 1f - step.toFloat() / steps.toFloat()
                                    applyDeckGains()
                                    delay(20L)
                                }
                                // Dejar vaciar el buffer de hardware (~128 ms) en silencio antes de cerrar la línea.
                                delay(140L)
                                onTrackFinished(completedNaturally = true)
                            }
                        }
                    }
                    TransitionAction.None -> Unit
                }
            }
            val mix = if (!canPrepareCrossfade || length <= 0 || key == null || next == null || fading?.isActive == true) null else {
                val ready = preparedNext?.takeIf { it.currentId == current?.id && it.next.id == next.id }
                val window = ready?.let {
                    DesktopTransitionPlanner.mixWindow(state.durationMs, fadeSeconds, ramSaverEnabled, pcmSupported, activeAdhd, it.plan)
                }
                when {
                    ready != null && window != null -> NextMix(
                        nextId = next.id,
                        startMs = window.first,
                        switchMs = window.first + window.second / 2,
                        style = ready.plan?.style?.label ?: "Fundido suave",
                        ready = true,
                    )
                    preparingKey == key || ready != null -> NextMix(next.id, null, null, null, ready = false)
                    else -> null
                }
            }
            if (_nextMix.value != mix) _nextMix.value = mix
            delay(100)
        }
    }

    private fun DesktopAudioEngine.EngineState.idOrNull(): String? = _state.value.id

    private suspend fun prepareNext(current: SongItem, next: SongItem, generation: Long) {
        val deck = decks.first { it !== engine && it !== outgoing }
        preparingDeck = deck
        deck.stop()
        var succeeded = false
        try {
            val duration = (next.duration ?: 0).takeIf { it > 0 }?.times(1000L) ?: 0L
            val activeDj = dj
            val adhdFadeMs = DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(fadeSeconds, ramSaverEnabled)
            var nextSeg = if (activeDj?.mode == DjMode.Adhd && duration > 0L) {
                djMutex.withLock {
                    if (dj === activeDj) {
                        activeDj.adhdSegment(
                            next.toTrack(),
                            duration,
                            adhdFadeMs,
                            sectionsOf(next.id),
                            analysisCache.get(next.id),
                        )
                    } else null
                }
            } else null
            val initialPos = nextSeg?.startMs ?: 0L
            val local = downloads.localFile(next.id)
            val quality: Quality?
            val gain: Float
            if (local != null) {
                quality = downloads.quality(next.id)
                gain = 1f
                if (!deck.loadLocalFile(local, duration, playWhenReady = false, initialPositionMs = initialPos, trackId = next.id)) return
            } else {
                val tier = if (prefs.dataSaver.first() && prefs.quality.first() != QualityMode.Max) Tier.Low else Tier.High
                val stream = InnerTubeXPlayer.resolve(next.id, tier).getOrThrow()
                currentCoroutineContext().ensureActive()
                quality = stream.quality
                gain = safeLoudnessGain(stream.loudnessDb)
                deck.loadAndPlay(
                    cacheKey = tier.key(next.id),
                    streamUrl = stream.url,
                    headers = stream.headers,
                    contentLength = stream.contentLength,
                    durationMs = duration,
                    codec = stream.quality.codec,
                    playWhenReady = false,
                    initialPositionMs = initialPos,
                    trackId = next.id,
                )
            }
            val ready = withTimeoutOrNull(90_000) { deck.state.first { !it.buffering && it.bufferedFraction >= 1f } } != null
            if (!ready) return

            // Garantizar análisis real del tema entrante (BPM, fase/downbeat, RMS, drop) antes de planificar la mezcla.
            if (analysisCache.get(next.id) == null) {
                deck.cachedAudioFile()?.let { cached -> deck.analyzeSync(cached, next.id) }
            }
            val actualNextDur = deck.state.value.durationMs.takeIf { it > 0L } ?: duration
            if (activeDj?.mode == DjMode.Adhd && actualNextDur > 0L) {
                val updatedSeg = djMutex.withLock {
                    if (dj === activeDj) {
                        activeDj.adhdSegment(
                            next.toTrack(),
                            actualNextDur,
                            adhdFadeMs,
                            sectionsOf(next.id),
                            analysisCache.get(next.id),
                        )
                    } else null
                }
                if (updatedSeg != null) nextSeg = updatedSeg
            }

            val curDurMs = engine.state.value.durationMs
            val curAdhd = if (activeDj?.mode == DjMode.Adhd && fullOnceTrackId != current.id) _adhdSegment.value else null
            val configuredFadeMs = if (curAdhd != null) adhdFadeMs else fadeSeconds * 1000L
            val plan = if (activeDj != null) {
                djMutex.withLock {
                    if (dj === activeDj) {
                        activeDj.transitionPlan(
                            outgoing = current.toTrack(),
                            incoming = next.toTrack(),
                            outgoingDurationMs = curDurMs,
                            configuredFadeMs = configuredFadeMs,
                            tandaChanged = djTandas[current.id] != null && djTandas[next.id] != null && djTandas[current.id] != djTandas[next.id],
                            outgoingAdhd = curAdhd,
                            incomingAdhd = nextSeg,
                            outgoingVocals = sectionsOf(current.id),
                            incomingVocals = sectionsOf(next.id),
                            autoLength = smartMixEnabled,
                        )
                    } else null
                }
            } else if (smartMixEnabled) {
                DjTransitionPlanner.plan(
                    outgoingDurationMs = curDurMs,
                    configuredFadeMs = configuredFadeMs,
                    outgoingAudio = analysisCache.get(current.id),
                    incomingAudio = analysisCache.get(next.id),
                    outgoingAdhd = null,
                    incomingAdhd = null,
                    outgoingVocals = sectionsOf(current.id),
                    incomingVocals = sectionsOf(next.id),
                    autoLength = true,
                    incomingFromStart = true,
                )
            } else null

            val targetStart = plan?.incomingStartMs ?: nextSeg?.startMs ?: initialPos
            if (targetStart != initialPos && targetStart >= 0L) {
                deck.seekTo(targetStart)
            }

            if (preparationGeneration.get() == generation && preparingKey == (current.id to next.id) && activeDeck.value !== deck) {
                preparedNext = PreparedNext(current.id, next, deck, quality, gain, nextSeg, plan)
                failedPrepareKey = null
                succeeded = true
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "No se pudo preparar la siguiente canción para el fundido")
        } finally {
            if (preparationGeneration.get() == generation) {
                if (!succeeded && preparedNext?.deck !== deck) {
                    failedPrepareKey = current.id to next.id
                    failedPrepareAtMs = System.currentTimeMillis()
                    if (preparingKey == (current.id to next.id)) preparingKey = null
                }
                if (preparedNext?.deck !== deck && activeDeck.value !== deck && outgoing !== deck) {
                    deck.stop()
                    deck.setGain(volumeGain())
                }
                if (preparingDeck === deck) preparingDeck = null
            }
        }
    }

    private suspend fun performCrossfade(next: PreparedNext, length: Long) {
        val old = engine
        val originalIndex = currentIndex
        val oldGain = currentLoudnessGain
        val deck = next.deck
        val plan = next.plan
        val prevAdhd = if (dj?.mode == DjMode.Adhd && fullOnceTrackId != next.currentId) _adhdSegment.value else null
        val useEchoOut = plan?.style == DjTransitionStyle.EchoOutFilterSweep || plan?.style == DjTransitionStyle.LoopRollOut
        val useTempoNudge = plan?.style == DjTransitionStyle.BeatMatchLongMix && abs(plan.incomingTempoNudge - 1.0) > 0.004

        deck.setTempo(if (useTempoNudge) plan.incomingTempoNudge.toFloat() else 1f)
        if (useEchoOut) {
            val frame = MixCurve.echoOut(0f, plan.highPassStartHz, plan.highPassEndHz, plan.echoOutWet, plan.echoDelayMs.toInt(), plan.echoFeedback)
            deck.setMixBands(frame.inBands)
        } else {
            val (_, startInBands) = MixCurve.bands(0f, smartMixEnabled || (plan?.useBassSwap == true))
            deck.setMixBands(startInBands.copy(overall = 1f))
        }
        deck.setGain(0f)
        deck.resume()

        val started = withTimeoutOrNull(1500) { deck.state.first { it.playing && !it.buffering } } != null
        if (!started || engine !== old || !old.state.value.playing || songQueue.getOrNull(originalIndex + 1)?.id != next.next.id || currentIndex != originalIndex) {
            deck.stop()
            deck.resetMixBands()
            deck.setTempo(1f)
            deck.setGain(volumeGain())
            preparedNext = null
            preparingKey = null
            return
        }

        recordPlayProgress(completed = true)
        setOutgoing(old); outgoingGain = oldGain; fadeOut = 1f; fadeIn = 0f
        preparedNext = null; preparation = null; preparingKey = null
        currentIndex = originalIndex + 1
        val song = next.next
        manualQueueIds.remove(song.id)

        val activeDj = dj
        var resolvedTanda = if (activeDj != null) djTandas[song.id] else null
        if (activeDj != null) {
            djMutex.withLock {
                if (dj === activeDj) {
                    commitDjQueueAdvanceLocked(activeDj, song.id)
                    resolvedTanda = activeDj.tandaOf(song.id) ?: resolvedTanda
                    resolvedTanda?.let { djTandas[song.id] = it }
                }
            }
        }

        val gainMatch = plan?.incomingGainMatch ?: 1f
        currentLoudnessGain = (next.gain * gainMatch).coerceIn(0.25f, 1.45f)

        val resolvedNextSeg = next.adhdSegment ?: activeDj?.takeIf { it.mode == DjMode.Adhd }?.let { d ->
            deck.state.value.durationMs.takeIf { it > 0L }?.let { dur ->
                djMutex.withLock {
                    if (dj === d) d.adhdSegment(song.toTrack(), dur, DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(fadeSeconds, ramSaverEnabled), sectionsOf(song.id), analysisCache.get(song.id))
                    else null
                }
            }
        }
        fullOnceTrackId = null
        _djFullOnce.value = false
        _adhdSegment.value = resolvedNextSeg
        _djTanda.value = resolvedTanda
        currentTrackStartOffsetMs = plan?.incomingStartMs ?: resolvedNextSeg?.startMs ?: 0L
        finishedAdhdTrackKey = null
        engine = deck
        _currentQuality.value = next.quality
        next.quality?.let { StreamInfo.put(song.id, it) }
        _state.value = NowPlaying(
            id = song.id,
            title = song.title,
            artist = song.artists.joinToString { it.name },
            artists = song.artists.map { it.name to it.id },
            album = song.album?.name,
            artwork = song.thumbnail,
            playing = true,
            buffering = false,
            durationMs = deck.state.value.durationMs,
            upNext = (songQueue.size - 1 - currentIndex).coerceAtLeast(0),
            index = currentIndex,
        )
        syncQueueFlow()
        scope.launch { prefs.addHeard(song.toHeard()) }
        fetchLyrics(song)
        maybeReplenishQueue()

        // Ajustar duración al tiempo restante real de la saliente para que nunca llegue a EOF antes de p=1.
        val oldDur = old.state.value.durationMs
        val oldPos = old.state.value.positionMs
        val oldRemaining = if (oldDur > 0L) (oldDur - oldPos - 240L) else Long.MAX_VALUE
        val safeLength = minOf(length, oldRemaining).coerceAtLeast(250L)
        Timber.tag("oto.mix").i(
            "Iniciando mezcla %s -> %s: style=%s safeLength=%d ms oldPos=%d/%d ms tempo=%.3f adhd=%s",
            next.currentId, song.id, plan?.style ?: "Legacy", safeLength, oldPos, oldDur,
            if (useTempoNudge) plan.incomingTempoNudge else 1.0, prevAdhd != null,
        )

        val start = System.nanoTime()
        var completedCleanly = false
        // Cuadre de fase: con el tempo ya igualado, la entrante se acelera o frena un poco (aún baja) hasta que sus beats
        // caen sobre los de la saliente. Se promedian varias lecturas: la posición del PC va a saltos de un trozo.
        val baseTempo = if (useTempoNudge) plan.incomingTempoNudge.toFloat() else 1f
        val phaseLock = plan?.phaseLock == true && plan.outgoingAudio != null && plan.incomingAudio != null
        val phaseErrs = ArrayDeque<Double>()
        var phaseLocked = false
        var phaseCorrecting = false
        var lastPhaseAt = 0L
        try {
            while (true) {
                val p = ((System.nanoTime() - start) / 1_000_000.0 / safeLength).toFloat().coerceIn(0f, 1f)
                if (phaseLock && !phaseLocked && System.nanoTime() - lastPhaseAt >= 150_000_000L) {
                    lastPhaseAt = System.nanoTime()
                    if (p < plan.bassSwapAt - 0.06f) {
                        val outOff = plan.outgoingAudio!!.beatOffset(old.state.value.positionMs)
                        val inOff = plan.incomingAudio!!.beatOffset(deck.state.value.positionMs)
                        if (outOff != null && inOff != null) {
                            var err = inOff - outOff
                            if (err > 0.5) err -= 1.0
                            if (err < -0.5) err += 1.0
                            phaseErrs.addLast(err * (plan.incomingAudio?.beatPeriodMs ?: 500.0) / baseTempo)
                            if (phaseErrs.size > 3) phaseErrs.removeFirst()
                            val avg = phaseErrs.average()
                            if (phaseErrs.size >= 2 && abs(avg) <= 18.0) {
                                deck.setTempo(baseTempo)
                                phaseLocked = true
                                Timber.tag("oto.mix").i("fase cuadrada: %.0f ms a p=%.2f", avg, p)
                            } else if (phaseErrs.size >= 2) {
                                deck.setTempo(baseTempo * (1.0 - avg / 1_200.0).coerceIn(0.96, 1.04).toFloat())
                                phaseCorrecting = true
                            }
                        }
                    } else if (phaseCorrecting) {
                        deck.setTempo(baseTempo)
                        phaseCorrecting = false
                        phaseLocked = true
                        Timber.tag("oto.mix").i("fase sin cuadrar del todo al llegar al relevo")
                    }
                }
                if (useEchoOut) {
                    val frame = MixCurve.echoOut(p, plan.highPassStartHz, plan.highPassEndHz, plan.echoOutWet, plan.echoDelayMs.toInt(), plan.echoFeedback)
                    fadeOut = frame.outEnvelope
                    fadeIn = frame.inEnvelope
                    old.setMixBands(frame.outBands)
                    deck.setMixBands(frame.inBands)
                } else if (plan?.style == DjTransitionStyle.BeatMatchLongMix) {
                    val width = ((plan.incomingAudio?.beatPeriodMs ?: 500.0) / safeLength).toFloat().coerceIn(0.03f, 0.12f)
                    val (outBands, inBands) = MixCurve.djMatched(p, plan.bassSwapAt, width)
                    fadeOut = outBands.overall; fadeIn = inBands.overall
                    old.setMixBands(outBands.copy(overall = 1f))
                    deck.setMixBands(inBands.copy(overall = 1f))
                } else {
                    val useSmartBands = smartMixEnabled || (plan?.useBassSwap == true)
                    val (outBands, inBands) = MixCurve.bands(p, useSmartBands)
                    fadeOut = outBands.overall; fadeIn = inBands.overall
                    old.setMixBands(outBands.copy(overall = 1f))
                    deck.setMixBands(inBands.copy(overall = 1f))
                }
                applyDeckGains()
                if (p >= 1f) {
                    completedCleanly = true
                    break
                }
                if (engine !== deck || !deck.state.value.playing) break
                delay(16)
            }
            if (completedCleanly && engine === deck) {
                // Drenar los últimos ~160 ms (>128 ms de buffer Java Sound) ya en silencio para evitar chasquidos al cerrar la línea.
                fadeOut = 0f
                fadeIn = 1f
                deck.resetMixBands()
                applyDeckGains()
                delay(160)
            }
            Timber.tag("oto.mix").i(
                "Mezcla %s -> %s finalizada: clean=%s elapsed=%d ms",
                next.currentId, song.id, completedCleanly, (System.nanoTime() - start) / 1_000_000L,
            )
        } finally {
            if (outgoing === old) {
                old.stop()
                old.resetMixBands()
                old.setTempo(1f)
                old.setGain(volumeGain())
                setOutgoing(null)
            }
            if (activeDeck.value === deck) {
                fadeIn = 1f; fadeOut = 0f
                deck.resetMixBands()
                applyDeckGains()
                if (useTempoNudge && completedCleanly) {
                    val startTempo = plan.incomingTempoNudge.toFloat()
                    tempoReturnJob?.cancel()
                    tempoReturnJob = scope.launch {
                        val steps = 80
                        for (i in 1..steps) {
                            if (engine !== deck || !deck.state.value.playing) break
                            val t = startTempo + (1f - startTempo) * (i.toFloat() / steps.toFloat())
                            deck.setTempo(t)
                            delay(100L)
                        }
                        if (engine === deck) deck.setTempo(1f)
                    }
                } else {
                    deck.setTempo(1f)
                }
            } else {
                deck.stop()
                deck.resetMixBands()
                deck.setTempo(1f)
                deck.setGain(volumeGain())
            }
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

    fun appendArtistTracks(artistId: String, tracks: List<SongItem>) {
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
        if (dj != null) {
            manualQueueIds += song.id
            val insertAt = (songQueue.indexOfLast { it.id in manualQueueIds }.takeIf { it > currentIndex } ?: currentIndex) + 1
            songQueue.add(insertAt.coerceIn(0, songQueue.size), song)
        } else {
            songQueue.add(song)
        }
        syncQueueFlow()
    }

    override fun playNext(song: SongItem) {
        cancelCrossfade()
        if (songQueue.isEmpty()) {
            play(song)
            return
        }
        if (dj != null) manualQueueIds += song.id
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

    /** Una lista tuya, tal como se ve ([newestFirst]: lo último que entró, arriba). */
    override fun playLocal(list: LocalPlaylist, start: Int?, newestFirst: Boolean) {
        if (list.songs.isEmpty()) return
        val smart = list.id.startsWith(io.github.iaguito22.oto.dj.Smart.PREFIX)
        _source.value = Source(
            when {
                smart -> "HECHA CON LO TUYO"
                list.id.startsWith("folder:") -> "DE TU CARPETA"
                else -> "DESDE TU LISTA"
            },
            list.name, local = list.id.takeUnless { smart },
        )
        val n = list.songs.size
        val age = { i: Int -> if (n == 1) 0f else (if (newestFirst) i else n - 1 - i) / (n - 1f) }
        playList(list.id, list.songs.map { it.toSong() }, List(n) { age(it) }, start)
    }

    private fun clearDjSession() {
        djGeneration.incrementAndGet()
        dj = null
        djReplenishJob?.cancel()
        djReplenishJob = null
        manualQueueIds.clear()
        _djActive.value = false
        _djFullOnce.value = false
        fullOnceTrackId = null
        _adhdSegment.value = null
        _djNextUp.value = emptyList()
        djTandas.clear()
        _djTanda.value = null
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

    fun playDj(mode: DjMode, onFail: () -> Unit) = startDjSession(mode, seed = null, onFail = onFail)

    /** Arranca el DJ tomando [song] (y su artista/ambiente) como ancla inicial. */
    fun playDjFrom(song: SongItem, mode: DjMode = _djMode.value, onFail: () -> Unit = {}) =
        startDjSession(mode, seed = song, onFail = onFail)

    private fun startDjSession(mode: DjMode, seed: SongItem?, onFail: () -> Unit) {
        val gen = djGeneration.incrementAndGet()
        scope.launch {
            val mood = _djMood.value
            val newDj = withContext(Dispatchers.Default) {
                val lists = prefs.playlists.first()
                val liked = prefs.liked.first()
                val history = prefs.history.first()
                val profile = Profile(lists, liked, history, plays.all(), System.currentTimeMillis())
                djProfile = profile
                val known = genres.ready()
                Dj(
                    profile = profile,
                    catalog = djCatalog,
                    memory = Memory(File(OtoDirs.filesDir, "dj.json")),
                    scope = scope,
                    mood = mood,
                    mode = mode,
                    log = { Timber.tag("dj").d(it) },
                    genre = resolveGenre(_djGenre.value),
                    genreOf = { t -> known.genreOf(t, profile) },
                    analysisCache = analysisCache,
                )
            }
            if (djGeneration.get() != gen) return@launch
            // Los géneros que falten, poco a poco (iTunes limita las consultas).
            djProfile?.let { p -> scope.launch(Dispatchers.IO) { runCatching { genres.fill(p) } } }

            var first: io.github.iaguito22.oto.dj.Track? = null
            var nextUp: List<DjPickInfo> = emptyList()
            djMutex.withLock {
                if (djGeneration.get() != gen) return@withLock
                first = if (seed != null) newDj.startFrom(seed.toTrack(), playSeedFirst = true) else newDj.start()
                if (first != null) {
                    nextUp = withContext(Dispatchers.IO) {
                        runCatching { newDj.prepareNextUp(DJ_NEXT_UP_COUNT) }.getOrDefault(emptyList())
                    }
                }
            }
            val startedTrack = first
            if (startedTrack == null) {
                onFail()
                return@launch
            }
            if (djGeneration.get() != gen) return@launch
            prefs.setDjMode(mode)
            radio = null
            stopAutoplay()
            djReplenishJob?.cancel()
            djReplenishJob = null
            manualQueueIds.clear()
            dj = newDj
            _djActive.value = true
            _djMode.value = mode
            _djFullOnce.value = false
            fullOnceTrackId = null
            originalQueue = null
            currentListId = null
            applyLoopMode(LoopMode.Off)
            _source.value = Source("DJ", if (mode == DjMode.Adhd) "DJ rápido" else "Mezcla para ti")
            djTandas.clear()
            (newDj.tandaOf(startedTrack.id) ?: newDj.tanda)?.let { djTandas[startedTrack.id] = it }
            nextUp.forEach { info -> info.tanda?.let { djTandas[info.track.id] = it } }
            _djNextUp.value = nextUp
            songQueue = (listOf(startedTrack.toSong()) + nextUp.map { it.track.toSong() }).toMutableList()
            currentIndex = 0
            syncQueueFlow()
            startCurrentTrack()
        }
    }

    /** Anula el recorte ADHD solo para la pista actual sin apagar el modo rápido. */
    fun playDjFullOnce() {
        val activeDj = dj ?: return
        val cur = songQueue.getOrNull(currentIndex) ?: return
        fullOnceTrackId = cur.id
        _djFullOnce.value = true
        _adhdSegment.value = null
        finishedAdhdTrackKey = null
        if (fading?.isActive != true) cancelCrossfade()
        scope.launch {
            djMutex.withLock {
                if (dj === activeDj) activeDj.playFullOnce(cur.id)
            }
        }
    }

    /** Veta un tema preparado en la cola NextUp del DJ por su `trackId` y lo reemplaza al instante. */
    fun vetoDjNextUp(trackId: String) {
        val activeDj = dj ?: return
        if (trackId == songQueue.getOrNull(currentIndex)?.id) return
        if (preparedNext?.next?.id == trackId || preparingKey?.second == trackId) {
            cancelCrossfade()
        }
        val gen = djGeneration.get()
        djReplenishJob?.cancel()
        djReplenishJob = scope.launch {
            val updated = djMutex.withLock {
                if (dj !== activeDj || djGeneration.get() != gen) return@withLock null
                withContext(Dispatchers.IO) {
                    runCatching { activeDj.vetoNextUp(trackId, refillCount = DJ_NEXT_UP_COUNT) }.getOrNull()
                }
            } ?: return@launch
            if (dj !== activeDj || djGeneration.get() != gen) return@launch
            syncDjTail(updated)
        }
    }

    /** Veta por índice dentro de `queue` (comodidad para vistas de cola). */
    fun vetoDjNext(index: Int) {
        if (index <= currentIndex) return
        val id = songQueue.getOrNull(index)?.id ?: return
        vetoDjNextUp(id)
    }

    private fun syncDjTail(nextUp: List<DjPickInfo>) {
        nextUp.forEach { info -> info.tanda?.let { djTandas[info.track.id] = it } }
        val split = (currentIndex + 1).coerceIn(0, songQueue.size)
        val kept = songQueue.take(split)
        val manual = songQueue.drop(split).filter { it.id in manualQueueIds }
        songQueue = (kept + manual + nextUp.map { it.track.toSong() }).toMutableList()
        _djNextUp.value = nextUp
        syncQueueFlow()
    }

    private suspend fun commitDjQueueAdvanceLocked(activeDj: Dj, targetId: String) {
        val upcoming = activeDj.nextUp()
        val pos = upcoming.indexOfFirst { it.track.id == targetId }
        if (pos >= 0) {
            // Confirmamos el tema que empieza (y si se saltó directamente a uno posterior en NextUp, avanzamos hasta él).
            repeat(pos + 1) {
                val picked = runCatching { activeDj.next() }.getOrNull()
                if (picked != null) {
                    (activeDj.tandaOf(picked.id) ?: activeDj.tanda)?.let { djTandas[picked.id] = it }
                }
            }
        }
        _djNextUp.value = activeDj.nextUp()
    }

    private fun sectionsOf(id: String): List<SongSection> =
        (lyricsStore.peek(id) as? Lyrics.Synced)?.let { SongSections.of(it.lines) }.orEmpty()

    /** El género tuyo con ese nombre, de los ya conocidos (null: todo, o aún no se sabe). */
    private fun resolveGenre(name: String?): DjGenre? {
        val p = djProfile ?: return null
        return name?.let { n -> genres.top(p, max = 20).firstOrNull { it.name == n } }
    }

    /** Se ciñe a un género tuyo (null: todo). Si el DJ ya suena, lo que venía detrás se cambia por lo de ese género. */
    fun setDjGenre(name: String?) {
        _djGenre.value = name
        scope.launch { prefs.setDjGenre(name) }
        val activeDj = dj ?: return
        val gen = djGeneration.incrementAndGet()
        djReplenishJob?.cancel()
        cancelCrossfade()
        val split = (currentIndex + 1).coerceIn(0, songQueue.size)
        val kept = songQueue.take(split)
        val manual = songQueue.drop(split).filter { it.id in manualQueueIds }
        songQueue = (kept + manual).toMutableList()
        _djNextUp.value = emptyList()
        syncQueueFlow()
        djReplenishJob = scope.launch {
            val nextUp = djMutex.withLock {
                if (dj !== activeDj || djGeneration.get() != gen) return@withLock null
                activeDj.setGenre(resolveGenre(name))
                withContext(Dispatchers.IO) {
                    runCatching { activeDj.prepareNextUp(DJ_NEXT_UP_COUNT) }.getOrDefault(emptyList())
                }
            } ?: return@launch
            if (dj !== activeDj || djGeneration.get() != gen) return@launch
            syncDjTail(nextUp)
        }
    }

    /**
     * Tus géneros para la página del DJ, de más a menos escuchado: primero lo ya guardado y luego, según se van
     * encontrando los que faltan (poco a poco: iTunes limita las consultas), otra vez por [onUpdate].
     */
    suspend fun djGenres(onUpdate: (List<DjGenre>) -> Unit) {
        val p = withContext(Dispatchers.Default) {
            Profile(prefs.playlists.first(), prefs.liked.first(), prefs.history.first(), plays.all(), System.currentTimeMillis())
        }
        val g = genres.ready()
        onUpdate(g.top(p))
        runCatching { withContext(Dispatchers.IO) { g.fill(p) { onUpdate(g.top(p)) } } }
        onUpdate(g.top(p))
    }

    /** Salta el ambiente que pincha el DJ: limpia la cola futura y pasa al instante a otra tanda lejos de esta. */
    fun skipDjTanda() {
        val activeDj = dj ?: return
        val gen = djGeneration.incrementAndGet()
        djReplenishJob?.cancel()
        cancelCrossfade()
        if (songQueue.size > currentIndex + 1) {
            songQueue = songQueue.take(currentIndex + 1).toMutableList()
        }
        manualQueueIds.clear()
        _djNextUp.value = emptyList()
        syncQueueFlow()
        djReplenishJob = scope.launch {
            var pickedSong: SongItem? = null
            var pickedTanda: String? = null
            var nextUp: List<DjPickInfo> = emptyList()
            djMutex.withLock {
                if (dj !== activeDj || djGeneration.get() != gen) return@withLock
                activeDj.skipTanda()
                val t = withContext(Dispatchers.IO) { runCatching { activeDj.next() }.getOrNull() } ?: return@withLock
                if (dj !== activeDj || djGeneration.get() != gen) return@withLock
                pickedTanda = activeDj.tandaOf(t.id) ?: activeDj.tanda
                pickedTanda?.let { djTandas[t.id] = it }
                pickedSong = t.toSong()
                nextUp = withContext(Dispatchers.IO) {
                    runCatching { activeDj.prepareNextUp(DJ_NEXT_UP_COUNT) }.getOrDefault(emptyList())
                }
            }
            val song = pickedSong ?: return@launch
            if (dj !== activeDj || djGeneration.get() != gen) return@launch
            val base = songQueue.take((currentIndex + 1).coerceIn(0, songQueue.size)).toMutableList()
            val newIndex = base.size
            base.add(song)
            nextUp.forEach { info -> info.tanda?.let { djTandas[info.track.id] = it } }
            base.addAll(nextUp.map { it.track.toSong() })
            songQueue = base
            _djNextUp.value = nextUp
            _djTanda.value = pickedTanda
            skipTo(newIndex)
        }
    }

    fun setDjMood(mood: DjMood) {
        _djMood.value = mood
        scope.launch { prefs.setDjMood(mood) }
        val activeDj = dj ?: return
        val gen = djGeneration.get()
        scope.launch {
            val nextUp = djMutex.withLock {
                if (dj !== activeDj || djGeneration.get() != gen) return@withLock null
                activeDj.setMood(mood)
                withContext(Dispatchers.IO) {
                    runCatching { activeDj.prepareNextUp(DJ_NEXT_UP_COUNT) }.getOrDefault(emptyList())
                }
            } ?: return@launch
            if (dj === activeDj && djGeneration.get() == gen) syncDjTail(nextUp)
        }
    }

    fun setDjMode(mode: DjMode) {
        _djMode.value = mode
        scope.launch { prefs.setDjMode(mode) }
        val activeDj = dj ?: return
        if (activeDj.mode == mode) return
        cancelCrossfade()
        _source.value = Source("DJ", if (mode == DjMode.Adhd) "DJ rápido" else "Mezcla para ti")
        val gen = djGeneration.get()
        scope.launch {
            val cur = songQueue.getOrNull(currentIndex)
            val dur = _state.value.durationMs
            val fadeMs = DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(fadeSeconds, ramSaverEnabled)
            val seg = djMutex.withLock {
                if (dj !== activeDj || djGeneration.get() != gen) return@withLock null
                activeDj.setMode(mode)
                if (mode == DjMode.Adhd && cur != null && dur > 0L && fullOnceTrackId != cur.id) {
                    activeDj.adhdSegment(cur.toTrack(), dur, fadeMs, sectionsOf(cur.id), analysisCache.get(cur.id))
                } else null
            }
            if (dj !== activeDj || djGeneration.get() != gen) return@launch
            if (mode == DjMode.Adhd) {
                val pos = _positionMs.value
                val liveSeg = seg?.let { if (pos > it.startMs) it.startingAt(pos) else it }
                _adhdSegment.value = liveSeg
                if (liveSeg != null && pos < liveSeg.startMs) {
                    currentTrackStartOffsetMs = liveSeg.startMs
                    engine.seekTo(liveSeg.startMs)
                }
            } else {
                _adhdSegment.value = null
                _djFullOnce.value = false
                fullOnceTrackId = null
                finishedAdhdTrackKey = null
            }
        }
    }

    fun reportDjReaction(trackId: String, reaction: DjReaction) {
        val activeDj = dj ?: return
        scope.launch(Dispatchers.IO) {
            djMutex.withLock {
                if (dj === activeDj) runCatching { activeDj.react(trackId, reaction) }
            }
        }
    }

    override fun skipTo(index: Int) {
        if (index !in songQueue.indices) return
        recordPlayProgress(completed = false)
        currentIndex = index
        val target = songQueue[index]
        manualQueueIds.remove(target.id)
        val activeDj = dj
        if (activeDj != null) {
            val stagedIdxSync = _djNextUp.value.indexOfFirst { it.track.id == target.id }
            if (stagedIdxSync >= 0) {
                _djNextUp.value = _djNextUp.value.drop(stagedIdxSync + 1)
            }
            val gen = djGeneration.get()
            scope.launch {
                val nextUp = djMutex.withLock {
                    if (dj !== activeDj || djGeneration.get() != gen) return@withLock null
                    val stagedIdx = activeDj.nextUp().indexOfFirst { it.track.id == target.id }
                    if (stagedIdx >= 0) {
                        repeat(stagedIdx + 1) {
                            val picked = runCatching { activeDj.next() }.getOrNull()
                            if (picked != null) {
                                (activeDj.tandaOf(picked.id) ?: activeDj.tanda)?.let { djTandas[picked.id] = it }
                            }
                        }
                    }
                    withContext(Dispatchers.IO) {
                        runCatching { activeDj.prepareNextUp(DJ_NEXT_UP_COUNT) }.getOrDefault(emptyList())
                    }
                } ?: return@launch
                if (dj === activeDj && djGeneration.get() == gen) {
                    (activeDj.tandaOf(target.id) ?: djTandas[target.id])?.let {
                        djTandas[target.id] = it
                        if (_state.value.id == target.id) _djTanda.value = it
                    }
                    syncDjTail(nextUp)
                }
            }
        }
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
        val removed = songQueue[index]
        if (!removingCurrent && index > currentIndex && dj != null && _djNextUp.value.any { it.track.id == removed.id }) {
            vetoDjNextUp(removed.id)
            return
        }
        songQueue.removeAt(index)
        manualQueueIds.remove(removed.id)
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
        manualQueueIds.clear()
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

    /** El oído percibe la intensidad en escala logarítmica: potencia 1,5. */
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
            val order = Shuffle.order(
                listed.map { Shuffle.Item(it.id, it.artists.joinToString { a -> a.name }, ageOf[it.id]) },
                null,
                liked,
                heardAt,
                played = playedIds,
                after = current.artists.joinToString { it.name },
            )
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
    fun cycleLoop() = setLoop(when (_loopMode.value) {
        LoopMode.Off -> LoopMode.All
        LoopMode.All -> LoopMode.One
        LoopMode.One -> LoopMode.Off
    })

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
            val nextSong = songQueue[currentIndex]
            manualQueueIds.remove(nextSong.id)
            val activeDj = dj
            if (activeDj != null) {
                val stagedIdxSync = _djNextUp.value.indexOfFirst { it.track.id == nextSong.id }
                if (stagedIdxSync >= 0) {
                    _djNextUp.value = _djNextUp.value.drop(stagedIdxSync + 1)
                }
                val gen = djGeneration.get()
                scope.launch {
                    djMutex.withLock {
                        if (dj === activeDj && djGeneration.get() == gen) {
                            val pos = activeDj.nextUp().indexOfFirst { it.track.id == nextSong.id }
                            if (pos >= 0) {
                                repeat(pos + 1) {
                                    val picked = runCatching { activeDj.next() }.getOrNull()
                                    if (picked != null) {
                                        (activeDj.tandaOf(picked.id) ?: activeDj.tanda)?.let { djTandas[picked.id] = it }
                                    }
                                }
                            }
                            (activeDj.tandaOf(nextSong.id) ?: djTandas[nextSong.id])?.let {
                                djTandas[nextSong.id] = it
                                if (_state.value.id == nextSong.id) _djTanda.value = it
                            }
                        }
                    }
                    maybeReplenishQueue()
                }
            }
            syncQueueFlow()
            startCurrentTrack()
            if (activeDj == null) maybeReplenishQueue()
        } else {
            val gen = djGeneration.get()
            scope.launch {
                val activeDj = dj
                if (activeDj != null) {
                    var pickedSong: SongItem? = null
                    var pickedTanda: String? = null
                    var nextUp: List<DjPickInfo> = emptyList()
                    djMutex.withLock {
                        if (dj !== activeDj || djGeneration.get() != gen) return@withLock
                        val nextTrack = withContext(Dispatchers.IO) { runCatching { activeDj.next() }.getOrNull() }
                        if (nextTrack != null && dj === activeDj && djGeneration.get() == gen) {
                            pickedTanda = activeDj.tandaOf(nextTrack.id) ?: activeDj.tanda
                            pickedTanda?.let { djTandas[nextTrack.id] = it }
                            pickedSong = nextTrack.toSong()
                            nextUp = withContext(Dispatchers.IO) {
                                runCatching { activeDj.prepareNextUp(DJ_NEXT_UP_COUNT) }.getOrDefault(emptyList())
                            }
                        }
                    }
                    val song = pickedSong
                    if (song != null && dj === activeDj && djGeneration.get() == gen) {
                        songQueue.add(song)
                        currentIndex++
                        _djTanda.value = pickedTanda
                        syncDjTail(nextUp)
                        startCurrentTrack()
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
        val activeDj = dj
        if (activeDj != null) {
            val split = (currentIndex + 1).coerceIn(0, songQueue.size)
            val djUpcoming = songQueue.drop(split).count { it.id !in manualQueueIds }
            if (djUpcoming >= DJ_NEXT_UP_COUNT) return
            if (djReplenishJob?.isActive == true) return
            val gen = djGeneration.get()
            djReplenishJob = scope.launch {
                val nextUp = djMutex.withLock {
                    if (dj !== activeDj || djGeneration.get() != gen) return@withLock null
                    withContext(Dispatchers.IO) {
                        runCatching { activeDj.prepareNextUp(DJ_NEXT_UP_COUNT) }.getOrDefault(emptyList())
                    }
                } ?: return@launch
                if (dj === activeDj && djGeneration.get() == gen) syncDjTail(nextUp)
            }
        } else if (radio != null) {
            val remaining = songQueue.size - 1 - currentIndex
            if (remaining > 2) return
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
        engine.setTempo(1f)
        _currentQuality.value = null
        finishedAdhdTrackKey = null
        fullOnceTrackId = null
        _djFullOnce.value = false
        resolvingTrack = true
        val durGuess = (song.duration ?: 0).takeIf { it > 0 }?.times(1000L) ?: 0L
        val activeDj = dj
        val fadeMs = DesktopTransitionPlanner.effectiveAdhdCrossfadeMs(fadeSeconds, ramSaverEnabled)
        val cachedAudio = analysisCache.get(song.id)
        val initialAdhdSeg = if (activeDj?.mode == DjMode.Adhd && durGuess > 0L) {
            activeDj.adhdSegment(song.toTrack(), durGuess, fadeMs, sectionsOf(song.id), cachedAudio)
        } else null
        _adhdSegment.value = initialAdhdSeg
        _djTanda.value = if (activeDj != null) djTandas[song.id] else null
        val startPosMs = if (initialPositionMs > 0L) initialPositionMs else initialAdhdSeg?.startMs ?: 0L
        currentTrackStartOffsetMs = startPosMs

        val resolveAdhdStart: ((Long) -> Long)? = if (initialPositionMs == 0L && initialAdhdSeg == null && activeDj?.mode == DjMode.Adhd) {
            { actualDurMs ->
                val targetFile = engine.cachedAudioFile()
                if (sectionsOf(song.id).size < 3 && analysisCache.get(song.id) == null && targetFile != null) {
                    kotlinx.coroutines.runBlocking { engine.analyzeSync(targetFile, song.id) }
                }
                val seg = kotlinx.coroutines.runBlocking {
                    djMutex.withLock {
                        if (dj === activeDj && activeDj.mode == DjMode.Adhd && fullOnceTrackId != song.id) {
                            activeDj.adhdSegment(song.toTrack(), actualDurMs, fadeMs, sectionsOf(song.id), analysisCache.get(song.id))
                        } else null
                    }
                }
                if (_state.value.id == song.id && fullOnceTrackId != song.id) {
                    _adhdSegment.value = seg
                    val s = seg?.startMs ?: 0L
                    currentTrackStartOffsetMs = s
                    s
                } else 0L
            }
        } else null

        resolveJob = scope.launch {
            val dataSaver = prefs.dataSaver.first()
            val qualityMode = prefs.quality.first()
            val tier = if (dataSaver && qualityMode != QualityMode.Max) Tier.Low else Tier.High

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
                engine.loadLocalFile(
                    file = local,
                    durationMs = durGuess,
                    playWhenReady = playWhenReady,
                    initialPositionMs = startPosMs,
                    trackId = song.id,
                    resolveInitialPositionMs = resolveAdhdStart,
                )
                return@launch
            }

            val resolved = withContext(Dispatchers.IO) {
                InnerTubeXPlayer.resolve(song.id, tier)
            }
            resolved.onSuccess { stream ->
                StreamInfo.put(song.id, stream.quality)
                _currentQuality.value = stream.quality
                val gain = safeLoudnessGain(stream.loudnessDb)
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
                    trackId = song.id,
                    resolveInitialPositionMs = resolveAdhdStart,
                )
            }.onFailure { err ->
                resolvingTrack = false
                Timber.e(err, "No se pudo resolver stream para ${song.id}")
                _state.value = _state.value.copy(playing = false, buffering = false, error = err.message)
            }
        }
    }

    private fun safeLoudnessGain(loudnessDb: Double?): Float {
        val db = loudnessDb?.takeIf { it.isFinite() } ?: return 1f
        val linear = 10f.pow(-db.toFloat() / 20f)
        return if (linear.isFinite()) min(1f, linear).coerceIn(0.25f, 1f) else 1f
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
        val ready = lyricsStore.peek(song.id)
        _lyrics.value = ready
        _lyricsLoading.value = ready == null
        lyricsJob = scope.launch(Dispatchers.IO) {
            val artist = song.artists.joinToString { it.name }
            if ((song.duration ?: 0) <= 0) {
                withTimeoutOrNull(3000) { _state.first { it.id != song.id || it.durationMs > 0 } }
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
            fun rank(value: Lyrics?) = when (value) {
                is Lyrics.Synced -> if (value.wordByWord) 3 else 2
                is Lyrics.Plain -> 1
                else -> 0
            }
            try {
                var fetched = lyricsStore.get(query)
                if (_state.value.id == song.id) {
                    _lyrics.value = fetched.lyrics
                    _lyricsLoading.value = false
                }
                prefetchNextLyrics(song)
                for (wait in listOf(20_000L, 90_000L)) {
                    if (fetched.final) break
                    delay(wait)
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
        val song = songQueue.firstOrNull { it.id == id }?.toTrack()
        scope.launch(Dispatchers.IO) {
            runCatching {
                var kind: io.github.iaguito22.oto.dj.Kind? = null
                if (activeDj != null) {
                    djMutex.withLock {
                        if (dj === activeDj) {
                            kind = activeDj.picks.lastOrNull { p -> p.track.id == id }?.kind
                            if (kind != null) {
                                activeDj.heard(id, listenedMs, dur.coerceAtLeast(listenedMs))
                            }
                        }
                    }
                }
                if (song != null && listenedMs >= 2000L) {
                    plays.add(Play(id, song.title, song.artist, song.artistId, System.currentTimeMillis(), listenedMs, dur.coerceAtLeast(listenedMs), kind))
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
        const val DJ_NEXT_UP_COUNT = 3
    }
}
