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
import io.github.iaguito22.oto.MainActivity
import io.github.iaguito22.oto.OtoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
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
    private val retries = HashMap<String, Int>()
    private var retryJob: Job? = null

    private var radio: Radio? = null
    private var radioJob: Job? = null
    private val recent = ArrayDeque<String>()

    private val prefs by lazy { (application as OtoApp).prefs }
    private var liked = emptySet<String>()

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
                val queued = (0 until player.mediaItemCount).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
                val fresh = page.filter { it.id !in queued && it.id !in recent }.distinctBy { it.id }
                if (fresh.isNotEmpty()) {
                    player.addMediaItems(fresh.map { it.toMediaItem() })
                    Timber.d("radio: +%d", fresh.size)
                    return@launch
                }
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId ?: return
            retries.keys.retainAll(setOf(id))
            recent.remove(id)
            recent.addLast(id)
            while (recent.size > RECENT) recent.removeFirst()
            applyLoudness()
            topUpRadio()
            showLike()
            scope.launch(Dispatchers.IO) { prefs.addHeard(mediaItem.toHeard()) }
        }

        override fun onPlayerError(error: PlaybackException) = recover(error)
    }

    private val callback = object : MediaSession.Callback {
        // Todos los controladores (también la notificación) pueden pulsar el corazón.
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(LIKE).build())
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
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
        const val RECENT = 200
        const val QUALITY_KEY = "oto_quality"
        val LIKE = SessionCommand("io.github.iaguito22.oto.LIKE", Bundle.EMPTY)
    }
}
