package io.github.iaguito22.oto.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
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
import androidx.media3.exoplayer.audio.AudioSink
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
import io.github.iaguito22.oto.MainActivity
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.QualityMode
import io.github.iaguito22.oto.dj.Dj
import io.github.iaguito22.oto.dj.Kind
import io.github.iaguito22.oto.dj.Memory
import io.github.iaguito22.oto.dj.Play
import io.github.iaguito22.oto.dj.Profile
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
import timber.log.Timber
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
    private val retries = HashMap<String, Int>()
    private var retryJob: Job? = null

    private var radio: Radio? = null
    private var radioJob: Job? = null
    // Lo que suena es una lista (un álbum, una tuya…) y no la radio de una canción ni el DJ.
    private var list = false
    // Lo que sigue a una lista acabada (ver [Autoplay]): lo que añade va marcado (ver [markAuto]).
    private var auto: Autoplay? = null
    // Lo último que sonó (también en sesiones anteriores, desde el historial): la radio no lo vuelve a poner.
    // Por id y por título + artista, porque la misma canción suele estar como canción y como vídeo.
    private val recent = ArrayDeque<String>()

    private val app by lazy { application as OtoApp }
    private val library by lazy { Library(prefs) }
    /** La última búsqueda desde Android Auto: la pide en dos pasos (cuántos hay, luego cuáles). */
    private var found: Pair<String, List<MediaItem>> = "" to emptyList()
    private val prefs by lazy { app.prefs }
    private var liked = emptySet<String>()

    private var dj: Dj? = null
    private var djJob: Job? = null
    // Lo que dura cada una, para saber qué parte se oyó al pasar a otra.
    private val durations = HashMap<String, Long>()

    private lateinit var crossfade: Crossfade
    @Volatile private var fadeMs = 0L
    // El volumen es el de la normalización de cada canción ([gain]) por lo que lleva subido en un fundido ([fadeIn]).
    private var gain = 1f
    private var fadeIn = 1f

    override fun onCreate() {
        super.onCreate()
        InnerTubeXPlayer.initialize(this)
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
        crossfade = Crossfade(this, player, sources, scope, { fadeMs }, { gain }, onFadeIn = { fadeIn = it; applyVolume() })
        crossfade.start()

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
        scope.launch { prefs.crossfade.collect { fadeMs = it * 1000L } }

        // El corazón de la notificación sigue a Prefs: lo cambie quien lo cambie, aquí se ve.
        scope.launch { prefs.likedIds.collect { liked = it; showLike() } }

        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Si suena, sigue sonando con la app cerrada; si no, no queda nada vivo.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        connectivity.unregisterNetworkCallback(networkCallback)
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
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            AudioLevels.Sink(super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)!!)
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
                if (app.downloads.has(id)) continue
                runCatching {
                    val spec = resolve(DataSpec.Builder().setUri(id).setKey(id).setLength(HEAD_BYTES).build())
                    // Sin URL nueva: ya estaba entera en la caché.
                    if (spec.uri.toString() == id) return@runCatching
                    CacheWriter(headSource.createDataSource(), spec, null, null).also { writer = it }.cache()
                }.onFailure { Timber.w(it, "precarga %s", id) }
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
            tiers[id] = Tier.Low
            // Otra clave de caché = otra fuente: ExoPlayer la vuelve a preparar (la alta a medias se queda en la caché).
            player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setCustomCacheKey(Tier.Low.key(id)).build())
        }
    }

    /** YouTube normaliza bajando las canciones que suenan por encima de su referencia, nunca subiendo. */
    private fun applyLoudness() {
        val db = player.currentMediaItem?.mediaId?.let(loudness::get)
        gain = if (db == null || db <= 0) 1f else 10f.pow(-db.toFloat() / 20f)
        applyVolume()
    }

    private fun applyVolume() {
        player.volume = gain * fadeIn
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
        val on = player.currentMediaItem?.mediaId?.let { it in liked } ?: return
        session.setMediaButtonPreferences(
            listOf(
                CommandButton.Builder(if (on) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setDisplayName(if (on) "Quitar de Me gusta" else "Me gusta")
                    .setSessionCommand(LIKE)
                    .build(),
            ),
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
        if (YouTube.cookie.isNullOrEmpty()) return
        historyJob = scope.launch {
            while (player.currentMediaItem?.mediaId == id && player.currentPosition < HEARD_MS) delay(2_000)
            if (player.currentMediaItem?.mediaId != id) return@launch
            val url = tracking[id] ?: withContext(Dispatchers.IO) { InnerTubeXPlayer.resolve(id, tiers[id] ?: Tier.Low) }.getOrNull()?.tracking ?: return@launch
            withContext(Dispatchers.IO) { YouTube.registerPlayback(null, url) }
                .onSuccess { Timber.d("historial: %s", id) }
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

    /** Arranca el DJ (ver [Dj]): la primera suena al momento y el resto se elige de una en una, sobre la marcha. */
    private fun startDj(): ListenableFuture<SessionResult> {
        val done = SettableFuture.create<SessionResult>()
        firstOfDj { first ->
            if (first == null) {
                done.set(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                return@firstOfDj
            }
            player.setMediaItems(listOf(first))
            player.prepare()
            player.play()
            done.set(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        return done
    }

    /** Prepara el DJ y da su primera canción (null si no hay de dónde sacarla); quien la recibe la pone. */
    private fun firstOfDj(then: (MediaItem?) -> Unit) {
        stopRadio()
        list = false
        djJob?.cancel()
        djJob = scope.launch {
            val d = withContext(Dispatchers.Default) {
                val lists = prefs.playlists.first()
                val liked = prefs.liked.first()
                val history = prefs.history.first()
                val plays = app.plays.all()
                val profile = traced("oto.dj.profile") { Profile(lists, liked, history, plays, System.currentTimeMillis()) }
                Dj(profile, app.djCatalog, Memory(File(filesDir, "dj.json")), scope, log = { Timber.tag("dj").d(it) })
            }
            val first = d.start() ?: return@launch then(null)
            dj = d
            Timber.tag("dj").d("%s: %s — %s", d.picks.last().kind, first.artist, first.title)
            then(first.toDjItem(d))
        }
        djJob?.invokeOnCompletion { scope.launch { topUpDj() } }
    }

    /** Un future que se cumple con lo que devuelva [block], en el hilo principal. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch { runCatching { block() }.onSuccess { f.set(it) }.onFailure { f.setException(it) } }
        return f
    }

    private fun stopDj() {
        djJob?.cancel()
        dj = null
    }

    /** Siempre una por delante: lo justo para que no haya silencio y la siguiente ya sepa lo que pasaste. */
    private fun topUpDj() {
        val d = dj ?: return
        if (djJob?.isActive == true || player.mediaItemCount - player.currentMediaItemIndex - 1 >= 1) return
        djJob = scope.launch {
            val t = d.next() ?: return@launch
            if (dj !== d) return@launch
            player.addMediaItem(t.toDjItem(d))
            Timber.tag("dj").d("%s: %s — %s", d.picks.last().kind, t.artist, t.title)
        }
    }

    private fun Track.toDjItem(d: Dj): MediaItem {
        val item = toSong().toMediaItem()
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply { putString(DJ_KIND, d.picks.last().kind.name) }
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    /** Lo que se oyó de [item] antes de pasar a otra: al diario (de lo que aprende el DJ) y, si es suya, al DJ. */
    private fun logPlay(item: MediaItem, ms: Long) {
        val dur = durations[item.mediaId] ?: item.mediaMetadata.durationMs ?: 0L
        val kind = item.mediaMetadata.extras?.getString(DJ_KIND)?.let { k -> Kind.entries.firstOrNull { it.name == k } }
        if (kind == null && ms < 1_000) return
        val h = item.toHeard()
        val play = Play(h.id, h.title, h.artist, h.artistId, System.currentTimeMillis(), ms, dur, kind)
        scope.launch(Dispatchers.IO) { app.plays.add(play) }
        if (kind != null) dj?.heard(item.mediaId, ms, dur)
        if (item.isAuto) auto?.heard(item.mediaId, h.artist, ms, dur)
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
            delay(LYRICS_DELAY_MS)
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
            // Lo que ya no está en la cola vuelve a decidir su calidad la próxima vez; lo que sigue en ella, no (puede
            // estar a medio cargar).
            tiers.keys.retainAll((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet())
            stallJob?.cancel()
            if (player.playbackState == Player.STATE_BUFFERING) watchStart()
            durations.keys.retainAll(setOf(id) + (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet())
            remember(id, sameSong(mediaItem.mediaMetadata.title?.toString().orEmpty(), mediaItem.mediaMetadata.artist?.toString().orEmpty()))
            applyLoudness()
            topUpRadio()
            autoplay()
            topUpDj()
            prefetch()
            countInHistory(id)
            showLike()
            scope.launch(Dispatchers.IO) { prefs.addHeard(mediaItem.toHeard()) }
        }

        override fun onPlayerError(error: PlaybackException) = recover(error)

        override fun onRepeatModeChanged(repeatMode: Int) {
            if (repeatMode == Player.REPEAT_MODE_OFF) autoplay() else dropAutoplay()
        }

        // Quitar o mover canciones de la cola cambia el índice de la que suena sin pasar a otra.
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            playingIndex = player.currentMediaItemIndex
            prefetch()
        }

        override fun onPlaybackStateChanged(state: Int) {
            val id = player.currentMediaItem?.mediaId ?: return
            if (state == Player.STATE_BUFFERING && player.currentPosition <= STARTED_MS) watchStart() else if (state != Player.STATE_BUFFERING) stallJob?.cancel()
            if (state == Player.STATE_READY && player.duration > 0) {
                durations[id] = player.duration
                fetchLyrics()
            }
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            val item = old.mediaItem ?: return
            // Moverse dentro de la misma no es dejarla; volver a empezarla sola (repetir una), sí. Cambiarla por ella
            // misma en baja ([watchStart]) tampoco.
            if (item.mediaId == new.mediaItem?.mediaId && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
            logPlay(item, old.positionMs)
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
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(LIKE).add(DJ).build())
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == DJ.customAction) return startDj()
            if (customCommand.customAction != LIKE.customAction) return super.onCustomCommand(session, controller, customCommand, args)
            toggleLike()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
            val one = mediaItems.singleOrNull()
            // Desde Android Auto: el DJ, una canción de una lista (suena la lista desde ella) o lo pedido por voz.
            if (one != null && one.mediaId == Library.DJ) {
                val f = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                firstOfDj { first ->
                    if (first == null) f.setException(IllegalStateException("El DJ no tiene de dónde sacar"))
                    else f.set(MediaSession.MediaItemsWithStartPosition(listOf(first), 0, 0))
                }
                return f
            }
            if (one != null && (Library.inList(one.mediaId) != null || one.mediaId.isEmpty())) return future {
                val expanded = library.expand(one)
                if (expanded != null) {
                    stopDj(); stopRadio()
                    list = true
                    autoplay()
                    MediaSession.MediaItemsWithStartPosition(expanded.first.map { it.withPlaybackUri() }, expanded.second, 0)
                } else {
                    val found = resolve(one).map { it.withPlaybackUri() }
                    stopDj()
                    list = false
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
        const val RADIO_AHEAD = 2
        const val RADIO_PAGES = 3
        const val RECENT = 400
        const val HEARD_MS = 30_000L
        const val STALL_MS = 6_000L
        const val STARTED_MS = 1_000L
        const val AHEAD = 3
        const val PREFETCH_DELAY_MS = 4_000L
        const val LYRICS_DELAY_MS = 2_000L
        // Un trozo ([ChunkedDataSource]): casi un minuto en alta, de sobra para que empiece al momento.
        const val HEAD_BYTES = ChunkedDataSource.CHUNK_BYTES
        const val DJ_KIND = "oto_dj"
        val LIKE = SessionCommand("io.github.iaguito22.oto.LIKE", Bundle.EMPTY)
        val DJ = SessionCommand(DJ_COMMAND, Bundle.EMPTY)
    }
}

/** Lo que pide la interfaz para arrancar el DJ. */
const val DJ_COMMAND = "io.github.iaguito22.oto.DJ"
