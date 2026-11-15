package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.lyrics.Lyrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Métricas reales extraídas del audio de una pista (PCM o curva de bombos/energía) y cacheadas por [trackId].
 * Sirve para cuadrar transiciones al beat/downbeat, igualar loudness, corregir el [VibeScore] y elegir segmentos
 * en DJ rápido sin depender de letras sincronizadas.
 */
data class AudioMetrics(
    val trackId: String,
    /** Tempo estimado en BPM; null si no hay pulso claro (ambiente, intro hablada, silencio…). */
    val bpm: Double? = null,
    /** Fiabilidad del BPM estimado (0.0 a 1.0). */
    val bpmConfidence: Double = 0.0,
    /** Fase del primer pulso/beat detectado (ms). */
    val beatPhaseMs: Long = 0L,
    /** Fase del primer tiempo fuerte de compás 4/4 (ms), para alinear entradas en downbeat. */
    val downbeatPhaseMs: Long = 0L,
    /** Loudness integrado aproximado en dBFS (-60.0 a -0.5). */
    val rmsDb: Double = -14.0,
    /** Rango dinámico en dB (percentil 95 menos percentil 20 en zonas activas). */
    val dynamicRangeDb: Double = 8.0,
    /** Fuerza/densidad percusiva de bombos (0.0 a 1.0). */
    val beatStrength: Double = 0.5,
    /** Fin de la intro suave o silencio inicial (ms). */
    val introEndMs: Long = 0L,
    /** Instante del primer drop o entrada de energía principal (ms). */
    val dropMs: Long = 0L,
    /** Inicio del silencio/outro final (ms) antes del fin de la pista. */
    val outroStartMs: Long = 0L,
    /** Duración analizada (ms). */
    val durationMs: Long = 0L,
    val analyzedAt: Long = System.currentTimeMillis(),
    /** Curva compacta de intensidad combinada (RMS + densidad de bombos, 0..100) en cubos de [energyCurveStepMs]. */
    val energyCurve: List<Int> = emptyList(),
    val energyCurveStepMs: Long = 1_000L,
    /**
     * Rejilla local de beats: un beat real (ms) por cada trozo de ~8 s donde el pulso es claro. Las canciones grabadas
     * no van a tempo fijo y un BPM redondeado se desvía cientos de ms al final; con esto la fase se mide cerca del
     * punto de la mezcla. Vacía si el análisis no viene del audio decodificado (curva en vivo, sin posición exacta).
     */
    val beatGrid: List<Long> = emptyList(),
    /** Cuánto audio se decodificó de verdad (ms). 0 si viene de la curva en vivo. Con solo el principio, se repite. */
    val analyzedMs: Long = 0L,
    /** LUFS integrados reales BS.1770-4 (dBFS ponderado K). */
    val integratedLufs: Double = rmsDb,
    val keyName: String = "",
    val camelot: String = "",
    val keyConfidence: Double = 0.0,
    val globalChroma: List<Float> = emptyList(),
    val firstSoundMs: Long = introEndMs,
    val lastSoundMs: Long = outroStartMs,
    val downbeatPhase: Int = 0,
    val bars: List<BarFeature> = emptyList(),
) {
    /** Si el análisis salió de la canción entera (y no solo del principio que había en caché al precargar). */
    val coversWholeSong: Boolean get() = analyzedMs > 0L && (durationMs <= 0L || analyzedMs >= durationMs - WHOLE_SLACK_MS)

    /**
     * Este análisis (de lo que había bajado) es solo el principio de una canción de [totalMs]. Su «outro» y su «último
     * sonido» eran donde se acababa lo analizado (medido: 56 s de 185): el plan ponía ahí el cambio, a veces antes de
     * donde iba la canción, y el trozo del DJ rápido se cortaba ahí. El final aún no se conoce: el de la canción.
     */
    fun asPartOf(totalMs: Long): AudioMetrics {
        val analyzed = durationMs
        val total = maxOf(totalMs, analyzed)
        if (total <= analyzed + WHOLE_SLACK_MS) return copy(durationMs = total, analyzedMs = analyzed)
        return copy(durationMs = total, analyzedMs = analyzed, outroStartMs = total, lastSoundMs = total)
    }

    /** Periodo de un beat en ms, o null si no hay BPM válido. */
    val beatPeriodMs: Double? get() = bpm?.takeIf { it > 0.0 }?.let { 60_000.0 / it }

    /** Si el pulso es lo bastante claro para cuadrar beats con otra canción. */
    val beatReliable: Boolean get() = bpm != null && bpmConfidence >= BEAT_MATCH_CONFIDENCE && beatGrid.isNotEmpty()

    /** El beat más cercano a [t] según la rejilla local, o null si no hay pulso claro cerca. */
    fun nearestBeatMs(t: Long): Double? {
        val period = beatPeriodMs ?: return null
        if (beatGrid.isEmpty()) return null
        var i = beatGrid.binarySearch(t)
        if (i < 0) i = -i - 1
        val anchor = listOfNotNull(beatGrid.getOrNull(i - 1), beatGrid.getOrNull(i)).minByOrNull { abs(it - t) } ?: return null
        if (abs(anchor - t) > MAX_ANCHOR_DISTANCE_MS) return null
        val k = kotlin.math.round((t - anchor) / period)
        return anchor + k * period
    }

    /**
     * El periodo de beat cerca de [t], medido entre dos anclas de la rejilla (las grabaciones no van a tempo fijo):
     * para que un bucle de cuatro beats dure justo cuatro beats de esa parte de la canción.
     */
    fun localPeriodMs(t: Long): Double? {
        val period = beatPeriodMs ?: return null
        if (beatGrid.size < 2) return period
        var i = beatGrid.binarySearch(t)
        if (i < 0) i = -i - 1
        val a = beatGrid.getOrNull((i - 2).coerceAtLeast(0)) ?: return period
        val b = beatGrid.getOrNull((i + 1).coerceAtMost(beatGrid.lastIndex)) ?: return period
        if (b - a < 4_000L || abs(a - t) > 30_000L || abs(b - t) > 30_000L) return period
        val n = kotlin.math.round((b - a) / period)
        if (n < 1.0) return period
        val local = (b - a) / n
        return if (abs(local / period - 1.0) <= 0.02) local else period
    }

    /** Cuánto va [t] por delante del beat más cercano, en fracción de beat (−0,5…0,5). null sin rejilla cerca. */
    fun beatOffset(t: Long): Double? {
        val period = beatPeriodMs ?: return null
        val beat = nearestBeatMs(t) ?: return null
        return ((t - beat) / period).coerceIn(-0.5, 0.5)
    }

    /** Devuelve `(energíaMedia, energíaMáxima)` en escala 0..100 para `[startMs, endMs]`, o null sin curva. */
    fun windowEnergyStats(startMs: Long, endMs: Long): Pair<Double, Int>? {
        if (energyCurve.isEmpty() || energyCurveStepMs <= 0L) return null
        val lo = (startMs.coerceAtLeast(0L) / energyCurveStepMs).toInt().coerceIn(0, energyCurve.lastIndex)
        val hi = (endMs.coerceAtLeast(startMs) / energyCurveStepMs).toInt().coerceIn(lo, energyCurve.lastIndex)
        var sum = 0.0
        var maxV = 0
        var count = 0
        for (i in lo..hi) {
            val v = energyCurve[i]
            sum += v
            if (v > maxV) maxV = v
            count++
        }
        return if (count == 0) null else (sum / count) to maxV
    }

    /**
     * Busca dentro de `[minMs, maxMs]` el inicio de ventana de duración [windowMs] más suave y relajada
     * (baja energía RMS y baja densidad de bombos), evitando caer encima de un estribillo o drop intenso.
     */
    fun findSoftTransitionStartMs(
        minMs: Long,
        maxMs: Long,
        windowMs: Long,
        preferLate: Boolean = false,
        preferDownbeat: Boolean = true,
    ): Long? {
        if (energyCurve.isEmpty() || energyCurveStepMs <= 0L) return null
        val lo = minMs.coerceAtLeast(0L)
        val hi = maxOf(lo, maxMs)
        if (hi <= lo) return snapBeatMs(lo, lo, hi, preferDownbeat)

        val active = energyCurve.filter { it >= 5 }
        if (active.isEmpty()) return null
        val avgTrackEnergy = active.average()
        val chorusThreshold = maxOf(62.0, avgTrackEnergy * 1.12)
        val step = energyCurveStepMs.coerceAtLeast(500L)
        val win = windowMs.coerceAtLeast(step)

        var bestStart = lo
        var bestAvg = Double.MAX_VALUE
        var bestCost = Double.POSITIVE_INFINITY

        fun evalCandidate(t: Long) {
            val stats = windowEnergyStats(t, t + win) ?: return
            val (avgE, maxE) = stats
            val silencePenalty = if (avgE < 4.0 && avgTrackEnergy >= 12.0) 35.0 else 0.0
            val peakPenalty = if (maxE > chorusThreshold) (maxE - chorusThreshold) * 1.4 else 0.0
            val frac = (t - lo).toDouble() / (hi - lo).coerceAtLeast(1L)
            val posPenalty = if (preferLate) (1.0 - frac) * 5.0 else frac * 5.0
            val cost = avgE + 0.55 * maxE + silencePenalty + peakPenalty + posPenalty
            if (cost < bestCost) {
                bestCost = cost
                bestAvg = avgE
                bestStart = t
            }
        }

        var t = lo
        while (t < hi) {
            evalCandidate(t)
            t += step
        }
        evalCandidate(hi)

        val snapWindow = minOf(650L, win / 4L)
        val snapped = snapBeatMs(
            targetMs = bestStart,
            minMs = maxOf(lo, bestStart - snapWindow),
            maxMs = minOf(hi, bestStart + snapWindow),
            preferDownbeat = preferDownbeat,
        )
        val snappedStats = windowEnergyStats(snapped, snapped + win)
        return if (snappedStats != null && snappedStats.first <= bestAvg + 12.0) snapped else bestStart
    }

    /**
     * Busca la respiración previa suave justo antes de un drop/estribillo ([dropMs]) para que la pista entrante
     * empiece relajada y suba de forma natural hacia el pico en vez de entrar encima del golpe.
     */
    fun findPreDropBreathMs(
        minMs: Long,
        maxDropMs: Long,
        leadMinMs: Long = 4_000L,
        leadMaxMs: Long = 10_000L,
        mixWindowMs: Long = 4_000L,
    ): Long? {
        if (energyCurve.isEmpty() || energyCurveStepMs <= 0L) return null
        val loBound = minMs.coerceAtLeast(0L)
        if (dropMs > loBound + leadMinMs && dropMs <= maxDropMs) {
            val winStartMin = maxOf(loBound, dropMs - leadMaxMs)
            val winStartMax = maxOf(winStartMin, dropMs - leadMinMs)
            val dropStats = windowEnergyStats(dropMs, dropMs + 6_000L)
            val soft = findSoftTransitionStartMs(
                minMs = winStartMin,
                maxMs = winStartMax,
                windowMs = minOf(mixWindowMs, maxOf(1_500L, dropMs - winStartMax)),
                preferLate = false,
                preferDownbeat = true,
            )
            if (soft != null) {
                val softStats = windowEnergyStats(soft, soft + mixWindowMs)
                if (dropStats == null || softStats == null || softStats.first <= dropStats.first * 0.92) {
                    return soft
                }
            }
        }
        // Respaldo: buscar en la primera parte una zona suave seguida en 4–12 s por una subida clara.
        val scanHi = maxOf(loBound, maxDropMs)
        if (scanHi <= loBound) return null
        val step = energyCurveStepMs.coerceAtLeast(500L)
        var bestT: Long? = null
        var bestScore = Double.POSITIVE_INFINITY
        var t = loBound
        while (t <= scanHi) {
            val here = windowEnergyStats(t, t + mixWindowMs)
            val ahead = windowEnergyStats(t + mixWindowMs, t + mixWindowMs + 8_000L)
            if (here != null && ahead != null && here.first >= 4.0 && ahead.first - here.first >= 12.0) {
                val score = here.first + 0.5 * here.second - 0.35 * (ahead.first - here.first)
                if (score < bestScore) {
                    bestScore = score
                    bestT = t
                }
            }
            t += step
        }
        return bestT?.let {
            snapBeatMs(it, maxOf(loBound, it - 600L), minOf(scanHi, it + 600L), preferDownbeat = true)
        }
    }

    /**
     * Ajusta [targetMs] al beat (o downbeat si [preferDownbeat]) más cercano dentro de `[minMs, maxMs]`.
     * Si no hay BPM fiable o ningún pulso cabe en el rango, devuelve [targetMs] acotado al rango.
     */
    fun snapBeatMs(
        targetMs: Long,
        minMs: Long = 0L,
        maxMs: Long = Long.MAX_VALUE,
        preferDownbeat: Boolean = true,
    ): Long {
        val lo = minMs.coerceAtLeast(0L)
        val hi = maxOf(lo, maxMs)
        val clamped = targetMs.coerceIn(lo, hi)
        val period = beatPeriodMs ?: return clamped
        if (bpmConfidence < MIN_SNAP_CONFIDENCE) return clamped
        val step = if (preferDownbeat) period * 4.0 else period
        val phase = (if (preferDownbeat) downbeatPhaseMs else beatPhaseMs).toDouble()
        val k = kotlin.math.round((clamped - phase) / step)
        var best = (phase + k * step).roundToLong()
        if (best < lo) best = (phase + (k + 1.0) * step).roundToLong()
        if (best > hi) best = (phase + (k - 1.0) * step).roundToLong()
        if (best in lo..hi) return best
        if (preferDownbeat) return snapBeatMs(targetMs, lo, hi, preferDownbeat = false)
        return clamped
    }

    /** Convierte las métricas físicas en un [VibeScore] real para complementar o corregir la heurística de texto. */
    fun toVibeScore(baseTags: Set<String> = emptySet()): VibeScore {
        val loudnessNorm = ((rmsDb + 26.0) / 20.0).coerceIn(0.0, 1.0)
        val tempoNorm = bpm?.let { ((it - 68.0) / 86.0).coerceIn(0.0, 1.0) } ?: 0.45
        val compressionNorm = (1.0 - (dynamicRangeDb - 4.0) / 16.0).coerceIn(0.0, 1.0)
        val energy = (0.38 * loudnessNorm + 0.28 * beatStrength + 0.22 * tempoNorm + 0.12 * compressionNorm)
            .coerceIn(0.05, 0.98)
        val chill = (0.42 * (1.0 - energy) + 0.30 * (1.0 - beatStrength) + 0.28 * (1.0 - compressionNorm))
            .coerceIn(0.05, 0.98)
        // Foco: dinámica estable, sin cambios bruscos excesivos ni bombos agresivos.
        val steadyDynamics = (1.0 - abs(dynamicRangeDb - 9.0) / 14.0).coerceIn(0.0, 1.0)
        val focus = (0.45 * steadyDynamics + 0.35 * (1.0 - beatStrength * 0.8) + 0.20 * (1.0 - loudnessNorm * 0.5))
            .coerceIn(0.05, 0.98)
        return VibeScore(energy = energy, chill = chill, focus = focus, tags = baseTags)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", trackId)
        if (bpm != null) put("bpm", bpm)
        put("conf", bpmConfidence)
        put("bp", beatPhaseMs)
        put("dbp", downbeatPhaseMs)
        put("rms", rmsDb)
        put("dr", dynamicRangeDb)
        put("bs", beatStrength)
        put("ie", introEndMs)
        put("drop", dropMs)
        put("os", outroStartMs)
        put("dur", durationMs)
        put("at", analyzedAt)
        if (energyCurve.isNotEmpty()) {
            put("ec", energyCurve.joinToString(","))
            put("ecs", energyCurveStepMs)
        }
        if (beatGrid.isNotEmpty()) put("bg", beatGrid.joinToString(","))
        put("am", analyzedMs)
        put("lufs", (integratedLufs * 100.0).roundToInt() / 100.0)
        if (keyName.isNotEmpty()) put("key", keyName)
        if (camelot.isNotEmpty()) put("cam", camelot)
        put("kconf", (keyConfidence * 1000.0).roundToInt() / 1000.0)
        if (globalChroma.size == 12) put("gc", globalChroma.joinToString(",") { "%.3f".format(it) })
        put("fsm", firstSoundMs)
        put("lsm", lastSoundMs)
        put("dbi", downbeatPhase)
        if (bars.isNotEmpty()) {
            val arr = JSONArray()
            for (b in bars) arr.put(b.toJson())
            put("bars", arr)
        }
        put("v", VERSION)
    }

    companion object {
        private const val MIN_SNAP_CONFIDENCE = 0.25
        /** Desde cuánta fiabilidad del BPM se cuadran beats entre dos canciones. */
        const val BEAT_MATCH_CONFIDENCE = 0.45
        private const val MAX_ANCHOR_DISTANCE_MS = 12_000L
        private const val WHOLE_SLACK_MS = 8_000L
        /** Sube al cambiar lo que se calcula: lo guardado con una versión anterior se vuelve a analizar. */
        const val VERSION = 4

        fun fromJson(o: JSONObject): AudioMetrics? {
            val id = o.optString("id").ifEmpty { return null }
            if (o.optInt("v", 1) < VERSION) return null
            val ecRaw = o.optString("ec", "")
            val energyCurve = if (ecRaw.isNotEmpty()) {
                ecRaw.split(',').mapNotNull { it.trim().toIntOrNull()?.coerceIn(0, 100) }
            } else emptyList()
            val energyCurveStepMs = o.optLong("ecs", 1_000L).coerceAtLeast(250L)
            val rms = o.optDouble("rms", -14.0)
            val ie = o.optLong("ie", 0L)
            val os = o.optLong("os", 0L)
            val gcStr = o.optString("gc", "")
            val gc = if (gcStr.isNotEmpty()) {
                gcStr.split(',').mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 12 } ?: emptyList()
            } else emptyList()
            val barsArr = o.optJSONArray("bars")
            val barsList = if (barsArr != null) {
                buildList(barsArr.length()) {
                    for (i in 0 until barsArr.length()) {
                        barsArr.optJSONObject(i)?.let { BarFeature.fromJson(it) }?.let(::add)
                    }
                }
            } else emptyList()
            return AudioMetrics(
                trackId = id,
                bpm = if (o.has("bpm") && !o.isNull("bpm")) o.optDouble("bpm").takeIf { it > 0.0 } else null,
                bpmConfidence = o.optDouble("conf", 0.0),
                beatPhaseMs = o.optLong("bp", 0L),
                downbeatPhaseMs = o.optLong("dbp", 0L),
                rmsDb = rms,
                dynamicRangeDb = o.optDouble("dr", 8.0),
                beatStrength = o.optDouble("bs", 0.5),
                introEndMs = ie,
                dropMs = o.optLong("drop", 0L),
                outroStartMs = os,
                durationMs = o.optLong("dur", 0L),
                analyzedAt = o.optLong("at", 0L),
                energyCurve = energyCurve,
                energyCurveStepMs = energyCurveStepMs,
                beatGrid = o.optString("bg", "").split(',').mapNotNull { it.trim().toLongOrNull() },
                analyzedMs = o.optLong("am", 0L),
                integratedLufs = o.optDouble("lufs", rms),
                keyName = o.optString("key", ""),
                camelot = o.optString("cam", ""),
                keyConfidence = o.optDouble("kconf", 0.0),
                globalChroma = gc,
                firstSoundMs = o.optLong("fsm", ie),
                lastSoundMs = o.optLong("lsm", os),
                downbeatPhase = o.optInt("dbi", 0),
                bars = barsList,
            )
        }
    }
}

