package io.github.iaguito22.oto.importer

import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Una lista de Spotify por traer. [id]: su uri, con la que se reconoce al volver a importarla. [partial]: Spotify
 * solo dejó ver una parte (sin sesión, las 100 primeras).
 */
data class SourceList(
    val id: String,
    val name: String,
    val tracks: List<SourceTrack>,
    val liked: Boolean = false,
    val own: Boolean = true,
    val image: String? = null,
    val partial: Boolean = false,
    /** La carpeta en la que la tenías en Spotify. */
    val folder: String? = null,
)

/** De dónde salen las listas: el archivo de la extensión de Spicetify o el enlace de una lista pública. */
object Spotify {
    /** «Canciones que te gustan»: va a Me gusta, no a una lista nueva. */
    const val LIKED_URI = "spotify:collection:tracks"

    /** Lo que guarda `tools/spicetify/oto-export.js`. */
    fun parseExport(text: String): List<SourceList> {
        val root = JSONObject(text)
        require(root.optInt("oto") >= 1) { "No es una exportación de Oto" }
        val lists = root.getJSONArray("lists")
        return (0 until lists.length()).map { i ->
            val l = lists.getJSONObject(i)
            val tracks = l.getJSONArray("tracks")
            SourceList(
                id = l.getString("id"),
                name = l.optString("name"),
                liked = l.optBoolean("liked"),
                own = l.optBoolean("own", true),
                image = image(l.optString("image")),
                folder = l.optString("folder").takeIf { it.isNotEmpty() && it != "null" },
                tracks = (0 until tracks.length()).map { j ->
                    val t = tracks.getJSONObject(j)
                    val a = t.optJSONArray("a") ?: JSONArray()
                    SourceTrack(
                        t.optString("t"),
                        (0 until a.length()).map(a::getString),
                        t.optString("al").takeIf { it.isNotEmpty() && it != "null" },
                        t.optLong("d").takeIf { it > 0 },
                    )
                }.filter { it.title.isNotBlank() },
            )
        }
    }

    /** Las primeras versiones de la extensión guardaban la uri y no la URL: "spotify:image:…" o el mosaico "spotify:mosaic:a:b:c:d". */
    private fun image(u: String): String? = when {
        u.isEmpty() || u == "null" -> null
        u.startsWith("spotify:image:") -> IMAGES + u.removePrefix("spotify:image:")
        u.startsWith("spotify:mosaic:") -> IMAGES + u.removePrefix("spotify:mosaic:").substringBefore(':')
        else -> u
    }

    /** «open.spotify.com/playlist/…?si=…», «/intl-es/album/…» o «spotify:playlist:…». */
    fun parseLink(link: String): Pair<String, String>? {
        val m = Regex("""(playlist|album)[/:]([A-Za-z0-9]{22})""").find(link.trim()) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /**
     * Una lista o un álbum públicos, sin sesión: la página para incrustar trae el contenido en __NEXT_DATA__.
     * Spotify ahí solo enseña las 100 primeras.
     */
    fun fromLink(link: String): Result<SourceList> = runCatching {
        val (type, id) = parseLink(link) ?: throw IllegalArgumentException("Eso no es un enlace de una lista o un álbum de Spotify")
        val html = get("https://open.spotify.com/embed/$type/$id") ?: throw IOException("Spotify no encuentra esa lista (¿es privada?)")
        val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.groupValues?.get(1) ?: throw IOException("Spotify ha cambiado su página")
        val e = JSONObject(data).getJSONObject("props").getJSONObject("pageProps").getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        val list = e.getJSONArray("trackList")
        val album = if (type == "album") e.optString("name") else null
        SourceList(
            id = "spotify:$type:$id",
            name = e.optString("name").ifEmpty { e.optString("title") },
            image = e.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url"),
            partial = list.length() >= EMBED_LIMIT,
            tracks = (0 until list.length()).map { i ->
                val t = list.getJSONObject(i)
                SourceTrack(
                    t.optString("title"),
                    t.optString("subtitle").split(',').map { it.replace(' ', ' ').trim() }.filter { it.isNotEmpty() },
                    album,
                    t.optLong("duration").takeIf { it > 0 },
                )
            }.filter { it.title.isNotBlank() },
        )
    }

    /** El cuerpo, o null si es un 404. */
    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        // Como un navegador: es la misma página que ve cualquiera que abra el reproductor incrustado.
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36")
        try {
            val code = c.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: FileNotFoundException) {
            return null
        } finally {
            c.disconnect()
        }
    }

    private const val EMBED_LIMIT = 100
    private const val IMAGES = "https://i.scdn.co/image/"
}
