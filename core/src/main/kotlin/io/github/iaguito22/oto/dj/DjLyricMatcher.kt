package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.lyrics.Line
import io.github.iaguito22.oto.lyrics.Lyrics
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Buscador de palabras/frases cantadas compartidas con timing real por palabra (`Line.real == true`) y detector de
 * intervalos seguros de voz para evitar solapar dos voces principales durante una mezcla.
 */
object DjLyricMatcher {
    data class TimedWord(
        val startMs: Long,
        val endMs: Long,
        val raw: String,
        val norm: String,
        val lineIdx: Int,
        val real: Boolean = true,
        val lineStart: Boolean = false,
        val lineEnd: Boolean = false,
    )

    private val NON_ALNUM = Regex("[^\\p{L}\\p{Nd}]")
    private val STOP_WORDS = setOf(
        "a", "al", "algo", "and", "are", "as", "at", "be", "but", "by", "con", "da", "de", "del", "do", "don",
        "e", "el", "en", "es", "esa", "ese", "eso", "esta", "este", "esto", "for", "from", "ha", "he", "i", "im",
        "in", "is", "it", "la", "las", "le", "les", "lo", "los", "me", "mi", "mis", "my", "na", "ni", "no", "nos",
        "o", "of", "oh", "on", "or", "os", "pa", "para", "pero", "por", "que", "se", "si", "sin", "so", "su", "sus",
        "te", "the", "this", "ti", "to", "tu", "tus", "u", "un", "una", "uno", "unos", "we", "with", "y", "ya", "yo",
        "you", "your", "yeah", "uh", "ah", "eh", "ohhh",
    )

    fun extractTimedWords(lyrics: Lyrics.Synced?): List<TimedWord> {
        if (lyrics == null) return emptyList()
        val out = ArrayList<TimedWord>(256)
        lyrics.lines.forEachIndexed { lineIdx, line ->
            if (line.words.isEmpty()) return@forEachIndexed
            // Agrupa sílabas consecutivas (`space == false`) en palabras completas; admite tanto sincronía por palabra
            // (`line.real == true`) como estimada de LRC/LRCLIB (`line.real == false`).
            val minDur = if (line.real) 55L else 110L
            val lineWords = ArrayList<TimedWord>(line.words.size)
            val sb = StringBuilder()
            var wStart = -1L
            var wEnd = -1L
            for ((idx, w) in line.words.withIndex()) {
                if (wStart < 0L) wStart = w.start
                wEnd = max(wEnd, w.end)
                sb.append(w.text)
                if (w.space || idx == line.words.lastIndex) {
                    val rawWord = sb.toString().trim()
                    if (wEnd > wStart + minDur) {
                        val norm = normalizeToken(rawWord)
                        if (norm.length >= 2) {
                            lineWords += TimedWord(wStart, wEnd, rawWord, norm, lineIdx, real = line.real)
                        }
                    }
                    sb.setLength(0)
                    wStart = -1L
                    wEnd = -1L
                }
            }
            if (lineWords.isNotEmpty()) {
                for (i in lineWords.indices) {
                    val lw = lineWords[i]
                    out += lw.copy(lineStart = i == 0, lineEnd = i == lineWords.lastIndex)
                }
            }
        }
        return out
    }

    /** Proporción del intervalo `[startMs, endMs]` ocupada por voz principal (0..1), o `null` si no hay letra. */
    fun vocalOccupancyRatio(lyrics: Lyrics.Synced?, startMs: Long, endMs: Long): Double? {
        if (lyrics == null || endMs <= startMs) return null
        val total = (endMs - startMs).toDouble()
        var covered = 0L
        var prevEnd = startMs
        val spans = lyrics.lines.asSequence()
            .filter { (it.words.isNotEmpty() || it.end > it.start + 300L) && it.end > startMs && it.start < endMs }
            .sortedBy { it.start }
        for (line in spans) {
            val s = max(startMs, line.start)
            val e = min(endMs, line.end)
            if (e > s) {
                val adjStart = max(s, prevEnd)
                if (e > adjStart) {
                    covered += (e - adjStart)
                    prevEnd = e
                }
            }
        }
        return (covered / total).coerceIn(0.0, 1.0)
    }

