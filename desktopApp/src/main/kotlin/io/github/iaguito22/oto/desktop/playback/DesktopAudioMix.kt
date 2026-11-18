package io.github.iaguito22.oto.desktop.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Ganancias por banda, filtro pasa-altos y eco de salida de una pista durante el fundido. */
internal data class MixBands(
    val overall: Float = 1f,
    val low: Float = 1f,
    val mid: Float = 1f,
    val high: Float = 1f,
    val highPassHz: Float = 0f,
    val echoWet: Float = 0f,
    val echoFeedback: Float = 0.45f,
    val echoDelayMs: Int = 320,
) {
    val isNeutral: Boolean
        get() = low == 1f && mid == 1f && high == 1f && highPassHz <= 0f && echoWet <= 0f

    companion object {
        val NEUTRAL = MixBands()
    }
}

/**
 * Curvas de mezcla entre pistas:
 * - Clasica: igual potencia (`cos`/`sin`) sin alteracion espectral.
 * - Inteligente: curva en S suave (`smootherstep` + igual potencia) con relevo progresivo de graves
 *   (bass-swap) para que los bombos y bajos no choquen mientras entran agudos y medios.
 * - Echo-Out + High-Pass Sweep: barrido pasa-altos sobre la saliente mientras se disuelve en cola de eco.
 */
internal object MixCurve {
    fun smootherstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * x * (x * (x * 6f - 15f) + 10f)
    }

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    /** Devuelve `(saliente, entrante)` para un progreso [progress] en `0f..1f`. */
    fun bands(progress: Float, smartMix: Boolean): Pair<MixBands, MixBands> {
        val p = progress.coerceIn(0f, 1f)
        if (!smartMix) {
            val angle = p * (PI.toFloat() / 2f)
            return MixBands(overall = cos(angle)) to MixBands(overall = sin(angle))
        }
        val s = smootherstep(p)
        val angle = s * (PI.toFloat() / 2f)
        val outOverall = cos(angle)
        val inOverall = sin(angle)

        val outLow = 1f - smoothstep(0.15f, 0.58f, p)
        val outMid = lerp(1f, 0.72f, smoothstep(0.35f, 1.0f, p))
        val outHigh = lerp(1f, 0.55f, smoothstep(0.45f, 1.0f, p))

        val inLow = lerp(0.10f, 1.0f, smoothstep(0.35f, 0.68f, p))
        val inMid = lerp(0.75f, 1.0f, smoothstep(0.12f, 0.52f, p))
        val inHigh = lerp(0.85f, 1.0f, smoothstep(0.0f, 0.35f, p))

        return MixBands(outOverall, outLow, outMid, outHigh) to MixBands(inOverall, inLow, inMid, inHigh)
    }

    /**
     * Mezcla de DJ con los beats cuadrados (igual que en el móvil): la entrante sube sin graves hasta ~80 % en el
     * relevo ([swapAt]), la saliente sigue casi entera hasta entonces, y los graves pasan de una a otra en [width]
     * (un beat aprox.). Devuelve `(saliente, entrante)`.
     */
    fun djMatched(progress: Float, swapAt: Float, width: Float): Pair<MixBands, MixBands> {
        val p = progress.coerceIn(0f, 1f)
        val w = width.coerceIn(0.02f, 0.2f)
        val swapped = smoothstep(swapAt - w / 2f, swapAt + w / 2f, p)
        val inOverall = if (p <= swapAt) 0.8f * smoothstep(0f, swapAt, p)
        else 0.8f + 0.2f * smoothstep(swapAt, (swapAt + 0.22f).coerceAtMost(1f), p)
        val outOverall = if (p <= swapAt) 1f - 0.12f * smoothstep(0f, swapAt, p)
        else 0.88f * cos(smoothstep(swapAt, 0.95f, p) * PI.toFloat() / 2f)
        return MixBands(overall = outOverall, low = 1f - 0.97f * swapped) to MixBands(overall = inOverall, low = 0.03f + 0.97f * swapped)
    }

    /** Estado espectral y de envolvente para Echo-Out + barrido pasa-altos. */
    data class EchoOutFrame(
        val outBands: MixBands,
        val outEnvelope: Float,
        val inBands: MixBands,
        val inEnvelope: Float,
    )

    fun echoOut(
        progress: Float,
        highPassStartHz: Float = 80f,
        highPassEndHz: Float = 3_200f,
        echoOutWet: Float = 0.58f,
        echoDelayMs: Int = 320,
        echoFeedback: Float = 0.48f,
    ): EchoOutFrame {
        val p = progress.coerceIn(0f, 1f)
        val dryOut = 1f - smoothstep(0.05f, 0.58f, p)
        val sweepT = smootherstep((p / 0.65f).coerceIn(0f, 1f))
        val hpfHz = highPassStartHz + (highPassEndHz - highPassStartHz) * sweepT
        val wet = echoOutWet.coerceIn(0f, 0.85f) * smoothstep(0.0f, 0.30f, p)
        // La cola de eco sigue sonando tras bajar el seco y se desvanece suavemente antes de cerrar el deck.
        val outEnvelope = 1f - smoothstep(0.76f, 0.96f, p)
        val inEnvelope = sin(smoothstep(0.16f, 0.76f, p) * (PI.toFloat() / 2f))
        val inLow = lerp(0.35f, 1.0f, smoothstep(0.20f, 0.68f, p))
        return EchoOutFrame(
            outBands = MixBands(
                overall = dryOut,
                low = 1f - 0.65f * smoothstep(0.0f, 0.45f, p),
                mid = 1f,
                high = 1f,
                highPassHz = hpfHz,
                echoWet = wet,
                echoFeedback = echoFeedback,
                echoDelayMs = echoDelayMs,
            ),
            outEnvelope = outEnvelope,
            inBands = MixBands(overall = 1f, low = inLow, mid = 1f, high = 1f),
            inEnvelope = inEnvelope,
        )
    }
}

