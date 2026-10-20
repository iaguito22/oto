package io.github.iaguito22.oto.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.toSong
import kotlinx.coroutines.flow.first

/**
 * Lo que se ve en Android Auto (y en cualquier otro navegador de medios): Tu música (el DJ, Me gusta, tus carpetas y
 * listas, en el orden de la app) y lo oído hace poco. Una canción tocada dentro de una lista lleva la lista en su id
 * ([inList]), para que suene la lista entera desde ella y no solo esa.
 */
class Library(private val prefs: Prefs) {
    fun root(): MediaItem = browsable(ROOT, "Oto")

    suspend fun children(parent: String): List<MediaItem> = when {
        parent == ROOT -> listOf(
            browsable(MUSIC, "Tu música", grid = true),
            browsable(RECENT, "Oído hace poco"),
        )
        parent == MUSIC -> buildList {
            add(MediaItem.Builder().setMediaId(DJ).setMediaMetadata(
                MediaMetadata.Builder().setTitle("DJ").setSubtitle("Mezcla para ti").setIsPlayable(true).setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).build(),
            ).build())
            val liked = prefs.liked.first()
            add(browsable(LIST + LIKED_ID, "Me gusta", count(liked.size), liked.firstOrNull()?.artwork))
            val lists = prefs.playlists.first()
            val pinned = prefs.pinned.first()
            // Como en la app: lo fijado delante; una carpeta va donde su primera lista.
            val shelf = lists.groupBy { it.folder ?: "\u0000" + it.id }.values.map { g ->
                val f = g.first().folder
                if (f == null) g.first().id to list(g.first())
                else "d:$f" to browsable(FOLDER + f, f, "Carpeta", g.firstNotNullOfOrNull { l -> l.songs.firstOrNull { it.artwork != null }?.artwork })
            }
            val (top, rest) = shelf.partition { it.first in pinned }
            addAll((top + rest).map { it.second })
        }
        parent == RECENT -> prefs.history.first().map { song(it, RECENT_ID) }
        parent.startsWith(FOLDER) -> prefs.playlists.first().filter { it.folder == parent.removePrefix(FOLDER) }.map(::list)
        parent.startsWith(LIST) -> songsOf(parent.removePrefix(LIST))?.let { (id, songs) -> songs.map { song(it, id) } }.orEmpty()
        else -> emptyList()
    }

    /** Lo que suena al tocar [item]: la lista entera desde ella si viene de una; si no, null (suena sola, con radio). */
    suspend fun expand(item: MediaItem): Pair<List<MediaItem>, Int>? {
        val (listId, songId) = inList(item.mediaId) ?: return null
        val songs = (if (listId == RECENT_ID) prefs.history.first() else songsOf(listId)?.second) ?: return null
        val at = songs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
        return songs.map { it.toSong().toMediaItem() } to at
    }

    suspend fun search(query: String): List<MediaItem> =
        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items.orEmpty()
            .filterIsInstance<SongItem>().take(SEARCH_MAX).map { it.toMediaItem() }

    private suspend fun songsOf(id: String): Pair<String, List<Heard>>? =
        if (id == LIKED_ID) id to prefs.liked.first()
        else prefs.playlists.first().firstOrNull { it.id == id }?.let { it.id to it.songs }

    private fun list(l: LocalPlaylist) = browsable(LIST + l.id, l.name, count(l.songs.size), l.songs.firstOrNull { it.artwork != null }?.artwork)

    private fun song(h: Heard, listId: String): MediaItem {
        val base = h.toSong().toMediaItem()
        return base.buildUpon().setMediaId(IN + listId + SEP + h.id).build()
    }

    private fun browsable(id: String, title: String, subtitle: String? = null, art: String? = null, grid: Boolean = false) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(art?.toUri())
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(if (id.startsWith(FOLDER) || id == MUSIC || id == RECENT || id == ROOT) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .apply {
                    if (grid) setExtras(Bundle().apply {
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                    })
                }
                .build(),
        )
        .build()

    private fun count(n: Int) = if (n == 1) "1 canción" else "$n canciones"

    companion object {
        const val ROOT = "root"
        const val MUSIC = "music"
        const val RECENT = "recent"
        const val DJ = "dj"
        private const val LIST = "list:"
        private const val FOLDER = "folder:"
        private const val IN = "in|"
        private const val SEP = "|"
        private const val RECENT_ID = "recent"
        private const val SEARCH_MAX = 30

        /** «in|lista|canción» → (lista, canción). */
        fun inList(mediaId: String): Pair<String, String>? {
            if (!mediaId.startsWith(IN)) return null
            val rest = mediaId.removePrefix(IN)
            val cut = rest.lastIndexOf(SEP).takeIf { it > 0 } ?: return null
            return rest.substring(0, cut) to rest.substring(cut + 1)
        }
    }
}
