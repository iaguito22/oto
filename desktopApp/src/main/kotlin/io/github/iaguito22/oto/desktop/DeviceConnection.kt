package io.github.iaguito22.oto.desktop

import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
import io.github.iaguito22.oto.remote.*
import io.github.iaguito22.oto.account.Account
import io.github.iaguito22.oto.account.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.InetAddress

internal class DeviceConnection(val player: DesktopPlayerConnection, private val prefs: Prefs, private val account: Account, parentScope: CoroutineScope) : AutoCloseable {
    private var accountRequest: Job? = null
    @Volatile private var acceptingAccount = false
    private val accountStatusMutable = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val accountStatus = accountStatusMutable.asStateFlow()
    fun requestAccount() {
        accountRequest?.cancel()
        acceptingAccount = true
        accountStatusMutable.value = "Esperando la cuenta del móvil…"
        accountRequest = scope.launch { delay(120_000); acceptingAccount = false; accountStatusMutable.value = "Se agotó el tiempo para recibir la cuenta." }
    }
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val kv = OtoDirs.keyValue("local_link")
    private val identity = kv.getString("identity") ?: java.util.UUID.randomUUID().toString().also { kv.putString("identity", it) }
    private val secret = kv.getString("secret") ?: LocalLink.newSecret().also { kv.putString("secret", it) }
    val adapter = object : LinkPlayer {
        override fun snapshot(): JSONObject {
            val s = player.state.value
            return JSONObject().put("name", "Oto Desktop").put("id", s.id).put("title", s.title).put("artist", s.artist).put("artwork", s.artwork)
                .put("playing", s.playing).put("buffering", s.buffering).put("duration", s.durationMs).put("position", player.position())
                .put("volume",player.volume.value).put("index", s.index).put("shuffle", player.shuffle.value).put("loop", player.loop.value)
                .put("queue", JSONArray(player.queue.value.map { JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("artwork", it.artwork) }))
        }
        override suspend fun like() { val s = player.state.value; s.id?.let { prefs.toggleLike(Heard(it,s.title,s.artist,s.artwork)) } }
        override fun toggle() = player.toggle()
        override fun next() = player.next()
        override fun previous() = player.previous()
        override fun seek(ms: Long) = player.seekTo(ms)
        override fun shuffle(on: Boolean) = player.setShuffle(on)
        override fun loop(on: Boolean) = player.setLoop(on)
        override fun skip(index: Int) = player.skipTo(index)
        override fun move(from: Int, to: Int) = player.move(from, to)
        override fun remove(index: Int) = player.remove(index)
        override fun addToQueue(song: JSONObject, next: Boolean) {
            val track = Heard(song.getString("id"), song.getString("title"), song.optString("artist"), song.optString("artwork").takeUnless { it.isBlank() || it == "null" }).toSong()
            if (next) player.playNext(track) else player.enqueue(track)
        }
        override fun volume(value: Float) { player.adjustVolume(value - player.volume.value) }
        override fun setPlaying(on: Boolean) { if (player.state.value.playing != on) player.toggle() }
        override fun replace(state: JSONObject) {
            val a = state.getJSONArray("queue")
            val songs = (0 until a.length()).map { i -> a.getJSONObject(i).let { Heard(it.getString("id"), it.getString("title"), it.getString("artist"), it.optString("artwork").takeUnless { s -> s.isBlank() || s == "null" }).toSong() } }
            player.prepareTransfer(songs, state.optInt("index").coerceIn(songs.indices), state.optLong("position"), state.optBoolean("shuffle"))
        }
    }
    private val syncIssueMutable = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val syncIssue = syncIssueMutable.asStateFlow()
    private val replica = LibraryReplica(prefs, java.io.File(OtoDirs.filesDir, "library-replica.json"))
    val syncNotice = replica.notice
    private val bridge = LinkedPlayback(adapter)
    val link = LocalLink(scope, { adapter.snapshot().put("libraryHash", replica.digest()) }) { command ->
        when (command.optString("action")) {
            "library" -> replica.export()
            "sendAccount" -> {
                check(acceptingAccount) { "Abre el acceso desde el móvil en Oto Desktop primero" }
                acceptingAccount = false
                accountRequest?.cancel()
                try {
                    val data = command.getJSONObject("session")
                    val cookie = data.getString("cookie")
                    require("SAPISID" in cookie) { "La sesión del móvil no es válida" }
                    account.signIn(Session(cookie, data.optString("visitorData"), data.optString("dataSyncId"), data.optString("authUser", "0")))
                    accountStatusMutable.value = "Cuenta del móvil recibida y conectada."
                } catch (e: Exception) {
                    accountStatusMutable.value = "No se pudo conectar la cuenta del móvil."
                    throw e
                }
                JSONObject().put("ok", true)
            }
            else -> bridge.execute(command)
        }
    }
    private val sync = scope.launch {
        link.remote.collect { state ->
            if (state != null && state.optString("libraryHash") != replica.digest()) {
                runCatching { replica.merge(link.command(JSONObject().put("action", "library"))) }
                    .onSuccess { syncIssueMutable.value = null }.onFailure { syncIssueMutable.value = "No se pudo sincronizar: ${it.message}"; timber.log.Timber.w(it, "No se pudo sincronizar la biblioteca local") }
            } else { syncIssueMutable.value = null }
        }
    }
    val port = runCatching { link.listen(secret, kv.getLong("port", 48731).toInt()) }.getOrElse { link.listen(secret) }.also { kv.putLong("port", it.toLong()) }
    private val discovery = LocalDiscovery(scope).also { it.advertise(identity, port) }
    val host: String = runCatching { DatagramSocket().use { it.connect(InetAddress.getByName("8.8.8.8"), 53); it.localAddress.hostAddress } }.getOrDefault("127.0.0.1")
    val pairing: String get() = JSONObject().put("oto", 1).put("identity",identity).put("host", host).put("port", port).put("secret", secret).toString()
    suspend fun send(action: String) = link.command(JSONObject().put("action", action))
    suspend fun transferThere() {
        val state = adapter.snapshot()
        require(!state.optString("id").isNullOrBlank()) { "Elige una canción antes" }
        adapter.setPlaying(false)
        link.command(JSONObject().put("action", "transfer").put("state", state))
    }
    suspend fun transferHere() {
        val state = link.remote.value ?: error("No hay un dispositivo conectado")
        link.command(JSONObject().put("action", "pause"))
        bridge.execute(JSONObject().put("action", "transfer").put("state", state))
    }
    override fun close() { sync.cancel(); discovery.close(); link.close(); scope.cancel() }
}
