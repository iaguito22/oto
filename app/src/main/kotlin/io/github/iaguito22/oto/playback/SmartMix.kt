package io.github.iaguito22.oto.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.iaguito22.oto.dj.AdhdSegment
import io.github.iaguito22.oto.dj.DjGains
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

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

    // ---- Mezcla de DJ (canciones del DJ con análisis): larga, con las dos sonando y los graves de una sola ----

    private fun ramp(p: Float, from: Float, to: Float): Float =
        if (to <= from) (if (p >= to) 1f else 0f) else smoothStep((p - from) / (to - from))

    /**
     * Entrante en una mezcla de DJ: sube sin graves hasta ~80 % en el relevo ([swapAt]) y llega entera poco después.
     * Con los beats cuadrados ([matched]) entra antes, para que las dos se oigan juntas un buen rato.
     */
    fun djIncomingGain(progress: Float, swapAt: Float, matched: Boolean): Float = DjGains.djIncoming(progress, swapAt, matched)

    /**
     * Saliente en una mezcla de DJ: entera hasta el relevo de graves (apenas baja un poco para hacer sitio) y después se
     * va con curva de igual potencia. Con eco ([echo]) se queda un poco más, para que el eco tenga de qué salir.
     */
    fun djOutgoingGain(progress: Float, swapAt: Float, echo: Boolean): Float = DjGains.djOutgoing(progress, swapAt, echo)

    /**
     * Graves en una mezcla de DJ: la entrante sin graves hasta el relevo y la saliente sin graves desde él; el cambio
     * dura [width] (un beat aprox.), como al girar dos ruedas de graves a la vez en una mesa.
     */
    fun djBassCut(progress: Float, swapAt: Float, width: Float, incoming: Boolean): Float {
        val p = progress.coerceIn(0f, 1f)
        val w = width.coerceIn(0.02f, 0.2f)
        val swapped = ramp(p, swapAt - w / 2f, swapAt + w / 2f)
        return if (incoming) 1f - swapped else swapped
    }

    /**
     * Filtro de la saliente cuando no se cuadran beats: un pasa-altos que sube poco a poco (de casi nada a ~2 kHz) y
     * se lleva el bombo y el bajo mucho antes de que la otra llegue entera, así sus golpes no chocan.
     */
    fun djHighPassHz(progress: Float, startHz: Float, endHz: Float): Float {
        val s = ramp(progress.coerceIn(0f, 1f), 0.05f, 0.85f)
        val lo = startHz.coerceAtLeast(21f)
        val hi = endHz.coerceAtLeast(lo)
        return lo * Math.pow((hi / lo).toDouble(), s.toDouble()).toFloat()
    }

    /** Bucle con roll: la que se va se oye entera hasta el 60 % y luego se apaga (ya sin bombo por el filtro). */
    fun loopRollOutgoingGain(progress: Float): Float = DjGains.loopRollOutgoing(progress)

    /** Bucle con roll: la nueva empieza a oírse al 30 % y está entera al 80 %. */
    fun loopRollIncomingGain(progress: Float): Float = DjGains.loopRollIncoming(progress)

    /**
     * En cuántos trozos se parte el bucle de [beats] beats según avanza la mezcla. Con los beats cuadrados se parte poco
     * y tarde (a medio compás y a un beat); en el roll, cada vez más corto hasta medio beat, como un redoble.
     */
    fun loopDivision(progress: Float, roll: Boolean, beats: Int): Int {
        val p = progress.coerceIn(0f, 1f)
        val wanted = if (roll) {
            when {
                p < 0.5f -> 1
                p < 0.68f -> 2
                p < 0.84f -> 4
                else -> 8
            }
        } else {
            when {
                p < 0.75f -> 1
                p < 0.9f -> 2
                else -> 4
            }
        }
        // Nunca por debajo de medio beat.
        return wanted.coerceAtMost((beats * 2).coerceAtLeast(1))
    }

    /** Eco de salida: (seco, eco, alimentación) en [progress]. Lo seco se va y queda el eco a tempo, que se apaga. */
    fun djEcho(progress: Float, wet: Float): Triple<Float, Float, Float> {
        val p = progress.coerceIn(0f, 1f)
        if (wet <= 0f) return Triple(1f, 0f, 1f)
        val dry = 1f - ramp(p, 0.55f, 0.82f)
        val w = wet * ramp(p, 0.45f, 0.62f) * (1f - ramp(p, 0.85f, 1f))
        val feed = 1f - ramp(p, 0.72f, 0.84f)
        return Triple(dry, w, feed)
    }
}

