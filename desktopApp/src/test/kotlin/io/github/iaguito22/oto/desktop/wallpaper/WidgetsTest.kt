package io.github.iaguito22.oto.desktop.wallpaper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.math.hypot

/** La hora y los controles del fondo vivo: apagados por defecto, bien colocados, que se dibujan y que los clics van donde toca. */
class WidgetsTest {
    @Test fun apagadosPorDefecto() {
        val p = WallpaperSettings()
        assertFalse(p.reloj); assertFalse(p.controles)
        // al encenderlos: el día en grande y la tarjeta compacta (Iago, 24/11)
        assertEquals("dia", p.relojEstilo); assertEquals("compacta", p.ctlEstilo)
        assertNull(geometry(1920f, 1080f, 1f, p).clock); assertNull(geometry(1920f, 1080f, 1f, p).ctl)
    }

    @Test fun laHoraConSuFormato() {
        val st = WidgetState(); val t = LocalDateTime.of(2026, 11, 24, 9, 5, 7)
        st.tick(WallpaperSettings(), t); assertEquals("09:05", st.time); assertEquals("", st.suffix); assertEquals("martes, 24 de noviembre", st.date); assertEquals("MARTES", st.day); assertEquals("24 NOVIEMBRE, 2026", st.dateLine)
        st.tick(WallpaperSettings(hora24 = false, segundos = true), t); assertEquals("9:05:07", st.time); assertEquals("a. m.", st.suffix)
        st.tick(WallpaperSettings(hora24 = false), t.withHour(0)); assertEquals("12:05", st.time)
        st.tick(WallpaperSettings(hora24 = false), t.withHour(21)); assertEquals("9:05", st.time); assertEquals("p. m.", st.suffix)
        assertEquals("3:07", fmtTime(187_400L))
    }

    @Test fun laHoraEnIngles() {
        val st = WidgetState(); val t = LocalDateTime.of(2026, 11, 24, 21, 5, 7)
        assertEquals("es", WallpaperSettings().relojIdioma)
        st.tick(WallpaperSettings(relojIdioma = "en"), t)
        assertEquals("Tuesday, November 24", st.date); assertEquals("TUESDAY", st.day); assertEquals("24 NOVEMBER, 2026", st.dateLine)
        st.tick(WallpaperSettings(relojIdioma = "en", hora24 = false), t); assertEquals("9:05", st.time); assertEquals("PM", st.suffix)
    }

    /** En cualquier sitio y estilo: dentro de la pantalla, sin tocar el círculo (los sitios junto a él) ni la letra. */
    @Test fun nuncaPisanElCirculoNiLaLetra() {
        val sitios = listOf("sobre", "bajo", "izquierda", "derecha", "centro", "ai", "ad", "bi", "bd")
        for (estilo in listOf("anillo", "malla", "barras")) for (tam in listOf(.08f, .11f, .16f)) for (rp in sitios) for (cp in sitios) for (ce in listOf("compacta", "pildora", "tarjeta", "botones", "vinilo")) {
            val p = WallpaperSettings(estilo = estilo, tam = tam, reloj = true, relojPos = rp, controles = true, ctlPos = cp, ctlEstilo = ce, ctlVolumen = true, ctlAleatorio = true)
            val g = geometry(1920f, 1080f, 1f, p)
            val what = "$estilo $tam reloj $rp controles $cp $ce"
            for ((name, r, pos) in listOf(Triple("reloj", g.clock!!, rp), Triple("controles", g.ctl!!, cp))) {
                assertTrue("$what: $name fuera de la pantalla $r", r.left >= 0f && r.top >= 0f && r.right <= g.W && r.bottom <= g.H)
                if (pos in listOf("sobre", "bajo", "izquierda", "derecha", "centro") && !(name == "controles" && ce == "vinilo")) {
                    val nx = g.cx.coerceIn(r.left, r.right); val ny = g.cy.coerceIn(r.top, r.bottom)
                    assertTrue("$what: $name toca el círculo", hypot(nx - g.cx, ny - g.cy) >= g.ex - .5f)
                }
                for (z in g.zones) assertFalse("$what: la letra (${z.id}) pisa $name", Rect(z.x0, z.y0, z.x1, z.y1).overlaps(r))
            }
            if (rp == cp) assertFalse("$what: hora y controles se pisan", g.clock!!.overlaps(g.ctl!!))
        }
    }

