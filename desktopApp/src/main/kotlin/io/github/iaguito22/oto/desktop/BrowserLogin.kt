package io.github.iaguito22.oto.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.account.Account
import io.github.iaguito22.oto.account.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom

sealed interface LoginState {
    data object Idle : LoginState
    data object Waiting : LoginState
    data object Done : LoginState
    data class Failed(val why: String) : LoginState
}

/**
 * Inicio de sesion rapido: abre music.youtube.com en tu navegador y una extension minima (carpeta
 * `oto-login-extension`, se instala una vez) manda tu sesion a este servidor, que solo escucha en 127.0.0.1,
 * acepta una unica peticion con el token de esta vez y se cierra.
 */
class BrowserLogin(private val account: Account, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private var server: HttpServer? = null
    private var timeout: Job? = null

    fun start() {
        cancel()
        val token = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/session") { ex -> handle(ex, token) }
        http.start()
        server = http
        _state.value = LoginState.Waiting
        timeout = scope.launch {
            delay(TIMEOUT_MS)
            if (_state.value == LoginState.Waiting) {
                _state.value = LoginState.Failed("Sin respuesta del navegador: instala la extension de Oto y vuelve a intentarlo")
                close()
            }
        }
        val url = "https://music.youtube.com/?oto_login=${http.address.port}.$token"
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { _state.value = LoginState.Failed("No se pudo abrir el navegador. Abre: $url") }
    }

    fun cancel() {
        timeout?.cancel()
        close()
        _state.value = LoginState.Idle
    }

    /** Copia la extension a una carpeta normal y la abre, para cargarla en el navegador (una sola vez). */
    fun extractExtension(): File {
        val dir = File(OtoDirs.filesDir, "oto-login-extension").apply { mkdirs() }
        for (name in listOf("manifest.json", "content.js", "page.js", "background.js")) {
            val src = javaClass.getResourceAsStream("/oto-login-extension/$name") ?: continue
            src.use { File(dir, name).writeBytes(it.readBytes()) }
        }
        runCatching { Desktop.getDesktop().open(dir) }
        return dir
    }

    private fun handle(ex: HttpExchange, token: String) {
        try {
            val ok = ex.requestMethod == "POST" && ex.requestURI.query?.contains("token=$token") == true
            if (!ok) return reply(ex, 403)
            val body = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val cookie = body.getString("cookie")
            val visitor = body.getString("v")
            if ("SAPISID" !in cookie || visitor.isEmpty()) return reply(ex, 422)
            val session = Session(
                cookie = cookie,
                visitorData = visitor,
                dataSyncId = body.optString("d").substringBefore("||"),
                authUser = body.optString("u", "0"),
            )
            reply(ex, 200)
            timeout?.cancel()
            close()
            scope.launch {
                runCatching { account.signIn(session) }
                    .onSuccess { _state.value = LoginState.Done }
                    .onFailure { _state.value = LoginState.Failed(it.message ?: "No se pudo iniciar sesion") }
            }
        } catch (e: Exception) {
            reply(ex, 400)
        }
    }

    private fun reply(ex: HttpExchange, code: Int) {
        ex.sendResponseHeaders(code, -1)
        ex.close()
    }

    private fun close() {
        server?.stop(0)
        server = null
    }

    private companion object {
        const val TIMEOUT_MS = 5 * 60 * 1000L
    }
}
