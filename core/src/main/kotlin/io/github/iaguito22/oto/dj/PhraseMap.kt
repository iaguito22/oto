package io.github.iaguito22.oto.dj

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Límites de frase de una canción: donde empieza o acaba de verdad una parte (estrofa, estribillo, drop…) y, cuando el
 * compás es fiable, cada 4 compases desde esos límites. El DJ rápido entra y sale ahí para que el trozo suene a una
 * canción con principio y final, y para que las mezclas caigan en frase.
 *
 * Solo salen de compases MEDIDOS ([AudioMetrics.bars] con pulso; nunca de los rellenados para lo no analizado) y de la
 * letra sincronizada. Sin pulso ni letra no hay puntos: quien lo use debe tener un respaldo.
 */
data class PhraseMap(
    /** Puntos ordenados por tiempo. */
    val points: List<PhrasePoint>,
    /** Duración de un beat (ms), o 0 sin pulso. */
    val beatMs: Double,
    /** Si la rejilla de compases es de fiar (se añadieron múltiplos de 4 compases). */
    val gridTrusted: Boolean,
) {
    val isEmpty: Boolean get() = points.isEmpty()
    val times: List<Long> get() = points.map { it.ms }

    /** Primer punto en `[fromMs, toMs]`. */
    fun firstIn(fromMs: Long, toMs: Long): PhrasePoint? = points.firstOrNull { it.ms in fromMs..toMs }

    /** Último punto en `[fromMs, toMs]`. */
    fun lastIn(fromMs: Long, toMs: Long): PhrasePoint? = points.lastOrNull { it.ms in fromMs..toMs }

    /** Tolerancia para decir que un instante «cae en» un punto. */
    val toleranceMs: Long get() = if (beatMs > 0.0) (beatMs * 0.6).roundToLong().coerceIn(220L, 450L) else 450L

    companion object {
        val EMPTY = PhraseMap(emptyList(), 0.0, false)

        /** Bares reales más cortos/largos que esto no son compases (ventanas sin pulso, restos). */
        private const val MIN_BAR_MS = 1_100L
        private const val MAX_BAR_MS = 4_800L

        internal fun measuredUntil(durationMs: Long, audio: AudioMetrics?): Long = when {
            audio == null -> 0L
            audio.analyzedMs > 0L -> audio.analyzedMs.coerceAtMost(durationMs)
            else -> (audio.durationMs.takeIf { it > 0L } ?: durationMs).coerceAtMost(durationMs)
        }

        /** Si la forma de la letra se puede usar para elegir trozos y frases (no contradice la voz medida). */
        fun lyricsUsable(durationMs: Long, sections: List<SongSection>, audio: AudioMetrics?): Boolean =
            sections.isNotEmpty() && lyricsMatchAudio(sections, audio, measuredUntil(durationMs, audio))

        fun of(durationMs: Long, sections: List<SongSection>, audio: AudioMetrics?): PhraseMap {
            val beat = audio?.beatPeriodMs?.takeIf { audio.bpmConfidence >= 0.25 } ?: 0.0
            val measuredUntil = measuredUntil(durationMs, audio)
            val measuredBars = audio?.bars.orEmpty().filter {
                it.durationMs in MIN_BAR_MS..MAX_BAR_MS && it.endMs <= measuredUntil
            }
            // Inicios de compás medidos (sin el trozo parcial del principio).
            var downbeats = if (beat > 0.0) {
                measuredBars.filter { it.startMs > 0L }.map { it.startMs }
            } else emptyList()
            if (downbeats.size < 8) downbeats = emptyList()

            val lyricsOk = sections.isNotEmpty() && lyricsMatchAudio(sections, audio, measuredUntil)
            val usable = if (lyricsOk) sections else emptyList()

            // ¿Es de fiar el compás del análisis? Con letra: los versos tienen que caer siempre a la misma distancia de
            // su compás (la voz entra en el compás o con la misma anacrusa). La letra NO mueve el compás: con anacrusas de
            // 1, 2 o 3 beats no se puede saber solo con ella dónde cae el tiempo fuerte (se probó y era ambiguo).
            var trusted = downbeats.isNotEmpty() && (audio?.bpmConfidence ?: 0.0) >= 0.45
            if (trusted && beat > 0.0) {
                val lines = usable.flatMap { s -> s.lineStartsMs.ifEmpty { listOf(s.startMs) } }.filter { it < measuredUntil }
                if (lines.size >= 6) trusted = lineConsistency(downbeats, lines, beat) >= 0.55
            }

            val anchors = ArrayList<PhrasePoint>()
            fun snap(ms: Long, maxDist: Double): Long? {
                if (downbeats.isEmpty()) return null
                val d = nearest(downbeats, ms)
                return d.takeIf { abs(it - ms) <= maxDist }
            }
            for ((i, s) in usable.withIndex()) {
                // Inicio de sección: el compás donde entra la voz, o el siguiente si la voz entra con anacrusa.
                val startPoint = if (downbeats.isNotEmpty() && s.startMs < measuredUntil) {
                    downbeats.firstOrNull { it >= s.startMs - (beat * 0.5).roundToLong() && it <= s.startMs + (beat * 4.5).roundToLong() }
                } else s.startMs
                // Sin compases: el inicio vale como ancla de entrada, pero con la sílaba (ya es su límite).
                if (startPoint != null) anchors += PhrasePoint(startPoint, PhraseKind.SectionStart, i, s.isChorus)
                // Fin de sección: el primer compás tras la última palabra (con su cola), sin pasar del siguiente bloque.
                val nextStart = usable.getOrNull(i + 1)?.startMs ?: durationMs
                val vocalEnd = s.endMs + SongSections.VOCAL_TAIL_MS
                val endPoint = if (downbeats.isNotEmpty() && vocalEnd < measuredUntil) {
                    val tail = if (s.isChorus && s.seamFreeMs == 0L) SongSections.VOCAL_TAIL_MS else 200L
                    downbeats.firstOrNull { it >= s.endMs + tail && it <= nextStart }
                } else vocalEnd.takeIf { it <= nextStart }
                if (endPoint != null) anchors += PhrasePoint(endPoint, PhraseKind.SectionEnd, i, s.isChorus)
            }
            if (audio != null && downbeats.isNotEmpty()) {
                val tol = beat * 2.2
                listOf(audio.introEndMs to PhraseKind.IntroEnd, audio.dropMs to PhraseKind.Drop, audio.outroStartMs to PhraseKind.OutroStart)
                    .filter { (ms, _) -> ms in 2_000L until measuredUntil - 2_000L }
                    .forEach { (ms, kind) -> snap(ms, tol)?.let { anchors += PhrasePoint(it, kind) } }
                // Cambios claros de energía/batería entre compases medidos.
                for (k in 1 until measuredBars.size) {
                    val prev = measuredBars[k - 1]
                    val bar = measuredBars[k]
                    if (prev.endMs != bar.startMs) continue
                    val loud = ((abs(bar.lufs - prev.lufs) - 1.0) / 3.0).coerceIn(0.0, 1.0)
                    val drums = ((abs(bar.drumEnergy - prev.drumEnergy) - 0.08) / 0.25).coerceIn(0.0, 1.0)
                    if (0.5 * loud + 0.5 * drums >= 0.5) snap(bar.startMs, beat * 0.6)?.let { anchors += PhrasePoint(it, PhraseKind.EnergyChange) }
                }
            }

            // Entradas: 2, 4 y 8 compases antes de cada sección cantada, contados desde su propio compás (valen aunque la
            // fase global no sea de fiar: la cuenta es relativa a la sección).
            if (downbeats.isNotEmpty()) {
                for (a in anchors.filter { it.kind == PhraseKind.SectionStart }) {
                    val idx = downbeats.indexOfFirst { abs(it - a.ms) <= 1L }
                    if (idx < 0) continue
                    val prevVoiceEnd = a.sectionIndex?.let { usable.getOrNull(it - 1)?.endMs } ?: Long.MIN_VALUE
                    for (n in intArrayOf(2, 4, 8)) {
                        val d = downbeats.getOrNull(idx - n) ?: continue
                        if (d > prevVoiceEnd) anchors += PhrasePoint(d, PhraseKind.LeadIn, a.sectionIndex, a.chorus)
                    }
                }
            }
            val merged = dedupe(anchors.filter { it.ms in 0L..durationMs }.sortedBy { it.ms }, (beat * 1.5).coerceAtLeast(600.0))
            if (!trusted || downbeats.isEmpty()) return PhraseMap(merged, beat, false)

            // Múltiplos de 4 compases desde cada ancla hasta la siguiente.
            val withGrid = ArrayList<PhrasePoint>(merged)
            for ((idx, a) in merged.withIndex()) {
                val limit = merged.getOrNull(idx + 1)?.ms ?: measuredUntil
                val from = downbeats.indexOfFirst { abs(it - a.ms) <= beat * 0.6 }
                if (from < 0) continue
                var k = from + 4
                while (k < downbeats.size && downbeats[k] < limit - (beat * 2).roundToLong()) {
                    withGrid += PhrasePoint(downbeats[k], PhraseKind.Grid)
                    k += 4
                }
            }
            return PhraseMap(dedupe(withGrid.sortedBy { it.ms }, beat * 1.5), beat, true)
        }

        /**
         * Parte de los versos que caen a la distancia más repetida (en beats) del compás que los sigue: 1 si todos entran
         * con la misma anacrusa respecto a la rejilla medida.
         */
        internal fun lineConsistency(downbeats: List<Long>, lines: List<Long>, beat: Double): Double {
            val buckets = lines.mapNotNull { l ->
                val after = downbeats.firstOrNull { it >= l - beat * 0.5 } ?: return@mapNotNull null
                kotlin.math.round((after - l) / beat).toInt().coerceIn(0, 4)
            }
            if (buckets.isEmpty()) return 0.0
            return buckets.groupingBy { it }.eachCount().values.max().toDouble() / buckets.size
        }

        /**
         * Comprueba que la letra cae donde el audio tiene voz: si los compases dentro de los bloques cantados tienen
         * claramente MENOS voz que los de fuera, la letra va desplazada y no sirve para anclar frases.
         */
        internal fun lyricsMatchAudio(sections: List<SongSection>, audio: AudioMetrics?, measuredUntil: Long): Boolean {
            val bars = audio?.bars?.filter { it.endMs <= measuredUntil && it.startMs >= 5_000L } ?: return true
            if (bars.size < 12) return true
            val inside = bars.filter { b -> sections.any { b.startMs >= it.startMs && b.endMs <= it.endMs } }
            val outside = bars.filter { b -> sections.none { b.endMs > it.startMs - 1_000L && b.startMs < it.endMs + 1_000L } }
            if (inside.size < 4 || outside.size < 4) return true
            return inside.map { it.vocalRatio }.average() >= outside.map { it.vocalRatio }.average() - 0.03
        }

        private fun nearest(sorted: List<Long>, ms: Long): Long {
            var i = sorted.binarySearch(ms)
            if (i >= 0) return sorted[i]
            i = -i - 1
            return listOfNotNull(sorted.getOrNull(i - 1), sorted.getOrNull(i)).minByOrNull { abs(it - ms) }!!
        }

        /** Junta puntos casi iguales quedándose con el más fuerte (anclas antes que rejilla). */
        private fun dedupe(sorted: List<PhrasePoint>, minGapMs: Double): List<PhrasePoint> {
            val out = ArrayList<PhrasePoint>()
            for (p in sorted) {
                val last = out.lastOrNull()
                if (last != null && p.ms - last.ms < minGapMs) {
                    if (p.kind.rank < last.kind.rank) out[out.lastIndex] = p.copy(sectionIndex = p.sectionIndex ?: last.sectionIndex)
                    continue
                }
                out += p
            }
            return out
        }
    }
}

enum class PhraseKind(val rank: Int) {
    SectionStart(0),
    SectionEnd(1),
    Drop(2),
    IntroEnd(3),
    OutroStart(4),
    EnergyChange(5),
    LeadIn(6),
    Grid(7),
}

data class PhrasePoint(
    val ms: Long,
    val kind: PhraseKind,
    /** Bloque vocal ([SongSection]) al que pertenece el límite, si sale de la letra. */
    val sectionIndex: Int? = null,
    val chorus: Boolean = false,
)
