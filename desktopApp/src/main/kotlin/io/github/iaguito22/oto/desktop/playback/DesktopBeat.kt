package io.github.iaguito22.oto.desktop.playback

import java.io.BufferedInputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** El mismo detector de graves que AudioLevels del móvil, colocado en la línea temporal del audio. */
internal object DesktopBeat {
    class Detector(private val emit: (Float) -> Unit) {
        private val alpha = (1 - exp(-2 * PI * 150 / 48000)).toFloat()
        private var lp1 = 0f
        private var lp2 = 0f
        private var sum = 0.0
        private var frames = 0
        private var average = 0f

        fun sample(value: Float) {
            lp1 += alpha * (value - lp1)
            lp2 += alpha * (lp1 - lp2)
            sum += lp2 * lp2
            if (++frames == 512) {
                val rms = sqrt(sum / 512).toFloat()
                average += (rms - average) * .006f
                val ratio = if (average > 1e-4f) rms / average else 0f
                val t = ((ratio - 1.05f) / 1.1f).coerceIn(0f, 1f)
                emit(t * t * (3 - 2 * t))
                sum = 0.0
                frames = 0
            }
        }
    }

    /** El golpe más fuerte de los [window] trozos (de 512 muestras a 48 kHz) que acaban en [positionMs]. */
    fun peak(values: FloatArray, positionMs: Long, window: Int): Float {
        val end = (positionMs.coerceAtLeast(0L) * 48000 / 512000).toInt()
        var peak = 0f
        for (i in maxOf(0, end - window + 1)..end) peak = maxOf(peak, values.getOrElse(i) { 0f })
        return peak
    }

    /**
     * Decodifica [file] con FFmpeg a mono 48 kHz s16le en una sola pasada y alimenta tanto al detector de graves
     * como a cualquier consumidor adicional ([onChunk], por ejemplo `AudioAnalyzer.Session`).
     */
    fun analyze(
        file: File,
        cancelled: () -> Boolean,
        onChunk: ((samples: FloatArray, count: Int) -> Unit)? = null,
    ): FloatArray {
        val values = ArrayList<Float>()
        val detector = Detector { values.add(it) }
        val process = ProcessBuilder(
            DesktopTools.require("ffmpeg"),
            "-v", "error",
            "-i", file.absolutePath,
            "-vn",
            "-ac", "1",
            "-ar", "48000",
            "-f", "s16le",
            "pipe:1",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val raw = ByteArray(8192)
            val floats = FloatArray(4096)
            BufferedInputStream(process.inputStream).use { input ->
                while (!cancelled()) {
                    val bytesRead = input.read(raw, 0, raw.size)
                    if (bytesRead <= 0) break
                    var usable = bytesRead - (bytesRead % 2)
                    if (bytesRead % 2 != 0) {
                        val extra = input.read()
                        if (extra >= 0) {
                            raw[bytesRead] = extra.toByte()
                            usable = bytesRead + 1
                        }
                    }
                    val samples = usable / 2
                    for (i in 0 until samples) {
                        val lo = raw[i * 2].toInt() and 255
                        val hi = raw[i * 2 + 1].toInt()
                        val s = ((hi shl 8) or lo).toShort() / 32768f
                        floats[i] = s
                        detector.sample(s)
                    }
                    if (samples > 0) onChunk?.invoke(floats, samples)
                }
            }
        } finally {
            process.destroyForcibly()
        }
        return values.toFloatArray()
    }
}
