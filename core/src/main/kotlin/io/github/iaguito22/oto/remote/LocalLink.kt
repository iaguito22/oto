package io.github.iaguito22.oto.remote

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Qué estado periódico enviar. Antes iba entero cada 500 ms, con la cola completa (miles de canciones): el otro
 * aparato lo descifraba, parseaba y redibujaba dos veces por segundo aunque nada cambiase, y gastaba batería.
 * Ahora: al cambiar algo, al momento; sonando, una vez por segundo (la posición la extrapola quien recibe);
 * en pausa, cada 10 s para mantener la conexión. La cola solo viaja cuando cambia (`queueSame` si no).
 * Los adaptadores reutilizan el mismo JSONArray mientras la cola no cambia: se compara por identidad.
 */
internal class StateOutbox {
    private var lastKey: String? = null
    private var lastQueue: Any? = null
    private var lastAt = Long.MIN_VALUE / 2

    fun next(state: JSONObject, nowMs: Long): JSONObject? {
        val queue = state.opt("queue")
        // Sonando, la posición avanza sola y no cuenta como cambio; en pausa, moverla sí.
        val position = if (state.optBoolean("playing")) state.remove("position") else null
        state.remove("queue")
        val key = state.toString()
        val sameQueue = queue != null && queue === lastQueue
        val changed = key != lastKey || !sameQueue
        val every = if (state.optBoolean("playing")) 1_000L else 10_000L
        if (!changed && nowMs - lastAt < every) return null
        lastKey = key; lastQueue = queue; lastAt = nowMs
        if (position != null) state.put("position", position)
        if (sameQueue) state.put("queueSame", true) else if (queue != null) state.put("queue", queue)
        return state
    }

    companion object {
        /** Recompone el estado recibido con la cola anterior si no ha cambiado. */
        fun receive(body: JSONObject, previous: JSONObject?): JSONObject {
            if (body.optBoolean("queueSame") && !body.has("queue")) previous?.optJSONArray("queue")?.let { body.put("queue", it) }
            body.remove("queueSame")
            return body
        }
    }
}

