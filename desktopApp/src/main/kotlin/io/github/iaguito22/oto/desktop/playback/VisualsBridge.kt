package io.github.iaguito22.oto.desktop.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.playback.NowPlaying
import org.json.JSONObject
import timber.log.Timber
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Puente de visuales: deja que programas de este PC (el laboratorio de luces, y a través de él QLC+) sigan lo que
 * suena en Oto. Escucha solo en 127.0.0.1:[PORT] y viene apagado (Ajustes → «Luces y visuales externos»).
 *
 * - `GET /estado`: canción, posición, si suena y la hora de esa posición (para extrapolar entre consultas).
 * - `GET /audio?id=…`: el audio de la canción que suena (el archivo ya descargado), para analizarla entera.
 * - `GET /lab/…`: el laboratorio de luces (su carpeta en este PC), para abrirlo en el navegador con el escenario.
 *
 * Solo contesta a peticiones dirigidas a 127.0.0.1/localhost y a páginas servidas desde este PC.
 */
internal object VisualsBridge {
    const val PORT = 8767
    private const val KEY = "on"
    private val store get() = OtoDirs.keyValue("visuals_bridge")
    private var server: HttpServer? = null
    private var player: DesktopPlayerConnection? = null

    /** Si no se pudo abrir el puerto (otro programa lo usa), el motivo; si no, null. */
    @Volatile var issue: String? = null
        private set

    /** Adónde van las luces: al navegador (verlas en el laboratorio), a QLC+ o a los dos. El laboratorio lo lee de /estado. */
    var output: VisualsOutput
        get() = runCatching { VisualsOutput.valueOf(store.getString("salida") ?: "") }.getOrDefault(VisualsOutput.Navegador)
        set(value) { runCatching { store.putString("salida", value.name) } }

    /** La carpeta del laboratorio de luces en este PC, o null si no hay (entonces Extras no enseña las luces). */
    fun labDir(): File? = visualsLabDir(
        saved = runCatching { store.getString("lab") }.getOrNull()?.let(::File),
        env = System.getenv("OTO_LAB_DIR"),
        dataDir = OtoDirs.filesDir,
    )

    /** Abre el laboratorio en el navegador con el escenario (Martiñán) siguiendo a Oto. Devuelve el problema, si lo hay. */
    fun openLab(): String? {
        if (labDir() == null) return "No encuentro el laboratorio de luces en este PC."
        if (server == null) start()
        if (server == null) return issue ?: "No se pudo abrir el puente de visuales."
        return runCatching { Desktop.getDesktop().browse(URI("http://127.0.0.1:$PORT/lab/index.html?escena=martinan&oto=1")) }
            .exceptionOrNull()?.let { "No se pudo abrir el navegador." }
    }

    var enabled: Boolean
        get() = runCatching { store.getString(KEY) == "1" }.getOrDefault(false)
        set(value) {
            runCatching { store.putString(KEY, if (value) "1" else null) }
            if (value) start() else stop()
        }

    /** Al arrancar Oto: recuerda el reproductor y abre el puente si estaba activado. */
    fun attach(player: DesktopPlayerConnection) {
        this.player = player
        if (enabled) start()
    }

