package io.github.iaguito22.oto.account

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.toHeard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Cómo va la copia: lo que se enseña en Tu cuenta. */
sealed interface CloudState {
    data object Off : CloudState
    data class Working(val what: String) : CloudState
    data class Done(val lists: Int, val at: Long) : CloudState
    data class Failed(val why: String) : CloudState
}

/**
 * Importación por selección y copia continua solo de las listas autorizadas.
 * Cada copia privada lleva el id de Oto en su descripción para reconocerla al volver a entrar.
 * Desmarcar o borrar una lista local nunca elimina su copia de YouTube Music.
 */
@OptIn(FlowPreview::class)
class Cloud(private val prefs: Prefs, private val account: Account, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<CloudState>(CloudState.Off)
    val state: StateFlow<CloudState> = _state.asStateFlow()

    /** Una lista ya subida: su id en YouTube, y el nombre, las canciones y la descripción tal y como se subieron. */
    private data class Synced(val yt: String, val name: String, val ids: List<String>, val meta: String?)

    /** Una lista de Oto encontrada en la cuenta. */
    private data class Found(val yt: String, val name: String, val meta: ListMeta, val described: Boolean)

    private data class Saved(val manifest: String?, val lists: Map<String, Synced>, val written: String?, val pending: Set<String> = emptySet())

    private val operation = Mutex()
    val selection = prefs.cloudSelection

    /** Solo lee el catálogo; iniciar sesión no modifica ninguna lista. */
    suspend fun available(): List<PlaylistItem> = withContext(Dispatchers.IO) {
        val all = ArrayList<PlaylistItem>()
        library { if(it.title != MANIFEST_TITLE && it.id.removePrefix("VL") != "LM") all += it; false }
        all.distinctBy { it.id.removePrefix("VL") }
    }

    /** Importación explícita y exportación limitada a los destinos elegidos. */
    suspend fun choose(requestedChoice: CloudSelection) = withContext(Dispatchers.IO) {
        val choice=requestedChoice.separated()
        check(account.session.value != null) { "Inicia sesión primero" }
        prefs.setCloudSelection(null)
        operation.withLock {
            try {
                var saved = load()
                val catalog = if(choice.imports.any { it!="LM" } || choice.exports.isNotEmpty()) available() else emptyList()
                check(choice.exports.isEmpty() || catalog.isNotEmpty()) { "No se pudo comprobar el catálogo de YouTube Music. No se crearán listas hasta poder verificarlo." }
                val local = prefs.playlists.first()
                val requested = local.filter { it.id in choice.exports } + if(LIKED_ID in choice.exports) listOf(prefs.likedList.first().copy(name="Me gusta de Oto")) else emptyList()
                // Recuperar los ids de copias anteriores evita duplicarlas tras volver a entrar.
                for(item in catalog.filter { item -> requested.any { it.name == item.title && it.id !in saved.lists } }) {
                    val yt=item.id.removePrefix("VL")
                    val raw=YouTube.browseRaw("VL$yt").getOrThrow()
                    val meta=ListMeta.find(raw) ?: continue
                    if(requested.any { it.id==meta.id }) {
                        val tracks=songs(yt) ?: error("No se pudo leer «${item.title}»")
                        saved=saved.copy(lists=saved.lists+(meta.id to Synced(yt,item.title,tracks.map{it.id},meta.encode())))
                        save(saved)
                    }
                }
                for(yt in choice.imports) {
                    if(yt=="LM") { restoreLikes(); continue }
                    val item=catalog.firstOrNull { it.id.removePrefix("VL")==yt } ?: error("Una lista seleccionada ya no está disponible. Actualiza la selección.")
                    _state.value=CloudState.Working("Importando «${item.title}»")
                    val tracks=songs(yt) ?: error("No se pudo leer «${item.title}»")
                    val raw=YouTube.browseRaw("VL$yt").getOrThrow()
                    val meta=ListMeta.find(raw)
                    val id=meta?.id ?: "youtube:$yt"
                    importCloudPlaylist(prefs,LocalPlaylist(id,item.title,tracks,meta?.from ?: "youtube:$yt",meta?.folder))
                    if(meta!=null) { saved=saved.copy(lists=saved.lists+(id to Synced(yt,item.title,tracks.map{it.id},meta.encode())));save(saved) }
                }
                prefs.setCloudSelection(choice.encode())
                _state.value=if(choice.exports.isEmpty()) CloudState.Done(0,System.currentTimeMillis()) else CloudState.Working("Preparando las listas elegidas")
            } catch(e: Exception) { fail(e);throw e }
        }
    }

    fun start() {
        scope.launch {
            account.session.collectLatest { s ->
                if (s == null) { _state.value = CloudState.Off; return@collectLatest }
                prefs.cloudSelection.collectLatest { raw ->
                    val choice=CloudSelection.decode(raw)
                    if(choice==null) { _state.value=CloudState.Off; return@collectLatest }
                    combine(prefs.playlists, prefs.folders, prefs.pinned, prefs.sortFlipped, prefs.liked) { l, _, _, _, _ -> l }
                        .debounce(DEBOUNCE_MS)
                        .collect { withContext(Dispatchers.IO) { operation.withLock { runCatching { sync(choice) }.onFailure { fail(it) } } } }
                }
            }
        }
    }

    /** Al cerrar sesión: lo subido es de esa cuenta, no de la siguiente. */
    suspend fun forget() {
        prefs.setCloudSelection(null)
        prefs.setCloud(null)
        _state.value = CloudState.Off
    }

    private fun fail(e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Timber.w(e, "copia")
        _state.value = CloudState.Failed(e.message ?: "Sin conexión")
    }

    private suspend fun sync(choice: CloudSelection) {
        var saved = load()
        // Las listas desmarcadas quedan intactas, tanto aquí como en YouTube Music.
        val lists = prefs.playlists.first().filter { it.id in choice.exports } + if(LIKED_ID in choice.exports) listOf(prefs.likedList.first().copy(name="Me gusta de Oto")) else emptyList()
        lists.forEachIndexed { i, l ->
            val ids = l.songs.map { it.id }
            val meta = ListMeta(l.id, l.folder, l.from).encode()
            val s = saved.lists[l.id]
            if (s == null) {
                val catalog = available()
                check(catalog.isNotEmpty()) { "No se pudo comprobar el catálogo de YouTube Music. No se crearán listas hasta poder verificarlo." }
                ensureSafePlaylistCreation(l.id, l.name, saved.pending, catalog.map { it.title })
                _state.value = CloudState.Working("Guardando «${l.name}» (${i + 1} de ${lists.size})")
                saved = saved.copy(pending = saved.pending + l.id)
                save(saved)
                val yt = YouTube.createPlaylist(l.name, ids.take(FIRST)).getOrThrow()
                saved = saved.copy(lists = saved.lists + (l.id to Synced(yt, l.name, ids.take(FIRST), null)), pending = saved.pending - l.id)
                save(saved)
                YouTube.editPlaylist(yt, description = LIST_NOTE + meta).getOrThrow()
                saved = saved.copy(lists = saved.lists + (l.id to Synced(yt, l.name, ids.take(FIRST), meta)))
                save(saved)
                ids.drop(FIRST).chunked(CHUNK).forEach { chunk ->
                    YouTube.editPlaylist(yt, add = chunk).getOrThrow()
                    saved = saved.copy(lists = saved.lists + (l.id to saved.lists.getValue(l.id).copy(ids = saved.lists.getValue(l.id).ids + chunk)))
                    save(saved)
                }
            } else {
                val had = s.ids.toHashSet()
                val now = ids.toHashSet()
                val add = ids.filter { it !in had }
                val remove = s.ids.filter { it !in now }
                val name = l.name.takeIf { it != s.name }
                val description = (LIST_NOTE + meta).takeIf { meta != s.meta }
                if (add.isEmpty() && remove.isEmpty() && name == null && description == null) return@forEachIndexed
                _state.value = CloudState.Working("Guardando «${l.name}»")
                YouTube.editPlaylist(s.yt, remove = remove.take(CHUNK), name = name, description = description).getOrThrow()
                remove.drop(CHUNK).chunked(CHUNK).forEach { YouTube.editPlaylist(s.yt, remove = it).getOrThrow() }
                add.chunked(CHUNK).forEach { YouTube.editPlaylist(s.yt, add = it).getOrThrow() }
                saved = saved.copy(lists = saved.lists + (l.id to Synced(s.yt, l.name, ids, meta)))
                save(saved)
            }
        }

        // Mantener la copia existente sin borrar ni reescribir su índice global desde una
        // selección parcial. Cada lista exportada conserva sus metadatos recuperables.
        _state.value = CloudState.Done(lists.size, System.currentTimeMillis())
    }

    /** Importa los Me gusta de YouTube como una lista independiente de los de Oto. */
    private suspend fun restoreLikes() {
        _state.value = CloudState.Working("Recuperando Me gusta")
        val songs = songs("LM") ?: error("No se pudieron leer los Me gusta de YouTube Music")
        importCloudPlaylist(prefs,LocalPlaylist(YOUTUBE_LIKES_LIST_ID,"Me gusta de YouTube Music",songs,"youtube:LM"))
    }

    /** Todas las canciones de una lista de YouTube, página a página. */
    private suspend fun songs(yt: String): List<Heard>? {
        val first = YouTube.playlist(yt).getOrElse { Timber.w(it, "copia: leer %s", yt); return null }
        if (first.playlist.title.isBlank()) { Timber.w("copia: respuesta incompleta al leer %s", yt); return null }
        val all = first.songs.toMutableList<SongItem>()
        var next = first.songsContinuation ?: first.continuation
        while (next != null) {
            val page = YouTube.playlistContinuation(next).getOrThrow()
            all += page.songs
            next = page.continuation
        }
        return all.map { it.toHeard() }.distinctBy { it.id }
    }

    /** Tus listas de YouTube Music, página a página, hasta que [stop] diga basta. */
    private suspend fun library(stop: (PlaylistItem) -> Boolean) {
        var page = YouTube.library(LIBRARY_PLAYLISTS).getOrThrow()
        var items = page.items
        var next = page.continuation
        while (true) {
            if (items.filterIsInstance<PlaylistItem>().any(stop)) return
            val c = next ?: return
            val more = YouTube.libraryContinuation(c).getOrThrow()
            items = more.items
            next = more.continuation
        }
    }

    /** La copia, buscándola entre tus listas de YouTube Music por su nombre. */
    private suspend fun findManifest(): String? {
        var id: String? = null
        library { if (it.title == MANIFEST_TITLE) id = it.id.removePrefix("VL"); id != null }
        return id
    }

    /** Las listas de Oto de la cuenta: las que llevan su [ListMeta] en la descripción. */
    private suspend fun scan(): List<Found> {
        _state.value = CloudState.Working("Buscando tus listas")
        val all = ArrayList<PlaylistItem>()
        library { all += it; false }
        return all.filter { it.title != MANIFEST_TITLE && it.id != "LM" }.mapNotNull { p ->
            val yt = p.id.removePrefix("VL")
            val raw = YouTube.browseRaw("VL$yt").getOrElse { Timber.w(it, "copia: leer %s", yt); return@mapNotNull null }
            ListMeta.find(raw)?.let { Found(yt, p.title, it, described = true) }
        }
    }

    private suspend fun readManifest(id: String): Manifest? {
        val raw = YouTube.browseRaw("VL$id").getOrThrow()
        return Regex(Regex.escape(Manifest.PREFIX) + "[A-Za-z0-9_-]+").find(raw)?.value?.let(Manifest::decode)
    }

    // ---- Lo subido, en Prefs ----

    private suspend fun load(): Saved = runCatching {
        val o = JSONObject(prefs.cloud() ?: return Saved(null, emptyMap(), null))
        val l = o.optJSONObject("lists") ?: JSONObject()
        Saved(
            o.optString("manifest").ifEmpty { null },
            l.keys().asSequence().associateWith { k ->
                val e = l.getJSONObject(k)
                val ids = e.getJSONArray("ids")
                Synced(e.getString("yt"), e.optString("n"), (0 until ids.length()).map(ids::getString), e.optString("m").ifEmpty { null })
            },
            o.optString("written").ifEmpty { null },
            o.optJSONArray("pending")?.let { a -> (0 until a.length()).map(a::getString).toSet() }.orEmpty(),
        )
    }.getOrDefault(Saved(null, emptyMap(), null))

    private suspend fun save(s: Saved) {
        val l = JSONObject()
        s.lists.forEach { (k, v) -> l.put(k, JSONObject().put("yt", v.yt).put("n", v.name).put("ids", JSONArray(v.ids)).put("m", v.meta)) }
        prefs.setCloud(JSONObject().put("manifest", s.manifest).put("written", s.written).put("lists", l).put("pending", JSONArray(s.pending.toList())).toString())
    }

    private companion object {
        const val DEBOUNCE_MS = 4_000L
        const val FIRST = 200
        const val CHUNK = 100
        const val LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"
        const val MANIFEST_TITLE = "Oto · copia de tus listas"
        const val MANIFEST_NOTE = "Oto guarda aquí cómo tienes tus listas (carpetas, fijadas, orden). No la borres. "
        const val LIST_NOTE = "Lista de Oto. "
    }
}

