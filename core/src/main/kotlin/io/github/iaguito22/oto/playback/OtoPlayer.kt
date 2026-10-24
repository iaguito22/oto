package io.github.iaguito22.oto.playback

import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import io.github.iaguito22.oto.LocalPlaylist
import kotlinx.coroutines.flow.StateFlow

/** Lo que la interfaz necesita saber de lo que suena. La posición no va aquí: se lee por fotograma con [OtoPlayer.position]. */
data class NowPlaying(
    val id: String? = null,
    val title: String = "",
    val artist: String = "",
    /** Los artistas uno a uno (nombre e id de YouTube Music, si lo hay). */
    val artists: List<Pair<String, String?>> = emptyList(),
    val album: String? = null,
    val artwork: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val upNext: Int = 0,
    val index: Int = 0,
    val error: String? = null,
)

/**
 * De dónde sale lo que suena: un álbum, una lista o la radio de una canción. [item] (de YouTube) o [local] (el id de
 * una lista tuya) es lo que se abre al pedirlo.
 */
data class Source(val kind: String, val title: String, val item: YTItem? = null, val local: String? = null)

/** Una canción de la cola; [index] es su posición en la lista del reproductor. */
data class QueueEntry(val index: Int, val id: String, val title: String, val artist: String, val artwork: String?, val auto: Boolean = false)

/**
 * Contrato común del reproductor para Android (`PlayerConnection` sobre Media3) y Desktop (`DesktopPlayerConnection`).
 */
interface OtoPlayer {
    val state: StateFlow<NowPlaying>
    val queue: StateFlow<List<QueueEntry>>
    val previousEntry: StateFlow<QueueEntry?>
    val source: StateFlow<Source?>
    val shuffle: StateFlow<Boolean>
    val loop: StateFlow<Boolean>

    fun play(song: SongItem)
    fun playAll(songs: List<SongItem>, start: Int?, from: YTItem? = null)
    fun playLocal(list: LocalPlaylist, start: Int?, newestFirst: Boolean)
    fun playDj(onFail: () -> Unit)
    fun playNext(song: SongItem)
    fun move(from: Int, to: Int)
    fun place(from: Int, after: Int? = null, before: Int? = null)
    fun remove(index: Int)
    fun setShuffle(on: Boolean)
    fun setLoop(on: Boolean)
    fun toggle()
    fun next()
    fun previous()
    fun restarts(): Boolean
    fun seekTo(ms: Long)
    fun skipTo(index: Int)
    fun position(): Long
}
