package io.github.iaguito22.oto.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.manualQueueIndices(): Set<Int> = buildSet {
    for (index in 0 until length()) {
        val entry = optJSONObject(index) ?: continue
        if (entry.optBoolean("manual")) add(index)
    }
}

fun JSONObject.transferredRadioSeed(): String? =
    optString("radioSeed").takeUnless { it.isBlank() || it == "null" }

/** De dónde venía la música en el otro dispositivo («Tu lista · rock»), para seguir mostrándolo aquí. */
fun JSONObject.transferredSource(): io.github.iaguito22.oto.playback.Source? {
    fun text(key: String) = optString(key).takeUnless { it.isBlank() || it == "null" }
    val title = text("sourceTitle") ?: return null
    // Las listas se sincronizan con el mismo id: con él, el origen se puede abrir también aquí.
    return io.github.iaguito22.oto.playback.Source(text("sourceKind") ?: "Lista", title, local = text("sourceLocal"))
}

interface LinkPlayer {
    fun snapshot(): JSONObject
    fun toggle()
    fun next()
    fun previous()
    fun seek(ms: Long)
    fun shuffle(on: Boolean)
    fun loop(on: Boolean)
    fun skip(index: Int)
    fun move(from: Int, to: Int)
    fun remove(index: Int)
    /** La canción se envía como {id,title,artist,artwork}; next elige siguiente o final del bloque manual. */
    fun addToQueue(song: JSONObject, next: Boolean)
    fun volume(value: Float)
    suspend fun like()
    fun replace(state: JSONObject)
    fun setPlaying(on: Boolean)
    /** Empieza la mezcla del DJ en este dispositivo. */
    fun dj() {}
}

/** Lo que suena en el otro dispositivo, leído de su estado periódico. */
data class RemoteNow(val id: String, val title: String, val artist: String, val artwork: String?, val playing: Boolean, val buffering: Boolean) {
    companion object {
        fun of(state: JSONObject?): RemoteNow? {
            state ?: return null
            val id = state.optString("id").takeUnless { it.isBlank() || it == "null" } ?: return null
            fun text(key: String) = state.optString(key).takeUnless { it == "null" }.orEmpty()
            return RemoteNow(id, text("title"), text("artist"), text("artwork").ifBlank { null }, state.optBoolean("playing"), state.optBoolean("buffering"))
        }

        /**
         * Si el reproductor local debe mostrar y controlar lo del otro dispositivo: solo si suena allí (o se mandó allí,
         * [previous]). Se mantiene tras pausar allí, para poder reanudarlo; lo local manda en cuanto suena.
         * Antes, sin nada cargado aquí, se mostraba lo de allí aunque estuviera en pausa: al abrir el PC (que no guarda su
         * cola) salía siempre «sonando en el móvil» con la última canción del móvil (Iago, 25/11).
         */
        @Suppress("UNUSED_PARAMETER")
        fun follow(previous: Boolean, localHasSong: Boolean, localPlaying: Boolean, remote: RemoteNow?): Boolean = when {
            remote == null || localPlaying -> false
            remote.playing -> true
            else -> previous
        }
    }
}

/** Las órdenes se ejecutan en el hilo de UI de la plataforma. */
class LinkedPlayback(private val player: LinkPlayer) {
    suspend fun execute(message: JSONObject): JSONObject {
        when(message.getString("action")) {
            "like" -> player.like()
            "toggle" -> player.toggle()
            "next" -> player.next()
            "previous" -> player.previous()
            "seek" -> player.seek(message.getLong("position").coerceAtLeast(0))
            "shuffle" -> player.shuffle(message.getBoolean("value"))
            "loop" -> player.loop(message.getBoolean("value"))
            "skip" -> player.skip(message.getInt("index"))
            "move" -> player.move(message.getInt("from"), message.getInt("to"))
            "remove" -> player.remove(message.getInt("index"))
            "addToQueue" -> {
                val song = message.getJSONObject("song")
                require(song.getString("id").isNotBlank() && song.getString("title").isNotBlank())
                player.addToQueue(song, message.optBoolean("next", false))
            }
            "volume" -> player.volume(message.getDouble("value").toFloat().coerceIn(0f, 1f))
            "pause" -> player.setPlaying(false)
            "dj" -> player.dj()
            "transfer" -> {
                val state = message.getJSONObject("state")
                require(state.getJSONArray("queue").length() in 1..10000)
                player.replace(state)
                withTimeout(12_000) {
                    while (true) {
                        val now = player.snapshot()
                        if (now.optString("id") == state.getString("id") && !now.optBoolean("buffering") && now.optLong("duration") > 0) break
                        delay(50)
                    }
                }
                player.seek(state.optLong("position"))
                player.loop(state.optBoolean("loop"))
                player.setPlaying(state.optBoolean("playing"))
            }
            else -> error("Orden no compatible")
        }
        return JSONObject().put("ok", true)
    }
}
