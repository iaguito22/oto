package io.github.iaguito22.oto.dj

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * «Tu resumen»: lo que has escuchado en este aparato, sacado de [Plays] (las últimas escuchas, unos meses). Cuenta el
 * tiempo que sonó cada canción de verdad, la eligieras tú o el DJ; lo saltado antes de 30 s no cuenta como escucha.
 * Las escuchas no se comparten entre aparatos: el resumen del móvil y el del PC son distintos.
 */
data class Recap(
    val since: Long,
    val minutes: Long,
    val plays: Int,
    val songs: Int,
    val artists: Int,
    val topSongs: List<Song>,
    val topArtists: List<Artist>,
    val months: List<Month>,
    /** Hora del día (0–23) en la que más escuchas, o null sin datos. */
    val hour: Int?,
    /** Canciones nuevas que te puso el DJ y oíste enteras. */
    val djFinds: Int,
) {
    data class Song(val id: String, val title: String, val artist: String, val artistId: String?, val minutes: Long, val times: Int)
    data class Artist(val name: String, val id: String?, val minutes: Long, val times: Int)
    data class Month(val month: YearMonth, val minutes: Long)

    val empty get() = plays == 0

    companion object {
        fun of(plays: List<Play>, zone: ZoneId = ZoneId.systemDefault(), top: Int = 5): Recap {
            val heard = plays.filter { it.heard }
            val ms = { p: Play -> if (p.dur > 0) minOf(p.ms, p.dur) else p.ms }
            fun minutes(list: List<Play>) = list.sumOf(ms) / 60_000
            val songs = heard.groupBy { it.key }.values
                .map { g -> val last = g.maxBy { it.at }; Song(last.id, last.title, last.artist, last.artistId, minutes(g), g.size) }
                .sortedWith(compareByDescending<Song> { it.times }.thenByDescending { it.minutes })
            val artists = heard.groupBy { it.who }.values
                .map { g -> val last = g.maxBy { it.at }; Artist(last.artist, g.firstNotNullOfOrNull { it.artistId }, minutes(g), g.size) }
                .sortedByDescending { it.minutes }
            val months = heard.groupBy { YearMonth.from(Instant.ofEpochMilli(it.at).atZone(zone)) }
                .map { (m, g) -> Month(m, minutes(g)) }.sortedBy { it.month }
            val hour = heard.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).hour }
                .maxByOrNull { (_, g) -> g.sumOf(ms) }?.key
            return Recap(
                since = plays.minOfOrNull { it.at } ?: 0L,
                minutes = minutes(heard), plays = heard.size,
                songs = songs.size, artists = artists.size,
                topSongs = songs.take(top), topArtists = artists.take(top),
                months = months, hour = hour,
                djFinds = heard.filter { it.dj != null && it.dj != Kind.FAMILIAR && it.reward >= 0.85 }.distinctBy { it.key }.size,
            )
        }
    }
}