    /** True si `[ms - 180, ms + 180]` cae en mitad de una palabra o línea vocal real. */
    fun isMidVocal(lyrics: Lyrics.Synced?, ms: Long): Boolean {
        if (lyrics == null) return false
        return lyrics.lines.any { line ->
            if (line.real && line.words.isNotEmpty()) {
                val inWord = line.words.any { w -> w.end > w.start + 90L && ms in (w.start + 45L)..(w.end - 45L) }
                if (inWord) return@any true
                // Evitar cortar en medio de palabras contiguas de la misma frase (hueco < 220 ms).
                for (i in 0 until line.words.lastIndex) {
                    val cur = line.words[i]
                    val nxt = line.words[i + 1]
                    if (nxt.start - cur.end in 0L..220L && ms in (cur.end - 20L)..(nxt.start + 20L)) return@any true
                }
                false
            } else {
                val sungEnd = lineSungEnd(line)
                (line.words.isNotEmpty() || sungEnd > line.start + 360L) && ms in (line.start + 160L)..(sungEnd - 160L)
            }
        }
    }

    /**
     * True si un corte seco en [cutMs] partiría una palabra, el cuerpo de una línea cantada o dos versos
     * consecutivos sin respiro (salvo unión limpia entre fin de preestribillo e inicio de estribillo si [allowChorusSeam]).
     */
    fun cutsWordOrPhrase(lyrics: Lyrics.Synced?, cutMs: Long, allowChorusSeam: Boolean = false): Boolean {
        if (lyrics == null) return false
        val sung = lyrics.lines
            .filter { it.words.isNotEmpty() || it.bg.isNotEmpty() || it.end > it.start + 300L }
            .sortedBy { it.start }
        if (sung.isEmpty()) return false

        for (i in sung.indices) {
            val line = sung[i]
            val sungEnd = lineSungEnd(line)
            if (line.real && line.words.isNotEmpty()) {
                if (line.words.any { w -> w.end > w.start + 50L && cutMs in (w.start + 25L)..(w.end - 15L) }) {
                    return true
                }
                for (j in 0 until line.words.lastIndex) {
                    val cur = line.words[j]
                    val nxt = line.words[j + 1]
                    if (nxt.start - cur.end in 0L..380L && cutMs in (cur.end - 25L)..(nxt.start + 25L)) {
                        return true
                    }
                }
            } else {
                val startBound = line.words.firstOrNull()?.start ?: line.start
                if (sungEnd > startBound + 260L && cutMs in (startBound + 35L)..(sungEnd - 80L)) {
                    return true
                }
            }
            val next = sung.getOrNull(i + 1)
            if (next != null && next.start - sungEnd in 0L..260L && cutMs in (sungEnd - 80L)..(next.start + 40L)) {
                val cleanChorusJoin = allowChorusSeam && cutMs >= sungEnd - 25L && cutMs <= next.start + 20L
                if (!cleanChorusJoin) return true
            }
        }
        return false
    }

    private fun lineSungEnd(line: Line): Long {
        val lastWord = (line.words + line.bg).maxOfOrNull { it.end } ?: line.end
        return max(lastWord, line.start)
    }

