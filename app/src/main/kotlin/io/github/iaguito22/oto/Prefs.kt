package io.github.iaguito22.oto

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
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
private val SPOTS = stringPreferencesKey("shelf_spots")
private val CLOUD = stringPreferencesKey("cloud")
private val QUALITY = stringPreferencesKey("audio_quality")
private val DATA_SAVER = booleanPreferencesKey("data_saver")
private val LOOP = booleanPreferencesKey("loop")
private val CROSSFADE = intPreferencesKey("crossfade")
private val ROMANIZE = booleanPreferencesKey("romanize")
private val DROP_STYLE = stringPreferencesKey("drop_style")
private val COVER_BEAT = booleanPreferencesKey("cover_beat")
private val DOWNLOAD_LISTS = stringSetPreferencesKey("download_lists")
private val DOWNLOAD_SONGS = stringPreferencesKey("download_songs")

/** Lo que se fija arriba en Tus listas: el id de una lista o «d:» y el nombre de una carpeta. */
fun pinKey(list: LocalPlaylist) = list.folder?.let { "d:$it" } ?: list.id

/** El id con el que «Me gusta» se abre y suena como si fuera una lista tuya más. */
const val LIKED_ID = "liked"

/** Lo que viene puesto: un fundido corto, que se note sin comerse los finales. */
const val DEFAULT_CROSSFADE = 3
/** Lo que se puede elegir en Ajustes. */
val CROSSFADE_CHOICES = listOf(0, 3, 6, 10)

/** Una canción oída o guardada. Lo justo para enseñarla y volver a ponerla. */
data class Heard(val id: String, val title: String, val artist: String, val artwork: String?, val artistId: String? = null)

fun Heard.toSong() = SongItem(id = id, title = title, artists = listOf(Artist(artist, artistId)), thumbnail = artwork.orEmpty())

fun SongItem.toHeard() = Heard(id, title, artists.joinToString { it.name }, thumbnail, artists.firstOrNull()?.id)

/** Una lista tuya, guardada en el móvil. [from]: la lista de Spotify de la que se importó, si viene de una. */
data class LocalPlaylist(val id: String, val name: String, val songs: List<Heard>, val from: String? = null, val folder: String? = null)

/**
 * Auto: la calidad que aguante la conexión, elegida al empezar cada canción (nunca cambia a mitad); si la conexión
 * duda, antes fluida que alta. Máxima: siempre la mejor, aunque tarde más en empezar.
 */
enum class QualityMode { Auto, Max }

/** Cómo se ve la gota: un aro fino del color de acento, como la huella de los Nothing, o la gota líquida. */
enum class DropStyle { Ring, Liquid }

/** Lo que la app recuerda entre sesiones. */
class Prefs(private val context: Context) {
    // Las listas, Me gusta y el historial se decodifican una vez por cambio y se comparten: antes cada pantalla y
    // servicio que los miraba decodificaba por su cuenta los megas de JSON de tus listas (cuatro veces al abrir la app).
    private val shared = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val tutorialDone: Flow<Boolean> = context.store.data.map { it[TUTORIAL_DONE] ?: false }

    suspend fun setTutorialDone(done: Boolean) {
        context.store.edit { it[TUTORIAL_DONE] = done }
    }

    /** A la derecha de la barra: lo que falta (-1:23) o lo que dura (3:45). */
    val showRemaining: Flow<Boolean> = context.store.data.map { it[REMAINING] ?: false }

    suspend fun setShowRemaining(on: Boolean) {
        context.store.edit { it[REMAINING] = on }
    }

    /** Con qué calidad suena: ver [QualityMode]. */
    val quality: Flow<QualityMode> = context.store.data
        .map { p -> QualityMode.entries.firstOrNull { it.name == p[QUALITY] } ?: QualityMode.Auto }
        .distinctUntilChanged()

    suspend fun setQuality(mode: QualityMode) {
        context.store.edit { it[QUALITY] = mode.name }
    }

    /** Con datos móviles, la calidad baja aunque la conexión dé para más. */
    val dataSaver: Flow<Boolean> = context.store.data.map { it[DATA_SAVER] ?: false }.distinctUntilChanged()

    suspend fun setDataSaver(on: Boolean) {
        context.store.edit { it[DATA_SAVER] = on }
    }

    /** Aleatorio, como el botón de Spotify: vale para todo lo que se ponga hasta quitarlo. */
    val shuffle: Flow<Boolean> = context.store.data.map { it[SHUFFLE] ?: false }.distinctUntilChanged()

    suspend fun setShuffle(on: Boolean) {
        context.store.edit { it[SHUFFLE] = on }
    }