/** Filtro de estado variable (TPT SVF) Linkwitz-Riley de 2º orden (Q=0,5) por canal. */
private class SvfLr2(cutoffHz: Float, private val sampleRate: Float) {
    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f
    private val k = 2f

    private var ic1 = 0f
    private var ic2 = 0f

    var low = 0f
        private set
    var high = 0f
        private set

    init {
        setCutoff(cutoffHz)
    }

    fun setCutoff(cutoffHz: Float) {
        val safeHz = cutoffHz.coerceIn(20f, sampleRate * 0.42f)
        val g = tan(PI * safeHz / sampleRate).toFloat()
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun reset() {
        ic1 = 0f
        ic2 = 0f
        low = 0f
        high = 0f
    }

    fun step(input: Float) {
        val v3 = input - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = flush(2f * v1 - ic1)
        ic2 = flush(2f * v2 - ic2)
        if (!ic1.isFinite() || !ic2.isFinite()) {
            reset()
            return
        }
        low = v2
        high = input - k * v1 - v2
    }

    private fun flush(v: Float): Float = if (abs(v) < 1e-9f) 0f else v
}

/**
 * Divisor estereo de 3 bandas Linkwitz-Riley (graves < 200 Hz, medios, agudos > 2.4 kHz) sobre PCM s16le,
 * con suavizado por muestra (sin saltos ni chasquidos entre bloques), barrido pasa-altos y cola de eco estéreo.
 */
internal class StereoBandFilter(
    private val sampleRate: Float = 48_000f,
    lowCutHz: Float = 200f,
    highCutHz: Float = 2_400f,
) {
    private val lowL = SvfLr2(lowCutHz, sampleRate)
    private val highL = SvfLr2(highCutHz, sampleRate)
    private val lowR = SvfLr2(lowCutHz, sampleRate)
    private val highR = SvfLr2(highCutHz, sampleRate)
    private val hpfL = SvfLr2(30f, sampleRate)
    private val hpfR = SvfLr2(30f, sampleRate)

    private var initialized = false
    private var curMaster = 1f
    private var curOverall = 1f
    private var curLow = 1f
    private var curMid = 1f
    private var curHigh = 1f
    private var curHpfWet = 0f
    private var curHpfHz = 30f
    private var lastCoeffHpfHz = -1f
    private var curEchoWet = 0f

    private var echoL: FloatArray? = null
    private var echoR: FloatArray? = null
    private var echoWrite = 0
    private var echoLpL = 0f
    private var echoLpR = 0f

    fun reset() {
        lowL.reset(); highL.reset()
        lowR.reset(); highR.reset()
        hpfL.reset(); hpfR.reset()
        initialized = false
        curMaster = 1f
        curOverall = 1f
        curLow = 1f
        curMid = 1f
        curHigh = 1f
        curHpfWet = 0f
        curHpfHz = 30f
        lastCoeffHpfHz = -1f
        curEchoWet = 0f
        echoWrite = 0
        echoLpL = 0f
        echoLpR = 0f
        echoL?.fill(0f)
        echoR?.fill(0f)
    }

    fun processPcm16(buffer: ByteArray, byteCount: Int, bands: MixBands, masterGain: Float, inputScale: Float = 1f) {
        val targetMaster = if (masterGain.isFinite()) masterGain.coerceIn(0f, 2f) else 1f
        val targetOverall = if (bands.overall.isFinite()) bands.overall.coerceIn(0f, 1.5f) else 1f
        val targetLow = if (bands.low.isFinite()) bands.low.coerceIn(0f, 2f) else 1f
        val targetMid = if (bands.mid.isFinite()) bands.mid.coerceIn(0f, 2f) else 1f
        val targetHigh = if (bands.high.isFinite()) bands.high.coerceIn(0f, 2f) else 1f
        val rawHpfHz = if (bands.highPassHz.isFinite() && bands.highPassHz > 20f) {
            bands.highPassHz.coerceIn(20f, sampleRate * 0.40f)
        } else 0f
        val targetHpfWet = if (rawHpfHz > 20f) 1f else 0f
        val targetHpfHz = if (rawHpfHz > 20f) rawHpfHz else curHpfHz
        val targetEchoWet = if (bands.echoWet.isFinite()) bands.echoWet.coerceIn(0f, 0.9f) else 0f
        val feedback = if (bands.echoFeedback.isFinite()) bands.echoFeedback.coerceIn(0f, 0.85f) else 0.45f

        if (!initialized) {
            curMaster = targetMaster
            curOverall = targetOverall
            curLow = targetLow
            curMid = targetMid
            curHigh = targetHigh
            curHpfWet = targetHpfWet
            curHpfHz = if (rawHpfHz > 20f) rawHpfHz else 30f
            curEchoWet = targetEchoWet
            initialized = true
        }

        val maxDelay = sampleRate.toInt().coerceAtLeast(1)
        val delaySamples = ((bands.echoDelayMs.coerceIn(60, 950) * sampleRate) / 1000f)
            .roundToInt().coerceIn(1, maxDelay - 1)
        val needEcho = curEchoWet > 0.0005f || targetEchoWet > 0.0005f
        if (needEcho && echoL == null) {
            echoL = FloatArray(maxDelay)
            echoR = FloatArray(maxDelay)
        }
        val eL = echoL
        val eR = echoR

        val limit = byteCount - (byteCount % 4)
        var i = 0
        var frame = 0
        while (i < limit) {
            curMaster += SMOOTH_ALPHA * (targetMaster - curMaster)
            curOverall += SMOOTH_ALPHA * (targetOverall - curOverall)
            curLow += SMOOTH_ALPHA * (targetLow - curLow)
            curMid += SMOOTH_ALPHA * (targetMid - curMid)
            curHigh += SMOOTH_ALPHA * (targetHigh - curHigh)
            curHpfWet += SMOOTH_ALPHA * (targetHpfWet - curHpfWet)
            curEchoWet += SMOOTH_ALPHA * (targetEchoWet - curEchoWet)

            val hpfActive = curHpfWet > 0.0005f || targetHpfWet > 0.0005f
            if (hpfActive && (frame and 15) == 0) {
                curHpfHz += 0.02f * (targetHpfHz - curHpfHz)
                if (abs(curHpfHz - lastCoeffHpfHz) >= 1f) {
                    hpfL.setCutoff(curHpfHz)
                    hpfR.setCutoff(curHpfHz)
                    lastCoeffHpfHz = curHpfHz
                }
            }

            val left = (((buffer[i + 1].toInt() and 255) shl 8) or (buffer[i].toInt() and 255)).toShort() * inputScale
            val right = (((buffer[i + 3].toInt() and 255) shl 8) or (buffer[i + 2].toInt() and 255)).toShort() * inputScale

            lowL.step(left)
            val lLow = lowL.low
            val lRest = -lowL.high
            highL.step(lRest)
            val lMid = highL.low
            val lHigh = -highL.high

            lowR.step(right)
            val rLow = lowR.low
            val rRest = -lowR.high
            highR.step(rRest)
            val rMid = highR.low
            val rHigh = -highR.high

            var dryL = lLow * curLow + lMid * curMid + lHigh * curHigh
            var dryR = rLow * curLow + rMid * curMid + rHigh * curHigh

            if (hpfActive) {
                hpfL.step(dryL)
                hpfR.step(dryR)
                dryL = dryL * (1f - curHpfWet) + hpfL.high * curHpfWet
                dryR = dryR * (1f - curHpfWet) + hpfR.high * curHpfWet
            }

            var outL = dryL * curOverall
            var outR = dryR * curOverall

            if (needEcho && eL != null && eR != null) {
                var readPos = echoWrite - delaySamples
                if (readPos < 0) readPos += maxDelay
                val delL = eL[readPos]
                val delR = eR[readPos]
                // Amortiguación cálida de agudos en la realimentación del eco (~4.5 kHz).
                echoLpL += 0.35f * (delL - echoLpL)
                echoLpR += 0.35f * (delR - echoLpR)
                eL[echoWrite] = (outL + echoLpL * feedback).coerceIn(-32768f, 32768f)
                eR[echoWrite] = (outR + echoLpR * feedback).coerceIn(-32768f, 32768f)
                echoWrite = if (echoWrite + 1 >= maxDelay) 0 else echoWrite + 1
                outL += delL * curEchoWet
                outR += delR * curEchoWet
            }

            outL *= curMaster
            outR *= curMaster

            val sL = if (outL.isFinite()) outL.roundToInt().coerceIn(-32768, 32767) else 0
            val sR = if (outR.isFinite()) outR.roundToInt().coerceIn(-32768, 32767) else 0
            buffer[i] = sL.toByte()
            buffer[i + 1] = (sL shr 8).toByte()
            buffer[i + 2] = sR.toByte()
            buffer[i + 3] = (sR shr 8).toByte()
            i += 4
            frame++
        }
    }

    private companion object {
        // Suavizado ~5 ms por muestra a 48 kHz: elimina saltos entre trozos sin retardar el fundido.
        const val SMOOTH_ALPHA = 0.004f
    }
}

/**
 * Ajuste suave de tempo sin cambiar el tono (WSOLA sobre PCM s16le estéreo a 48 kHz).
 *
 * Permite arrancar la pista entrante emparejada al BPM saliente (±6 %) y devolverla suavemente
 * a 1.0x sin reiniciar FFmpeg ni producir cortes.
 */
internal class WsolaTimeStretch(
    initialTempo: Float = 1f,
) {
    @Volatile
    var targetTempo: Float = initialTempo.coerceIn(MIN_TEMPO, MAX_TEMPO)
        set(value) {
            field = value.coerceIn(MIN_TEMPO, MAX_TEMPO)
        }

    private var currentTempo: Float = targetTempo
    private var engaged: Boolean = abs(currentTempo - 1f) > ENGAGE_EPS

    // Buffer circular lineal de entrada (interleaved L, R) en floats [-32768..32767]
    private var inL = FloatArray(16384)
    private var inR = FloatArray(16384)
    private var inSize = 0

    // Segunda mitad de la ventana previa ya lista para solaparse (Hs muestras)
    private var prevTailL = FloatArray(HOP_S)
    private var prevTailR = FloatArray(HOP_S)
    private var hasPrev = false
    private var refOffset = 0 // inicio natural de la continuación de la ventana previa en inL/inR
    private var nominalOffset = 0f

    /** Muestras de entrada ya consumidas hasta el último bloque emitido (para el reloj de reproducción). */
    var sourceFramesConsumed: Long = 0L
        private set
    private var baseDiscardedFrames: Long = 0L

    private val hannFall = FloatArray(HOP_S) { i ->
        0.5f * (1f + cos(PI.toFloat() * i / HOP_S))
    }
    private val hannRise = FloatArray(HOP_S) { i ->
        0.5f * (1f - cos(PI.toFloat() * i / HOP_S))
    }

    fun reset(tempo: Float = 1f) {
        val clamped = tempo.coerceIn(MIN_TEMPO, MAX_TEMPO)
        targetTempo = clamped
        currentTempo = clamped
        engaged = abs(clamped - 1f) > ENGAGE_EPS
        inSize = 0
        hasPrev = false
        refOffset = 0
        nominalOffset = 0f
        sourceFramesConsumed = 0L
        baseDiscardedFrames = 0L
    }

    /**
     * Procesa [byteCount] bytes s16le estéreo y devuelve los bytes resultantes (siempre múltiplo de 4).
     * Cuando nunca se activó el tempo-nudge, devuelve el mismo rango sin copiar ni añadir latencia.
     */
    fun process(inputBytes: ByteArray, byteCount: Int, outputSink: (ByteArray, Int) -> Unit) {
        val validBytes = byteCount - (byteCount % 4)
        if (validBytes <= 0) return
        if (!engaged && abs(targetTempo - 1f) <= ENGAGE_EPS) {
            sourceFramesConsumed += validBytes / 4
            outputSink(inputBytes, validBytes)
            return
        }
        if (!engaged) {
            engaged = true
            currentTempo = targetTempo
        }
        val newFrames = validBytes / 4
        ensureCapacity(inSize + newFrames)
        var bi = 0
        for (f in 0 until newFrames) {
            inL[inSize + f] = (((inputBytes[bi + 1].toInt() and 255) shl 8) or (inputBytes[bi].toInt() and 255)).toShort().toFloat()
            inR[inSize + f] = (((inputBytes[bi + 3].toInt() and 255) shl 8) or (inputBytes[bi + 2].toInt() and 255)).toShort().toFloat()
            bi += 4
        }
        inSize += newFrames

        if (!hasPrev) {
            if (inSize < WINDOW) return
            // Primer bloque: emitimos los primeros Hs frames tal cual y guardamos los siguientes Hs como cola.
            emitPcm(inL, inR, 0, HOP_S, outputSink)
            for (k in 0 until HOP_S) {
                prevTailL[k] = inL[HOP_S + k]
                prevTailR[k] = inR[HOP_S + k]
            }
            hasPrev = true
            refOffset = HOP_S
            nominalOffset = (HOP_S * currentTempo)
            sourceFramesConsumed = baseDiscardedFrames + HOP_S
        }

        val outBuf = ByteArray(HOP_S * 4)
        while (true) {
            currentTempo += 0.05f * (targetTempo - currentTempo)
            if (abs(targetTempo - 1f) <= ENGAGE_EPS && abs(currentTempo - 1f) <= ENGAGE_EPS && refOffset + HOP_S <= inSize) {
                // Reenganche sin saltos al stream continuo una vez completado el retorno suave a 1.0x.
                var oi = 0
                for (k in 0 until HOP_S) {
                    val wF = hannFall[k]
                    val wR = hannRise[k]
                    val sL = (prevTailL[k] * wF + inL[refOffset + k] * wR).roundToInt().coerceIn(-32768, 32767)
                    val sR = (prevTailR[k] * wF + inR[refOffset + k] * wR).roundToInt().coerceIn(-32768, 32767)
                    outBuf[oi] = sL.toByte()
                    outBuf[oi + 1] = (sL shr 8).toByte()
                    outBuf[oi + 2] = sR.toByte()
                    outBuf[oi + 3] = (sR shr 8).toByte()
                    oi += 4
                }
                outputSink(outBuf, outBuf.size)
                val tailStart = refOffset + HOP_S
                val remFrames = inSize - tailStart
                if (remFrames > 0) {
                    emitPcm(inL, inR, tailStart, remFrames, outputSink)
                }
                baseDiscardedFrames += inSize
                sourceFramesConsumed = baseDiscardedFrames
                inSize = 0
                hasPrev = false
                refOffset = 0
                nominalOffset = 0f
                currentTempo = 1f
                engaged = false
                return
            }
            val nomInt = nominalOffset.roundToInt().coerceAtLeast(0)
            val searchEnd = nomInt + SEARCH
            val minForward = (refOffset - HOP_S / 2).coerceAtLeast(0)
            val searchStart = maxOf(nomInt - SEARCH, minForward).coerceAtMost(searchEnd)
            if (searchEnd + WINDOW > inSize) break

            val best = findBestOffset(refOffset, searchStart, searchEnd)
            var oi = 0
            for (k in 0 until HOP_S) {
                val wF = hannFall[k]
                val wR = hannRise[k]
                val sL = (prevTailL[k] * wF + inL[best + k] * wR).roundToInt().coerceIn(-32768, 32767)
                val sR = (prevTailR[k] * wF + inR[best + k] * wR).roundToInt().coerceIn(-32768, 32767)
                outBuf[oi] = sL.toByte()
                outBuf[oi + 1] = (sL shr 8).toByte()
                outBuf[oi + 2] = sR.toByte()
                outBuf[oi + 3] = (sR shr 8).toByte()
                oi += 4
                prevTailL[k] = inL[best + HOP_S + k]
                prevTailR[k] = inR[best + HOP_S + k]
            }
            outputSink(outBuf, outBuf.size)

            refOffset = best + HOP_S
            nominalOffset += HOP_S * currentTempo
            sourceFramesConsumed = maxOf(sourceFramesConsumed, baseDiscardedFrames + refOffset)

            val safeDiscard = minOf(refOffset, (nominalOffset.toInt() - SEARCH).coerceAtLeast(0))
            if (safeDiscard >= HOP_S) {
                val rem = inSize - safeDiscard
                if (rem > 0) {
                    System.arraycopy(inL, safeDiscard, inL, 0, rem)
                    System.arraycopy(inR, safeDiscard, inR, 0, rem)
                }
                inSize = rem
                refOffset -= safeDiscard
                nominalOffset -= safeDiscard
                baseDiscardedFrames += safeDiscard
            }
        }
    }

    fun flush(outputSink: (ByteArray, Int) -> Unit) {
        if (!engaged) return
        if (hasPrev) {
            emitPcm(prevTailL, prevTailR, 0, HOP_S, outputSink)
            hasPrev = false
        }
        if (refOffset < inSize) {
            emitPcm(inL, inR, refOffset, inSize - refOffset, outputSink)
            inSize = 0
        }
    }

    private fun findBestOffset(ref: Int, start: Int, end: Int): Int {
        if (ref + HOP_S > inSize) return ((start + end) / 2).coerceIn(start, end)
        var bestPos = ((start + end) / 2).coerceIn(start, end)
        var bestScore = Double.NEGATIVE_INFINITY
        // Submuestreo de paso 4 para búsqueda rápida y estable en tiempo real.
        var pos = start
        while (pos <= end) {
            var corr = 0.0
            var normA = 0.0
            var normB = 0.0
            var k = 0
            while (k < HOP_S) {
                val a = inL[ref + k] + inR[ref + k]
                val b = inL[pos + k] + inR[pos + k]
                corr += a * b
                normA += a * a
                normB += b * b
                k += 4
            }
            val denom = kotlin.math.sqrt(normA * normB)
            val score = if (denom > 1e-3) corr / denom else 0.0
            if (score > bestScore) {
                bestScore = score
                bestPos = pos
            }
            pos += 4
        }
        return bestPos
    }

    private fun emitPcm(l: FloatArray, r: FloatArray, offset: Int, frames: Int, outputSink: (ByteArray, Int) -> Unit) {
        if (frames <= 0) return
        val out = ByteArray(frames * 4)
        var oi = 0
        for (f in 0 until frames) {
            val sL = l[offset + f].roundToInt().coerceIn(-32768, 32767)
            val sR = r[offset + f].roundToInt().coerceIn(-32768, 32767)
            out[oi] = sL.toByte()
            out[oi + 1] = (sL shr 8).toByte()
            out[oi + 2] = sR.toByte()
            out[oi + 3] = (sR shr 8).toByte()
            oi += 4
        }
        outputSink(out, out.size)
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= inL.size) return
        var nextCap = inL.size
        while (nextCap < needed) nextCap *= 2
        inL = inL.copyOf(nextCap)
        inR = inR.copyOf(nextCap)
    }

    private companion object {
        const val HOP_S = 960 // 20 ms a 48 kHz
        const val WINDOW = HOP_S * 2 // 40 ms
        const val SEARCH = 672 // ±14 ms (cubre al menos un ciclo completo de graves ~70 Hz)
        const val MIN_TEMPO = 0.85f
        const val MAX_TEMPO = 1.15f
        const val ENGAGE_EPS = 0.001f // = DjTempoMatch.STRETCH_EPS
    }
}
