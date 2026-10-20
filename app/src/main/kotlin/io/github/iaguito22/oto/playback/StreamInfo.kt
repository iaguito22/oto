package io.github.iaguito22.oto.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
