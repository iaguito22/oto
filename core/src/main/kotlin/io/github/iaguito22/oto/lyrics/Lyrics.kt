package io.github.iaguito22.oto.lyrics

/** Un trozo con su tiempo: una palabra o una sílaba. [space] dice si detrás va un espacio (si no, es la misma palabra). */
data class Word(val start: Long, val end: Long, val text: String, val space: Boolean)

/**
 * Una línea cantada. [words] es la voz y [bg] los coros, con sus propios tiempos (suenan a la vez que la voz).
 * [real]: los tiempos por palabra vienen de la fuente; si no, se reparten a ritmo de canto (ver [estimate]).
 * [duet]: la canta la segunda voz y va a la derecha, como en Apple Music.
 */
data class Line(
    val start: Long,
    val end: Long,
    val words: List<Word>,
    val bg: List<Word> = emptyList(),
    val real: Boolean = true,
    val duet: Boolean = false,
) {
    val text get() = words.joinText()
    val bgText get() = bg.joinText()
}

fun List<Word>.joinText() = buildString { this@joinText.forEach { append(it.text); if (it.space) append(' ') } }.trim()

sealed interface Lyrics {
    data object Missing : Lyrics

    /** [source] es de dónde salió, para decirlo al final de la letra. */
    data class Synced(val lines: List<Line>, val source: String, val language: String? = null) : Lyrics {
        val wordByWord get() = lines.any { it.real }

        /** La misma letra movida [ms] (positivo: más tarde). */
        fun shifted(ms: Long): Synced = if (ms == 0L) this else copy(lines = lines.map { l ->
            fun List<Word>.move() = map { it.copy(start = it.start + ms, end = it.end + ms) }
            l.copy(start = l.start + ms, end = l.end + ms, words = l.words.move(), bg = l.bg.move())
        })
    }

    data class Plain(val lines: List<String>, val source: String) : Lyrics
}

/** Lo que se busca: el título y el artista tal como los da YouTube, y la duración para no equivocarse de versión. */
data class LyricsQuery(val id: String, val title: String, val artist: String, val album: String?, val durationMs: Long)

/** El ajuste a mano de una letra, para la vista: «Sincronía: tal cual», «Letra +0,9 s» (más tarde) o «Letra −0,3 s». */
fun lyricsOffsetLabel(ms: Long): String =
    if (ms == 0L) "Sincronía: tal cual"
    else "Letra " + (if (ms > 0) "+" else "−") + "${kotlin.math.abs(ms) / 1000},${kotlin.math.abs(ms) % 1000 / 100}" + " s"

/** De cuánto en cuánto se ajusta y hasta dónde. */
const val LYRICS_OFFSET_STEP_MS = 100L
const val LYRICS_OFFSET_MAX_MS = 5_000L
