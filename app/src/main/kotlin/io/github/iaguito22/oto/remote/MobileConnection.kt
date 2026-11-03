package io.github.iaguito22.oto.remote

import android.content.Context
import android.media.AudioManager
import io.github.iaguito22.oto.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class MobileConnection(private val app: OtoApp) {
    private val prefs = app.getSharedPreferences("oto_local_link", Context.MODE_PRIVATE)
    var enabled = false
        private set
    private val player get() = app.player
    val adapter = object : LinkPlayer {
        override fun snapshot(): JSONObject {
            val s = player.state.value
            val audio=app.getSystemService(AudioManager::class.java)
            return JSONObject().put("name", "Oto móvil").put("id", s.id).put("title", s.title).put("artist", s.artist).put("artwork", s.artwork)
                .put("playing", s.playing).put("buffering", s.buffering).put("duration", s.durationMs).put("position", player.position())
                .put("volume",audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()/audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)).put("index", s.index).put("shuffle", player.shuffle.value).put("loop", player.loopMode.value != LoopMode.Off)
                .put("queue", JSONArray(player.sessionQueue.value.map { JSONObject().put("id",it.id).put("title",it.title).put("artist",it.artist).put("artwork",it.artwork) }))
        }
        override suspend fun like() { val s = player.state.value; s.id?.let { app.prefs.toggleLike(Heard(it,s.title,s.artist,s.artwork)) } }
        override fun toggle() = player.toggle()
        override fun next() = player.next()
        override fun previous() = player.previous()
        override fun seek(ms: Long) = player.seekTo(ms)
        override fun shuffle(on: Boolean) = player.setShuffle(on)
        override fun loop(on: Boolean) = player.setLoop(if(on) LoopMode.All else LoopMode.Off)
        override fun skip(index: Int) = player.skipTo(index)
        override fun move(from: Int, to: Int) = player.move(from,to)
        override fun remove(index: Int) = player.remove(index)
        override fun addToQueue(song: JSONObject, next: Boolean) {
            val track=Heard(song.getString("id"),song.getString("title"),song.optString("artist"),song.optString("artwork").takeUnless { it.isBlank() || it=="null" }).toSong()
            if(next) player.playNext(track) else player.enqueue(track)
        }
        override fun setPlaying(on: Boolean) { if(player.state.value.playing != on) player.toggle() }
        override fun volume(value: Float) {
            val audio = app.getSystemService(AudioManager::class.java)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*value).toInt(), 0)
        }
        override fun replace(state: JSONObject) {
            val a = state.getJSONArray("queue")
            val songs = (0 until a.length()).map { i -> a.getJSONObject(i).let { Heard(it.getString("id"),it.getString("title"),it.getString("artist"),it.optString("artwork").takeUnless { s -> s.isBlank() || s=="null" }).toSong() } }
            player.prepareTransfer(songs, state.optInt("index").coerceIn(songs.indices), state.optLong("position"), state.optBoolean("shuffle"))
        }
    }
    private val syncIssueMutable = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val syncIssue = syncIssueMutable.asStateFlow()
    private val replica = LibraryReplica(app.prefs, java.io.File(app.filesDir, "library-replica.json"))
    val syncNotice = replica.notice
    private val bridge = LinkedPlayback(adapter)
    val link = LocalLink(app.scope, { adapter.snapshot().put("libraryHash",replica.digest()) }) { command ->
        if(command.optString("action")=="library") replica.export() else bridge.execute(command)
    }
    init {
        app.scope.launch { link.remote.collect { state ->
            if(state!=null && state.optString("libraryHash")!=replica.digest()) runCatching { replica.merge(link.command(JSONObject().put("action","library"))) }
                .onSuccess { syncIssueMutable.value = null }.onFailure { syncIssueMutable.value = "No se pudo sincronizar: ${it.message}"; timber.log.Timber.w(it,"No se pudo sincronizar la biblioteca local") }
        } }
    }
    val canResume get() = prefs.contains("pair")
    fun pair(text: String) {
        val json = JSONObject(text)
        require(json.getInt("oto") == 1)
        require(json.getInt("port") in 1..65535)
        require(java.util.Base64.getUrlDecoder().decode(json.getString("secret")).size == 32)
        val host = json.getString("host")
        require(host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))
        prefs.edit().putString("pair", text).apply()
        androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, ConnectionService::class.java))
    }
    private var discoveryJob: Job? = null
    fun start() {
        val saved = prefs.getString("pair", null) ?: return
        val json = JSONObject(saved)
        enabled = true; player.connect()
        link.connect(json.getString("host"),json.getInt("port"),json.getString("secret"))
        discoveryJob?.cancel()
        discoveryJob = app.scope.launch {
            val discovery=LocalDiscovery(app.scope)
            while(isActive) {
                delay(5000)
                if(link.remote.value==null && json.has("identity")) discovery.find(json.getString("identity"))?.let { (host,port) -> link.connect(host,port,json.getString("secret")) }
            }
        }
    }
    fun disconnect() {
        enabled=false; discoveryJob?.cancel(); prefs.edit().remove("pair").apply(); link.close()
        app.stopService(android.content.Intent(app, ConnectionService::class.java))
    }
    suspend fun send(action: String) = link.command(JSONObject().put("action",action))
    suspend fun addToRemoteQueue(song: Heard) {
        check(link.remote.value != null) { "El ordenador no está conectado" }
        link.command(JSONObject().put("action", "addToQueue").put("next", false)
            .put("song", JSONObject().put("id", song.id).put("title", song.title)
                .put("artist", song.artist).put("artwork", song.artwork)))
    }
    suspend fun sendAccount() {
        val session = app.account.session.value ?: error("Inicia sesión en Oto móvil primero")
        val data = JSONObject().put("cookie", session.cookie).put("visitorData", session.visitorData)
            .put("dataSyncId", session.dataSyncId).put("authUser", session.authUser)
        link.command(JSONObject().put("action", "sendAccount").put("session", data))
    }
    suspend fun transferThere() = withContext(Dispatchers.Main) {
        val state=adapter.snapshot(); require(!state.optString("id").isNullOrBlank()) { "Elige una canción" }
        adapter.setPlaying(false)
        link.command(JSONObject().put("action","transfer").put("state",state))
    }
    suspend fun transferHere() = withContext(Dispatchers.Main) {
        val state=link.remote.value ?: error("No hay un dispositivo conectado")
        link.command(JSONObject().put("action","pause"))
        bridge.execute(JSONObject().put("action","transfer").put("state",state))
    }
}
