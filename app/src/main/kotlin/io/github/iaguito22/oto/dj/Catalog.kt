package io.github.iaguito22.oto.dj

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import io.github.iaguito22.oto.importer.Matcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Un artista de YouTube Music: sus canciones más oídas, de más a menos, y a quién se parece según YouTube. */
data class ArtistInfo(val id: String, val name: String, val top: List<Track>, val related: List<Pair<String, String>>, val more: String?)

/** Lo que el DJ pide fuera. Aparte, para poder probar el DJ sin red. */
interface Catalog {
    suspend fun artist(id: String): ArtistInfo?

    /** Todas sus canciones conocidas, no solo las cinco de la portada. */
    suspend fun songs(info: ArtistInfo): List<Track>

    /** La radio de una canción. */
    suspend fun radio(id: String): List<Track>

    /** El id de un artista por su nombre. */
    suspend fun find(name: String): String?
}

/**
 * [Catalog] con YouTube Music, guardando una semana lo de cada artista en [dir] (si lo hay): cambia poco y así cada
 * sesión pide solo lo nuevo.
 */
class YouTubeCatalog(private val dir: File?) : Catalog {
    private val memory = ConcurrentHashMap<String, JSONObject>()

    override suspend fun artist(id: String): ArtistInfo? = cached("a-$id", WEEK) {
        val page = YouTube.artist(id).getOrThrow()
        val top = page.sections.firstOrNull { s -> s.items.isNotEmpty() && s.items.all { it is SongItem } }
        val related = page.sections.lastOrNull { s -> s.items.isNotEmpty() && s.items.all { it is ArtistItem } }
        JSONObject()
            .put("n", page.artist.title)
            .put("top", tracks(top?.items.orEmpty().filterIsInstance<SongItem>().map { it.toTrack() }))
            .put("rel", JSONArray().apply { related?.items?.forEach { put(JSONArray().put(it.id).put(it.title)) } })
            .put("more", top?.moreEndpoint?.browseId)
    }?.let { o ->
        val rel = o.getJSONArray("rel")
        ArtistInfo(
            id, o.optString("n"), tracks(o.getJSONArray("top")),
            (0 until rel.length()).map { rel.getJSONArray(it).let { r -> r.getString(0) to r.getString(1) } },
            o.optString("more").ifEmpty { null },
        )
    }

    override suspend fun songs(info: ArtistInfo): List<Track> {
        val more = info.more ?: return info.top
        return cached("s-${info.id}", 2 * WEEK) {
            val page = YouTube.playlist(more.removePrefix("VL")).getOrThrow()
            JSONObject().put("t", tracks(page.songs.map { it.toTrack() }))
        }?.let { tracks(it.getJSONArray("t")) } ?: info.top
    }

    override suspend fun radio(id: String): List<Track> = withContext(Dispatchers.IO) {
        YouTube.next(WatchEndpoint(videoId = id, playlistId = "RDAMVM$id")).getOrNull()?.items?.map { it.toTrack() }.orEmpty()
    }

    override suspend fun find(name: String): String? = cached("f-${Matcher.norm(name)}", 8 * WEEK) {
        val items = YouTube.search(name, YouTube.SearchFilter.FILTER_ARTIST).getOrThrow().items.filterIsInstance<ArtistItem>()
        val want = Matcher.norm(name)
        val hit = items.firstOrNull { Matcher.norm(it.title) == want } ?: items.firstOrNull()?.takeIf { Matcher.norm(it.title).contains(want) }
        JSONObject().put("id", hit?.id)
    }?.optString("id")?.ifEmpty { null }

    /** De memoria, del disco si no ha caducado o de la red; null si la red falla. */
    private suspend fun cached(name: String, ttl: Long, fetch: suspend () -> JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        memory[name]?.let { return@withContext it }
        val file = dir?.let { File(it, name.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json") }
        val now = System.currentTimeMillis()
        file?.takeIf { it.exists() && now - it.lastModified() < ttl }?.let { f ->
            runCatching { JSONObject(f.readText()) }.getOrNull()?.let { memory[name] = it; return@withContext it }
        }
        runCatching { fetch() }.getOrNull()?.also { o ->
            memory[name] = o
            file?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(o.toString()) } }
        }
    }

    private fun tracks(list: List<Track>) = JSONArray().apply {
        list.forEach { t ->
            put(JSONObject().put("id", t.id).put("t", t.title).put("a", JSONArray(t.artists)).put("ai", JSONArray(t.artistIds.map { it ?: "" }))
                .put("i", t.artwork).put("s", t.seconds).put("u", t.upload))
        }
    }

    private fun tracks(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val names = o.getJSONArray("a")
        val ids = o.optJSONArray("ai") ?: JSONArray()
        Track(
            o.getString("id"), o.optString("t"),
            (0 until names.length()).map(names::getString),
            (0 until ids.length()).map { ids.getString(it).ifEmpty { null } },
            o.optString("i").ifEmpty { null }, o.optInt("s").takeIf { it > 0 }, o.optBoolean("u"),
        )
    }

    private companion object {
        const val WEEK = 7 * DAY
    }
}
