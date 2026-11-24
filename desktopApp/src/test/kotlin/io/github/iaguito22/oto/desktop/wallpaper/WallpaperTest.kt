package io.github.iaguito22.oto.desktop.wallpaper

import io.github.iaguito22.oto.desktop.playback.AudioTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

class WallpaperTest {
    @Test fun ajustesPorDefectoSonLosElegidos() {
        val p = WallpaperSettings()
        assertEquals("anillo", p.estilo); assertEquals(false, p.particulas); assertEquals(120, p.fps); assertEquals(.85f, p.aro); assertEquals("actual", p.fondo)
        assertEquals(0f, p.oscurecer); assertEquals(.5f, p.sombra); assertEquals("caratula", p.dentro)
    }

    @Test fun ajustesSeGuardanYSeLeen() {
        val p = WallpaperSettings(estilo = "onda", aro = 1.3f, letraPos = "izquierda", fps = 30)
        assertEquals(p, WallpaperSettings.decode(WallpaperSettings.encode(p)))
        // claves desconocidas o que faltan no rompen nada
        assertEquals(WallpaperSettings(tam = .14f), WallpaperSettings.decode("""{"tam":0.14,"algoNuevo":true}"""))
        assertEquals(WallpaperSettings(), WallpaperSettings.decode("esto no es json"))
        assertEquals(WallpaperSettings(), WallpaperSettings.decode(null))
    }

    @Test fun loGuardadoSePoneAlDiaUnaVez() {
        val viejo = WallpaperSettings(estilo = "malla", particulas = true, fps = 60, tam = .14f)
        val v1 = migrateSettings(viejo, 1)
        assertEquals("anillo", v1.estilo); assertEquals(false, v1.particulas); assertEquals(120, v1.fps); assertEquals(.14f, v1.tam)
        // desde v2 solo cambia los fps; lo elegido a propósito (24 o sin límite) se respeta
        assertEquals(WallpaperSettings(estilo = "malla", particulas = true, fps = 120), migrateSettings(WallpaperSettings(estilo = "malla", particulas = true, fps = 30), 2))
        assertEquals(24, migrateSettings(WallpaperSettings(fps = 24), 2).fps)
        assertEquals(viejo, migrateSettings(viejo, 3))
    }

    @Test fun estilosRapidos() {
        val club = WallpaperSettings.presets.getValue("Club")(WallpaperSettings())
        assertTrue(club.laseres); assertEquals("electronica", club.estiloLetra)
        assertEquals(WallpaperSettings(), WallpaperSettings.presets.getValue("Por defecto")(club))
    }

    /** La letra solo puede ir en zonas que no tocan el círculo prohibido, en cualquier posición y estilo. */
    @Test fun zonasDeLetraNuncaTocanElVisualizador() {
        for (estilo in EXT.keys) for (pos in listOf("tl", "tc", "tr", "ml", "mc", "mr", "bl", "bc", "br")) for (tam in listOf(.06f, .11f, .2f)) {
            val g = geometry(1920f, 1080f, 1f, WallpaperSettings(estilo = estilo, pos = pos, tam = tam))
            for (z in g.zones) {
                val nx = g.cx.coerceIn(z.x0, z.x1); val ny = g.cy.coerceIn(z.y0, z.y1)
                assertTrue("$estilo $pos $tam ${z.id}", hypot(nx - g.cx, ny - g.cy) >= g.ex - .5f)
                assertTrue(z.y1 <= g.barsTop)
            }
        }
    }

    @Test fun espectroVeLosGravesYLosAgudosDondeTocan() {
        val s = Spectrum()
        fun tone(hz: Double) { for (i in s.samples.indices) s.samples[i] = (.5 * sin(2 * PI * hz * i / 48_000)).toFloat() }
        tone(60.0); repeat(20) { s.update(true, 1 / 60f, .5f) }
        val bass = (0 until Spectrum.BASS_BANDS).maxOf { s.cur[it] }; val high = (70 until Spectrum.NBAND).maxOf { s.cur[it] }
        assertTrue("graves $bass agudos $high", bass > .5f && high < .1f)
        tone(6000.0); repeat(40) { s.update(true, 1 / 60f, .5f) }
        val bass2 = (0 until Spectrum.BASS_BANDS).maxOf { s.cur[it] }; val high2 = (Spectrum.bandOf(4500.0) until Spectrum.bandOf(8000.0)).maxOf { s.cur[it] }
        assertTrue("graves $bass2 agudos $high2", high2 > .5f && bass2 < .1f)
        repeat(150) { s.update(false, 1 / 60f, .5f) }
        assertTrue(s.cur.all { it < .02f })
    }

