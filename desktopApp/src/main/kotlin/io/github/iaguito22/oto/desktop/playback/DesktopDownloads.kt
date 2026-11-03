package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.playback.InnerTubeXPlayer
import io.github.iaguito22.oto.playback.Quality
import io.github.iaguito22.oto.playback.Tier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/** El id con el que suena «todo lo descargado» (y se vuelve a abrir desde lo que suena). */
const val DOWNLOADS_ID = "downloads"

/**
 * Descargas reales de escritorio: lo mismo que `Downloads.kt` en el móvil, pero con archivos propios.
 *
 * - Carpeta aparte: `OtoDirs.filesDir/downloads` (nunca la caché de reproducción, que se recorta sola).
 * - Se mantiene igual a lo pedido en `Prefs`: listas enteras (también «Me gusta», con lo que añadas después) y
 *   canciones sueltas. Lo que quites de Prefs se borra del disco.
 * - Descarga en alta, por trozos de 1 MB, con reanudación (`.part`) y hasta [RETRIES] intentos por canción.
 * - Índice `index.json` para saber qué hay y con qué calidad sin volver a preguntar a YouTube.
 */
class DesktopDownloads(
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val resolve: suspend (String, Tier) -> Result<InnerTubeXPlayer.Stream> = InnerTubeXPlayer::resolve,
) {
    enum class State { Queued, Downloading, Done, Failed }

    data class Entry(
        val id: String,
        val title: String,
        val artist: String,
        val artwork: String?,
        val file: String,
        val quality: String? = null,
        val itag: Int = 0,
        val bytes: Long = 0L,
        val state: State = State.Queued,
        val progress: Float = 0f,
        val error: String? = null,
    )

    private val dir = File(OtoDirs.filesDir, "downloads")
    private val indexFile = File(dir, "index.json")
    private val indexLock = Mutex()

    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    /** Lo descargado y lo que se está bajando, por id de canción. */
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()

    private val _wanted = MutableStateFlow<Map<String, Heard>>(emptyMap())
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var started = false
    private var syncJob: Job? = null
    private var retryJob: Job? = null
    private val workers = mutableListOf<Job>()
    private val active = ConcurrentHashMap<String, Job>()

    fun start() {
        if (started) return
        started = true
        dir.mkdirs()
        loadIndex()

        repeat(PARALLEL) {
            workers += scope.launch(Dispatchers.IO) {
                for (id in queue) {
                    val heard = _wanted.value[id] ?: continue
                    if (_entries.value[id]?.state == State.Done && localFile(id) != null) continue
                    coroutineScope {
                        val task = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                            if (id in _wanted.value) download(id, heard)
                        }
                        active[id] = task
                        try { task.start(); task.join() } finally { active.remove(id, task) }
                    }
                }
            }
        }

        syncJob = scope.launch {
            combine(
                prefs.downloadLists,
                prefs.downloadSongs,
                prefs.playlists,
                prefs.liked,
            ) { lists, songs, all, liked ->
                val wanted = LinkedHashMap<String, Heard>()
                songs.forEach { wanted[it.id] = it }
                lists.forEach { listId ->
                    val items = if (listId == LIKED_ID) liked else all.firstOrNull { it.id == listId }?.songs.orEmpty()
                    items.forEach { wanted.putIfAbsent(it.id, it) }
                }
                wanted
            }
                .debounce(SYNC_DEBOUNCE_MS)
                .collect { wanted ->
                    _wanted.value = wanted
                    sync(wanted)
                }
        }

        // Lo que falló (sin red un rato, por ejemplo) se reintenta solo.
        retryJob = scope.launch {
            while (isActive) {
                delay(RETRY_EVERY_MS)
                _entries.value.values
                    .filter { it.state == State.Failed }
                    .forEach { enqueue(it.id, _wanted.value[it.id] ?: return@forEach) }
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
        retryJob?.cancel()
        retryJob = null
        workers.forEach { it.cancel() }
        workers.clear()
        active.values.forEach { it.cancel() }
        active.clear()
        while (queue.tryReceive().isSuccess) { /* No conservar trabajos de la ejecución anterior. */ }
        _entries.update { it.filterValues { entry -> entry.state == State.Done } }
        started = false
    }

    private suspend fun sync(wanted: Map<String, Heard>) {
        val drop = _entries.value.keys - wanted.keys
        drop.forEach { remove(it) }
        for ((id, heard) in wanted) {
            val entry = _entries.value[id]
            when {
                entry == null -> enqueue(id, heard)
                entry.state == State.Failed -> enqueue(id, heard)
                entry.state == State.Done && localFile(id) == null -> enqueue(id, heard)
            }
        }
    }

    private fun enqueue(id: String, heard: Heard) {
        val current = _entries.value[id]
        if (current != null && (current.state == State.Queued || current.state == State.Downloading)) return
        val entry = Entry(
            id = id,
            title = heard.title,
            artist = heard.artist,
            artwork = heard.artwork,
            file = current?.file.orEmpty(),
            state = State.Queued,
        )
        _entries.update { it + (id to entry) }
        queue.trySend(id)
    }

    fun retry(id: String) {
        if (_entries.value[id]?.state == State.Failed) _wanted.value[id]?.let { enqueue(id, it) }
    }

    private suspend fun download(id: String, heard: Heard) {
        val before = _entries.value[id]
        update(id) {
            it.copy(
                title = heard.title,
                artist = heard.artist,
                artwork = heard.artwork,
                state = State.Downloading,
                progress = 0f,
                error = null,
            )
        }

        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt++
            try {
                val stream = resolve(id, Tier.High).getOrElse { throw it }
                val ext = if (
                    stream.quality.codec.contains("opus", ignoreCase = true) ||
                    stream.quality.codec.contains("webm", ignoreCase = true)
                ) "webm" else "m4a"
                val finalFile = File(dir, "$id.$ext")
                val part = File(dir, "$id.part")

                // Si el formato cambió desde la última vez, lo a medias ya no sirve.
                if (before != null && before.itag != 0 && before.itag != stream.itag) {
                    part.delete()
                    finalFile.delete()
                }
                if (finalFile.exists() && finalFile.length() > 0L) {
                    markDone(id, finalFile, stream.quality, stream.itag)
                    return
                }

                var lastPct = -1
                val bytes = downloadChunked(stream.url, stream.headers, stream.contentLength, part) { done, total ->
                    val pct = if (total > 0L) ((done * 100L) / total).toInt() else 0
                    if (pct != lastPct) {
                        lastPct = pct
                        update(id) { it.copy(progress = (pct / 100f).coerceIn(0f, 1f), bytes = done) }
                    }
                }
                if (bytes <= 0L) throw IllegalStateException("La descarga quedó vacía")

                finalFile.delete()
                if (!part.renameTo(finalFile)) {
                    part.copyTo(finalFile, overwrite = true)
                    part.delete()
                }
                markDone(id, finalFile, stream.quality, stream.itag)
                return
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (attempt >= RETRIES) {
                    Timber.w(t, "descarga %s falló tras %d intentos", id, attempt)
                    update(id) { it.copy(state = State.Failed, error = t.message ?: "Error de descarga") }
                    saveIndex()
                    return
                }
                delay(1_000L * attempt)
            }
        }
    }

    private suspend fun markDone(id: String, file: File, quality: Quality, itag: Int) {
        _entries.update { map ->
            val entry = map[id] ?: return@update map
            map + (id to entry.copy(
                file = file.name,
                quality = quality.encode(),
                itag = itag,
                bytes = file.length(),
                state = State.Done,
                progress = 1f,
                error = null,
            ))
        }
        saveIndex()
    }

    private fun update(id: String, block: (Entry) -> Entry) {
        _entries.update { map -> map[id]?.let { map + (id to block(it)) } ?: map }
    }

    /** Entera en las descargas: suena desde el archivo, sin red. */
    fun has(id: String): Boolean = localFile(id) != null

    fun localFile(id: String): File? {
        val entry = _entries.value[id] ?: return null
        if (entry.state != State.Done || entry.file.isEmpty()) return null
        val file = File(dir, entry.file)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    fun quality(id: String): Quality? = _entries.value[id]?.quality?.let(Quality::decode)

    /** Lo que ocupan todas las descargas, en bytes. */
    fun bytes(): Long =
        dir.listFiles()?.filter { it.isFile && it.name != indexFile.name }?.sumOf { it.length() } ?: 0L

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        active.remove(id)?.cancelAndJoin()
        val entry = _entries.value[id] ?: return@withContext
        runCatching { if (entry.file.isNotEmpty()) File(dir, entry.file).delete() }
        runCatching { File(dir, "$id.part").delete() }
        _entries.update { it - id }
        saveIndex()
    }

    /** Todo fuera: lo pedido en Prefs y lo bajado. */
    suspend fun clear() {
        prefs.clearDownloads()
        _wanted.value = emptyMap()
        active.values.toList().forEach { it.cancelAndJoin() }
        dir.listFiles()?.forEach { it.delete() }
        _entries.value = emptyMap()
        saveIndex()
    }

    private fun loadIndex() {
        if (!indexFile.exists()) return
        runCatching {
            val array = JSONArray(indexFile.readText())
            val map = LinkedHashMap<String, Entry>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val entry = Entry(
                    id = id,
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    artwork = o.optString("artwork").takeIf { it.isNotEmpty() },
                    file = o.optString("file"),
                    quality = o.optString("quality").takeIf { it.isNotEmpty() },
                    itag = o.optInt("itag", 0),
                    state = State.Done,
                    progress = 1f,
                )
                val file = File(dir, entry.file)
                if (!file.exists() || file.length() <= 0L) continue
                map[id] = entry.copy(bytes = file.length())
            }
            _entries.value = map
        }.onFailure { Timber.w(it, "No se pudo leer el índice de descargas") }
    }

    private suspend fun saveIndex() = indexLock.withLock {
        runCatching {
            val array = JSONArray()
            _entries.value.values
                .filter { it.state == State.Done && it.file.isNotEmpty() }
                .forEach { entry ->
                    array.put(JSONObject().apply {
                        put("id", entry.id)
                        put("title", entry.title)
                        put("artist", entry.artist)
                        put("artwork", entry.artwork.orEmpty())
                        put("file", entry.file)
                        put("quality", entry.quality.orEmpty())
                        put("itag", entry.itag)
                    })
                }
            dir.mkdirs()
            indexFile.writeText(array.toString())
        }.onFailure { Timber.w(it, "No se pudo guardar el índice de descargas") }
    }

    private companion object {
        const val PARALLEL = 2
        const val RETRIES = 6
        const val SYNC_DEBOUNCE_MS = 400L
        const val RETRY_EVERY_MS = 60_000L
    }
}