/**
 * Analizador puro en Kotlin sobre PCM s16le (o curva de bombos/energía) para calcular BPM, fase de beat/downbeat,
 * loudness RMS, rango dinámico, fin de intro, drop e inicio de silencio final sin cargar la pista entera en RAM.
 */
object AudioAnalyzer {
    private const val LOW_CUT_HZ = 150.0
    private const val HIGH_CUT_HZ = 2_000.0
    private const val MIN_BPM = 62.0
    private const val MAX_BPM = 186.0
    private const val PREFERRED_CENTER_BPM = 118.0
    private const val GRID_CHUNK_MS = 8_000.0
    private const val GRID_MIN_CONTRAST = 1.6
    private const val CLARITY_FLOOR = 0.05
    private const val CLARITY_SPAN = 0.30

    /** Solo para pruebas: recibe detalles internos del análisis. */
    @Volatile var PROBE: ((String) -> Unit)? = null

    /** Sesión incremental que procesa bloques de PCM s16le o muestras flotantes en tiempo real / streaming. */
    class Session(
        private val trackId: String,
        private val sampleRate: Int,
        private val channels: Int = 1,
    ) {
        private val ch = channels.coerceAtLeast(1)
        private val rate = sampleRate.coerceAtLeast(8_000)
        private val frameSamples = (rate / 100).coerceAtLeast(64) // ~10 ms por trama
        val frameMs: Double = frameSamples * 1000.0 / rate
        private val alphaLow = (1.0 - exp(-2.0 * PI * LOW_CUT_HZ / rate)).toFloat()
        private val alphaMid = (1.0 - exp(-2.0 * PI * HIGH_CUT_HZ / rate)).toFloat()

        private var lp1 = 0f
        private var lp2 = 0f
        private var hp1 = 0f
        private var hp2 = 0f
        private var fullSum = 0.0
        private var bassSum = 0.0
        private var midSum = 0.0
        private var highSum = 0.0
        private var kwSum = 0.0
        private var countInFrame = 0

        private val kwFilter = DjDsp.KWeightingFilter(rate)
        private val dsDiv = max(1, (rate / 22_050.0).roundToInt())
        private val dsRate = rate.toDouble() / dsDiv
        private var dsPhase = 0
        private val fftSize = 2048
        private val fftHop = 512
        private val fft = DjDsp.Radix2Fft(fftSize)
        private val fftWin = DoubleArray(fftSize) { i -> 0.5 * (1.0 - cos(2.0 * PI * i / fftSize)) }
        private val fftRing = DoubleArray(fftSize)
        private val fftInput = DoubleArray(fftSize)
        private val fftMag = DoubleArray(fftSize / 2 + 1)
        private val prevMag = DoubleArray(fftSize / 2 + 1)
        private var ringIdx = 0
        private var hopAcc = 0
        private var totalDsSamples = 0L
        private val binToChroma = IntArray(fftSize / 2 + 1) { b ->
            val f = b * dsRate / fftSize
            if (f in 65.0..2000.0) {
                val midi = (12.0 * (ln(f / 440.0) / ln(2.0)) + 69.0).roundToInt()
                ((midi % 12) + 12) % 12
            } else -1
        }
        private val coreLoBin = ((220.0 * fftSize) / dsRate).roundToInt().coerceIn(1, fftSize / 2)
        private val coreHiBin = ((2600.0 * fftSize) / dsRate).roundToInt().coerceIn(coreLoBin + 1, fftSize / 2)
        private val harshLoBin = ((2600.0 * fftSize) / dsRate).roundToInt().coerceIn(coreLoBin + 1, fftSize / 2)
        private val harshHiBin = ((7200.0 * fftSize) / dsRate).roundToInt().coerceIn(harshLoBin, fftSize / 2)
        private val vocLoBin = ((320.0 * fftSize) / dsRate).roundToInt().coerceIn(1, fftSize / 2)
        private val vocHiBin = ((3000.0 * fftSize) / dsRate).roundToInt().coerceIn(vocLoBin + 1, fftSize / 2)
        private val fluxHiBin = ((8000.0 * fftSize) / dsRate).roundToInt().coerceIn(vocHiBin, fftSize / 2)

        private val fullEnv = FloatArrayBuilder()
        private val bassEnv = FloatArrayBuilder()
        // Medios (voz, caja) y agudos (charles, palmas): sin ellos el pulso de rap/reguetón salía dudoso.
        private val midEnv = FloatArrayBuilder()
        private val highEnv = FloatArrayBuilder()
        private val kwEnv = FloatArrayBuilder()
        private val specTimeMs = FloatArrayBuilder(1024)
        private val specHarsh = FloatArrayBuilder(1024)
        private val specFlux = FloatArrayBuilder(1024)
        private val specVocal = FloatArrayBuilder(1024)
        private val specChroma = FloatArrayBuilder(8192)
        private var totalInputSamples = 0L

        fun feedPcm16(bytes: ByteArray, length: Int = bytes.size) {
            val frameBytes = ch * 2
            val limit = length - (length % frameBytes)
            var i = 0
            while (i < limit) {
                var mono = 0f
                for (c in 0 until ch) {
                    val lo = bytes[i].toInt() and 0xFF
                    val hi = bytes[i + 1].toInt()
                    val sample = ((hi shl 8) or lo).toShort() / 32768f
                    mono += sample
                    i += 2
                }
                pushMono(mono / ch)
            }
        }

        fun feedSamples(samples: ShortArray, count: Int = samples.size) {
            val limit = count - (count % ch)
            var i = 0
            while (i < limit) {
                var mono = 0f
                for (c in 0 until ch) {
                    mono += samples[i++] / 32768f
                }
                pushMono(mono / ch)
            }
        }

        fun feedSamples(samples: FloatArray, count: Int = samples.size) {
            val limit = count - (count % ch)
            var i = 0
            while (i < limit) {
                var mono = 0f
                for (c in 0 until ch) {
                    mono += samples[i++]
                }
                pushMono(mono / ch)
            }
        }

        private fun pushMono(x: Float) {
            lp1 += alphaLow * (x - lp1)
            lp2 += alphaLow * (lp1 - lp2)
            hp1 += alphaMid * (x - hp1)
            hp2 += alphaMid * (hp1 - hp2)
            val mid = hp2 - lp2
            val high = x - hp2
            fullSum += (x * x).toDouble()
            bassSum += (lp2 * lp2).toDouble()
            midSum += (mid * mid).toDouble()
            highSum += (high * high).toDouble()
            val kw = kwFilter.stepMono(x.toDouble())
            kwSum += kw * kw
            totalInputSamples++

            if (dsPhase == 0) {
                fftRing[ringIdx] = x.toDouble()
                ringIdx = (ringIdx + 1) % fftSize
                totalDsSamples++
                hopAcc++
                if (totalDsSamples >= fftSize && hopAcc >= fftHop) {
                    hopAcc = 0
                    extractSpectralFrame()
                }
            }
            dsPhase++
            if (dsPhase >= dsDiv) dsPhase = 0

            countInFrame++
            if (countInFrame == frameSamples) {
                fullEnv.add(sqrt(fullSum / frameSamples).toFloat())
                bassEnv.add(sqrt(bassSum / frameSamples).toFloat())
                midEnv.add(sqrt(midSum / frameSamples).toFloat())
                highEnv.add(sqrt(highSum / frameSamples).toFloat())
                kwEnv.add((kwSum / frameSamples).toFloat())
                fullSum = 0.0
                bassSum = 0.0
                midSum = 0.0
                highSum = 0.0
                kwSum = 0.0
                countInFrame = 0
            }
        }

        private fun extractSpectralFrame() {
            var r = ringIdx
            for (i in 0 until fftSize) {
                fftInput[i] = fftRing[r] * fftWin[i]
                r++
                if (r == fftSize) r = 0
            }
            fft.magnitudeSpectrum(fftInput, fftMag)
            val centerMs = ((totalInputSamples.toDouble() / rate) * 1000.0 - (fftSize * 500.0 / dsRate)).coerceAtLeast(0.0)
            specTimeMs.add(centerMs.toFloat())

            var coreE = 0.0
            var harshE = 0.0
            var vocE = 0.0
            var totalE = 0.0
            var fluxE = 0.0
            val cBins = DoubleArray(12)
            for (b in 1..fftSize / 2) {
                val m = fftMag[b]
                val p = m * m
                totalE += p
                if (b in coreLoBin..coreHiBin) coreE += p
                if (b in harshLoBin..harshHiBin) harshE += p
                if (b in vocLoBin..vocHiBin) vocE += p
                if (b <= fluxHiBin) {
                    val d = m - prevMag[b]
                    if (d > 0.0) fluxE += d
                }
                prevMag[b] = m
                val pc = binToChroma[b]
                if (pc >= 0) cBins[pc] += m
            }
            val harsh = (sqrt(harshE + 1e-12) / (sqrt(coreE + 1e-12) + 1e-6)).coerceIn(0.0, 2.5)
            val voc = if (totalE > 1e-10) (vocE / totalE).coerceIn(0.0, 1.0) else 0.0
            val flux = (fluxE / (sqrt(totalE) * 6.0 + 1e-4)).coerceIn(0.0, 2.0)
            specHarsh.add(harsh.toFloat())
            specVocal.add(voc.toFloat())
            specFlux.add(flux.toFloat())
            for (pc in 0 until 12) specChroma.add(cBins[pc].toFloat())
        }

        fun finish(): AudioMetrics {
            if (countInFrame > frameSamples / 4) {
                fullEnv.add(sqrt(fullSum / countInFrame).toFloat())
                bassEnv.add(sqrt(bassSum / countInFrame).toFloat())
                midEnv.add(sqrt(midSum / countInFrame).toFloat())
                highEnv.add(sqrt(highSum / countInFrame).toFloat())
                kwEnv.add((kwSum / countInFrame).toFloat())
            }
            return analyzeEnvelopes(
                trackId = trackId,
                fullEnv = fullEnv.toArray(),
                bassEnv = bassEnv.toArray(),
                frameMs = frameMs,
                midEnv = midEnv.toArray(),
                highEnv = highEnv.toArray(),
                kwEnv = kwEnv.toArray(),
                specTimeMs = specTimeMs.toArray(),
                specHarsh = specHarsh.toArray(),
                specFlux = specFlux.toArray(),
                specVocal = specVocal.toArray(),
                specChroma = specChroma.toArray(),
            )
        }
    }

    fun analyzePcm16(trackId: String, samples: ShortArray, sampleRate: Int, channels: Int = 1): AudioMetrics {
        val s = Session(trackId, sampleRate, channels)
        s.feedSamples(samples)
        return s.finish()
    }

