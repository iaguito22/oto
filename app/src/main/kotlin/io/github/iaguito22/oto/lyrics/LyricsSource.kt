package io.github.iaguito22.oto.lyrics

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * De dónde sale la letra, en orden (lo mismo que el tema de Spotify, sin la base de Spotify):
 * 1. lyricsplus: la letra de Apple Music con tiempos por sílaba y los coros aparte.
 * 2. Netease (yrc): tiempos por palabra; aquí no pide captcha porque no sale de Spotify.
 * 3. LRCLIB: tiempos por línea; las palabras se reparten a ritmo de canto ([estimate]).
 * 4. YouTube Music, sin sincronizar.
 */
object LyricsSource {
    private val LP_HOSTS = listOf("lyricsplus.binimum.org", "lyricsplus.prjktla.workers.dev")
    // El que falla (cupo diario agotado, caído) se salta el resto de la sesión.
    private val lpDown = mutableSetOf<String>()

    /** null si no se pudo preguntar (sin red): no se guarda y se vuelve a intentar. */
    suspend fun fetch(q: LyricsQuery): Lyrics? = withContext(Dispatchers.IO) {
        var offline = false
        suspend fun <T> attempt(name: String, block: suspend () -> T?): T? = try {
            block()
        } catch (e: IOException) {
            Timber.w(e, "Letra: $name sin red")
            offline = true
            null
        } catch (e: Exception) {
            Timber.w(e, "Letra: $name falló")
            null
        }
        val title = cleanTitle(q.title)
        attempt("lyricsplus") { lyricsPlus(title, q) }?.let { return@withContext it }
        attempt("netease") { netease(title, q) }?.let { return@withContext it }
        val lrc = attempt("lrclib") { lrclib(title, q) }
        if (lrc is Lyrics.Synced) return@withContext lrc
        attempt("youtube") { youtube(q.id) }?.let { return@withContext it }
        lrc ?: if (offline) null else Lyrics.Missing
    }

    // ---- lyricsplus -------------------------------------------------------------------------------

    private fun lyricsPlus(title: String, q: LyricsQuery): Lyrics? {
        val params = buildString {
            append("title=").append(enc(title)).append("&artist=").append(enc(q.artist))
            append("&album=").append(enc(q.album.orEmpty()))
            if (q.durationMs > 0) append("&duration=").append((q.durationMs / 1000.0).roundToInt())
        }
        var json: JSONObject? = null
        for (host in LP_HOSTS) {
            if (host in lpDown) continue
            try {
                json = JSONObject(get("https://$host/v2/lyrics/get?$params", timeout = 9000) ?: return null) // 404: el espejo tampoco la tiene
                break
            } catch (e: Exception) {
                lpDown += host
            }
        }
        val d = json ?: return null
        if (!Regex("word|syllable", RegexOption.IGNORE_CASE).containsMatchIn(d.optString("type"))) return null
        val arr = d.optJSONArray("lyrics") ?: return null
        val lines = (0 until arr.length()).mapNotNull { i ->
            val l = arr.getJSONObject(i)
            val syl = l.optJSONArray("syllabus") ?: return@mapNotNull null
            val main = ArrayList<Word>()
            val bg = ArrayList<Word>()
            for (k in 0 until syl.length()) {
                val s = syl.getJSONObject(k)
                val raw = s.optString("text")
                if (raw.isBlank()) continue
                val t = s.optLong("time")
                val w = Word(t, t + s.optLong("duration"), raw.trim(), raw.endsWith(" "))
                if (s.optBoolean("isBackground")) bg += w.copy(text = w.text.trim('(', ')', '（', '）')) else main += w
            }
            if (main.isEmpty() && bg.isEmpty()) return@mapNotNull null
            val singer = l.optJSONObject("element")?.optString("singer")
            Line(l.optLong("time"), l.optLong("time") + l.optLong("duration"), main, bg, real = true, duet = singer == "v2")
        }
        if (lines.isEmpty()) return null
        val meta = d.optJSONObject("metadata")
        val source = when (meta?.optString("source")) {
            "qApple", "Apple" -> "Apple Music"
            "Spotify" -> "Spotify"
            "Musixmatch" -> "Musixmatch"
            else -> "lyricsplus"
        }
        return Lyrics.Synced(lines, source, meta?.optString("language")?.ifBlank { null })
    }

    // ---- Netease ----------------------------------------------------------------------------------