/** Canal local autenticado. El secreto viaja solo en el QR, nunca por la conexión. */
class LocalLink(
    private val scope: CoroutineScope,
    private val snapshot: suspend () -> JSONObject,
    private val execute: suspend (JSONObject) -> JSONObject,
) : AutoCloseable {
    private val random = SecureRandom()
    private val statusMutable = MutableStateFlow("Sin conexión")
    val status = statusMutable.asStateFlow()
    private val remoteMutable = MutableStateFlow<JSONObject?>(null)
    val remote = remoteMutable.asStateFlow()
    private var listener: ServerSocket? = null
    @Volatile private var connection: Channel? = null
    private var reconnect: Job? = null
    private val requests = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val ids = AtomicLong()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val jobs = mutableListOf<Job>()

    fun listen(secret: String, port: Int = 0): Int {
        check(listener == null)
        val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        listener = server
        jobs += scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += socket
                launch { runConnection(socket, secret, true) }
            }
        }
        return server.localPort
    }

    fun connect(host: String, port: Int, secret: String) {
        reconnect?.cancel()
        connection?.close()
        reconnect = scope.launch(Dispatchers.IO) {
            while (isActive) {
                statusMutable.value = "Conectando…"
                val socket = Socket()
                sockets += socket
                try { socket.connect(InetSocketAddress(host, port), 4000); runConnection(socket, secret, false) }
                catch (_: Exception) { socket.close(); sockets -= socket }
                if (isActive) { statusMutable.value = "Sin conexión · reintentando"; delay(3000) }
            }
        }
    }

    suspend fun command(command: JSONObject): JSONObject {
        val channel = connection ?: error("El otro dispositivo no está conectado")
        val id = ids.incrementAndGet()
        val response = CompletableDeferred<JSONObject>()
        requests[id] = response
        return try {
            withContext(Dispatchers.IO) { channel.send(JSONObject().put("type", "command").put("id", id).put("body", command)) }
            withTimeout(30_000) { response.await() }.also { if (it.has("error")) error(it.getString("error")) }
        } finally { requests.remove(id) }
    }

    private suspend fun runConnection(socket: Socket, secret: String, server: Boolean) = coroutineScope {
        var channel: Channel? = null
        try {
            socket.soTimeout = 30_000; socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
            val own = ByteArray(32).also(random::nextBytes)
            output.writeInt(2); output.write(own); output.flush()
            require(input.readInt() == 2) { "Versión incompatible" }
            val other = ByteArray(32).also(input::readFully)
            val clientNonce = if (server) other else own
            val serverNonce = if (server) own else other
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(Base64.getUrlDecoder().decode(secret), "HmacSHA256")) }
            val key = mac.doFinal(clientNonce + serverNonce)
            channel = Channel(socket, input, output, key, server)
            channel.send(JSONObject().put("type", "hello").put("version", 2))
            require(channel.receive().getString("type") == "hello")
            connection?.close(); connection = channel
            statusMutable.value = "Conectado en la red local"
            val active = channel
            val ticker = launch(Dispatchers.IO) {
                try {
                    val outbox = StateOutbox()
                    while (isActive) {
                        outbox.next(withContext(Dispatchers.Main) { snapshot() }, System.nanoTime() / 1_000_000)?.let { active.send(JSONObject().put("type", "state").put("body", it)) }
                        delay(500)
                    }
                } catch(cancelled: CancellationException) { throw cancelled } catch(error:Exception) { active.close() }
            }
            // El lector nunca escribe respuestas: dos bibliotecas grandes enviadas a la
            // vez pueden llenar ambos buffers TCP y bloquear la conexión en ambos extremos.
            val commands = kotlinx.coroutines.channels.Channel<JSONObject>(64)
            val worker = launch(Dispatchers.IO) {
                try {
                    for (msg in commands) {
                        val result = try { withContext(Dispatchers.Main) { execute(msg.getJSONObject("body")) } }
                            catch (e: Exception) {
                                currentCoroutineContext().ensureActive()
                                JSONObject().put("error", e.message ?: "No se pudo completar la acción")
                            }
                        // Publicar el resultado antes de confirmar: un salto seguido de una
                        // transferencia no debe usar la posición de la actualización anterior.
                        active.send(JSONObject().put("type", "state").put("body", withContext(Dispatchers.Main) { snapshot() }))
                        active.send(JSONObject().put("type", "reply").put("id", msg.getLong("id")).put("body", result))
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { timber.log.Timber.d(e, "Respuesta local interrumpida") }
                finally { active.close() }
            }
            try {
                while (isActive) {
                    val msg = active.receive()
                    when (msg.getString("type")) {
                        "state" -> remoteMutable.value = StateOutbox.receive(msg.getJSONObject("body"), remoteMutable.value)
                        "command" -> check(commands.trySend(msg).isSuccess) { "Demasiadas órdenes pendientes" }
                        "reply" -> requests[msg.getLong("id")]?.complete(msg.getJSONObject("body"))
                    }
                }
            } finally { ticker.cancel(); commands.close(); worker.cancel(); active.close() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            timber.log.Timber.d("Conexión local interrumpida (%s): %s", error::class.simpleName, error.message)
            // Una clave incorrecta, una desconexión o una trama inválida cierran el canal completo.
        } finally {
            channel?.close() ?: socket.close(); sockets -= socket
            if (connection === channel && channel != null) {
                connection = null; remoteMutable.value = null; statusMutable.value = "Sin conexión"
                requests.values.forEach { it.completeExceptionally(IllegalStateException("Conexión interrumpida")) }
            }
        }
    }

    override fun close() {
        reconnect?.cancel(); listener?.close(); connection?.close(); sockets.forEach { runCatching { it.close() } }; jobs.forEach { it.cancel() }
    }

    private class Channel(val socket: Socket, val input: DataInputStream, val output: DataOutputStream, key: ByteArray, val server: Boolean) {
        val key = SecretKeySpec(key, "AES")
        private var sent = 0L; private var received = 0L
        @Synchronized fun send(body: JSONObject) {
            val nonce = java.nio.ByteBuffer.allocate(12).putInt(if (server) 1 else 0).putLong(sent++).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
            val json = body.toString().toByteArray(Charsets.UTF_8)
            require(json.size <= MAX_DECODED)
            val payload = if(json.size > 2048) ByteArrayOutputStream().apply { write(1);GZIPOutputStream(this).use { it.write(json) } }.toByteArray() else byteArrayOf(0) + json
            val bytes = cipher.doFinal(payload)
            require(bytes.size <= MAX_FRAME)
            output.writeInt(bytes.size); output.write(bytes); output.flush()
        }
        fun receive(): JSONObject {
            val length = input.readInt(); require(length in 16..MAX_FRAME)
            val bytes = ByteArray(length).also(input::readFully)
            val nonce = java.nio.ByteBuffer.allocate(12).putInt(if (server) 0 else 1).putLong(received++).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
            val payload = cipher.doFinal(bytes)
            require(payload.isNotEmpty())
            val json = when(payload[0].toInt()) {
                0 -> payload.copyOfRange(1,payload.size)
                1 -> GZIPInputStream(ByteArrayInputStream(payload,1,payload.size-1)).use { it.readNBytes(MAX_DECODED+1) }
                else -> error("Formato de mensaje no compatible")
            }
            require(json.size <= MAX_DECODED)
            return JSONObject(String(json, Charsets.UTF_8))
        }
        fun close() { runCatching { socket.close() } }
    }
    companion object {
        private const val MAX_FRAME = 8 * 1024 * 1024
        private const val MAX_DECODED = 32 * 1024 * 1024
        fun newSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    }
}
