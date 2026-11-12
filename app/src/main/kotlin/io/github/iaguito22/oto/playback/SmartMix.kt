package io.github.iaguito22.oto.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.iaguito22.oto.dj.AdhdSegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Curvas de mezcla tipo Apple Music: ganancia de potencia constante sobre curva S suave y cruce progresivo de graves
 * (bass-swap) para que el bombo de la que entra no choque con el de la que sale.
 */
object SmartMixMath {
    /** Curva S (coseno elevado) con pendiente cero en ambos extremos. */
    fun smoothStep(progress: Float): Float {
        val x = progress.coerceIn(0f, 1f)
        return (0.5f * (1f - cos(x * PI.toFloat()))).coerceIn(0f, 1f)
    }

    /** Ganancia de la pista saliente en [progress] (0..1). */
    fun outgoingGain(progress: Float, smartMix: Boolean): Float {
        val p = progress.coerceIn(0f, 1f)
        val s = if (smartMix) smoothStep(p) else p
        return cos(s * PI.toFloat() / 2f).coerceIn(0f, 1f)
    }

    /** Ganancia de la pista entrante en [progress] (0..1). */
    fun incomingGain(progress: Float, smartMix: Boolean): Float {
        val p = progress.coerceIn(0f, 1f)
        val s = if (smartMix) smoothStep(p) else p
        return sin(s * PI.toFloat() / 2f).coerceIn(0f, 1f)
    }

    /** Cuánto se recortan los graves de la saliente (0 = plano, 1 = graves recogidos). */
    fun outgoingBassCut(progress: Float, smartMix: Boolean): Float {
        if (!smartMix) return 0f
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0.05f) return 0f
        if (p >= 0.55f) return 1f
        return smoothStep((p - 0.05f) / 0.50f)
    }

    /** Cuánto se recortan los graves de la entrante (0 = plano, 1 = graves recogidos al inicio). */
    fun incomingBassCut(progress: Float, smartMix: Boolean): Float {
        if (!smartMix) return 0f
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0.22f) return 1f
        if (p >= 0.68f) return 0f
        return 1f - smoothStep((p - 0.22f) / 0.46f)
    }
}

/**
 * Filtro ligero de estante de graves (low-shelf por debajo de ~190 Hz) en PCM normalizado [-1, 1].
 * Separa los graves con un pasa-bajos de primer orden y los atenúa suavemente según [targetCut], suavizando
 * la transición por muestra para evitar chasquidos al cambiar la curva en vivo.
 */
class BassSwapFilter(private val cutoffHz: Double = DEFAULT_CUTOFF_HZ) {
    private val lp = FloatArray(MAX_CHANNELS)
    private var channels = 0
    private var alpha = 0f

    @Volatile
    var targetCut: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private var smoothedCut = 0f

    fun configure(sampleRate: Int, channelCount: Int) {
        channels = channelCount.coerceIn(1, MAX_CHANNELS)
        alpha = if (sampleRate > 0) (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).toFloat() else 0f
        resetState()
    }

    fun resetState() {
        lp.fill(0f)
        smoothedCut = targetCut
    }

    fun resetAll() {
        targetCut = 0f
        smoothedCut = 0f
        lp.fill(0f)
    }

    fun isBypassed(): Boolean = targetCut < BYPASS_EPS && smoothedCut < BYPASS_EPS

    /** Procesa un búfer PCM de 16 bits con signo in-place/out-buffer; devuelve el número de bytes escritos. */
    fun processPcm16(src: ByteBuffer, dst: ByteBuffer) {
        val chCount = channels.coerceAtLeast(1)
        val frameBytes = chCount * 2
        val frames = src.remaining() / frameBytes
        var srcPos = src.position()
        for (f in 0 until frames) {
            smoothedCut += SMOOTH_RATE * (targetCut - smoothedCut)
            val bassGain = 1f - MAX_BASS_ATTENUATION * smoothedCut
            for (c in 0 until chCount) {
                val sample = src.getShort(srcPos) / 32768f
                srcPos += 2
                val low = lp[c] + alpha * (sample - lp[c])
                lp[c] = low
                val high = sample - low
                val out = (high + low * bassGain).coerceIn(-1f, 1f)
                val pcm = (out * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                dst.putShort(pcm)
            }
        }
        src.position(srcPos)
    }

    /** Procesa un búfer PCM float de 32 bits; devuelve el número de bytes escritos. */
    fun processPcmFloat(src: ByteBuffer, dst: ByteBuffer) {
        val chCount = channels.coerceAtLeast(1)
        val frameBytes = chCount * 4
        val frames = src.remaining() / frameBytes
        var srcPos = src.position()
        for (f in 0 until frames) {
            smoothedCut += SMOOTH_RATE * (targetCut - smoothedCut)
            val bassGain = 1f - MAX_BASS_ATTENUATION * smoothedCut
            for (c in 0 until chCount) {
                val sample = src.getFloat(srcPos)
                srcPos += 4
                val low = lp[c] + alpha * (sample - lp[c])
                lp[c] = low
                val high = sample - low
                val out = (high + low * bassGain).coerceIn(-1f, 1f)
                dst.putFloat(out)
            }
        }
        src.position(srcPos)
    }

    private companion object {
        const val MAX_CHANNELS = 8
        const val DEFAULT_CUTOFF_HZ = 190.0
        const val MAX_BASS_ATTENUATION = 0.85f
        const val SMOOTH_RATE = 0.0005f
        const val BYPASS_EPS = 0.001f
    }
}

/**
 * [AudioProcessor] de Media3 para aplicar el cruce de graves solo durante el fundido sin tocar el formato de salida
 * ni reiniciar el AudioSink, preservando la reproducción gapless y la caché de audio.
 */
@OptIn(UnstableApi::class)
class BassSwapAudioProcessor : BaseAudioProcessor() {
    private val filter = BassSwapFilter()

    var cutAmount: Float
        get() = filter.targetCut
        set(value) {
            filter.targetCut = value
        }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val okEncoding = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!okEncoding || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount !in 1..8) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        filter.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        if (filter.isBypassed()) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val order = ByteOrder.nativeOrder()
        val src = inputBuffer.duplicate().order(order)
        out.order(order)
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> filter.processPcm16(src, out)
            C.ENCODING_PCM_FLOAT -> filter.processPcmFloat(src, out)
            else -> out.put(src)
        }
        inputBuffer.position(src.position())
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        filter.resetState()
    }

    override fun onReset() {
        filter.resetAll()
    }
}

/** Lógica pura para integrar los segmentos ADHD en el reproductor y medir la escucha real. */
object AdhdPlayback {
    private const val INTRO_SKIP_TOLERANCE_MS = 1_500L

    fun transitionPlan(segment: AdhdSegment?): TransitionPlan? =
        segment?.let { TransitionPlan(it.endMs, it.mixOutDurationMs) }

    fun shouldSkipIntro(segment: AdhdSegment?, currentPositionMs: Long): Boolean =
        segment != null && segment.startMs > 0L && currentPositionMs < segment.startMs - INTRO_SKIP_TOLERANCE_MS

    fun effectiveListenedMs(posMs: Long, accumMs: Long, segment: AdhdSegment?): Long =
        if (segment != null) {
            if (accumMs > 0L) accumMs else (posMs - segment.startMs).coerceAtLeast(0L)
        } else {
            posMs
        }
}
