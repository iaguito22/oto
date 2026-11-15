package io.github.iaguito22.oto.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Bucle de beats de la que se va, como el botón «loop» de una mesa: desde un tiempo fuerte se graba un compás y, al
 * acabarlo, se repite en vez de seguir con la canción (que sigue avanzando por debajo, sin oírse). Hacia el final se
 * puede partir en trozos más cortos ([division] 2, 4, 8…: medio compás, un beat, medio beat), lo que se llama «roll».
 *
 * Trabaja en el tiempo de la canción (antes del cambio de velocidad), así que el bucle dura justo N beats de ella.
 * Para saber en qué punto de la canción va cada muestra, [LoopSink] le dice dónde empieza el audio tras cada búsqueda.
 *
 * El control llega del hilo principal ([arm], [division], [disarm]); el audio, del hilo de reproducción.
 */
@OptIn(UnstableApi::class)
class BeatLoopAudioProcessor : BaseAudioProcessor() {
    // ---- Control (hilo principal) ----
    @Volatile private var armStartUs = -1L
    @Volatile private var armLenUs = 0L
    @Volatile private var armBeatUs = 0L
    /** En cuántos trozos se parte el bucle (1 = entero). Solo divisores exactos de su número de beats. */
    @Volatile var division: Int = 1
    /** Dónde se pidió empezar la última búsqueda del reproductor (para elegir bien el origen del audio). */
    @Volatile var startHintUs: Long = 0L

    // ---- Lo que ve el control: dónde empezó de verdad el bucle (µs de la canción) y cuánto dura ----
    @Volatile var loopStartedUs: Long = -1L
        private set
    @Volatile var loopLenUs: Long = 0L
        private set

    // ---- Audio (hilo de reproducción) ----
    @Volatile private var pendingStart = true
    @Volatile private var pendingCandidates: LongArray? = null
    private var streamStartUs = 0L
    private var framesIn = 0L
    private var rate = 0
    private var ch = 0
    private var loopStartFrame = -1L
    private var lenFrames = 0
    private var xfFrames = 0
    private var rec = FloatArray(0)
    private var recFrames = 0
    private var lastSrc = -1
    private var fadeFrom = -1
    private var fadeLeft = 0

    /**
     * Prepara un bucle de [lenMs] que empieza en [startMs] de la canción, en un tiempo fuerte de pulso [beatMs]. Si ese
     * punto ya pasó cuando llega el audio, empieza en el siguiente beat (así sigue cuadrado).
     */
    fun arm(startMs: Double, lenMs: Double, beatMs: Double) {
        disarm()
        armBeatUs = (beatMs * 1000.0).roundToLong()
        armLenUs = (lenMs * 1000.0).roundToLong()
        armStartUs = (startMs * 1000.0).roundToLong()
    }

    val isArmed: Boolean get() = armStartUs >= 0L

    fun disarm() {
        armStartUs = -1L
        division = 1
        loopStartedUs = -1L
        loopLenUs = 0L
        resetLoopRequested = true
    }

    @Volatile private var resetLoopRequested = false

    /**
     * Lo que se oye en [posMs] de la canción con el bucle puesto: el punto equivalente dentro del bucle. Sirve para
     * medir la fase de sus beats aunque la canción siga avanzando por debajo.
     */
    fun audiblePositionMs(posMs: Long): Double {
        val start = loopStartedUs
        val len = loopLenUs
        if (start < 0L || len <= 0L) return posMs.toDouble()
        val posUs = posMs * 1000L
        if (posUs < start + len) return posMs.toDouble()
        val cur = (len / division.coerceAtLeast(1)).coerceAtLeast(1L)
        return (start + (posUs - start) % cur) / 1000.0
    }

    /** Desde [LoopSink]: el reproductor buscó (o empezó); el próximo audio que llegue es el origen. */
    internal fun onSinkFlush() {
        pendingStart = true
    }

