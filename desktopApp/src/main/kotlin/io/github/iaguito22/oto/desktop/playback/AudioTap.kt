package io.github.iaguito22.oto.desktop.playback

/**
 * Copia de lo que Oto manda a la tarjeta de sonido, para el fondo de escritorio vivo. Solo trabaja si [active]
 * (el fondo está encendido): entonces cada trozo PCM estéreo s16le que se escribe en la línea se guarda en mono.
 *
 * [read] devuelve las muestras que están sonando AHORA: las que la línea ya ha reproducido según su posición, no las
 * que se acaban de escribir (que sonarán dentro de lo que tarde el búfer de la línea).
 */
internal object AudioTap {
    @Volatile var active = false
    private const val SIZE = 1 shl 16                 // 1,4 s a 48 kHz
    private val ring = FloatArray(SIZE)
    private var written = 0L                          // fotogramas escritos desde que se abrió la línea actual
    @Volatile private var line: Any? = null
    @Volatile private var played: (() -> Long)? = null

    /** El motor abre una línea nueva (canción, salto o cambio de dispositivo): se empieza a contar de cero. */
    @Synchronized fun attach(owner: Any, playedFrames: () -> Long) {
        line = owner; played = playedFrames; written = 0L
        ring.fill(0f)
    }

    @Synchronized fun detach(owner: Any) {
        if (line === owner) { line = null; played = null }
    }

    /** Un trozo PCM estéreo s16le (4 bytes por fotograma) tal y como va a la línea. */
    fun write(owner: Any, pcm: ByteArray, len: Int) {
        if (!active || line !== owner) return
        synchronized(this) {
            var i = 0
            var w = written
            while (i + 3 < len) {
                val l = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 255)).toShort()
                val r = ((pcm[i + 3].toInt() shl 8) or (pcm[i + 2].toInt() and 255)).toShort()
                ring[(w and (SIZE - 1).toLong()).toInt()] = (l + r) / 65536f
                w++; i += 4
            }
            written = w
        }
    }

    /**
     * Copia en [out] las últimas `out.size` muestras que ya han sonado (desplazadas [shiftFrames], positivo = más
     * adelante). Devuelve false si no hay nada sonando.
     */
    fun read(out: FloatArray, shiftFrames: Long = 0L): Boolean {
        val p = played ?: return false.also { out.fill(0f) }
        synchronized(this) {
            if (line == null) { out.fill(0f); return false }
            val end = (runCatching { p() }.getOrDefault(written) + shiftFrames).coerceIn(0L, written)
            val start = end - out.size
            for (k in out.indices) {
                val f = start + k
                out[k] = if (f < 0 || f < written - SIZE) 0f else ring[(f and (SIZE - 1).toLong()).toInt()]
            }
            return end > 0
        }
    }
}
