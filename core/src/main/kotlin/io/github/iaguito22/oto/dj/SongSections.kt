package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.importer.Matcher
import io.github.iaguito22.oto.lyrics.Line

/**
 * La forma de una canción sacada de su letra sincronizada: bloques de versos separados por silencios de la voz
 * (estrofa, estribillo, puente…) y cuáles se repiten (el estribillo). Sirve al DJ rápido para entrar y salir en
 * los bordes de un bloque, nunca a mitad de una frase ni de un estribillo.
 */
data class SongSection(
    /** Cuándo empieza a cantar el primer verso del bloque. */
    val startMs: Long,
    /** Cuándo termina de cantar el último. */
    val endMs: Long,
    /** De 0 a 1: cuántos de sus versos se repiten en otra parte de la canción. */
    val repeat: Double,
    /** Cuándo empieza cada verso del bloque (para cuadrar la fase del compás con la voz). */
    val lineStartsMs: List<Long> = emptyList(),
    /**
     * Trozo de un bloque repetido demasiado largo para ser un estribillo (en reguetón, minutos de versos que se repiten
     * sin pausas de la voz), partido donde vuelve a empezar la repetición. Se protege su interior, no la unión con el
     * siguiente trozo: si no, la protección encadenaba hasta el final y la canción sonaba entera (medido el 17/11).
     */
    val part: Boolean = false,
    /**
     * En un [part] seguido de otro (una unión): cuánto antes de su final puede empezar una salida rápida, que tiene que
     * acabar justo al terminar su último verso, antes de que vuelva a empezar la repetición. 0 si no le sigue una unión.
     */
    val seamFreeMs: Long = 0L,
    /** Respiraciones entre líneas con tiempos de palabra, incluida la voz de fondo. */
    val breathsMs: List<LongRange> = emptyList(),
) {
    // Una parte de un bloque repetido largo se protege siempre, aunque su propia proporción de versos repetidos sea baja.
    val isChorus get() = part || repeat >= CHORUS_REPEAT

    private companion object {
        const val CHORUS_REPEAT = 0.5
    }
}

object SongSections {
    /** Silencio de la voz que, como mínimo, separa dos bloques. */
    private const val MIN_GAP_MS = 1_600L
    /** Con letras sin tiempos por palabra, un hueco entre versos mucho mayor que lo normal también separa. */
    private const val GAP_FACTOR = 2.2
    private const val MIN_LINE_CHARS = 6
    /** Un estribillo de verdad no pasa de esto (los dobles, ~40 s); más es un bloque repetido que hay que partir. */
    private const val MAX_CHORUS_MS = 45_000L
    private const val MIN_PART_MS = 8_000L
    /**
     * Antes de una unión, desde cuándo puede empezar una salida rápida (la del DJ rápido baja en 400 ms) que acabe con el
     * último verso de la parte. Un fundido más largo no cabe: se metería en la voz de un lado o del otro.
     */
    private const val SEAM_FREE_MS = 1_500L
    /** Holgura del objetivo de salida dentro del final libre (ver [safeExitAt]). */
    private const val SEAM_SLACK_MS = 150L

    const val VOCAL_TAIL_MS = 600L

    fun overlapsChorus(startMs: Long, endMs: Long, sections: List<SongSection>): Boolean =
        sections.any {
            it.isChorus && if (it.seamFreeMs > 0L) {
                // Solo vale del todo fuera, o empezando en el final libre de la parte (lo que siga tras su último verso, en
                // un hueco o en la parte siguiente, lo juzga esa otra sección), igual que [safeExitAt].
                startMs < it.endMs && endMs > it.startMs && startMs < it.endMs - it.seamFreeMs
            } else startMs < it.endMs + VOCAL_TAIL_MS && endMs > it.startMs
        }

    /** Respaldo del DJ rápido. La bajada y el salto caben entre dos líneas completas. */
    internal fun allowsRapidBreath(startMs: Long, endMs: Long, sections: List<SongSection>): Boolean =
        sections.any { it.isChorus && it.breathsMs.any { gap -> startMs >= gap.first && endMs <= gap.last } } &&
            sections.all { section ->
                startMs >= section.endMs || endMs <= section.startMs ||
                    section.breathsMs.any { gap -> startMs >= gap.first && endMs <= gap.last }
            }

    internal fun rapidBreaths(sections: List<SongSection>): List<LongRange> =
        sections.filter { it.isChorus }.flatMap { it.breathsMs }.sortedBy { it.first }

