package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.importer.Matcher
import io.github.iaguito22.oto.lyrics.LyricsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Un género de los que más escuchas: su nombre, tus canciones de ese género ([Track.key]), los artistas tuyos que
 * sobre todo lo hacen y cuánto pesa en lo tuyo.
 */
data class DjGenre(val name: String, val songs: Set<String>, val artists: Set<String>, val weight: Double)

/**
 * El género de cada canción tuya. YouTube no lo da, así que se pregunta a la búsqueda pública de iTunes (sin cuenta ni
 * clave; solo viajan el título y el artista) y se guarda. Por canción y no por artista porque un artista puede tocar
 * varios (pop y reguetón); si la canción no está en iTunes, vale el género del artista. Lo que no se encuentra se
 * vuelve a intentar al mes. Solo se aceptan resultados del mismo artista (hay versiones y homónimos de otros géneros).
 */
class GenreStore(
    private val file: File?,
    private val songLookup: suspend (title: String, artist: String) -> String? = ::itunesSongGenre,
    private val artistLookup: suspend (String) -> String? = ::itunesArtistGenre,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val genre: String, val at: Long)

    /** «s:» + canción, «a:» + artista → género ya agrupado ([family]); vacío si no se encontró. */
    private val map = HashMap<String, Entry>()
    private val lock = Mutex()
    private var loaded = false

    fun songGenre(key: String): String? = synchronized(map) { map["s:$key"]?.genre?.ifEmpty { null } }

    /** El de iTunes para el artista, o el que más se repite entre sus canciones que ya se conocen. */
    fun artistGenre(key: String, songs: Collection<String> = emptyList()): String? {
        if (songs.isNotEmpty()) {
            songs.mapNotNull(::songGenre).groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { return it.key }
        }
        return synchronized(map) { map["a:$key"]?.genre?.ifEmpty { null } }
    }

    /** El género de una canción: el suyo o, si no se sabe, el de su artista. */
    fun genreOf(t: Track, profile: Profile? = null): String? =
        songGenre(t.key) ?: artistGenre(t.who, profile?.artists?.get(t.who)?.songs?.keys.orEmpty())

    /** Las canciones de [profile] que más pesan y aún no tienen género mirado. */
    suspend fun missing(profile: Profile): Int {
        load()
        return synchronized(map) { wanted(profile).count { needs("s:${it.key}") } }
    }

    /**
     * Mira el género de hasta [max] canciones tuyas que falten (las que más pesan primero), poco a poco: iTunes limita
     * a ~20 consultas por minuto. Si una no está, prueba con su artista. [onFound] se llama tras cada hallazgo.
     */
    suspend fun fill(profile: Profile, max: Int = MAX_LOOKUPS, onFound: () -> Unit = {}): Int = lock.withLock {
        load()
        var done = 0
        for (t in wanted(profile)) {
            if (done >= max) break
            if (!synchronized(map) { needs("s:${t.key}") }) continue
            if (done > 0) delay(GAP_MS)
            val raw = runCatching { songLookup(t.title, t.artist) }.getOrElse { break } // sin red: otra vez será
            val genre = raw?.let(::family).orEmpty()
            put("s:${t.key}", genre)
            done++
            if (genre.isEmpty() && synchronized(map) { needs("a:${t.who}") }) {
                delay(GAP_MS)
                val a = runCatching { artistLookup(t.artists.firstOrNull() ?: t.artist) }.getOrElse { break }
                put("a:${t.who}", a?.let(::family).orEmpty())
                done++
            }
            if (genreOf(t, profile) != null) onFound()
            if (done % SAVE_EVERY == 0) save()
        }
        if (done > 0) save()
        done
    }

    /** Tus géneros, de más a menos escuchado: los que juntan al menos [MIN_SONGS] canciones de dos artistas o más. */
    fun top(profile: Profile, max: Int = 6): List<DjGenre> {
        class Acc { val songs = HashSet<String>(); val artists = HashMap<String, Int>(); var w = 0.0 }
        val groups = HashMap<String, Acc>()
        for (a in profile.core.take(TOP_ARTISTS)) for (t in a.songs.values) {
            val g = genreOf(t, profile) ?: continue
            val acc = groups.getOrPut(g, ::Acc)
            acc.songs += t.key
            acc.artists.merge(a.key, 1, Int::plus)
            acc.w += (profile.weight[t.key] ?: 0.5) + 0.2 * a.affinity
        }
        return groups.filter { it.value.songs.size >= MIN_SONGS && it.value.artists.size >= 2 }
            .map { (name, acc) ->
                // Un artista es «de» este género si al menos un tercio de lo suyo que se conoce lo es.
                val main = acc.artists.filter { (k, n) ->
                    val known = profile.artists[k]?.songs?.values?.count { genreOf(it, profile) != null } ?: n
                    n * 3 >= known
                }.keys
                DjGenre(name, acc.songs, main, acc.w)
            }
            .sortedByDescending { it.weight }
            .take(max)
    }

    private fun wanted(profile: Profile): List<Track> =
        profile.core.take(TOP_ARTISTS).flatMap { it.songs.values }
            .sortedByDescending { profile.weight[it.key] ?: 0.0 }
            .take(TOP_SONGS)

    private fun needs(key: String): Boolean {
        val e = map[key] ?: return true
        return e.genre.isEmpty() && now() - e.at > RETRY_MS
    }

    private fun put(key: String, genre: String) = synchronized(map) { map[key] = Entry(genre, now()) }

    private suspend fun load() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            runCatching {
                val f = file?.takeIf { it.exists() } ?: return@runCatching
                val o = JSONObject(f.readText())
                synchronized(map) {
                    for (k in o.keys()) {
                        val a = o.getJSONArray(k)
                        map[k] = Entry(a.getString(0), a.getLong(1))
                    }
                }
            }
        }
        loaded = true
    }

    /** Lo ya guardado, sin red: para enseñar los géneros nada más abrir. */
    suspend fun ready(): GenreStore = apply { load() }

    private suspend fun save() = withContext(Dispatchers.IO) {
        val f = file ?: return@withContext
        runCatching {
            val o = JSONObject()
            synchronized(map) { for ((k, e) in map) o.put(k, JSONArray().put(e.genre).put(e.at)) }
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    companion object {
        const val MAX_LOOKUPS = 60
        private const val TOP_ARTISTS = 150
        private const val TOP_SONGS = 400
        private const val MIN_SONGS = 4
        private const val GAP_MS = 3_200L
        private const val SAVE_EVERY = 5
        private const val RETRY_MS = 30L * DAY

        /** Agrupa los géneros de iTunes en familias reconocibles (sin perder los que no se conocen). */
        fun family(raw: String): String? {
            val g = raw.trim()
            if (g.isEmpty()) return null
            val n = Matcher.norm(g)
            return when {
                "urbano" in n || "reggaeton" in n || "regueton" in n || "latin urban" in n -> "Urbano latino"
                "k pop" in n || "kpop" in n -> "K-Pop"
                "j pop" in n || "jpop" in n -> "J-Pop"
                "anime" in n -> "Anime"
                "hip hop" in n || "rap" in n -> "Hip-hop/Rap"
                "pop latino" in n || "latin pop" in n -> "Pop latino"
                "latin" in n || "latino" in n || "salsa" in n || "bachata" in n || "cumbia" in n || "regional mexicano" in n -> "Latino"
                "r and b" in n || "r b" in n || "soul" in n -> "R&B"
                "metal" in n -> "Metal"
                "punk" in n || "rock" in n -> "Rock"
                "alternativ" in n || "indie" in n -> "Alternativa"
                "electron" in n || "dance" in n || "house" in n || "techno" in n || "edm" in n -> "Electrónica"
                "banda sonora" in n || "soundtrack" in n -> "Bandas sonoras"
                "singer" in n || "cantautor" in n -> "Cantautor"
                "pop" in n -> "Pop"
                else -> g
            }
        }

        private fun get(url: String): JSONArray? {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 7000
            c.readTimeout = 7000
            c.setRequestProperty("User-Agent", "Oto (https://github.com/iaguito22/oto)")
            try {
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                val body = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                return JSONObject(body).optJSONArray("results")
            } finally {
                c.disconnect()
            }
        }

        private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)

        /** El género de la canción [title] de [artist] en iTunes (España), solo si es del mismo artista y título. */
        suspend fun itunesSongGenre(title: String, artist: String): String? = withContext(Dispatchers.IO) {
            val q = URLEncoder.encode("$artist ${LyricsSource.cleanTitle(title)}", "UTF-8")
            val results = get("https://itunes.apple.com/search?term=$q&entity=song&limit=10&country=ES&lang=es_es") ?: return@withContext null
            results.objects().firstOrNull {
                LyricsSource.sameTrackArtist(artist, it.optString("artistName")) &&
                    LyricsSource.sameTrackTitle(title, it.optString("trackName"))
            }?.optString("primaryGenreName")?.ifEmpty { null }
        }

        /** El género principal de [artist] en iTunes (España), o null si no hay uno con ese nombre. */
        suspend fun itunesArtistGenre(artist: String): String? = withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(artist, "UTF-8")
            val results = get("https://itunes.apple.com/search?term=$q&entity=musicArtist&limit=5&country=ES&lang=es_es") ?: return@withContext null
            val want = Matcher.norm(artist)
            results.objects().firstOrNull { Matcher.norm(it.optString("artistName")) == want }
                ?.optString("primaryGenreName")?.ifEmpty { null }
        }
    }
}