    private fun netease(title: String, q: LyricsQuery): Lyrics? {
        val firstArtist = q.artist.split(", ", " & ", " x ").first()
        val search = JSONObject(get("https://music.163.com/api/search/get?type=1&limit=8&s=${enc("$title $firstArtist")}") ?: return null)
        val songs = search.optJSONObject("result")?.optJSONArray("songs") ?: return null
        val nn = norm(title)
        val na = norm(firstArtist)
        var best: JSONObject? = null
        var bestDiff = Long.MAX_VALUE
        for (i in 0 until songs.length()) {
            val s = songs.getJSONObject(i)
            val sn = norm(s.optString("name"))
            val sameName = sn.isNotEmpty() && (sn == nn || sn.startsWith(nn) || nn.startsWith(sn))
            val artists = s.optJSONArray("artists") ?: JSONArray()
            val sameArtist = (0 until artists.length()).any { k ->
                val x = norm(artists.getJSONObject(k).optString("name"))
                x.isNotEmpty() && (na.contains(x) || x.contains(na))
            }
            val diff = if (q.durationMs > 0) abs(s.optLong("duration") - q.durationMs) else 0
            if (sameName && sameArtist && diff < bestDiff) { best = s; bestDiff = diff }
        }
        if (best == null || bestDiff > 2500) return null
        val r = JSONObject(get("https://music.163.com/api/song/lyric?id=${best.optLong("id")}&lv=1&kv=1&tv=-1&yv=1") ?: return null)
        val yrc = r.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val lines = parseYrc(yrc)
        return if (lines.isEmpty()) null else Lyrics.Synced(lines, "NetEase")
    }

    private val YRC_LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val YRC_WORD = Regex("""\((\d+),(\d+),-?\d+\)""")
    // Las primeras líneas de Netease son créditos («作词 : …»): no se cantan.
    private val CREDIT = Regex("""^[^:：]{1,24}\s*[:：]""")

    /** `[ini,dur](ini,dur,0)palabra (ini,dur,0)otra …` → líneas, con lo que va entre paréntesis como coro. */
    internal fun parseYrc(src: String): List<Line> = src.lines().mapIndexedNotNull { idx, raw ->
        val m = YRC_LINE.find(raw.trim()) ?: return@mapIndexedNotNull null
        val body = m.groupValues[3]
        val starts = YRC_WORD.findAll(body).toList()
        if (starts.isEmpty()) return@mapIndexedNotNull null
        val pieces = starts.mapIndexed { k, w ->
            val text = body.substring(w.range.last + 1, if (k + 1 < starts.size) starts[k + 1].range.first else body.length)
            Triple(w.groupValues[1].toLong(), w.groupValues[2].toLong(), text)
        }
        val text = pieces.joinToString("") { it.third }.trim()
        if (text.isEmpty() || (idx < 12 && CREDIT.containsMatchIn(text))) return@mapIndexedNotNull null
        val (main, bg) = splitParens(pieces.map { (t, d, tx) -> Word(t, t + d, tx.trim(), tx.endsWith(" ")) }.filter { it.text.isNotEmpty() })
        Line(m.groupValues[1].toLong(), m.groupValues[1].toLong() + m.groupValues[2].toLong(), main, bg, real = true)
    }

    /** Lo que va entre paréntesis es coro, salvo que la línea entera lo sea: entonces es la voz. */
    private fun splitParens(words: List<Word>): Pair<List<Word>, List<Word>> {
        val main = ArrayList<Word>()
        val bg = ArrayList<Word>()
        var depth = 0
        for (w in words) {
            val opens = w.text.count { it == '(' || it == '（' }
            val closes = w.text.count { it == ')' || it == '）' }
            val inside = depth > 0 || opens > 0
            depth = max(0, depth + opens - closes)
            val clean = w.copy(text = w.text.filterNot { it in "()（）" })
            if (clean.text.isEmpty()) continue
            if (inside) bg += clean else main += clean
        }
        return if (main.isEmpty()) bg to emptyList() else main to bg
    }

    // ---- LRCLIB -----------------------------------------------------------------------------------