    fun analyzePcm16Bytes(trackId: String, bytes: ByteArray, sampleRate: Int, channels: Int = 1): AudioMetrics {
        val s = Session(trackId, sampleRate, channels)
        s.feedPcm16(bytes)
        return s.finish()
    }

    /** Analiza una curva ya extraída de bombos/pulsos (p. ej. `DesktopBeat` o `AudioLevels`). */
    fun analyzeBassCurve(
        trackId: String,
        bassPulses: FloatArray,
        frameMs: Double,
        rmsEnvelope: FloatArray? = null,
        durationMs: Long = 0L,
    ): AudioMetrics {
        if (bassPulses.isEmpty()) {
            return AudioMetrics(trackId = trackId, durationMs = durationMs)
        }
        val full = rmsEnvelope?.takeIf { it.size == bassPulses.size } ?: bassPulses
        // La curva en vivo no sabe en qué posición exacta de la canción empezó: su rejilla de beats no vale.
        val metrics = analyzeEnvelopes(trackId, full, bassPulses, frameMs.coerceAtLeast(1.0)).copy(beatGrid = emptyList())
        return if (durationMs > 0L) metrics.copy(durationMs = durationMs, outroStartMs = minOf(metrics.outroStartMs, durationMs)) else metrics
    }

    private fun analyzeEnvelopes(
        trackId: String,
        fullEnv: FloatArray,
        bassEnv: FloatArray,
        frameMs: Double,
        midEnv: FloatArray? = null,
        highEnv: FloatArray? = null,
        kwEnv: FloatArray? = null,
        specTimeMs: FloatArray? = null,
        specHarsh: FloatArray? = null,
        specFlux: FloatArray? = null,
        specVocal: FloatArray? = null,
        specChroma: FloatArray? = null,
    ): AudioMetrics {
        val n = minOf(fullEnv.size, bassEnv.size)
        if (n < 10) {
            return AudioMetrics(trackId = trackId, durationMs = (n * frameMs).roundToLong())
        }
        val durationMs = (n * frameMs).roundToLong()

        // 1. Loudness RMS y rango dinámico en zonas con señal.
        val sortedActive = fullEnv.filter { it > 1e-4f }.sorted()
        val rmsDb: Double
        val dynamicRangeDb: Double
        if (sortedActive.isEmpty()) {
            rmsDb = -48.0
            dynamicRangeDb = 4.0
        } else {
            val activeFloor = (sortedActive[(sortedActive.size * 0.85).toInt().coerceIn(0, sortedActive.lastIndex)] * 0.1f)
                .coerceAtLeast(1e-4f)
            val active = sortedActive.filter { it >= activeFloor }
            val meanSq = active.sumOf { (it * it).toDouble() } / active.size.coerceAtLeast(1)
            rmsDb = (10.0 * log10(meanSq.coerceAtLeast(1e-8))).coerceIn(-60.0, -0.5)
            val p20 = active[(active.size * 0.20).toInt().coerceIn(0, active.lastIndex)]
            val p95 = active[(active.size * 0.95).toInt().coerceIn(0, active.lastIndex)]
            val p20Db = 20.0 * log10(p20.toDouble().coerceAtLeast(1e-5))
            val p95Db = 20.0 * log10(p95.toDouble().coerceAtLeast(1e-5))
            dynamicRangeDb = (p95Db - p20Db).coerceIn(1.0, 35.0)
        }

        // 2. Envolvente suavizada (~400 ms) para detectar intro muda, outro silencioso, bordes de sonido y drop.
        val smoothHalf = ((200.0 / frameMs).roundToInt()).coerceIn(2, 40)
        val smoothFull = movingAverage(fullEnv, smoothHalf)
        val smoothBass = movingAverage(bassEnv, smoothHalf)
        val refLevel = percentile(smoothFull, 0.85).coerceAtLeast(1e-4f)
        val bassRef = percentile(smoothBass, 0.85).coerceAtLeast(1e-4f)

        val introEndMs = detectIntroEnd(smoothFull, refLevel, frameMs, durationMs)
        val outroStartMs = detectOutroStart(smoothFull, refLevel, frameMs, durationMs, introEndMs)
        val firstSoundMs = detectFirstSoundMs(fullEnv, refLevel, frameMs, introEndMs)
        val lastSoundMs = detectLastSoundMs(fullEnv, refLevel, frameMs, durationMs, introEndMs)

        // 3. Curva de onsets para BPM, fases y fuerza de bombos.
        val startFrame = (introEndMs / frameMs).toInt().coerceIn(0, (n - 1).coerceAtLeast(0))
        val endFrame = (outroStartMs / frameMs).toInt().coerceIn(startFrame + 1, n)
        // Dos curvas de golpes: graves + volumen (va bien con bombo a negras) y por bandas en escala logarítmica (un
        // charles o una caja cuentan aunque el 808 suene mucho más: rap, reguetón). Se queda la de pulso más claro y
        // más constante a lo largo de la canción.
        val linearRaw = FloatArray(n)
        for (i in 1 until n) {
            val dBass = (bassEnv[i] - bassEnv[i - 1]).coerceAtLeast(0f)
            val dFull = (fullEnv[i] - fullEnv[i - 1]).coerceAtLeast(0f)
            linearRaw[i] = dBass + 0.45f * dFull
        }
        val linearOnset = normalizeOnset(linearRaw, frameMs)
        val linearTempo = estimateTempo(linearOnset, bassEnv, frameMs, startFrame, endFrame)
        var onset = linearOnset
        var tempo = linearTempo
        if (midEnv != null && highEnv != null && midEnv.size >= n && highEnv.size >= n) {
            val bandRaw = FloatArray(n)
            val bands = arrayOf(bassEnv, midEnv, highEnv)
            val weights = floatArrayOf(1.0f, 0.7f, 0.8f)
            for ((b, env) in bands.withIndex()) {
                val ref = percentile(env.copyOf(n), 0.9).coerceAtLeast(1e-5f)
                val gamma = 100f / ref
                var prev = ln(1f + gamma * env[0])
                for (i in 1 until n) {
                    val cur = ln(1f + gamma * env[i])
                    bandRaw[i] += weights[b] * (cur - prev).coerceAtLeast(0f)
                    prev = cur
                }
            }
            val bandOnset = normalizeOnset(bandRaw, frameMs)
            val bandTempo = estimateTempo(bandOnset, bassEnv, frameMs, startFrame, endFrame)
            PROBE?.invoke("lineal ${linearTempo.describe()} | bandas ${bandTempo.describe()}")
            if (bandTempo.quality > linearTempo.quality) {
                onset = bandOnset
                tempo = bandTempo
            }
        }
        // En temas muy comprimidos o acústicos sin mucho salto de envolvente temporal, el flujo espectral conserva cada transitorio.
        if (tempo.bpm == null && specTimeMs != null && specFlux != null && specTimeMs.isNotEmpty() && specFlux.size == specTimeMs.size) {
            val fluxRaw = FloatArray(n)
            var sp = 0
            for (i in 0 until n) {
                val tMs = (i * frameMs).toFloat()
                while (sp + 1 < specTimeMs.size && specTimeMs[sp + 1] < tMs) sp++
                val v0 = specFlux[sp]
                val v1 = specFlux.getOrNull(sp + 1) ?: v0
                val t0 = specTimeMs[sp]
                val t1 = specTimeMs.getOrNull(sp + 1) ?: t0
                val frac = if (t1 > t0) ((tMs - t0) / (t1 - t0)).coerceIn(0f, 1f) else 0f
                val interpFlux = v0 + (v1 - v0) * frac
                val bassEdge = if (i > 0) (bassEnv[i] - bassEnv[i - 1]).coerceAtLeast(0f) else 0f
                fluxRaw[i] = interpFlux + 0.55f * (bassEdge / bassRef)
            }
            val fluxOnset = normalizeOnset(fluxRaw, frameMs)
            val fluxTempo = estimateTempo(fluxOnset, bassEnv, frameMs, startFrame, endFrame)
            PROBE?.invoke("flujo ${fluxTempo.describe()}")
            if (fluxTempo.bpm != null && fluxTempo.quality > tempo.quality) {
                onset = fluxOnset
                tempo = fluxTempo
            }
        }

        // 4. Fuerza percusiva y drop (en energía/bombos, sin depender de letra).
        val beatStrength = computeBeatStrength(onset, bassEnv, fullEnv, startFrame, endFrame, tempo)
        val rawDropMs = detectDropMs(smoothFull, smoothBass, refLevel, bassRef, frameMs, durationMs, introEndMs, outroStartMs)
        val dropMs = if (tempo.bpm != null && tempo.confidence >= 0.3) {
            snapStatic(rawDropMs, tempo.bpm, tempo.downbeatPhaseMs, tempo.beatPhaseMs, introEndMs, maxOf(introEndMs, outroStartMs - 15_000L))
        } else {
            rawDropMs
        }

        // 5. Curva compacta de intensidad por segundo (RMS + graves + densidad de bombos) para elegir valles suaves.
        val curveStepMs = 1_000L
        val bucketFrames = ((curveStepMs / frameMs).roundToInt()).coerceAtLeast(1)
        val onsetRef = percentile(onset, 0.85).coerceAtLeast(0.05f)
        val curveList = ArrayList<Int>((n / bucketFrames) + 1)
        var cIdx = 0
        while (cIdx < n) {
            val cEnd = minOf(n, cIdx + bucketFrames)
            var fSum = 0.0
            var bSum = 0.0
            var oSum = 0.0
            val len = (cEnd - cIdx).coerceAtLeast(1)
            for (k in cIdx until cEnd) {
                fSum += smoothFull[k]
                bSum += smoothBass[k]
                oSum += onset[k]
            }
            val fNorm = ((fSum / len) / refLevel).coerceIn(0.0, 1.25) / 1.25
            val bNorm = ((bSum / len) / bassRef).coerceIn(0.0, 1.25) / 1.25
            val oNorm = ((oSum / len) / onsetRef).coerceIn(0.0, 1.35) / 1.35
            val combined = (0.45 * fNorm + 0.35 * bNorm + 0.20 * oNorm).coerceIn(0.0, 1.0)
            curveList += (combined * 100.0).roundToInt().coerceIn(0, 100)
            cIdx = cEnd
        }

        // 6. Métricas v4: LUFS integrado BS.1770-4, tonalidad Camelot y métricas espectrales por compás.
        val integratedLufs = if (kwEnv != null && kwEnv.size >= n) {
            computeGatedLufsFromFrames(kwEnv, frameMs)
        } else {
            rmsDb
        }
        val spectralMeta = buildBarsAndHarmony(
            n = n,
            durationMs = durationMs,
            introEndMs = introEndMs,
            outroStartMs = outroStartMs,
            frameMs = frameMs,
            fullEnv = fullEnv,
            bassEnv = bassEnv,
            midEnv = midEnv,
            highEnv = highEnv,
            kwEnv = kwEnv,
            onset = onset,
            bassRef = bassRef,
            onsetRef = onsetRef,
            tempo = tempo,
            specTimeMs = specTimeMs,
            specHarsh = specHarsh,
            specFlux = specFlux,
            specVocal = specVocal,
            specChroma = specChroma,
        )
        val downbeatPhase = if (tempo.bpm != null && tempo.bpm > 20.0) {
            val period = 60_000.0 / tempo.bpm
            (((tempo.downbeatPhaseMs - tempo.beatPhaseMs) / period).roundToInt().let { ((it % 4) + 4) % 4 })
        } else 0

        return AudioMetrics(
            trackId = trackId,
            bpm = tempo.bpm,
            bpmConfidence = tempo.confidence,
            beatPhaseMs = tempo.beatPhaseMs,
            downbeatPhaseMs = tempo.downbeatPhaseMs,
            rmsDb = rmsDb,
            dynamicRangeDb = dynamicRangeDb,
            beatStrength = beatStrength,
            introEndMs = introEndMs,
            dropMs = dropMs,
            outroStartMs = outroStartMs,
            durationMs = durationMs,
            energyCurve = curveList,
            energyCurveStepMs = curveStepMs,
            beatGrid = tempo.grid,
            analyzedMs = durationMs,
            integratedLufs = integratedLufs,
            keyName = spectralMeta.keyName,
            camelot = spectralMeta.camelot,
            keyConfidence = spectralMeta.keyConfidence,
            globalChroma = spectralMeta.globalChroma,
            firstSoundMs = firstSoundMs,
            lastSoundMs = lastSoundMs,
            downbeatPhase = downbeatPhase,
            bars = spectralMeta.bars,
        )
    }

    private fun Int.lastIndexSafe(size: Int): Int = (size - 1).coerceAtLeast(0)

    private data class SpectralBarsResult(
        val keyName: String,
        val camelot: String,
        val keyConfidence: Double,
        val globalChroma: List<Float>,
        val bars: List<BarFeature>,
    )

    private fun computeGatedLufsFromFrames(kwEnv: FloatArray, frameMs: Double): Double {
        if (kwEnv.isEmpty()) return -70.0
        val blockFrames = max(1, (400.0 / frameMs).roundToInt())
        val stepFrames = max(1, (100.0 / frameMs).roundToInt())
        if (kwEnv.size <= blockFrames) {
            val ms = kwEnv.average()
            return if (ms <= 1e-12) -70.0 else (-0.691 + 10.0 * log10(ms)).coerceIn(-70.0, 6.0)
        }
        val pref = DoubleArray(kwEnv.size + 1)
        for (i in kwEnv.indices) pref[i + 1] = pref[i] + kwEnv[i].toDouble()
        val absGate = 10.0.pow((-70.0 + 0.691) / 10.0)
        val blocks = ArrayList<Double>((kwEnv.size / stepFrames) + 1)
        var pos = 0
        while (pos + blockFrames <= kwEnv.size) {
            val ms = (pref[pos + blockFrames] - pref[pos]) / blockFrames
            if (ms > absGate) blocks += ms
            pos += stepFrames
        }
        if (blocks.isEmpty()) return -70.0
        val avgAbs = blocks.average()
        val relGate = avgAbs * 0.1
        val finalMs = (blocks.filter { it >= relGate }.takeIf { it.isNotEmpty() }?.average() ?: avgAbs).coerceAtLeast(1e-12)
        return (-0.691 + 10.0 * log10(finalMs)).coerceIn(-70.0, 6.0)
    }

    private fun detectFirstSoundMs(fullEnv: FloatArray, refLevel: Float, frameMs: Double, introEndMs: Long): Long {
        val threshold = maxOf(refLevel * 0.04f, 0.004f)
        val sustain = ((60.0 / frameMs).roundToInt()).coerceIn(2, 12)
        val maxIdx = (fullEnv.size - sustain).coerceAtMost((introEndMs / frameMs).toInt() + sustain)
        for (i in 0..maxIdx) {
            var ok = true
            for (k in 0 until sustain) {
                if (fullEnv[i + k] < threshold) { ok = false; break }
            }
            if (ok) {
                val ms = (i * frameMs).roundToLong()
                return if (ms < 120L) 0L else ms.coerceIn(0L, introEndMs)
            }
        }
        return introEndMs
    }

