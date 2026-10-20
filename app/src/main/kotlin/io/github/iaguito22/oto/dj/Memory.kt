package io.github.iaguito22.oto.dj

import org.json.JSONObject
import java.io.File

/** Con qué intención pone el DJ una canción. */
enum class Kind {
    /** Tuya, de las que llevabas tiempo sin oír. */
    FAMILIAR,

    /** De un artista tuyo, pero no la tienes. */
    DEEP,

    /** De un artista nuevo al que llevan los tuyos. */
    NEW,

    /** Un artista nuevo que une lo que suena con otra parte de lo tuyo: el cambio de tanda. */
    BRIDGE,
}

/**
 * Lo que el DJ recuerda de una sesión a otra: cuánto te quedas con cada [Kind] y desde qué artistas arrancó cada
 * tanda (para no volver a ellos enseguida). Lo que puso, canción a canción, está en [Plays].
 */
class Memory(private val file: File?) {
    /** Por intención: escucha acumulada y veces, con olvido (ver [decay]). */
    val arms = Kind.entries.associateWithTo(HashMap()) { doubleArrayOf(0.0, 0.0) }

    /** Artista → cuándo fue el centro de una tanda por última vez. */
    val anchors = HashMap<String, Long>()

    /** Nombre (ver [artistKey]) → id de YouTube Music, de los que hubo que buscar. */
    val ids = HashMap<String, String>()

    init {
        runCatching {
            val o = JSONObject(file?.takeIf { it.exists() }?.readText() ?: return@runCatching)
            o.optJSONObject("arms")?.let { a ->
                for (k in Kind.entries) a.optJSONArray(k.name)?.let { arms[k] = doubleArrayOf(it.getDouble(0), it.getDouble(1)) }
            }
            o.optJSONObject("anchors")?.let { a -> a.keys().forEach { anchors[it] = a.getLong(it) } }
            o.optJSONObject("ids")?.let { a -> a.keys().forEach { ids[it] = a.getString(it) } }
        }
    }

    /** Cuánto pesa cada sesión anterior: lo de hace semanas cuenta poco y el DJ puede cambiar contigo. */
    fun decay() {
        for (v in arms.values) { v[0] *= DECAY; v[1] *= DECAY }
    }

    fun learn(kind: Kind, reward: Double) {
        arms.getValue(kind).let { it[0] += reward; it[1] += 1 }
    }

    fun save(now: Long) {
        val f = file ?: return
        // Las anclas de hace más de un mes ya no frenan nada.
        anchors.values.removeAll { now - it > 30 * DAY }
        val o = JSONObject()
            .put("arms", JSONObject().apply { arms.forEach { (k, v) -> put(k.name, org.json.JSONArray().put(v[0]).put(v[1])) } })
            .put("anchors", JSONObject(anchors as Map<*, *>))
            .put("ids", JSONObject(ids as Map<*, *>))
        runCatching { f.writeText(o.toString()) }
    }

    private companion object {
        const val DECAY = 0.9
    }
}

const val DAY = 86_400_000L
