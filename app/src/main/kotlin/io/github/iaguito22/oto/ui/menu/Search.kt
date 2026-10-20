package io.github.iaguito22.oto.ui.menu

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.SearchSummaryPage
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.importer.Matcher
import io.github.iaguito22.oto.importer.songKey
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Lo tuyo (me gusta, listas, historial): con esto se adelanta un resultado, como hace Spotify. */
data class Taste(val artists: Set<String> = emptySet(), val songs: Set<String> = emptySet()) {
    companion object {
        fun of(heard: Sequence<Heard>): Taste {
            val artists = HashSet<String>()
            val songs = HashSet<String>()
            for (h in heard) {
                h.artist.split(", ", " & ").map(Matcher::norm).filterTo(artists) { it.isNotEmpty() }
                songs += songKey(h.title, h.artist)
            }
            return Taste(artists, songs)
        }
    }
}

/**
 * Buscar como en Spotify: una sola lista con lo más probable arriba (un artista, un álbum o una canción) y, mezclados,
 * canciones, artistas y álbumes. YouTube Music da el resultado principal y unas pocas de cada tipo; la búsqueda solo de
 * canciones, en paralelo, alarga la lista. La misma canción como canción y como vídeo sale una vez, como canción.
 */
/** Los filtros de arriba de los resultados, como en Spotify. */
enum class Filter(val label: String) { All("Todo"), Songs("Canciones"), Artists("Artistas"), Albums("Álbumes"), Lists("Listas") }

object Search {
    /** Con un filtro, solo de ese tipo y más resultados; las canciones, con lo tuyo delante igual que en «Todo». */
    suspend fun run(q: String, taste: Taste, filter: Filter): Result<List<YTItem>> = when (filter) {
        Filter.All -> run(q, taste)
        Filter.Songs -> YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).map { r ->
            rank(q, null, r.items.filterIsInstance<SongItem>(), taste)
        }
        Filter.Artists -> YouTube.search(q, YouTube.SearchFilter.FILTER_ARTIST).map { r -> r.items.filter { it is ArtistItem && !it.isProfile } }
        Filter.Albums -> YouTube.search(q, YouTube.SearchFilter.FILTER_ALBUM).map { r -> r.items.filterIsInstance<AlbumItem>() }
        Filter.Lists -> coroutineScope {
            val community = async { YouTube.search(q, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST) }
            YouTube.search(q, YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST).map { r ->
                (r.items + community.await().getOrNull()?.items.orEmpty()).filterIsInstance<PlaylistItem>().distinctBy { it.id }
            }
        }
    }

    suspend fun run(q: String, taste: Taste): Result<List<YTItem>> = coroutineScope {
        val songs = async { YouTube.search(q, YouTube.SearchFilter.FILTER_SONG) }
        val summary = YouTube.searchSummary(q)
        val more = songs.await().getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
        summary.map { rank(q, it, more, taste) }.recoverCatching { e -> if (more.isEmpty()) throw e else rank(q, null, more, taste) }
    }

    fun rank(q: String, page: SearchSummaryPage?, more: List<SongItem>, taste: Taste): List<YTItem> {
        val all = page?.summaries.orEmpty().flatMap { it.items }.filter { !(it is SongItem && it.isEpisode) }
        val keyOf = { s: SongItem -> songKey(s.title, s.artists.joinToString { it.name }) }

        // Canciones: las de verdad primero; un videoclip solo si no está como canción; lo subido por cualquiera, nunca
        // (salvo que no haya otra cosa).
        val audio = (all.filterIsInstance<SongItem>().filter { !it.isVideoSong } + more).distinctBy { it.id }
        val clips = all.filterIsInstance<SongItem>().filter { it.isVideoSong && it.musicVideoType != MUSIC_VIDEO_TYPE_UGC }
        val seen = HashSet<String>()
        var songs = (audio + clips).filter { seen.add(keyOf(it)) }
        if (songs.isEmpty()) songs = all.filterIsInstance<SongItem>()

        // Lo tuyo sube, pero solo entre lo que de verdad responde a la búsqueda.
        // Responde si el título tiene lo que se buscó quitando el artista (la última palabra puede estar a medias).
        // Buscar solo un artista no adelanta nada: ahí manda lo más oído.
        val words = Matcher.norm(q).split(' ').filter { it.isNotEmpty() }
        fun answers(s: SongItem): Boolean {
            val artist = s.artists.flatMap { Matcher.norm(it.name).split(' ') }.toSet()
            val need = words.filter { it !in artist }
            if (need.isEmpty()) return false
            val title = Matcher.norm(s.title).split(' ')
            val hit = need.count { w -> w in title || (w == words.last() && title.any { it.startsWith(w) }) }
            return hit * 3 >= need.size * 2
        }
        fun boost(s: SongItem): Int = when {
            !answers(s) -> 0
            keyOf(s) in taste.songs -> 6
            s.artists.any { Matcher.norm(it.name) in taste.artists } -> 3
            else -> 0
        }
        songs = songs.withIndex().sortedBy { (i, s) -> i - boost(s) }.map { it.value }

        // Arriba, lo que YouTube Music da por principal; si es una canción, la versión de canción (o la tuya, si la
        // tienes y también responde).
        val head = all.firstOrNull { !(it is SongItem && it.musicVideoType == MUSIC_VIDEO_TYPE_UGC) && (it !is ArtistItem || !it.isProfile) }
        val top: YTItem? = when (head) {
            is SongItem -> songs.firstOrNull { boost(it) > 0 } ?: songs.firstOrNull { keyOf(it) == keyOf(head) } ?: head
            is AlbumItem, is ArtistItem, is PlaylistItem -> head
            else -> songs.firstOrNull()
        }

        // Si lo principal ya es un artista, los demás que salen son parecidos, no lo que se buscó.
        val artists = if (top is ArtistItem) emptyList() else all.filterIsInstance<ArtistItem>().filter { !it.isProfile }.take(2)
        val albums = all.filterIsInstance<AlbumItem>().take(2)
        val playlists = all.filterIsInstance<PlaylistItem>().take(1)
        val rest = songs.filter { it.id != top?.id }
        return (listOfNotNull(top) + rest.take(3) + artists + albums + playlists + rest.drop(3))
            .distinctBy { it.id }
            .take(if (page == null) MAX * 2 else MAX)
    }

    private const val MAX = 30
}
