package io.github.iaguito22.oto.desktop

import io.github.iaguito22.oto.playback.NowPlaying
import io.github.iaguito22.oto.playback.Source
import io.github.iaguito22.oto.remote.RemoteNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Lo del móvil tal como lo pinta el reproductor del ordenador. */
internal data class Mirrored(val now: NowPlaying, val shuffle: Boolean, val loop: Boolean, val source: Source?)

/**
 * Lo que suena en el móvil, cuando el ordenador lo muestra y controla como si sonara aquí (lo mismo que hace el
 * móvil con el ordenador). Las reglas de cuándo seguirlo están en [RemoteNow.follow]: lo local manda en cuanto suena.
 * El estado llega cada medio segundo; la posición se extrapola entre medias para que la barra avance fluida.
 */
internal class RemoteMirror(remote: StateFlow<JSONObject?>, local: StateFlow<NowPlaying>, scope: CoroutineScope) {
    private val stateMutable = MutableStateFlow<Mirrored?>(null)
    val state: StateFlow<Mirrored?> = stateMutable.asStateFlow()
    private val positionMutable = MutableStateFlow(0L)
    val position: StateFlow<Long> = positionMutable.asStateFlow()

    private data class Clock(val base: Long, val at: Long, val running: Boolean, val duration: Long) {
        fun now(): Long {
            val run = if (running) System.nanoTime() / 1_000_000 - at else 0L
            return (base + run).coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        }
    }
    @Volatile private var clock = Clock(0, 0, false, 0)
    private var following = false

    init {
        scope.launch { combine(remote, local, ::update).collect {} }
        scope.launch {
            while (isActive) {
                if (stateMutable.value == null) stateMutable.first { it != null }
                val c = clock
                positionMutable.value = c.now()
                delay(if (c.running) 100 else 250)
            }
        }
    }

    /** Tras un salto en la barra, sin esperar a la siguiente actualización del móvil. */
    fun jump(ms: Long) { clock = clock.copy(base = ms, at = System.nanoTime() / 1_000_000); positionMutable.value = clock.now() }

    private fun update(state: JSONObject?, local: NowPlaying) {
        following = RemoteNow.follow(following, local.id != null, local.playing, RemoteNow.of(state))
        if (!following || state == null) { stateMutable.value = null; return }
        stateMutable.value = parse(state)
        val now = stateMutable.value!!.now
        clock = Clock(state.optLong("position").coerceAtLeast(0), System.nanoTime() / 1_000_000, now.playing && !now.buffering, now.durationMs)
    }

    companion object {
        fun parse(state: JSONObject): Mirrored {
            fun text(key: String) = state.optString(key).takeUnless { it == "null" }.orEmpty()
            val artist = text("artist")
            val now = NowPlaying(
                id = text("id").ifBlank { null }, title = text("title"), artist = artist, artists = listOf(artist to text("artistId").ifBlank { null }),
                artwork = text("artwork").ifBlank { null }, playing = state.optBoolean("playing"), buffering = state.optBoolean("buffering"),
                durationMs = state.optLong("duration").coerceAtLeast(0), index = state.optInt("index").coerceAtLeast(0),
            )
            val source = text("sourceTitle").takeIf { it.isNotBlank() }?.let { Source(text("sourceKind").ifBlank { "Lista" }, it) }
            return Mirrored(now, state.optBoolean("shuffle"), state.optBoolean("loop"), source)
        }
    }
}