    @Synchronized private fun start() {
        val p = player ?: return
        if (server != null) return
        issue = null
        server = runCatching {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0).apply {
                executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "oto-visuales").apply { isDaemon = true } }
                createContext("/estado") { ex -> guarded(ex) { reply(ex, 200, "application/json; charset=utf-8", visualsStateJson(p.state.value, p.positionMs.value, System.currentTimeMillis(), output).toByteArray()) } }
                createContext("/audio") { ex -> guarded(ex) { sendAudio(ex, p) } }
                createContext("/lab/") { ex -> guarded(ex) { sendLab(ex) } }
                start()
            }
        }.onFailure {
            Timber.w(it, "No se pudo abrir el puente de visuales")
            issue = "No se pudo abrir el puerto $PORT: otro programa lo está usando."
        }.getOrNull()
    }

    @Synchronized fun stop() {
        server?.stop(0)
        server = null
        issue = null
    }

    private fun guarded(ex: HttpExchange, body: () -> Unit) {
        try {
            val origin = ex.requestHeaders.getFirst("Origin")
            if (!visualsHostAllowed(ex.requestHeaders.getFirst("Host")) || !visualsOriginAllowed(origin)) {
                ex.sendResponseHeaders(403, -1); return
            }
            if (origin != null) ex.responseHeaders.add("Access-Control-Allow-Origin", origin)
            ex.responseHeaders.add("Cache-Control", "no-store")
            body()
        } catch (e: Exception) {
            Timber.w(e, "Puente de visuales")
        } finally {
            ex.close()
        }
    }

    private fun reply(ex: HttpExchange, code: Int, type: String, bytes: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sendLab(ex: HttpExchange) {
        val file = labDir()?.let { visualsLabFile(it, ex.requestURI.path.removePrefix("/lab/")) }
        if (file == null) { reply(ex, 404, "text/plain; charset=utf-8", "No está.".toByteArray()); return }
        reply(ex, 200, visualsLabType(file.name), file.readBytes())
    }

    private fun sendAudio(ex: HttpExchange, p: DesktopPlayerConnection) {
        val wanted = ex.requestURI.query?.split('&')?.firstOrNull { it.startsWith("id=") }?.removePrefix("id=")
        val file = p.currentAudioFile()
        if (file == null || (wanted != null && wanted != p.state.value.id)) {
            reply(ex, 409, "text/plain; charset=utf-8", "Esa canción ya no suena o aún se está descargando.".toByteArray())
            return
        }
        ex.responseHeaders.add("Content-Type", visualsAudioType(file.name))
        ex.sendResponseHeaders(200, file.length())
        ex.responseBody.use { out -> file.inputStream().use { it.copyTo(out) } }
    }
}

private val LOCAL_ORIGIN = Regex("""^http://(127\.0\.0\.1|localhost)(:\d{1,5})?$""")
private val LOCAL_HOST = Regex("""^(127\.0\.0\.1|localhost)(:\d{1,5})?$""")

/** Sin cabecera Origin (curl, scripts) o una página servida desde este PC. «null» no: lo mandan iframes de cualquier web. */
internal fun visualsOriginAllowed(origin: String?): Boolean = origin == null || LOCAL_ORIGIN.matches(origin)

/** La petición tiene que ir dirigida a 127.0.0.1 o localhost (si no, podría ser una web que se hace pasar por local). */
internal fun visualsHostAllowed(host: String?): Boolean = host != null && LOCAL_HOST.matches(host)

internal enum class VisualsOutput(val label: String) { Navegador("Navegador"), Qlc("QLC+"), Ambos("Los dos") }

internal fun visualsStateJson(np: NowPlaying, positionMs: Long, nowMs: Long, output: VisualsOutput = VisualsOutput.Navegador): String = JSONObject()
    .put("id", np.id ?: JSONObject.NULL)
    .put("title", np.title)
    .put("artist", np.artist)
    .put("durationMs", np.durationMs)
    .put("positionMs", positionMs)
    .put("playing", np.playing && !np.buffering)
    .put("at", nowMs)
    .put("salida", when (output) { VisualsOutput.Navegador -> "navegador"; VisualsOutput.Qlc -> "qlc"; VisualsOutput.Ambos -> "ambos" })
    .toString()

/**
 * El laboratorio de luces no viene con Oto: es una carpeta con `index.html` que hay que tener en este PC. Se busca,
 * por orden, en la guardada en `visuals_bridge.json` («lab»), en `OTO_LAB_DIR` y en `laboratorio-visual` dentro de
 * los datos de Oto (una copia o un enlace a la carpeta).
 */
internal fun visualsLabDir(saved: File?, env: String?, dataDir: File): File? =
    listOfNotNull(saved, env?.takeIf { it.isNotBlank() }?.let(::File), File(dataDir, "laboratorio-visual"))
        .firstOrNull { File(it, "index.html").isFile }

/** Un archivo dentro de la carpeta del laboratorio; null si no existe o si la ruta intenta salir de ella. */
internal fun visualsLabFile(dir: File, path: String): File? {
    val name = java.net.URLDecoder.decode(path.ifEmpty { "index.html" }, Charsets.UTF_8)
    val base = dir.canonicalFile
    val file = File(base, name).canonicalFile
    return file.takeIf { it.isFile && it.path.startsWith(base.path + File.separator) }
}

internal fun visualsLabType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "html" -> "text/html; charset=utf-8"
    "js" -> "text/javascript; charset=utf-8"
    "css" -> "text/css; charset=utf-8"
    "json" -> "application/json; charset=utf-8"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "svg" -> "image/svg+xml"
    else -> "application/octet-stream"
}

internal fun visualsAudioType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "webm", "opus" -> "audio/webm"
    "mp3" -> "audio/mpeg"
    "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    else -> "audio/mp4"
}
