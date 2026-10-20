package io.github.iaguito22.oto.importer

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import java.text.Normalizer
import kotlin.math.abs

/** Una canción tal y como la da Spotify: lo justo para encontrarla en YouTube Music. */
data class SourceTrack(val title: String, val artists: List<String>, val album: String?, val durationMs: Long?)

/**
 * La misma canción aunque llegue con otro id: «Canción (Official Video)» de «Artista» y «Canción» de «Artista, Otro».
 * La radio y la búsqueda la usan para no repetir.
 */
fun songKey(title: String, artist: String): String {
    val a = Matcher.norm(artist.substringBefore(',').substringBefore(" & "))
    var t = title.replace(VIDEO_TAGS, "")
    // Los vídeos suelen llamarse «Artista - Canción».
    val dash = t.indexOf(" - ")
    if (dash > 0 && Matcher.norm(t.substring(0, dash)).let { it.isNotEmpty() && (a.contains(it) || it.contains(a)) }) t = t.substring(dash + 3)
    return Matcher.norm(t) + "|" + a
}

private val VIDEO_TAGS = Regex("""\s*[(\[][^)\]]*\b(official|oficial|video|vídeo|audio|lyrics?|letra|visuali[sz]er|mv)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

/** Lo que salió al buscarla: la mejor y su nota (0–1), o null si nada pasó el corte. */
data class Match(val song: SongItem, val score: Float)

/**
 * Busca en YouTube Music la misma canción que [SourceTrack]. Primero entre las canciones; si ninguna se parece
 * lo bastante, entre los vídeos (hay mucho que solo está subido como vídeo). La nota mezcla el título, el artista
 * y la duración, que es lo que más delata una versión equivocada (un directo, un remix, un «sped up»).
 */
object Matcher {
    suspend fun find(t: SourceTrack): Result<Match?> = runCatching {
        val q = query(t)
        val songs = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.filterIsInstance<SongItem>()
        val best = best(t, songs.take(CANDIDATES))
        if (best != null && best.score >= GOOD) return@runCatching best
        val videos = YouTube.search(q, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
        listOfNotNull(best, best(t, videos.take(CANDIDATES), video = true)).maxByOrNull { it.score }?.takeIf { it.score >= ACCEPT }
    }

    fun best(t: SourceTrack, candidates: List<SongItem>, video: Boolean = false): Match? =
        candidates.map { Match(it, score(t, it, video)) }.maxByOrNull { it.score }

    fun score(t: SourceTrack, s: SongItem, video: Boolean = false): Float {
        val artists = s.artists.map { norm(it.name) }.filter { it.isNotEmpty() }
        val srcArtists = t.artists.map(::norm).filter { it.isNotEmpty() }
        // En los vídeos el artista suele ir en el título («Artista - Canción (Official Video)»): se quita antes de comparar.
        val artistWords = (artists + srcArtists).flatMap(::words).toSet()
        val a = words(bare(t.title)) - NOISE
        val b = words(bare(s.title)) - NOISE - if (video) artistWords - words(t.title) else emptySet()
        val title = maxOf(dice(a, b), bigrams(a.joinToString(""), b.joinToString("")))

        val artist = when {
            srcArtists.isEmpty() -> 0.5f
            srcArtists.any { x -> artists.any { y -> x == y || y.contains(x) || x.contains(y) } } -> 1f
            video && srcArtists.any { norm(s.title).contains(it) } -> 1f
            srcArtists.any { x -> artists.any { y -> bigrams(x, y) >= 0.8f } } -> 1f
            else -> 0f
        }

        val d = s.duration
        val duration = if (t.durationMs == null || d == null) 0.4f else {
            val diff = abs(t.durationMs / 1000 - d)
            when {
                diff <= 2 -> 1f
                diff <= 5 -> 0.8f
                diff <= 10 -> 0.4f
                diff <= 25 -> 0f
                else -> -0.6f
            }
        }

        // Una versión que el original no dice que sea (directo, remix, acelerada…) casi nunca es la buena. En los vídeos
        // también delata el canal («… Nightcore»).
        val said = words(t.title) + t.artists.flatMap(::words)
        val extra = (words(s.title) + if (video) s.artists.flatMap { words(it.name) } else emptyList()).count { it in VERSIONS && it !in said }
        // A igualdad, lo subido por el sello (canción o videoclip) antes que lo de cualquiera.
        val ugc = if (s.musicVideoType == MUSIC_VIDEO_TYPE_UGC) 0.05f else 0f
        return 0.5f * title + 0.3f * artist + 0.2f * duration - 0.25f * extra - ugc
    }

    /** Título sin coletillas («- Remastered 2011», «(feat. …)») y el primer artista. */
    fun query(t: SourceTrack): String {
        val title = t.title
            .replace(Regex("""\s+-\s+.*\b(remaster|remastered|version|versión|edit|mono|stereo|bonus)\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(FEAT_PAREN, "")
            .trim()
        return listOfNotNull(title.ifEmpty { t.title }, t.artists.firstOrNull()).joinToString(" ")
    }

    /** Sin los invitados: «(feat. …)», «(con …)», «ft. …». YouTube Music los pone en el título y Spotify en los artistas. */
    private fun bare(s: String) = s
        .replace(FEAT_PAREN, " ")
        .replace(Regex("""\s(feat\.?|ft\.?|featuring)\s.*?(?=\s-\s|[(\[]|$)""", RegexOption.IGNORE_CASE), " ")

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun words(s: String): Set<String> = norm(s).split(' ').filter { it.isNotEmpty() && !(it.length == 4 && it.all(Char::isDigit)) }.toSet()

    private fun dice(a: Set<String>, b: Set<String>): Float =
        if (a.isEmpty() || b.isEmpty()) 0f else 2f * a.intersect(b).size / (a.size + b.size)

    /** Para lo que no se parte en palabras (japonés, coreano) o se escribe junto/separado distinto. */
    private fun bigrams(x: String, y: String): Float {
        fun g(s: String) = s.replace(" ", "").windowed(2).toList()
        val a = g(x)
        val b = g(y).toMutableList()
        if (a.isEmpty() || b.isEmpty()) return if (x.isNotEmpty() && x == y) 1f else 0f
        var hit = 0
        for (p in a) if (b.remove(p)) hit++
        return 2f * hit / (a.size + g(y).size)
    }

    private val FEAT_PAREN = Regex("""\s*[(\[](feat\.?|ft\.?|featuring|with|con)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private const val CANDIDATES = 6
    private const val GOOD = 0.8f
    private const val ACCEPT = 0.62f

    private val NOISE = setOf(
        "feat", "ft", "featuring", "with", "con", "prod", "remaster", "remastered", "version", "versión", "edit", "radio",
        "original", "mix", "mono", "stereo", "explicit", "clean", "official", "video", "audio", "music", "lyric", "lyrics",
        "visualizer", "mv", "hd", "4k", "the", "from", "of", "soundtrack", "ost",
    )
    private val VERSIONS = setOf(
        "live", "directo", "remix", "acoustic", "acustico", "instrumental", "karaoke", "cover", "sped", "slowed", "reverb",
        "nightcore", "8d", "extended", "piano", "orchestral", "demo", "rehearsal",
    )
}