    private fun lrclib(title: String, q: LyricsQuery): Lyrics? {
        val secs = (q.durationMs / 1000.0).roundToInt()
        val direct = get(
            "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(q.artist)}" +
                (q.album?.let { "&album_name=${enc(it)}" } ?: "") + (if (secs > 0) "&duration=$secs" else ""),
        )?.let(::JSONObject)
        val hit = direct ?: run {
            val arr = JSONArray(get("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(q.artist)}") ?: return null)
            (0 until arr.length()).map(arr::getJSONObject)
                .filter { secs <= 0 || abs(it.optDouble("duration") - secs) <= 3 }
                .maxByOrNull { if (it.optString("syncedLyrics").isNotBlank()) 1 else 0 }
        } ?: return null
        val synced = hit.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics.Synced(lines, "LRCLIB")
        }
        val plain = hit.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return Lyrics.Plain(plain.trim().lines(), "LRCLIB")
    }

    private val LRC_TIME = Regex("""\[(\d+):(\d+(?:[.:]\d+)?)]""")

    internal fun parseLrc(src: String): List<Line> {
        val stamps = src.lines().flatMap { raw ->
            val times = LRC_TIME.findAll(raw).toList()
            if (times.isEmpty()) return@flatMap emptyList()
            val text = raw.substring(times.last().range.last + 1).trim()
            times.map { (it.groupValues[1].toLong() * 60_000 + (it.groupValues[2].replace(':', '.').toDouble() * 1000).toLong()) to text }
        }.sortedBy { it.first }
        // Las líneas vacías no se cantan, pero marcan dónde acaba la anterior.
        return stamps.mapIndexedNotNull { i, (t, text) ->
            if (text.isEmpty()) return@mapIndexedNotNull null
            val next = stamps.getOrNull(i + 1)?.first ?: (t + 5000)
            estimate(text, t, next)
        }
    }

    private val CJK = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff\\uf900-\\ufaff\\uac00-\\ud7af]")
    private val KANA_HAN = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff]")

    /**
     * Sin tiempos por palabra (del tema de Spotify): las palabras van a ritmo de canto (~60 ms por letra) y la
     * última se alarga hasta la respiración antes de la frase siguiente. Los coros entre paréntesis no le quitan
     * tiempo a la voz: arrancan al acabar la palabra que tienen delante.
     */
    internal fun estimate(text: String, start: Long, next: Long): Line {
        val cjk = !text.contains(' ') && CJK.containsMatchIn(text)
        val toks: List<String> = if (cjk) text.map { it.toString() } else Regex("""(\s+)|([^\s]+)""").findAll(text).flatMap { m ->
            val w = m.value
            if (w.isNotBlank() && KANA_HAN.containsMatchIn(w)) Regex("[\\u3040-\\u30ff\\u3400-\\u9fff]|[^\\u3040-\\u30ff\\u3400-\\u9fff]+").findAll(w).map { it.value }
            else sequenceOf(w)
        }.toList()
        val inBv = BooleanArray(toks.size)
        if (text.contains('(') || text.contains('（')) {
            var depth = 0
            toks.forEachIndexed { i, w ->
                val o = w.count { it == '(' || it == '（' }
                val c = w.count { it == ')' || it == '）' }
                inBv[i] = w.isNotBlank() && (depth > 0 || o > 0)
                depth = max(0, depth + o - c)
            }
            if (toks.indices.none { toks[it].isNotBlank() && !inBv[it] }) inBv.fill(false) // línea entera de coro: es la voz
        }
        val u = 60.0
        val sung = toks.indices.filter { toks[it].isNotBlank() && !inBv[it] }
        val units = sung.map { toks[it].length + 2.0 }.toMutableList()
        val lastU = units.removeLastOrNull() ?: 0.0
        val bodyU = units.sum()
        val span = (next - start).toDouble()
        val room = max(300.0, span - min(450.0, span * 0.12))
        var k = u
        var hold = lastU * u
        if ((bodyU + lastU) * u >= room) {
            k = room / (bodyU + lastU); hold = lastU * k
        } else {
            val free = room - (bodyU + lastU) * u
            val slow = min(free * 0.3, bodyU * u * 0.5)
            k = u + if (bodyU > 0) slow / bodyU else 0.0
            hold = min(lastU * u + free - slow, lastU * u + 2600)
        }
        var acc = start.toDouble()
        var wi = 0
        var bvAt = start.toDouble()
        val main = ArrayList<Word>()
        val bg = ArrayList<Word>()
        toks.forEachIndexed { i, w ->
            if (w.isBlank()) return@forEachIndexed
            val space = !cjk && toks.getOrNull(i + 1)?.isBlank() == true
            if (inBv[i]) {
                val s = bvAt
                bvAt += (w.length + 2) * u
                val clean = w.filterNot { it in "()（）" }
                if (clean.isNotEmpty()) bg += Word(s.toLong(), bvAt.toLong(), clean, space)
                return@forEachIndexed
            }
            val s = acc
            val len = if (wi < units.size) units[wi] * k else hold
            val sungEnd = s + min(len, (w.length + 2) * u)
            acc += len
            wi++
            bvAt = sungEnd
            main += Word(s.toLong(), acc.toLong(), w, space)
        }
        return Line(start, max(acc.toLong(), next), main, bg, real = false)
    }

    // ---- YouTube Music ------------------------------------------------------------------------------

    private suspend fun youtube(id: String): Lyrics? {
        val endpoint = YouTube.next(WatchEndpoint(videoId = id)).getOrThrow().lyricsEndpoint ?: return null
        val text = YouTube.lyrics(endpoint).getOrThrow()
        return if (text.isNullOrBlank()) null else Lyrics.Plain(text.trim().lines(), "YouTube Music")
    }

    // ---- Romanización ---------------------------------------------------------------------------------

    private val CYR = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'ґ' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'є' to "ye", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'і' to "i", 'ї' to "yi", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh",
        'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )
    private val CYRILLIC = Regex("[\\u0400-\\u04ff]")

    fun needsRomanization(lines: List<Line>) = lines.any { CJK.containsMatchIn(it.text) || CYRILLIC.containsMatchIn(it.text) }

    /**
     * La pronunciación debajo de cada línea en japonés, chino y coreano (Google Translate, una petición por cada 25
     * líneas) y en cirílico (tabla local). Clave: el texto de la voz. null si falló: se reintenta (3, 10 y 30 s).
     */
    suspend fun romanize(lines: List<Line>, lang: String?): Map<String, String>? = withContext(Dispatchers.IO) {
        val texts = lines.map { it.text }.filter { it.isNotBlank() }.distinct()
        val out = HashMap<String, String>()
        texts.filter { !CJK.containsMatchIn(it) && CYRILLIC.containsMatchIn(it) }.forEach { out[it] = cyrLatin(it) }
        val cjk = texts.filter { CJK.containsMatchIn(it) }
        val sl = lang?.takeIf { Regex("^(ja|zh|ko)").containsMatchIn(it) } ?: "auto"
        for (tries in 0..3) {
            try {
                cjk.chunked(25).forEach { askRomanization(it, sl, out) }
                return@withContext out
            } catch (e: Exception) {
                Timber.w(e, "Romanización")
                if (tries == 3) return@withContext null
                delay(longArrayOf(3000, 10_000, 30_000)[tries])
            }
        }
        null
    }

    // Líneas separadas por « | », que sobrevive a la romanización; si un bloque no casa línea a línea, a mitades.
    private fun askRomanization(part: List<String>, sl: String, out: MutableMap<String, String>) {
        val q = part.joinToString(" | ") { it.replace('|', ' ') }
        val body = get("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=en&dt=rm&q=${enc(q)}") ?: throw IOException("404")
        val first = JSONArray(body).optJSONArray(0) ?: JSONArray()
        val joined = (0 until first.length()).joinToString("") { i -> first.optJSONArray(i)?.optString(3)?.takeIf { it != "null" }.orEmpty() }
        val res = joined.split(Regex("""\s*\|\s*"""))
        if (res.size == part.size) {
            // Las comillas japonesas 「」 vuelven como ` y ': se dejan como comillas de verdad.
            part.forEachIndexed { i, t ->
                res[i].trim().takeIf { it.isNotEmpty() }?.let { out[t] = it.replace('`', '‘').replaceFirstChar(Char::lowercaseChar) }
            }
        } else if (part.size > 1) {
            askRomanization(part.subList(0, (part.size + 1) / 2), sl, out)
            askRomanization(part.subList((part.size + 1) / 2, part.size), sl, out)
        }
    }

    private fun cyrLatin(t: String) = buildString {
        for (c in t) {
            val l = c.lowercaseChar()
            val r = CYR[l]
            when {
                r == null -> append(c)
                c != l && r.isNotEmpty() -> append(r.replaceFirstChar(Char::uppercaseChar))
                else -> append(r)
            }
        }
    }

    // ---- Red ------------------------------------------------------------------------------------

    /** El cuerpo, o null si es un 404. Otros errores lanzan IOException. */
    private fun get(url: String, timeout: Int = 7000): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "Oto (https://github.com/iaguito22/oto)")
        try {
            val code = c.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("HTTP $code")
            // lyricsplus manda gzip sin Content-Encoding: se mira la firma.
            val bytes = c.inputStream.use { it.readBytes() }
            val raw = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(bytes.inputStream()).use { it.readBytes() } else bytes
            return String(raw, Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            return null
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun norm(t: String) = t.lowercase().filter { it.isLetterOrDigit() }

    // «(Official Video)», «[Lyrics]»…: ninguna web de letras los tiene en el título.
    private val NOISE = Regex(
        """\s*[(\[][^)\]]*\b(official|video|audio|lyrics?|letra|visuali[sz]er|m/?v|hd|4k|remaster(ed)?|explicit)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    private fun cleanTitle(t: String) = t.replace(NOISE, "").trim().ifEmpty { t }
}