    /** Segundos de fundido entre canciones (0: sin fundido). Ver [io.github.iaguito22.oto.playback.Crossfade]. */
    val crossfade: Flow<Int> = context.store.data.map { it[CROSSFADE] ?: DEFAULT_CROSSFADE }.distinctUntilChanged()

    suspend fun setCrossfade(seconds: Int) {
        context.store.edit { it[CROSSFADE] = seconds }
    }

    /** Una lista que acaba vuelve a empezar; si no, siguen canciones parecidas (como la reproducción automática). */
    val loop: Flow<Boolean> = context.store.data.map { it[LOOP] ?: false }.distinctUntilChanged()

    suspend fun setLoop(on: Boolean) {
        context.store.edit { it[LOOP] = on }
    }

    /** La letra en otra escritura (japonés, coreano…) lleva debajo cómo se lee. */
    val romanize: Flow<Boolean> = context.store.data.map { it[ROMANIZE] ?: true }.distinctUntilChanged()

    suspend fun setRomanize(on: Boolean) {
        context.store.edit { it[ROMANIZE] = on }
    }

    /** Ver [DropStyle]. Lo que viene puesto es el aro: con él la pantalla queda más limpia. */
    val dropStyle: Flow<DropStyle> = context.store.data
        .map { p -> DropStyle.entries.firstOrNull { it.name == p[DROP_STYLE] } ?: DropStyle.Ring }
        .distinctUntilChanged()

    suspend fun setDropStyle(style: DropStyle) {
        context.store.edit { it[DROP_STYLE] = style.name }
    }

    /** La carátula late un poco con los graves, como la gota. */
    val coverBeat: Flow<Boolean> = context.store.data.map { it[COVER_BEAT] ?: true }.distinctUntilChanged()

    suspend fun setCoverBeat(on: Boolean) {
        context.store.edit { it[COVER_BEAT] = on }
    }

    // ---- Descargas (ver Downloads): lo que pediste tener sin conexión ----

    /** Las listas descargadas (también [LIKED_ID]): lo que entre en ellas se descarga también. */
    val downloadLists: Flow<Set<String>> = context.store.data.map { it[DOWNLOAD_LISTS].orEmpty() }.distinctUntilChanged()

    suspend fun setDownloadList(id: String, on: Boolean) {
        context.store.edit { p -> p[DOWNLOAD_LISTS] = p[DOWNLOAD_LISTS].orEmpty().let { if (on) it + id else it - id } }
    }

    /** Las canciones descargadas una a una, la última primero. */
    val downloadSongs: Flow<List<Heard>> = context.store.data.map { it[DOWNLOAD_SONGS] }.distinctUntilChanged().map(::decode)

    suspend fun setDownloadSong(h: Heard, on: Boolean) {
        context.store.edit { p ->
            val rest = decode(p[DOWNLOAD_SONGS]).filter { it.id != h.id }
            p[DOWNLOAD_SONGS] = encode(if (on) listOf(h) + rest else rest)
        }
    }

