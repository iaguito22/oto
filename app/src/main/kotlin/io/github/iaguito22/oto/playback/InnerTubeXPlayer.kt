package io.github.iaguito22.oto.playback

import android.content.Context
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
import com.metrolist.innertubex.extraction.PlayerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
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
    private const val PLAYER_REQUEST_TIMEOUT_MS = 30_000L
    private const val VISITOR_DATA_TIMEOUT_MS = 30_000L
    private const val CONFIG_REFRESH_MS = 60 * 60 * 1000L
    private const val CONFIG_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

    @Volatile private var appContext: Context? = null
    @Volatile private var currentBundle: Bundle? = null
    @Volatile private var prewarming = false
    private val bundleMutex = Mutex()
    private val clientFailures = ConcurrentHashMap<String, FailedClients>()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** Descarga la configuración del reproductor y el cifrado antes de la primera canción. */
    suspend fun prewarm() {
        prewarming = true
        try {
            bundle().extractor.prewarm()
        } finally {
            prewarming = false
        }
    }

    /** [tier]: la calidad que se decidió para esta canción (ver [Tier]); la misma en cada vuelta a pedirla. */
    suspend fun resolve(videoId: String, tier: Tier): Result<Stream> =
        try {
            val hints = ContentHints().withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true)
            val stream = requireNotNull(
                bundle().extractor.extract(
                    videoId = videoId,
                    hints = hints,
                    excludedClients = failedClients(videoId),
                    // HIGH: la mejor que haya (opus ~160 kbps; con Premium, más). LOW: el AAC más ligero (~48 kbps).
                    audioQuality = if (tier == Tier.Low) AudioQuality.LOW else AudioQuality.HIGH,
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

    suspend fun refreshAfterStreamRejection(): Boolean {
        // La configuración guardada puede ser la de un reproductor que YouTube ya no acepta: la próxima, de nuevo.
        configPrefs.edit().clear().apply()
        return bundle().cipher.refreshAfterStreamRejection()
    }

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
            val extractor = PatientExtractor.create(
                YtConfigParserImpl(transport.httpClient, transport.innerTube, store, logger).resilient(),
                cipher, transport.innerTube, tokenProvider, logger, PLAYER_REQUEST_TIMEOUT_MS, VISITOR_DATA_TIMEOUT_MS,
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
        override val capabilities: TokenProviderCapabilities
            get() = if (prewarming) {
                TokenProviderCapabilities(providers = emptySet(), usesWebView = false)
            } else {
                TokenProviderCapabilities(
                    providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
                    usesWebView = true,
                )
            }

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
        val quality: Quality,
        /** El formato exacto: si al volver a pedirla a mitad de canción llega otro, lo guardado ya no le sirve. */
        val itag: Int,
        /** Con cuenta, a esta URL se avisa de que ha sonado: así cuenta en el historial de YouTube. */
        val tracking: String?,
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
        quality = Quality(
            codec = codecs?.substringBefore('.')?.ifBlank { null } ?: mimeType?.substringAfter('/')?.substringBefore(';') ?: "audio",
            bitrate = bitrate,
            sampleRate = sampleRate,
        ),
        itag = itag,
        tracking = playbackTracking?.playbackUrl,
    )

    private data class Bundle(val generation: Long, val cipher: YouTubeCipherService, val extractor: InnerTubeExtractor)

    private data class FailedClients(val names: Set<String>, val at: Long)

    /**
     * La configuración del reproductor de YouTube, guardada: la última buena se usa al momento (y se renueva aparte si
     * tiene más de [CONFIG_REFRESH_MS]). Pedirla cuesta una página entera y InnerTubeX le da 15 s: con poca cobertura
     * no llegaba nunca y ninguna canción empezaba. Si YouTube rechaza una URL, se tira (ver [refreshAfterStreamRejection]).
     */
    private fun YtConfigParser.resilient(): YtConfigParser = object : YtConfigParser by this {
        override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig {
            val saved = savedConfig(useLoginCookies)
            if (saved != null && System.currentTimeMillis() - saved.second < CONFIG_MAX_AGE_MS) {
                if (System.currentTimeMillis() - saved.second > CONFIG_REFRESH_MS && refreshing.compareAndSet(false, true)) {
                    background.launch {
                        try { runCatching { live(videoId, useLoginCookies) }.onFailure { Timber.tag(TAG).w(it, "No se pudo renovar la configuración") } }
                        finally { refreshing.set(false) }
                    }
                }
                return saved.first
            }
            return try {
                live(videoId, useLoginCookies)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saved?.first ?: throw e
            }
        }

        private suspend fun live(videoId: String, useLoginCookies: Boolean): PlayerConfig =
            try {
                this@resilient.fetchConfig(videoId, useLoginCookies)
            } catch (_: IllegalStateException) {
                this@resilient.fetchEmbeddedConfig(videoId, useLoginCookies = false)
            }.also { saveConfig(useLoginCookies, it) }
    }

    private val refreshing = AtomicBoolean(false)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configPrefs by lazy {
        requireNotNull(appContext) { "InnerTubeXPlayer sin inicializar" }.getSharedPreferences("oto_player_config", Context.MODE_PRIVATE)
    }

    private fun savedConfig(login: Boolean): Pair<PlayerConfig, Long>? = runCatching {
        val o = JSONObject(configPrefs.getString(configKey(login), null) ?: return null)
        PlayerConfig(
            playerUrl = o.getString("playerUrl"),
            signatureTimestamp = if (o.has("sts")) o.getInt("sts") else null,
            visitorData = o.optString("visitorData").ifEmpty { null },
            clientVersion = o.optString("clientVersion").ifEmpty { null },
            encryptedHostFlags = o.optString("hostFlags").ifEmpty { null },
        ) to o.getLong("at")
    }.getOrNull()

    private fun saveConfig(login: Boolean, c: PlayerConfig) {
        val o = JSONObject().put("playerUrl", c.playerUrl).put("at", System.currentTimeMillis())
            .put("visitorData", c.visitorData.orEmpty()).put("clientVersion", c.clientVersion.orEmpty()).put("hostFlags", c.encryptedHostFlags.orEmpty())
        c.signatureTimestamp?.let { o.put("sts", it) }
        configPrefs.edit().putString(configKey(login), o.toString()).apply()
    }

    private fun configKey(login: Boolean) = if (login) "login" else "visitor"

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
