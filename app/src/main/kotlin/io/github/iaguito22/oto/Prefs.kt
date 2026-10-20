package io.github.iaguito22.oto

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.store by preferencesDataStore("oto")

// Las listas, aparte: con lo importado de Spotify pesan megas, y así oír una canción (historial) no las reescribe.
// Antes vivían en «oto»: la primera vez se mudan.
private val Context.lists by preferencesDataStore("oto_lists", produceMigrations = { ctx ->
    listOf(object : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences) = currentData[PLAYLISTS] == null && ctx.store.data.first()[PLAYLISTS] != null
        override suspend fun migrate(currentData: Preferences): Preferences =
            currentData.toMutablePreferences().apply { ctx.store.data.first()[PLAYLISTS]?.let { this[PLAYLISTS] = it } }
        override suspend fun cleanUp() { ctx.store.edit { it.remove(PLAYLISTS) } }
    })
})
private val TUTORIAL_DONE = booleanPreferencesKey("tutorial_done")
private val HISTORY = stringPreferencesKey("history")
private val PLAYLISTS = stringPreferencesKey("playlists")
private val REMAINING = booleanPreferencesKey("show_remaining")
private val LIKED = stringPreferencesKey("liked")
private val LIKES_SENT = stringSetPreferencesKey("likes_sent")
private val SHUFFLE = booleanPreferencesKey("shuffle")
private val SORT_FLIPPED = stringSetPreferencesKey("sort_flipped")
private val PINNED = stringSetPreferencesKey("pinned")
private val FOLDERS = stringSetPreferencesKey("folders")

/** Lo que se fija arriba en Tus listas: el id de una lista o «d:» y el nombre de una carpeta. */
fun pinKey(list: LocalPlaylist) = list.folder?.let { "d:$it" } ?: list.id

/** El id con el que «Me gusta» se abre y suena como si fuera una lista tuya más. */
const val LIKED_ID = "liked"

/** Una canción oída o guardada. Lo justo para enseñarla y volver a ponerla. */
data class Heard(val id: String, val title: String, val artist: String, val artwork: String?, val artistId: String? = null)

fun Heard.toSong() = SongItem(id = id, title = title, artists = listOf(Artist(artist, artistId)), thumbnail = artwork.orEmpty())

fun SongItem.toHeard() = Heard(id, title, artists.joinToString { it.name }, thumbnail, artists.firstOrNull()?.id)

/** Una lista tuya, guardada en el móvil. [from]: la lista de Spotify de la que se importó, si viene de una. */
data class LocalPlaylist(val id: String, val name: String, val songs: List<Heard>, val from: String? = null, val folder: String? = null)

/** Lo que la app recuerda entre sesiones. */
class Prefs(private val context: Context) {
    val tutorialDone: Flow<Boolean> = context.store.data.map { it[TUTORIAL_DONE] ?: false }

    suspend fun setTutorialDone(done: Boolean) {
        context.store.edit { it[TUTORIAL_DONE] = done }
    }

    /** A la derecha de la barra: lo que falta (-1:23) o lo que dura (3:45). */
    val showRemaining: Flow<Boolean> = context.store.data.map { it[REMAINING] ?: true }

    suspend fun setShowRemaining(on: Boolean) {
        context.store.edit { it[REMAINING] = on }
    }

    /** Aleatorio, como el botón de Spotify: vale para todo lo que se ponga hasta quitarlo. */
    val shuffle: Flow<Boolean> = context.store.data.map { it[SHUFFLE] ?: false }.distinctUntilChanged()

    suspend fun setShuffle(on: Boolean) {
        context.store.edit { it[SHUFFLE] = on }
    }

    /**
     * Las listas que se ven al revés de lo normal. Lo normal: Me gusta, lo último que te gustó arriba; las demás, en el
     * orden en que entraron (como en Spotify).
     */
    val sortFlipped: Flow<Set<String>> = context.store.data.map { it[SORT_FLIPPED].orEmpty() }.distinctUntilChanged()

    suspend fun flipSort(id: String) {
        context.store.edit { p -> p[SORT_FLIPPED] = p[SORT_FLIPPED].orEmpty().let { if (id in it) it - id else it + id } }
    }

    val pinned: Flow<Set<String>> = context.store.data.map { it[PINNED].orEmpty() }.distinctUntilChanged()

    suspend fun togglePin(key: String) {
        context.store.edit { p -> p[PINNED] = p[PINNED].orEmpty().let { if (key in it) it - key else it + key } }
    }

