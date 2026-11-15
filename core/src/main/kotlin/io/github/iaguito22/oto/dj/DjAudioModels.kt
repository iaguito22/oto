package io.github.iaguito22.oto.dj

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Métricas espectrales, rítmicas y de intensidad por compás (o ventana de ~2 s sin pulso) extraídas del audio real.
 */
data class BarFeature(
    val startMs: Long,
    val endMs: Long,
    val localBpm: Double = 0.0,
    val lufs: Double = -14.0,
    val lowRms: Double = 0.0,
    val midRms: Double = 0.0,
    val highRms: Double = 0.0,
    val kickSteady: Double = 0.0,
    val drumEnergy: Double = 0.0,
    val harshness: Double = 0.0,
    val vocalRatio: Double = 0.0,
    val chroma: List<Float> = emptyList(),
) {
    val midMs: Long get() = (startMs + endMs) / 2L
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)

    /** Inclinación tonal grave-vs-agudo: útil para evitar choques entre piano suave y guitarra distorsionada. */
    val tonalBrightness: Double
        get() = ln((highRms + midRms * 0.45 + 1e-5) / (lowRms + 1e-5))

    /** Índice heurístico de solo instrumental/guitarra aguda sin voz ni base rítmica estable. */
    val soloRisk: Double
        get() {
            val highShare = highRms / (lowRms + midRms + highRms + 1e-5)
            val brightLead = (highShare * 2.1 + harshness * 0.8 - vocalRatio * 0.9 - kickSteady * 0.35).coerceIn(0.0, 1.0)
            return brightLead
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("s", startMs)
        put("e", endMs)
        put("b", (localBpm * 10.0).roundToInt() / 10.0)
        put("l", (lufs * 10.0).roundToInt() / 10.0)
        put("lo", (lowRms * 1000.0).roundToInt() / 1000.0)
        put("mi", (midRms * 1000.0).roundToInt() / 1000.0)
        put("hi", (highRms * 1000.0).roundToInt() / 1000.0)
        put("ks", (kickSteady * 100.0).roundToInt() / 100.0)
        put("de", (drumEnergy * 100.0).roundToInt() / 100.0)
        put("ha", (harshness * 1000.0).roundToInt() / 1000.0)
        put("vr", (vocalRatio * 100.0).roundToInt() / 100.0)
        if (chroma.size == 12) {
            put("ch", chroma.joinToString(",") { "%.2f".format(it) })
        }
    }

    companion object {
        fun fromJson(o: JSONObject): BarFeature? {
            val s = o.optLong("s", -1L)
            val e = o.optLong("e", -1L)
            if (s < 0L || e <= s) return null
            val chStr = o.optString("ch", "")
            val ch = if (chStr.isNotEmpty()) {
                chStr.split(',').mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 12 } ?: emptyList()
            } else emptyList()
            return BarFeature(
                startMs = s,
                endMs = e,
                localBpm = o.optDouble("b", 0.0),
                lufs = o.optDouble("l", -14.0),
                lowRms = o.optDouble("lo", 0.0),
                midRms = o.optDouble("mi", 0.0),
                highRms = o.optDouble("hi", 0.0),
                kickSteady = o.optDouble("ks", 0.0),
                drumEnergy = o.optDouble("de", 0.0),
                harshness = o.optDouble("ha", 0.0),
                vocalRatio = o.optDouble("vr", 0.0),
                chroma = ch,
            )
        }
    }
}

/** Utilidades armónicas (detección de tonalidad Krumhansl-Kessler, rueda Camelot y relaciones de BPM). */
object DjHarmonic {
    private val NOTE_NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val MAJOR_CAMELOT = arrayOf("8B", "3B", "10B", "5B", "12B", "7B", "2B", "9B", "4B", "11B", "6B", "1B")
    private val MINOR_CAMELOT = arrayOf("5A", "12A", "7A", "2A", "9A", "4A", "11A", "6A", "1A", "8A", "3A", "10A")

