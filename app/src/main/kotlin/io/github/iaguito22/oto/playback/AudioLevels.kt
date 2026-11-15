package io.github.iaguito22.oto.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.github.iaguito22.oto.dj.DjDsp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
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
    const val CHUNK_FRAMES = CHUNK

    private val times = LongArray(SIZE)
    private val pulses = FloatArray(SIZE)
    @Volatile private var head = 0
    @Volatile private var clockUs = Long.MIN_VALUE
    @Volatile private var clockNanos = 0L
    @Volatile private var running = false
    @Volatile var onChunk: ((sampleRate: Int, bassRms: Float, fullRms: Float) -> Unit)? = null

    private const val TAP_CAP = 48_000 // 4 s a 12 kHz para medir desfase PCM exacto sin apenas memoria.
    private val tapLock = Any()
    private val tapRing = FloatArray(TAP_CAP)
    private var tapAnchorTrackUs = Long.MIN_VALUE
    private var tapTotalSamples = 0L
    @Volatile private var tapOffsetUs = 0L
    @Volatile private var lastSubmittedPtsUs = Long.MIN_VALUE
    @Volatile private var lastRate = 0
    @Volatile private var lastChannels = 0
    @Volatile private var lastEncoding = 0

    /** Cuántos ms de PCM ya entregados al AudioTrack del reproductor siguen en vuelo esperando salir. */
    fun pendingMs(): Long? {
        val pts = lastSubmittedPtsUs
        val clk = clockUs
        if (pts == Long.MIN_VALUE || clk == Long.MIN_VALUE) return null
        return (pts - clk) / 1000L
    }

    fun formatSummary(): String = "${lastRate}Hz/${lastChannels}ch/enc=$lastEncoding"

    /** Instantánea del tap PCM a 12 kHz de lo reproducido por `player`, devolviendo `(pcm12k, originTrackMs)`. */
    fun snapshotTap12k(): Pair<FloatArray, Long>? = synchronized(tapLock) {
        if (tapAnchorTrackUs == Long.MIN_VALUE || tapTotalSamples <= 0L) return null
        val n = minOf(tapTotalSamples, TAP_CAP.toLong()).toInt()
        val dropped = tapTotalSamples - n
        val originMs = (tapAnchorTrackUs + dropped * 1_000_000L / DjDsp.SYNC_TAP_SAMPLE_RATE) / 1000L
        val out = FloatArray(n)
        val startIdx = ((tapTotalSamples - n) % TAP_CAP).toInt()
        val firstPart = minOf(n, TAP_CAP - startIdx)
        System.arraycopy(tapRing, startIdx, out, 0, firstPart)
        if (firstPart < n) {
            System.arraycopy(tapRing, 0, out, firstPart, n - firstPart)
        }
        out to originMs
    }

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
        private var fullSum = 0.0
        private var frames = 0
        private var chunkStartUs = 0L
        private var average = 0f
        private var tapInFrames = 0L
        private var tapLastBucket = 0L
        private var tapBucketSum = 0f
        private var tapBucketCount = 0
        private var expectedNextTrackUs = Long.MIN_VALUE

        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            encoding = inputFormat.pcmEncoding
            channels = inputFormat.channelCount
            rate = inputFormat.sampleRate
            lastEncoding = encoding
            lastChannels = channels
            lastRate = rate
            alpha = if (rate > 0) (1 - exp(-2 * PI * CUTOFF_HZ / rate)).toFloat() else 0f
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
        }

        override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
            tapOffsetUs = outputStreamOffsetUs
            super.setOutputStreamOffsetUs(outputStreamOffsetUs)
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            // Si el sink no se lo traga entero, el renderer vuelve a pasar el mismo búfer: se mide una sola vez.
            if (presentationTimeUs != lastPts) {
                lastPts = presentationTimeUs
                lastSubmittedPtsUs = presentationTimeUs
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
            lastSubmittedPtsUs = Long.MIN_VALUE
            lp1 = 0f; lp2 = 0f; sum = 0.0; fullSum = 0.0; frames = 0
            synchronized(tapLock) {
                expectedNextTrackUs = Long.MIN_VALUE
                tapAnchorTrackUs = Long.MIN_VALUE
                tapTotalSamples = 0L
                tapInFrames = 0L
                tapLastBucket = 0L
                tapBucketSum = 0f
                tapBucketCount = 0
            }
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
            val trackPtsUs = (ptsUs - tapOffsetUs).coerceAtLeast(0L)
            var pos = b.position()
            synchronized(tapLock) {
                if (expectedNextTrackUs == Long.MIN_VALUE || abs(trackPtsUs - expectedNextTrackUs) > 8_000L) {
                    tapAnchorTrackUs = trackPtsUs
                    tapTotalSamples = 0L
                    tapInFrames = 0L
                    tapLastBucket = 0L
                    tapBucketSum = 0f
                    tapBucketCount = 0
                }
                for (f in 0 until count) {
                    if (frames == 0) chunkStartUs = ptsUs + f * 1_000_000L / rate
                    var mono = 0f
                    for (c in 0 until channels) {
                        mono += if (bytes == 2) b.getShort(pos) / 32768f else b.getFloat(pos)
                        pos += bytes
                    }
                    mono /= channels
                    if (!mono.isFinite()) mono = 0f
                    tapBucketSum += mono
                    tapBucketCount++
                    tapInFrames++
                    val bucket = (tapInFrames * DjDsp.SYNC_TAP_SAMPLE_RATE) / rate
                    if (bucket > tapLastBucket) {
                        tapRing[(tapTotalSamples % TAP_CAP).toInt()] = tapBucketSum / tapBucketCount
                        tapTotalSamples++
                        tapBucketSum = 0f
                        tapBucketCount = 0
                        tapLastBucket = bucket
                    }
                    lp1 += alpha * (mono - lp1)
                    lp2 += alpha * (lp1 - lp2)
                    sum += lp2 * lp2
                    fullSum += mono * mono
                    if (++frames == CHUNK) {
                        val bassRms = sqrt(sum / CHUNK).toFloat()
                        val fullRms = sqrt(fullSum / CHUNK).toFloat()
                        push(bassRms)
                        onChunk?.invoke(rate, bassRms, fullRms)
                        sum = 0.0
                        fullSum = 0.0
                        frames = 0
                    }
                }
                expectedNextTrackUs = trackPtsUs + (count * 1_000_000L / rate)
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
