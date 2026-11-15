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
) {
    val isChorus get() = repeat >= CHORUS_REPEAT

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

    fun of(lines: List<Line>): List<SongSection> {
        val sung = lines.filter { it.words.isNotEmpty() || it.bg.isNotEmpty() }.sortedBy { it.start }
        if (sung.size < 4) return emptyList()
        val ends = sung.map(::sungEnd)
        val steps = sung.zipWithNext { a, b -> b.start - a.start }.filter { it > 0 }.sorted()
        val median = steps.getOrElse(steps.size / 2) { 3_000L }

        // Verso → cuántas veces sale (normalizado: sin tildes ni signos).
        val keys = sung.map { Matcher.norm(it.text) }
        val count = keys.filter { it.length >= MIN_LINE_CHARS }.groupingBy { it }.eachCount()

        val sections = ArrayList<SongSection>()
        var first = 0
        for (i in sung.indices) {
            val last = i == sung.lastIndex
            val split = last || run {
                val gap = sung[i + 1].start - ends[i]
                val step = sung[i + 1].start - sung[i].start
                gap >= MIN_GAP_MS || step >= maxOf(6_000L, (median * GAP_FACTOR).toLong())
            }
            if (!split) continue
            val members = (first..i)
            val repeated = members.count { (count[keys[it]] ?: 0) >= 2 }
            sections += SongSection(sung[first].start, ends[i], repeated.toDouble() / members.count())
            first = i + 1
        }
        return sections
    }

    /** Lo que de verdad dura la voz: con tiempos estimados, el fin de la línea llega hasta la siguiente. */
    private fun sungEnd(l: Line): Long {
        val lastWord = (l.words + l.bg).maxOfOrNull { it.end } ?: l.end
        return if (l.real) maxOf(lastWord, l.start) else minOf(maxOf(lastWord, l.start), l.end)
    }
}