    // Perfiles tonales clásicos de Krumhansl-Kessler.
    private val KK_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val KK_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    data class KeyResult(
        val keyName: String,
        val camelot: String,
        val confidence: Double,
    )

    fun estimateKey(chroma: DoubleArray): KeyResult {
        if (chroma.size != 12) return KeyResult("", "", 0.0)
        val norm = sqrt(chroma.sumOf { it * it })
        if (norm <= 1e-8) return KeyResult("", "", 0.0)
        val vec = DoubleArray(12) { chroma[it] / norm }
        val vecMean = vec.average()

        var bestName = ""
        var bestCamelot = ""
        var bestCorr = -1.0
        var secondCorr = -1.0

        fun testTemplates(profile: DoubleArray, names: Array<String>, suffix: String) {
            val profMean = profile.average()
            for (root in 0 until 12) {
                var num = 0.0
                var dV = 0.0
                var dP = 0.0
                for (i in 0 until 12) {
                    val v = vec[(root + i) % 12] - vecMean
                    val p = profile[i] - profMean
                    num += v * p
                    dV += v * v
                    dP += p * p
                }
                val corr = num / (sqrt(dV * dP) + 1e-9)
                if (corr > bestCorr) {
                    secondCorr = bestCorr
                    bestCorr = corr
                    bestName = NOTE_NAMES[root] + suffix
                    bestCamelot = names[root]
                } else if (corr > secondCorr) {
                    secondCorr = corr
                }
            }
        }

        testTemplates(KK_MAJOR, MAJOR_CAMELOT, " maj")
        testTemplates(KK_MINOR, MINOR_CAMELOT, " min")
        val margin = (bestCorr - max(0.0, secondCorr)).coerceIn(0.0, 0.5)
        val conf = ((bestCorr * 0.72 + margin * 1.15)).coerceIn(0.0, 1.0)
        return KeyResult(bestName, bestCamelot, conf)
    }

    /** Distancia circular en la rueda Camelot (0 = idéntica, 1 = compatible directa, >=4 = choque armónico). */
    fun camelotDistance(c1: String, c2: String): Int {
        if (c1.isBlank() || c2.isBlank()) return 3
        val n1 = c1.dropLast(1).toIntOrNull() ?: return 3
        val l1 = c1.last().uppercaseChar()
        val n2 = c2.dropLast(1).toIntOrNull() ?: return 3
        val l2 = c2.last().uppercaseChar()
        val ring = min(abs(n1 - n2), 12 - abs(n1 - n2))
        return if (l1 == l2) ring else ring + 1
    }

    /** Compatibilidad armónica normalizada (0..1) ponderada por la confianza tonal de ambos temas. */
    fun harmonicCompatibility(a: AudioMetrics?, b: AudioMetrics?): Double {
        if (a == null || b == null || a.camelot.isBlank() || b.camelot.isBlank()) return 0.55
        val minConf = min(a.keyConfidence, b.keyConfidence)
        if (minConf < 0.22) return 0.55
        val dist = camelotDistance(a.camelot, b.camelot)
        val raw = when (dist) {
            0 -> 1.0
            1 -> 0.92
            2 -> 0.74
            3 -> 0.45
            else -> 0.16
        }
        return 0.55 + (raw - 0.55) * minConf.coerceIn(0.25, 1.0)
    }

    data class BpmRelation(
        val effectiveBpmB: Double,
        val ratioOutOverIn: Double,
        val relDiffPct: Double,
        val octaveMultiplier: Double,
    )

    /** Comprueba compatibilidad entre `bpmA` y `bpmB` incluyendo 0.5x y 2.0x. */
    fun bpmRelation(bpmA: Double, bpmB: Double): BpmRelation {
        if (bpmA <= 0.0 || bpmB <= 0.0) return BpmRelation(bpmB, 1.0, 100.0, 1.0)
        val cands = doubleArrayOf(1.0, 2.0, 0.5)
        var bestMult = 1.0
        var bestEff = bpmB
        var bestDiff = Double.POSITIVE_INFINITY
        for (m in cands) {
            val eff = bpmB * m
            val diff = abs(eff - bpmA) / bpmA * 100.0
            val bias = if (m != 1.0) 1.2 else 0.0
            if (diff + bias < bestDiff) {
                bestDiff = diff + bias
                bestMult = m
                bestEff = eff
            }
        }
        val pureDiff = abs(bestEff - bpmA) / bpmA * 100.0
        val ratio = (bpmA / bestEff).coerceIn(0.75, 1.35)
        return BpmRelation(bestEff, ratio, pureDiff, bestMult)
    }
}

