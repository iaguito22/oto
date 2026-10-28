package io.github.iaguito22.oto.desktop

import com.metrolist.innertube.YouTube
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
    var currentToken: String? = null
        private set

    val currentPort: Int? get() = server?.address?.port

    fun getConsoleSnippet(): String? {
        val p = currentPort ?: return null
        val t = currentToken ?: return null
        return """fetch("http://127.0.0.1:$p/session?token=$t",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({cookie:document.cookie,v:window.yt?.config_?.VISITOR_DATA||"",d:window.yt?.config_?.DATASYNC_ID||"",u:String(window.yt?.config_?.SESSION_INDEX||0)})}).then(r=>alert(r.ok?"¡Sesión conectada con Oto!":"Error: "+r.status));"""
    }

    suspend fun signInWithCookie(rawText: String): Result<Unit> = runCatching {
        val text = rawText.trim()
        val cookieStr = when {
            "-H 'cookie:" in text.lowercase() -> {
                Regex("(?i)-H\\s+['\"]cookie:\\s*([^'\"]+)['\"]").find(text)?.groupValues?.get(1) ?: text
            }
            "-b '" in text -> {
                Regex("-b\\s+['\"]([^'\"]+)['\"]").find(text)?.groupValues?.get(1) ?: text
            }
            else -> text
        }
        if ("SAPISID" !in cookieStr) {
            throw IllegalArgumentException("Falta la cookie SAPISID. Copia las cookies desde una pestaña donde tengas la sesión abierta en music.youtube.com.")
        }
        val visitor = YouTube.visitorData ?: ""
        val session = Session(
            cookie = cookieStr,
            visitorData = visitor,
            dataSyncId = "",
            authUser = "0",
        )
        account.signIn(session)
        _state.value = LoginState.Done
    }

    fun start() {
        cancel()
        val token = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        currentToken = token
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/session") { ex -> handle(ex, token) }
        http.start()
        server = http
        _state.value = LoginState.Waiting
        timeout = scope.launch {
            delay(TIMEOUT_MS)
            if (_state.value == LoginState.Waiting) {
                _state.value = LoginState.Failed("Sin respuesta del navegador: pega el comando en la consola (F12) o pega tu cookie directamente.")
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
        currentToken = null
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