    /**
     * Busca la mejor pareja frase-de-salida (en `[outMinMs, outMaxMs]`) + frase-de-entrada (en `[inMinMs, inMaxMs]`)
     * para `LyricLoopCut`, garantizando que B canta esa misma frase justo al entrar al corte.
     */
    fun findSharedLoopMatch(
        outLyrics: Lyrics.Synced?,
        inLyrics: Lyrics.Synced?,
        outMinMs: Long,
        outMaxMs: Long,
        inMinMs: Long,
        inMaxMs: Long,
        bpmA: Double,
        requireOpeningPhrase: Boolean = false,
    ): LyricLoopPlanSpec? {
        val wordsA = extractTimedWords(outLyrics)
        val wordsB = extractTimedWords(inLyrics)
        if (wordsA.isEmpty() || wordsB.isEmpty()) return null

        val beatMs = if (bpmA > 20.0) 60_000.0 / bpmA else 500.0
        val outWords = wordsA.indices.filter { wordsA[it].endMs in outMinMs..outMaxMs && wordsA[it].startMs >= outMinMs - 1500L }
        val inWords = wordsB.indices.filter {
            (!requireOpeningPhrase || it == 0) && wordsB[it].startMs in inMinMs..inMaxMs
        }
        if (outWords.isEmpty() || inWords.isEmpty()) return null

        var best: LyricLoopPlanSpec? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (nLen in intArrayOf(3, 2, 1)) {
            for (ia in outWords) {
                if (ia + nLen > wordsA.size) continue
                // No cruzar huecos enormes de línea entre palabras de la misma frase.
                if (wordsA[ia + nLen - 1].lineIdx != wordsA[ia].lineIdx) continue
                val phraseTokens = (0 until nLen).map { wordsA[ia + it].norm }
                if (nLen == 1 && (phraseTokens[0] in STOP_WORDS || phraseTokens[0].length < 4)) continue
                if (nLen > 1 && phraseTokens.all { it in STOP_WORDS }) continue

                val rawAStart = wordsA[ia].startMs
                val rawAEnd = wordsA[ia + nLen - 1].endMs
                val durA = rawAEnd - rawAStart
                if (durA < 220L || durA > (beatMs * 3.8).roundToLong()) continue
                // Con LRC por línea (`real == false`), exigimos frase multi-palabra o palabra en borde de línea para no cortar a ciegas.
                if (!wordsA[ia].real && nLen == 1 && (!wordsA[ia].lineStart && !wordsA[ia].lineEnd || durA < 340L)) continue

                // Si la palabra/frase suelta es muy corta (< 620 ms), incluimos 1-2 palabras previas de contexto en el
                // bucle para que no suene robótico al repetirse, manteniendo el acento final sobre la palabra compartida.
                var ctxStartIdx = ia
                while (ctxStartIdx > 0 &&
                    wordsA[ctxStartIdx - 1].lineIdx == wordsA[ia].lineIdx &&
                    (rawAEnd - wordsA[ctxStartIdx - 1].startMs) <= (beatMs * 2.1).roundToLong() &&
                    (rawAEnd - wordsA[ctxStartIdx].startMs) < 620L
                ) {
                    ctxStartIdx--
                }
                val prePadA = if (wordsA[ia].real) 45L else 75L
                val postPadA = if (wordsA[ia].real) 60L else 85L
                val capStartMs = max(0L, wordsA[ctxStartIdx].startMs - prePadA)
                val capEndMs = rawAEnd + postPadA
                val rawSpan = (capEndMs - capStartMs).coerceIn(260L, (beatMs * 2.2).roundToLong())

                // Verificar que no pisamos inmediatamente otra frase muy encajada tras el cierre en A (si es posible).
                val nextAStart = wordsA.getOrNull(ia + nLen)?.startMs ?: Long.MAX_VALUE
                val postGapA = (nextAStart - rawAEnd).coerceIn(0L, 2000L)
                // Cuantizar la unidad del bucle al beat más cercano cuando cabe sin pisar la frase siguiente.
                val beatRounded = beatMs.roundToLong().coerceIn(260L, 1_100L)
                val twoBeatRounded = (beatMs * 2.0).roundToLong().coerceIn(480L, (beatMs * 2.2).roundToLong())
                val loopDurMs = when {
                    rawSpan <= beatRounded && beatRounded <= (rawSpan * 1.35).roundToLong() &&
                        capStartMs + beatRounded <= nextAStart -> beatRounded
                    rawSpan <= twoBeatRounded && twoBeatRounded <= (rawSpan * 1.35).roundToLong() &&
                        capStartMs + twoBeatRounded <= nextAStart -> twoBeatRounded
                    else -> rawSpan
                }

                for (ib in inWords) {
                    if (ib + nLen > wordsB.size) continue
                    if (wordsB[ib + nLen - 1].lineIdx != wordsB[ib].lineIdx) continue
                    var matches = true
                    for (k in 0 until nLen) {
                        if (wordsB[ib + k].norm != phraseTokens[k]) {
                            matches = false
                            break
                        }
                    }
                    if (!matches) continue

                    val inPhraseStart = wordsB[ib].startMs
                    val inPhraseEnd = wordsB[ib + nLen - 1].endMs
                    val prevBEnd = wordsB.getOrNull(ib - 1)?.endMs ?: 0L
                    val preGapB = inPhraseStart - prevBEnd
                    if (!wordsB[ib].real && nLen == 1 && !wordsB[ib].lineStart && preGapB < 350L) continue
                    // Buscamos que B arranque diciendo esa frase limpia o tras una respiración clara (nunca mid-line brusco).
                    val entryPadB = if (wordsB[ib].real) 110L else 140L
                    val entryMs = when {
                        ib == 0 -> max(0L, inPhraseStart - 90L)
                        wordsB[ib - 1].lineIdx != wordsB[ib].lineIdx -> max(prevBEnd + 40L, inPhraseStart - entryPadB)
                        preGapB >= 320L -> max(prevBEnd + 60L, inPhraseStart - entryPadB)
                        else -> max(wordsB.firstOrNull { it.lineIdx == wordsB[ib].lineIdx }?.startMs ?: inPhraseStart, inPhraseStart - entryPadB)
                    }.coerceAtLeast(0L)
                    if (entryMs > inMaxMs) continue

                    // Evita entrar con una palabra que viene justo después de mucha letra pegada en B si no coincide con línea nueva.
                    val leadTimeB = inPhraseStart - entryMs
                    if (leadTimeB > 1800L) continue

                    val durationFactor = 1.0 - abs(loopDurMs - beatMs) / (beatMs * 1.5)
                    val ngramBonus = when (nLen) {
                        3 -> 3.4
                        2 -> 2.3
                        else -> 1.0
                    }
                    val cleanEntryBonus = when {
                        ib == 0 -> 1.3
                        wordsB[ib - 1].lineIdx != wordsB[ib].lineIdx -> 1.2
                        preGapB >= 400L -> 0.85
                        else -> 0.25
                    }
                    val realBonus = (if (wordsA[ia].real) 0.45 else 0.0) + (if (wordsB[ib].real) 0.45 else 0.0)
                    val gapBonus = (postGapA / 600.0).coerceAtMost(0.9)
                    val wordQuality = if (nLen == 1) (phraseTokens[0].length.coerceAtMost(9) / 8.0) else 1.0
                    val score = ngramBonus + cleanEntryBonus + realBonus + gapBonus + 0.6 * durationFactor + 0.4 * wordQuality

                    if (score > bestScore) {
                        bestScore = score
                        val phraseText = (0 until nLen).joinToString(" ") { wordsA[ia + it].raw }
                        val doubleSpeedTail = loopDurMs < (beatMs * 0.72).roundToLong() || (nLen == 1 && rawAEnd - rawAStart < 560L)
                        val repeats = 4
                        best = LyricLoopPlanSpec(
                            phraseText = phraseText,
                            outLoopStartMs = capStartMs,
                            outLoopEndMs = capStartMs + loopDurMs,
                            inPhraseStartMs = inPhraseStart,
                            inPhraseEndMs = inPhraseEnd,
                            inEntryMs = entryMs,
                            repeats = repeats,
                            beatMs = beatMs,
                            loopDurationMs = loopDurMs,
                            doubleSpeedTail = doubleSpeedTail,
                            confidence = (score / 5.2).coerceIn(0.35, 1.0),
                        )
                    }
                }
            }
        }
        return best
    }

