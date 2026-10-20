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
    }

    data class Plain(val lines: List<String>, val source: String) : Lyrics
}

/** Lo que se busca: el título y el artista tal como los da YouTube, y la duración para no equivocarse de versión. */
data class LyricsQuery(val id: String, val title: String, val artist: String, val album: String?, val durationMs: Long)