    /**
     * Forced exits and their whole duck/fade obey the same protected interval as planned mixes.
     *
     * [fadeBefore]: la bajada va ANTES del punto devuelto (Android baja y luego salta en él); por defecto va después
     * (el PC sale en él y baja). En ambos casos la bajada entera queda fuera de lo protegido; en las uniones de un bloque
     * repetido ([SongSection.seamFreeMs]) cae sobre el final del último verso, sin llegar al verso siguiente.
     */
    fun safeExitAt(
        plannedMs: Long, sections: List<SongSection>, durationMs: Long, fadeMs: Long = 0L, fadeBefore: Boolean = false,
    ): Long {
        var at = plannedMs.coerceIn(0L, durationMs)
        for (section in sections.filter { it.isChorus }.sortedBy { it.startMs }) {
            val windowStart = if (fadeBefore) at - fadeMs else at
            val windowEnd = if (fadeBefore) at else at + fadeMs
            if (section.seamFreeMs > 0L) {
                // También un punto justo al inicio de la parte: ahí empieza su verso.
                if ((windowEnd > section.startMs || windowStart == section.startMs || at == section.startMs) && windowStart < section.endMs) {
                    val freeFrom = section.endMs - section.seamFreeMs
                    // Primer y último punto válidos: la bajada entera en el final libre y sin llegar al verso siguiente.
                    val first = if (fadeBefore) freeFrom + fadeMs else freeFrom
                    val last = if (fadeBefore) section.endMs - 1L else section.endMs - fadeMs
                    // Nunca hacia atrás (una comprobación tardía con la bajada antes volvía a la unión y bajaba el volumen
                    // sobre el verso nuevo): como pronto, la unión; la parte siguiente decide.
                    at = when {
                        // Un fundido largo no cabe: a la unión, y la parte siguiente decide.
                        fadeMs > section.seamFreeMs -> maxOf(at, section.endMs)
                        // Ya en el final libre: vale tal cual.
                        at in first..last -> at
                        // Antes: casi al final del verso, con una holgura corta para quien lo comprueba cada pocos ms o
                        // salta algo tarde (sin ella la unión se perdía por milisegundos; más grande, se comía el verso).
                        at < first -> (section.endMs - SEAM_SLACK_MS - (if (fadeBefore) 0L else fadeMs)).coerceAtLeast(first)
                        // Tarde para esta unión: a la siguiente.
                        else -> maxOf(at, section.endMs)
                    }.coerceAtMost(durationMs)
                }
                continue
            }
            if ((windowEnd > section.startMs || windowStart == section.startMs || at == section.startMs) && windowStart < section.endMs + VOCAL_TAIL_MS) {
                // Con la bajada antes, el salto va una bajada más tarde: que la bajada entera caiga pasada la cola vocal.
                at = (section.endMs + VOCAL_TAIL_MS + (if (fadeBefore) fadeMs else 0L)).coerceAtMost(durationMs)
            }
        }
        return at
    }

    fun of(lines: List<Line>): List<SongSection> {
        val sung = lines.filter { it.words.isNotEmpty() || it.bg.isNotEmpty() }.sortedBy { it.start }
        if (sung.size < 4) return emptyList()
        val ends = sung.map(::sungEnd)
        val steps = sung.zipWithNext { a, b -> b.start - a.start }.filter { it > 0 }.sorted()
        val median = steps.getOrElse(steps.size / 2) { 3_000L }

        // Verso → cuántas veces sale (normalizado: sin tildes ni signos).
        val keys = sung.map { Matcher.norm(it.text) }
        val count = keys.filter { it.length >= MIN_LINE_CHARS }.groupingBy { it }.eachCount()

        // Repeated runs identify a chorus even when a long verse leads straight into it without silence.
        val repeats = keys.map { (count[it] ?: 0) >= 2 }
        val chorusLine = BooleanArray(sung.size)
        var run = 0
        while (run < sung.size) {
            if (!repeats[run]) { run++; continue }
            var end = run + 1
            while (end < sung.size && repeats[end] && sung[end].start - ends[end - 1] < 4_500L) end++
            if (end - run >= 2) for (i in run until end) chorusLine[i] = true
            run = end
        }
        // An isolated non-repeated tag belongs to the surrounding chorus, not a new verse.
        for (i in 1 until sung.size) {
            if (!repeats[i] && chorusLine[i - 1] && sung[i].start - ends[i - 1] < 4_500L &&
                (i == sung.lastIndex || sung[i + 1].start - ends[i] >= 4_500L || chorusLine[i + 1])) {
                chorusLine[i] = true
            }
        }
        val sections = ArrayList<SongSection>()
        var first = 0
        for (i in sung.indices) {
            val last = i == sung.lastIndex
            val split = last || run {
                val gap = sung[i + 1].start - ends[i]
                val step = sung[i + 1].start - sung[i].start
                // A breath between repeated lines is not the end of the chorus.
                val sameChorus = chorusLine[i] && chorusLine[i + 1] && gap < 4_500L
                chorusLine[i] != chorusLine[i + 1] ||
                    (!sameChorus && (gap >= MIN_GAP_MS || step >= maxOf(6_000L, (median * GAP_FACTOR).toLong())))
            }
            if (!split) continue
            val members = (first..i)
            val repeated = members.count { (count[keys[it]] ?: 0) >= 2 }
            val whole = SongSection(sung[first].start, ends[i], repeated.toDouble() / members.count(),
                members.map { sung[it].start }, breathsMs = measuredBreaths(first, i, sung, ends))
            sections += if (whole.isChorus && whole.endMs - whole.startMs > MAX_CHORUS_MS) {
                splitLongRepeat(first, i, sung, ends, keys, count)
            } else listOf(whole)
            first = i + 1
        }
        return sections
    }