    private fun detectLastSoundMs(
        fullEnv: FloatArray,
        refLevel: Float,
        frameMs: Double,
        durationMs: Long,
        minSoundMs: Long,
    ): Long {
        val threshold = maxOf(refLevel * 0.03f, 0.003f)
        val sustain = ((60.0 / frameMs).roundToInt()).coerceIn(2, 12)
        for (i in fullEnv.lastIndex downTo sustain) {
            var ok = true
            for (k in 0 until sustain) {
                if (fullEnv[i - k] < threshold) { ok = false; break }
            }
            if (ok) {
                val ms = ((i + 1) * frameMs).roundToLong() + 120L
                return if (ms >= durationMs - 250L) durationMs else ms.coerceIn(minSoundMs, durationMs)
            }
        }
        return durationMs
    }

    private fun buildBarsAndHarmony(
        n: Int,
        durationMs: Long,
        introEndMs: Long,
        outroStartMs: Long,
        frameMs: Double,
        fullEnv: FloatArray,
        bassEnv: FloatArray,
        midEnv: FloatArray?,
        highEnv: FloatArray?,
        kwEnv: FloatArray?,
        onset: FloatArray,
        bassRef: Float,
        onsetRef: Float,
        tempo: TempoResult,
        specTimeMs: FloatArray?,
        specHarsh: FloatArray?,
        specFlux: FloatArray?,
        specVocal: FloatArray?,
        specChroma: FloatArray?,
    ): SpectralBarsResult {
        if (specTimeMs == null || specChroma == null || specTimeMs.isEmpty() || specChroma.size < 12) {
            return SpectralBarsResult("", "", 0.0, emptyList(), emptyList())
        }
        val specCount = minOf(
            specTimeMs.size,
            specHarsh?.size ?: Int.MAX_VALUE,
            specFlux?.size ?: Int.MAX_VALUE,
            specVocal?.size ?: Int.MAX_VALUE,
            specChroma.size / 12,
        )
        if (specCount <= 0) return SpectralBarsResult("", "", 0.0, emptyList(), emptyList())

        // Tonalidad global ponderando el cuerpo central de la pista (fuera de intros/outros silenciosos).
        val globalChromaD = DoubleArray(12)
        val bodyLo = maxOf(introEndMs.toDouble(), durationMs * 0.10)
        val bodyHi = minOf(outroStartMs.toDouble(), durationMs * 0.90)
        var chromaWeightSum = 0.0
        for (s in 0 until specCount) {
            val t = specTimeMs[s].toDouble()
            val w = if (t in bodyLo..bodyHi) 1.0 else 0.35
            val base = s * 12
            for (pc in 0 until 12) {
                globalChromaD[pc] += specChroma[base + pc] * w
            }
            chromaWeightSum += w
        }
        val globalNorm = sqrt(globalChromaD.sumOf { it * it })
        val globalChroma = if (globalNorm > 1e-8) {
            globalChromaD.map { (it / globalNorm).toFloat() }
        } else emptyList()
        val keyRes = if (chromaWeightSum > 0.0 && globalNorm > 1e-8) {
            DjHarmonic.estimateKey(globalChromaD)
        } else DjHarmonic.KeyResult("", "", 0.0)

        // Segmentación en compases alineados al downbeat/rejilla local o ventanas de 2 s sin pulso.
        val bars = ArrayList<BarFeature>()
        val hasPulse = tempo.bpm != null && tempo.bpm > 20.0 && tempo.confidence >= 0.25
        val nominalPeriod = if (hasPulse) 60_000.0 / tempo.bpm!! else 500.0
        val barLenMs = if (hasPulse) (nominalPeriod * 4.0).coerceIn(1_200.0, 4_500.0) else 2_000.0

        val boundaries = ArrayList<Long>()
        boundaries += 0L
        var cur = if (hasPulse) {
            val firstAligned = tempo.downbeatPhaseMs.coerceIn(0L, barLenMs.roundToLong())
            if (firstAligned >= 800L) firstAligned else (firstAligned + barLenMs.roundToLong()).coerceAtMost(durationMs)
        } else 2_000L
        while (cur < durationMs - 600L) {
            val snapped = if (hasPulse && tempo.grid.isNotEmpty()) {
                var gIdx = tempo.grid.binarySearch(cur)
                if (gIdx < 0) gIdx = -gIdx - 1
                val anchor = listOfNotNull(tempo.grid.getOrNull(gIdx - 1), tempo.grid.getOrNull(gIdx))
                    .minByOrNull { abs(it - cur) }
                if (anchor != null && abs(anchor - cur) <= nominalPeriod * 0.45) anchor else cur
            } else cur
            val nextB = snapped.coerceIn(boundaries.last() + 500L, durationMs)
            boundaries += nextB
            cur = nextB + barLenMs.roundToLong()
        }
        if (boundaries.last() < durationMs) {
            if (durationMs - boundaries.last() >= 500L || boundaries.size == 1) {
                boundaries += durationMs
            } else {
                boundaries[boundaries.lastIndex] = durationMs
            }
        }

        var specPtr = 0
        for (bi in 0 until boundaries.lastIndex) {
            val sMs = boundaries[bi]
            val eMs = boundaries[bi + 1]
            if (eMs <= sMs) continue
            val fLo = (sMs / frameMs).toInt().coerceIn(0, n - 1)
            val fHi = (eMs / frameMs).toInt().coerceIn(fLo + 1, n)
            val fCount = (fHi - fLo).coerceAtLeast(1)

            var lowSq = 0.0
            var midSq = 0.0
            var highSq = 0.0
            var fullSq = 0.0
            var kwSum = 0.0
            var onsetSum = 0.0
            var onsetMax = 0.0
            for (k in fLo until fHi) {
                val fv = fullEnv[k].toDouble()
                val bv = bassEnv[k].toDouble()
                val mv = midEnv?.getOrNull(k)?.toDouble() ?: fv * 0.55
                val hv = highEnv?.getOrNull(k)?.toDouble() ?: fv * 0.35
                fullSq += fv * fv
                lowSq += bv * bv
                midSq += mv * mv
                highSq += hv * hv
                if (kwEnv != null && k < kwEnv.size) kwSum += kwEnv[k]
                val ov = onset[k].toDouble()
                onsetSum += ov
                if (ov > onsetMax) onsetMax = ov
            }
            val barFullRms = sqrt(fullSq / fCount)
            val barBassRms = sqrt(lowSq / fCount)
            val barMidRms = sqrt(midSq / fCount)
            val barHighRms = sqrt(highSq / fCount)
            val barKwMs = if (kwEnv != null) kwSum / fCount else (fullSq / fCount)
            val barLufs = if (barKwMs <= 1e-12) -60.0 else (-0.691 + 10.0 * log10(barKwMs)).coerceIn(-60.0, 6.0)

            while (specPtr < specCount && specTimeMs[specPtr] < sMs) specPtr++
            var spEnd = specPtr
            var hSum = 0.0
            var fFlux = 0.0
            var vSum = 0.0
            var spN = 0
            val barChromaD = DoubleArray(12)
            while (spEnd < specCount && specTimeMs[spEnd] < eMs) {
                if (specHarsh != null) hSum += specHarsh[spEnd]
                if (specFlux != null) fFlux += specFlux[spEnd]
                if (specVocal != null) vSum += specVocal[spEnd]
                val base = spEnd * 12
                for (pc in 0 until 12) barChromaD[pc] += specChroma[base + pc]
                spN++
                spEnd++
            }
            specPtr = spEnd
            val harshness = if (spN > 0) (hSum / spN).coerceIn(0.0, 2.0) else 0.15
            val fluxMean = if (spN > 0) (fFlux / spN) else 0.0
            val vocalRatio = if (spN > 0) (vSum / spN).coerceIn(0.0, 1.0) else 0.25
            val bcNorm = sqrt(barChromaD.sumOf { it * it })
            val barChroma = if (bcNorm > 1e-8) barChromaD.map { (it / bcNorm).toFloat() } else emptyList()

            val bassRel = (barBassRms / bassRef.toDouble().coerceAtLeast(1e-4)).coerceIn(0.0, 1.25) / 1.25
            val onsetRel = ((onsetSum / fCount) / (onsetRef.toDouble().coerceAtLeast(0.05) * 0.85)).coerceIn(0.0, 1.25) / 1.25
            val fluxRel = (fluxMean / 0.45).coerceIn(0.0, 1.0)
            val tempoW = if (hasPulse) tempo.confidence.coerceIn(0.0, 1.0) else 0.10
            val kickSteady = (0.45 * tempoW * bassRel.coerceAtLeast(0.15) + 0.35 * bassRel + 0.20 * onsetRel).coerceIn(0.0, 1.0)
            val drumEnergy = (0.40 * onsetRel + 0.35 * bassRel + 0.25 * fluxRel).coerceIn(0.0, 1.0)
            val localBpm = if (hasPulse) (240_000.0 / (eMs - sMs)).coerceIn(tempo.bpm!! * 0.85, tempo.bpm * 1.15) else 0.0

            bars += BarFeature(
                startMs = sMs,
                endMs = eMs,
                localBpm = localBpm,
                lufs = barLufs,
                lowRms = barBassRms,
                midRms = barMidRms,
                highRms = barHighRms,
                kickSteady = kickSteady,
                drumEnergy = drumEnergy,
                harshness = harshness,
                vocalRatio = vocalRatio,
                chroma = barChroma,
            )
        }
        return SpectralBarsResult(keyRes.keyName, keyRes.camelot, keyRes.confidence, globalChroma, bars)
    }

    private fun detectIntroEnd(smoothFull: FloatArray, refLevel: Float, frameMs: Double, durationMs: Long): Long {
        val threshold = refLevel * 0.22f
        val sustain = ((450.0 / frameMs).roundToInt()).coerceIn(3, 60)
        val maxSearch = minOf(smoothFull.size - sustain, (smoothFull.size * 0.45).toInt())
        for (i in 0..maxSearch) {
            var ok = true
            for (k in 0 until sustain) {
                if (smoothFull[i + k] < threshold) { ok = false; break }
            }
            if (ok) {
                val ms = (i * frameMs).roundToLong()
                return if (ms < 600L) 0L else ms.coerceAtMost(durationMs)
            }
        }
        return 0L
    }

    private fun detectOutroStart(
        smoothFull: FloatArray,
        refLevel: Float,
        frameMs: Double,
        durationMs: Long,
        introEndMs: Long,
    ): Long {
        val threshold = refLevel * 0.20f
        val sustain = ((350.0 / frameMs).roundToInt()).coerceIn(3, 50)
        for (i in smoothFull.lastIndex downTo sustain) {
            var ok = true
            for (k in 0 until sustain) {
                if (smoothFull[i - k] < threshold) { ok = false; break }
            }
            if (ok) {
                val ms = ((i + 1) * frameMs).roundToLong() + 300L
                return if (ms >= durationMs - 800L) durationMs else ms.coerceIn(introEndMs, durationMs)
            }
        }
        return durationMs
    }

    private fun detectDropMs(
        smoothFull: FloatArray,
        smoothBass: FloatArray,
        refLevel: Float,
        bassRef: Float,
        frameMs: Double,
        durationMs: Long,
        introEndMs: Long,
        outroStartMs: Long,
    ): Long {
        if (durationMs <= 45_000L) return introEndMs
        val winBefore = ((2_500.0 / frameMs).roundToInt()).coerceAtLeast(10)
        val winAfter = ((4_500.0 / frameMs).roundToInt()).coerceAtLeast(20)
        val minFrame = maxOf((introEndMs / frameMs).toInt(), (5_000.0 / frameMs).toInt(), winBefore)
        val maxLimitMs = minOf((durationMs * 0.65).toLong(), outroStartMs - 25_000L)
        val maxFrame = minOf(smoothFull.size - winAfter - 1, (maxLimitMs / frameMs).toInt())
        if (maxFrame <= minFrame) return introEndMs

        val pref = DoubleArray(smoothFull.size + 1)
        val prefBass = DoubleArray(smoothBass.size + 1)
        for (i in smoothFull.indices) {
            pref[i + 1] = pref[i] + smoothFull[i]
            prefBass[i + 1] = prefBass[i] + smoothBass[i]
        }

        val step = (200.0 / frameMs).roundToInt().coerceAtLeast(1)
        var bestFrame = minFrame
        var bestScore = Double.NEGATIVE_INFINITY
        var i = minFrame
        while (i <= maxFrame) {
            val bFull = (pref[i] - pref[i - winBefore]) / winBefore
            val aFull = (pref[i + winAfter] - pref[i]) / winAfter
            val bBass = (prefBass[i] - prefBass[i - winBefore]) / winBefore
            val aBass = (prefBass[i + winAfter] - prefBass[i]) / winAfter

            val contrast = ((aFull - bFull) / refLevel).coerceAtLeast(0.0) +
                1.35 * ((aBass - bBass) / bassRef).coerceAtLeast(0.0)
            val level = 0.65 * (aFull / refLevel) + 0.55 * (aBass / bassRef)
            val posFrac = i.toDouble() / smoothFull.size
            val posBias = 1.0 - 0.55 * abs(posFrac - 0.28)
            val score = (contrast * 1.4 + level) * posBias
            if (score > bestScore) {
                bestScore = score
                bestFrame = i
            }
            i += step
        }
        return (bestFrame * frameMs).roundToLong().coerceIn(introEndMs, durationMs)
    }

    private class TempoResult(
        val bpm: Double?,
        val confidence: Double,
        val beatPhaseMs: Long,
        val downbeatPhaseMs: Long,
        val grid: List<Long> = emptyList(),
        /** Cuánto se parece la curva de golpes a sí misma un beat después (0..1). */
        val clarity: Double = 0.0,
        /** Qué parte de los saltos entre anclas de la rejilla caen en un número entero de beats (0..1). */
        val steadiness: Double = 0.0,
    ) {
        val quality: Double get() = if (bpm == null) clarity * 0.5 else clarity * (0.5 + 0.5 * steadiness)
        fun describe() = "%s c=%.3f s=%.2f fiab=%.2f".format(bpm?.let { "%.2f".format(it) } ?: "?", clarity, steadiness, confidence)
    }

    private fun normalizeOnset(raw: FloatArray, frameMs: Double): FloatArray {
        val n = raw.size
        val onset = FloatArray(n)
        val localAvg = movingAverage(raw, ((150.0 / frameMs).roundToInt()).coerceIn(3, 30))
        var maxOnset = 1e-6f
        for (i in 1 until n) {
            val v = (raw[i] - localAvg[i] * 0.65f).coerceAtLeast(0f)
            onset[i] = v
            if (v > maxOnset) maxOnset = v
        }
        for (i in 1 until n) onset[i] /= maxOnset
        return onset
    }

    /** Saltos entre anclas consecutivas (≤ 3 trozos) que caen a ±8 % de un número entero de beats. */
    private fun steadiness(grid: List<Long>, periodMs: Double): Double {
        var pairs = 0
        var good = 0
        for (i in 1 until grid.size) {
            val d = (grid[i] - grid[i - 1]).toDouble()
            if (d > GRID_CHUNK_MS * 3.5) continue
            pairs++
            val beats = d / periodMs
            if (abs(beats - kotlin.math.round(beats)) <= 0.08) good++
        }
        return if (pairs == 0) 0.0 else good.toDouble() / pairs
    }

