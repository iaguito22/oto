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
    // La misma cola, el mismo JSONArray: el enlace solo la reenvía cuando cambia (ver StateOutbox).
    private var queueCache: Pair<Any, JSONArray>? = null
    private fun queueJson(queue: List<io.github.iaguito22.oto.playback.QueueEntry>): JSONArray =
        queueCache?.takeIf { it.first === queue }?.second
            ?: JSONArray(queue.map { JSONObject().put("id",it.id).put("title",it.title).put("artist",it.artist).put("artwork",it.artwork) }).also { queueCache = queue to it }
    // Siempre lo de este teléfono, aunque la interfaz esté mostrando el ordenador (ver PlayerConnection.Mirror).
    val adapter = object : LinkPlayer {
        override fun snapshot(): JSONObject {
            val s = player.localState.value
            val audio=app.getSystemService(AudioManager::class.java)
            val position = player.here { player.position() }
            return JSONObject().put("name", "Oto móvil").put("id", s.id).put("title", s.title).put("artist", s.artist).put("artwork", s.artwork)
                .put("playing", s.playing).put("buffering", s.buffering).put("duration", s.durationMs).put("position", position)
                .put("volume",audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()/audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)).put("index", s.index).put("shuffle", player.localShuffle.value).put("loop", player.localLoopMode.value != LoopMode.Off)
                .put("queue", queueJson(player.sessionQueue.value))
                .put("sourceKind", player.localSource.value?.kind).put("sourceTitle", player.localSource.value?.title)
        }
        override suspend fun like() { val s = player.localState.value; s.id?.let { app.prefs.toggleLike(Heard(it,s.title,s.artist,s.artwork)) } }
        override fun toggle() = player.here { player.toggle() }
        override fun next() = player.here { player.next() }
        override fun previous() = player.here { player.previous() }
        override fun seek(ms: Long) = player.here { player.seekTo(ms) }
        override fun shuffle(on: Boolean) = player.here { player.setShuffle(on) }
        override fun loop(on: Boolean) = player.here { player.setLoop(if(on) LoopMode.All else LoopMode.Off) }
        override fun skip(index: Int) = player.here { player.skipTo(index) }
        override fun move(from: Int, to: Int) = player.here { player.move(from,to) }
        override fun remove(index: Int) = player.here { player.remove(index) }
        override fun dj() = player.here { player.playDj {} }
        override fun addToQueue(song: JSONObject, next: Boolean) {
            val track=Heard(song.getString("id"),song.getString("title"),song.optString("artist"),song.optString("artwork").takeUnless { it.isBlank() || it=="null" }).toSong()
            player.here { if(next) player.playNext(track) else player.enqueue(track) }
        }
        override fun setPlaying(on: Boolean) { if(player.localState.value.playing != on) player.here { player.toggle() } }
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
    private val followingMutable = kotlinx.coroutines.flow.MutableStateFlow<RemoteNow?>(null)
    /** Lo del ordenador cuando el teléfono lo está mostrando y controlando como si sonara aquí. */
    val following = followingMutable.asStateFlow()
    private val mirror = object : io.github.iaguito22.oto.playback.PlayerConnection.Mirror {
        private var last = 0L
        override fun send(action: String, fill: (JSONObject) -> Unit) {
            app.scope.launch { runCatching { link.command(JSONObject().put("action", action).also(fill)) }.onFailure { timber.log.Timber.w(it, "No se pudo enviar %s al ordenador", action) } }
        }
        override fun handOff(state: JSONObject) {
            // La última manda: si se ponen dos seguidas, no importa en qué orden contesta el ordenador.
            val stamp = ++last
            app.scope.launch { runCatching { if (stamp == last) link.command(JSONObject().put("action", "transfer").put("state", state)) }.onFailure { timber.log.Timber.w(it, "No se pudo pasar la canción al ordenador") } }
        }
    }
    private fun followRemote(state: JSONObject?) {
        val now = RemoteNow.of(state)
        val local = player.localState.value
        val follow = RemoteNow.follow(followingMutable.value != null, local.id != null, local.playing, now)
        if (follow && state != null) { player.showRemote(state, mirror); followingMutable.value = now }
        else { player.hideRemote(); followingMutable.value = null }
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
        // En el hilo de la interfaz, como todo lo del reproductor. Sonar aquí (auriculares, notificación, «Traer aquí») lo suelta.
        app.scope.launch(Dispatchers.Main) {
            kotlinx.coroutines.flow.combine(link.remote, player.localState) { state, _ -> state }.collect { followRemote(it) }
        }
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
        // Traída aquí, aunque llegue en pausa, la gota vuelve a ser de este teléfono.
        followingMutable.value = null; player.hideRemote()
    }
}
