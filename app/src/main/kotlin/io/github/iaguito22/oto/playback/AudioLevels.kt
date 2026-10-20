package io.github.iaguito22.oto.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Cuánto pegan los graves de lo que suena ahora mismo, para que la gota lata con la música.
 *
 * Se mide en el AudioSink porque es el único sitio que sabe a la vez el tiempo de cada trozo de PCM y el
 * tiempo de lo que ya está saliendo por el altavoz: entre una cosa y otra hay medio segundo de búfer, y un
 * latido medio segundo antes del bombo se ve mal. Los dos tiempos van en la misma escala (la del renderer),
 * así que no importa que cambie la canción sin cortes.
 */
@OptIn(UnstableApi::class)
object AudioLevels {
    private const val SIZE = 1024 // potencia de 2; a 512 muestras por trozo son ~11 s a 48 kHz
    private const val CHUNK = 512

    private val times = LongArray(SIZE)
    private val pulses = FloatArray(SIZE)
    @Volatile private var head = 0
    @Volatile private var clockUs = Long.MIN_VALUE
    @Volatile private var clockNanos = 0L
    @Volatile private var running = false

    /** Golpe de graves de lo que se oye en este instante: 0 en reposo, 1 en un bombo. 0 si no suena nada. */
    fun now(): Float {
        if (!running || clockUs == Long.MIN_VALUE) return 0f
        val t = clockUs + (System.nanoTime() - clockNanos) / 1000
        val h = head
        var i = h - 1
        val stop = maxOf(0, h - SIZE)
        while (i >= stop) {
            val k = i and (SIZE - 1)
            if (times[k] <= t) return if (t - times[k] < STALE_US) pulses[k] else 0f
            i--
        }
        return 0f
    }

    class Sink(delegate: AudioSink) : ForwardingAudioSink(delegate) {
        private var encoding = C.ENCODING_INVALID
        private var channels = 0
        private var rate = 0
        private var alpha = 0f
        private var lastPts = Long.MIN_VALUE

        // Dos pasa-bajos en cascada (12 dB/oct a 150 Hz): queda el bombo y el bajo, no la voz.
        private var lp1 = 0f
        private var lp2 = 0f
        private var sum = 0.0
        private var frames = 0
        private var chunkStartUs = 0L
        private var average = 0f

        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            encoding = inputFormat.pcmEncoding
            channels = inputFormat.channelCount
            rate = inputFormat.sampleRate
            alpha = if (rate > 0) (1 - exp(-2 * PI * CUTOFF_HZ / rate)).toFloat() else 0f
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            // Si el sink no se lo traga entero, el renderer vuelve a pasar el mismo búfer: se mide una sola vez.
            if (presentationTimeUs != lastPts) {
                lastPts = presentationTimeUs
                measure(buffer, presentationTimeUs)
            }
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
            val position = super.getCurrentPositionUs(sourceEnded)
            if (position != AudioSink.CURRENT_POSITION_NOT_SET) {
                clockUs = position
                clockNanos = System.nanoTime()
            }
            return position
        }

        override fun play() {
            super.play()
            running = true
        }

        override fun pause() {
            running = false
            super.pause()
        }

        // Tras un salto el sink sigue «en play» (no vuelve a llamar a play()): solo se olvida lo medido.
        override fun flush() {
            clockUs = Long.MIN_VALUE
            head = 0
            lastPts = Long.MIN_VALUE
            lp1 = 0f; lp2 = 0f; sum = 0.0; frames = 0
            super.flush()
        }

        override fun reset() {
            flush()
            super.reset()
        }

        private fun measure(buffer: ByteBuffer, ptsUs: Long) {
            val bytes = when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_FLOAT -> 4
                else -> return
            }
            if (channels <= 0 || rate <= 0) return
            val b = buffer.duplicate().order(ByteOrder.nativeOrder())
            val frameBytes = bytes * channels
            val count = b.remaining() / frameBytes
            var pos = b.position()
            for (f in 0 until count) {
                if (frames == 0) chunkStartUs = ptsUs + f * 1_000_000L / rate
                var mono = 0f
                for (c in 0 until channels) {
                    mono += if (bytes == 2) b.getShort(pos) / 32768f else b.getFloat(pos)
                    pos += bytes
                }
                mono /= channels
                lp1 += alpha * (mono - lp1)
                lp2 += alpha * (lp1 - lp2)
                sum += lp2 * lp2
                if (++frames == CHUNK) {
                    push(sqrt(sum / CHUNK).toFloat())
                    sum = 0.0
                    frames = 0
                }
            }
        }

        /** Un golpe es un trozo bastante más fuerte que la media de los últimos ~2 s, no un trozo fuerte sin más. */
        private fun push(rms: Float) {
            average += (rms - average) * AVERAGE_RATE
            val ratio = if (average > 1e-4f) rms / average else 0f
            val t = ((ratio - 1.05f) / 1.1f).coerceIn(0f, 1f)
            val h = head
            val k = h and (SIZE - 1)
            times[k] = chunkStartUs
            pulses[k] = t * t * (3 - 2 * t)
            head = h + 1
        }
    }

    private const val CUTOFF_HZ = 150.0
    private const val AVERAGE_RATE = 0.006f // ~2 s con trozos de 10,7 ms
    private const val STALE_US = 60_000L
}