    private data class AutocorrWindowResult(
        val bpm: Double,
        val clarity: Double,
        val clarityConf: Double,
        val peakRatio: Double,
    )

    private fun scoreAutocorrWindow(
        onset: FloatArray,
        frameMs: Double,
        offset: Int,
        winLen: Int,
    ): AutocorrWindowResult? {
        val minLag = ((60_000.0 / (MAX_BPM * 2.0)) / frameMs).toInt().coerceAtLeast(4)
        val maxLag = ((60_000.0 / (MIN_BPM * 0.5)) / frameMs).toInt().coerceAtMost(winLen / 2)
        if (maxLag <= minLag + 4) return null

        var mean = 0.0
        for (k in 0 until winLen) mean += onset[offset + k]
        mean /= winLen
        val centered = DoubleArray(winLen) { onset[offset + it] - mean }
        var ac0 = 0.0
        for (k in 0 until winLen) ac0 += centered[k] * centered[k]
        ac0 /= winLen
        if (ac0 <= 1e-12) return null
        val ac = DoubleArray(maxLag + 1)
        val absAc = ArrayList<Double>(maxLag - minLag + 1)
        for (lag in minLag..maxLag) {
            var sum = 0.0
            val count = winLen - lag
            if (count <= 0) continue
            for (k in 0 until count) {
                sum += centered[k] * centered[k + lag]
            }
            val v = sum / count
            ac[lag] = v
            absAc += abs(v)
        }
        if (absAc.isEmpty()) return null
        absAc.sort()
        val medianAbsAc = absAc[absAc.size / 2].coerceAtLeast(ac0 * 0.008)

        fun interpAc(lag: Double): Double {
            val lo = lag.toInt()
            if (lo < minLag || lo + 1 > maxLag) return 0.0
            val frac = lag - lo
            return ac[lo] * (1.0 - frac) + ac[lo + 1] * frac
        }

        fun combScore(bpm: Double): Double {
            val lag1 = (60_000.0 / bpm) / frameMs
            val lag2 = lag1 * 2.0
            val raw = interpAc(lag1) + 0.45 * interpAc(lag2)
            val octDist = ln(bpm / PREFERRED_CENTER_BPM) / ln(2.0)
            val prior = exp(-0.5 * (octDist / 0.58).pow(2))
            return raw * prior
        }

        var bestBpm = PREFERRED_CENTER_BPM
        var bestScore = -1.0
        var b = MIN_BPM
        while (b <= MAX_BPM) {
            val s = combScore(b)
            if (s > bestScore) {
                bestScore = s
                bestBpm = b
            }
            b += 0.5
        }
        if (bestScore <= 1e-7) return null

        var fineBpm = bestBpm
        var fineScore = bestScore
        var cand = (bestBpm - 1.5).coerceAtLeast(MIN_BPM)
        val fineEnd = (bestBpm + 1.5).coerceAtMost(MAX_BPM)
        while (cand <= fineEnd) {
            val s = combScore(cand)
            if (s > fineScore) {
                fineScore = s
                fineBpm = cand
            }
            cand += 0.1
        }
        var finest = fineBpm
        var finestScore = fineScore
        cand = (fineBpm - 0.1).coerceAtLeast(MIN_BPM)
        while (cand <= (fineBpm + 0.1).coerceAtMost(MAX_BPM)) {
            val s = combScore(cand)
            if (s > finestScore) {
                finestScore = s
                finest = cand
            }
            cand += 0.01
        }
        val roundedBpm = (finest * 100.0).roundToInt() / 100.0
        val lagBest = (60_000.0 / finest) / frameMs
        val rawPeak = (interpAc(lagBest) + interpAc(lagBest * 2.0)) / 2.0
        val clarity = (rawPeak / ac0).coerceIn(-1.0, 1.0)
        val clarityConf = ((clarity - CLARITY_FLOOR) / CLARITY_SPAN).coerceIn(0.0, 1.0)
        val peakRatio = rawPeak / medianAbsAc
        return AutocorrWindowResult(roundedBpm, clarity, clarityConf, peakRatio)
    }

    private fun estimateTempo(
        onset: FloatArray,
        bassEnv: FloatArray,
        frameMs: Double,
        startFrame: Int,
        endFrame: Int,
    ): TempoResult {
        val activeLen = endFrame - startFrame
        if (activeLen < (6_000.0 / frameMs).toInt()) {
            return TempoResult(null, 0.0, 0L, 0L)
        }
        val primaryLen = minOf(activeLen, (90_000.0 / frameMs).toInt())
        val primaryOffset = startFrame + ((activeLen - primaryLen) / 3).coerceAtLeast(0)
        var best = scoreAutocorrWindow(onset, frameMs, primaryOffset, primaryLen)

        // Si la ventana central cae en un puente lento/rubato, probamos ventanas focalizadas de ~45 s (estribillos/drops).
        val subLen = (45_000.0 / frameMs).toInt()
        if ((best == null || best.clarityConf < 0.18) && activeLen > subLen + (10_000.0 / frameMs).toInt()) {
            val maxSubOff = (endFrame - subLen).coerceAtLeast(startFrame)
            for (frac in doubleArrayOf(0.18, 0.48, 0.74)) {
                val off = (startFrame + (activeLen * frac).toInt() - subLen / 2).coerceIn(startFrame, maxSubOff)
                val cand = scoreAutocorrWindow(onset, frameMs, off, subLen) ?: continue
                if (best == null || cand.clarityConf > best.clarityConf ||
                    (cand.clarityConf == best.clarityConf && cand.peakRatio > best.peakRatio)
                ) {
                    best = cand
                }
            }
        }
        if (best == null || best.clarity < 0.025 || (best.clarityConf < 0.12 && best.peakRatio < 2.2)) {
            return TempoResult(null, best?.clarityConf ?: 0.0, 0L, 0L, clarity = best?.clarity ?: 0.0)
        }
        val roundedBpm = best.bpm
        val clarity = best.clarity
        val clarityConf = best.clarityConf

        val periodFrames = (60_000.0 / roundedBpm) / frameMs
        val periodInt = periodFrames.roundToInt().coerceAtLeast(1)
        var bestPhase = 0
        var bestPhaseEnergy = -1.0
        for (phi in 0 until periodInt) {
            var e = 0.0
            var k = 0
            while (true) {
                val idx = startFrame + phi + (k * periodFrames).roundToInt()
                if (idx >= endFrame - 1) break
                val peak = maxOf(onset[idx], 0.65f * onset[(idx - 1).coerceAtLeast(0)], 0.65f * onset[idx + 1])
                e += peak + 0.25f * bassEnv[idx]
                k++
            }
            if (e > bestPhaseEnergy) {
                bestPhaseEnergy = e
                bestPhase = phi
            }
        }

        // Estimación de downbeat (compás 4/4): qué beat de cada 4 concentra más golpe/energía.
        var bestBarOffset = 0
        var bestBarEnergy = -1.0
        for (barBeat in 0..3) {
            var e = 0.0
            var bar = 0
            while (true) {
                val idx = startFrame + bestPhase + ((bar * 4 + barBeat) * periodFrames).roundToInt()
                if (idx >= endFrame - 1) break
                e += bassEnv[idx] + 0.6 * onset[idx]
                bar++
            }
            if (e > bestBarEnergy) {
                bestBarEnergy = e
                bestBarOffset = barBeat
            }
        }

        val beatPhaseMs = ((startFrame + bestPhase) * frameMs).roundToLong() % (60_000.0 / roundedBpm).roundToLong().coerceAtLeast(1L)
        val barPeriodMs = (4.0 * 60_000.0 / roundedBpm).roundToLong().coerceAtLeast(1L)
        val downbeatPhaseMs = ((startFrame + bestPhase + (bestBarOffset * periodFrames).roundToInt()) * frameMs).roundToLong() % barPeriodMs

        // Rejilla: primero libre; luego se ajusta una recta a toda la canción (tempo exacto y la fase del beat que
        // manda) y se vuelve a buscar cada ancla cerca de esa recta, para que ninguna caiga en el contratiempo.
        val periodMs0 = 60_000.0 / roundedBpm
        val free = localGrid(onset, frameMs, startFrame, endFrame, periodFrames)
        val fit = fitBeatLine(free, periodMs0)
        val periodMs = fit?.second ?: periodMs0
        val grid = if (fit != null) {
            localGrid(onset, frameMs, startFrame, endFrame, periodMs / frameMs, line = fit.first to periodMs)
        } else free
        val bpmOut = (60_000.0 / periodMs * 100.0).roundToInt() / 100.0
        val steady = steadiness(grid, periodMs)
        if (clarityConf < 0.12 && (fit == null || steady < 0.52 || grid.size < 8)) {
            return TempoResult(null, clarityConf, 0L, 0L, clarity = clarity, steadiness = steady)
        }
        PROBE?.invoke("bpm %.2f→%.2f fr[".format(roundedBpm, bpmOut) + grid.zipWithNext { x, y -> val bt = (y - x) / periodMs; "%.2f".format(bt - kotlin.math.round(bt)) }.joinToString(" ") + "]")
        val (beatPhaseOut, downbeatOut) = if (fit != null) {
            val a = fit.first
            val beatPhase = ((a % periodMs) + periodMs) % periodMs
            // El tiempo fuerte: de cada cuatro beats de la recta, el que más golpe y graves lleva.
            var bestBar = 0
            var bestE = -1.0
            for (barBeat in 0..3) {
                var e = 0.0
                var k = 0
                while (true) {
                    val idx = ((beatPhase + (k * 4 + barBeat) * periodMs) / frameMs).roundToInt()
                    if (idx >= endFrame - 1) break
                    if (idx >= startFrame) e += bassEnv[idx] + 0.6 * onset[idx]
                    k++
                }
                if (e > bestE) { bestE = e; bestBar = barBeat }
            }
            beatPhase.roundToLong() to (beatPhase + bestBar * periodMs).roundToLong() % (periodMs * 4.0).roundToLong().coerceAtLeast(1L)
        } else beatPhaseMs to downbeatPhaseMs
        // Fiabilidad: pulso claro y, sobre todo, una rejilla que se mantiene toda la canción (con muchas anclas que caen
        // justas, aunque la curva de golpes sea floja por la voz, los beats están ahí).
        val steadyConf = ((steady - 0.5) / 0.45).coerceIn(0.0, 1.0) * (grid.size / 12.0).coerceAtMost(1.0)
        val confidence = (0.55 * clarityConf + 0.65 * steadyConf + 0.1).coerceIn(0.0, 1.0)
        return TempoResult(bpmOut, confidence, beatPhaseOut, downbeatOut, grid, clarity, steady)
    }

    /**
     * Recta de beats `t = a + k·P` que mejor pasa por las anclas: la fase que comparten la mayoría (las de contratiempo
     * fuera) y el periodo exacto por mínimos cuadrados. Null con pocas anclas o si el periodo se aleja más de un 2 %.
     */
    private fun fitBeatLine(grid: List<Long>, periodMs: Double): Pair<Double, Double>? {
        if (grid.size < 4) return null
        var sx = 0.0
        var sy = 0.0
        for (t in grid) {
            val ang = 2.0 * PI * ((t % periodMs) / periodMs)
            sx += kotlin.math.cos(ang)
            sy += kotlin.math.sin(ang)
        }
        val phase = ((kotlin.math.atan2(sy, sx) / (2.0 * PI)) * periodMs).let { if (it < 0) it + periodMs else it }
        fun circDist(t: Long, ph: Double, p: Double): Double {
            val d = ((t - ph) % p + p) % p
            return minOf(d, p - d)
        }
        var inliers = grid.filter { circDist(it, phase, periodMs) <= 0.2 * periodMs }
        if (inliers.size < 3) return null
        var a = phase
        var p = periodMs
        repeat(2) {
            val ks = inliers.map { kotlin.math.round((it - a) / p) }
            val n = ks.size.toDouble()
            val mk = ks.sum() / n
            val mt = inliers.sumOf { it.toDouble() } / n
            var cov = 0.0
            var varK = 0.0
            for (i in ks.indices) {
                cov += (ks[i] - mk) * (inliers[i] - mt)
                varK += (ks[i] - mk) * (ks[i] - mk)
            }
            if (varK <= 0.0) return null
            val newP = cov / varK
            if (abs(newP / periodMs - 1.0) > 0.02) return null
            a = mt - newP * mk
            p = newP
            val keep = grid.filter { t ->
                val k = kotlin.math.round((t - a) / p)
                abs(t - (a + k * p)) <= 0.15 * p
            }
            if (keep.size < 3) return null
            inliers = keep
        }
        return a to p
    }

    /**
     * Un beat por cada trozo de [GRID_CHUNK_MS]: la fase que mejor cae sobre los golpes de ese trozo, con el periodo
     * global. Los trozos sin pulso claro (puentes, parones) se saltan.
     */
    private fun localGrid(
        onset: FloatArray,
        frameMs: Double,
        startFrame: Int,
        endFrame: Int,
        periodFrames: Double,
        /** Recta de beats (fase y periodo en ms): si se da, cada ancla se busca a ±20 % de beat de ella. */
        line: Pair<Double, Double>? = null,
    ): List<Long> {
        val periodInt = periodFrames.roundToInt().coerceAtLeast(2)
        val chunk = (GRID_CHUNK_MS / frameMs).roundToInt().coerceAtLeast(periodInt * 4)
        val grid = ArrayList<Long>()
        var c0 = maxOf(1, startFrame)
        while (c0 + periodInt * 4 < endFrame - 1) {
            val c1 = minOf(endFrame - 1, c0 + chunk)
            var bestPhi = 0
            var best = -1.0
            var total = 0.0
            for (phi in 0 until periodInt) {
                var e = 0.0
                var k = 0
                while (true) {
                    val idx = c0 + phi + (k * periodFrames).roundToInt()
                    if (idx >= c1 - 1) break
                    e += maxOf(onset[idx], 0.7f * onset[idx - 1], 0.7f * onset[idx + 1])
                    k++
                }
                total += e
                if (e > best) {
                    best = e
                    bestPhi = phi
                }
            }
            val mean = total / periodInt
            if (line != null) {
                // Solo cerca de la recta: el beat que manda en la canción, no el contratiempo de este trozo.
                val (a, p) = line
                val c0Ms = c0 * frameMs
                val predMs = a + kotlin.math.ceil((c0Ms - a) / p) * p
                val predPhi = ((predMs - c0Ms) / frameMs)
                best = -1.0
                val reach = 0.2 * periodFrames
                for (phi in 0 until periodInt) {
                    var d = abs(phi - predPhi) % periodFrames
                    d = minOf(d, periodFrames - d)
                    if (d > reach) continue
                    var e = 0.0
                    var k = 0
                    while (true) {
                        val idx = c0 + phi + (k * periodFrames).roundToInt()
                        if (idx >= c1 - 1) break
                        e += maxOf(onset[idx], 0.7f * onset[idx - 1], 0.7f * onset[idx + 1])
                        k++
                    }
                    if (e > best) {
                        best = e
                        bestPhi = phi
                    }
                }
            }
            // Pulso claro: la mejor fase destaca bastante sobre la media de todas.
            if (mean > 1e-6 && best / mean >= (if (line != null) GRID_MIN_CONTRAST * 0.8 else GRID_MIN_CONTRAST)) {
                // Afinar dentro de la trama (10 ms) con el centro de masas de los golpes de ese trozo.
                var w = 0.0
                var m = 0.0
                var k = 0
                while (true) {
                    val idx = c0 + bestPhi + (k * periodFrames).roundToInt()
                    if (idx >= c1 - 1) break
                    val center = idx - (c0 + bestPhi + k * periodFrames)
                    for (d in -1..1) {
                        val v = onset[idx + d].toDouble()
                        w += v
                        m += v * (center + d)
                    }
                    k++
                }
                val shift = if (w > 1e-9) (m / w).coerceIn(-1.0, 1.0) else 0.0
                grid += ((c0 + bestPhi + shift) * frameMs).roundToLong()
            }
            c0 += chunk
        }
        return grid
    }

