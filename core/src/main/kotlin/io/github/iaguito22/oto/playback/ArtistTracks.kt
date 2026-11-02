package io.github.iaguito22.oto.playback

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Amplía las canciones destacadas con la selección y los álbumes que YouTube Music ofrece para el artista. */
object ArtistTracks {
    suspend fun more(artist:ArtistItem, featured:List<SongItem>, albums:List<AlbumItem>):List<SongItem> = withContext(Dispatchers.IO) {
        coroutineScope {
            val selection=async {
                val endpoint=artist.playEndpoint ?: artist.shuffleEndpoint
                if(endpoint==null) emptyList() else withTimeoutOrNull(8_000) {
                    YouTube.next(endpoint).getOrNull()?.items.orEmpty().filter { song ->
                        song.artists.any { it.id==artist.id || it.name.equals(artist.title,ignoreCase=true) }
                    }
                }.orEmpty()
            }
            val records=albums.distinctBy{it.browseId}.take(4).map { album -> async {
                withTimeoutOrNull(8_000) { YouTube.album(album.browseId).getOrNull()?.songs.orEmpty() }.orEmpty()
            } }
            val existing=featured.mapTo(HashSet()){it.id}
            (selection.await()+records.awaitAll().flatten()).filter { existing.add(it.id) }.take(75)
        }
    }
}
