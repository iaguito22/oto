package io.github.iaguito22.oto.lyrics

import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * La letra ya buscada: en memoria y en el móvil, para que abrirla sea al momento. El servicio la pide al empezar cada
 * canción ([get]); la pantalla de la letra, si llega antes, se suma a esa misma petición en vez de lanzar otra.
 * «No hay letra» también se guarda, pero solo unos días: las fuentes la van teniendo con el tiempo.
 */
class LyricsStore(private val dir: File) {
    private val memory = LruCache<String, Lyrics>(64)
    private val romMemory = LruCache<String, Map<String, String>>(64)
    private val running = ConcurrentHashMap<String, CompletableDeferred<LyricsSource.Fetched>>()

    /** Lo que ya está en memoria, sin esperar a nada. */
    fun peek(id: String): Lyrics? = memory.get(id)

    fun peekRomanized(id: String): Map<String, String>? = romMemory.get(id)

    suspend fun get(q: LyricsQuery): LyricsSource.Fetched {
        memory.get(q.id)?.let { return LyricsSource.Fetched(it, true) }
        read(q.id)?.let { memory.put(q.id, it); return LyricsSource.Fetched(it, true) }
        val mine = CompletableDeferred<LyricsSource.Fetched>()
        val other = running.putIfAbsent(q.id, mine)
        if (other != null) return other.await()
        return try {
            LyricsSource.fetch(q).also { r ->
                if (r.final) r.lyrics?.let { memory.put(q.id, it); write(q.id, it) }
                mine.complete(r)
            }
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            running.remove(q.id, mine)
        }
    }

    /** Guarda una letra mejor que llegó al reintentar. */
    suspend fun put(id: String, lyrics: Lyrics) {
        memory.put(id, lyrics)
        write(id, lyrics)
    }

    /** La pronunciación de una letra (japonés, chino, coreano, cirílico), también guardada. */
    suspend fun romanized(id: String, lyrics: Lyrics.Synced): Map<String, String>? {
        romMemory.get(id)?.let { return it }
        val file = File(dir, "$id.rom.json")
        withContext(Dispatchers.IO) { runCatching { JSONObject(file.readText()) }.getOrNull() }?.let { o ->
            return o.keys().asSequence().associateWith(o::getString).also { romMemory.put(id, it) }
        }
        val r = LyricsSource.romanize(lyrics.lines, lyrics.language) ?: return null
        romMemory.put(id, r)
        withContext(Dispatchers.IO) { runCatching { dir.mkdirs(); file.writeText(JSONObject(r as Map<*, *>).toString()) } }
        return r
    }

    private suspend fun read(id: String): Lyrics? = withContext(Dispatchers.IO) {
        val file = File(dir, "$id.json")
        if (!file.exists()) return@withContext null
        runCatching {
            val o = JSONObject(file.readText())
            when (o.getString("t")) {
                "missing" -> Lyrics.Missing.takeIf { System.currentTimeMillis() - file.lastModified() < MISSING_MS }
                "plain" -> Lyrics.Plain(o.getJSONArray("l").strings(), o.getString("src"))
                else -> Lyrics.Synced(o.getJSONArray("l").objects().map(::line), o.getString("src"), o.optString("lang").ifEmpty { null })
            }
        }.onFailure { Timber.w(it, "Letra guardada ilegible: %s", id) }.getOrNull()
    }

    private suspend fun write(id: String, l: Lyrics) = withContext(Dispatchers.IO) {
        val o = when (l) {
            Lyrics.Missing -> JSONObject().put("t", "missing")
            is Lyrics.Plain -> JSONObject().put("t", "plain").put("l", JSONArray(l.lines)).put("src", l.source)
            is Lyrics.Synced -> JSONObject().put("t", "synced").put("l", JSONArray(l.lines.map(::json))).put("src", l.source).put("lang", l.language)
        }
        runCatching {
            dir.mkdirs()
            File(dir, "$id.json").writeText(o.toString())
            trim()
        }.onFailure { Timber.w(it, "No se pudo guardar la letra") }
    }

    /** Las más viejas fuera cuando pasan de [MAX_FILES]: unos pocos MB como mucho. */
    private fun trim() {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun json(l: Line) = JSONObject()
        .put("s", l.start).put("e", l.end)
        .put("w", JSONArray(l.words.map(::json)))
        .apply { if (l.bg.isNotEmpty()) put("b", JSONArray(l.bg.map(::json))) }
        .put("r", l.real).put("d", l.duet)

    private fun json(w: Word) = JSONArray().put(w.start).put(w.end).put(w.text).put(w.space)

    private fun line(o: JSONObject) = Line(
        o.getLong("s"), o.getLong("e"),
        o.getJSONArray("w").arrays().map(::word),
        o.optJSONArray("b")?.arrays()?.map(::word).orEmpty(),
        o.getBoolean("r"), o.getBoolean("d"),
    )

    private fun word(a: JSONArray) = Word(a.getLong(0), a.getLong(1), a.getString(2), a.getBoolean(3))

    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.arrays() = (0 until length()).map(::getJSONArray)

    private companion object {
        const val MISSING_MS = 3 * 24 * 3600_000L
        const val MAX_FILES = 1500
    }
}
