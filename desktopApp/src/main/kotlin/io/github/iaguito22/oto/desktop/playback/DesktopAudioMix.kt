package io.github.iaguito22.oto.desktop.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Ganancias por banda y nivel global de una pista durante el fundido. */
internal data class MixBands(
    val overall: Float = 1f,
    val low: Float = 1f,
    val mid: Float = 1f,
    val high: Float = 1f,
) {
    val isNeutral: Boolean get() = low == 1f && mid == 1f && high == 1f

    companion object {
        val NEUTRAL = MixBands()
    }
}

/**
 * Curvas de mezcla entre pistas:
 * - Clasica: igual potencia (`cos`/`sin`) sin alteracion espectral.
 * - Inteligente: curva en S suave (`smootherstep` + igual potencia) con relevo progresivo de graves
 *   (bass-swap) para que los bombos y bajos no choquen mientras entran agudos y medios.
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
}

/** Filtro de estado variable (TPT SVF) Linkwitz-Riley de 2º orden (Q=0,5) por canal. */
private class SvfLr2(cutoffHz: Float, sampleRate: Float) {
    private val g = tan(PI * cutoffHz / sampleRate).toFloat()
    private val k = 2f
    private val a1 = 1f / (1f + g * (g + k))
    private val a2 = g * a1
    private val a3 = g * a2

    private var ic1 = 0f
    private var ic2 = 0f

    var low = 0f
        private set
    var high = 0f
        private set

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
        low = v2
        high = input - k * v1 - v2
    }

    private fun flush(v: Float): Float = if (abs(v) < 1e-9f) 0f else v
}

/**
 * Divisor estereo de 3 bandas Linkwitz-Riley (graves < 200 Hz, medios, agudos > 2.4 kHz) sobre PCM s16le.
 *
 * Mantiene respuesta en frecuencia plana con ganancia neutra y evita saltos de fase al entrar la mezcla.
 */
internal class StereoBandFilter(
    sampleRate: Float = 48_000f,
    lowCutHz: Float = 200f,
    highCutHz: Float = 2_400f,
) {
    private val lowL = SvfLr2(lowCutHz, sampleRate)
    private val highL = SvfLr2(highCutHz, sampleRate)
    private val lowR = SvfLr2(lowCutHz, sampleRate)
    private val highR = SvfLr2(highCutHz, sampleRate)

    fun reset() {
        lowL.reset(); highL.reset()
        lowR.reset(); highR.reset()
    }

    fun processPcm16(buffer: ByteArray, byteCount: Int, bands: MixBands, masterGain: Float) {
        val gain = (masterGain * bands.overall).coerceIn(0f, 2f)
        val limit = byteCount - (byteCount % 4)
        var i = 0
        while (i < limit) {
            val left = (((buffer[i + 1].toInt() and 255) shl 8) or (buffer[i].toInt() and 255)).toShort().toFloat()
            val right = (((buffer[i + 3].toInt() and 255) shl 8) or (buffer[i + 2].toInt() and 255)).toShort().toFloat()

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

            val outL = (lLow * bands.low + lMid * bands.mid + lHigh * bands.high) * gain
            val outR = (rLow * bands.low + rMid * bands.mid + rHigh * bands.high) * gain

            val sL = outL.roundToInt().coerceIn(-32768, 32767)
            val sR = outR.roundToInt().coerceIn(-32768, 32767)
            buffer[i] = sL.toByte()
            buffer[i + 1] = (sL shr 8).toByte()
            buffer[i + 2] = sR.toByte()
            buffer[i + 3] = (sR shr 8).toByte()
            i += 4
        }
    }
}
