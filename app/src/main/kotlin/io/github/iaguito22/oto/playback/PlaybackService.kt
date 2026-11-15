package io.github.iaguito22.oto.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.DEFAULT_SMART_MIX
import io.github.iaguito22.oto.MainActivity
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.QualityMode
import io.github.iaguito22.oto.dj.AdhdPlanner
import io.github.iaguito22.oto.dj.AdhdSegment
import io.github.iaguito22.oto.dj.AudioAnalysisCache
import io.github.iaguito22.oto.dj.Dj
import io.github.iaguito22.oto.dj.DjBridgeStyle
import io.github.iaguito22.oto.dj.DjGenre
import io.github.iaguito22.oto.dj.DjMode
import io.github.iaguito22.oto.dj.DjMood
import io.github.iaguito22.oto.dj.DjPickInfo
import io.github.iaguito22.oto.dj.DjReaction
import io.github.iaguito22.oto.dj.DjTransitionPlan
import io.github.iaguito22.oto.dj.DjTransitionPlanner
import io.github.iaguito22.oto.dj.Kind
import io.github.iaguito22.oto.dj.Memory
import io.github.iaguito22.oto.dj.Play
import io.github.iaguito22.oto.dj.Profile
import io.github.iaguito22.oto.dj.SongSections
import io.github.iaguito22.oto.dj.Track
import io.github.iaguito22.oto.importer.songKey
import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.lyrics.LyricsQuery
import io.github.iaguito22.oto.lyrics.LyricsSource
import io.github.iaguito22.oto.traced
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

