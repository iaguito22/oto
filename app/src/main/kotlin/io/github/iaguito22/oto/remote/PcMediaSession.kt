package io.github.iaguito22.oto.remote

import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.iaguito22.oto.playback.ARTWORK_PX
import io.github.iaguito22.oto.playback.resize
import org.json.JSONObject

/**
 * Lo que suena en el ordenador como un reproductor más del sistema: notificación, pantalla de bloqueo y
 * auriculares. No reproduce nada aquí; cada orden se envía al ordenador con [send] (ver [LinkedPlayback]).
 * Solo publica la canción actual y sus vecinas, no la cola entera, porque el estado llega cada medio segundo.
 */
@UnstableApi
internal class PcPlayer(private val send: (JSONObject) -> Unit) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var remote: JSONObject? = null
    private var window = 0..-1
    private var current = 0

    fun show(state: JSONObject?) { remote = state; invalidateState() }

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_METADATA, COMMAND_GET_TIMELINE,
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        ).build()
        val s = remote ?: return State.Builder().setAvailableCommands(commands).setPlaybackState(STATE_IDLE).build()
        fun JSONObject.text(key: String) = optString(key).takeUnless { it == "null" }.orEmpty()
        val queue = s.optJSONArray("queue")
        val index = s.optInt("index").coerceAtLeast(0)
        val size = queue?.length() ?: 0
        // La canción actual con la anterior y la siguiente: así el sistema ofrece los dos saltos.
        window = if (size == 0) index..index else (index - 1).coerceAtLeast(0)..(index + 1).coerceAtMost(size - 1)
        current = index - window.first
        val loop = s.optBoolean("loop")
        val items = window.map { i ->
            val e = queue?.optJSONObject(i)
            val now = i == index || e == null
            val title = if (now) s.text("title") else e!!.text("title")
            val artist = if (now) s.text("artist") else e!!.text("artist")
            val art = (if (now) s.text("artwork") else e!!.text("artwork")).ifBlank { null }
            val meta = MediaMetadata.Builder().setTitle(title)
                // La pantalla de bloqueo solo enseña título y artista: ahí se dice dónde suena.
                .setArtist(listOf(artist, "en el ordenador").filter { it.isNotBlank() }.joinToString(" · "))
                .setArtworkUri(art?.resize(ARTWORK_PX)?.toUri()).build()
            val id = if (now) s.text("id") else e!!.text("id")
            MediaItemData.Builder("$i:$id").setMediaItem(MediaItem.Builder().setMediaId(id).setMediaMetadata(meta).build())
                .setMediaMetadata(meta).setIsSeekable(true)
                .setDurationUs(if (now) s.optLong("duration").takeIf { it > 0 }?.let { it * 1000 } ?: C.TIME_UNSET else C.TIME_UNSET).build()
        }
        val playing = s.optBoolean("playing")
        val buffering = s.optBoolean("buffering")
        return State.Builder().setAvailableCommands(commands).setPlaylist(items).setCurrentMediaItemIndex(current)
            .setRepeatMode(if (loop) REPEAT_MODE_ALL else REPEAT_MODE_OFF)
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(if (buffering) STATE_BUFFERING else STATE_READY)
            .setContentPositionMs(PositionSupplier.getExtrapolating(s.optLong("position").coerceAtLeast(0), if (playing && !buffering) 1f else 0f))
            .build()
    }

    private fun order(action: String, fill: (JSONObject) -> Unit = {}): ListenableFuture<*> {
        send(JSONObject().put("action", action).also(fill))
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val s = remote ?: return Futures.immediateVoidFuture()
        if (s.optBoolean("playing") == playWhenReady) return Futures.immediateVoidFuture()
        // Se ve al instante; la siguiente actualización del ordenador confirma.
        remote = JSONObject(s.toString()).put("playing", playWhenReady)
        return order("toggle")
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = when {
        seekCommand == COMMAND_SEEK_TO_NEXT || seekCommand == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM || mediaItemIndex > current -> order("next")
        seekCommand == COMMAND_SEEK_TO_PREVIOUS || seekCommand == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM || mediaItemIndex in 0 until current -> order("previous")
        else -> {
            val ms = positionMs.coerceAtLeast(0)
            remote = remote?.let { JSONObject(it.toString()).put("position", ms) }
            order("seek") { it.put("position", ms) }
        }
    }
}