internal fun ensureSafePlaylistCreation(id: String, name: String, pending: Set<String>, existingNames: List<String>) {
    check(id !in pending) { "No se sabe si se creó «$name». Revisa YouTube Music antes de volver a exportarla." }
    check(existingNames.none { it.trim().equals(name.trim(), ignoreCase = true) }) {
        "Ya existe una lista llamada «$name» en YouTube Music. Revisa la lista antes de exportar para evitar duplicados."
    }
}

internal suspend fun importCloudPlaylist(prefs: Prefs, list: LocalPlaylist) {
    if (list.id == YOUTUBE_LIKES_LIST_ID || list.from == "youtube:LM") {
        prefs.restore(listOf(list.copy(id=YOUTUBE_LIKES_LIST_ID,name="Me gusta de YouTube Music",from="youtube:LM")),emptySet(),emptySet(),emptySet())
    }
    else if (list.id == LIKED_ID) prefs.restoreLiked(list.songs)
    else prefs.restore(listOf(list),list.folder?.let { setOf(it) }.orEmpty(),emptySet(),emptySet())
}

/**
 * Lo que es de todas tus listas, comprimido para caber en una descripción (5000 caracteres; YouTube no deja «<» ni
 * «>», así que va en base64). Cada lista va por su [ref], no por su id entero: así caben cientos.
 *
 * La primera versión llevaba aquí cada lista entera ([old]) y con unas 45 ya no cabía; se sigue leyendo.
 */
