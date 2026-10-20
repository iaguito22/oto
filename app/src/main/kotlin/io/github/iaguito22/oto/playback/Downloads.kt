package io.github.iaguito22.oto.playback

import android.app.Notification
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Lo que pediste tener sin conexión: listas enteras (lo que entre en ellas después, también) y canciones sueltas.
 * Va en su propia caché, fuera de la de la reproducción: esa se recorta sola ([io.github.iaguito22.oto.Storage]) y
 * esta nunca; solo se borra lo que quites tú. Se baja siempre en alta y, con el ahorro de datos puesto, solo con wifi.
 */
@OptIn(UnstableApi::class)
class Downloads(private val app: OtoApp) {
    val cache by lazy { SimpleCache(File(app.filesDir, "downloads"), NoOpCacheEvictor(), app.database) }

    // La URL de cada una mientras se baja: si un trozo falla y se reintenta, no hace falta volver a pedirla.
    private val urls = ConcurrentHashMap<String, Pair<CachedStreamUrl, Long>>()

    /** Debe crearse en el hilo principal: es el que usan sus avisos. */
    val manager: DownloadManager by lazy {
        InnerTubeXPlayer.initialize(app)
        val network = OkHttpDataSource.Factory(AUDIO_HTTP)
        val upstream = ResolvingDataSource.Factory({ ChunkedDataSource(network.createDataSource()) }, ::resolve)
        DownloadManager(app, app.database, cache, upstream, Executors.newFixedThreadPool(PARALLEL)).apply {
            maxParallelDownloads = PARALLEL
            minRetryCount = RETRIES
            addListener(listener)
        }
    }

    private val _states = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** El estado de cada canción descargada o por descargar (los de [Download], STATE_*), por id. */
    val states: StateFlow<Map<String, Int>> = _states.asStateFlow()

    private val _waiting = MutableStateFlow(false)
    /** Hay algo por bajar pero falta lo que pide (red, o wifi con el ahorro de datos). */
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    private val ready = CompletableDeferred<Unit>()

    /** Mantiene lo descargado igual a lo pedido en Prefs, cambie quien lo cambie. */
    @kotlin.OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        val prefs = app.prefs
        scope.launch(Dispatchers.Main) {
            manager // en el principal
            combine(prefs.downloadLists, prefs.downloadSongs, prefs.playlists, prefs.liked) { lists, songs, all, liked ->
                val wanted = LinkedHashMap<String, Heard>()
                songs.forEach { wanted[it.id] = it }
                lists.forEach { id -> (if (id == LIKED_ID) liked else all.firstOrNull { it.id == id }?.songs.orEmpty()).forEach { wanted.putIfAbsent(it.id, it) } }
                wanted
            }
                .distinctUntilChanged { a, b -> a.keys == b.keys }
                .debounce(SYNC_DEBOUNCE_MS)
                .collect { sync(it) }
        }
        scope.launch(Dispatchers.Main) {
            prefs.dataSaver.collect { saver ->
                manager.requirements = Requirements(if (saver) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
            }
        }
    }

    private suspend fun sync(wanted: Map<String, Heard>) {
        ready.await()
        val have = _states.value
        val drop = have.keys - wanted.keys
        // Lo que falló (sin red un rato, por ejemplo) se vuelve a intentar.
        val add = wanted.keys.filter { it !in have || have[it] == Download.STATE_FAILED }
        drop.forEach { manager.removeDownload(it) }
        add.forEach { id -> manager.addDownload(DownloadRequest.Builder(id, Uri.parse(id)).setCustomCacheKey(id).build()) }
        if (drop.isNotEmpty() || add.isNotEmpty()) Timber.d("descargas: +%d −%d", add.size, drop.size)
        // Con la app delante: el servicio la sigue bajando aunque se cierre. Desde segundo plano Android no deja.
        if (add.isNotEmpty()) runCatching { DownloadService.start(app, DownloadsService::class.java) }
    }