    private suspend fun renamePin(old: String, new: String?) {
        context.store.edit { p ->
            val set = p[PINNED].orEmpty()
            if (old in set) p[PINNED] = set - old + listOfNotNull(new)
        }
    }

    val history: Flow<List<Heard>> = context.store.data.map { decode(it[HISTORY]) }

    /** La más reciente primero, sin repetidas, hasta [HISTORY_SIZE]. */
    suspend fun addHeard(h: Heard) {
        context.store.edit { p ->
            val list = listOf(h) + decode(p[HISTORY]).filter { it.id != h.id }
            p[HISTORY] = encode(list.take(HISTORY_SIZE))
        }
    }

    // ---- Me gusta ----

    /** La última que te gustó, la primera. */
    val liked: Flow<List<Heard>> = context.store.data.map { it[LIKED] }.distinctUntilChanged().map(::decode).flowOn(Dispatchers.Default)

    val likedIds: Flow<Set<String>> = liked.map { l -> l.mapTo(HashSet()) { it.id } }

    /** «Me gusta» como lista: así se enseña y suena igual que las tuyas. */
    val likedList: Flow<LocalPlaylist> = liked.map { LocalPlaylist(LIKED_ID, "Me gusta", it) }

    /** Entra la primera o sale. */
    suspend fun setLiked(h: Heard, on: Boolean) {
        context.store.edit { p ->
            val rest = decode(p[LIKED]).filter { it.id != h.id }
            p[LIKED] = encode(if (on) listOf(h) + rest else rest)
        }
    }

    /** Varias a la vez (un álbum): entran las que falten, delante y en su orden; o salen todas. */
    suspend fun setLikedAll(songs: List<Heard>, on: Boolean) {
        context.store.edit { p ->
            val ids = songs.mapTo(HashSet()) { it.id }
            val rest = decode(p[LIKED]).filter { it.id !in ids }
            p[LIKED] = encode(if (on) songs.distinctBy { it.id } + rest else rest)
        }
    }

    /** Las que aún no estaban, detrás de las que ya te gustaban (lo importado es más antiguo). */
    suspend fun addLikedAll(songs: List<Heard>) {
        context.store.edit { p ->
            val list = decode(p[LIKED])
            val have = list.mapTo(HashSet()) { it.id }
            p[LIKED] = encode(list + songs.filter { have.add(it.id) })
        }
    }

    /** Devuelve si ahora te gusta. */
    suspend fun toggleLike(h: Heard): Boolean {
        var now = false
        context.store.edit { p ->
            val list = decode(p[LIKED])
            now = list.none { it.id == h.id }
            p[LIKED] = encode(if (now) listOf(h) + list else list.filter { it.id != h.id })
        }
        return now
    }

    /** Los me gusta que ya sabe YouTube Music (con cuenta, ver Account). */
    suspend fun likesSent(): Set<String> = context.store.data.first()[LIKES_SENT].orEmpty()

    suspend fun markLikeSent(id: String, on: Boolean) {
        context.store.edit { p -> p[LIKES_SENT] = p[LIKES_SENT].orEmpty().let { if (on) it + id else it - id } }
    }

    suspend fun clearLikesSent() {
        context.store.edit { it.remove(LIKES_SENT) }
    }

    // ---- Listas ----

    // Decodificar varios megas de JSON no puede ir en el hilo de la interfaz.
    val playlists: Flow<List<LocalPlaylist>> = context.lists.data.map { it[PLAYLISTS] }.distinctUntilChanged().map(::decodeLists).flowOn(Dispatchers.Default)

    /** La nueva va la primera; devuelve su id. */
    suspend fun createPlaylist(name: String, first: Heard? = null): String = createPlaylist(name, listOfNotNull(first))

    suspend fun createPlaylist(name: String, songs: List<Heard>): String {
        val id = UUID.randomUUID().toString()
        editLists { listOf(LocalPlaylist(id, name.trim(), songs.distinctBy { it.id })) + it }
        return id
    }

    /**
     * Una lista traída de Spotify. Si ya se importó antes (mismo [from]), se rehace en su sitio con lo nuevo; si no,
     * entra en la posición [at], para que un lote quede en el mismo orden que en Spotify.
     */
    suspend fun importPlaylist(from: String, name: String, songs: List<Heard>, at: Int, folder: String? = null) = editLists { l ->
        val clean = songs.distinctBy { it.id }
        if (l.any { it.from == from }) l.map { if (it.from == from) it.copy(name = name, songs = clean, folder = folder ?: it.folder) else it }
        else l.toMutableList().apply { add(at.coerceIn(0, size), LocalPlaylist(UUID.randomUUID().toString(), name, clean, from, folder)) }
    }

