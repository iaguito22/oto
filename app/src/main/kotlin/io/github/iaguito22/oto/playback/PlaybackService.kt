package io.github.iaguito22.oto.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.iaguito22.oto.MainActivity
import com.metrolist.innertube.YouTube
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.dj.Dj
import io.github.iaguito22.oto.dj.Kind
import io.github.iaguito22.oto.dj.Memory
import io.github.iaguito22.oto.dj.Play
import io.github.iaguito22.oto.dj.Profile
import io.github.iaguito22.oto.dj.Track
import io.github.iaguito22.oto.importer.songKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val cache by lazy { (application as OtoApp).audioCache }

    private val urls = StreamUrlCache()
    private val loudness = ConcurrentHashMap<String, Double>()
    private val tracking = ConcurrentHashMap<String, String>()
    private var historyJob: Job? = null
    private val retries = HashMap<String, Int>()
    private var retryJob: Job? = null

    private var radio: Radio? = null
    private var radioJob: Job? = null
    // Lo último que sonó (también en sesiones anteriores, desde el historial): la radio no lo vuelve a poner.
    // Por id y por título + artista, porque la misma canción suele estar como canción y como vídeo.
    private val recent = ArrayDeque<String>()

    private val app by lazy { application as OtoApp }
    private val prefs by lazy { app.prefs }
    private var liked = emptySet<String>()

    private var dj: Dj? = null
    private var djJob: Job? = null
    // Lo que dura cada una, para saber qué parte se oyó al pasar a otra.
    private val durations = HashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        InnerTubeXPlayer.initialize(this)
        player = ExoPlayer.Builder(this, renderersFactory())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Abre y llena el búfer de la siguiente canción antes de que acabe esta: sin silencio entre temas.
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration(PRELOAD_US)
        player.addListener(listener)

        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setCallback(callback)
            .build()

        scope.launch {
            val heard = withContext(Dispatchers.IO) { prefs.history.first() }
            heard.asReversed().forEach { remember(it.id, sameSong(it.title, it.artist)) }
        }

        // El corazón de la notificación sigue a Prefs: lo cambie quien lo cambie, aquí se ve.
        scope.launch { prefs.likedIds.collect { liked = it; showLike() } }

        connectivity.registerDefaultNetworkCallback(networkCallback)
        scope.launch(Dispatchers.IO) {
            runCatching { InnerTubeXPlayer.prewarm() }.onFailure { Timber.w(it, "prewarm") }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Si suena, sigue sonando con la app cerrada; si no, no queda nada vivo.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        connectivity.unregisterNetworkCallback(networkCallback)
        scope.cancel()
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
        val cached = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(OkHttpClient()))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ResolvingDataSource.Factory(cached, ::resolve)
    }

    /** Se llama en el hilo de carga cada vez que ExoPlayer abre un trozo del audio. */
    private fun resolve(spec: DataSpec): DataSpec {
        val id = spec.key ?: return spec
        // Entera en la caché: no hace falta ni pedir la URL. Si solo hay un trozo, sí (el resto viene de la red).
        val metadata = cache.getContentMetadata(id)
        val length = ContentMetadata.getContentLength(metadata)
        Quality.decode(metadata.get(QUALITY_KEY, null as String?))?.let { StreamInfo.put(id, it) }
        if (length > 0 && cache.isCached(id, spec.position, length - spec.position)) return spec

        urls[id]?.let { return spec.withResolvedStream(it) }

        val generation = urls.generation(id)
        val started = System.nanoTime()
        val stream = runBlocking(Dispatchers.IO) { InnerTubeXPlayer.resolve(id, connectivity) }
            .getOrElse { throw ResolveException(id, it) }
        Timber.d("stream %s en %d ms (%s)", id, (System.nanoTime() - started) / 1_000_000, stream.client)

        StreamInfo.put(id, stream.quality)
        stream.tracking?.let { tracking[id] = it }
        runCatching { cache.applyContentMetadataMutations(id, ContentMetadataMutations().set(QUALITY_KEY, stream.quality.encode())) }
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
            mediaId = id,
            url = resolved.url,
            requestHeaders = resolved.requestHeaders,
            clientName = resolved.clientName,
            expiresInSeconds = stream.expiresInSeconds,
            requireBoundedRange = resolved.requireBoundedRange,
            rangeChunkSizeBytes = resolved.rangeChunkSizeBytes,
            useRangeChunks = resolved.useRangeChunks,
            expectedGeneration = generation,
        )
        return spec.withResolvedStream(resolved)
    }

    /** YouTube normaliza bajando las canciones que suenan por encima de su referencia, nunca subiendo. */
    private fun applyLoudness() {
        val db = player.currentMediaItem?.mediaId?.let(loudness::get)
        player.volume = if (db == null || db <= 0) 1f else 10f.pow(-db.toFloat() / 20f)
    }

    // --- Errores ---

    private fun recover(error: PlaybackException) {
        val id = player.currentMediaItem?.mediaId ?: return
        val network = error.isNetwork()
        val attempt = (retries[id] ?: 0) + 1
        retries[id] = attempt
        Timber.w(error, "error %s (%s), intento %d", id, error.errorCodeName, attempt)

        val failedClient = urls.clientName(id)
        urls.invalidate(id)
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
            val url = tracking[id] ?: withContext(Dispatchers.IO) { InnerTubeXPlayer.resolve(id, connectivity) }.getOrNull()?.tracking ?: return@launch
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
    }

    /** Cuando quedan [RADIO_AHEAD] canciones o menos por delante, añade la siguiente página de la radio. */
    private fun topUpRadio() {
        if (radio == null || radioJob?.isActive == true) return
        if (player.mediaItemCount - player.currentMediaItemIndex - 1 > RADIO_AHEAD) return
        radioJob = scope.launch {
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
        stopRadio()
        djJob?.cancel()
        djJob = scope.launch {
            val d = withContext(Dispatchers.Default) {
                val profile = Profile(prefs.playlists.first(), prefs.liked.first(), prefs.history.first(), app.plays.all(), System.currentTimeMillis())
                Dj(profile, app.djCatalog, Memory(File(filesDir, "dj.json")), scope, log = { Timber.tag("dj").d(it) })
            }
            val first = d.start()
            if (first == null) {
                done.set(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                return@launch
            }
            dj = d
            Timber.tag("dj").d("%s: %s — %s", d.picks.last().kind, first.artist, first.title)
            player.setMediaItems(listOf(first.toDjItem(d)))
            player.prepare()
            player.play()
            done.set(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        djJob?.invokeOnCompletion { scope.launch { topUpDj() } }
        return done
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
    }

    private fun remember(vararg keys: String) {
        for (k in keys) {
            recent.remove(k)
            recent.addLast(k)
        }
        while (recent.size > RECENT) recent.removeFirst()
    }

    private fun sameSong(title: String, artist: String) = "~" + songKey(title, artist)

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId ?: return
            retries.keys.retainAll(setOf(id))
            durations.keys.retainAll(setOf(id) + (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet())
            remember(id, sameSong(mediaItem.mediaMetadata.title?.toString().orEmpty(), mediaItem.mediaMetadata.artist?.toString().orEmpty()))
            applyLoudness()
            topUpRadio()
            topUpDj()
            countInHistory(id)
            showLike()
            scope.launch(Dispatchers.IO) { prefs.addHeard(mediaItem.toHeard()) }
        }

        override fun onPlayerError(error: PlaybackException) = recover(error)

        override fun onPlaybackStateChanged(state: Int) {
            val id = player.currentMediaItem?.mediaId ?: return
            if (state == Player.STATE_READY && player.duration > 0) durations[id] = player.duration
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            val item = old.mediaItem ?: return
            // Moverse dentro de la misma no es dejarla; volver a empezarla sola (repetir una), sí.
            if (item.mediaId == new.mediaItem?.mediaId && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
            logPlay(item, old.positionMs)
        }
    }

    private val callback = object : MediaSession.Callback {
        // Todos los controladores (también la notificación) pueden pulsar el corazón.
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(LIKE).add(DJ).build())
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

        // Los MediaItem que llegan de un controlador pierden la URI por el camino.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapTo(ArrayList()) { it.withPlaybackUri() })

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val items = mediaItems.map { it.withPlaybackUri() }
            // Poner una sola canción es pedir su radio detrás; una lista (álbum, playlist) suena tal cual.
            // Se lanza después de que la sesión aplique la cola nueva.
            stopDj()
            if (items.size == 1) scope.launch(Dispatchers.Main) { startRadio(items[0].mediaId) } else stopRadio()
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs))
        }
    }

    private class ResolveException(id: String, cause: Throwable) : IOException("No se pudo resolver $id: ${cause.message}", cause) {
        val network = generateSequence(cause) { it.cause }.any { it is IOException && it !is ResolveException }
    }

    private fun PlaybackException.isNetwork(): Boolean =
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            generateSequence<Throwable>(this) { it.cause }.any { it is ResolveException && it.network }

    private companion object {
        const val PRELOAD_US = 30_000_000L
        const val MAX_RETRIES = 3
        const val RADIO_AHEAD = 2
        const val RADIO_PAGES = 3
        const val RECENT = 400
        const val HEARD_MS = 30_000L
        const val QUALITY_KEY = "oto_quality"
        const val DJ_KIND = "oto_dj"
        val LIKE = SessionCommand("io.github.iaguito22.oto.LIKE", Bundle.EMPTY)
        val DJ = SessionCommand(DJ_COMMAND, Bundle.EMPTY)
    }
}

/** Lo que pide la interfaz para arrancar el DJ. */
const val DJ_COMMAND = "io.github.iaguito22.oto.DJ"