    /** Entera en las descargas: suena desde aquí, sin red. */
    fun has(id: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(id))
        return length > 0 && cache.isCached(id, 0, length)
    }

    fun quality(id: String): Quality? = Quality.decode(cache.getContentMetadata(id).get(QUALITY_KEY, null as String?))

    /** Lo que ocupan, en bytes. */
    fun bytes(): Long = cache.cacheSpace

    /** Todo fuera: lo pedido y lo bajado. */
    suspend fun clear() {
        app.prefs.clearDownloads()
        withContext(Dispatchers.Main) { manager.removeAllDownloads() }
    }

    /** En el hilo de descarga, cada vez que se abre un trozo por bajar. */
    private fun resolve(spec: DataSpec): DataSpec {
        val id = spec.key ?: return spec
        urls[id]?.let { (stream, until) -> if (System.currentTimeMillis() < until) return spec.withResolvedStream(stream) }
        val stream = runBlocking(Dispatchers.IO) { InnerTubeXPlayer.resolve(id, Tier.High) }
            .getOrElse { throw IOException("No se pudo resolver $id: ${it.message}", it) }
        // Lo bajado a medias es de otro formato: no casa con lo nuevo. Fuera, y a empezar de cero.
        val stored = cache.getContentMetadata(id).get(ITAG_KEY, 0L)
        if (stored != 0L && stored != stream.itag.toLong()) {
            runCatching { cache.removeResource(id) }
            throw IOException("$id: cambió el formato")
        }
        runCatching {
            cache.applyContentMetadataMutations(id, ContentMetadataMutations().set(QUALITY_KEY, stream.quality.encode()).set(ITAG_KEY, stream.itag.toLong()))
        }
        val resolved = CachedStreamUrl(stream.url, stream.headers, stream.client)
        urls[id] = resolved to System.currentTimeMillis() + stream.expiresInSeconds * 1000L / 2
        return spec.withResolvedStream(resolved)
    }

    private val listener = object : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) {
            app.scope.launch(Dispatchers.IO) {
                val all = HashMap<String, Int>()
                downloadManager.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) all[c.download.request.id] = c.download.state }
                _states.update { all + it }
                ready.complete(Unit)
            }
        }

        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            _states.update { it + (download.request.id to download.state) }
            if (download.state == Download.STATE_COMPLETED) urls.remove(download.request.id)
            finalException?.let { Timber.w(it, "descarga %s", download.request.id) }
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            _states.update { it - download.request.id }
            urls.remove(download.request.id)
        }

        override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements, notMetRequirements: Int) {
            _waiting.value = notMetRequirements != 0
        }
    }

    private companion object {
        const val PARALLEL = 3
        const val RETRIES = 10
        const val SYNC_DEBOUNCE_MS = 400L
    }
}

/**
 * Lo que suena: si está descargada, de las descargas (sin pedir nada a la red); si no, por [online], que la busca en
 * la caché o en YouTube.
 */
@OptIn(UnstableApi::class)
class OfflineFirstDataSource(
    private val offline: DataSource,
    private val online: DataSource,
    private val has: (String) -> Boolean,
    private val onOffline: (String) -> Unit,
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        offline.addTransferListener(transferListener)
        online.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // La clave de una canción pasada a baja (`id~l`) también: descargada, suena la descargada.
        val id = dataSpec.key?.let(Tier::idOf)
        if (id != null && has(id)) {
            onOffline(id)
            current = offline
            return offline.open(dataSpec.buildUpon().setKey(id).build())
        }
        current = online
        return online.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

    override fun close() {
        current?.close()
        current = null
    }

    class Factory(private val downloads: Downloads, private val online: DataSource.Factory) : DataSource.Factory {
        // Solo lectura: lo que no esté, no se baja aquí (eso es cosa de [online]).
        private val offline = CacheDataSource.Factory().setCache(downloads.cache).setCacheWriteDataSinkFactory(null)

        override fun createDataSource(): DataSource = OfflineFirstDataSource(
            offline.createDataSource(), online.createDataSource(), downloads::has,
            onOffline = { id -> downloads.quality(id)?.let { StreamInfo.put(id, it) } },
        )
    }
}

/** Sigue bajando con la app cerrada, con su notificación de progreso. */
@OptIn(UnstableApi::class)
class DownloadsService : DownloadService(NOTIFICATION_ID, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, CHANNEL, R.string.downloads_channel, 0) {
    private val notes by lazy { DownloadNotificationHelper(this, CHANNEL) }

    override fun getDownloadManager(): DownloadManager = (application as OtoApp).downloads.manager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        notes.buildProgressNotification(this, R.drawable.media3_notification_small_icon, null, null, downloads, notMetRequirements)

    private companion object {
        const val NOTIFICATION_ID = 2
        const val JOB_ID = 2
        const val CHANNEL = "downloads"
    }
}
