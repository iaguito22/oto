package io.github.iaguito22.oto.account

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.Heard
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
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
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
 * Con cuenta, tus listas también viven en YouTube Music: cada una es una lista privada tuya allí, y una más
 * («Oto · copia de tus listas», vacía) guarda en su descripción lo que YouTube no sabe: carpetas, fijadas, el orden.
 * Al entrar en una instalación nueva (u otro móvil) vuelven solas, y Me gusta con tus me gusta de YouTube Music.
 *
 * Solo se suben los cambios: lo que entra o sale de cada lista desde la última vez ([Synced]).
 */
@OptIn(FlowPreview::class)
class Cloud(private val prefs: Prefs, private val account: Account, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<CloudState>(CloudState.Off)
    val state: StateFlow<CloudState> = _state.asStateFlow()

    /** Una lista ya subida: su id en YouTube, y el nombre y las canciones tal y como se subieron. */
    private data class Synced(val yt: String, val name: String, val ids: List<String>)

    private data class Saved(val manifest: String?, val lists: Map<String, Synced>, val written: String?)

    fun start() {
        scope.launch {
            account.session.collectLatest { s ->
                if (s == null) { _state.value = CloudState.Off; return@collectLatest }
                combine(prefs.playlists, prefs.folders, prefs.pinned, prefs.sortFlipped) { l, _, _, _ -> l }
                    .debounce(DEBOUNCE_MS)
                    .collect { withContext(Dispatchers.IO) { runCatching { sync() }.onFailure { fail(it) } } }
            }
        }
    }

    /** Al cerrar sesión: lo subido es de esa cuenta, no de la siguiente. */
    suspend fun forget() {
        prefs.setCloud(null)
        _state.value = CloudState.Off
    }

    private fun fail(e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Timber.w(e, "copia")
        _state.value = CloudState.Failed(e.message ?: "Sin conexión")
    }

    private suspend fun sync() {
        var saved = load()
        val manifestId = saved.manifest ?: findManifest()
        val remote = manifestId?.let { readManifest(it) }

        // 1. Lo que hay en la cuenta y esta instalación no conoce (es nueva, u otro móvil): se trae. Si la lista ya
        // está aquí con el mismo id (se perdió lo subido, no la lista), solo se apunta, para no duplicarla allí.
        if (remote != null) {
            val fresh = saved.lists.isEmpty()
            val local = prefs.playlists.first().mapTo(HashSet()) { it.id }
            val unknown = remote.lists.filter { it.id !in saved.lists }
            val restored = ArrayList<LocalPlaylist>()
            unknown.forEachIndexed { i, m ->
                _state.value = CloudState.Working("Recuperando «${m.name}» (${i + 1} de ${unknown.size})")
                val songs = songs(m.yt) ?: return@forEachIndexed
                saved = saved.copy(lists = saved.lists + (m.id to Synced(m.yt, m.name, songs.map { it.id })))
                if (m.id !in local) restored += LocalPlaylist(m.id, m.name, songs, m.from, m.folder)
            }
            if (unknown.isNotEmpty()) {
                prefs.restore(restored, remote.folders, remote.pinned, remote.flipped)
                save(saved)
            }
            if (fresh) restoreLikes()
        }

        // 2. Lo de aquí, a la cuenta.
        val lists = prefs.playlists.first()
        lists.forEachIndexed { i, l ->
            val ids = l.songs.map { it.id }
            val s = saved.lists[l.id]
            if (s == null) {
                _state.value = CloudState.Working("Guardando «${l.name}» (${i + 1} de ${lists.size})")
                val yt = YouTube.createPlaylist(l.name, ids.take(FIRST)).getOrThrow()
                ids.drop(FIRST).chunked(CHUNK).forEach { YouTube.editPlaylist(yt, add = it).getOrThrow() }
                saved = saved.copy(lists = saved.lists + (l.id to Synced(yt, l.name, ids)))
                save(saved)
            } else {
                val had = s.ids.toHashSet()
                val now = ids.toHashSet()
                val add = ids.filter { it !in had }
                val remove = s.ids.filter { it !in now }
                val name = l.name.takeIf { it != s.name }
                if (add.isEmpty() && remove.isEmpty() && name == null) return@forEachIndexed
                _state.value = CloudState.Working("Guardando «${l.name}»")
                YouTube.editPlaylist(s.yt, remove = remove.take(CHUNK), name = name).getOrThrow()
                remove.drop(CHUNK).chunked(CHUNK).forEach { YouTube.editPlaylist(s.yt, remove = it).getOrThrow() }
                add.chunked(CHUNK).forEach { YouTube.editPlaylist(s.yt, add = it).getOrThrow() }
                saved = saved.copy(lists = saved.lists + (l.id to Synced(s.yt, l.name, ids)))
                save(saved)
            }
        }

        // 3. Las que borraste aquí, también allí. Nunca si de pronto no hay ninguna: eso huele a error al leerlas.
        if (lists.isNotEmpty()) {
            val here = lists.mapTo(HashSet()) { it.id }
            for ((id, s) in saved.lists.filterKeys { it !in here }) {
                YouTube.deletePlaylist(s.yt).onFailure { Timber.w(it, "copia: borrar %s", s.yt) }
                saved = saved.copy(lists = saved.lists - id)
                save(saved)
            }
        }

        // 4. La descripción de la copia: carpetas, fijadas y orden.
        val manifest = Manifest(
            lists.mapNotNull { l -> saved.lists[l.id]?.let { Manifest.Entry(l.id, it.yt, l.name, l.folder, l.from) } },
            prefs.folders.first(), prefs.pinned.first(), prefs.sortFlipped.first(),
        ).encode()
        if (manifest != saved.written || manifestId == null) {
            val id = manifestId ?: YouTube.createPlaylist(MANIFEST_TITLE, emptyList()).getOrThrow()
            YouTube.editPlaylist(id, description = MANIFEST_NOTE + manifest).getOrThrow()
            saved = saved.copy(manifest = id, written = manifest)
            save(saved)
        }
        _state.value = CloudState.Done(saved.lists.size, System.currentTimeMillis())
    }

    /** Tus me gusta de YouTube Music (con los que Oto subió), a Me gusta. Ya constan como enviados. */
    private suspend fun restoreLikes() {
        _state.value = CloudState.Working("Recuperando Me gusta")
        val songs = songs("LM") ?: return
        prefs.markLikesSent(songs.map { it.id })
        prefs.addLikedAll(songs)
    }

    /** Todas las canciones de una lista de YouTube, página a página. */
    private suspend fun songs(yt: String): List<Heard>? {
        val first = YouTube.playlist(yt).getOrElse { Timber.w(it, "copia: leer %s", yt); return null }
        val all = first.songs.toMutableList<SongItem>()
        var next = first.songsContinuation ?: first.continuation
        while (next != null) {
            val page = YouTube.playlistContinuation(next).getOrElse { Timber.w(it, "copia: seguir %s", yt); break }
            all += page.songs
            next = page.continuation
        }
        return all.map { it.toHeard() }.distinctBy { it.id }
    }

    /** La copia, buscándola entre tus listas de YouTube Music por su nombre. */
    private suspend fun findManifest(): String? {
        var page = YouTube.library(LIBRARY_PLAYLISTS).getOrNull() ?: return null
        var items = page.items
        var next = page.continuation
        while (true) {
            items.filterIsInstance<PlaylistItem>().firstOrNull { it.title == MANIFEST_TITLE }?.let { return it.id.removePrefix("VL") }
            val c = next ?: return null
            val more = YouTube.libraryContinuation(c).getOrNull() ?: return null
            items = more.items
            next = more.continuation
        }
    }

    private suspend fun readManifest(id: String): Manifest? {
        val raw = YouTube.browseRaw("VL$id").getOrNull() ?: return null
        return Regex(Manifest.PREFIX + "[A-Za-z0-9_-]+").find(raw)?.value?.let(Manifest::decode)
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
                Synced(e.getString("yt"), e.optString("n"), (0 until ids.length()).map(ids::getString))
            },
            o.optString("written").ifEmpty { null },
        )
    }.getOrDefault(Saved(null, emptyMap(), null))

    private suspend fun save(s: Saved) {
        val l = JSONObject()
        s.lists.forEach { (k, v) -> l.put(k, JSONObject().put("yt", v.yt).put("n", v.name).put("ids", JSONArray(v.ids))) }
        prefs.setCloud(JSONObject().put("manifest", s.manifest).put("written", s.written).put("lists", l).toString())
    }

    private companion object {
        const val DEBOUNCE_MS = 4_000L
        const val FIRST = 200
        const val CHUNK = 100
        const val LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"
        const val MANIFEST_TITLE = "Oto · copia de tus listas"
        const val MANIFEST_NOTE = "Oto guarda aquí cómo tienes tus listas (carpetas, fijadas, orden). No la borres. "
    }
}

