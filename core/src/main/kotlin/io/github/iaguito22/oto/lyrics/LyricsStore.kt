package io.github.iaguito22.oto.lyrics

import io.github.iaguito22.oto.MemCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
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
    private data class Cached(val lyrics: Lyrics, val refreshSpacing: Boolean = false)
    private val memory = MemCache<String, Lyrics>(64)
    private val romMemory = MemCache<String, Map<String, String>>(64)
    private val running = ConcurrentHashMap<String, CompletableDeferred<LyricsSource.Fetched>>()

    /** Lo que ya está en memoria, sin esperar a nada. */
    fun peek(id: String): Lyrics? = memory.get(id)

    /** Como [peek], con el ajuste a mano de la canción ya aplicado: para el DJ, que mira dónde se canta de verdad. */
    fun peekAligned(id: String): Lyrics? = peek(id).let { if (it is Lyrics.Synced) it.shifted(offsetMs(id)) else it }

    fun peekRomanized(id: String): Map<String, String>? = romMemory.get(id)

    suspend fun get(q: LyricsQuery): LyricsSource.Fetched {
        memory.get(q.id)?.let { return LyricsSource.Fetched(it, true) }
        read(q.id)?.lyrics?.let { memory.put(q.id, it); return LyricsSource.Fetched(it, true) }
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

    /** Migra una letra afectada sin retrasar ni ocultar la versión guardada mientras se abre la vista. */
    suspend fun repairNetEaseSpacing(q: LyricsQuery, current: Lyrics?): Lyrics.Synced? {
        if (current !is Lyrics.Synced || !missingLatinWordSpaces(current)) return null
        val cached = read(q.id) ?: return null
        val old = cached.lyrics as? Lyrics.Synced ?: return null
        if (!cached.refreshSpacing) return old.takeUnless(::missingLatinWordSpaces)
        val fresh = LyricsSource.fetch(q).lyrics as? Lyrics.Synced
        if (fresh != null && fresh.wordByWord && !missingLatinWordSpaces(fresh)) {
            put(q.id, fresh)
            return fresh
        }
        val latest = memory.get(q.id) ?: read(q.id)?.lyrics
        if (latest == old) write(q.id, old, retrySpacingAt = System.currentTimeMillis() + SPACING_RETRY_MS)
        return null
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

    private suspend fun read(id: String): Cached? = withContext(Dispatchers.IO) {
        val file = File(dir, "$id.json")
        if (!file.exists()) return@withContext null
        runCatching {
            val o = JSONObject(file.readText())
            val version = o.optInt("matchVersion")
            if (version != 2 && version != MATCH_VERSION) return@runCatching null
            // Las sincronizadas de LRCLIB elegidas antes del consenso (19/11) pueden ir desplazadas: se vuelven a pedir.
            if (version == 2 && o.optString("t") == "synced" && o.optString("src") == "LRCLIB") return@runCatching null
            when (o.getString("t")) {
                "missing" -> Cached(Lyrics.Missing).takeIf { System.currentTimeMillis() - file.lastModified() < MISSING_MS }
                "plain" -> Cached(Lyrics.Plain(o.getJSONArray("l").strings(), o.getString("src")))
                else -> {
                    val src = o.getString("src")
                    val lines = o.getJSONArray("l").objects().map(::line).let { if (src == "NetEase") LyricsSource.fixEvenLines(it) else it }
                    val lyrics = Lyrics.Synced(lines, src, o.optString("lang").ifEmpty { null })
                    Cached(lyrics, o.optLong("retrySpacingAt") <= System.currentTimeMillis() && missingLatinWordSpaces(lyrics))
                }
            }
        }.onFailure { Timber.w(it, "Letra guardada ilegible: %s", id) }.getOrNull()
    }

    private suspend fun write(id: String, l: Lyrics, retrySpacingAt: Long? = null) = withContext(Dispatchers.IO) {
        val o = when (l) {
            Lyrics.Missing -> JSONObject().put("t", "missing")
            is Lyrics.Plain -> JSONObject().put("t", "plain").put("l", JSONArray(l.lines)).put("src", l.source)
            is Lyrics.Synced -> JSONObject().put("t", "synced").put("l", JSONArray(l.lines.map(::json))).put("src", l.source).put("lang", l.language)
        }
        runCatching {
            dir.mkdirs()
            o.put("matchVersion", MATCH_VERSION)
            val file = File(dir, "$id.json")
            if (l is Lyrics.Synced && missingLatinWordSpaces(l)) {
                val retry = retrySpacingAt ?: runCatching { JSONObject(file.readText()).optLong("retrySpacingAt") }.getOrDefault(0L)
                if (retry > 0) o.put("retrySpacingAt", retry)
            }
            file.writeText(o.toString())
            trim()
        }.onFailure { Timber.w(it, "No se pudo guardar la letra") }
    }

    private fun missingLatinWordSpaces(lyrics: Lyrics.Synced): Boolean {
        if (lyrics.source != "NetEase" || !lyrics.wordByWord) return false
        val lines = lyrics.lines.filter { line ->
            line.words.size >= 3 && line.words.all { word -> word.text.all { it in ' '..'~' || it == '’' } }
        }
        return lines.size >= 3 && lines.count { line -> line.words.dropLast(1).none { it.space } } * 3 >= lines.size * 2
    }

    /**
     * Cuánto se retrasa la letra de cada canción (negativo: se adelanta), puesto a mano. Hay letras hechas para otra
     * versión del audio (Iago, 25/11: «In the End» 1,1 s tarde, «Can You Feel My Heart» 0,9 s antes) y ninguna fuente
     * lo puede saber; medirlo contra el audio falló en 2 de 4 canciones comprobadas.
     */
    private val _offsets = MutableStateFlow(readOffsets())
    val offsets: StateFlow<Map<String, Long>> = _offsets

    fun offsetMs(id: String?): Long = id?.let { _offsets.value[it] } ?: 0L

    suspend fun setOffset(id: String, ms: Long) {
        val next = _offsets.updateAndGet { if (ms == 0L) it - id else it + (id to ms) }
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                File(dir, OFFSETS_FILE).writeText(JSONObject(next as Map<*, *>).toString())
            }.onFailure { Timber.w(it, "No se pudo guardar el desfase de la letra") }
        }
    }

    private fun readOffsets(): Map<String, Long> = runCatching {
        val o = JSONObject(File(dir, OFFSETS_FILE).readText())
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    /** Las más viejas fuera cuando pasan de [MAX_FILES]: unos pocos MB como mucho. Los desfases no se tocan. */
    private fun trim() {
        val files = dir.listFiles()?.filter { it.name != OFFSETS_FILE }?.toTypedArray() ?: return
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
        const val SPACING_RETRY_MS = 24 * 3600_000L
        const val MATCH_VERSION = 3
        const val MAX_FILES = 1500
        const val OFFSETS_FILE = "desfases.json"
    }
}