    private fun computeBeatStrength(
        onset: FloatArray,
        bassEnv: FloatArray,
        fullEnv: FloatArray,
        startFrame: Int,
        endFrame: Int,
        tempo: TempoResult,
    ): Double {
        var bassSum = 0.0
        var fullSum = 0.0
        var transientSum = 0.0
        val count = (endFrame - startFrame).coerceAtLeast(1)
        for (i in startFrame until endFrame) {
            bassSum += bassEnv[i]
            fullSum += fullEnv[i]
            transientSum += onset[i]
        }
        val bassShare = if (fullSum > 1e-6) (bassSum / fullSum).coerceIn(0.0, 1.0) else 0.0
        val pulseActivity = (transientSum / count * 4.5).coerceIn(0.0, 1.0)
        return (0.45 * tempo.confidence + 0.35 * pulseActivity + 0.20 * bassShare).coerceIn(0.0, 1.0)
    }

    private fun snapStatic(
        targetMs: Long,
        bpm: Double,
        downbeatPhaseMs: Long,
        beatPhaseMs: Long,
        minMs: Long,
        maxMs: Long,
    ): Long {
        val period = 60_000.0 / bpm
        val step = period * 4.0
        val k = kotlin.math.round((targetMs - downbeatPhaseMs) / step)
        val cand = (downbeatPhaseMs + k * step).roundToLong()
        if (cand in minMs..maxMs) return cand
        val kb = kotlin.math.round((targetMs - beatPhaseMs) / period)
        val candB = (beatPhaseMs + kb * period).roundToLong()
        return if (candB in minMs..maxMs) candB else targetMs.coerceIn(minMs, maxMs)
    }

    private fun movingAverage(data: FloatArray, halfWin: Int): FloatArray {
        val out = FloatArray(data.size)
        if (data.isEmpty()) return out
        val pref = DoubleArray(data.size + 1)
        for (i in data.indices) pref[i + 1] = pref[i] + data[i]
        for (i in data.indices) {
            val lo = (i - halfWin).coerceAtLeast(0)
            val hi = (i + halfWin).coerceAtMost(data.lastIndex)
            out[i] = ((pref[hi + 1] - pref[lo]) / (hi - lo + 1)).toFloat()
        }
        return out
    }

    private fun percentile(data: FloatArray, q: Double): Float {
        if (data.isEmpty()) return 0f
        val sorted = data.sortedArray()
        val idx = (q * sorted.lastIndex).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private class FloatArrayBuilder(initialCapacity: Int = 2048) {
        private var buf = FloatArray(initialCapacity)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = v
        }

        fun toArray(): FloatArray = buf.copyOf(size)
    }
}

/**
 * Caché persistente por `track.id` de métricas de audio reales ([AudioMetrics]) compartida entre Android y Desktop.
 */
class AudioAnalysisCache(
    private val file: File? = File(OtoDirs.cacheDir, "dj/audio-analysis.json"),
) {
    private val cache = ConcurrentHashMap<String, AudioMetrics>()
    private val lock = Any()
    @Volatile private var loaded = false

    init {
        loadIfNeeded()
    }

    fun get(trackId: String): AudioMetrics? {
        loadIfNeeded()
        return cache[trackId]
    }

    fun put(metrics: AudioMetrics, persist: Boolean = false) {
        loadIfNeeded()
        cache[metrics.trackId] = metrics
        trimIfNeeded()
        if (persist) save()
    }

    fun analyzeAndPutPcm16(
        trackId: String,
        samples: ShortArray,
        sampleRate: Int,
        channels: Int = 1,
        persist: Boolean = false,
    ): AudioMetrics = AudioAnalyzer.analyzePcm16(trackId, samples, sampleRate, channels).also {
        put(it, persist = persist)
    }

    fun analyzeAndPutBassCurve(
        trackId: String,
        bassPulses: FloatArray,
        frameMs: Double,
        rmsEnvelope: FloatArray? = null,
        durationMs: Long = 0L,
        persist: Boolean = false,
    ): AudioMetrics = AudioAnalyzer.analyzeBassCurve(trackId, bassPulses, frameMs, rmsEnvelope, durationMs).also {
        put(it, persist = persist)
    }

    fun save() {
        val f = file ?: return
        synchronized(lock) {
            trimIfNeeded()
            runCatching {
                val root = JSONObject()
                for ((id, m) in cache) {
                    root.put(id, m.toJson())
                }
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }
        }
    }

    private fun loadIfNeeded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            runCatching {
                val f = file?.takeIf { it.exists() } ?: return@runCatching
                val root = JSONObject(f.readText())
                for (k in root.keys()) {
                    root.optJSONObject(k)?.let(AudioMetrics::fromJson)?.let { cache[k] = it }
                }
            }
            loaded = true
        }
    }

    private fun trimIfNeeded() {
        if (cache.size <= MAX_ENTRIES) return
        val oldest = cache.values.sortedBy { it.analyzedAt }.take(cache.size - MAX_ENTRIES)
        oldest.forEach { cache.remove(it.trackId) }
    }

    companion object {
        private const val MAX_ENTRIES = 2_000
    }
}

/** Tipo de transición recomendado entre la pista saliente y la entrante. */
enum class DjTransitionStyle {
    /**
     * Las dos con pulso claro y a ±6 % de tempo: mezcla larga con los beats cuadrados (la que se va se ajusta al
     * tempo de la que entra y se corrige la fase), las dos sonando a la vez y relevo de graves en un beat.
     */
    BeatMatchLongMix,
    /** Fundido equal-power sin efectos (la mezcla inteligente apagada). */
    SmoothEqualPower,
    /**
     * Sin beats que cuadrar (tempo distinto, pulso dudoso o sin analizar): la que se va pierde los graves y el bombo
     * con un pasa-altos que sube poco a poco, así sus golpes no chocan con los de la que entra; eco a tempo al final si
     * cambia de tanda o el tempo es muy distinto. Igual de larga que las otras.
     */
    EchoOutFilterSweep,
    /**
     * La que se va tiene pulso claro pero no se puede cuadrar con la otra (tempo muy distinto o la otra sin pulso): se
     * repite su último compás en bucle, cada vez más corto («roll»), mientras un pasa-altos le quita el bombo, y la
     * nueva entra encima en la segunda mitad. Sin dos baterías a la vez desfasadas.
     */
    LoopRollOut,
    /** Bucle vocal con respuesta de frase compartida en la canción entrante (motor v2). */
    LyricLoopCut,
    /** Pickup corto + silencio nítido + entrada directa al tiempo 1 de un drop/estribillo (motor v2). */
    EnergyDropCut,
}

/** Nombre corto para la cola (el mismo que los estilos del puente en el móvil). */
val DjTransitionStyle.label: String
    get() = when (this) {
        DjTransitionStyle.BeatMatchLongMix -> "Cuadre de beats"
        DjTransitionStyle.SmoothEqualPower -> "Fundido suave"
        DjTransitionStyle.EchoOutFilterSweep -> "Filtro + eco"
        DjTransitionStyle.LoopRollOut -> "Bucle y roll"
        DjTransitionStyle.LyricLoopCut -> "Bucle vocal"
        DjTransitionStyle.EnergyDropCut -> "Corte en drop"
    }

/** Plan de transición ejecutable por Android (`Crossfade`/`SmartMix`) y Desktop (`DesktopAudioEngine`/`DesktopAudioMix`). */
data class DjTransitionPlan(
    val style: DjTransitionStyle,
    val fadeDurationMs: Long,
    /**
     * Relación de tempo saliente/entrante (con mitades y dobles). La entrante NO se acelera: en Android se ajusta la
     * que se va ([outgoingSpeed]), que ya no vuelve; en el PC la entrante va a este ritmo hasta que la otra se apaga.
     */
    val incomingTempoNudge: Double = 1.0,
    /** Velocidad para la saliente durante la mezcla, para que su tempo sea el de la entrante (1/[incomingTempoNudge]). */
    val outgoingSpeed: Double = 1.0,
    /** En qué punto del fundido (0..1) se cambian los graves de una a otra. */
    val bassSwapAt: Float = 0.5f,
    /** Cuadrar la fase de los beats en vivo (las dos tienen rejilla fiable). */
    val phaseLock: Boolean = false,
    /** Análisis usados (para cuadrar la fase en vivo y para los registros). */
    val outgoingAudio: AudioMetrics? = null,
    val incomingAudio: AudioMetrics? = null,
    /** Instante en la saliente (ms) donde arranca el fundido de salida. */
    val outgoingFadeStartMs: Long,
    /** Punto de entrada recomendado en la entrante (ms), evitando silencio inicial y alineado al downbeat. */
    val incomingStartMs: Long,
    /** Ajuste lineal de volumen para igualar loudness de la entrante con la saliente. */
    val incomingGainMatch: Float = 1f,
    val useBassSwap: Boolean = true,
    val useHighPassSweep: Boolean = false,
    val highPassStartHz: Float = 120f,
    val highPassEndHz: Float = 3_600f,
    val echoOutWet: Float = 0f,
    val echoDelayMs: Long = 320L,
    val echoFeedback: Float = 0.35f,
    val bpmDiffPercent: Double? = null,
    val reason: String = "",
    /**
     * Bucle de la saliente: empieza en [loopStartMs] (un tiempo fuerte; también es donde arranca el fundido), dura
     * [loopBeats] beats de [loopBeatMs]. Sin bucle si [loopBeats] es 0.
     */
    val loopStartMs: Long = -1L,
    val loopBeats: Int = 0,
    val loopBeatMs: Double = 0.0,
    /** Especificación v2 para renderizado anticipado de puente en Android sin deriva entre reproductores. */
    val bridgeSpec: DjBridgeSpec? = null,
)

/**
 * Planificador compartido de transiciones según BPM, salto de género/tanda, análisis real de audio y segmentos ADHD.
 */
object DjTransitionPlanner {
    private const val BEAT_MATCH_MAX_PCT = 6.0
    private const val ECHO_OUT_MIN_PCT = 10.0
    /** Lo que se deja sonar tras el último verso antes de empezar a mezclar (la última sílaba y su cola). */
    private const val VOCAL_TAIL_MS = 600L
    /** Lo mínimo que se acorta un fundido para que no se pisen dos voces. */
    private const val MIN_VOICE_FADE_MS = 5_000L
    /** Con menos mezcla que esto no da para un bucle que se oiga repetir. */
    private const val MIN_LOOP_FADE_MS = 4_000L
    /** Lo que tiene que quedar de canción tras la mezcla con bucle (sigue avanzando por debajo). */
    private const val LOOP_END_MARGIN_MS = 400L
    /** En qué punto del fundido empieza a oírse la nueva en el bucle con roll. */
    const val LOOP_ROLL_IN_AT = 0.3
    /** Lo más corta que deja el DJ una mezcla (salvo que la canción no dé para más). */
    private const val MIN_AUTO_FADE_MS = 4_000L
    /** En una lista normal solo se salta el silencio del principio, nunca una intro de verdad. */
    private const val MAX_SKIPPED_INTRO_MS = 4_000L
    /** Hasta dónde se aleja la salida del final del trozo (DJ rápido) o del final de la canción. */
    private const val ADHD_REACH_MS = 14_000L
    private const val FULL_REACH_MS = 45_000L
    /** Desde cuánto se oye una canción en la mezcla (−9 dB) y hasta qué punto importa que canten las dos. */
    private const val AUDIBLE = 0.35f
    private const val CLASH_UNTIL = 0.85f
    private const val CLASH_STEP = 0.025f
    private const val CLASH_COST = 4.0
    private const val LOOP_VOICE_MS = 300L
    private const val LOOP_VOICE_COST = 3.0
    private const val MID_PHRASE_COST = 1.0