/**
 * Reproducción en segundo plano. Media3 da con esto la notificación, los controles de la pantalla de bloqueo
 * y los de los auriculares; aquí solo vive lo propio: resolver el stream al abrirlo, reintentar y la radio.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val cache by lazy { (application as OtoApp).audioCache }

    private val urls = StreamUrlCache()
    private val bandwidth by lazy { DefaultBandwidthMeter.getSingletonInstance(this) }
    // La calidad de cada canción que suena o va a sonar: se decide al empezar a cargarla y ya no cambia (ver [Tier]).
    private val tiers = ConcurrentHashMap<String, Tier>()
    @Volatile private var mode = QualityMode.Auto
    private var saver = false
    // La que suena, para [WholeSongLoadControl] (que pregunta desde el hilo de reproducción).
    @Volatile private var playingIndex = 0
    private var stallJob: Job? = null
    private var prefetchJob: Job? = null
    @Volatile private var writer: CacheWriter? = null
    private var prefetched = emptyList<String>()
    private val loudness = ConcurrentHashMap<String, Double>()
    private val tracking = ConcurrentHashMap<String, String>()
    private var historyJob: Job? = null
    private var historyCounted = false
    private val retries = HashMap<String, Int>()
    private var retryJob: Job? = null

    private var radio: Radio? = null
    private var radioJob: Job? = null
    // Lo que suena es una lista (un álbum, una tuya…) y no la radio de una canción ni el DJ.
    private var list = false
    private var preserveTransferredQueue = false
    // Lo que sigue a una lista acabada (ver [Autoplay]): lo que añade va marcado (ver [markAuto]).
    private var auto: Autoplay? = null
    // Lo último que sonó (también en sesiones anteriores, desde el historial): la radio no lo vuelve a poner.
    // Por id y por título + artista, porque la misma canción suele estar como canción y como vídeo.
    private val recent = ArrayDeque<String>()

    private val app by lazy { application as OtoApp }
    private val library by lazy { Library(prefs, this, djGenres = ::djGenreNames) }

    /** Tus géneros (de lo ya guardado, sin red) para el DJ de Android Auto. */
    private suspend fun djGenreNames(): List<String> = withContext(Dispatchers.Default) {
        val p = djProfile ?: Profile(prefs.playlists.first(), prefs.liked.first(), prefs.history.first(), app.plays.all(), System.currentTimeMillis())
        app.genres.ready().top(p).map { it.name }
    }
    /** La última búsqueda desde Android Auto: la pide en dos pasos (cuántos hay, luego cuáles). */
    private var found: Pair<String, List<MediaItem>> = "" to emptyList()
    private val prefs by lazy { app.prefs }
    private var liked = emptySet<String>()

    private var dj: Dj? = null
    private var djJob: Job? = null
    private val djTracks = HashMap<String, Track>()
    private val adhdSegments = HashMap<String, AdhdSegment>()
    private val adhdUsedAudio = HashSet<String>()
    // Las que ya se trocearon con su letra delante (aunque no sirviera): no se rehace en cada vistazo.
    private val adhdUsedLyrics = HashSet<String>()
    // Lo que ya ha sonado en esta sesión del DJ.
    private val djSessionPlayed = HashSet<String>()
    private var adhdIntroAppliedFor: Pair<String, Int>? = null
    private var adhdMonitorJob: Job? = null
    private var duckJob: Job? = null
    private val analysisCache by lazy { AudioAnalysisCache() }
    private val audioAnalyzer by lazy { AndroidAudioAnalyzer(cache, app.downloads, analysisCache) }
    private val sliceDecoder by lazy {
        AndroidAudioSliceDecoder(
            audioCache = cache,
            downloads = app.downloads,
            dataSourceFactory = { dataSourceFactory().createDataSource() },
            allowNetworkSlice = { !(saver && connectivity.isActiveNetworkMetered) },
        )
    }
    private val liveLock = Any()
    private var liveTrackId: String? = null
    private var liveTrackDurMs = 0L
    private var liveSampleRate = 0
    private val liveBass = ArrayList<Float>(1024)
    private val liveFull = ArrayList<Float>(1024)
    @Volatile private var djMood = DjMood.Auto
    @Volatile private var djGenreName: String? = null
    /** Tus gustos tal como los vio el DJ al empezar: para saber tus géneros sin rehacerlos. */
    private var djProfile: Profile? = null
    @Volatile private var djMode = DjMode.Normal
    @Volatile private var smartMix = DEFAULT_SMART_MIX
    // Tiempo real reproducido en la pista actual (descuenta pausas e intros saltadas en modo ADHD).
    private var playAccumMs = 0L
    private var playStartedAtMs = 0L
    private var currentTrackAlreadyLiked = false
    // Lo que dura cada una, para saber qué parte se oyó al pasar a otra.
    private val durations = HashMap<String, Long>()

    private val incomingBass = BassSwapAudioProcessor()
    // Calla en el PCM lo que el relevo calla con el volumen, para que la grabación de pantalla oiga la mezcla.
    private val captureGate = CaptureGate()
    private val outgoingBass = BassSwapAudioProcessor()
    private val outgoingEcho = EchoOutFilterSweepAudioProcessor()
    private lateinit var crossfade: Crossfade
    @Volatile private var fadeMs = 0L
    @Volatile private var isMixing = false
    // La próxima mezcla tal como la ve [Crossfade] (inicio, cambio, estilo, si está lista) y para qué pareja.
    @Volatile private var mixUi: MixUi? = null
    private val recentBridgeStyles = ArrayDeque<DjBridgeStyle>(4)
    private var committedMixPair: Pair<String, String>? = null
    // El volumen es el de la normalización de cada canción ([gain]) por lo que lleva subido en un fundido ([fadeIn]).
    private var gain = 1f
    private var fadeIn = 1f

    override fun onCreate() {
        super.onCreate()
        (application as OtoApp).initInnerTube()
        val sources = DefaultMediaSourceFactory(dataSourceFactory()).setLoadErrorHandlingPolicy(PatientErrorPolicy(::isConnectionLost))
        player = ExoPlayer.Builder(this, renderersFactory())
            .setMediaSourceFactory(sources)
            .setLoadControl(WholeSongLoadControl({ mode }, { playingIndex }))
            .setBandwidthMeter(bandwidth)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Sin la precarga de ExoPlayer: la siguiente ya la baja entera [WholeSongLoadControl] en cuanto esta termina de
        // bajar, y la precarga añadía 30 s de la de después, que casi nunca se oyen.
        player.addListener(listener)
        crossfade = Crossfade(
            context = this,
            player = player,
            sources = sources,
            scope = scope,
            length = { fadeMs },
            gain = { gain },
            onFadeIn = { fadeIn = it; applyVolume() },
            smartMix = { smartMix },
            transitionPlan = ::transitionPlanFor,
            nextStartPosition = ::nextStartPositionFor,
            incomingProcessor = incomingBass,
            outgoingProcessor = outgoingBass,
            outgoingEchoProcessor = outgoingEcho,
            djMixPlan = ::djMixPlanFor,
            sessionGate = captureGate,
            onMixingChanged = { active ->
                isMixing = active
                if (active) {
                    val cur = player.currentMediaItem
                    val next = if (player.hasNextMediaItem()) player.getMediaItemAt(player.nextMediaItemIndex) else null
                    val pair = if (cur != null && next != null) cur.mediaId to next.mediaId else null
                    if (pair != null && pair != committedMixPair) {
                        val dur = player.duration.takeIf { it > 0L } ?: cur?.mediaMetadata?.durationMs ?: 0L
                        val style = mixUi?.takeIf { it.pair == null || it.pair == pair }?.style ?: if (cur != null && next != null && dur > 0L) {
                            val reqFade = if (fadeMs > 0L) fadeMs else effectiveAdhdFadeMs()
                            djMixPlanFor(cur, next, dur, reqFade)?.bridgeSpec?.style
                        } else null
                        if (style != null) {
                            committedMixPair = pair
                            recentBridgeStyles.addLast(style)
                            while (recentBridgeStyles.size > 4) recentBridgeStyles.removeFirst()
                        }
                    }
                }
                publishDjSessionExtras()
            },
            onMixUiChanged = { ui ->
                mixUi = ui
                publishDjSessionExtras()
            },
            sliceDecoder = sliceDecoder,
            cacheKeyFor = { item -> item.localConfiguration?.customCacheKey ?: keyOf(item.mediaId) },
            gainFor = ::gainForTrack,
            bridgeCacheDir = File(cacheDir, "dj_bridges"),
            onPrepareIncomingAnalysis = { id, key ->
                scope.launch(Dispatchers.Default) {
                    runCatching { audioAnalyzer.analyzeIfCached(id, key) }
                }
            },
        )
        crossfade.start()
        // La que suena y la siguiente se terminan de bajar mientras suena esta: en cuanto están enteras, se analizan
        // enteras (lo de la precarga era solo el principio y daba BPM dudosos).
        scope.launch {
            while (isActive) {
                delay(ANALYSIS_WATCH_MS)
                if (!player.isPlaying) continue
                val at = player.currentMediaItemIndex
                for (i in at..minOf(at + 1, player.mediaItemCount - 1)) {
                    val id = player.getMediaItemAt(i).mediaId
                    if (analysisCache.get(id)?.coversWholeSong == true) continue
                    val key = keyOf(id)
                    launch(Dispatchers.Default) {
                        val updated = runCatching { audioAnalyzer.analyzeIfCached(id, key) }.getOrNull()
                        if (updated?.coversWholeSong == true) {
                            withContext(Dispatchers.Main) {
                                publishDjSessionExtras()
                            }
                        }
                    }
                }
            }
        }
        AudioLevels.onChunk = { sr, bass, full -> recordLiveAudioChunk(sr, bass, full) }

        session = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        scope.launch {
            val heard = withContext(Dispatchers.IO) { prefs.history.first() }
            heard.asReversed().forEach { remember(it.id, sameSong(it.title, it.artist)) }
        }

        scope.launch { prefs.quality.collect { mode = it } }
        scope.launch { prefs.dataSaver.collect { saver = it } }
        // La mezcla inteligente, si está activada, con su duración; si no, el fundido normal.
        scope.launch {
            prefs.mixSeconds.collect {
                fadeMs = it * 1000L
                publishDjSessionExtras()
            }
        }
        scope.launch {
            prefs.smartMix.collect {
                smartMix = it
                if (!it) mixUi = null
                publishDjSessionExtras()
            }
        }
        scope.launch { prefs.djMood.collect { djMood = it; dj?.setMood(it) } }
        scope.launch { prefs.djGenre.collect { djGenreName = it } }
        scope.launch { prefs.djMode.collect { updateDjMode(it) } }

        // El corazón de la notificación sigue a Prefs: lo cambie quien lo cambie, aquí se ve.
        scope.launch {
            prefs.likedIds.collect { newLiked ->
                val current = player.currentMediaItem
                if (current != null && dj != null && current.isDjItem && !currentTrackAlreadyLiked && current.mediaId in newLiked) {
                    currentTrackAlreadyLiked = true
                    dj?.react(current.mediaId, DjReaction.Like)
                }
                liked = newLiked
                showLike()
            }
        }

        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Si suena, sigue sonando con la app cerrada; si no, no queda nada vivo.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        AudioLevels.onChunk = null
        finalizeLiveAudioAnalysis(null, 0L)
        connectivity.unregisterNetworkCallback(networkCallback)
        duckJob?.cancel()
        scope.cancel()
        writer?.cancel()
        crossfade.release()
        session.release()
        player.release()
        super.onDestroy()
    }

    // --- Streams ---

    /** El sink de siempre, con [AudioLevels] delante para que la gota sepa cuándo pega el bombo. */
    private fun renderersFactory() = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
            val base = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(incomingBass))
                .build()
            // La puerta va DENTRO de la toma de AudioLevels: el relevo sigue midiendo lo que suena callado.
            return AudioLevels.Sink(CaptureGateSink(base, captureGate))
        }

        // El renderer de audio de siempre, que además dice a la puerta de qué canción es cada tramo que empieza a leer
        // (su nivel de normalización va en el PCM desde que sale su primer audio).
        override fun buildAudioRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: AudioSink,
            eventHandler: Handler,
            eventListener: AudioRendererEventListener,
            out: ArrayList<Renderer>,
        ) {
            val from = out.size
            super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
            val at = (from until out.size).firstOrNull { out[it] is MediaCodecAudioRenderer } ?: return
            out[at] = object : MediaCodecAudioRenderer(
                context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink,
            ) {
                override fun onStreamChanged(formats: Array<out Format>, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
                    captureGate.onStreamRead(offsetUs, mediaPeriodId.periodUid)
                    super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
                }
            }
        }
    }

    /** Nivel de cada canción de la cola para la puerta PCM (lo que antes iba en `player.volume`). */
    private fun publishLevels() {
        val tl = player.currentTimeline
        if (tl.isEmpty) return
        val window = Timeline.Window()
        val period = Timeline.Period()
        val ids = HashMap<Any, String>()
        val levels = HashMap<String, Float>()
        val cur = player.currentMediaItemIndex.coerceAtLeast(0)
        // Las de alrededor de la que suena (una lista de miles no hace falta entera).
        for (w in (cur - 2).coerceAtLeast(0)..(cur + 40).coerceAtMost(tl.windowCount - 1)) {
            tl.getWindow(w, window)
            val id = window.mediaItem.mediaId
            levels[id] = gainForTrack(id)
            for (p in window.firstPeriodIndex..window.lastPeriodIndex) {
                ids[tl.getPeriod(p, period, true).uid ?: continue] = id
            }
        }
        captureGate.periodIds = ids
        captureGate.levels = levels
    }

    private fun dataSourceFactory(): DataSource.Factory {
        val network = OkHttpDataSource.Factory(AUDIO_HTTP)
        val cached = CacheDataSource.Factory()
            .setCache(cache)
            // A trozos: de una vez, YouTube la frena a ~33 KB/s (ver [ChunkedDataSource]).
            .setUpstreamDataSourceFactory { ChunkedDataSource(network.createDataSource()) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Lo descargado, antes que nada: suena sin red y sin pedir la URL.
        return OfflineFirstDataSource.Factory(app.downloads, ResolvingDataSource.Factory(cached, ::resolve))
    }

    /**
     * Se llama en el hilo de carga cada vez que ExoPlayer abre un trozo del audio. La primera vez que se abre una
     * canción se decide su calidad ([Tier]); a partir de ahí, cada trozo (también al volver a pedir la URL porque
     * caducó o porque cambió la red) es de ese mismo formato.
     */
    private fun resolve(spec: DataSpec): DataSpec {
        val key = spec.key ?: return spec
        val id = Tier.idOf(key)
        val tier = Tier.forcedBy(key)?.also { tiers[id] = it }
            ?: tiers.getOrPut(id) { chooseTier(conditions(), isWhole(Tier.High.key(id))).also { Timber.d("calidad %s: %s", id, it) } }
        val cacheKey = tier.key(id)
        val keyed = if (cacheKey == key) spec else spec.buildUpon().setKey(cacheKey).build()

        // Entera en la caché: no hace falta ni pedir la URL. Si solo hay un trozo, sí (el resto viene de la red).
        val metadata = cache.getContentMetadata(cacheKey)
        val length = ContentMetadata.getContentLength(metadata)
        Quality.decode(metadata.get(QUALITY_KEY, null as String?))?.let { StreamInfo.put(id, it) }
        if (length > 0 && cache.isCached(cacheKey, spec.position, length - spec.position)) return keyed

        urls[cacheKey]?.let { return keyed.withResolvedStream(it) }

        val generation = urls.generation(cacheKey)
        val started = System.nanoTime()
        val stream = runBlocking(Dispatchers.IO) { InnerTubeXPlayer.resolve(id, tier) }
            .getOrElse { throw ResolveException(id, it) }
        Timber.d("stream %s en %d ms (%s, itag %d)", cacheKey, (System.nanoTime() - started) / 1_000_000, stream.client, stream.itag)

        // Lo guardado es de otro formato (otro cliente dio otro itag): esos bytes ya no casan con los nuevos. Fuera, y
        // un error para que ExoPlayer vuelva a abrirla desde la cabecera, en el mismo punto.
        val storedItag = metadata.get(ITAG_KEY, 0L)
        if (storedItag != 0L && storedItag != stream.itag.toLong() && cache.getCachedBytes(cacheKey, 0, C.LENGTH_UNSET.toLong()) > 0) {
            Timber.w("%s: itag %d en vez de %d, se vuelve a bajar", cacheKey, stream.itag, storedItag)
            runCatching { cache.removeResource(cacheKey) }
            throw ResolveException(id, IllegalStateException("cambió el formato"))
        }

        StreamInfo.put(id, stream.quality)
        stream.tracking?.let { tracking[id] = it }
        runCatching {
            cache.applyContentMetadataMutations(
                cacheKey,
                ContentMetadataMutations().set(QUALITY_KEY, stream.quality.encode()).set(ITAG_KEY, stream.itag.toLong()),
            )
        }
        stream.loudnessDb?.let {
            loudness[id] = it
            scope.launch { applyLoudness() }
        }
        val resolved = CachedStreamUrl(
            url = stream.url,
            requestHeaders = stream.headers,
            clientName = stream.client,
            requireBoundedRange = stream.requireBoundedRange,
            rangeChunkSizeBytes = stream.rangeChunkSizeBytes,
            useRangeChunks = stream.useRangeChunks,
        )
        urls.put(
            mediaId = cacheKey,
            url = resolved.url,
            requestHeaders = resolved.requestHeaders,
            clientName = resolved.clientName,
            expiresInSeconds = stream.expiresInSeconds,
            requireBoundedRange = resolved.requireBoundedRange,
            rangeChunkSizeBytes = resolved.rangeChunkSizeBytes,
            useRangeChunks = resolved.useRangeChunks,
            expectedGeneration = generation,
        )
        return keyed.withResolvedStream(resolved)
    }

    /**
     * El principio de las [AHEAD] siguientes (y su URL), ya en la caché: así pasar una o dos seguidas no se queda
     * cargando. ExoPlayer solo baja la siguiente, y no hasta acabar con la que suena. Con ahorro de datos y datos
     * móviles, solo la siguiente.
     */
    private fun prefetch() {
        if (!player.isPlaying) return
        val at = player.currentMediaItemIndex
        val ahead = if (saver && connectivity.isActiveNetworkMetered) 1 else AHEAD
        val ids = (at + 1..min(at + ahead, player.mediaItemCount - 1)).map { player.getMediaItemAt(it).mediaId }
        if (ids == prefetched && prefetchJob?.isActive == true) return
        prefetched = ids
        prefetchJob?.cancel()
        writer?.cancel()
        if (ids.isEmpty()) return
        prefetchJob = scope.launch(Dispatchers.IO) {
            // Primero, que arranque la que suena.
            delay(PREFETCH_DELAY_MS)
            for (id in ids) {
                if (!isActive) return@launch
                if (!app.downloads.has(id)) {
                    runCatching {
                        val spec = resolve(DataSpec.Builder().setUri(id).setKey(id).setLength(HEAD_BYTES).build())
                        // Sin URL nueva: ya estaba entera en la caché.
                        if (spec.uri.toString() == id) return@runCatching
                        CacheWriter(headSource.createDataSource(), spec, null, null).also { writer = it }.cache()
                    }.onFailure { Timber.w(it, "precarga %s", id) }
                }
                if (isActive && analysisCache.get(id)?.coversWholeSong != true) {
                    runCatching { audioAnalyzer.analyzeIfCached(id, keyOf(id)) }
                }
            }
            // Con DJ / mezcla inteligente, bajamos entera la inmediatamente siguiente (`at + 1`) antes del puente para
            // que llegue ya analizada entera (BPM fiable, rejilla completa y barras reales antes de renderizar el WAV).
            val nextId = ids.firstOrNull()
            if (isActive && nextId != null && (dj != null || smartMix) && !(saver && connectivity.isActiveNetworkMetered)) {
                if (!app.downloads.has(nextId) && analysisCache.get(nextId)?.coversWholeSong != true) {
                    runCatching {
                        val fullSpec = resolve(DataSpec.Builder().setUri(nextId).setKey(nextId).setLength(C.LENGTH_UNSET.toLong()).build())
                        if (fullSpec.uri.toString() != nextId) {
                            CacheWriter(headSource.createDataSource(), fullSpec, null, null).also { writer = it }.cache()
                        }
                    }.onFailure { Timber.w(it, "precarga completa %s", nextId) }
                }
                if (isActive && analysisCache.get(nextId)?.coversWholeSong != true) {
                    val updated = runCatching { audioAnalyzer.analyzeIfCached(nextId, keyOf(nextId)) }.getOrNull()
                    if (updated?.coversWholeSong == true) {
                        withContext(Dispatchers.Main) { publishDjSessionExtras() }
                    }
                }
            }
            writer = null
        }
    }

    // Lo mismo que suena, sin resolver (eso ya lo hace [prefetch]) ni lo descargado: solo para escribir en la caché.
    private val headSource by lazy {
        CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { ChunkedDataSource(OkHttpDataSource.Factory(AUDIO_HTTP).createDataSource()) }
    }

    private fun isWhole(cacheKey: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        return length > 0 && cache.isCached(cacheKey, 0, length)
    }

    /** Lo que cuenta para elegir calidad, leído en el momento (en el hilo de carga: aquí se puede esperar a Prefs). */
    private fun conditions() = Conditions(
        mode = runBlocking { prefs.quality.first() },
        dataSaver = runBlocking { prefs.dataSaver.first() },
        metered = connectivity.isActiveNetworkMetered,
        bandwidthBps = bandwidth.bitrateEstimate,
    )

    /** La clave con la que está (o estará) en la caché lo que suena ahora de esta canción. */
    private fun keyOf(id: String) = (tiers[id] ?: Tier.High).key(id)

    /**
     * En Auto, si una canción en alta lleva [STALL_MS] sin empezar a sonar con la URL ya pedida y con red (son los bytes
     * lo que no llega), se pasa a baja antes de que empiece: nunca a mitad.
     */
    private fun watchStart() {
        val id = player.currentMediaItem?.mediaId ?: return
        if (stallJob?.isActive == true) return
        stallJob = scope.launch {
            delay(STALL_MS)
            val item = player.currentMediaItem ?: return@launch
            if (item.mediaId != id || mode != QualityMode.Auto || tiers[id] != Tier.High) return@launch
            if (player.playbackState != Player.STATE_BUFFERING || player.currentPosition > STARTED_MS) return@launch
            if (urls[Tier.High.key(id)] == null) return@launch
            // Sin conexión, bajar la calidad no la trae antes: espera y vuelve en alta cuando haya red.
            val net = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
            if (net?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) return@launch
            Timber.d("calidad %s: no arranca en alta, pasa a baja", id)
            MixLog.i("calidad: $id no arranca en alta, se cambia a baja")
            tiers[id] = Tier.Low
            // Otra clave de caché = otra fuente: ExoPlayer la vuelve a preparar (la alta a medias se queda en la caché).
            player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setCustomCacheKey(Tier.Low.key(id)).build())
        }
    }

    /** YouTube normaliza bajando las canciones que suenan por encima de su referencia, nunca subiendo. */
    private fun gainForTrack(id: String?): Float {
        if (id == null) return 1f
        val db = loudness[id]
        return when {
            db != null -> if (db <= 0) 1f else 10f.pow(-db.toFloat() / 20f).coerceIn(MIN_GAIN, 1f)
            else -> {
                val rms = analysisCache.get(id)?.rmsDb
                if (rms != null && rms > TARGET_RMS_DB) {
                    10f.pow(-((rms - TARGET_RMS_DB).toFloat()) / 20f).coerceIn(MIN_GAIN, 1f)
                } else {
                    1f
                }
            }
        }
    }

    private fun applyLoudness() {
        gain = gainForTrack(player.currentMediaItem?.mediaId)
        publishLevels()
        applyVolume()
    }

    private fun recordLiveAudioChunk(sampleRate: Int, bassRms: Float, fullRms: Float) {
        if (sampleRate <= 0 || !bassRms.isFinite() || !fullRms.isFinite()) return
        synchronized(liveLock) {
            if (liveTrackId == null || liveBass.size >= MAX_LIVE_CHUNKS) return
            if (liveSampleRate == 0) liveSampleRate = sampleRate
            if (liveSampleRate == sampleRate) {
                liveBass.add(bassRms)
                liveFull.add(fullRms)
            }
        }
    }

    private fun finalizeLiveAudioAnalysis(nextId: String?, nextDurMs: Long) {
        val prevId: String?
        val prevDur: Long
        val sr: Int
        val bassCopy: FloatArray?
        val fullCopy: FloatArray?
        synchronized(liveLock) {
            prevId = liveTrackId
            prevDur = liveTrackDurMs
            sr = liveSampleRate
            if (prevId != null && prevId != nextId && sr > 0 && liveBass.size >= MIN_LIVE_CHUNKS && analysisCache.get(prevId) == null) {
                bassCopy = liveBass.toFloatArray()
                fullCopy = liveFull.toFloatArray()
            } else {
                bassCopy = null
                fullCopy = null
            }
            if (prevId != nextId) {
                liveTrackId = nextId
                liveTrackDurMs = nextDurMs
                liveSampleRate = 0
                liveBass.clear()
                liveFull.clear()
            } else if (nextDurMs > 0L) {
                liveTrackDurMs = nextDurMs
            }
        }
        if (prevId != null && bassCopy != null && fullCopy != null) {
            val frameMs = AudioLevels.CHUNK_FRAMES * 1000.0 / sr
            scope.launch(Dispatchers.Default) {
                runCatching {
                    analysisCache.analyzeAndPutBassCurve(
                        trackId = prevId,
                        bassPulses = bassCopy,
                        frameMs = frameMs,
                        rmsEnvelope = fullCopy,
                        durationMs = prevDur,
                        persist = true,
                    )
                }
            }
        }
    }

    private fun applyVolume() {
        // La normalización ([gain]) va en el PCM, por canción (ver [CaptureGate.levels]): así llega también a la
        // grabación de pantalla. El volumen solo lleva el fundido.
        player.volume = fadeIn
    }

    // --- Errores ---

    private fun recover(error: PlaybackException) {
        val id = player.currentMediaItem?.mediaId ?: return
        val network = error.isNetwork()
        val attempt = (retries[id] ?: 0) + 1
        retries[id] = attempt
        Timber.w(error, "error %s (%s), intento %d", id, error.errorCodeName, attempt)

        val failedClient = urls.clientName(keyOf(id))
        urls.invalidate(keyOf(id))
        if (!network) {
            // Una URL rechazada suele ser cosa del cliente que la dio o de un cifrado viejo: se cambian los dos.
            failedClient?.let { InnerTubeXPlayer.markClientFailed(id, it) }
            scope.launch(Dispatchers.IO) {
                if (runCatching { InnerTubeXPlayer.refreshAfterStreamRejection() }.getOrDefault(false)) {
                    InnerTubeXPlayer.clearClientFailures()
                }
            }
            if (attempt > MAX_RETRIES) {
                Timber.w("se salta %s", id)
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                }
                return
            }
        }
        // Sin red se espera cada vez más (y [networkCallback] reintenta en cuanto vuelve).
        val wait = if (network) min(30_000L, 2_000L shl (attempt - 1).coerceAtMost(4)) else 500L
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            if (player.currentMediaItem?.mediaId == id && player.playerError != null) player.prepare()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                if (player.playerError?.isNetwork() == true) {
                    retryJob?.cancel()
                    player.prepare()
                }
            }
        }
    }

    // --- Me gusta ---

    /** Un corazón en la notificación, la pantalla de bloqueo y los controles del sistema. */
    private fun showLike() {
        val item = player.currentMediaItem ?: return
        val on = item.mediaId in liked
        session.setMediaButtonPreferences(
            buildList {
                add(
                    CommandButton.Builder(if (on) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                        .setDisplayName(if (on) "Quitar de Me gusta" else "Me gusta")
                        .setSessionCommand(LIKE)
                        .build(),
                )
                // Con el DJ: saltar el ambiente que suena, también desde Android Auto y la notificación.
                if (dj != null && item.isDjItem) add(
                    CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                        .setCustomIconResId(io.github.iaguito22.oto.R.drawable.ic_skip_next)
                        .setDisplayName("Otro ambiente")
                        .setSessionCommand(DJ_SKIP_CMD)
                        .build(),
                )
            },
        )
    }

    private fun toggleLike() {
        val item = player.currentMediaItem ?: return
        scope.launch { prefs.toggleLike(item.toHeard()) }
    }

    // --- Historial de YouTube ---

    /**
     * Con cuenta, lo que suena más de [HEARD_MS] cuenta en tu historial de YouTube, que es de lo que aprenden sus
     * recomendaciones (como un «stream» de Spotify). Si sonaba desde la caché no hubo URL de aviso: se pide aparte.
     */
    private fun countInHistory(id: String) {
        historyJob?.cancel()
        if (historyCounted || !player.isPlaying || YouTube.cookie.isNullOrEmpty()) return
        historyJob = scope.launch {
            while (player.currentMediaItem?.mediaId == id && player.currentPosition < HEARD_MS) delay(2_000)
            if (player.currentMediaItem?.mediaId != id) return@launch
            val url = tracking[id] ?: withContext(Dispatchers.IO) { InnerTubeXPlayer.resolve(id, tiers[id] ?: Tier.Low) }.getOrNull()?.tracking ?: return@launch
            withContext(Dispatchers.IO) { YouTube.registerPlayback(null, url) }
                .onSuccess { historyCounted = true; Timber.d("historial: %s", id) }
                .onFailure { Timber.w(it, "historial") }
        }
    }

    // --- Radio ---

    private fun startRadio(seedId: String) {
        radioJob?.cancel()
        radio = Radio(seedId)
        topUpRadio()
    }

    private fun stopRadio() {
        radioJob?.cancel()
        radio = null
        auto = null
    }

    /**
     * Una lista sin bucle, al acercarse a su final, sigue con canciones parecidas a ella (ver [Autoplay]), como la
     * reproducción automática de Spotify. Con bucle, vuelve a empezar (eso lo hace ExoPlayer).
     */
    private fun autoplay() {
        if (preserveTransferredQueue && !player.playWhenReady) return
        if (!list || radio != null || auto != null || dj != null || player.repeatMode != Player.REPEAT_MODE_OFF || player.mediaItemCount == 0) return
        if (player.mediaItemCount - player.currentMediaItemIndex - 1 > RADIO_AHEAD) return
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }.filter { !it.isAuto }
            .map { Shuffle.Item(it.mediaId, it.mediaMetadata.artist?.toString().orEmpty(), null) }
        auto = Autoplay(Autoplay.seeds(items, player.getMediaItemAt(player.mediaItemCount - 1).mediaId, liked))
        topUpRadio()
    }

    /** Se puso el bucle con las parecidas ya en la cola: fuera las que aún no han sonado. */
    private fun dropAutoplay() {
        if (auto == null) return
        stopRadio()
        for (i in player.mediaItemCount - 1 downTo player.currentMediaItemIndex + 1) {
            if (player.getMediaItemAt(i).isAuto) player.removeMediaItem(i)
        }
    }

    /** Cuando quedan [RADIO_AHEAD] canciones o menos por delante, añade la siguiente página de la radio. */
    private fun topUpRadio() {
        if ((radio == null && auto == null) || radioJob?.isActive == true) return
        if (player.mediaItemCount - player.currentMediaItemIndex - 1 > RADIO_AHEAD) return
        radioJob = scope.launch {
            auto?.let { a ->
                // Ni lo que hay en la cola ni lo que sonó hace poco, por id ni por canción ([songKey], sin la «~»).
                val exclude = HashSet<String>()
                for (i in 0 until player.mediaItemCount) {
                    val m = player.getMediaItemAt(i)
                    exclude += m.mediaId
                    exclude += songKey(m.mediaMetadata.title?.toString().orEmpty(), m.mediaMetadata.artist?.toString().orEmpty())
                }
                recent.mapTo(exclude) { it.removePrefix("~") }
                val last = player.getMediaItemAt(player.mediaItemCount - 1).mediaId
                repeat(RADIO_PAGES) {
                    val fresh = withContext(Dispatchers.Default) { a.next(exclude, last) }
                    if (auto !== a) return@launch
                    if (fresh.isNotEmpty()) {
                        player.addMediaItems(fresh.map { it.toMediaItem().markAuto() })
                        Timber.d("reproducción automática: +%d", fresh.size)
                        return@launch
                    }
                }
                return@launch
            }
            repeat(RADIO_PAGES) {
                var r = radio ?: return@launch
                if (r.exhausted && player.mediaItemCount > 0) {
                    r = Radio(player.getMediaItemAt(player.mediaItemCount - 1).mediaId).also { radio = it }
                }
                val page = withContext(Dispatchers.IO) { r.more() }
                    .getOrElse { Timber.w(it, "radio"); return@launch }
                val queued = HashSet<String>()
                for (i in 0 until player.mediaItemCount) {
                    val m = player.getMediaItemAt(i)
                    queued += m.mediaId
                    queued += sameSong(m.mediaMetadata.title?.toString().orEmpty(), m.mediaMetadata.artist?.toString().orEmpty())
                }
                val fresh = page
                    .filter { s -> s.id !in queued && s.id !in recent && sameSong(s.title, s.artists.joinToString { it.name }).let { it !in queued && it !in recent } }
                    .distinctBy { sameSong(it.title, it.artists.joinToString { a -> a.name }) }
                if (fresh.isNotEmpty()) {
                    player.addMediaItems(fresh.map { it.toMediaItem() })
                    Timber.d("radio: +%d", fresh.size)
                    return@launch
                }
            }
        }
    }

    // --- DJ ---

    private val MediaItem.isDjItem: Boolean
        get() = mediaMetadata.extras?.getString(DJ_KIND) != null

    private fun clearAdhdSegments() {
        adhdSegments.clear()
        adhdUsedAudio.clear()
        adhdUsedLyrics.clear()
        adhdIntroAppliedFor = null
    }

    private fun effectiveAdhdFadeMs(): Long = if (fadeMs > 0L) fadeMs else AdhdPlanner.DEFAULT_MIX_MS

    private fun ensureAdhdSegment(item: MediaItem, durMs: Long): AdhdSegment? {
        val d = dj ?: return null
        if (djMode != DjMode.Adhd || !item.isDjItem || durMs <= 0L) return null
        if (d.isFullOnce(item.mediaId)) return null
        val cached = adhdSegments[item.mediaId]
        val sections = (app.lyrics.peek(item.mediaId) as? Lyrics.Synced)?.let { SongSections.of(it.lines) }.orEmpty()
        val audio = analysisCache.get(item.mediaId)
        // La duración exacta llega al preparar la canción y suele diferir en menos de un segundo de la de YouTube:
        // rehacer el trozo entonces lo movería de sitio con la canción ya sonando. Solo se rehace si llegó la letra
        // o un análisis de audio todavía no aplicado antes de que empiece a sonar.
        // La que ya suena no se rehace nunca (aunque llegue otra duración): se recolocó desde donde entró por el puente
        // y rehacerla le devolvía un final de antes; así salían trozos de menos de 40 s.
        if (cached != null && item.mediaId == player.currentMediaItem?.mediaId) return cached
        if (cached != null && kotlin.math.abs(cached.fullDurationMs - durMs) <= SAME_DURATION_MS) {
            val needLyrics = !cached.fromLyrics && sections.isNotEmpty() && item.mediaId !in adhdUsedLyrics
            val needAudio = !cached.fromLyrics && audio != null && item.mediaId !in adhdUsedAudio
            val canImprove = (needLyrics || needAudio) &&
                item.mediaId != player.currentMediaItem?.mediaId
            if (!canImprove) return cached
        }
        val track = djTracks[item.mediaId] ?: item.toHeard().toDjFallbackTrack(durMs)
        val seg = d.adhdSegment(track, durationMs = durMs, crossfadeMs = effectiveAdhdFadeMs(), sections = sections, audio = audio) ?: return null
        adhdSegments[item.mediaId] = seg
        if (audio != null) adhdUsedAudio += item.mediaId else adhdUsedAudio -= item.mediaId
        if (sections.isNotEmpty()) adhdUsedLyrics += item.mediaId
        val kindLabel = when {
            seg.fromLyrics -> "por la letra"
            audio != null && (audio.dropMs > 0L || audio.outroStartMs > 0L) -> "por análisis de audio"
            else -> "a ojo"
        }
        MixLog.i("DJ rápido: trozo de ${track.title} ${seg.startMs / 1000}–${seg.endMs / 1000} s de ${durMs / 1000} s ($kindLabel)")
        return seg
    }

    /**
     * Pide ya la letra de una canción del DJ que va a sonar: con ella se elige mejor su trozo en el DJ rápido y, en
     * los dos modos, la mezcla sabe cuándo canta cada una.
     */
    private fun prefetchDjLyrics(t: Track) {
        val secs = t.seconds ?: return
        scope.launch {
            runCatching { app.lyrics.get(LyricsQuery(t.id, t.title, t.artist, null, secs * 1000L)) }
        }
    }

    private fun io.github.iaguito22.oto.Heard.toDjFallbackTrack(durMs: Long) = Track(
        id = id,
        title = title,
        artists = artist.split(", ").filter { it.isNotBlank() }.ifEmpty { listOf(artist) },
        artistIds = listOf(artistId),
        artwork = artwork,
        seconds = (durMs / 1000L).toInt().takeIf { it > 0 },
    )

    private fun transitionPlanFor(item: MediaItem, durMs: Long): TransitionPlan? {
        val d = dj ?: return null
        if (djMode != DjMode.Adhd || !item.isDjItem || d.isFullOnce(item.mediaId)) return null
        return AdhdPlayback.transitionPlan(ensureAdhdSegment(item, durMs))
    }

    private fun djMixPlanFor(outgoing: MediaItem, incoming: MediaItem, outgoingDurMs: Long, requestedFadeMs: Long): DjTransitionPlan? {
        if (!smartMix || outgoingDurMs <= 0L) return null
        // La letra dice cuándo canta cada una: se mezcla sobre la parte instrumental y sin pisar dos voces.
        val outVocals = sectionsOf(outgoing.mediaId).orEmpty()
        val inVocals = sectionsOf(incoming.mediaId).orEmpty()
        val d = dj
        val recentStyles = recentBridgeStyles.toList()
        if (d == null || !outgoing.isDjItem || !incoming.isDjItem) {
            // Fuera del DJ, con la mezcla inteligente también decide el DJ: cuánto dura, dónde y con qué estilo. La
            // siguiente entra desde su principio (sin el silencio inicial).
            if (!smartMix) return null
            val nonDjInDur = incoming.mediaMetadata.durationMs ?: timelineDurationOf(incoming.mediaId) ?: 0L
            return DjTransitionPlanner.plan(
                outgoingDurationMs = outgoingDurMs,
                configuredFadeMs = requestedFadeMs,
                outgoingAudio = analysisCache.get(outgoing.mediaId),
                incomingAudio = analysisCache.get(incoming.mediaId),
                outgoingVocals = outVocals,
                incomingVocals = inVocals,
                autoLength = true,
                incomingFromStart = true,
                incomingDurationMs = nonDjInDur,
                outgoingLyrics = app.lyrics.peek(outgoing.mediaId) as? Lyrics.Synced,
                incomingLyrics = app.lyrics.peek(incoming.mediaId) as? Lyrics.Synced,
                djMode = DjMode.Normal,
                isDjSession = false,
                recentStyles = recentStyles,
            )
        }
        val outTrack = djTracks[outgoing.mediaId] ?: outgoing.toHeard().toDjFallbackTrack(outgoingDurMs)
        val inDur = incoming.mediaMetadata.durationMs
            ?: djTracks[incoming.mediaId]?.seconds?.let { it * 1000L }
            ?: timelineDurationOf(incoming.mediaId)
        val inTrack = djTracks[incoming.mediaId] ?: incoming.toHeard().toDjFallbackTrack(inDur ?: 0L)
        val outTanda = outgoing.mediaMetadata.extras?.getString(DJ_TANDA) ?: d.tandaOf(outgoing.mediaId)
        val inTanda = incoming.mediaMetadata.extras?.getString(DJ_TANDA) ?: d.tandaOf(incoming.mediaId)
        val tandaChanged = outTanda != null && inTanda != null && outTanda != inTanda
        val outAdhd = if (djMode == DjMode.Adhd && !d.isFullOnce(outgoing.mediaId)) ensureAdhdSegment(outgoing, outgoingDurMs) else null
        val inAdhd = if (djMode == DjMode.Adhd && !d.isFullOnce(incoming.mediaId) && inDur != null && inDur > 0L) {
            ensureAdhdSegment(incoming, inDur)
        } else null
        return d.transitionPlan(
            outgoing = outTrack,
            incoming = inTrack,
            outgoingDurationMs = outgoingDurMs,
            configuredFadeMs = requestedFadeMs,
            tandaChanged = tandaChanged,
            outgoingAdhd = outAdhd,
            incomingAdhd = inAdhd,
            outgoingVocals = outVocals,
            incomingVocals = inVocals,
            autoLength = smartMix,
            incomingDurationMs = inDur ?: 0L,
            outgoingLyrics = app.lyrics.peek(outgoing.mediaId) as? Lyrics.Synced,
            incomingLyrics = app.lyrics.peek(incoming.mediaId) as? Lyrics.Synced,
            recentStyles = recentStyles,
        )
    }

    // Bloques de la letra por canción (el plan se rehace en cada vistazo del fundido: no volver a trocear la letra).
    private val sectionsMemo = HashMap<String, List<io.github.iaguito22.oto.dj.SongSection>>()

    private fun sectionsOf(id: String): List<io.github.iaguito22.oto.dj.SongSection>? {
        sectionsMemo[id]?.let { return it }
        val lines = (app.lyrics.peek(id) as? Lyrics.Synced)?.lines ?: return null
        return SongSections.of(lines).also {
            if (sectionsMemo.size > 64) sectionsMemo.clear()
            sectionsMemo[id] = it
        }
    }

    private fun nextStartPositionFor(item: MediaItem): Long {
        val d = dj ?: return 0L
        if (djMode != DjMode.Adhd || !item.isDjItem || d.isFullOnce(item.mediaId)) return 0L
        val durMs = item.mediaMetadata.durationMs
            ?: djTracks[item.mediaId]?.seconds?.let { it * 1000L }
            ?: timelineDurationOf(item.mediaId)
            ?: return 0L
        return ensureAdhdSegment(item, durMs)?.startMs ?: 0L
    }

    /** La duración que ya sabe el reproductor de una canción de la cola (la siguiente suele estar preparada). */
    private fun timelineDurationOf(id: String): Long? {
        val timeline = player.currentTimeline
        val window = androidx.media3.common.Timeline.Window()
        for (i in 0 until timeline.windowCount) {
            timeline.getWindow(i, window)
            if (window.mediaItem.mediaId == id && window.durationMs != C.TIME_UNSET && window.durationMs > 0) return window.durationMs
        }
        return null
    }

    private fun applyAdhdForCurrent(durMs: Long) {
        val item = player.currentMediaItem ?: return
        val d = dj
        if (d == null || djMode != DjMode.Adhd || !item.isDjItem || durMs <= 0L || d.isFullOnce(item.mediaId)) {
            adhdMonitorJob?.cancel()
            return
        }
        var seg = ensureAdhdSegment(item, durMs) ?: return
        val key = item.mediaId to player.currentMediaItemIndex
        if (adhdIntroAppliedFor != key) {
            adhdIntroAppliedFor = key
            val pos = player.currentPosition
            if (crossfade.handingOver) {
                // Ya está entrando en un fundido: saltar ahora se oiría como un corte. El trozo sigue desde aquí.
                // Entró antes o después de donde empezaba su trozo: el trozo cuenta desde aquí (con su duración mínima).
                if (kotlin.math.abs(pos - seg.startMs) > SKIP_TOLERANCE_MS) {
                    seg = seg.startingAt(pos)
                    adhdSegments[item.mediaId] = seg
                    MixLog.i("DJ rápido: entró por el fundido en $pos ms, trozo recolocado hasta ${seg.endMs} ms")
                }
            } else if (AdhdPlayback.shouldSkipIntro(seg, pos)) {
                MixLog.i("DJ rápido: salta la intro de $pos a ${seg.startMs} ms")
                player.seekTo(seg.startMs)
            }
        }
        startAdhdMonitor(key, seg)
    }

    /** Baja el volumen en [ms] antes de un salto que no puede ir con fundido, para que no suene a corte. */
    private suspend fun duck(ms: Long, down: Boolean) {
        val steps = (ms / 20L).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val q = i.toFloat() / steps
            fadeIn = if (down) 1f - q else q
            applyVolume()
            delay(20L)
        }
    }

    private fun startAdhdMonitor(key: Pair<String, Int>, seg: AdhdSegment) {
        adhdMonitorJob?.cancel()
        adhdMonitorJob = scope.launch {
            while (isActive) {
                delay(250L)
                val current = player.currentMediaItem ?: break
                val d = dj
                if (d == null || djMode != DjMode.Adhd || d.isFullOnce(current.mediaId) || (current.mediaId to player.currentMediaItemIndex) != key) break
                if (!player.isPlaying || crossfade.handingOver) continue
                if (player.currentPosition >= seg.endMs - DUCK_MS) {
                    if (player.hasNextMediaItem()) {
                        // No hubo fundido (la siguiente no estaba lista o está desactivado): bajada corta, salto y subida.
                        // Se lanza fuera de [adhdMonitorJob] porque seekTo dispara onMediaItemTransition y cancelaría este Job.
                        MixLog.i("DJ rápido: fin del trozo sin fundido en ${player.currentPosition} ms")
                        val nextIdx = player.currentMediaItemIndex + 1
                        val nextStart = nextStartPositionFor(player.getMediaItemAt(nextIdx))
                        duckAndAdvanceAdhd(nextIdx, nextStart)
                    }
                    break
                }
            }
        }
    }

    private fun duckAndAdvanceAdhd(nextIdx: Int, nextStart: Long) {
        duckJob?.cancel()
        duckJob = scope.launch {
            try {
                duck(DUCK_MS, down = true)
                if (nextIdx < player.mediaItemCount) {
                    if (nextStart > 0L) player.seekTo(nextIdx, nextStart) else player.seekToNextMediaItem()
                }
                withTimeoutOrNull(3_000L) { while (!player.isPlaying) delay(20L) }
                duck(DUCK_MS, down = false)
            } finally {
                fadeIn = 1f
                applyVolume()
            }
        }
    }

    private fun updateDjMode(newMode: DjMode) {
        if (djMode == newMode) return
        djMode = newMode
        dj?.setMode(newMode)
        clearAdhdSegments()
        if (newMode == DjMode.Adhd) {
            val item = player.currentMediaItem ?: return
            val dur = player.duration.takeIf { it > 0 } ?: item.mediaMetadata.durationMs ?: 0L
            if (dur > 0L) applyAdhdForCurrent(dur)
        } else {
            adhdMonitorJob?.cancel()
        }
    }

    /** Arranca el DJ (ver [Dj]): la primera suena al momento y el resto se elige de una en una, sobre la marcha. */
    private fun startDj(requestedMode: DjMode? = null): ListenableFuture<SessionResult> {
        val modeToUse = requestedMode ?: djMode
        djMode = modeToUse
        val done = SettableFuture.create<SessionResult>()
        firstOfDj(modeToUse) { first ->
            if (first == null) {
                done.set(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                return@firstOfDj
            }
            val startPos = nextStartPositionFor(first)
            if (startPos > 0L) adhdIntroAppliedFor = first.mediaId to 0
            player.setMediaItems(listOf(first), 0, startPos)
            player.prepare()
            player.play()
            done.set(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        return done
    }

    /** Crea una instancia de [Dj] con el perfil actual y la caché de análisis de audio. */
    private suspend fun createDjInstance(modeToUse: DjMode): Dj {
        val d = withContext(Dispatchers.Default) {
            val lists = prefs.playlists.first()
            val liked = prefs.liked.first()
            val history = prefs.history.first()
            val plays = app.plays.all()
            val profile = traced("oto.dj.profile") { Profile(lists, liked, history, plays, System.currentTimeMillis()) }
            djProfile = profile
            val genres = app.genres.ready()
            Dj(
                profile = profile,
                catalog = app.djCatalog,
                memory = Memory(File(filesDir, "dj.json")),
                scope = scope,
                mood = djMood,
                mode = modeToUse,
                log = { Timber.tag("dj").d(it) },
                genre = resolveGenre(djGenreName),
                genreOf = { t -> genres.genreOf(t, profile) },
                analysisCache = analysisCache,
                lyricsPeek = { app.lyrics.peek(it) },
            )
        }
        // Los géneros que falten, poco a poco y sin prisa (iTunes limita las consultas).
        djProfile?.let { p -> scope.launch { runCatching { app.genres.fill(p) } } }
        return d
    }

    /** Prepara el DJ y da su primera canción (null si no hay de dónde sacarla); quien la recibe la pone. */
    private fun firstOfDj(modeToUse: DjMode = djMode, then: (MediaItem?) -> Unit) {
        stopRadio()
        list = false
        clearDjRuntime()
        djJob = scope.launch {
            val d = createDjInstance(modeToUse)
            val first = d.start() ?: return@launch then(null)
            dj = d
            djSessionPlayed.clear()
            djTracks[first.id] = first
            // En el DJ rápido la primera también entra por un bloque: se espera un poco a su letra (casi siempre está
            // guardada; si no, a ojo / análisis de audio).
            if (modeToUse == DjMode.Adhd) first.seconds?.let { secs ->
                withTimeoutOrNull(FIRST_LYRICS_WAIT_MS) {
                    runCatching { app.lyrics.get(LyricsQuery(first.id, first.title, first.artist, null, secs * 1000L)) }
                }
            }
            Timber.tag("dj").d("%s (%s/%s): %s — %s", d.picks.last().kind, d.mood.label, d.mode.label, first.artist, first.title)
            then(first.toDjItem(d))
            syncUpcomingDjQueue(d)
        }
    }

    /** Arranca el DJ usando [args] como semilla («Arrancar DJ desde aquí»). */
    private fun startDjFrom(args: Bundle): ListenableFuture<SessionResult> {
        val trackId = args.getString(EXTRA_DJ_TRACK_ID)?.takeIf { it.isNotBlank() }
            ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
        val title = args.getString(EXTRA_DJ_TITLE).orEmpty()
        val artists = args.getStringArrayList(EXTRA_DJ_ARTISTS)?.filter { it.isNotBlank() }
            ?.ifEmpty { listOf(args.getString(EXTRA_DJ_ARTIST).orEmpty()) }
            ?: listOf(args.getString(EXTRA_DJ_ARTIST).orEmpty())
        val artistIds = args.getStringArrayList(EXTRA_DJ_ARTIST_IDS)?.map { it.ifEmpty { null } }.orEmpty()
        val artwork = args.getString(EXTRA_DJ_ARTWORK)?.ifBlank { null }
        val seconds = args.getInt(EXTRA_DJ_SECONDS, 0).takeIf { it > 0 }
        val modeToUse = args.getString(EXTRA_DJ_MODE)
            ?.let { name -> DjMode.entries.firstOrNull { it.name == name } }
            ?: djMode
        djMode = modeToUse
        val seed = Track(
            id = trackId,
            title = title,
            artists = artists,
            artistIds = artistIds,
            artwork = artwork,
            seconds = seconds,
        )

        val done = SettableFuture.create<SessionResult>()
        stopRadio()
        list = false
        clearDjRuntime()
        djJob = scope.launch {
            val d = createDjInstance(modeToUse)
            val alreadyPlayingSeed = player.currentMediaItem?.mediaId == seed.id &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
            val first = d.startFrom(seed, playSeedFirst = true) ?: run {
                done.set(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                return@launch
            }
            dj = d
            djSessionPlayed.clear()
            djTracks[first.id] = first
            if (alreadyPlayingSeed) {
                val curIdx = player.currentMediaItemIndex
                val from = curIdx + 1
                if (from < player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
                // Mantiene sonando la pista actual sin corte; solo añade las etiquetas del DJ a su metadata.
                val cur = player.getMediaItemAt(curIdx)
                val extras = Bundle(cur.mediaMetadata.extras ?: Bundle.EMPTY).apply {
                    putString(DJ_KIND, d.picks.lastOrNull()?.kind?.name ?: Kind.FAMILIAR.name)
                    d.tandaOf(first.id)?.let { putString(DJ_TANDA, it) }
                    d.reasonOf(first.id)?.let { putString(DJ_REASON, it) }
                }
                val tagged = cur.buildUpon().setMediaMetadata(cur.mediaMetadata.buildUpon().setExtras(extras).build()).build()
                player.replaceMediaItem(curIdx, tagged)
                val dur = player.duration.takeIf { it > 0 } ?: tagged.mediaMetadata.durationMs ?: (seconds?.times(1000L) ?: 0L)
                if (modeToUse == DjMode.Adhd && dur > 0L) applyAdhdForCurrent(dur)
            } else {
                if (modeToUse == DjMode.Adhd) first.seconds?.let { secs ->
                    withTimeoutOrNull(FIRST_LYRICS_WAIT_MS) {
                        runCatching { app.lyrics.get(LyricsQuery(first.id, first.title, first.artist, null, secs * 1000L)) }
                    }
                }
                val item = first.toDjItem(d)
                val startPos = nextStartPositionFor(item)
                if (startPos > 0L) adhdIntroAppliedFor = first.id to 0
                player.setMediaItems(listOf(item), 0, startPos)
                player.prepare()
                player.play()
            }
            done.set(SessionResult(SessionResult.RESULT_SUCCESS))
            syncUpcomingDjQueue(d)
        }
        return done
    }

    /** Anula el recorte ADHD solo para la canción que suena ahora («Oír esta entera»). */
    private fun playFullOnceForCurrent() {
        val d = dj ?: return
        val current = player.currentMediaItem ?: return
        if (!current.isDjItem) return
        d.playFullOnce(current.mediaId)
        adhdSegments.remove(current.mediaId)
        adhdUsedAudio.remove(current.mediaId)
        adhdMonitorJob?.cancel()
        MixLog.i("DJ rápido: oír esta entera → ${current.mediaMetadata.title}")
        publishDjSessionExtras()
    }

    /** Veta un tema preparado en NextUp y lo sustituye por otro en cola y en la sesión. */
    private fun vetoUpcomingDj(trackId: String) {
        val d = dj ?: return
        djJob?.cancel()
        djJob = scope.launch {
            val picks = d.vetoNextUp(trackId, NEXT_UP_COUNT)
            if (dj !== d) return@launch
            applyUpcomingQueueDiff(d, picks)
            publishDjSessionExtras()
        }
    }

    /** Un future que se cumple con lo que devuelva [block], en el hilo principal. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch { runCatching { block() }.onSuccess { f.set(it) }.onFailure { f.setException(it) } }
        return f
    }

    private fun clearDjRuntime() {
        djJob?.cancel()
        duckJob?.cancel()
        adhdMonitorJob?.cancel()
        clearAdhdSegments()
        djTracks.clear()
        recentBridgeStyles.clear()
        committedMixPair = null
    }

    private fun stopDj() {
        clearDjRuntime()
        dj = null
        publishDjSessionExtras()
    }

    /** El género tuyo con ese nombre, de los ya conocidos (null: todo, o aún no se sabe). */
    private fun resolveGenre(name: String?): DjGenre? {
        val p = djProfile ?: return null
        return name?.let { n -> app.genres.top(p, max = 20).firstOrNull { it.name == n } }
    }

    /**
     * Quita lo que el DJ tenía puesto detrás de la que suena y pide otra (tras saltar el ambiente o cambiar de
     * género). Con [skipNow], pasa ya a ella; si no, sigue sonando la de ahora.
     */
    private fun replaceUpcomingDj(skipNow: Boolean) {
        val d = dj ?: return
        val from = player.currentMediaItemIndex + 1
        if (from < player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
        djJob?.cancel()
        publishDjSessionExtras()
        djJob = scope.launch {
            val t = d.next() ?: run {
                publishDjSessionExtras()
                return@launch
            }
            if (dj !== d) return@launch
            djTracks[t.id] = t
            prefetchDjLyrics(t)
            val item = t.toDjItem(d)
            player.addMediaItem(item)
            if (skipNow) {
                val idx = player.mediaItemCount - 1
                val start = nextStartPositionFor(item)
                if (start > 0L) player.seekTo(idx, start) else player.seekToDefaultPosition(idx)
            }
            MixLog.i("DJ: ${if (skipNow) "ambiente saltado" else "género cambiado"} → ${d.tandaOf(t.id) ?: d.tanda} (${t.artist} — ${t.title})")
            syncUpcomingDjQueue(d)
        }
    }

    /** Mantiene 2–3 próximos temas reales del DJ en cola ([NEXT_UP_COUNT]) sin adelantar el anchor activo. */
    private fun topUpDj() {
        val d = dj ?: return
        if (djJob?.isActive == true) return
        val ahead = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (d.nextUp().size >= NEXT_UP_COUNT && ahead >= NEXT_UP_COUNT) {
            publishDjSessionExtras()
            return
        }
        djJob = scope.launch { syncUpcomingDjQueue(d) }
    }

    private suspend fun syncUpcomingDjQueue(d: Dj) {
        var picks = d.prepareNextUp(NEXT_UP_COUNT)
        // Seguro: nada que ya haya sonado en esta sesión del DJ vuelve a la cola.
        repeat(3) {
            val again = picks.firstOrNull { it.track.id in djSessionPlayed } ?: return@repeat
            MixLog.i("DJ: ${again.track.title} ya sonó en esta sesión, se cambia")
            picks = d.vetoNextUp(again.track.id, NEXT_UP_COUNT)
        }
        picks = picks.filter { it.track.id !in djSessionPlayed }
        if (dj !== d) return
        applyUpcomingQueueDiff(d, picks)
        publishDjSessionExtras()
    }

    private fun applyUpcomingQueueDiff(d: Dj, picks: List<DjPickInfo>) {
        picks.forEach { p ->
            djTracks[p.track.id] = p.track
            prefetchDjLyrics(p.track)
        }
        val base = player.currentMediaItemIndex + 1
        if (base < 0) return
        val existingCount = (player.mediaItemCount - base).coerceAtLeast(0)
        var common = 0
        val limit = min(existingCount, picks.size)
        while (common < limit && player.getMediaItemAt(base + common).mediaId == picks[common].track.id) {
            common++
        }
        // Si ya coincide exactamente con los ítems en cola (p. ej. índice+1 intacto), no tocamos la precarga de ExoPlayer.
        if (common == picks.size && existingCount == picks.size) return
        val removeFrom = base + common
        if (removeFrom < player.mediaItemCount) {
            player.removeMediaItems(removeFrom, player.mediaItemCount)
        }
        if (common < picks.size) {
            player.addMediaItems(picks.drop(common).map { it.toDjItem(d) })
        }
    }

    private fun publishDjSessionExtras() {
        if (!::session.isInitialized) return
        val d = dj
        val current = player.currentMediaItem
        val nextItem = if (current != null && player.hasNextMediaItem()) {
            player.getMediaItemAt(player.nextMediaItemIndex)
        } else null
        val pairIds = if (current != null && nextItem != null) current.mediaId to nextItem.mediaId else null
        // Solo la mezcla de ESTA pareja. No se borra al cambiar de canción o de cola: el relevo no la vuelve a mandar
        // si no cambia (y el aviso «Preparando la mezcla» desaparecía hasta el siguiente cambio de estado).
        val mixAllowed = current != null && nextItem != null && player.repeatMode != Player.REPEAT_MODE_ONE && smartMix
        val pairMix = if (mixAllowed) mixUi?.takeIf { it.pair == null || it.pair == pairIds } else null
        val mix = pairMix?.takeIf { it.startMs >= 0L && it.state != MixUiState.None }
        // Antes, sin mezcla publicada para esta pareja (p. ej. mientras suena el puente anterior) se calculaba aquí un
        // plan con el análisis a medias y se enseñaba su estilo como si estuviera decidido. Ahora: «Preparando» hasta
        // que el fundido la mira; solo el estilo de su plan.
        val waiting = mixAllowed && pairMix == null && !isMixing && player.playWhenReady
        val resolvedStyle = mix?.style
        val extras = Bundle().apply {
            val tanda = if (d != null && current != null && current.isDjItem) {
                d.tandaOf(current.mediaId) ?: current.mediaMetadata.extras?.getString(DJ_TANDA)
            } else null
            if (tanda != null) putString(SESSION_DJ_TANDA, tanda)
            val fullOnce = d != null && current != null && current.isDjItem && d.isFullOnce(current.mediaId)
            putBoolean(SESSION_DJ_FULL_ONCE, fullOnce)
            val nextUpJson = if (d != null) DjSessionCodec.encodeNextUp(d.nextUp()) else "[]"
            putString(SESSION_DJ_NEXT_UP, nextUpJson)
            putBoolean(SESSION_DJ_MIXING, isMixing)
            putLong(SESSION_DJ_MIX_START_MS, mix?.startMs ?: -1L)
            putLong(SESSION_DJ_MIX_SWITCH_MS, mix?.switchMs ?: -1L)
            (mix?.state ?: MixUiState.Preparing.takeIf { waiting })?.let { putString(SESSION_DJ_MIX_STATE, it.id) }
            if (resolvedStyle != null) putString(SESSION_DJ_NEXT_STYLE, resolvedStyle.id)
        }
        session.setSessionExtras(extras)
    }

    private fun Track.toDjItem(d: Dj): MediaItem {
        val item = toSong().toMediaItem()
        val kind = d.picks.lastOrNull { it.track.id == id }?.kind?.name ?: Kind.FAMILIAR.name
        val tandaLabel = d.tandaOf(id) ?: d.tanda
        val reason = d.reasonOf(id)
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply {
            putString(DJ_KIND, kind)
            tandaLabel?.let { putString(DJ_TANDA, it) }
            reason?.let { putString(DJ_REASON, it) }
        }
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    private fun DjPickInfo.toDjItem(d: Dj): MediaItem {
        val item = track.toSong().toMediaItem()
        val tandaLabel = tanda ?: d.tandaOf(track.id)
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply {
            putString(DJ_KIND, kind.name)
            tandaLabel?.let { putString(DJ_TANDA, it) }
            putString(DJ_REASON, reason)
        }
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    private fun flushPlayMs(): Long {
        if (playStartedAtMs > 0L) {
            playAccumMs += (SystemClock.elapsedRealtime() - playStartedAtMs).coerceAtLeast(0L)
            playStartedAtMs = 0L
        }
        return playAccumMs
    }

    private fun resetPlayClock() {
        playAccumMs = 0L
        playStartedAtMs = if (player.isPlaying) SystemClock.elapsedRealtime() else 0L
    }

    /** Lo que se oyó de [item] antes de pasar a otra: al diario (de lo que aprende el DJ) y, si es suya, al DJ. */
    private fun logPlay(item: MediaItem, posMs: Long, accumMs: Long = 0L) {
        val dur = durations[item.mediaId] ?: item.mediaMetadata.durationMs ?: 0L
        val seg = adhdSegments[item.mediaId]
        val listenedMs = AdhdPlayback.effectiveListenedMs(posMs, accumMs, seg)
        val kind = item.mediaMetadata.extras?.getString(DJ_KIND)?.let { k -> Kind.entries.firstOrNull { it.name == k } }
        if (kind == null && listenedMs < 1_000) return
        val h = item.toHeard()
        val play = Play(h.id, h.title, h.artist, h.artistId, System.currentTimeMillis(), listenedMs, dur, kind)
        scope.launch(Dispatchers.IO) { app.plays.add(play) }
        if (kind != null) dj?.heard(item.mediaId, listenedMs, dur)
        if (item.isAuto) auto?.heard(item.mediaId, h.artist, listenedMs, dur)
    }

    private fun remember(vararg keys: String) {
        for (k in keys) {
            recent.remove(k)
            recent.addLast(k)
        }
        while (recent.size > RECENT) recent.removeFirst()
    }

    private fun sameSong(title: String, artist: String) = "~" + songKey(title, artist)

    private var lyricsJob: Job? = null
    private var lyricsFor: String? = null

    /**
     * La letra de la que suena, pedida ya (con la duración, que hace falta para no equivocarse de versión): abrirla
     * luego es al momento. Tras un par de segundos, para no gastar el cupo de lyricsplus pasando canciones.
     */
    private fun fetchLyrics() {
        val item = player.currentMediaItem ?: return
        if (item.mediaId == lyricsFor) return
        lyricsFor = item.mediaId
        lyricsJob?.cancel()
        val m = item.mediaMetadata
        val q = LyricsQuery(item.mediaId, m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), m.albumTitle?.toString(), player.duration)
        lyricsJob = scope.launch {
            delay(if (dj != null) 450L else LYRICS_DELAY_MS)
            // En DJ/smartMix adelantamos la letra de las siguientes para planificar `LyricLoopCut` y huecos de voz a tiempo.
            if (smartMix || dj != null) {
                val cur = player.currentMediaItemIndex
                for (offset in 1..2) {
                    val idx = cur + offset
                    if (idx >= player.mediaItemCount) break
                    val ahead = player.getMediaItemAt(idx)
                    val am = ahead.mediaMetadata
                    val dur = am.durationMs ?: djTracks[ahead.mediaId]?.seconds?.let { it * 1000L } ?: timelineDurationOf(ahead.mediaId)
                    if (dur != null && dur > 0L && app.lyrics.peek(ahead.mediaId) == null) {
                        runCatching {
                            app.lyrics.get(LyricsQuery(ahead.mediaId, am.title?.toString().orEmpty(), am.artist?.toString().orEmpty(), am.albumTitle?.toString(), dur))
                        }
                    }
                }
            }
            val l = runCatching { app.lyrics.get(q) }.getOrNull()?.lyrics
            if (l is Lyrics.Synced && LyricsSource.needsRomanization(l.lines)) runCatching { app.lyrics.romanized(q.id, l) }
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId ?: return
            retries.keys.retainAll(setOf(id))
            // Lo que ya no suena ni va a sonar justo después vuelve a decidir su calidad la próxima vez.
            playingIndex = player.currentMediaItemIndex
            val activeIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            // Lo que ya no está en la cola vuelve a decidir su calidad la próxima vez; lo que sigue en ella, no (puede
            // estar a medio cargar).
            tiers.keys.retainAll(activeIds)
            djTracks.keys.retainAll(activeIds)
            adhdSegments.keys.retainAll(activeIds)
            adhdUsedAudio.retainAll(activeIds)
            adhdUsedLyrics.retainAll(activeIds)
            stallJob?.cancel()
            if (player.playbackState == Player.STATE_BUFFERING) watchStart()
            durations.keys.retainAll(setOf(id) + activeIds)
            remember(id, sameSong(mediaItem.mediaMetadata.title?.toString().orEmpty(), mediaItem.mediaMetadata.artist?.toString().orEmpty()))
            resetPlayClock()
            currentTrackAlreadyLiked = id in liked
            val knownDur = player.duration.takeIf { it > 0 } ?: mediaItem.mediaMetadata.durationMs ?: 0L
            finalizeLiveAudioAnalysis(id, knownDur)
            if (!crossfade.handingOver && duckJob?.isActive != true) {
                fadeIn = 1f
            }
            applyLoudness()
            val d = dj
            if (d != null && mediaItem.isDjItem) {
                djSessionPlayed += id
                if (djMode == DjMode.Adhd && knownDur > 0L) {
                    applyAdhdForCurrent(knownDur)
                } else {
                    adhdMonitorJob?.cancel()
                }
                val consumed = d.consumeUpcomingThrough(id)
                if (consumed != null) {
                    djJob?.cancel()
                    djJob = scope.launch {
                        if (dj === d) syncUpcomingDjQueue(d)
                    }
                } else {
                    topUpDj()
                }
            } else {
                adhdMonitorJob?.cancel()
            }
            publishDjSessionExtras()
            topUpRadio()
            autoplay()
            prefetch()
            historyCounted = false
            countInHistory(id)
            showLike()
            scope.launch(Dispatchers.IO) { prefs.addHeard(mediaItem.toHeard()) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && preserveTransferredQueue) {preserveTransferredQueue=false;autoplay()}
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                if (playStartedAtMs == 0L) playStartedAtMs = SystemClock.elapsedRealtime()
                player.currentMediaItem?.mediaId?.let(::countInHistory)
                prefetch()
            } else {
                flushPlayMs()
                historyJob?.cancel()
                prefetchJob?.cancel()
                writer?.cancel()
            }
        }

        override fun onPlayerError(error: PlaybackException) = recover(error)

        override fun onRepeatModeChanged(repeatMode: Int) {
            if (repeatMode == Player.REPEAT_MODE_OFF) autoplay() else dropAutoplay()
            publishDjSessionExtras()
        }

        // Quitar o mover canciones de la cola cambia el índice de la que suena sin pasar a otra.
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            playingIndex = player.currentMediaItemIndex
            publishLevels()
            prefetch()
            publishDjSessionExtras()
        }

        override fun onPlaybackStateChanged(state: Int) {
            val item = player.currentMediaItem ?: return
            val id = item.mediaId
            if (state == Player.STATE_BUFFERING && player.currentPosition <= STARTED_MS) watchStart() else if (state != Player.STATE_BUFFERING) stallJob?.cancel()
            if (state == Player.STATE_READY && player.duration > 0) {
                durations[id] = player.duration
                finalizeLiveAudioAnalysis(id, player.duration)
                if (analysisCache.get(id)?.coversWholeSong != true) {
                    scope.launch(Dispatchers.Default) {
                        if (audioAnalyzer.analyzeIfCached(id, keyOf(id)) != null) {
                            withContext(Dispatchers.Main) {
                                if (player.currentMediaItem?.mediaId == id) applyLoudness()
                                publishDjSessionExtras()
                            }
                        }
                    }
                }
                if (dj != null && djMode == DjMode.Adhd && item.isDjItem) {
                    applyAdhdForCurrent(player.duration)
                }
                publishDjSessionExtras()
                fetchLyrics()
            }
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            val item = old.mediaItem ?: return
            // Moverse dentro de la misma no es dejarla; volver a empezarla sola (repetir una), sí. Cambiarla por ella
            // misma en baja ([watchStart]) tampoco.
            if (item.mediaId == new.mediaItem?.mediaId && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                if (dj != null && djMode == DjMode.Adhd && item.isDjItem && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    adhdSegments[item.mediaId]?.let { seg ->
                        if (new.positionMs >= seg.endMs + 1_000L) adhdMonitorJob?.cancel()
                    }
                }
                return
            }
            val accum = flushPlayMs()
            logPlay(item, old.positionMs, accum)
            resetPlayClock()
        }
    }

    private val callback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(library.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ) = future {
            val all = library.children(parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(all.subList(from, (from + pageSize).coerceAtMost(all.size)), params)
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String) = future {
            val item = library.expand(MediaItem.Builder().setMediaId(mediaId).build())?.let { (items, at) -> items[at] }
            if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?) = future {
            found = query to library.search(query)
            session.notifySearchResultChanged(browser, query, found.second.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ) = future {
            val all = if (found.first == query) found.second else library.search(query)
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(all.subList(from, (from + pageSize).coerceAtMost(all.size)), params)
        }

        // Todos los controladores (también la notificación) pueden pulsar el corazón; y explorar la biblioteca (Android Auto).
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(LIKE)
                        .add(DJ)
                        .add(DJ_MOOD_CMD)
                        .add(DJ_MODE_CMD)
                        .add(DJ_REACT_CMD)
                        .add(DJ_GENRE_CMD)
                        .add(DJ_SKIP_CMD)
                        .add(DJ_FULL_ONCE_CMD)
                        .add(DJ_VETO_CMD)
                        .add(DJ_START_FROM_CMD)
                        .build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                DJ.customAction -> {
                    val requestedMode = args.getString(EXTRA_DJ_MODE)?.let { name -> DjMode.entries.firstOrNull { it.name == name } }
                    startDj(requestedMode)
                }
                DJ_START_FROM_CMD.customAction -> startDjFrom(args)
                DJ_MOOD_CMD.customAction -> {
                    val mood = args.getString(EXTRA_DJ_MOOD)?.let { name -> DjMood.entries.firstOrNull { it.name == name } }
                    if (mood != null) {
                        djMood = mood
                        dj?.let { d ->
                            d.setMood(mood)
                            replaceUpcomingDj(skipNow = false)
                        }
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                DJ_MODE_CMD.customAction -> {
                    val mode = args.getString(EXTRA_DJ_MODE)?.let { name -> DjMode.entries.firstOrNull { it.name == name } }
                    if (mode != null) updateDjMode(mode)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                DJ_GENRE_CMD.customAction -> {
                    val name = args.getString(EXTRA_DJ_GENRE)?.ifEmpty { null }
                    djGenreName = name
                    dj?.setGenre(resolveGenre(name))
                    // Lo que ya estaba puesto era del género de antes: la siguiente ya es del nuevo.
                    if (dj != null) replaceUpcomingDj(skipNow = false)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                DJ_SKIP_CMD.customAction -> {
                    dj?.let { d ->
                        d.skipTanda()
                        replaceUpcomingDj(skipNow = true)
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                DJ_FULL_ONCE_CMD.customAction -> {
                    playFullOnceForCurrent()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                DJ_VETO_CMD.customAction -> {
                    val id = args.getString(EXTRA_DJ_TRACK_ID)
                    if (!id.isNullOrEmpty()) vetoUpcomingDj(id)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                DJ_REACT_CMD.customAction -> {
                    val id = args.getString(EXTRA_DJ_TRACK_ID)
                    val reaction = args.getString(EXTRA_DJ_REACTION)?.let { name -> DjReaction.entries.firstOrNull { it.name == name } }
                    if (!id.isNullOrEmpty() && reaction != null) {
                        dj?.react(id, reaction)
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                LIKE.customAction -> {
                    toggleLike()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }

        // Los MediaItem que llegan de un controlador pierden la URI por el camino. Los de Android Auto pueden venir
        // de una lista (id «in|lista|canción») o ser una búsqueda por voz (sin id, con lo dicho).
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            mediaItems.flatMapTo(ArrayList()) { item -> resolve(item).map { it.withPlaybackUri() } }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.firstOrNull()?.requestMetadata?.extras?.getBoolean("oto_exact_transfer") == true) {
                stopDj(); stopRadio(); list = true; preserveTransferredQueue = true
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems.map { it.withPlaybackUri() },startIndex,startPositionMs))
            }
            preserveTransferredQueue = false
            val one = mediaItems.singleOrNull()
            // Desde Android Auto: el DJ, una canción de una lista (suena la lista desde ella) o lo pedido por voz.
            val djAsk = one?.let { Library.djRequest(it.mediaId) }
            if (djAsk != null) {
                val (fast, genreName) = djAsk
                val mode = if (fast) DjMode.Adhd else DjMode.Normal
                // Lo elegido en el coche queda elegido también en la app.
                djGenreName = genreName
                djMode = mode
                scope.launch { prefs.setDjGenre(genreName); prefs.setDjMode(mode) }
                val f = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                firstOfDj(mode) { first ->
                    if (first == null) f.setException(IllegalStateException("El DJ no tiene de dónde sacar"))
                    else {
                        val startPos = nextStartPositionFor(first)
                        if (startPos > 0L) adhdIntroAppliedFor = first.mediaId to 0
                        f.set(MediaSession.MediaItemsWithStartPosition(listOf(first), 0, startPos))
                    }
                }
                return f
            }
            if (one != null && (Library.listAction(one.mediaId) != null || Library.inList(one.mediaId) != null || one.mediaId.isEmpty())) return future {
                val expanded = library.expand(one)
                if (expanded != null) {
                    stopDj(); stopRadio()
                    list = true
                    val action = Library.listAction(one.mediaId)
                    if (action != null) player.shuffleModeEnabled = action.second
                    autoplay()
                    val start = if (action?.second == true) expanded.first.indices.random() else expanded.second
                    MediaSession.MediaItemsWithStartPosition(expanded.first.map { it.withPlaybackUri() }, start, 0)
                } else {
                    if (Library.listAction(one.mediaId) != null) return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
                    val found = resolve(one).map { it.withPlaybackUri() }
                    stopDj()
                    list = false
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    found.firstOrNull()?.let { startRadio(it.mediaId) }
                    MediaSession.MediaItemsWithStartPosition(found, 0, 0)
                }
            }
            val items = mediaItems.map { it.withPlaybackUri() }
            // Poner una sola canción es pedir su radio detrás; una lista (álbum, playlist) suena tal cual.
            // Se lanza después de que la sesión aplique la cola nueva.
            stopDj()
            list = items.size > 1
            // Una lista corta puede acabarse enseguida: se mira ya si toca seguir con parecidas.
            if (items.size == 1) scope.launch(Dispatchers.Main) { startRadio(items[0].mediaId) } else { stopRadio(); scope.launch(Dispatchers.Main) { autoplay() } }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs))
        }
    }

    /** Lo que de verdad suena por [item]: la canción de una lista, la primera que encuentre lo dicho por voz, o él mismo. */
    private suspend fun resolve(item: MediaItem): List<MediaItem> {
        Library.inList(item.mediaId)?.let { (_, song) -> return library.expand(item)?.let { (l, at) -> listOf(l[at]) } ?: listOf(item.buildUpon().setMediaId(song).build()) }
        if (item.mediaId.isNotEmpty()) return listOf(item)
        val q = item.requestMetadata.searchQuery ?: return emptyList()
        return library.search(q).take(1)
    }

    private class ResolveException(id: String, cause: Throwable) : IOException("No se pudo resolver $id: ${cause.message}", cause) {
        val network = generateSequence(cause) { it.cause }.any { it is IOException && it !is ResolveException }
    }

    /** Para [PatientErrorPolicy]: lo que se arregla esperando a que vuelva la cobertura. */
    private fun isConnectionLost(e: IOException): Boolean =
        generateSequence<Throwable>(e) { it.cause }.firstNotNullOfOrNull { (it as? ResolveException)?.network } ?: e.isConnectionFailure()

    private fun PlaybackException.isNetwork(): Boolean =
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            generateSequence<Throwable>(this) { it.cause }.any { it is ResolveException && it.network }

    private companion object {
        const val MAX_RETRIES = 3
        const val ANALYSIS_WATCH_MS = 5_000L
        const val RADIO_AHEAD = 2
        const val RADIO_PAGES = 3
        const val RECENT = 400
        const val HEARD_MS = 30_000L
        const val STALL_MS = 6_000L
        const val STARTED_MS = 1_000L
        const val SAME_DURATION_MS = 3_000L
        const val SKIP_TOLERANCE_MS = 1_500L
        const val DUCK_MS = 400L
        const val FIRST_LYRICS_WAIT_MS = 2_500L
        const val AHEAD = 3
        const val NEXT_UP_COUNT = 3
        const val PREFETCH_DELAY_MS = 4_000L
        const val LYRICS_DELAY_MS = 2_000L
        const val MIN_GAIN = 0.15f
        const val TARGET_RMS_DB = -14.0
        const val MIN_LIVE_CHUNKS = 1_500
        const val MAX_LIVE_CHUNKS = 8_192
        // Un trozo ([ChunkedDataSource]): casi un minuto en alta, de sobra para que empiece al momento.
        const val HEAD_BYTES = ChunkedDataSource.CHUNK_BYTES
        const val DJ_KIND = "oto_dj"
        val LIKE = SessionCommand("io.github.iaguito22.oto.LIKE", Bundle.EMPTY)
        val DJ = SessionCommand(DJ_COMMAND, Bundle.EMPTY)
        val DJ_MOOD_CMD = SessionCommand(DJ_MOOD_COMMAND, Bundle.EMPTY)
        val DJ_MODE_CMD = SessionCommand(DJ_MODE_COMMAND, Bundle.EMPTY)
        val DJ_REACT_CMD = SessionCommand(DJ_REACT_COMMAND, Bundle.EMPTY)
        val DJ_GENRE_CMD = SessionCommand(DJ_GENRE_COMMAND, Bundle.EMPTY)
        val DJ_SKIP_CMD = SessionCommand(DJ_SKIP_COMMAND, Bundle.EMPTY)
        val DJ_FULL_ONCE_CMD = SessionCommand(DJ_FULL_ONCE_COMMAND, Bundle.EMPTY)
        val DJ_VETO_CMD = SessionCommand(DJ_VETO_COMMAND, Bundle.EMPTY)
        val DJ_START_FROM_CMD = SessionCommand(DJ_START_FROM_COMMAND, Bundle.EMPTY)
    }
}

/** Lo que pide la interfaz para arrancar el DJ. */
const val DJ_COMMAND = "io.github.iaguito22.oto.DJ"
const val DJ_MOOD_COMMAND = "io.github.iaguito22.oto.DJ_MOOD"
const val DJ_GENRE_COMMAND = "io.github.iaguito22.oto.DJ_GENRE"
const val DJ_SKIP_COMMAND = "io.github.iaguito22.oto.DJ_SKIP"
const val DJ_FULL_ONCE_COMMAND = "io.github.iaguito22.oto.DJ_FULL_ONCE"
const val DJ_VETO_COMMAND = "io.github.iaguito22.oto.DJ_VETO"
const val DJ_START_FROM_COMMAND = "io.github.iaguito22.oto.DJ_START_FROM"
const val EXTRA_DJ_GENRE = "genre"
/** En los extras de cada canción del DJ: la tanda que pinchaba al ponerla (ver [io.github.iaguito22.oto.dj.Dj.tanda]). */
const val DJ_TANDA = "oto_dj_tanda"
const val DJ_REASON = "oto_dj_reason"
const val SESSION_DJ_TANDA = "oto_session_dj_tanda"
const val SESSION_DJ_FULL_ONCE = "oto_session_dj_full_once"
const val SESSION_DJ_NEXT_UP = "oto_session_dj_next_up"
const val SESSION_DJ_MIXING = "oto_session_dj_mixing"
const val SESSION_DJ_MIX_START_MS = "oto_session_dj_mix_start_ms"
const val SESSION_DJ_MIX_SWITCH_MS = "oto_session_dj_mix_switch_ms"
const val SESSION_DJ_MIX_STATE = "oto_session_dj_mix_state"
const val SESSION_DJ_NEXT_STYLE = "oto_session_dj_next_style"
const val DJ_MODE_COMMAND = "io.github.iaguito22.oto.DJ_MODE"
const val DJ_REACT_COMMAND = "io.github.iaguito22.oto.DJ_REACT"
const val EXTRA_DJ_MODE = "mode"
const val EXTRA_DJ_MOOD = "mood"
const val EXTRA_DJ_TRACK_ID = "track_id"
const val EXTRA_DJ_REACTION = "reaction"
const val EXTRA_DJ_TITLE = "title"
const val EXTRA_DJ_ARTIST = "artist"
const val EXTRA_DJ_ARTISTS = "artists"
const val EXTRA_DJ_ARTIST_IDS = "artist_ids"
const val EXTRA_DJ_ARTWORK = "artwork"
const val EXTRA_DJ_SECONDS = "seconds"

internal object DjSessionCodec {
    fun encodeNextUp(picks: List<DjPickInfo>): String {
        val arr = JSONArray()
        for (p in picks) {
            val t = p.track
            val obj = JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("artists", JSONArray(t.artists))
                put("artistIds", JSONArray(t.artistIds.map { it ?: "" }))
                if (t.artwork != null) put("artwork", t.artwork)
                if (t.seconds != null) put("seconds", t.seconds)
                put("kind", p.kind.name)
                if (p.tanda != null) put("tanda", p.tanda)
                put("reason", p.reason)
                put("isBridge", p.isBridge)
                if (p.bridgeFrom != null) put("bridgeFrom", p.bridgeFrom)
                if (p.bridgeTo != null) put("bridgeTo", p.bridgeTo)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    fun decodeNextUp(raw: String?): List<DjPickInfo> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").ifEmpty { continue }
                    val artistsJson = o.optJSONArray("artists")
                    val artists = buildList {
                        if (artistsJson != null) {
                            for (j in 0 until artistsJson.length()) {
                                artistsJson.optString(j).takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                    }
                    val artistIdsJson = o.optJSONArray("artistIds")
                    val artistIds = buildList<String?> {
                        if (artistIdsJson != null) {
                            for (j in 0 until artistIdsJson.length()) {
                                add(artistIdsJson.optString(j).ifEmpty { null })
                            }
                        }
                    }
                    val track = Track(
                        id = id,
                        title = o.optString("title"),
                        artists = artists,
                        artistIds = artistIds,
                        artwork = o.optString("artwork").ifEmpty { null },
                        seconds = if (o.has("seconds")) o.optInt("seconds").takeIf { it > 0 } else null,
                    )
                    val kind = Kind.entries.firstOrNull { it.name == o.optString("kind") } ?: Kind.FAMILIAR
                    add(
                        DjPickInfo(
                            track = track,
                            kind = kind,
                            tanda = o.optString("tanda").ifEmpty { null },
                            reason = o.optString("reason"),
                            isBridge = o.optBoolean("isBridge", false),
                            bridgeFrom = o.optString("bridgeFrom").ifEmpty { null },
                            bridgeTo = o.optString("bridgeTo").ifEmpty { null },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