    /** Una nota de bajo enciende pocas barras, y dos notas distintas (La1 y Mi2) se ven en sitios distintos. */
    @Test fun unaNotaDeBajoEnciendePocasBarras() {
        fun settle(hz: Double): Spectrum {
            val s = Spectrum()
            for (i in s.samples.indices) s.samples[i] = (.5 * sin(2 * PI * hz * i / 48_000)).toFloat()
            repeat(30) { s.update(true, 1 / 60f, .5f) }
            return s
        }
        val a = settle(55.0); val e = settle(82.4)
        val top = a.cur.max()
        val lit = (0 until Spectrum.bandOf(300.0)).count { a.cur[it] > .5f * top }
        assertTrue("barras encendidas por una nota de 55 Hz: $lit", lit <= 4)
        val pa = a.cur.indices.maxBy { a.cur[it] }; val pe = e.cur.indices.maxBy { e.cur[it] }
        assertTrue("55 Hz en la barra $pa y 82 Hz en la $pe", pe - pa >= 3)
    }

    /**
     * Platos 30 dB por debajo del bajo (lo normal en una mezcla) tienen que mover las barras de agudos al menos la mitad
     * que el bajo las suyas. Antes llegaban al 2 %.
     */
    @Test fun losAgudosSubenComoLosGraves() {
        val s = Spectrum(); val rnd = java.util.Random(7)
        val noise = DoubleArray(s.samples.size) { rnd.nextGaussian() }
        repeat(3) { for (i in noise.size - 1 downTo 1) noise[i] -= noise[i - 1] }   // ruido con casi todo arriba (platos)
        val bassRms = .4 / Math.sqrt(2.0); val noiseRms = Math.sqrt(noise.sumOf { it * it } / noise.size)
        val k = bassRms * Math.pow(10.0, -30 / 20.0) / noiseRms
        for (i in s.samples.indices) s.samples[i] = (.4 * sin(2 * PI * 55 * i / 48_000) + k * noise[i]).toFloat()
        repeat(60) { s.update(true, 1 / 60f, .5f) }
        val bass = (0 until Spectrum.BASS_BANDS).maxOf { s.spec[it] }
        val highs = (Spectrum.bandOf(6000.0) until Spectrum.NBAND).maxOf { s.spec[it] }
        assertTrue("graves $bass agudos $highs", highs >= .5f * bass)
    }

    @Test fun elBomboSeDetectaEnVivo() {
        val s = Spectrum(); var maxKick = 0f
        for (f in 0 until 240) {
            val hit = f % 30 < 4   // un golpe de bombo cada medio segundo
            for (i in s.samples.indices) s.samples[i] = if (hit) (.8 * sin(2 * PI * 55 * i / 48_000)).toFloat() else (.02 * sin(2 * PI * 440 * i / 48_000)).toFloat()
            s.update(true, 1 / 60f, .5f)
            if (f > 60 && hit) maxKick = maxOf(maxKick, s.kick)
        }
        assertTrue("bombo $maxKick", maxKick > .6f)
    }

    @Test fun laTomaDeAudioDaLoQueEstaSonandoNoLoQueSeAcabaDeEscribir() {
        val owner = Any(); var played = 0L
        AudioTap.active = true
        try {
            AudioTap.attach(owner) { played }
            // 4000 fotogramas estéreo: los 2000 primeros valen +0,5 y los siguientes −0,5
            val pcm = ByteArray(4000 * 4)
            for (f in 0 until 4000) { val v = (if (f < 2000) 16384 else -16384).toShort().toInt()
                for (c in 0..1) { pcm[f * 4 + c * 2] = (v and 255).toByte(); pcm[f * 4 + c * 2 + 1] = (v shr 8).toByte() } }
            AudioTap.write(owner, pcm, pcm.size)
            val out = FloatArray(512)
            played = 1500; assertTrue(AudioTap.read(out)); assertTrue(out.all { it > .45f })      // aún suena la primera mitad
            played = 3900; assertTrue(AudioTap.read(out)); assertTrue(out.all { it < -.45f })     // ya la segunda
            AudioTap.detach(owner); assertFalse(AudioTap.read(out))
            AudioTap.active = false; AudioTap.attach(owner) { played }; AudioTap.write(owner, pcm, pcm.size)
            played = 1500; AudioTap.read(out); assertTrue("apagado no copia nada", out.all { it == 0f })
        } finally { AudioTap.active = false; AudioTap.detach(owner) }
    }
}
