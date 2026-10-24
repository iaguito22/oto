package io.github.iaguito22.oto.dj

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.importer.Matcher
import io.github.iaguito22.oto.importer.songKey

/** Una canción para el DJ, con todos sus artistas: el primero es el que cuenta. */
data class Track(
    val id: String,
    val title: String,
    val artists: List<String>,
    val artistIds: List<String?> = emptyList(),
    val artwork: String? = null,
    val seconds: Int? = null,
    /** Un vídeo subido por cualquiera (no la canción ni su videoclip oficial): para descubrir, no. */
    val upload: Boolean = false,
) {
    val artist get() = artists.firstOrNull().orEmpty()
    val artistId get() = artistIds.firstOrNull()

    /** La misma canción aunque venga como vídeo o con otro id. */
    val key by lazy { songKey(title, artist) }
    val who by lazy { artistKey(artist) }

    /** El título sin versión: «Canción (Slowed)», «Canción - Remix» y «Canción [Sped Up]» son la misma canción. */
    val base by lazy {
        val dash = title.indexOf(" - ")
        val t = if (dash > 0 && Matcher.norm(title.substring(0, dash)).let { it.isNotEmpty() && who.contains(it) }) title.substring(dash + 3) else title
        Matcher.norm(t.substringBefore(" - ").replace(BRACKETS, " "))
    }

    fun toSong() = SongItem(
        id = id, title = title, thumbnail = artwork.orEmpty(), duration = seconds,
        artists = artists.mapIndexed { i, n -> Artist(n, artistIds.getOrNull(i)) },
    )
}

private val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")

/** Un artista por su nombre: «Bad Bunny», «BAD BUNNY» y «Bad Bunny & Feid» son el mismo. */
fun artistKey(name: String) = Matcher.norm(name.substringBefore(',').substringBefore(" & ").substringBefore(" x "))

fun Heard.toTrack() = Track(id, title, artist.split(", ").filter { it.isNotBlank() }.ifEmpty { listOf(artist) }, listOf(artistId), artwork)

fun SongItem.toTrack() = Track(id, title, artists.map { it.name }, artists.map { it.id }, thumbnail, duration, musicVideoType == MUSIC_VIDEO_TYPE_UGC || isEpisode)