    /** Sin visualizador, la hora y los controles quedan en el centro de la pantalla, uno encima del otro (Iago, 24/11). */
    @Test fun sinVisualizador_laHoraYLosControlesVanAlCentro() {
        for ((rp, cp) in listOf("sobre" to "bajo", "centro" to "centro")) {
            val p = WallpaperSettings(visualizador = false, reloj = true, relojPos = rp, controles = true, ctlPos = cp)
            val g = geometry(1920f, 1080f, 1f, p)
            val c = g.clock!!; val k = g.ctl!!; val what = "$rp/$cp: hora $c, controles $k"
            assertEquals(what, 960f, c.center.x, 1f); assertEquals(what, 960f, k.center.x, 1f)
            assertTrue(what, c.bottom <= k.top)
            assertTrue(what, c.bottom <= 540f + 1f && k.top >= 540f - 1f || rp == "centro")
            if (rp == "centro") assertEquals(what, 540f, (c.top + k.bottom) / 2, 1f)
            for (z in g.zones) assertFalse("$what: la letra (${z.id}) los pisa", Rect(z.x0, z.y0, z.x1, z.y1).overlaps(c) || Rect(z.x0, z.y0, z.x1, z.y1).overlaps(k))
        }
    }

    /** «En el centro» con el visualizador allí: la hora encima del círculo y los controles debajo. */
    @Test fun enElCentroConVisualizador_laHoraEncima() {
        val g = geometry(1280f, 720f, 1f, WallpaperSettings(reloj = true, relojPos = "centro", controles = true, ctlPos = "centro"))
        assertTrue("hora ${g.clock}", g.clock!!.bottom <= g.cy); assertTrue("controles ${g.ctl}", g.ctl!!.top >= g.cy)
    }

    /**
     * Con el fondo vivo apagado no hay visualizador: la hora y los controles van al centro, uno encima del otro, y no
     * alrededor de un círculo que no se ve (antes cada uno se colocaba solo, con el hueco del círculo en medio).
     */
    @Test fun sinFondoVivo_laHoraYLosControlesJuntosEnElCentro() {
        val g = widgetOnlyGeometry(1920f, 1080f, 1f, WallpaperSettings(reloj = true, controles = true))
        val c = g.clock!!; val k = g.ctl!!
        assertEquals(960f, c.center.x, 1f); assertEquals(960f, k.center.x, 1f)
        assertTrue("hora $c controles $k", c.bottom <= k.top && k.top - c.bottom < 1080f * .06f)
    }

    /** Solo la hora en el centro: justo en medio de la pantalla. */
    @Test fun laHoraSolaEnElCentro() {
        val g = geometry(1920f, 1080f, 1f, WallpaperSettings(visualizador = false, reloj = true, relojPos = "centro"))
        assertEquals(960f, g.clock!!.center.x, 1f); assertEquals(540f, g.clock!!.center.y, 1f)
    }

    /** Con la hora y los controles encima, la letra se queda sitio: no desaparece por quitar el visualizador. */
    @Test fun sinVisualizador_laLetraTieneSitio() {
        val g = geometry(1920f, 1080f, 1f, WallpaperSettings(visualizador = false, reloj = true, controles = true))
        assertTrue("sin zonas: ${g.zones}", lyricCandidates(g.zones).isNotEmpty())
    }

    /** Con la hora encima y el vinilo abajo a la izquierda, la letra también sale arriba a la izquierda (Iago, 24/11). */
    @Test fun conElVinilo_laLetraTambienVaALaIzquierda() {
        val p = WallpaperSettings(reloj = true, controles = true, ctlEstilo = "vinilo")
        val g = geometry(1920f, 1080f, 1.25f, p)
        val left = g.zones.find { it.id == "izquierda" }
        assertTrue("sin zona izquierda: ${g.zones}", left != null)
        assertTrue("la izquierda no es candidata: ${g.zones}", left in lyricCandidates(g.zones))
        assertFalse(Rect(left!!.x0, left.y0, left.x1, left.y1).overlaps(g.ctl!!))
    }

    /** Solo la hora y el visualizador: la letra va a los lados, como antes; no a la franja estrecha de debajo (Iago, 24/11). */
    @Test fun soloLaHora_laLetraComoAntes() {
        val g = geometry(1920f, 1080f, 1.25f, WallpaperSettings(reloj = true))
        val ids = lyricCandidates(g.zones).map { it.id }
        assertTrue("$ids", "izquierda" in ids && "derecha" in ids)
        assertFalse("$ids", "abajo" in ids)
    }

    @Test fun losClicsVanAlBotonQueSePulsoYSeSuelta() {
        val hits = listOf(Hit("play", Rect(0f, 0f, 40f, 40f)), Hit("next", Rect(50f, 0f, 90f, 40f)), Hit("seek", Rect(0f, 50f, 200f, 60f)), Hit("vol", Rect(0f, 70f, 100f, 80f)))
        val inp = WidgetInput()
        assertNull(inp.step(Offset(20f, 20f), false, hits))
        assertNull(inp.step(Offset(20f, 20f), true, hits)); assertEquals("play", inp.pressed)
        assertEquals("play" to .5f, inp.step(Offset(20f, 20f), false, hits))
        // pulsar en uno y soltar en otro: nada
        inp.step(Offset(20f, 20f), true, hits); assertNull(inp.step(Offset(70f, 20f), false, hits))
        // la barra: salta a donde se suelta
        inp.step(Offset(50f, 55f), true, hits); assertEquals("seek" to .25f, inp.step(Offset(50f, 55f), false, hits))
        // el volumen sigue al ratón mientras está pulsado, aunque se salga de la barra
        assertEquals("vol" to .3f, inp.step(Offset(30f, 75f), true, hits))
        assertEquals("vol" to 1f, inp.step(Offset(150f, 120f), true, hits))
        assertNull(inp.step(Offset(150f, 120f), false, hits))
        // fuera del escritorio (otra ventana delante): nada
        assertNull(inp.step(null, true, hits)); assertNull(inp.step(Offset(20f, 20f), false, hits))
    }