    suspend fun clearDownloads() {
        context.store.edit { it.remove(DOWNLOAD_LISTS); it.remove(DOWNLOAD_SONGS) }
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

    val history: Flow<List<Heard>> = context.store.data.map { it[HISTORY] }.distinctUntilChanged().map(::decode)
        .flowOn(Dispatchers.Default).shareIn(shared, SharingStarted.Eagerly, 1)

    /** La más reciente primero, sin repetidas, hasta [HISTORY_SIZE]. */
    suspend fun addHeard(h: Heard) {
        context.store.edit { p ->
            val list = listOf(h) + decode(p[HISTORY]).filter { it.id != h.id }
            p[HISTORY] = encode(list.take(HISTORY_SIZE))
        }
    }

    // ---- Me gusta ----

    /** La última que te gustó, la primera. */
    val liked: Flow<List<Heard>> = context.store.data.map { it[LIKED] }.distinctUntilChanged().map(::decode)
        .flowOn(Dispatchers.Default).shareIn(shared, SharingStarted.Eagerly, 1)

    val likedIds: Flow<Set<String>> = liked.map { l -> l.mapTo(HashSet()) { it.id } }
        .shareIn(shared, SharingStarted.Eagerly, 1)

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

    suspend fun markLikesSent(ids: Collection<String>) {
        context.store.edit { p -> p[LIKES_SENT] = p[LIKES_SENT].orEmpty() + ids }
    }

    suspend fun clearLikesSent() {
        context.store.edit { it.remove(LIKES_SENT) }
    }

    // ---- Listas ----

    // Decodificar varios megas de JSON no puede ir en el hilo de la interfaz.
    val playlists: Flow<List<LocalPlaylist>> = context.lists.data.map { it[PLAYLISTS] }.distinctUntilChanged().map(::decodeLists)
        .flowOn(Dispatchers.Default).shareIn(shared, SharingStarted.Eagerly, 1)

    /** Los ids de todo lo que está en alguna de tus listas: para saber al momento si una canción está guardada. */
    val listedIds: Flow<Set<String>> = playlists.map { l -> l.flatMapTo(HashSet()) { p -> p.songs.map { it.id } } }
        .shareIn(shared, SharingStarted.Eagerly, 1)

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

    /** Tus listas en el orden de [ids] (las que no estén, detrás, como estaban). */
    suspend fun orderLists(ids: List<String>) = editLists { l ->
        val at = ids.withIndex().associate { (i, id) -> id to i }
        l.sortedBy { at[it.id] ?: Int.MAX_VALUE }
    }

    suspend fun renamePlaylist(id: String, name: String) = editLists { l -> l.map { if (it.id == id) it.copy(name = name.trim()) else it } }

    suspend fun deletePlaylist(id: String) {
        editLists { l -> l.filter { it.id != id } }
        renamePin(id, null)
        setDownloadList(id, false)
    }

    // ---- Carpetas: una carpeta es un nombre que comparten varias listas ----

    /** Las carpetas creadas con «Nueva → Carpeta»: así existen aunque aún no tengan listas. */
    val folders: Flow<Set<String>> = context.store.data.map { it[FOLDERS].orEmpty() }.distinctUntilChanged()

    /** Dónde van el DJ y Me gusta en la fila de arriba de Tus listas (por su clave); si no se han movido, delante. */
    val spots: Flow<Map<String, Int>> = context.store.data.map { p ->
        p[SPOTS]?.split(',')?.mapNotNull { e -> e.split(':').takeIf { it.size == 2 }?.let { (k, v) -> v.toIntOrNull()?.let { k to it } } }?.toMap()
            ?: mapOf("dj" to 0, LIKED_ID to 1)
    }.distinctUntilChanged()

    suspend fun setSpots(spots: Map<String, Int>) {
        context.store.edit { it[SPOTS] = spots.entries.joinToString(",") { (k, v) -> "$k:$v" } }
    }

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

    // ---- Copia en la cuenta (ver Cloud) ----

    /** Lo que ya está subido: junto a las listas, porque pesa lo mismo que ellas. */
    suspend fun cloud(): String? = context.lists.data.first()[CLOUD]

    suspend fun setCloud(s: String?) {
        context.lists.edit { p -> if (s == null) p.remove(CLOUD) else p[CLOUD] = s }
    }

    /** Las que falten (por id) entran detrás, en el orden dado; y se suman las carpetas, fijadas y órdenes. */
    suspend fun restore(lists: List<LocalPlaylist>, folders: Set<String>, pinned: Set<String>, flipped: Set<String>) {
        editLists { l ->
            val have = l.mapTo(HashSet()) { it.id }
            l + lists.filter { have.add(it.id) }
        }
        context.store.edit { p ->
            p[FOLDERS] = p[FOLDERS].orEmpty() + folders
            p[PINNED] = p[PINNED].orEmpty() + pinned
            p[SORT_FLIPPED] = p[SORT_FLIPPED].orEmpty() + flipped
        }
    }

    private suspend fun editLists(f: (List<LocalPlaylist>) -> List<LocalPlaylist>) {
        context.lists.edit { p -> p[PLAYLISTS] = encodeLists(f(decodeLists(p[PLAYLISTS]))) }
    }

    // Las canciones de cada lista van dentro del mismo JSON, sin pasar a texto y volver a leerlo (eso duplicaba el
    // trabajo con miles de canciones).
    private fun encodeLists(list: List<LocalPlaylist>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("n", it.name).put("s", songsJson(it.songs)).put("f", it.from).put("d", it.folder)) }
    }.toString()

    private fun decodeLists(s: String?): List<LocalPlaylist> = traced("oto.decodeLists") { decodeListsRaw(s) }

    private fun decodeListsRaw(s: String?): List<LocalPlaylist> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            LocalPlaylist(o.getString("id"), o.optString("n"), runCatching { songs(o.optJSONArray("s")) }.getOrDefault(emptyList()), o.optString("f").ifEmpty { null }, o.optString("d").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    private fun encode(list: List<Heard>) = songsJson(list).toString()

    private fun songsJson(list: List<Heard>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("t", it.title).put("a", it.artist).put("i", it.artwork).put("ai", it.artistId)) }
    }

    private fun decode(s: String?): List<Heard> = runCatching { songs(JSONArray(s ?: return emptyList())) }.getOrDefault(emptyList())

    private fun songs(a: JSONArray?): List<Heard> = if (a == null) emptyList() else (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Heard(o.getString("id"), o.optString("t"), o.optString("a"), o.optString("i").ifEmpty { null }, o.optString("ai").ifEmpty { null })
    }

    private companion object {
        const val HISTORY_SIZE = 50
    }
}
