package com.metrolist.innertube

import com.metrolist.innertube.models.MediaInfo
import com.metrolist.innertube.models.ReturnYouTubeDislikeResponse
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertube.models.response.NextResponse
import com.metrolist.innertubex.InnerTube as InnerTubeX
import com.metrolist.innertubex.InnerTubeHttpException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.userAgent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.metrolist.innertubex.models.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Compatibility facade that keeps Metrolist's parsed response models while InnerTubeX owns
 * YouTube request construction, session handling, retries, and authenticated mutations.
 */
class InnerTube(
    client: HttpClient? = null,
) {
    private var configuredProxy: Proxy? = null
    private var configuredProxyAuth: String? = null
    private var httpClient = client ?: createClient()
    private var innerTubeX = InnerTubeX(httpClient)
    private var transportGeneration = 0L

    class ExtractionTransport internal constructor(
        val innerTube: InnerTubeX,
        val httpClient: HttpClient,
        val generation: Long,
    )

    var locale: YouTubeLocale
        get() = innerTubeX.locale
        set(value) {
            innerTubeX.locale = value
        }

    var visitorData: String?
        get() = innerTubeX.visitorData
        set(value) {
            innerTubeX.visitorData = value
        }

    var dataSyncId: String?
        get() = innerTubeX.dataSyncId
        set(value) {
            innerTubeX.dataSyncId = value
        }

    var authUser: String
        get() = innerTubeX.authUser
        set(value) {
            innerTubeX.authUser = value
        }

    var cookie: String?
        get() = innerTubeX.cookie
        set(value) {
            innerTubeX.cookie = value
        }

    var proxy: Proxy?
        get() = configuredProxy
        set(value) {
            if (configuredProxy == value) return
            configuredProxy = value
            recreateTransport()
        }

    var proxyAuth: String?
        get() = configuredProxyAuth
        set(value) {
            if (configuredProxyAuth == value) return
            configuredProxyAuth = value
            if (configuredProxy != null) recreateTransport()
        }

    var useLoginForBrowse: Boolean
        get() = innerTubeX.useLoginForBrowse
        set(value) {
            innerTubeX.useLoginForBrowse = value
        }

    @Synchronized
    private fun recreateTransport() {
        val session = innerTubeX.sessionSnapshot()
        innerTubeX.close()
        httpClient.close()
        httpClient = createClient()
        innerTubeX =
            InnerTubeX(httpClient).also { replacement ->
                replacement.locale = session.locale
                replacement.replaceSession(
                    cookie = session.cookie,
                    visitorData = session.visitorData,
                    dataSyncId = session.dataSyncId,
                    authUser = session.authUser,
                    useLoginForBrowse = session.useLoginForBrowse,
                )
                replacement.regionOverrideActive = session.regionOverrideActive
            }
        transportGeneration++
    }

    @Synchronized
    fun extractionTransport(): ExtractionTransport =
        ExtractionTransport(
            innerTube = innerTubeX,
            httpClient = httpClient,
            generation = transportGeneration,
        )

    @OptIn(ExperimentalSerializationApi::class)
    private fun createClient() =
        HttpClient(OkHttp) {
            // InnerTubeX handles endpoint-specific status validation and transient retries.
            expectSuccess = false

            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }

            install(ContentEncoding) {
                gzip(0.9F)
                deflate(0.8F)
            }

            engine {
                config {
                    connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(60, TimeUnit.SECONDS)
                    writeTimeout(60, TimeUnit.SECONDS)
                    protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                    retryOnConnectionFailure(true)
                    cache(okhttp3.Cache(File(System.getProperty("java.io.tmpdir"), "http_cache"), 50L * 1024L * 1024L))
                    configuredProxy?.let(::proxy)
                    configuredProxyAuth?.let { auth ->
                        proxyAuthenticator { _, response ->
                            response.request
                                .newBuilder()
                                .header("Proxy-Authorization", auth)
                                .build()
                        }
                    }
                }
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
            }

            defaultRequest {
                url("https://music.youtube.com/youtubei/v1/")
                header("Accept", "application/json")
                header("Cache-Control", "no-cache")
            }
        }

    suspend fun search(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ) = hedged { innerTubeX.search(client, query, params, continuation, setLogin = setLogin) }

    suspend fun player(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String? = null,
    ) = innerTubeX.player(client, videoId, playlistId, signatureTimestamp, poToken)

    suspend fun registerPlayback(
        url: String,
        cpn: String,
        playlistId: String?,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ) = innerTubeX.registerPlayback(client, url, cpn, playlistId).requireSuccess("registerPlayback")

    suspend fun browse(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ) = hedged { innerTubeX.browse(client, browseId, params, continuation, setLogin) }

    /**
     * Oto: YouTube contesta a veces una lectura (browse, search, next…: 1 de cada ~11, medido; también con curl y
     * conexiones nuevas) con un 403 que tarda 5-10 s en llegar, cuando lo normal son 0,2-0,3 s y repetirla sale bien.
     * Mientras no haya una respuesta buena, cada [HEDGE_MS] (o en cuanto falla una) sale otra igual, hasta
     * [HEDGE_TRIES]; vale la primera que salga bien y las demás se cancelan. Si fallan todas, el error de la última.
     */
    private suspend fun <T> hedged(block: suspend () -> T): T = coroutineScope {
        val pending = mutableListOf(async { runCatching { block() } })
        var tries = 1
        var last: Result<T>? = null
        while (pending.isNotEmpty()) {
            val done = select<Pair<Deferred<Result<T>>, Result<T>>?> {
                pending.forEach { d -> d.onAwait { d to it } }
                if (tries < HEDGE_TRIES) onTimeout(HEDGE_MS) { null }
            }
            if (done != null) {
                pending -= done.first
                if (done.second.isSuccess) {
                    pending.forEach { it.cancel() }
                    return@coroutineScope done.second.getOrThrow()
                }
                last = done.second
            }
            if (tries < HEDGE_TRIES) {
                pending += async { runCatching { block() } }
                tries++
            }
        }
        last!!.getOrThrow()
    }

    suspend fun next(
        client: YouTubeClient,
        videoId: String?,
        playlistId: String?,
        playlistSetVideoId: String?,
        index: Int?,
        params: String?,
        continuation: String? = null,
    ) = hedged { innerTubeX.next(client, videoId, playlistId, playlistSetVideoId, index, params, continuation) }

    suspend fun feedback(
        client: YouTubeClient,
        tokens: List<String>,
    ) = innerTubeX.feedback(client, tokens).requireSuccess("feedback")

    suspend fun getSearchSuggestions(
        client: YouTubeClient,
        input: String,
    ) = hedged { innerTubeX.getSearchSuggestions(client, input, setLogin = false) }

    suspend fun getQueue(
        client: YouTubeClient,
        videoIds: List<String>?,
        playlistId: String?,
    ) = hedged { innerTubeX.getQueue(client, videoIds, playlistId) }

    suspend fun getTranscript(
        client: YouTubeClient,
        videoId: String,
    ) = innerTubeX.getTranscript(client, videoId)

    suspend fun fetchFreshVisitorData() = innerTubeX.fetchFreshVisitorData()

    suspend fun accountMenu(client: YouTubeClient) = innerTubeX.accountMenu(client).requireSuccess("accountMenu")

    suspend fun accountsList() = innerTubeX.accountsList(YouTubeClient.WEB).requireSuccess("accountsList")

    suspend fun likeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = innerTubeX.likeVideo(client, videoId).requireSuccess("likeVideo")

    suspend fun unlikeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = innerTubeX.unlikeVideo(client, videoId).requireSuccess("unlikeVideo")

    suspend fun subscribeChannel(
        client: YouTubeClient,
        channelId: String,
        params: String? = null,
    ) = innerTubeX.subscribeChannel(client, channelId, params).requireSuccess("subscribeChannel")

    suspend fun unsubscribeChannel(
        client: YouTubeClient,
        channelId: String,
        params: String? = null,
    ) = innerTubeX.unsubscribeChannel(client, channelId, params).requireSuccess("unsubscribeChannel")

    suspend fun likePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = innerTubeX.likePlaylist(client, playlistId).requireSuccess("likePlaylist")

    suspend fun unlikePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = innerTubeX.unlikePlaylist(client, playlistId).requireSuccess("unlikePlaylist")

    suspend fun addToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
    ) = innerTubeX.addToPlaylist(client, playlistId, videoId).requireSuccess("addToPlaylist")

    suspend fun addPlaylistToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        addedPlaylistId: String,
    ) = innerTubeX.addPlaylistToPlaylist(client, playlistId, addedPlaylistId).requireSuccess("addPlaylistToPlaylist")

    suspend fun removeFromPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = innerTubeX.removePlaylistSong(client, playlistId, setVideoId, videoId).requireSuccess("removeFromPlaylist")

    suspend fun moveSongPlaylist(
        client: YouTubeClient,
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ) = innerTubeX.movePlaylistSong(client, playlistId, setVideoId, successorSetVideoId).requireSuccess("moveSongPlaylist")

    suspend fun createPlaylist(
        client: YouTubeClient,
        title: String,
    ) = innerTubeX.createPlaylist(client, title).requireSuccess("createPlaylist")

    /**
     * Varias acciones en una sola edición (InnerTubeX solo manda una por petición): para subir cientos de canciones
     * de golpe. Firmada como las suyas, con la sesión del momento.
     */
    suspend fun editPlaylist(
        client: YouTubeClient,
        playlistId: String,
        actions: List<JsonObject>,
    ): HttpResponse {
        val s = innerTubeX.sessionSnapshot()
        return httpClient.post("browse/edit_playlist") {
            signed(client, s)
            setBody(
                buildJsonObject {
                    put("context", context(client, s))
                    put("playlistId", playlistId.removePrefix("VL"))
                    put("actions", JsonArray(actions))
                },
            )
        }.requireSuccess("editPlaylist")
    }

    /** Una lista privada que nace ya con [videoIds]. */
    suspend fun createPlaylist(
        client: YouTubeClient,
        title: String,
        videoIds: List<String>,
    ): HttpResponse {
        val s = innerTubeX.sessionSnapshot()
        return httpClient.post("playlist/create") {
            signed(client, s)
            setBody(
                buildJsonObject {
                    put("context", context(client, s))
                    put("title", title)
                    put("privacyStatus", "PRIVATE")
                    put("videoIds", JsonArray(videoIds.map(::JsonPrimitive)))
                },
            )
        }.requireSuccess("createPlaylist")
    }

    // Los cuerpos de InnerTubeX son internos: se arman a mano, con su mismo contexto.
    private fun context(client: YouTubeClient, s: InnerTubeX.SessionSnapshot) =
        CONTEXT_JSON.encodeToJsonElement(Context.serializer(), client.toContext(s.locale, s.visitorData, s.dataSyncId))

    private fun io.ktor.client.request.HttpRequestBuilder.signed(client: YouTubeClient, s: InnerTubeX.SessionSnapshot) {
        val sapisid = s.sapisid ?: throw IOException("Sin sesión de YouTube")
        val now = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1").digest("$now $sapisid $ORIGIN".toByteArray()).joinToString("") { "%02x".format(it) }
        parameter("prettyPrint", false)
        contentType(ContentType.Application.Json)
        userAgent(client.userAgent)
        header("X-Goog-Api-Format-Version", "1")
        header("X-YouTube-Client-Name", client.clientId)
        header("X-YouTube-Client-Version", client.clientVersion)
        header("Origin", ORIGIN)
        header("X-Origin", ORIGIN)
        header("Referer", "$ORIGIN/")
        header("X-Goog-AuthUser", s.authUser)
        s.visitorData?.let { header("X-Goog-Visitor-Id", it) }
        header(HttpHeaders.Cookie, s.cookie)
        header(HttpHeaders.Authorization, "SAPISIDHASH ${now}_$hash")
    }

    suspend fun renamePlaylist(
        client: YouTubeClient,
        playlistId: String,
        name: String,
    ) = innerTubeX.renamePlaylist(client, playlistId, name).requireSuccess("renamePlaylist")

    suspend fun setPlaylistThumbnail(
        client: YouTubeClient,
        playlistId: String,
        image: ByteArray,
    ) = innerTubeX.setPlaylistThumbnail(client, playlistId, image).requireSuccess("setPlaylistThumbnail")

    suspend fun removePlaylistThumbnail(
        client: YouTubeClient,
        playlistId: String,
    ) = innerTubeX.removePlaylistThumbnail(client, playlistId).requireSuccess("removePlaylistThumbnail")

    suspend fun deletePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = innerTubeX.deletePlaylist(client, playlistId).requireSuccess("deletePlaylist")

    suspend fun uploadSong(
        filename: String,
        contentLength: Long,
        content: () -> InputStream,
    ) = withContext(Dispatchers.IO) {
        innerTubeX
            .uploadSong(filename, contentLength) {
                content().toByteReadChannel(Dispatchers.IO)
            }.requireSuccess("uploadSong")
    }

    suspend fun deletePrivatelyOwnedEntity(entityId: String) =
        innerTubeX
            .deletePrivatelyOwnedEntity(YouTubeClient.WEB_REMIX, entityId)
            .requireSuccess("deletePrivatelyOwnedEntity")

    private suspend fun HttpResponse.requireSuccess(operation: String): HttpResponse {
        if (!status.isSuccess()) {
            bodyAsChannel().cancel(null)
            throw InnerTubeHttpException(operation, status)
        }
        return this
    }

    private suspend fun returnYouTubeDislike(videoId: String) =
        withRetry {
            httpClient.get("https://returnyoutubedislikeapi.com/Votes?videoId=$videoId") {
                contentType(ContentType.Application.Json)
            }
        }

    private suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelay: Long = 500L,
        factor: Double = 2.0,
        block: suspend () -> T,
    ): T {
        var currentDelay = initialDelay
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (exception: IOException) {
                attempt++
                if (attempt >= maxAttempts) throw exception
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong()
            }
        }
    }

    suspend fun getMediaInfo(videoId: String): Result<MediaInfo> =
        runCatching {
            val response =
                next(
                    client = YouTubeClient.WEB,
                    videoId = videoId,
                    playlistId = null,
                    playlistSetVideoId = null,
                    index = null,
                    params = null,
                    continuation = null,
                ).body<NextResponse>()

            val baseForInfo =
                response.contents.twoColumnWatchNextResults
                    ?.results
                    ?.results
                    ?.content
                    ?.find { it?.videoSecondaryInfoRenderer != null }
                    ?.videoSecondaryInfoRenderer

            val baseForTitle =
                response.contents.twoColumnWatchNextResults
                    ?.results
                    ?.results
                    ?.content
                    ?.find { it?.videoPrimaryInfoRenderer != null }
                    ?.videoPrimaryInfoRenderer

            val returnYouTubeDislikeResponse =
                returnYouTubeDislike(videoId).body<ReturnYouTubeDislikeResponse>()

            MediaInfo(
                videoId = videoId,
                title = baseForTitle?.title?.runs?.firstOrNull()?.text,
                author = baseForInfo?.owner?.videoOwnerRenderer?.title?.runs?.firstOrNull()?.text,
                authorId = baseForInfo?.owner?.videoOwnerRenderer?.navigationEndpoint?.browseEndpoint?.browseId,
                authorThumbnail =
                    baseForInfo
                        ?.owner
                        ?.videoOwnerRenderer
                        ?.thumbnail
                        ?.thumbnails
                        ?.find { it.height == 48 }
                        ?.url
                        ?.replace("s48", "s960"),
                description = baseForInfo?.attributedDescription?.content,
                subscribers = baseForInfo?.owner?.videoOwnerRenderer?.subscriberCountText?.simpleText?.split(" ")?.firstOrNull(),
                uploadDate = baseForTitle?.dateText?.simpleText,
                viewCount = returnYouTubeDislikeResponse.viewCount,
                like = returnYouTubeDislikeResponse.likes,
                dislike = returnYouTubeDislikeResponse.dislikes,
            )
        }
}

private const val ORIGIN = "https://music.youtube.com"
private val CONTEXT_JSON = Json { encodeDefaults = true; explicitNulls = false }

// Oto: lo que se espera a una lectura antes de pedirla otra vez a la vez, y cuántas como mucho (ver InnerTube.hedged).
private const val HEDGE_MS = 1500L
private const val HEDGE_TRIES = 3
