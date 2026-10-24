package io.github.iaguito22.oto.playback

import io.github.iaguito22.oto.QualityMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/**
 * La calidad de una canción. Se decide una vez, al empezar a cargarla, y no cambia hasta que acaba: cada una se
 * guarda en la caché con su propia clave (`id` la alta, `id~l` la baja), así nunca se mezclan trozos de dos formatos.
 */
enum class Tier(val suffix: String) {
    High(""), Low("~l");

    fun key(id: String) = id + suffix

    companion object {
        fun idOf(key: String) = key.substringBefore('~')
        /** La que pide la clave, si la pide (una canción que se pasó a baja por no arrancar). */
        fun forcedBy(key: String) = entries.firstOrNull { it.suffix.isNotEmpty() && key.endsWith(it.suffix) }
    }
}

/** Lo que manda al elegir: los ajustes y cómo va la red justo ahora. */
data class Conditions(val mode: QualityMode, val dataSaver: Boolean, val metered: Boolean, val bandwidthBps: Long)

/**
 * Qué calidad pedir para una canción que aún no ha empezado a cargar. Si ya está entera en alta en la caché, esa:
 * no gasta datos ni puede cortarse.
 */
fun chooseTier(c: Conditions, cachedHigh: Boolean): Tier = when {
    cachedHigh -> Tier.High
    c.dataSaver && c.metered -> Tier.Low
    c.mode == QualityMode.Max -> Tier.High
    c.bandwidthBps >= AUTO_HIGH_BPS -> Tier.High
    else -> Tier.Low
}

const val AUTO_HIGH_BPS = 300_000L
const val ARTWORK_PX = 1080

private val GOOGLEUSERCONTENT_SIZE = Regex("^(https://(?:lh3|yt3)\\.googleusercontent\\.com/[^?]*?)=w(\\d+)-h(\\d+)[^?]*(\\?.*)?$")
private val GGPHT_SIZE = Regex("^(https://yt3\\.ggpht\\.com/[^?=]+)=(?:s\\d+|w\\d+-h\\d+)[^?]*(\\?.*)?$")

/** Pide la carátula al tamaño que se va a pintar (de `YouTubeUtils.resize` de Metrolist). */
fun String.resize(width: Int): String {
    GOOGLEUSERCONTENT_SIZE.matchEntire(this)?.groupValues?.let { g ->
        val height = (width.toDouble() * g[3].toInt() / g[2].toInt()).roundToInt().coerceAtLeast(1)
        return "${g[1]}=w$width-h$height-p-l90-rj${g[4]}"
    }
    GGPHT_SIZE.matchEntire(this)?.groupValues?.let { g -> return "${g[1]}=s$width${g[2]}" }
    return this
}

/** Con qué calidad llega una canción: lo que YouTube dio al pedir el stream. */
data class Quality(val codec: String, val bitrate: Int?, val sampleRate: Int?) {
    override fun toString() = listOfNotNull(
        codec.uppercase(),
        bitrate?.let { "${(it + 500) / 1000} kbps" },
        sampleRate?.let { if (it % 1000 == 0) "${it / 1000} kHz" else "%.1f kHz".format(it / 1000f) },
    ).joinToString(" · ")

    /** Para guardarla junto al audio en la caché: así se sabe también cuando no se vuelve a pedir el stream. */
    fun encode() = "$codec|${bitrate ?: ""}|${sampleRate ?: ""}"

    companion object {
        fun decode(s: String?): Quality? {
            val p = s?.split('|')?.takeIf { it.size == 3 } ?: return null
            return Quality(p[0], p[1].toIntOrNull(), p[2].toIntOrNull())
        }
    }
}

/** El servicio lo escribe al resolver cada canción y la interfaz lo lee (mismo proceso). */
object StreamInfo {
    private val _quality = MutableStateFlow<Map<String, Quality>>(emptyMap())
    val quality: StateFlow<Map<String, Quality>> = _quality.asStateFlow()

    fun put(id: String, q: Quality) = _quality.update { if (it[id] == q) it else it + (id to q) }
}

// Lo que se guarda con cada canción en la caché (y en las descargas): su calidad y el formato exacto (itag).
const val QUALITY_KEY = "oto_quality"
const val ITAG_KEY = "oto_itag"
