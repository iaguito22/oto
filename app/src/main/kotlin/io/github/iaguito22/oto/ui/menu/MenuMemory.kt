package io.github.iaguito22.oto.ui.menu

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.YTItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.menuStore by preferencesDataStore("oto_menu")
private val RECENTS = stringPreferencesKey("recents")
private val PLAYED = stringPreferencesKey("lists_played")
private val LISTS_SORT = stringPreferencesKey("lists_sort")

/**
 * Lo que recuerda el Menú y nada más necesita: lo buscado hace poco y cuándo sonó por última vez cada lista o carpeta.
 * En su propio DataStore: no se mezcla con Prefs ni con las listas.
 */
class MenuMemory private constructor(private val context: Context) {

    /** Lo último buscado o abierto desde la búsqueda, lo más reciente primero. */
    val recents: Flow<List<Recent>> = context.menuStore.data.map { decodeRecents(it[RECENTS]) }.distinctUntilChanged()

    /** Lo buscado entra arriba; si ya estaba (el mismo texto o lo mismo abierto), sube. */
    suspend fun remember(r: Recent) {
        context.menuStore.edit { p ->
            val rest = decodeRecents(p[RECENTS]).filter { it.key != r.key }
            p[RECENTS] = encodeRecents((listOf(r) + rest).take(RECENTS_SIZE))
        }
    }

    suspend fun forget(r: Recent) {
        context.menuStore.edit { p -> p[RECENTS] = encodeRecents(decodeRecents(p[RECENTS]).filter { it.key != r.key }) }
    }

    /** Cuándo sonó cada lista ([LocalPlaylist.id]) o carpeta («d:» + nombre, como al fijarla), en ms. */
    val played: Flow<Map<String, Long>> = context.menuStore.data.map { p ->
        p[PLAYED]?.let { s -> runCatching { JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } }.getOrNull() }.orEmpty()
    }.distinctUntilChanged()

    suspend fun markPlayed(key: String, at: Long) {
        context.menuStore.edit { p ->
            val o = p[PLAYED]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            p[PLAYED] = o.put(key, at).toString()
        }
    }

    /** Cómo se ordena «Todas tus listas». */
    val listsSort: Flow<ListsSort> = context.menuStore.data.map { p ->
        ListsSort.entries.firstOrNull { it.name == p[LISTS_SORT] } ?: ListsSort.Recent
    }.distinctUntilChanged()

    suspend fun setListsSort(sort: ListsSort) {
        context.menuStore.edit { it[LISTS_SORT] = sort.name }
    }

    private fun encodeRecents(list: List<Recent>) = JSONArray().apply {
        list.forEach { r -> put(JSONObject().put("k", r.kind.name).put("id", r.id).put("t", r.title).put("s", r.sub).put("i", r.art).put("x", r.extra)) }
    }.toString()

    private fun decodeRecents(s: String?): List<Recent> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val kind = Recent.Kind.entries.firstOrNull { it.name == o.optString("k") } ?: return@mapNotNull null
            Recent(kind, o.optString("id"), o.optString("t"), o.optString("s").ifEmpty { null }, o.optString("i").ifEmpty { null }, o.optString("x").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val RECENTS_SIZE = 8

        @Volatile private var instance: MenuMemory? = null

        fun of(context: Context): MenuMemory = instance ?: synchronized(this) {
            instance ?: MenuMemory(context.applicationContext).also { instance = it }
        }
    }
}

enum class ListsSort(val label: String) { Recent("Recientes"), Name("A–Z"), Custom("Tu orden") }

/**
 * Una búsqueda ([Kind.Query], el texto en [title]) o algo abierto desde ella (un artista, un álbum o una lista).
 * [extra]: la playlist de un álbum (para compartirlo al volver a abrirlo).
 */
data class Recent(val kind: Kind, val id: String, val title: String, val sub: String? = null, val art: String? = null, val extra: String? = null) {
    enum class Kind { Query, Artist, Album, Playlist }

    val key get() = if (kind == Kind.Query) "q:" + title.trim().lowercase() else id

    /** Lo abierto, como YouTube lo dio (con lo justo para volver a abrirlo). */
    fun item(): YTItem? = when (kind) {
        Kind.Query -> null
        Kind.Artist -> ArtistItem(id = id, title = title, thumbnail = art, shuffleEndpoint = null, radioEndpoint = null)
        Kind.Album -> AlbumItem(browseId = id, playlistId = extra.orEmpty(), title = title, artists = sub?.let { listOf(Artist(it, null)) }, thumbnail = art.orEmpty())
        Kind.Playlist -> PlaylistItem(
            id = id, title = title, author = sub?.let { Artist(it, null) }, songCountText = null, thumbnail = art,
            playEndpoint = null, shuffleEndpoint = null, radioEndpoint = null,
        )
    }

    companion object {
        fun query(q: String) = Recent(Kind.Query, "", q.trim())

        fun of(item: YTItem): Recent? = when (item) {
            is ArtistItem -> Recent(Kind.Artist, item.id, item.title, null, item.thumbnail)
            is AlbumItem -> Recent(Kind.Album, item.browseId, item.title, item.artists?.joinToString { it.name }?.ifEmpty { null }, item.thumbnail, item.playlistId)
            is PlaylistItem -> Recent(Kind.Playlist, item.id, item.title, item.author?.name, item.thumbnail)
            else -> null
        }
    }
}