    /** Cada estilo se dibuja y deja sus botones donde se pueden pulsar; capturas en build/fondo-widgets. */
    @Test fun seDibujanConSusBotones() {
        val out = File("build/fondo-widgets").apply { mkdirs() }
        val casos = mapOf(
            "defecto" to WallpaperSettings(reloj = true, controles = true),
            "pildora" to WallpaperSettings(reloj = true, relojEstilo = "fino", controles = true, ctlEstilo = "pildora"),
            "vinilo" to WallpaperSettings(reloj = true, relojPos = "ad", controles = true, ctlEstilo = "vinilo", ctlAleatorio = true),
            "tarjeta" to WallpaperSettings(reloj = true, relojEstilo = "grueso", relojPos = "ad", controles = true, ctlEstilo = "tarjeta", ctlPos = "derecha", ctlVolumen = true, ctlAleatorio = true, ctlRepetir = true),
            "botones" to WallpaperSettings(reloj = true, relojEstilo = "digital", segundos = true, controles = true, ctlEstilo = "botones", ctlVolumen = true, relojPos = "bajo"),
            "contorno" to WallpaperSettings(reloj = true, relojEstilo = "contorno", hora24 = false, relojPos = "izquierda", controles = true, ctlPos = "bd", ctlColor = "blanco", ctlTitulo = false),
            "sin-visualizador" to WallpaperSettings(visualizador = false, reloj = true, controles = true),
            "centro" to WallpaperSettings(reloj = true, relojPos = "centro", controles = true, ctlPos = "centro"),
        )
        for ((name, p) in casos) {
            val sp = Spectrum(); val sc = WallpaperScene().apply { audio = sp; this.p = p; playing = true }
            sc.ensureGeometry(1280f, 720f, 1f); repeat(10) { sp.update(false, 1 / 60f, .5f); sc.step(1 / 60f) }
            val st = WidgetState().apply {
                tick(p, LocalDateTime.of(2026, 11, 24, 21, 47, 12)); title = "Una canción con un título bastante largo"; artist = "Artista"; hasSong = true
                playing = true; posMs = 83_000; durMs = 214_000; liked = true; shuffle = true; loop = 1; volume = .7f; hover = "next"; spin = 1.2f
                queue = List(3) { QItem(it + 4, "Siguiente canción ${it + 1}", "Otro artista", null) }
            }
            val scene = ImageComposeScene(1280, 720, Density(1f)) {
                val tm = rememberTextMeasurer()
                val ic = WidgetIcons(rememberVectorPainter(Icons.Rounded.SkipPrevious), rememberVectorPainter(Icons.Rounded.PlayArrow), rememberVectorPainter(Icons.Rounded.Pause),
                    rememberVectorPainter(Icons.Rounded.SkipNext), rememberVectorPainter(Icons.Rounded.FavoriteBorder), rememberVectorPainter(Icons.Rounded.Favorite),
                    rememberVectorPainter(Icons.Rounded.Shuffle), rememberVectorPainter(Icons.Rounded.Repeat), rememberVectorPainter(Icons.Rounded.RepeatOne),
                    rememberVectorPainter(Icons.AutoMirrored.Rounded.VolumeUp))
                Canvas(Modifier.fillMaxSize().background(Color(0xFF2A3550))) { drawViz(sc, false); drawFront(sc); drawClock(sc, sc.G!!, tm, st); drawControls(sc, sc.G!!, tm, st, ic) }
            }
            File(out, "$name.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes); scene.close()
            val ids = st.hits.map { it.id }
            assertTrue("$name: $ids", ids.containsAll(listOf("prev", "play", "next", "like", "seek")))
            if (p.ctlVolumen && p.ctlEstilo != "vinilo") assertTrue("$name: $ids", "vol" in ids)
            if (p.ctlEstilo == "vinilo") assertTrue("$name: la cola $ids", ids.containsAll(listOf("q:4", "q:5", "q:6")))
            val ctl = sc.G!!.ctl!!
            for (h in st.hits) assertTrue("$name: ${h.id} fuera de los controles ${h.rect} $ctl", ctl.inflate(12f).contains(h.rect.center))
        }
    }
}