    fun plan(
        outgoingDurationMs: Long,
        configuredFadeMs: Long,
        outgoingAudio: AudioMetrics? = null,
        incomingAudio: AudioMetrics? = null,
        outgoingVibe: VibeScore? = null,
        incomingVibe: VibeScore? = null,
        outgoingGenre: String? = null,
        incomingGenre: String? = null,
        tandaChanged: Boolean = false,
        outgoingAdhd: AdhdSegment? = null,
        incomingAdhd: AdhdSegment? = null,
        /** Cuándo deja de cantar la saliente (fin del último verso de su letra sincronizada), si se sabe. */
        outgoingVocalEndMs: Long? = null,
        /** Cuándo empieza a cantar la entrante, si se sabe. */
        incomingVocalStartMs: Long? = null,
        /** Dónde canta la saliente (bloques de su letra sincronizada); vacío si no se sabe. */
        outgoingVocals: List<SongSection> = emptyList(),
        /** Dónde canta la entrante; vacío si no se sabe. */
        incomingVocals: List<SongSection> = emptyList(),
        /**
         * El DJ decide cuánto dura la mezcla y dónde empieza (en beats y compases de la música, sin pisar dos voces);
         * [configuredFadeMs] solo dice si hay mezcla (> 0). Sin esto, se respeta [configuredFadeMs].
         */
        autoLength: Boolean = false,
        /** La entrante empieza por su principio (sin el silencio inicial), como en una lista normal. */
        incomingFromStart: Boolean = false,
        incomingDurationMs: Long = 0L,
        outgoingLyrics: Lyrics.Synced? = null,
        incomingLyrics: Lyrics.Synced? = null,
        djMode: DjMode = DjMode.Normal,
        isDjSession: Boolean = true,
        recentStyles: List<DjBridgeStyle> = emptyList(),
    ): DjTransitionPlan {
        val safeOutDur = outgoingDurationMs.coerceAtLeast(0L)
        val baseFade = configuredFadeMs.coerceAtLeast(0L)
        val auto = autoLength && baseFade > 0L
        val effectiveDjSession = isDjSession && !incomingFromStart
        val hasV4Bars = outgoingAudio?.bars?.isNotEmpty() == true && incomingAudio?.bars?.isNotEmpty() == true
        val hasSyncedPair = outgoingLyrics != null && incomingLyrics != null
        if (baseFade > 0L && (hasV4Bars || hasSyncedPair || (!effectiveDjSession && incomingDurationMs > 0L && (outgoingAudio?.analyzedMs ?: 0L) > 0L))) {
            val spec = DjBridgePlanner.plan(
                outgoingDurationMs = safeOutDur,
                incomingDurationMs = incomingDurationMs,
                configuredFadeMs = baseFade,
                outgoingAudio = outgoingAudio,
                incomingAudio = incomingAudio,
                outgoingLyrics = outgoingLyrics,
                incomingLyrics = incomingLyrics,
                outgoingVocals = outgoingVocals,
                incomingVocals = incomingVocals,
                outgoingAdhd = outgoingAdhd,
                incomingAdhd = incomingAdhd,
                tandaChanged = tandaChanged,
                djMode = djMode,
                isDjSession = effectiveDjSession,
                autoLength = auto,
                recentStyles = recentStyles,
            )
            return fromBridgeSpec(spec, outgoingAudio, incomingAudio)
        }

        // Diferencia porcentual de BPM considerando también mitades/dobles musicales si cae cerca.
        val outBpm = outgoingAudio?.takeIf { it.bpm != null && it.bpmConfidence >= 0.25 }?.bpm
        val inBpm = incomingAudio?.takeIf { it.bpm != null && it.bpmConfidence >= 0.25 }?.bpm
        val bpmInfo = if (outBpm != null && inBpm != null && inBpm > 0.0) {
            val directRatio = outBpm / inBpm
            val halfRatio = outBpm / (inBpm * 2.0)
            val doubleRatio = outBpm / (inBpm * 0.5)
            val candidates = listOf(directRatio, halfRatio, doubleRatio)
            val bestRatio = candidates.minBy { abs(it - 1.0) }
            val diffPct = abs(bestRatio - 1.0) * 100.0
            bestRatio to diffPct
        } else null

        val bpmDiffPct = bpmInfo?.second
        val genreJump = !outgoingGenre.isNullOrBlank() && !incomingGenre.isNullOrBlank() &&
            !outgoingGenre.equals(incomingGenre, ignoreCase = true)
        val vibeJump = if (outgoingVibe != null && incomingVibe != null) {
            outgoingVibe.synergyWith(incomingVibe) < 0.42
        } else false

        // Solo se cuadran beats si las dos tienen pulso claro y rejilla: con un BPM dudoso, dos baterías a la vez
        // durante 10 s suenan desfasadas. Un cambio de tanda o de género con el mismo tempo también se cuadra.
        val bothReliable = outgoingAudio?.beatReliable == true && incomingAudio?.beatReliable == true
        // Sin cuadrar, pero con el pulso de la que se va claro: bucle de su último compás en vez de un fundido a secas.
        var style = when {
            bothReliable && bpmDiffPct != null && bpmDiffPct <= BEAT_MATCH_MAX_PCT -> DjTransitionStyle.BeatMatchLongMix
            outgoingAudio?.beatReliable == true && (auto || baseFade >= MIN_LOOP_FADE_MS) -> DjTransitionStyle.LoopRollOut
            else -> DjTransitionStyle.EchoOutFilterSweep
        }
        val onBeat = style != DjTransitionStyle.EchoOutFilterSweep

        // La duración elegida en Ajustes se respeta en todas (antes los saltos de tanda se quedaban en ~3 s y cortaban
        // el rollo). Solo se limita a un tercio de la canción, o del trozo en DJ rápido.
        var fadeDurationMs = when {
            baseFade <= 0L -> 0L
            outgoingAdhd != null -> minOf(baseFade, outgoingAdhd.clipDurationMs / 3L)
            safeOutDur > 0L -> baseFade.coerceAtMost(safeOutDur / 3L)
            else -> baseFade
        }

        // Punto de salida en saliente: busca valles/zonas relajadas antes del silencio final (evita cruzar sobre estribillo).
        val rawOutEnd = when {
            outgoingAdhd != null -> outgoingAdhd.endMs
            outgoingAudio != null && outgoingAudio.outroStartMs > fadeDurationMs * 2L -> minOf(safeOutDur, outgoingAudio.outroStartMs)
            else -> safeOutDur
        }
        val tailStart = (rawOutEnd - fadeDurationMs).coerceAtLeast(0L)
        val rawFadeStart = when {
            outgoingAudio != null && fadeDurationMs > 0L && outgoingAudio.energyCurve.isNotEmpty() && outgoingAdhd != null -> {
                val minAdhdFade = maxOf(
                    outgoingAdhd.startMs + AdhdPlanner.MIN_CLIP_MS - fadeDurationMs,
                    outgoingAdhd.fadeOutStartMs - 6_000L,
                ).coerceIn(0L, tailStart)
                val softAdhd = outgoingAudio.findSoftTransitionStartMs(
                    minMs = minAdhdFade,
                    maxMs = tailStart,
                    windowMs = fadeDurationMs,
                    preferLate = true,
                    preferDownbeat = onBeat,
                )
                val adhdStats = outgoingAudio.windowEnergyStats(tailStart, rawOutEnd)
                val softStats = softAdhd?.let { outgoingAudio.windowEnergyStats(it, it + fadeDurationMs) }
                if (softAdhd != null && adhdStats != null && softStats != null && softStats.first + 8.0 < adhdStats.first) {
                    softAdhd
                } else {
                    tailStart
                }
            }
            outgoingAudio != null && fadeDurationMs > 0L && outgoingAudio.energyCurve.isNotEmpty() -> {
                val tailStats = outgoingAudio.windowEnergyStats(tailStart, rawOutEnd)
                val searchMin = maxOf(rawOutEnd / 2L, rawOutEnd - maxOf(28_000L, fadeDurationMs * 3L)).coerceAtMost(tailStart)
                val soft = outgoingAudio.findSoftTransitionStartMs(
                    minMs = searchMin,
                    maxMs = tailStart,
                    windowMs = fadeDurationMs,
                    preferLate = true,
                    preferDownbeat = onBeat,
                )
                val softStats = soft?.let { outgoingAudio.windowEnergyStats(it, it + fadeDurationMs) }
                // Si el final natural ya es suave, se conserva; si el final pega estribillo intenso, salimos en el valle previo.
                if (soft != null && tailStats != null && softStats != null && tailStats.first >= 50.0 && softStats.first <= tailStats.first - 14.0) {
                    soft
                } else {
                    tailStart
                }
            }
            else -> tailStart
        }
        val outgoingFadeStartMs = (if (outgoingAudio != null && fadeDurationMs > 0L) {
            val window = minOf(650L, fadeDurationMs / 4L)
            val snapped = outgoingAudio.snapBeatMs(
                targetMs = rawFadeStart,
                minMs = (rawFadeStart - window).coerceAtLeast(0L),
                maxMs = (rawFadeStart + window).coerceAtMost((rawOutEnd - minOf(fadeDurationMs, 1_500L)).coerceAtLeast(0L)),
                preferDownbeat = onBeat,
            )
            val baseStats = outgoingAudio.windowEnergyStats(rawFadeStart, rawFadeStart + fadeDurationMs)
            val snapStats = outgoingAudio.windowEnergyStats(snapped, snapped + fadeDurationMs)
            if (baseStats != null && snapStats != null && snapStats.first > baseStats.first + 12.0) rawFadeStart else snapped
        } else {
            rawFadeStart
        }).let { start ->
            // Con letra: si la voz de la que se va acaba poco antes del fundido, se empieza justo al acabar la voz
            // (el fundido se alarga hasta el doble): la cola instrumental se mezcla con la entrada de la otra.
            val vocalEnd = outgoingVocalEndMs
            if (outgoingAdhd == null && vocalEnd != null && fadeDurationMs > 0L &&
                vocalEnd < start && vocalEnd + VOCAL_TAIL_MS >= rawOutEnd - fadeDurationMs * 2L
            ) {
                val afterVoice = (vocalEnd + VOCAL_TAIL_MS).coerceAtMost(start)
                val snapped = outgoingAudio?.snapBeatMs(afterVoice, afterVoice, (afterVoice + 1_000L).coerceAtMost(start), preferDownbeat = true) ?: afterVoice
                fadeDurationMs = (rawOutEnd - snapped).coerceAtLeast(fadeDurationMs)
                snapped
            } else {
                start
            }
        }

        // Punto de entrada en entrante: en ADHD entra en la respiración suave previa al drop; en normal busca pocket suave tras intro.
        val incomingStartMs = when {
            incomingAdhd != null -> {
                if (incomingAudio != null) {
                    incomingAudio.snapBeatMs(
                        targetMs = incomingAdhd.startMs,
                        minMs = maxOf(0L, incomingAdhd.startMs - 500L),
                        maxMs = minOf(incomingAdhd.endMs - AdhdPlanner.MIN_CLIP_MS / 2L, incomingAdhd.startMs + 500L).coerceAtLeast(incomingAdhd.startMs),
                        preferDownbeat = true,
                    )
                } else {
                    incomingAdhd.startMs
                }
            }
            incomingFromStart -> {
                val silence = incomingAudio?.introEndMs?.takeIf { it <= MAX_SKIPPED_INTRO_MS } ?: 0L
                if (onBeat && incomingAudio != null && silence > 0L) {
                    incomingAudio.snapBeatMs(silence, silence, silence + 1_000L, preferDownbeat = true)
                } else silence
            }
            incomingAudio != null -> {
                val minStart = incomingAudio.introEndMs.coerceAtMost((incomingAudio.durationMs / 4L).coerceAtLeast(0L))
                val maxSoftEntry = minOf(
                    (incomingAudio.durationMs / 3L).coerceAtLeast(minStart),
                    minStart + 6_000L,
                )
                val softEntry = if (incomingAudio.energyCurve.isNotEmpty() && maxSoftEntry > minStart + 1_000L) {
                    incomingAudio.findSoftTransitionStartMs(
                        minMs = minStart,
                        maxMs = maxSoftEntry,
                        windowMs = maxOf(2_000L, fadeDurationMs),
                        preferLate = false,
                        preferDownbeat = onBeat,
                    )
                } else null
                val entryTarget = softEntry ?: minStart
                if (onBeat) {
                    incomingAudio.snapBeatMs(
                        targetMs = entryTarget,
                        minMs = entryTarget,
                        maxMs = entryTarget + 2_500L,
                        preferDownbeat = true,
                    )
                } else {
                    entryTarget
                }
            }
            else -> 0L
        }

        // Igualación suave de volumen por RMS real.
        val incomingGainMatch = if (outgoingAudio != null && incomingAudio != null) {
            val deltaDb = (outgoingAudio.rmsDb - incomingAudio.rmsDb).coerceIn(-4.0, 4.0)
            10.0.pow(deltaDb / 20.0).toFloat().coerceIn(0.65f, 1.45f)
        } else 1f

        val nudge = if (style == DjTransitionStyle.BeatMatchLongMix && bpmInfo != null) {
            bpmInfo.first.coerceIn(0.94, 1.06)
        } else 1.0

        // Si la entrante empieza a cantar mientras la otra aún canta, las dos voces se pisan: se acorta el fundido para
        // que la nueva voz llegue cuando la otra ya está casi apagada (60 % del fundido).
        val inVoice = incomingVocalStartMs?.let { it - incomingStartMs }
        val outSingsAtEnd = outgoingVocalEndMs != null && outgoingVocalEndMs > outgoingFadeStartMs + fadeDurationMs / 3L
        val fadeStartManual = if (inVoice != null && inVoice > 0L && outSingsAtEnd && inVoice < fadeDurationMs * 6L / 10L) {
            val shorter = (inVoice * 10L / 6L).coerceAtLeast(minOf(fadeDurationMs, MIN_VOICE_FADE_MS))
            val delta = fadeDurationMs - shorter
            fadeDurationMs = shorter
            outgoingFadeStartMs + delta
        } else outgoingFadeStartMs

        // Con el DJ decidiendo: se prueban varios puntos de salida (en compases) y duraciones (en beats) y se queda el
        // que mejor suena: sin dos voces a la vez, saliendo cuando acaba una parte y no encima de un estribillo.
        val fadeStartFinal = if (auto && safeOutDur > 0L) {
            val pick = chooseWindow(
                style = style,
                outAudio = outgoingAudio,
                inAudio = incomingAudio,
                outDurMs = safeOutDur,
                outAdhd = outgoingAdhd,
                incomingStartMs = incomingStartMs,
                outVoice = Voice.of(outgoingVocals, untilMs = outgoingVocalEndMs),
                inVoice = Voice.of(incomingVocals, fromMs = incomingVocalStartMs),
                outSpeed = if (nudge != 1.0) (1.0 / nudge).coerceIn(0.94, 1.06) else 1.0,
            )
            fadeDurationMs = pick.second
            pick.first
        } else fadeStartManual

        // Bucle: arranca en el tiempo fuerte más cercano al inicio del fundido (y el fundido con él), dura un compás de
        // ese trozo de la canción (dos si el compás es muy corto) y se repite hasta el final de la mezcla.
        var loopStartMs = -1L
        var loopBeats = 0
        var loopBeatMs = 0.0
        var fadeStartLooped = fadeStartFinal
        if (style != DjTransitionStyle.EchoOutFilterSweep && outgoingAudio != null && fadeDurationMs >= MIN_LOOP_FADE_MS) {
            val period = outgoingAudio.localPeriodMs(fadeStartFinal)
            if (period != null) {
                val reach = (period * 2.0).roundToLong()
                val bar = outgoingAudio.snapBeatMs(
                    targetMs = fadeStartFinal,
                    minMs = (fadeStartFinal - reach).coerceAtLeast(0L),
                    maxMs = fadeStartFinal + reach / 2L,
                    preferDownbeat = true,
                )
                val beat = outgoingAudio.nearestBeatMs(bar)?.roundToLong()
                if (beat != null && beat >= 0L && beat + fadeDurationMs <= safeOutDur - LOOP_END_MARGIN_MS) {
                    loopStartMs = beat
                    loopBeats = if (period * 4.0 < 1_500.0) 8 else 4
                    loopBeatMs = period
                    fadeStartLooped = beat
                }
            }
        }
        if (style == DjTransitionStyle.LoopRollOut && loopBeats == 0) style = DjTransitionStyle.EchoOutFilterSweep
        // En el bucle con roll la nueva entra en la segunda mitad: se adelanta su arranque para que su punto de entrada
        // suene justo cuando se la empieza a oír.
        val incomingStartFinal = if (style == DjTransitionStyle.LoopRollOut && incomingStartMs > 0L) {
            (incomingStartMs - (fadeDurationMs * LOOP_ROLL_IN_AT).toLong()).coerceAtLeast(0L)
        } else incomingStartMs

        // Eco a tempo exacto (un beat, o su doble/mitad para que quepa): con un retardo que no cae en el beat el eco
        // suena a la canción duplicada y desfasada. Sin pulso fiable, sin eco.
        val beatMs = outgoingAudio?.takeIf { it.bpm != null && it.bpmConfidence >= AudioMetrics.BEAT_MATCH_CONFIDENCE }?.beatPeriodMs
        val echoDelayMs = beatMs?.let {
            var d = it
            while (d < 240.0) d *= 2.0
            while (d > 950.0) d /= 2.0
            d.roundToLong()
        }
        val bigJump = tandaChanged || (bpmDiffPct != null && bpmDiffPct > ECHO_OUT_MIN_PCT)
        val echoWet = if (style == DjTransitionStyle.EchoOutFilterSweep && bigJump && echoDelayMs != null) 0.32f else 0f
        val reason = when (style) {
            DjTransitionStyle.BeatMatchLongMix -> "beats cuadrados ±${"%.1f".format(bpmDiffPct ?: 0.0)}% BPM"
            DjTransitionStyle.EchoOutFilterSweep -> buildString {
                append("filtro")
                if (echoWet > 0f) append(" + eco $echoDelayMs ms")
                append(
                    when {
                        tandaChanged -> " (cambio de tanda)"
                        genreJump -> " ($outgoingGenre → $incomingGenre)"
                        bpmDiffPct != null -> " (${"%.0f".format(bpmDiffPct)}% ΔBPM)"
                        outgoingAudio == null || incomingAudio == null -> " (sin analizar)"
                        else -> " (pulso dudoso)"
                    },
                )
            }
            DjTransitionStyle.SmoothEqualPower -> "mezcla suave"
            DjTransitionStyle.LoopRollOut -> buildString {
                append("bucle de $loopBeats beats + roll")
                append(
                    when {
                        bpmDiffPct != null -> " (${"%.0f".format(bpmDiffPct)}% ΔBPM)"
                        incomingAudio == null -> " (la otra sin analizar)"
                        else -> " (la otra sin pulso claro)"
                    },
                )
            }
            DjTransitionStyle.LyricLoopCut -> "bucle vocal"
            DjTransitionStyle.EnergyDropCut -> "corte en drop"
        }.let { if (style == DjTransitionStyle.BeatMatchLongMix && loopBeats > 0) "$it, bucle de $loopBeats beats" else it }

        return DjTransitionPlan(
            style = style,
            fadeDurationMs = fadeDurationMs,
            incomingTempoNudge = nudge,
            outgoingSpeed = if (nudge != 1.0) (1.0 / nudge).coerceIn(0.94, 1.06) else 1.0,
            bassSwapAt = when (style) {
                DjTransitionStyle.BeatMatchLongMix -> 0.5f
                DjTransitionStyle.LoopRollOut -> 0.7f
                else -> 0.55f
            },
            // La fase solo se cuadra a tempo directo (no a mitad/doble, donde un beat de una son dos de la otra).
            phaseLock = style == DjTransitionStyle.BeatMatchLongMix && outBpm != null && inBpm != null &&
                abs(outBpm / inBpm - 1.0) * 100.0 <= BEAT_MATCH_MAX_PCT,
            outgoingAudio = outgoingAudio,
            incomingAudio = incomingAudio,
            outgoingFadeStartMs = fadeStartLooped,
            incomingStartMs = incomingStartFinal,
            incomingGainMatch = incomingGainMatch,
            useBassSwap = true,
            useHighPassSweep = style == DjTransitionStyle.EchoOutFilterSweep || style == DjTransitionStyle.LoopRollOut,
            highPassStartHz = 30f,
            highPassEndHz = 1_800f,
            echoOutWet = echoWet,
            echoDelayMs = echoDelayMs ?: 0L,
            echoFeedback = 0.32f,
            bpmDiffPercent = bpmDiffPct,
            reason = reason,
            loopStartMs = loopStartMs,
            loopBeats = loopBeats,
            loopBeatMs = loopBeatMs,
        )
    }

