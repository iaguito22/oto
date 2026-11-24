package io.github.iaguito22.oto.dj

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import org.json.JSONObject

/**
 * Reglas del cuadre de tempo: cuánto se puede estirar la entrante (B) para ponerla al tempo de la saliente (A), cuándo
 * hay bombo suficiente para cuadrar beats y cuánto tarda B en volver a su velocidad cuando ya suena sola.
 */
object DjTempoMatch {
    /** Solo la última preparada se revisa por tempo; el prefijo conserva canciones y puentes. */
    fun protectedUpcomingIds(ids: List<String>): Set<String> = ids.dropLast(1).toSet()

    /** Estiramiento máximo de B si canta durante el cruce: por encima se nota en la voz. */
    const val MAX_STRETCH_PCT = 6.0

    /** Estiramiento máximo si B entra sin voz durante el cruce (el estiramiento apenas se oye en lo instrumental). */
    const val MAX_STRETCH_INSTRUMENTAL_PCT = 8.0

    /** Por debajo de esta ocupación vocal en el cruce, B cuenta como instrumental. */
    const val INSTRUMENTAL_VOCALS = 0.15

    /** Bombo en el pulso mínimo, de media en los compases del cruce y en las dos canciones, para cuadrar beats. */
    const val MIN_KICK_ON_BEAT = 0.35

    /** Por debajo de esta armonía ([DjHarmonic.harmonicCompatibility]) el puente no cuadra beats: tonos que chocan. */
    const val MIN_BEAT_MATCH_HARMONY = 0.45

    /** Velocidad mínima y máxima de B (coherente con [MAX_STRETCH_INSTRUMENTAL_PCT]). */
    const val MIN_RATIO = 1.0 - MAX_STRETCH_INSTRUMENTAL_PCT / 100.0
    const val MAX_RATIO = 1.0 + MAX_STRETCH_INSTRUMENTAL_PCT / 100.0

    /**
     * Por debajo de esto B no se estira (va a 1×). Mismo umbral en planificador, renderizador y `DjDsp.timeStretchStereo`:
     * con 0,1 % la deriva en 14 s de cruce es de ~14 ms, inaudible.
     */
    const val STRETCH_EPS = 0.001

    fun isStretched(ratio: Double): Boolean = abs(ratio - 1.0) > STRETCH_EPS

    /** Vuelta mínima que hace el renderizador aunque apenas haya cambio (la de antes). */
    const val MIN_RENDERED_GLIDE_MS = 1_600L

    /** Lo que el renderizador añade tras la vuelta: 200 ms de cola seca y hasta 800 ms de corrección de fase de B. */
    private const val TAIL_AND_SHIFT_MS = 1_000L

    /** Cuánto cambia la velocidad de B, en %. */
    fun stretchPct(ratio: Double): Double = abs(ratio - 1.0) * 100.0

    /** Estiramiento permitido según cuánto canta B durante el cruce (0..1). */
    fun maxStretchPct(incomingVocals: Double): Double =
        if (incomingVocals <= INSTRUMENTAL_VOCALS) MAX_STRETCH_INSTRUMENTAL_PCT else MAX_STRETCH_PCT

    fun clampRatio(ratio: Double): Double = ratio.coerceIn(MIN_RATIO, MAX_RATIO)

    /** Beats que dura la vuelta de B a 1×: cuanto más cambio, más larga, para que no se note el acelerón o el frenazo. */
    fun returnGlideBeats(ratio: Double): Int {
        val pct = stretchPct(ratio)
        return when {
            pct <= 2.0 -> 8
            pct <= 4.5 -> 12
            else -> 16
        }
    }

    /**
     * Duración (ms de salida) de la vuelta de B a su velocidad, con B ya sola. [bpm] es el tempo al que suena B durante
     * el cruce (el de A). 0 si no hay cambio de velocidad.
     */
    fun returnGlideMs(bpm: Double, ratio: Double): Long {
        if (!isStretched(ratio)) return 0L
        val beatMs = if (bpm > 20.0) 60_000.0 / bpm else 500.0
        return (returnGlideBeats(ratio) * beatMs).roundToLong().coerceIn(3_000L, 10_000L)
    }