/**
 * Filtro ligero de estante de graves (low-shelf por debajo de ~190 Hz) en PCM normalizado [-1, 1].
 * Separa los graves con un pasa-bajos de primer orden y los atenúa suavemente según [targetCut], suavizando
 * la transición por muestra para evitar chasquidos al cambiar la curva en vivo.
 */
class BassSwapFilter(private val cutoffHz: Double = DEFAULT_CUTOFF_HZ) {
    private val lp = FloatArray(MAX_CHANNELS)
    private val lp2 = FloatArray(MAX_CHANNELS)
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
        lp2.fill(0f)
        smoothedCut = targetCut
    }

    fun resetAll() {
        targetCut = 0f
        smoothedCut = 0f
        lp.fill(0f)
        lp2.fill(0f)
    }

    fun isBypassed(): Boolean = targetCut < BYPASS_EPS && smoothedCut < BYPASS_EPS

    fun clearFilterMemory() {
        lp.fill(0f)
        lp2.fill(0f)
    }

    /** Procesa un búfer PCM de 16 bits con signo in-place/out-buffer; devuelve el número de bytes escritos. */
    fun processPcm16(src: ByteBuffer, dst: ByteBuffer) {
        val chCount = channels.coerceAtLeast(1)
        val frameBytes = chCount * 2
        val frames = src.remaining() / frameBytes
        var srcPos = src.position()
        for (f in 0 until frames) {
            smoothedCut += SMOOTH_RATE * (targetCut - smoothedCut)
            val g = MAX_BASS_ATTENUATION * smoothedCut
            for (c in 0 until chCount) {
                val sample = src.getShort(srcPos) / 32768f
                srcPos += 2
                val out = (sample * (1f - g) + highs(sample, c) * g).coerceIn(-1f, 1f)
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
            val g = MAX_BASS_ATTENUATION * smoothedCut
            for (c in 0 until chCount) {
                val raw = src.getFloat(srcPos)
                val sample = if (raw.isFinite()) raw else 0f
                srcPos += 4
                val out = (sample * (1f - g) + highs(sample, c) * g).coerceIn(-1f, 1f)
                dst.putFloat(out)
            }
        }
        src.position(srcPos)
    }

    /**
     * La señal sin graves: dos pasa-altos de primer orden en cascada (cada uno = señal − su pasa-bajos), ~−21 dB a
     * 60 Hz frente a los −10 dB de uno solo. Se calcula siempre, también sin recorte, para no dar saltos al empezar.
     */
    private fun highs(x: Float, c: Int): Float {
        val l1 = lp[c] + alpha * (x - lp[c])
        lp[c] = if (l1.isFinite()) l1 else 0f
        val h1 = x - lp[c]
        val l2 = lp2[c] + alpha * (h1 - lp2[c])
        lp2[c] = if (l2.isFinite()) l2 else 0f
        return h1 - lp2[c]
    }

    private companion object {
        const val MAX_CHANNELS = 8
        const val DEFAULT_CUTOFF_HZ = 170.0
        const val MAX_BASS_ATTENUATION = 0.97f
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
    private var wasBypassed = true

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
        wasBypassed = filter.isBypassed()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        if (filter.isBypassed()) {
            if (!wasBypassed) {
                filter.clearFilterMemory()
                wasBypassed = true
            }
            out.put(inputBuffer)
            out.flip()
            return
        }
        wasBypassed = false
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
        wasBypassed = filter.isBypassed()
    }

    override fun onReset() {
        filter.resetAll()
        wasBypassed = true
    }
}

/**
 * Efecto Echo-Out + High-Pass Filter Sweep para transiciones con salto de BPM (>10 %) o cambio de género/tanda.
 * Pasa la señal por un filtro pasa-altos de 12 dB/oct suavizado por muestra y alimenta una cola de eco estéreo
 * para limpiar los graves antes de entrar la siguiente pista.
 */
class EchoOutFilterSweepFilter {
    private val prevIn1 = FloatArray(MAX_CHANNELS)
    private val prevHp1 = FloatArray(MAX_CHANNELS)
    private val prevIn2 = FloatArray(MAX_CHANNELS)
    private val prevHp2 = FloatArray(MAX_CHANNELS)