/**
 * Lo que YouTube no sabe de tus listas, comprimido para caber en una descripción (5000 caracteres; YouTube no
 * deja «<» ni «>», así que va en base64).
 */
internal data class Manifest(val lists: List<Entry>, val folders: Set<String>, val pinned: Set<String>, val flipped: Set<String>) {
    data class Entry(val id: String, val yt: String, val name: String, val folder: String?, val from: String?)

    fun encode(): String {
        val json = JSONObject()
            .put("v", 1)
            .put("l", JSONArray(lists.map { JSONObject().put("i", it.id).put("y", it.yt).put("n", it.name).put("d", it.folder).put("f", it.from) }))
            .put("folders", JSONArray(folders.sorted()))
            .put("pinned", JSONArray(pinned.sorted()))
            .put("flip", JSONArray(flipped.sorted()))
            .toString()
        val d = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(json.toByteArray()); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    companion object {
        const val PREFIX = "oto1:"

        fun decode(s: String): Manifest? = runCatching {
            val bytes = Base64.getUrlDecoder().decode(s.removePrefix(PREFIX))
            val inf = Inflater().apply { setInput(bytes) }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inf.end()
            val o = JSONObject(out.toString(Charsets.UTF_8))
            val l = o.getJSONArray("l")
            fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }.orEmpty()
            Manifest(
                (0 until l.length()).map { i ->
                    val e = l.getJSONObject(i)
                    Entry(e.getString("i"), e.getString("y"), e.optString("n"), e.optString("d").ifEmpty { null }, e.optString("f").ifEmpty { null })
                },
                set("folders"), set("pinned"), set("flip"),
            )
        }.onFailure { Timber.w(it, "copia: descripción ilegible") }.getOrNull()
    }
}