internal data class Manifest(
    val order: List<String>,
    val folders: Set<String>,
    val pinned: Set<String>,
    val flipped: Set<String>,
    val old: List<Entry> = emptyList(),
) {
    data class Entry(val id: String, val yt: String, val name: String, val folder: String?, val from: String?)

    fun encode(): String {
        val json = JSONObject()
            .put("v", 2)
            .put("o", order.joinToString(""))
            .put("folders", JSONArray(folders.sorted()))
            .put("pinned", JSONArray(pinned.sorted()))
            .put("flip", JSONArray(flipped.sorted()))
            .toString()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(json.toByteArray()))
    }

    companion object {
        const val PREFIX = "oto1:"
        private const val REF = 8

        /** Lo justo del id de una lista para distinguirla de las demás (48 bits de su hash). */
        fun ref(id: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(id.toByteArray())).take(REF)

        fun decode(s: String): Manifest? = runCatching {
            val o = JSONObject(inflate(Base64.getUrlDecoder().decode(s.removePrefix(PREFIX))).toString(Charsets.UTF_8))
            fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }.orEmpty()
            val old = o.optJSONArray("l")?.let { l ->
                (0 until l.length()).map { i ->
                    val e = l.getJSONObject(i)
                    Entry(e.getString("i"), e.getString("y"), e.optString("n"), e.optString("d").ifEmpty { null }, e.optString("f").ifEmpty { null })
                }
            }.orEmpty()
            Manifest(
                if (old.isNotEmpty()) old.map { ref(it.id) } else o.optString("o").chunked(REF),
                set("folders"), set("pinned"), set("flip"), old,
            )
        }.onFailure { Timber.w(it, "copia: descripción ilegible") }.getOrNull()
    }
}