    private var delayBuffer = FloatArray(0)
    private var delayWritePos = 0
    private var delayFrames = 1
    private var sampleRate = 48_000
    private var channels = 2

    @Volatile
    var targetCutoffHz: Float = MIN_CUTOFF_HZ
        set(value) {
            field = value.coerceIn(MIN_CUTOFF_HZ, MAX_CUTOFF_HZ)
        }

    @Volatile
    var targetWet: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    @Volatile
    var targetDry: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    @Volatile
    var targetFeed: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    @Volatile
    var feedback: Float = DEFAULT_FEEDBACK
        set(value) {
            field = value.coerceIn(0f, MAX_FEEDBACK)
        }

    @Volatile
    var delayMs: Int = DEFAULT_DELAY_MS
        set(value) {
            field = value.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        }

    private var smoothedCutoffHz = MIN_CUTOFF_HZ
    private var smoothedWet = 0f
    private var smoothedDry = 1f
    private var smoothedFeed = 1f

    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate.coerceAtLeast(8_000)
        this.channels = channelCount.coerceIn(1, MAX_CHANNELS)
        val maxFrames = ((this.sampleRate * MAX_DELAY_MS) / 1000L).toInt().coerceAtLeast(1)
        val needed = maxFrames * this.channels
        if (delayBuffer.size != needed) delayBuffer = FloatArray(needed)
        updateDelayFrames()
        resetState()
    }

    private fun updateDelayFrames() {
        val maxFrames = (delayBuffer.size / channels.coerceAtLeast(1)).coerceAtLeast(1)
        delayFrames = ((sampleRate.toLong() * delayMs) / 1000L).toInt().coerceIn(1, maxFrames)
    }

    fun resetState() {
        prevIn1.fill(0f)
        prevHp1.fill(0f)
        prevIn2.fill(0f)
        prevHp2.fill(0f)
        delayBuffer.fill(0f)
        delayWritePos = 0
        smoothedCutoffHz = targetCutoffHz
        smoothedWet = targetWet
        smoothedDry = targetDry
        smoothedFeed = targetFeed
    }

    fun resetAll() {
        targetCutoffHz = MIN_CUTOFF_HZ
        targetWet = 0f
        targetDry = 1f
        targetFeed = 1f
        feedback = DEFAULT_FEEDBACK
        delayMs = DEFAULT_DELAY_MS
        resetState()
    }

    fun isBypassed(): Boolean =
        targetWet < BYPASS_EPS &&
            smoothedWet < BYPASS_EPS &&
            targetCutoffHz <= MIN_CUTOFF_HZ + 5f &&
            smoothedCutoffHz <= MIN_CUTOFF_HZ + 5f &&
            targetDry >= 1f - BYPASS_EPS &&
            smoothedDry >= 1f - BYPASS_EPS

    fun processPcm16(src: ByteBuffer, dst: ByteBuffer) {
        updateDelayFrames()
        val chCount = channels.coerceAtLeast(1)
        val frameBytes = chCount * 2
        val frames = src.remaining() / frameBytes
        var srcPos = src.position()
        val ringFrames = (delayBuffer.size / chCount).coerceAtLeast(1)
        for (f in 0 until frames) {
            stepSmoothers()
            val coeff = hpCoeff(smoothedCutoffHz)
            val a = coeff.first
            val gain = coeff.second
            val readFrame = (delayWritePos - delayFrames + ringFrames) % ringFrames
            for (c in 0 until chCount) {
                val sample = src.getShort(srcPos) / 32768f
                srcPos += 2
                val out = processSample(sample, c, chCount, a, gain, readFrame)
                val pcm = (out * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                dst.putShort(pcm)
            }
            delayWritePos = (delayWritePos + 1) % ringFrames
        }
        src.position(srcPos)
    }

    fun processPcmFloat(src: ByteBuffer, dst: ByteBuffer) {
        updateDelayFrames()
        val chCount = channels.coerceAtLeast(1)
        val frameBytes = chCount * 4
        val frames = src.remaining() / frameBytes
        var srcPos = src.position()
        val ringFrames = (delayBuffer.size / chCount).coerceAtLeast(1)
        for (f in 0 until frames) {
            stepSmoothers()
            val coeff = hpCoeff(smoothedCutoffHz)
            val a = coeff.first
            val gain = coeff.second
            val readFrame = (delayWritePos - delayFrames + ringFrames) % ringFrames
            for (c in 0 until chCount) {
                val raw = src.getFloat(srcPos)
                val sample = if (raw.isFinite()) raw else 0f
                srcPos += 4
                val out = processSample(sample, c, chCount, a, gain, readFrame)
                dst.putFloat(out)
            }
            delayWritePos = (delayWritePos + 1) % ringFrames
        }
        src.position(srcPos)
    }

    private fun stepSmoothers() {
        smoothedCutoffHz += SMOOTH_RATE * (targetCutoffHz - smoothedCutoffHz)
        smoothedWet += SMOOTH_RATE * (targetWet - smoothedWet)
        smoothedDry += SMOOTH_RATE * (targetDry - smoothedDry)
        smoothedFeed += SMOOTH_RATE * (targetFeed - smoothedFeed)
    }

    private fun hpCoeff(cutoffHz: Float): Pair<Float, Float> {
        if (cutoffHz <= MIN_CUTOFF_HZ + 1f) return 1f to 1f
        val a = exp(-2.0 * PI * cutoffHz.toDouble() / sampleRate.toDouble()).toFloat().coerceIn(0f, 0.9999f)
        return a to ((1f + a) * 0.5f)
    }

    private fun processSample(sample: Float, ch: Int, chCount: Int, a: Float, gain: Float, readFrame: Int): Float {
        val hp1 = if (a >= 0.9999f) sample else (gain * (sample - prevIn1[ch]) + a * prevHp1[ch])
        prevIn1[ch] = sample
        prevHp1[ch] = if (hp1.isFinite()) hp1 else 0f

        val hp2 = if (a >= 0.9999f) prevHp1[ch] else (gain * (prevHp1[ch] - prevIn2[ch]) + a * prevHp2[ch])
        prevIn2[ch] = prevHp1[ch]
        prevHp2[ch] = if (hp2.isFinite()) hp2 else 0f

        val readIdx = readFrame * chCount + ch
        val writeIdx = delayWritePos * chCount + ch
        val delayed = if (delayBuffer.isNotEmpty()) delayBuffer[readIdx] else 0f
        if (delayBuffer.isNotEmpty()) {
            val nextDelay = tanh((prevHp2[ch] * smoothedFeed + delayed * feedback).toDouble()).toFloat()
            delayBuffer[writeIdx] = if (nextDelay.isFinite()) nextDelay else 0f
        }
        val wetSample = (prevHp2[ch] * smoothedDry + delayed * smoothedWet).coerceIn(-1f, 1f)
        return wetSample
    }

    private companion object {
        const val MAX_CHANNELS = 8
        const val MIN_CUTOFF_HZ = 20f
        const val MAX_CUTOFF_HZ = 6_000f
        const val DEFAULT_FEEDBACK = 0.45f
        const val MAX_FEEDBACK = 0.75f
        const val DEFAULT_DELAY_MS = 280
        const val MIN_DELAY_MS = 80
        const val MAX_DELAY_MS = 1_000
        const val SMOOTH_RATE = 0.0015f
        const val BYPASS_EPS = 0.001f
    }
}

