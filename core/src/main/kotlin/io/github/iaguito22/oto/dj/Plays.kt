package io.github.iaguito22.oto.dj

import org.json.JSONObject
import java.io.File

/**
 * Una escucha: cuánto sonó una canción antes de pasar a otra ([ms] de [dur]). [dj]: con qué intención la puso el DJ,
 * o null si la pusiste tú.
 */
data class Play(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val at: Long,
    val ms: Long,
    val dur: Long,
    val dj: Kind? = null,
) {
    val key by lazy { io.github.iaguito22.oto.importer.songKey(title, artist) }
    val who by lazy { artistKey(artist) }

    val reward get() = reward(ms, dur)

    /** Sonó lo bastante para contar como oída. */
    val heard get() = ms >= SKIP_MS
}

/**
 * Lo único que se mira de una escucha: cuánto te quedaste ([ms] de [dur]). 0 = la pasaste antes de 30 s; 1 = la oíste
 * (casi) entera. Un me gusta no cuenta: casi nadie los da.
 */
fun reward(ms: Long, dur: Long): Double {
    if (ms < SKIP_MS && (dur <= 0 || ms < dur / 2)) return 0.0
    val f = if (dur > 0) ms.toDouble() / dur else 0.0
    return maxOf(f / 0.8, ms / 240_000.0).coerceIn(0.0, 1.0)
}

const val SKIP_MS = 30_000L

/**
 * Lo que vas escuchando, una línea por canción, para que el DJ aprenda de ti. Guarda las [MAX] últimas: unos meses.
 * Seguro entre hilos: el servicio escribe y el DJ lee.
 */
class Plays(private val file: File) {
    private var cache: MutableList<Play>? = null

    @Synchronized
    fun all(): List<Play> = load().toList()

    @Synchronized
    fun add(p: Play) {
        val list = load()
        list += p
        if (list.size > MAX + MAX / 4) {
            list.subList(0, list.size - MAX).clear()
            file.writeText(list.joinToString("") { encode(it) + "\n" })
        } else file.appendText(encode(p) + "\n")
    }

    private fun load(): MutableList<Play> = cache ?: run {
        val l = if (file.exists()) file.readLines().mapNotNullTo(ArrayList()) { runCatching { decode(it) }.getOrNull() } else ArrayList()
        l.also { cache = it }
    }

    private fun encode(p: Play) = JSONObject()
        .put("id", p.id).put("t", p.title).put("a", p.artist).put("ai", p.artistId)
        .put("at", p.at).put("ms", p.ms).put("d", p.dur).put("dj", p.dj?.name)
        .toString()

    private fun decode(s: String): Play {
        val o = JSONObject(s)
        return Play(
            o.getString("id"), o.optString("t"), o.optString("a"), o.optString("ai").ifEmpty { null },
            o.getLong("at"), o.getLong("ms"), o.optLong("d"),
            o.optString("dj").ifEmpty { null }?.let { n -> Kind.entries.firstOrNull { it.name == n } },
        )
    }

    private companion object {
        const val MAX = 6000
    }
}