    /** Audio de B (ms de la canción) que consume la vuelta: con una rampa simétrica, la velocidad media es (r + 1) / 2. */
    fun returnGlideSourceMs(bpm: Double, ratio: Double): Long =
        (returnGlideMs(bpm, ratio) * (ratio + 1.0) * 0.5).roundToLong()

    /** Vuelta que hace de verdad el renderizador (con su mínimo), en ms de salida. */
    fun renderedGlideMs(bpm: Double, ratio: Double): Long = max(returnGlideMs(bpm, ratio), MIN_RENDERED_GLIDE_MS)

    /**
     * Audio de B (ms de canción) que el puente gasta después del cruce: vuelta + cola + corrección de fase. El
     * planificador lo usa para saber cuánto de B hay que decodificar (`dryBResumeMs`); sale de la misma función que usa
     * el renderizador para que no se descuadren.
     */
    fun bridgeTailSourceMs(bpm: Double, ratio: Double): Long =
        (renderedGlideMs(bpm, ratio) * (ratio + 1.0) * 0.5).roundToLong() + TAIL_AND_SHIFT_MS

    /**
     * Audio de B (ms de canción, desde la entrada) que gasta un cruce a tempo de [crossMs] antes del tramo seco: con B
     * estirada, cruce × ratio + [bridgeTailSourceMs]; a 1× (solo fase), el cruce más la corrección de fase máxima. Es la
     * cuenta del cuadre de beats y del fundido suave a tempo; el planificador la usa para `dryBResumeMs` y el
     * renderizador para saber si el tramo de B alcanza.
     */
    fun lockedCrossSourceMs(crossMs: Long, bpm: Double, ratio: Double): Long =
        if (isStretched(ratio)) (crossMs * ratio).roundToLong() + bridgeTailSourceMs(bpm, ratio)
        else crossMs + MAX_PHASE_SHIFT_MS

    /** Lo más que el renderizador retrasa la entrada de B para cuadrar la fase (`kickShiftMs`). */
    const val MAX_PHASE_SHIFT_MS = 800L

    /**
     * Desfase (ms, con signo: >0 = la entrante va adelantada) entre los beats de las dos en vivo. [outOff]/[inOff] son
     * la posición dentro de su beat (−0,5..0,5, `AudioMetrics.beatOffset`) y los periodos van en ms de reproducción (ya
     * con la velocidad de cada una). Se compara sobre el periodo más corto: a mitad o doble tempo los golpes de la lenta
     * caen sobre uno de cada dos de la rápida, y con el mismo tempo da lo mismo que antes.
     */
    fun phaseErrorMs(outOff: Double, outPeriodMs: Double, inOff: Double, inPeriodMs: Double): Double {
        val p = min(outPeriodMs, inPeriodMs).coerceAtLeast(1.0)
        var err = (inOff * inPeriodMs - outOff * outPeriodMs) % p
        if (err > p / 2) err -= p
        if (err < -p / 2) err += p
        return err
    }

    /**
     * Si la mezcla en vivo (sin puente) corrige la fase: cuadre de beats o eco alineado por el plan, aunque B vaya a 1×
     * (mismo tempo o mitad/doble exactos). Hace falta el análisis de las dos para medir la fase.
     */
    fun livePhaseLock(plan: DjTransitionPlan?): Boolean =
        plan != null && plan.phaseLock && plan.outgoingAudio != null && plan.incomingAudio != null &&
            (plan.style == DjTransitionStyle.BeatMatchLongMix || plan.style == DjTransitionStyle.EchoOutFilterSweep)

    /**
     * Bombo en el pulso medio de los compases que tocan `[fromMs, toMs)`, ponderado por lo que tocan. Lo no medido
     * (-1: compases sintéticos del análisis a medias o sin análisis) cuenta como 0: sin prueba de bombo no se cuadra.
     */
    fun kickOnBeat(bars: List<BarFeature>, fromMs: Long, toMs: Long): Double {
        if (bars.isEmpty() || toMs <= fromMs) return 0.0
        var sum = 0.0
        var weight = 0.0
        for (b in bars) {
            val overlap = (min(b.endMs, toMs) - max(b.startMs, fromMs)).coerceAtLeast(0L)
            if (overlap <= 0L) continue
            val v = b.kickOnBeat.coerceAtLeast(0.0)
            sum += v * overlap
            weight += overlap
        }
        return if (weight > 0.0) sum / weight else 0.0
    }