    /** Desde [LoopSink]: el primer trozo tras buscar trae su instante; se dan las dos lecturas posibles. */
    internal fun onFirstBuffer(candidatesUs: LongArray) {
        if (pendingStart) {
            pendingCandidates = candidatesUs
            pendingStart = false
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val ok = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!ok || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount !in 1..8) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        rate = inputAudioFormat.sampleRate
        ch = inputAudioFormat.channelCount
        xfFrames = rate * XFADE_MS / 1000
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        pendingCandidates?.let { c ->
            pendingCandidates = null
            // La lectura más cercana a donde se pidió buscar (según el renderizador, el instante lleva o no un desfase).
            val hint = startHintUs
            streamStartUs = c.minByOrNull { abs(it - hint) } ?: c[0]
            framesIn = 0L
            clearLoopAudio()
        }
        if (resetLoopRequested) {
            resetLoopRequested = false
            clearLoopAudio()
        }
        val bytesPerSample = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frames = remaining / (bytesPerSample * ch)
        val out = replaceOutputBuffer(remaining)
        val order = ByteOrder.nativeOrder()
        val src = inputBuffer.duplicate().order(order)
        out.order(order)

        val armed = armStartUs
        if (armed < 0L && loopStartFrame < 0L) {
            out.put(inputBuffer)
            out.flip()
            framesIn += frames
            return
        }
        if (loopStartFrame < 0L && armed >= 0L && rate > 0) {
            val lenF = (armLenUs * rate / 1_000_000L).toInt().coerceIn((rate / 50).coerceAtLeast(xfFrames + 1), rate * MAX_LOOP_S)
            val beatF = (armBeatUs * rate / 1_000_000.0).coerceAtLeast(1.0)
            var startF = ((armed - streamStartUs) * rate / 1_000_000.0)
            // Si el inicio pedido ya pasó, al siguiente beat (sigue cuadrado con la otra).
            if (startF < framesIn) startF += ceil((framesIn - startF) / beatF) * beatF
            loopStartFrame = startF.roundToLong()
            lenFrames = lenF
            rec = FloatArray((lenF + xfFrames) * ch)
            recFrames = 0
            lastSrc = -1
            fadeLeft = 0
            loopStartedUs = streamStartUs + loopStartFrame * 1_000_000L / rate
            loopLenUs = lenF * 1_000_000L / rate
        }

        val isFloat = bytesPerSample == 4
        val div = division.coerceAtLeast(1)
        val curLen = (lenFrames / div).coerceAtLeast(1)
        for (f in 0 until frames) {
            val pos = framesIn + f - loopStartFrame
            val recording = pos >= 0 && recFrames < lenFrames + xfFrames && pos == recFrames.toLong()
            val looping = pos >= lenFrames
            val srcIdx = if (looping) (pos % curLen).toInt() else -1
            val jump = looping && srcIdx != lastSrc + 1
            if (jump) {
                // Salto (vuelta al principio o cambio de trozo): un fundido corto desde lo que seguiría.
                fadeFrom = lastSrc + 1
                fadeLeft = xfFrames
            }
            for (c in 0 until ch) {
                val raw = if (isFloat) 0 else src.getShort().toInt()
                val x = if (isFloat) src.getFloat() else raw / 32768f
                if (recording) rec[recFrames * ch + c] = x
                val y = if (looping) {
                    val now = rec[srcIdx * ch + c]
                    if (fadeLeft > 0 && fadeFrom >= 0) {
                        val fromIdx = (fadeFrom + (xfFrames - fadeLeft)).coerceAtMost(lenFrames + xfFrames - 1)
                        val g = 1f - fadeLeft.toFloat() / xfFrames
                        now * g + rec[fromIdx * ch + c] * (1f - g)
                    } else now
                } else x
                when {
                    isFloat -> out.putFloat(y)
                    !looping -> out.putShort(raw.toShort())
                    else -> out.putShort((y * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
            if (recording) recFrames++
            if (looping) {
                lastSrc = srcIdx
                if (fadeLeft > 0) fadeLeft--
            }
        }
        inputBuffer.position(inputBuffer.position() + frames * bytesPerSample * ch)
        framesIn += frames
        out.flip()
    }

    private fun clearLoopAudio() {
        loopStartFrame = -1L
        recFrames = 0
        lastSrc = -1
        fadeLeft = 0
        if (armStartUs < 0L) {
            rec = FloatArray(0)
            lenFrames = 0
        }
    }

    // Los cambios de velocidad vacían la cadena sin buscar: el audio sigue donde iba y el bucle no se toca.
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) = Unit

    override fun onReset() {
        armStartUs = -1L
        pendingStart = true
        pendingCandidates = null
        framesIn = 0L
        clearLoopAudio()
        rec = FloatArray(0)
        loopStartedUs = -1L
        loopLenUs = 0L
    }

    private companion object {
        const val XFADE_MS = 6
        const val MAX_LOOP_S = 8
    }
}

/**
 * El AudioSink de siempre, que además avisa a [loop] de dónde empieza el audio tras cada búsqueda (el primer trozo
 * trae su instante). Los procesadores solos no lo saben.
 */
@OptIn(UnstableApi::class)
class LoopSink(sink: AudioSink, private val loop: BeatLoopAudioProcessor) : ForwardingAudioSink(sink) {
    @Volatile private var offsetUs = 0L
    @Volatile private var lastSubmittedPtsUs = Long.MIN_VALUE
    @Volatile private var clockUs = Long.MIN_VALUE
    @Volatile private var rate = 0
    @Volatile private var channels = 0
    @Volatile private var encoding = 0
    private var needStart = true

    fun pendingMs(): Long? {
        val p = lastSubmittedPtsUs
        val c = clockUs
        if (p == Long.MIN_VALUE || c == Long.MIN_VALUE) return null
        return (p - c) / 1000L
    }

    fun formatSummary(): String = "${rate}Hz/${channels}ch/enc=$encoding"

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        rate = inputFormat.sampleRate
        channels = inputFormat.channelCount
        encoding = inputFormat.pcmEncoding
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val pos = super.getCurrentPositionUs(sourceEnded)
        if (pos != AudioSink.CURRENT_POSITION_NOT_SET) clockUs = pos
        return pos
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        offsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun flush() {
        needStart = true
        lastSubmittedPtsUs = Long.MIN_VALUE
        clockUs = Long.MIN_VALUE
        loop.onSinkFlush()
        super.flush()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        lastSubmittedPtsUs = presentationTimeUs
        if (needStart) {
            needStart = false
            loop.onFirstBuffer(longArrayOf(presentationTimeUs, presentationTimeUs - offsetUs))
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }
}