    suspend fun renamePlaylist(id: String, name: String) = editLists { l -> l.map { if (it.id == id) it.copy(name = name.trim()) else it } }

    suspend fun deletePlaylist(id: String) {
        editLists { l -> l.filter { it.id != id } }
        renamePin(id, null)
    }

    // ---- Carpetas: una carpeta es un nombre que comparten varias listas ----

    /** Las carpetas creadas con «Nueva → Carpeta»: así existen aunque aún no tengan listas. */
    val folders: Flow<Set<String>> = context.store.data.map { it[FOLDERS].orEmpty() }.distinctUntilChanged()

    suspend fun createFolder(name: String) {
        context.store.edit { p -> p[FOLDERS] = p[FOLDERS].orEmpty() + name.trim() }
    }

    private suspend fun forgetFolder(old: String, new: String?) {
        context.store.edit { p -> p[FOLDERS] = p[FOLDERS].orEmpty() - old + listOfNotNull(new) }
    }

    /** A la carpeta [folder] (null: fuera de cualquiera). Entra la primera de las suyas. */
    suspend fun moveToFolder(id: String, folder: String?) = editLists { l ->
        val p = l.firstOrNull { it.id == id } ?: return@editLists l
        val rest = l.filter { it.id != id }
        val moved = p.copy(folder = folder?.trim()?.ifEmpty { null })
        val at = rest.indexOfFirst { it.folder == moved.folder && moved.folder != null }
        rest.toMutableList().apply { if (at >= 0) add(at, moved) else add(0, moved) }
    }

    suspend fun renameFolder(old: String, name: String) {
        editLists { l -> l.map { if (it.folder == old) it.copy(folder = name.trim()) else it } }
        renamePin("d:$old", "d:${name.trim()}")
        forgetFolder(old, name.trim())
    }

    /** Deshace la carpeta: sus listas vuelven a Tus listas, no se borra ninguna. */
    suspend fun ungroup(folder: String) {
        editLists { l -> l.map { if (it.folder == folder) it.copy(folder = null) else it } }
        renamePin("d:$folder", null)
        forgetFolder(folder, null)
    }

    /** Las que falten, al final y en orden. */
    suspend fun addAllToPlaylist(id: String, songs: List<Heard>) = editLists { l ->
        l.map { p ->
            if (p.id != id) p else {
                val have = p.songs.mapTo(HashSet()) { it.id }
                p.copy(songs = p.songs + songs.filter { have.add(it.id) })
            }
        }
    }

    suspend fun removeAllFromPlaylist(id: String, ids: Set<String>) = editLists { l ->
        l.map { p -> if (p.id == id) p.copy(songs = p.songs.filter { it.id !in ids }) else p }
    }

    /** Al final, sin repetirla. false si ya estaba. */
    suspend fun addToPlaylist(id: String, song: Heard): Boolean {
        var added = false
        editLists { l ->
            l.map { p ->
                if (p.id != id || p.songs.any { it.id == song.id }) p
                else { added = true; p.copy(songs = p.songs + song) }
            }
        }
        return added
    }

    suspend fun removeFromPlaylist(id: String, songId: String) = editLists { l ->
        l.map { p -> if (p.id == id) p.copy(songs = p.songs.filter { it.id != songId }) else p }
    }

    private suspend fun editLists(f: (List<LocalPlaylist>) -> List<LocalPlaylist>) {
        context.lists.edit { p -> p[PLAYLISTS] = encodeLists(f(decodeLists(p[PLAYLISTS]))) }
    }

    private fun encodeLists(list: List<LocalPlaylist>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("n", it.name).put("s", JSONArray(encode(it.songs))).put("f", it.from).put("d", it.folder)) }
    }.toString()

    private fun decodeLists(s: String?): List<LocalPlaylist> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            LocalPlaylist(o.getString("id"), o.optString("n"), decode(o.optJSONArray("s")?.toString()), o.optString("f").ifEmpty { null }, o.optString("d").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    private fun encode(list: List<Heard>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("t", it.title).put("a", it.artist).put("i", it.artwork).put("ai", it.artistId)) }
    }.toString()

    private fun decode(s: String?): List<Heard> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Heard(o.getString("id"), o.optString("t"), o.optString("a"), o.optString("i").ifEmpty { null }, o.optString("ai").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val HISTORY_SIZE = 50
    }
}