/** Los 5 estilos aprobados del motor Oto DJ v2. */
enum class DjBridgeStyle(val id: String, val label: String) {
    LyricLoopCut("lyric_loop", "Bucle vocal"),
    BeatMatchLongMix("beat_mix", "Cuadre de beats"),
    EnergyDropCut("energy_drop_cut", "Corte en drop"),
    FilterEcho("filter_echo", "Filtro + eco"),
    SmartFade("smart_fade", "Fundido suave"),
    ;

    companion object {
        fun fromId(id: String?): DjBridgeStyle? = if (id.isNullOrBlank()) null else entries.firstOrNull { it.id == id }
    }
}

/** Especificación exacta de un bucle vocal con respuesta de frase compartida en la canción entrante. */
data class LyricLoopPlanSpec(
    val phraseText: String,
    val outLoopStartMs: Long,
    val outLoopEndMs: Long,
    val inPhraseStartMs: Long,
    val inPhraseEndMs: Long,
    val inEntryMs: Long,
    val repeats: Int,
    val beatMs: Double,
    val loopDurationMs: Long,
    val doubleSpeedTail: Boolean,
    val confidence: Double,
)

/**
 * Especificación sample-accurate de un puente entre la pista saliente A y la entrante B,
 * renderizable por [DjBridgeRenderer] en un único archivo WAV de puente sin deriva ni cortes.
 */
data class DjBridgeSpec(
    val style: DjBridgeStyle,
    /** Instante en A donde empieza el bloque pre-roll seco (`preRollMs` antes de efectos). */
    val preRollStartMs: Long,
    /** Instante en A donde empiezan los efectos/solapamiento de transición (`cueOutStartMs`). */
    val cueOutStartMs: Long,
    /** Punto final de referencia en A (fin del bucle, corte de drop o fin del solape). */
    val cueOutEndMs: Long,
    /** Punto exacto en B donde empieza a entrar/sonar la canción B. */
    val entryInMs: Long,
    /** Punto en B donde el puente termina de entregar el testigo en seco (`1.0x`) al reproductor principal. */
    val dryBResumeMs: Long,
    /** Duración del tramo seco inicial de A incluido al principio del puente (por defecto 3800 ms). */
    val preRollMs: Long = 3_800L,
    /** Duración del tramo seco final de B incluido al final del puente (por defecto 4000 ms). */
    val postRollMs: Long = 4_000L,
    /** Duración activa de la transición DSP (`cueOutStartMs -> fin de mezcla/cola`). */
    val transitionDurationMs: Long,
    val bpmA: Double = 0.0,
    val bpmB: Double = 0.0,
    /** Relación temporal `bpmA / effBpmB` para igualar el pulso entre A y B en WSOLA. */
    val tempoMatchRatio: Double = 1.0,
    val bassSwapFrac: Float = 0.52f,
    val echoDelayMs: Double = 360.0,
    val reverbRoom: Float = 0.72f,
    val cutoffStartHz: Float = 65f,
    val cutoffEndHz: Float = 2_400f,
    /** Parámetros exclusivos de `EnergyDropCut`: pickup pasa-bajos previo al corte + silencio antes del golpe 1. */
    val pickupMs: Long = 0L,
    val silenceMs: Long = 0L,
    val kickShiftMs: Double = 0.0,
    val incomingGainMatch: Float = 1.0f,
    val lyricLoop: LyricLoopPlanSpec? = null,
    val compatibilityScore: Double = 0.5,
    val reason: String = "",
)