    /**
     * Parte un bloque repetido de más de [MAX_CHORUS_MS] donde vuelve a empezar la repetición: donde reaparece su primer
     * verso o, si no, el verso que más se repite dentro. Trozos de al menos [MIN_PART_MS]. Sin cortes, queda entero.
     */
    private fun splitLongRepeat(
        first: Int, last: Int, sung: List<Line>, ends: List<Long>, keys: List<String>, count: Map<String, Int>,
    ): List<SongSection> {
        // Rangos de versos [desde, hasta]; los que aún pasan de MAX_CHORUS_MS se vuelven a partir (pocas vueltas).
        var ranges = listOf(first to last)
        repeat(3) {
            ranges = ranges.flatMap { (a, b) ->
                if (ends[b] - sung[a].start <= MAX_CHORUS_MS) listOf(a to b) else cutAtRestarts(a, b, sung, ends, keys)
            }
        }
        if (ranges.size == 1) return listOf(SongSection(sung[first].start, ends[last], 1.0,
            (first..last).map { sung[it].start }, breathsMs = measuredBreaths(first, last, sung, ends)))
        return ranges.mapIndexed { i, (a, b) ->
            val part = a..b
            val repeated = part.count { (count[keys[it]] ?: 0) >= 2 }
            SongSection(sung[a].start, ends[b], repeated.toDouble() / part.count(), part.map { sung[it].start },
                part = true, seamFreeMs = if (i < ranges.lastIndex) SEAM_FREE_MS else 0L,
                breathsMs = measuredBreaths(a, b, sung, ends))
        }
    }

    private fun measuredBreaths(first: Int, last: Int, sung: List<Line>, ends: List<Long>): List<LongRange> {
        var voiceEnd = 0L
        return (first until last).mapNotNull { i ->
            voiceEnd = maxOf(voiceEnd, ends[i])
            val next = sung[i + 1]
            val nextStart = minOf(next.start, next.bg.minOfOrNull { it.start } ?: next.start)
            val start = voiceEnd + 180L
            val end = nextStart - 120L
            (start..end).takeIf { sung[i].real && next.real && end - start >= 650L }
        }
    }

    /** Corta [a, b] donde reaparece su primer verso o, si no se repite dentro, el que más se repite. */
    private fun cutAtRestarts(a: Int, b: Int, sung: List<Line>, ends: List<Long>, keys: List<String>): List<Pair<Int, Int>> {
        val inRange = (a..b).map { keys[it] }.filter { it.length >= MIN_LINE_CHARS }.groupingBy { it }.eachCount()
        val restartKey = keys[a].takeIf { (inRange[it] ?: 0) >= 2 }
            ?: inRange.filter { it.value >= 2 }.maxByOrNull { it.value }?.key
            ?: return listOf(a to b)
        val cuts = ArrayList<Int>()
        var lastCutStart = sung[a].start
        for (j in a + 1..b) {
            if (keys[j] == restartKey && sung[j].start - lastCutStart >= MIN_PART_MS && ends[b] - sung[j].start >= MIN_PART_MS) {
                cuts += j
                lastCutStart = sung[j].start
            }
        }
        if (cuts.isEmpty()) return listOf(a to b)
        return (listOf(a) + cuts).zip(cuts.map { it - 1 } + listOf(b))
    }

    /**
     * Lo que de verdad dura la voz. Con tiempos estimados la última palabra puede quedarse algo corta y el fin de la línea
     * llega hasta la siguiente; pero no más de [ESTIMATED_TAIL_MS] tras su última palabra: en las letras de LRCLIB la
     * última línea dura hasta el final de la canción (medido: «Hell no» acaba a 139 s y la línea a 154 s) y el último
     * estribillo tapaba el outro instrumental: no se podía mezclar en él y salía «Enlace al final» (37 % en DJ normal).
     */
    private fun sungEnd(l: Line): Long {
        val lastWord = (l.words + l.bg).maxOfOrNull { it.end } ?: l.end
        return if (l.real) maxOf(lastWord, l.start) else maxOf(lastWord, l.start, minOf(l.end, lastWord + ESTIMATED_TAIL_MS))
    }

    private const val ESTIMATED_TAIL_MS = 1_000L
}
