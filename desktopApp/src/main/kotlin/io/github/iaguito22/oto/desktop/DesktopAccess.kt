package io.github.iaguito22.oto.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID

/** Atajo del compositor: no captura el teclado mientras Oto está oculto. */
internal class DesktopAccess(shortcut: String, toggle: (Boolean) -> Unit) : AutoCloseable {
    private val token = UUID.randomUUID().toString()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var bound = false
    init {
        server.createContext("/$token") { exchange ->
            toggle(exchange.requestURI.path.endsWith("/show"))
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        require(shortcut.matches(Regex("[A-Z0-9 +]+")))
        server.start()
        io.github.iaguito22.oto.OtoDirs.keyValue("desktop_access").putString("url", "http://127.0.0.1:${server.address.port}/$token/show")
        if (System.getenv("HYPRLAND_INSTANCE_SIGNATURE") != null) {
            val bind = "if _G.oto_access_bind then _G.oto_access_bind:unbind() end; _G.oto_access_bind = hl.bind(\"$shortcut\", hl.dsp.exec_cmd(\"curl --silent --max-time 2 http://127.0.0.1:${server.address.port}/$token\"))"
            bound = runCatching { ProcessBuilder("hyprctl", "eval", bind).start().waitFor() == 0 }.getOrDefault(false)
        }
    }
    override fun close() {
        if (bound) runCatching { ProcessBuilder("hyprctl", "eval", "if _G.oto_access_bind then _G.oto_access_bind:unbind(); _G.oto_access_bind = nil end").start().waitFor() }
        server.stop(0)
    }
}
