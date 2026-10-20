package io.github.iaguito22.oto

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.store by preferencesDataStore("oto")
private val TUTORIAL_DONE = booleanPreferencesKey("tutorial_done")
private val HISTORY = stringPreferencesKey("history")
private val PLAYLISTS = stringPreferencesKey("playlists")
private val REMAINING = booleanPreferencesKey("show_remaining")
private val LIKED = stringPreferencesKey("liked")

/** El id con el que «Me gusta» se abre y suena como si fuera una lista tuya más. */
const val LIKED_ID = "liked"

/** Una canción oída o guardada. Lo justo para enseñarla y volver a ponerla. */
data class Heard(val id: String, val title: String, val artist: String, val artwork: String?, val artistId: String? = null)

fun Heard.toSong() = SongItem(id = id, title = title, artists = listOf(Artist(artist, artistId)), thumbnail = artwork.orEmpty())

fun SongItem.toHeard() = Heard(id, title, artists.joinToString { it.name }, thumbnail, artists.firstOrNull()?.id)

/** Una lista tuya, guardada en el móvil. */
data class LocalPlaylist(val id: String, val name: String, val songs: List<Heard>)

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
    val liked: Flow<List<Heard>> = context.store.data.map { decode(it[LIKED]) }

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

    // ---- Listas ----

    val playlists: Flow<List<LocalPlaylist>> = context.store.data.map { decodeLists(it[PLAYLISTS]) }

    /** La nueva va la primera; devuelve su id. */
    suspend fun createPlaylist(name: String, first: Heard? = null): String {
        val id = UUID.randomUUID().toString()
        editLists { listOf(LocalPlaylist(id, name.trim(), listOfNotNull(first))) + it }
        return id
    }

    suspend fun renamePlaylist(id: String, name: String) = editLists { l -> l.map { if (it.id == id) it.copy(name = name.trim()) else it } }

    suspend fun deletePlaylist(id: String) = editLists { l -> l.filter { it.id != id } }

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
        context.store.edit { p -> p[PLAYLISTS] = encodeLists(f(decodeLists(p[PLAYLISTS]))) }
    }

    private fun encodeLists(list: List<LocalPlaylist>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("n", it.name).put("s", JSONArray(encode(it.songs)))) }
    }.toString()

    private fun decodeLists(s: String?): List<LocalPlaylist> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            LocalPlaylist(o.getString("id"), o.optString("n"), decode(o.optJSONArray("s")?.toString()))
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
