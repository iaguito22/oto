package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.playback.NowPlaying
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualsBridgeTest {
    @Test fun soloAceptaPaginasDeEstePc() {
        assertTrue(visualsOriginAllowed(null))                       // curl, QLC+ o un script local
        assertFalse(visualsOriginAllowed("null"))                    // iframes aislados de cualquier web lo mandan
        assertTrue(visualsOriginAllowed("http://127.0.0.1:8766"))
        assertTrue(visualsOriginAllowed("http://localhost:8765"))
        assertFalse(visualsOriginAllowed("https://ejemplo.com"))
        assertFalse(visualsOriginAllowed("http://127.0.0.1.ejemplo.com"))
        assertFalse(visualsOriginAllowed("http://localhost.ejemplo.com:8766"))
    }

    @Test fun soloContestaSiLaPeticionVaAEstePc() {               // evita el «DNS rebinding»
        assertTrue(visualsHostAllowed("127.0.0.1:8767"))
        assertTrue(visualsHostAllowed("localhost:8767"))
        assertFalse(visualsHostAllowed("ejemplo.com:8767"))
        assertFalse(visualsHostAllowed(null))
    }

    @Test fun elEstadoLlevaCancionPosicionYHora() {
        val np = NowPlaying(id = "abc", title = "Superestrella", artist = "Aitana", playing = true, durationMs = 183_000)
        val json = JSONObject(visualsStateJson(np, positionMs = 58_220, nowMs = 1_000))
        assertEquals("abc", json.getString("id"))
        assertEquals("Superestrella", json.getString("title"))
        assertEquals("Aitana", json.getString("artist"))
        assertEquals(58_220L, json.getLong("positionMs"))
        assertEquals(183_000L, json.getLong("durationMs"))
        assertTrue(json.getBoolean("playing"))
        assertEquals(1_000L, json.getLong("at"))
    }

    @Test fun dicePorDondeSalenLasLuces() {
        assertEquals("navegador", JSONObject(visualsStateJson(NowPlaying(), 0, 0)).getString("salida"))
        assertEquals("qlc", JSONObject(visualsStateJson(NowPlaying(), 0, 0, VisualsOutput.Qlc)).getString("salida"))
        assertEquals("ambos", JSONObject(visualsStateJson(NowPlaying(), 0, 0, VisualsOutput.Ambos)).getString("salida"))
    }

    @Test fun elLaboratorioNoDejaSalirDeSuCarpeta() {
        val dir = kotlin.io.path.createTempDirectory("lab").toFile()
        java.io.File(dir, "index.html").writeText("<p>lab</p>")
        java.io.File(dir.parentFile, "secreto-${dir.name}.txt").apply { writeText("no") }.deleteOnExit()
        assertEquals("index.html", visualsLabFile(dir, "")?.name)
        assertEquals("index.html", visualsLabFile(dir, "index.html")?.name)
        assertEquals(null, visualsLabFile(dir, "../secreto-${dir.name}.txt"))
        assertEquals(null, visualsLabFile(dir, "%2e%2e/secreto-${dir.name}.txt"))
        assertEquals(null, visualsLabFile(dir, "no-existe.js"))
        dir.deleteRecursively()
    }

    @Test fun sinLaboratorioNoHayLuces() {                      // un PC cualquiera: Extras no enseña las luces
        val data = kotlin.io.path.createTempDirectory("oto").toFile()
        assertEquals(null, visualsLabDir(saved = null, env = null, dataDir = data))
        assertEquals(null, visualsLabDir(saved = java.io.File(data, "no-existe"), env = "", dataDir = data))
        data.deleteRecursively()
    }

    @Test fun elLaboratorioSeBuscaEnLosDatosDeOtoOEnLoGuardado() {
        val data = kotlin.io.path.createTempDirectory("oto").toFile()
        val inData = java.io.File(data, "laboratorio-visual").apply { mkdirs(); java.io.File(this, "index.html").writeText("") }
        assertEquals(inData, visualsLabDir(saved = null, env = null, dataDir = data))
        val saved = kotlin.io.path.createTempDirectory("lab").toFile().apply { java.io.File(this, "index.html").writeText("") }
        assertEquals(saved, visualsLabDir(saved = saved, env = null, dataDir = data))
        val env = kotlin.io.path.createTempDirectory("lab").toFile().apply { java.io.File(this, "index.html").writeText("") }
        assertEquals(env, visualsLabDir(saved = null, env = env.path, dataDir = data))
        listOf(data, saved, env).forEach { it.deleteRecursively() }
    }

    @Test fun sinCancionNoHayId() {
        val json = JSONObject(visualsStateJson(NowPlaying(), positionMs = 0, nowMs = 5))
        assertTrue(json.isNull("id"))
        assertFalse(json.getBoolean("playing"))
    }

    @Test fun tipoDeAudioSegunElArchivo() {
        assertEquals("audio/webm", visualsAudioType("x.webm"))
        assertEquals("audio/mp4", visualsAudioType("x.m4a"))
        assertEquals("audio/mpeg", visualsAudioType("x.mp3"))
    }
}