    private fun fromBridgeSpec(
        spec: DjBridgeSpec,
        outgoingAudio: AudioMetrics?,
        incomingAudio: AudioMetrics?,
    ): DjTransitionPlan {
        val mappedStyle = when (spec.style) {
            DjBridgeStyle.LyricLoopCut -> DjTransitionStyle.LyricLoopCut
            DjBridgeStyle.BeatMatchLongMix -> DjTransitionStyle.BeatMatchLongMix
            DjBridgeStyle.EnergyDropCut -> DjTransitionStyle.EnergyDropCut
            DjBridgeStyle.FilterEcho -> DjTransitionStyle.EchoOutFilterSweep
            DjBridgeStyle.SmartFade -> DjTransitionStyle.SmoothEqualPower
        }
        val bpmRel = if (spec.bpmA > 0.0 && spec.bpmB > 0.0) DjHarmonic.bpmRelation(spec.bpmA, spec.bpmB).relDiffPct else null
        return DjTransitionPlan(
            style = mappedStyle,
            fadeDurationMs = spec.transitionDurationMs.coerceAtLeast(350L),
            incomingTempoNudge = spec.tempoMatchRatio.coerceIn(0.75, 1.35),
            outgoingSpeed = if (spec.tempoMatchRatio != 1.0) (1.0 / spec.tempoMatchRatio).coerceIn(0.80, 1.25) else 1.0,
            bassSwapAt = spec.bassSwapFrac,
            phaseLock = spec.style == DjBridgeStyle.BeatMatchLongMix,
            outgoingAudio = outgoingAudio,
            incomingAudio = incomingAudio,
            outgoingFadeStartMs = spec.cueOutStartMs,
            incomingStartMs = spec.entryInMs,
            incomingGainMatch = spec.incomingGainMatch,
            useBassSwap = spec.style != DjBridgeStyle.EnergyDropCut && spec.style != DjBridgeStyle.LyricLoopCut,
            useHighPassSweep = spec.style == DjBridgeStyle.FilterEcho || spec.style == DjBridgeStyle.EnergyDropCut,
            highPassStartHz = spec.cutoffStartHz,
            highPassEndHz = spec.cutoffEndHz,
            echoOutWet = if (spec.style == DjBridgeStyle.FilterEcho && spec.echoDelayMs > 1.0) 0.28f else 0f,
            echoDelayMs = spec.echoDelayMs.roundToLong(),
            echoFeedback = 0.35f,
            bpmDiffPercent = bpmRel,
            reason = spec.reason,
            loopStartMs = spec.lyricLoop?.outLoopStartMs ?: -1L,
            loopBeats = spec.lyricLoop?.repeats ?: 0,
            loopBeatMs = spec.lyricLoop?.beatMs ?: 0.0,
            bridgeSpec = spec,
        )
    }

    /** Dónde canta una canción (ms), sacado de su letra. */
    private class Voice(private val spans: List<LongRange>) {
        fun at(t: Long): Boolean = spans.any { t in it }
        fun overlapMs(from: Long, to: Long): Long = spans.sumOf { r -> (minOf(r.last, to) - maxOf(r.first, from)).coerceAtLeast(0L) }

        companion object {
            /** null si no se sabe nada de la voz. */
            fun of(sections: List<SongSection>, untilMs: Long? = null, fromMs: Long? = null): Voice? = when {
                sections.isNotEmpty() -> Voice(sections.map { it.startMs..it.endMs })
                untilMs != null -> Voice(listOf(0L..untilMs))
                fromMs != null -> Voice(listOf(fromMs..Long.MAX_VALUE / 4))
                else -> null
            }
        }
    }

    /** Lo que se busca que dure cada estilo (ms) cuando decide el DJ. */
    private fun targetMs(style: DjTransitionStyle): Long = when (style) {
        DjTransitionStyle.BeatMatchLongMix -> 12_000L
        else -> 8_000L
    }

    /** Duraciones posibles: en beats de la mezcla (16 o 32 cuadrando; 8 o 16 con bucle y roll) o en segundos sin pulso. */
    private fun durationsMs(style: DjTransitionStyle, mixBeatMs: Double?, cap: Long): List<Long> {
        fun beats(options: List<Int>, lo: Long, hi: Long): List<Long> {
            val b = mixBeatMs ?: return emptyList()
            val all = options.map { (it * b).roundToLong() }
            return all.filter { it in lo..hi }.ifEmpty { listOf(all.first().coerceIn(lo, hi)) }
        }
        val list = when (style) {
            DjTransitionStyle.BeatMatchLongMix -> beats(listOf(16, 32), 7_000L, 24_000L)
            DjTransitionStyle.LoopRollOut -> beats(listOf(8, 16), 4_500L, 13_000L)
            else -> emptyList()
        }.ifEmpty { listOf(6_000L, 8_000L, 10_000L) }
        return list.map { it.coerceAtMost(cap.coerceAtLeast(MIN_AUTO_FADE_MS)) }.distinct()
    }

    /**
     * Elige dónde empieza la mezcla en la saliente y cuánto dura: compases cerca del final (o del final del trozo en DJ
     * rápido) y duraciones en beats. Se puntúa:
     * - dos voces a la vez mientras las dos se oyen (salvo al final, cuando la que se va ya está casi apagada): lo peor;
     * - un bucle que repite un trozo cantado, o cortar a la saliente a mitad de una frase;
     * - salir encima de mucha energía o justo cuando empieza a subir (un estribillo) en vez de cuando acaba una parte;
     * - alejarse del final natural (o del punto que eligió el trozo) y de la duración que pide el estilo.
     */
    private fun chooseWindow(
        style: DjTransitionStyle,
        outAudio: AudioMetrics?,
        inAudio: AudioMetrics?,
        outDurMs: Long,
        outAdhd: AdhdSegment?,
        incomingStartMs: Long,
        outVoice: Voice?,
        inVoice: Voice?,
        outSpeed: Double,
    ): Pair<Long, Long> {
        val reliable = outAudio?.beatReliable == true
        val outBeat = outAudio?.beatPeriodMs?.takeIf { reliable }
        // Un beat de la mezcla en tiempo real: con los beats cuadrados manda el tempo de la entrante.
        val mixBeat = when (style) {
            DjTransitionStyle.BeatMatchLongMix -> inAudio?.beatPeriodMs ?: outBeat
            DjTransitionStyle.LoopRollOut -> outBeat
            else -> null
        }
        val cap = if (outAdhd != null) outAdhd.clipDurationMs / 3L else outDurMs / 3L
        val durations = durationsMs(style, mixBeat, cap)
        val target = targetMs(style)
        val swapAt = when (style) {
            DjTransitionStyle.BeatMatchLongMix -> 0.5f
            DjTransitionStyle.LoopRollOut -> 0.7f
            else -> 0.55f
        }
        // Fin natural: el del trozo (DJ rápido) o antes del silencio final.
        val naturalEnd = when {
            outAdhd != null -> outAdhd.endMs
            outAudio != null && outAudio.outroStartMs > target * 2L -> minOf(outDurMs, outAudio.outroStartMs)
            else -> outDurMs
        }
        val hardEnd = if (outAdhd != null) {
            minOf(outDurMs, outAudio?.outroStartMs?.takeIf { it > outAdhd.endMs } ?: outDurMs)
        } else naturalEnd

        var bestT = (naturalEnd - target).coerceAtLeast(0L)
        var bestD = durations.minBy { abs(it - target) }
        var bestCost = Double.POSITIVE_INFINITY
        for (d in durations) {
            val songSpan = (d * outSpeed).roundToLong()
            val latest = (hardEnd - songSpan - LOOP_END_MARGIN_MS).coerceAtLeast(0L)
            val natural = (naturalEnd - songSpan).coerceIn(0L, latest).let { n ->
                // Con pulso, en el último tiempo fuerte que cabe.
                if (outBeat != null && outAudio != null) {
                    val bar = (outBeat * 4.0).roundToLong()
                    val snapped = outAudio.snapBeatMs(n, (n - bar).coerceAtLeast(0L), n, preferDownbeat = true)
                    (outAudio.nearestBeatMs(snapped)?.roundToLong() ?: snapped).coerceAtMost(n)
                } else n
            }
            val (lo, hi) = if (outAdhd != null) {
                val anchor = (outAdhd.endMs - songSpan).coerceAtLeast(0L)
                maxOf(outAdhd.startMs + AdhdPlanner.MIN_CLIP_MS - d, anchor - ADHD_REACH_MS).coerceAtMost(latest) to
                    minOf(latest, anchor + ADHD_REACH_MS)
            } else {
                maxOf(naturalEnd / 2L, naturalEnd - FULL_REACH_MS).coerceAtMost(latest) to latest
            }
            val candidates = ArrayList<Long>()
            if (outBeat != null && outAudio != null) {
                val bar = outBeat * 4.0
                var t = outAudio.snapBeatMs(lo, lo, (lo + bar).roundToLong(), preferDownbeat = true).toDouble()
                while (t <= hi) {
                    candidates += (outAudio.nearestBeatMs(t.roundToLong()) ?: t).roundToLong()
                    t += bar
                }
            } else {
                var t = lo
                while (t <= hi) {
                    candidates += t
                    t += 500L
                }
            }
            candidates += natural
            val loopLen = outBeat?.let { if (it * 4.0 < 1_500.0) it * 8.0 else it * 4.0 }?.roundToLong()
            val looped = loopLen != null && style != DjTransitionStyle.EchoOutFilterSweep && d >= MIN_LOOP_FADE_MS
            val inStart = if (style == DjTransitionStyle.LoopRollOut) {
                (incomingStartMs - (d * LOOP_ROLL_IN_AT).toLong()).coerceAtLeast(0L)
            } else incomingStartMs
            for (t in candidates.distinct()) {
                if (t < 0L || t + songSpan > outDurMs) continue
                var cost = 0.0
                val loopSings = looped && outVoice != null && outVoice.overlapMs(t, t + loopLen!!) > LOOP_VOICE_MS
                // Voces: lo que se oye de cada una a cada paso de la mezcla.
                if (outVoice != null && inVoice != null) {
                    var clash = 0.0
                    var p = 0f
                    while (p <= CLASH_UNTIL) {
                        val gOut = DjGains.outgoing(style, p, swapAt, echo = false)
                        val gIn = DjGains.incoming(style, p, swapAt)
                        if (gOut >= AUDIBLE && gIn >= AUDIBLE) {
                            val songAt = (p * d * outSpeed).toLong()
                            val outSings = if (looped && songAt >= loopLen!!) loopSings else outVoice.at(t + songAt)
                            if (outSings && inVoice.at(inStart + (p * d).toLong())) clash += CLASH_STEP * d
                        }
                        p += CLASH_STEP
                    }
                    cost += clash / 1000.0 * CLASH_COST
                }
                if (outVoice != null) {
                    if (loopSings) cost += LOOP_VOICE_COST
                    if (outVoice.at(t)) cost += MID_PHRASE_COST
                }
                // Energía: salir de una parte tranquila o justo cuando acaba una fuerte; no cuando empieza a subir.
                if (outAudio != null && outAudio.energyCurve.isNotEmpty()) {
                    outAudio.windowEnergyStats(t, t + songSpan)?.let { (avg, max) ->
                        cost += avg / 20.0
                        if (max >= 80 && avg >= 62.0) cost += 1.5
                    }
                    val before = outAudio.windowEnergyStats((t - 8_000L).coerceAtLeast(0L), t)?.first
                    val after = outAudio.windowEnergyStats(t, t + 6_000L)?.first
                    if (before != null && after != null) cost -= ((before - after) / 12.0).coerceIn(-2.0, 2.0)
                }
                // Cerca del final natural (o de donde acaba el trozo) y de la duración del estilo.
                val anchor = if (outAdhd != null) (outAdhd.endMs - songSpan) else natural
                cost += abs(anchor - t) / 1000.0 * (if (outAdhd != null) 0.12 else 0.08)
                cost += abs(d - target) / 1000.0 * 0.25
                if (cost < bestCost) {
                    bestCost = cost
                    bestT = t
                    bestD = d
                }
            }
        }
        return bestT to bestD
    }
}