/** Lo que YouTube no sabe de una lista: su id en Oto, su carpeta y de dónde vino. Va en su descripción. */
internal data class ListMeta(val id: String, val folder: String?, val from: String?) {
    fun encode(): String {
        val json = JSONObject().put("i", id).put("d", folder).put("f", from).toString()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    companion object {
        const val PREFIX = "oto-lista:"

        fun find(raw: String): ListMeta? = Regex(Regex.escape(PREFIX) + "([A-Za-z0-9_-]+)").find(raw)?.groupValues?.get(1)?.let { b ->
            runCatching {
                val o = JSONObject(Base64.getUrlDecoder().decode(b).toString(Charsets.UTF_8))
                ListMeta(o.getString("i"), o.optString("d").ifEmpty { null }, o.optString("f").ifEmpty { null })
            }.onFailure { Timber.w(it, "copia: lista ilegible") }.getOrNull()
        }
    }
}

private fun deflate(bytes: ByteArray): ByteArray {
    val d = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(bytes); finish() }
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (!d.finished()) out.write(buf, 0, d.deflate(buf))
    d.end()
    return out.toByteArray()
}

private fun inflate(bytes: ByteArray): ByteArray {
    val inf = Inflater().apply { setInput(bytes) }
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (!inf.finished()) {
        val n = inf.inflate(buf)
        if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
        out.write(buf, 0, n)
    }
    inf.end()
    return out.toByteArray()
}