    // ---- Elegir canciones que se puedan cuadrar (lo usa el DJ al ordenar la cola) ----

    /** Por debajo de esta parte de compases con bombo al pulso, la canción casi no tiene bombo: no se cuadra. */
    const val MIN_KICK_SHARE = 0.4

    /** Compases con bombo medido necesarios para fiarse de [TempoProfile.kickShare]. */
    const val MIN_KICK_BARS = 8

    /** Con la entrante a medias, compases medidos para dar por hecho que no tiene bombo (más que una intro). */
    const val MIN_KICK_BARS_PARTIAL = 16

    /**
     * Lo que hace falta del análisis para saber si dos canciones se pueden cuadrar: tempo, fiabilidad, rejilla y
     * bombo. Va aparte de `AudioMetrics` para guardarlo en un índice ligero ([DjTempoIndex]) que sobrevive a las
     * subidas de `AudioMetrics.VERSION` (sin él, tras cada subida el DJ elige sin saber el tempo de nada).
     */
    data class TempoProfile(
        val trackId: String,
        val bpm: Double,
        val confidence: Double,
        val gridBeats: Int,
        val beatStrength: Double,
        /** Parte de los compases medidos con bombo al pulso (≥ [MIN_KICK_ON_BEAT]); −1 si no se midió. */
        val kickShare: Double,
        /** Compases con bombo medido (para saber cuánto fiarse de [kickShare]). */
        val kickBars: Int,
        val wholeSong: Boolean,
        val analyzedMs: Long,
        val analyzedAt: Long,
        /** Cuándo se midió el bombo (puede venir de otro análisis que el tempo: ver `DjTempoIndex`). */
        val kickAt: Long = analyzedAt,
        /**
         * Si el bombo salió de un análisis de la canción entera. Va aparte de [wholeSong] (el tempo puede ser de un
         * análisis entero viejo y el bombo de uno nuevo a medias): para juzgar el bombo cuenta este.
         */
        val kickWhole: Boolean = wholeSong,
    ) {
        val kickKnown: Boolean get() = kickShare >= 0.0 && kickBars >= MIN_KICK_BARS

        fun toJson(): JSONObject = JSONObject().apply {
            put("b", (bpm * 100.0).roundToLong() / 100.0)
            put("c", (confidence * 1000.0).roundToLong() / 1000.0)
            put("g", gridBeats)
            put("s", (beatStrength * 1000.0).roundToLong() / 1000.0)
            put("k", (kickShare * 1000.0).roundToLong() / 1000.0)
            put("kn", kickBars)
            put("w", wholeSong)
            put("am", analyzedMs)
            put("t", analyzedAt)
            put("ka", kickAt)
            put("kw", kickWhole)
        }

        /**
         * Si el bombo de este perfil vale más que el de [other]: medido antes que no; luego, el medido sobre más
         * compases (más canción oída: una intro sin bombo no tapa lo que viene después); a igualdad, el más reciente.
         */
        fun kickBetterThan(other: TempoProfile): Boolean = when {
            kickKnown != other.kickKnown -> kickKnown
            kickWhole != other.kickWhole -> kickWhole
            kickBars != other.kickBars -> kickBars > other.kickBars
            else -> kickAt >= other.kickAt
        }

        /**
         * Si este perfil sabe más que [other]: entera antes que a medias; luego, más audio decodificado (la curva en
         * vivo, con 0, no pisa un análisis del audio); a igualdad, el más reciente (un algoritmo nuevo).
         */
        fun betterThan(other: TempoProfile?): Boolean = when {
            other == null -> true
            wholeSong != other.wholeSong -> wholeSong
            !wholeSong && analyzedMs != other.analyzedMs -> analyzedMs > other.analyzedMs
            else -> analyzedAt >= other.analyzedAt
        }

        companion object {
            fun fromJson(id: String, o: JSONObject): TempoProfile? {
                val bpm = o.optDouble("b", 0.0).takeIf { it > 20.0 } ?: 0.0
                return TempoProfile(
                    trackId = id,
                    bpm = bpm,
                    confidence = o.optDouble("c", 0.0),
                    gridBeats = o.optInt("g", 0),
                    beatStrength = o.optDouble("s", 0.0),
                    kickShare = o.optDouble("k", -1.0),
                    kickBars = o.optInt("kn", 0),
                    wholeSong = o.optBoolean("w", false),
                    analyzedMs = o.optLong("am", 0L),
                    analyzedAt = o.optLong("t", 0L),
                    kickAt = o.optLong("ka", o.optLong("t", 0L)),
                    kickWhole = o.optBoolean("kw", o.optBoolean("w", false)),
                )
            }

            /**
             * Del JSON de un análisis de una versión anterior (que `AudioMetrics.fromJson` descarta): el tempo y la
             * rejilla siguen valiendo para elegir; el bombo, si no estaba medido, queda desconocido.
             */
            fun fromAnalysisJson(o: JSONObject): TempoProfile? {
                val id = o.optString("id").ifEmpty { return null }
                if (o.optInt("v", 1) < 3) return null // antes de la v3 el BPM fallaba mucho
                val bpm = o.optDouble("bpm", 0.0)
                if (!(bpm > 20.0)) return null
                val grid = o.optString("bg", "").split(',').count { it.isNotBlank() }
                var measured = 0
                var withKick = 0
                o.optJSONArray("bars")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val kb = arr.optJSONObject(i)?.optDouble("kb", -1.0) ?: -1.0
                        if (kb >= 0.0) {
                            measured++
                            if (kb >= MIN_KICK_ON_BEAT) withKick++
                        }
                    }
                }
                val dur = o.optLong("dur", 0L)
                val am = o.optLong("am", 0L)
                return TempoProfile(
                    trackId = id,
                    bpm = bpm,
                    confidence = o.optDouble("conf", 0.0),
                    gridBeats = grid,
                    beatStrength = o.optDouble("bs", 0.5),
                    kickShare = if (measured > 0) withKick.toDouble() / measured else -1.0,
                    kickBars = measured,
                    // Sin duración conocida no se sabe si está entera.
                    wholeSong = am > 0L && dur > 0L && am >= dur - 8_000L,
                    analyzedMs = am,
                    analyzedAt = o.optLong("at", 0L),
                )
            }
        }
    }

    /**
     * El perfil de tempo de un análisis. Sin BPM también hay perfil (bpm 0): un análisis entero sin pulso tiene que
     * poder corregir en el índice un BPM viejo, y dice «sin pulso» en vez de «sin medir».
     */
    fun profileOf(m: AudioMetrics): TempoProfile? {
        val bpm = m.bpm?.takeIf { it > 20.0 } ?: 0.0
        var measured = 0
        var withKick = 0
        for (b in m.bars) {
            if (b.kickOnBeat >= 0.0) {
                measured++
                if (b.kickOnBeat >= MIN_KICK_ON_BEAT) withKick++
            }
        }
        return TempoProfile(
            trackId = m.trackId,
            bpm = bpm,
            confidence = m.bpmConfidence,
            gridBeats = m.beatGrid.size,
            beatStrength = m.beatStrength,
            kickShare = if (measured > 0) withKick.toDouble() / measured else -1.0,
            kickBars = measured,
            wholeSong = m.coversWholeSong,
            analyzedMs = m.analyzedMs,
            analyzedAt = m.analyzedAt,
        )
    }

    /**
     * Pulso lo bastante claro para cuadrar beats. Mismos umbrales que el planificador del puente
     * (`DjBridgePlanner.isBeatMatchReady` llama aquí).
     */
    fun isBeatMatchReady(bpm: Double?, confidence: Double, gridBeats: Int, beatStrength: Double): Boolean {
        if ((bpm ?: 0.0) <= 20.0) return false
        if (confidence >= 0.35 && gridBeats > 0) return true
        return confidence >= 0.18 && gridBeats >= 8 && beatStrength >= 0.28
    }

    fun isBeatMatchReady(m: AudioMetrics?): Boolean =
        m != null && isBeatMatchReady(m.bpm, m.bpmConfidence, m.beatGrid.size, m.beatStrength)

    fun isBeatMatchReady(p: TempoProfile): Boolean = isBeatMatchReady(p.bpm, p.confidence, p.gridBeats, p.beatStrength)

    enum class TempoFit {
        /** Se puede cuadrar: tempo dentro del límite (contando mitad o doble) y bombo en las dos. */
        Fits,
        /** Habría que estirar la entrante más del máximo (6 % con voz, 8 % sin). */
        TempoFar,
        /** La entrante casi no tiene bombo al pulso. */
        NoKick,
        /** La entrante, analizada entera, no tiene pulso claro. */
        NoPulse,
        /** DJ rápido: los tonos chocan (armonía por debajo de [MIN_BEAT_MATCH_HARMONY]) y el puente no cuadraría. */
        KeyClash,
        /** La saliente no se puede cuadrar con nada (sin pulso claro o sin bombo): cambiar la entrante no ayuda. */
        NotApplicable,
        /** Falta análisis de alguna (o lo analizado aún no basta para juzgar): se espera. */
        Unknown,
    }

    data class TempoVerdict(
        val fit: TempoFit,
        val bpmA: Double = 0.0,
        val bpmB: Double = 0.0,
        /** Lo que habría que estirar B para ponerla al tempo de A, en %. */
        val stretchPct: Double = 0.0,
    ) {
        /** Seguro que no se puede cuadrar por culpa de la entrante (otra sí podría). */
        val incomingBlocks: Boolean get() = fit == TempoFit.TempoFar || fit == TempoFit.NoKick || fit == TempoFit.NoPulse ||
            fit == TempoFit.KeyClash
    }

    /**
     * Si la entrante [b] se podrá cuadrar con la saliente [a], con los límites del puente: estirar como mucho
     * [maxStretchPct] (6 % si B canta al entrar, 8 % si entra sin voz: lo decide quien llama con la letra) y bombo al
     * pulso en buena parte de los compases. Lo usa el DJ para cambiar canciones de la cola antes de sus puentes. Un
     * «Fits» con lo analizado a medias es provisional: quien llama vuelve a mirar cuando llega más análisis.
     */
    fun tempoVerdict(a: TempoProfile?, b: TempoProfile?, maxStretchPct: Double = MAX_STRETCH_PCT): TempoVerdict {
        if (a == null) return TempoVerdict(TempoFit.Unknown)
        if (!isBeatMatchReady(a)) {
            return TempoVerdict(if (a.wholeSong) TempoFit.NotApplicable else TempoFit.Unknown, bpmA = a.bpm)
        }
        // Sin bombo en lo medido de A: solo cierra si es la canción entera (el principio puede ser una intro).
        if (a.kickWhole && a.kickKnown && a.kickShare < MIN_KICK_SHARE) return TempoVerdict(TempoFit.NotApplicable, bpmA = a.bpm)
        if (b == null) return TempoVerdict(TempoFit.Unknown, bpmA = a.bpm)
        val stretch = stretchPct(DjHarmonic.bpmRelation(a.bpm, b.bpm).ratioOutOverIn)
        val base = TempoVerdict(TempoFit.Fits, a.bpm, b.bpm, stretch)
        if (!isBeatMatchReady(b)) {
            // Con solo el principio el pulso puede salir dudoso (una intro): se espera a más audio.
            return base.copy(fit = if (b.wholeSong) TempoFit.NoPulse else TempoFit.Unknown)
        }
        // Con B a medias solo se juzga con lo que ya es fiable: tempo con pulso claro y bombo sobre bastantes compases
        // (una intro sin bombo no basta para descartarla).
        if (stretch > maxStretchPct) {
            return base.copy(fit = if (b.wholeSong || b.confidence >= AudioMetrics.BEAT_MATCH_CONFIDENCE) TempoFit.TempoFar else TempoFit.Unknown)
        }
        if (b.kickKnown && b.kickShare < MIN_KICK_SHARE) {
            return base.copy(fit = if (b.kickWhole || b.kickBars >= MIN_KICK_BARS_PARTIAL) TempoFit.NoKick else TempoFit.Unknown)
        }
        if (!b.kickKnown && !b.wholeSong) return base.copy(fit = TempoFit.Unknown)
        return base
    }
}