    /** Bonus rápido para el selector de siguiente canción (`Dj.kt`) cuando ambas letras reales comparten gancho cantable. */
    fun hasSharedSingablePhrase(outLyrics: Lyrics?, inLyrics: Lyrics?): Boolean {
        val o = outLyrics as? Lyrics.Synced ?: return false
        val i = inLyrics as? Lyrics.Synced ?: return false
        val wa = extractTimedWords(o)
        val wb = extractTimedWords(i)
        if (wa.isEmpty() || wb.isEmpty()) return false
        val tokensB = HashSet<String>(wb.size)
        for (idx in wb.indices) {
            if (wb[idx].norm.length >= 5 && wb[idx].norm !in STOP_WORDS) tokensB += wb[idx].norm
            if (idx + 1 < wb.size && wb[idx].lineIdx == wb[idx + 1].lineIdx) {
                tokensB += "${wb[idx].norm} ${wb[idx + 1].norm}"
            }
        }
        for (idx in wa.indices) {
            if (wa[idx].norm in tokensB) return true
            if (idx + 1 < wa.size && wa[idx].lineIdx == wa[idx + 1].lineIdx) {
                if ("${wa[idx].norm} ${wa[idx + 1].norm}" in tokensB) return true
            }
        }
        return false
    }

    private fun normalizeToken(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val ascii = decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return ascii.replace(NON_ALNUM, "").trim()
    }
}
