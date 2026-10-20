package io.github.iaguito22.oto.playback

import android.content.Context
import android.net.ConnectivityManager
import com.metrolist.innertube.YouTube
import com.metrolist.innertubex.InnerTubeLogEvent
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import io.github.iaguito22.oto.playback.potoken.PoTokenGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

/**
 * Único punto de extracción de streams. Adaptado de `InnerTubeXPlayer` de Metrolist (GPL-3.0):
 * pide a InnerTubeX solo URL directas (sin HLS ni SABR), que es lo que ExoPlayer reproduce de forma progresiva.
 */
object InnerTubeXPlayer {
    private const val TAG = "InnerTubeX"
    private const val CLIENT_FAILURE_TTL_MS = 5 * 60 * 1000L
    private const val DEFAULT_STREAM_TTL_SECONDS = 5 * 60

    @Volatile private var appContext: Context? = null
    @Volatile private var currentBundle: Bundle? = null
    private val bundleMutex = Mutex()
    private val clientFailures = ConcurrentHashMap<String, FailedClients>()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** Descarga la configuración del reproductor y el cifrado antes de la primera canción. */
    suspend fun prewarm() = bundle().extractor.prewarm()

    suspend fun resolve(videoId: String, connectivity: ConnectivityManager): Result<Stream> =
        try {
            val hints = ContentHints().withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true)
            val stream = requireNotNull(
                bundle().extractor.extract(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = failedClients(videoId),
                    // Con datos móviles, calidad baja; con wifi, la mejor que haya.
                    audioQuality = if (connectivity.isActiveNetworkMetered) AudioQuality.LOW else AudioQuality.AUTO,
                    clientPlaybackNonce = generateClientPlaybackNonce(),
                ),
            ) { "InnerTubeX no devolvió ningún stream" }
            check(stream.sabrBootstrap == null) { "SABR no está soportado" }
            Result.success(stream.toStream())
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamResolveException) {
            val cause = e.cause
            Result.failure(if (e.reason == StreamResolveException.Reason.NETWORK && cause != null) cause else e)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** El cliente que dio esta URL la vio rechazada: se salta durante unos minutos para este vídeo. */
    fun markClientFailed(videoId: String, client: String) {
        clientFailures.compute(videoId) { _, f -> FailedClients(f?.names.orEmpty() + client, System.currentTimeMillis()) }
    }

    fun clearClientFailures() = clientFailures.clear()

    suspend fun refreshAfterStreamRejection(): Boolean = bundle().cipher.refreshAfterStreamRejection()

    private fun failedClients(videoId: String): Set<String> {
        val f = clientFailures[videoId] ?: return emptySet()
        if (System.currentTimeMillis() - f.at !in 0 until CLIENT_FAILURE_TTL_MS) {
            clientFailures.remove(videoId, f)
            return emptySet()
        }
        return f.names
    }

    // El transporte de YouTube cambia de generación al cambiar de cuenta o de visitorData: entonces se rehace todo.
    private suspend fun bundle(): Bundle {
        YouTube.extractionTransport().let { t -> currentBundle?.takeIf { it.generation == t.generation }?.let { return it } }
        return bundleMutex.withLock {
            val transport = YouTube.extractionTransport()
            currentBundle?.takeIf { it.generation == transport.generation }?.let { return@withLock it }
            try {
                currentBundle?.cipher?.dispose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "No se pudo liberar el cifrado anterior")
            }
            val store = RemotePlayerConfigStore(transport.httpClient, configRepository, logger)
            val cipher = YouTubeCipherService(transport.httpClient, store, logger)
            val extractor = InnerTubeExtractor(
                configParser = YtConfigParserImpl(transport.httpClient, transport.innerTube, store, logger).withEmbeddedFallback(),
                cipherService = cipher,
                innerTube = transport.innerTube,
                tokenProvider = tokenProvider,
                logger = logger,
            )
            Bundle(transport.generation, cipher, extractor).also { currentBundle = it }
        }
    }

    private val configRepository: PlayerConfigRepository by lazy {
        PrefsPlayerConfigRepository(requireNotNull(appContext) { "InnerTubeXPlayer sin inicializar" })
    }

    private val poTokenGenerator by lazy {
        PoTokenGenerator(requireNotNull(appContext) { "InnerTubeXPlayer sin inicializar" })
    }

    private val tokenProvider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
            usesWebView = true,
        )

        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? =
            poTokenGenerator.getWebClientPoToken(videoId, visitorData)?.let {
                PoTokenResult(
                    playerRequestToken = it.playerRequestPoToken,
                    streamingDataToken = it.streamingDataPoToken,
                    visitorData = visitorData,
                )
            }

        override suspend fun close() = poTokenGenerator.close()
    }

    private val logger = InnerTubeLogger { e: InnerTubeLogEvent ->
        val details = if (e.details.isEmpty()) "" else e.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
        val t = Timber.tag(e.tag)
        when (e.level) {
            InnerTubeLogLevel.DEBUG -> t.d(e.message + details)
            InnerTubeLogLevel.INFO -> t.i(e.message + details)
            InnerTubeLogLevel.WARN -> t.w(e.message + details)
            InnerTubeLogLevel.ERROR -> t.e(e.message + details)
        }
    }

    class Stream(
        val url: String,
        val headers: Map<String, String>,
        val client: String,
        val expiresInSeconds: Int,
        val requireBoundedRange: Boolean,
        val rangeChunkSizeBytes: Long,
        val useRangeChunks: Boolean,
        val loudnessDb: Double?,
        val contentLength: Long?,
    )

    private fun ExtractedStream.toStream() = Stream(
        url = audioUrl,
        headers = headers,
        client = clientName,
        expiresInSeconds = expiresAt
            ?.let { ((it.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()) / 1000L).toInt() }
            ?.coerceAtLeast(1)
            ?: DEFAULT_STREAM_TTL_SECONDS,
        requireBoundedRange = requireBoundedRange,
        rangeChunkSizeBytes = rangeChunkSizeBytes,
        useRangeChunks = useRangeChunks,
        loudnessDb = loudnessDb,
        contentLength = contentLengthBytes,
    )

    private data class Bundle(val generation: Long, val cipher: YouTubeCipherService, val extractor: InnerTubeExtractor)

    private data class FailedClients(val names: Set<String>, val at: Long)

    private fun YtConfigParser.withEmbeddedFallback(): YtConfigParser = object : YtConfigParser by this {
        override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
            try {
                this@withEmbeddedFallback.fetchConfig(videoId, useLoginCookies)
            } catch (_: IllegalStateException) {
                this@withEmbeddedFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
            }
    }

    private class PrefsPlayerConfigRepository(context: Context) : PlayerConfigRepository {
        private val prefs = context.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)

        override val enabled = true
        override val sourceUrl = URL
        override val defaultSourceUrl = URL
        override var cachedJson: String
            get() = prefs.getString("json", "").orEmpty()
            set(v) = prefs.edit().putString("json", v).apply()
        override var cachedAtMs: Long
            get() = prefs.getLong("cached_at_ms", 0L)
            set(v) = prefs.edit().putLong("cached_at_ms", v).apply()
        override var cachedSourceUrl: String
            get() = prefs.getString("source_url", "").orEmpty()
            set(v) = prefs.edit().putString("source_url", v).apply()
        override var cachedEtag: String
            get() = prefs.getString("etag", "").orEmpty()
            set(v) = prefs.edit().putString("etag", v).apply()

        private companion object {
            const val URL = "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
        }
    }
}