/**
 * [AudioProcessor] de Media3 para aplicar Echo-Out + High-Pass Filter Sweep en la pista saliente (`tail`)
 * sin cambiar el formato PCM ni reiniciar el AudioSink.
 */
@OptIn(UnstableApi::class)
class EchoOutFilterSweepAudioProcessor : BaseAudioProcessor() {
    private val filter = EchoOutFilterSweepFilter()
    private var wasBypassed = true

    fun configureEffect(delayMs: Int, feedback: Float) {
        filter.delayMs = delayMs
        filter.feedback = feedback
    }

    fun updateSweep(cutoffHz: Float, dry: Float, wet: Float, feed: Float) {
        filter.targetCutoffHz = cutoffHz
        filter.targetDry = dry
        filter.targetWet = wet
        filter.targetFeed = feed
    }

    fun resetEffect() {
        filter.resetAll()
        wasBypassed = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val okEncoding = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!okEncoding || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount !in 1..8) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        filter.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        wasBypassed = filter.isBypassed()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        if (filter.isBypassed()) {
            if (!wasBypassed) {
                filter.resetState()
                wasBypassed = true
            }
            out.put(inputBuffer)
            out.flip()
            return
        }
        wasBypassed = false
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
        wasBypassed = filter.isBypassed()
    }

    override fun onReset() {
        filter.resetAll()
        wasBypassed = true
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
