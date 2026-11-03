package io.github.iaguito22.oto.desktop

import com.metrolist.innertube.YouTube
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.account.Account
import io.github.iaguito22.oto.account.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.SecureRandom
import java.time.Duration

sealed interface LoginState {
    data object Idle : LoginState

    /** Esperando a que el usuario entre. [automated]: Chrome temporal con CDP (si no, extensión/consola). */
    data class Waiting(val automated: Boolean = false) : LoginState

    data object Done : LoginState
    data class Failed(val why: String) : LoginState
}

/**
 * Inicio de sesión en YouTube Music para escritorio.
 *
 * Lo normal: abre un Chrome aparte con un perfil temporal y lee la sesión por el DevTools Protocol
 * (`Network.getAllCookies` incluye las cookies HttpOnly, y `yt.config_` da el visitorData). Al detectar
 * `music.youtube.com` con SAPISID, guarda la sesión y cierra el Chrome. El usuario no instala nada ni pega comandos.
 *
 * Si no hay Chrome/Chromium, cae al flujo de siempre: servidor en 127.0.0.1 + extensión o comando de consola.
 */
class BrowserLogin(private val account: Account, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private var server: HttpServer? = null
    private var timeout: Job? = null
    private var chromeJob: Job? = null
    private var chrome: Process? = null
    private var chromeProfile: File? = null
    private var cdp: CdpClient? = null

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
        closeChrome()
        _state.value = LoginState.Done
    }

    /** Abre Chrome con un perfil temporal; si no hay Chrome, cae al flujo de extensión/consola. */
    fun start(defaultBrowser: Boolean = false) {
        cancel()
        val chromePath = if (defaultBrowser) null else findChromiumBrowser()
        if (chromePath == null) {
            startBrowserFallback()
            return
        }
        _state.value = LoginState.Waiting(automated = true)
        chromeJob = scope.launch(Dispatchers.IO) {
            try {
                runChrome(chromePath)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                fail(t.message ?: "No se pudo abrir Chrome")
            }
        }
    }

    private fun startBrowserFallback() {
        val token = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        currentToken = token
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/session") { ex -> handle(ex, token) }
        http.start()
        server = http
        _state.value = LoginState.Waiting(automated = false)
        timeout = scope.launch {
            delay(TIMEOUT_MS)
            if (_state.value is LoginState.Waiting) {
                fail("Sin respuesta del navegador: pega el comando en la consola (F12) o pega tu cookie directamente.")
            }
        }
        val url = "https://music.youtube.com/?oto_login=${http.address.port}.$token"
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { fail("No se pudo abrir el navegador. Abre: $url") }
    }

    private suspend fun runChrome(chromePath: String) {
        val dir = Files.createTempDirectory("oto-login-").toFile()
        chromeProfile = dir
        val url = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
        val command = listOf(
            chromePath,
            "--remote-debugging-port=0",
            "--user-data-dir=${dir.absolutePath}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-sync",
            "--disable-background-networking",
            "--disable-component-update",
            "--new-window",
            url,
        )
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        chrome = process

        var port = 0
        var tries = 0
        while (port == 0 && tries < 80) {
            if (!process.isAlive) { fail("El navegador se cerró antes de iniciar sesión."); return }
            val file = File(dir, "DevToolsActivePort")
            if (file.exists()) port = file.readLines().firstOrNull()?.toIntOrNull() ?: 0
            if (port == 0) delay(250)
            tries++
        }
        if (port == 0) {
            fail("Chrome no abrió el depurador. Prueba otra vez o usa el método de la cookie.")
            return
        }

        val page = waitForPage(port)
        if (page == null) {
            fail("No encontré la pestaña de Chrome. Prueba otra vez.")
            return
        }
        val client = CdpClient.connect(page)
        cdp = client
        client.call("Network.enable")
        client.call("Runtime.enable")

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (_state.value !is LoginState.Waiting) return
            if (!process.isAlive) { fail("El navegador se cerró antes de iniciar sesión."); return }
            val info = evaluate(client)
            if (info != null && info.optString("host") == "music.youtube.com" && info.optString("v").isNotEmpty()) {
                val cookies = client.call("Network.getAllCookies").optJSONArray("cookies")
                val cookie = cookieHeader(cookies)
                if ("SAPISID" in cookie) {
                    val session = Session(
                        cookie = cookie,
                        visitorData = info.optString("v"),
                        dataSyncId = info.optString("d").substringBefore("||"),
                        authUser = info.optString("u", "0").filter(Char::isDigit).ifEmpty { "0" },
                    )
                    account.signIn(session)
                    _state.value = LoginState.Done
                    closeChrome()
                    return
                }
            }
            delay(700)
        }
        fail("No se detectó la sesión. ¿Terminaste de entrar en YouTube Music? También puedes pegar tu cookie a mano.")
    }

    private suspend fun evaluate(client: CdpClient): JSONObject? {
        val expression = """(function(){var c=window.yt&&window.yt.config_;return JSON.stringify({host:location.host,v:(c&&c.VISITOR_DATA)||'',d:(c&&c.DATASYNC_ID)||'',u:String((c&&c.SESSION_INDEX)||0)})})()"""
        val result = client.call(
            "Runtime.evaluate",
            JSONObject().put("expression", expression).put("returnByValue", true),
        )
        val value = result.optJSONObject("result")?.optString("value").orEmpty()
        if (value.isEmpty()) return null
        return runCatching { JSONObject(value) }.getOrNull()
    }

    private suspend fun waitForPage(port: Int): String? {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        repeat(30) {
            val body = runCatching {
                http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/list"))
                        .GET().timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body()
            }.getOrNull()
            if (body != null) {
                val targets = runCatching { JSONArray(body) }.getOrNull()
                if (targets != null) {
                    for (i in 0 until targets.length()) {
                        val target = targets.optJSONObject(i) ?: continue
                        if (target.optString("type") == "page" && target.optString("webSocketDebuggerUrl").isNotEmpty()) {
                            return target.getString("webSocketDebuggerUrl")
                        }
                    }
                }
            }
            delay(300)
        }
        return null
    }

    /** Solo las cookies que viajan a YouTube (incluidas las HttpOnly, que `document.cookie` no enseña). */
    private fun cookieHeader(cookies: JSONArray?): String {
        if (cookies == null) return ""
        val values = LinkedHashMap<String, String>()
        for (i in 0 until cookies.length()) {
            val cookie = cookies.optJSONObject(i) ?: continue
            val domain = cookie.optString("domain")
            if (!domain.endsWith("youtube.com", ignoreCase = true)) continue
            values[cookie.optString("name")] = cookie.optString("value")
        }
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun cancel() {
        chromeJob?.cancel()
        chromeJob = null
        timeout?.cancel()
        timeout = null
        closeChrome()
        close()
        currentToken = null
        _state.value = LoginState.Idle
    }

    private fun fail(why: String) {
        _state.value = LoginState.Failed(why)
        closeChrome()
        close()
    }

    private fun closeChrome() {
        runCatching { cdp?.close() }
        cdp = null
        val process = chrome
        chrome = null
        if (process != null) {
            runCatching { process.destroy() }
            runCatching { process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
            if (process.isAlive) runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
        }
        val dir = chromeProfile
        chromeProfile = null
        if (dir != null && !dir.deleteRecursively()) {
            // Chrome tarda un momento en soltar los archivos del perfil.
            scope.launch(Dispatchers.IO) {
                repeat(10) {
                    delay(300)
                    if (!dir.exists() || dir.deleteRecursively()) return@launch
                }
            }
        }
    }

    /** Copia la extensión a una carpeta normal y la abre, para cargarla en el navegador (una sola vez). */
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
