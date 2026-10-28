package io.github.iaguito22.oto.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cliente mínimo del Chrome DevTools Protocol sobre el WebSocket que abre Chrome con
 * `--remote-debugging-port`. Sirve para leer las cookies de la sesión (incluidas las HttpOnly, que una página no
 * puede ver con `document.cookie`) y `yt.config_` sin pedirle nada al usuario.
 */
class CdpClient private constructor() : WebSocket.Listener {
    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val text = StringBuilder()
    private val opened = CompletableDeferred<Unit>()

    @Volatile private var socket: WebSocket? = null

    override fun onOpen(webSocket: WebSocket) {
        socket = webSocket
        opened.complete(Unit)
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        text.append(data)
        if (last) {
            val raw = text.toString()
            text.setLength(0)
            runCatching { dispatch(JSONObject(raw)) }
        }
        webSocket.request(1)
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private fun dispatch(message: JSONObject) {
        val id = message.optLong("id", -1L)
        if (id < 0L) return // Evento, no respuesta.
        val future = pending.remove(id) ?: return
        val error = message.optJSONObject("error")
        if (error != null) {
            future.completeExceptionally(IllegalStateException(error.optString("message", "CDP error")))
        } else {
            future.complete(message.optJSONObject("result") ?: JSONObject())
        }
    }

    suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject {
        opened.await()
        val id = nextId.incrementAndGet()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        val message = JSONObject().put("id", id).put("method", method).put("params", params)
        socket?.sendText(message.toString(), true)?.get(10, TimeUnit.SECONDS)
            ?: throw IllegalStateException("CDP sin conexión")
        return suspendCancellableCoroutine { cont ->
            future.whenComplete { value, error ->
                if (error != null) cont.resumeWithException(error) else cont.resume(value)
            }
            cont.invokeOnCancellation { pending.remove(id) }
        }
    }

    fun close() {
        runCatching { socket?.sendClose(WebSocket.NORMAL_CLOSURE, "") }
        runCatching { socket?.abort() }
        socket = null
    }

    companion object {
        fun connect(webSocketUrl: String): CdpClient {
            val client = CdpClient()
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(webSocketUrl), client)
                .get(15, TimeUnit.SECONDS)
            return client
        }
    }
}
