package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.lyrics.Line
import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.lyrics.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Escucha del 25/11 («siguen pasando cambios en estribillos o en mitad de letra») y sonda con las canciones de Iago: la
 * saliente seguía cantando mientras se iba en ~60 % de las mezclas del DJ rápido y ~47 % del normal, y la entrante
 * entraba a mitad de frase en ~1/4. La voz pasa a ser obligatoria, como el estribillo.
 */
class DjBridgeVoiceTest {
    /** Una línea cantada de [start] a [end] (sus palabras) que, como en LRCLIB, «dura» hasta [lineEnd]. */
    private fun line(start: Long, end: Long, text: String, lineEnd: Long = end): Line {
        val words = text.split(' ')
        val step = (end - start) / words.size
        return Line(start, lineEnd, words.mapIndexed { i, w -> Word(start + i * step, start + (i + 1) * step, w, true) }, real = false)
    }

    /** Versos seguidos (cada uno distinto: ninguno es estribillo) de [from] a [to], cantando 2,6 s de cada 3 s. */
    private fun verses(from: Long, to: Long, tag: String): List<Line> =
        (from until to step 3_000L).mapIndexed { i, s -> line(s, s + 2_600L, "$tag verso número $i distinto", lineEnd = s + 3_000L) }

    private fun metrics(id: String, durMs: Long, bpm: Double = 100.0): AudioMetrics {
        val bars = ArrayList<BarFeature>()
        var t = 0L
        while (t < durMs) {
            bars += BarFeature(
                startMs = t, endMs = (t + 2_400L).coerceAtMost(durMs), localBpm = bpm, lufs = -11.0,
                lowRms = 0.42, midRms = 0.30, highRms = 0.18, kickSteady = 0.85, drumEnergy = 0.55,
                harshness = 0.12, vocalRatio = 0.15, kickOnBeat = 0.85,
            )
            t += 2_400L
        }
        val beat = (60_000.0 / bpm).toLong()
        return AudioMetrics(
            trackId = id, bpm = bpm, bpmConfidence = 0.90, beatPhaseMs = 0L, downbeatPhaseMs = 0L,
            rmsDb = -11.0, integratedLufs = -11.0, keyName = "A min", camelot = "8A", keyConfidence = 0.8,
            beatStrength = 0.70, firstSoundMs = 200L, lastSoundMs = durMs - 400L, outroStartMs = durMs - 400L,
            durationMs = durMs, beatGrid = (0L until durMs step beat).toList(), analyzedMs = durMs, bars = bars,
        )
    }

    private fun sungIn(lyrics: Lyrics.Synced, from: Long, to: Long): Long = lyrics.lines.sumOf { l ->
        l.words.sumOf { w -> (minOf(w.end, to) - maxOf(w.start, from)).coerceAtLeast(0L) }
    }

    @Test
    fun theOutgoingSongDoesNotSingWhileItLeaves() {
        // A canta seguido hasta 170 s, calla 8 s, canta otra vez hasta 190 s y acaba a 200 s. B empieza a cantar a 12 s.
        val a = Lyrics.Synced(verses(100_000L, 170_000L, "a") + verses(178_000L, 190_000L, "b"), "t")
        val b = Lyrics.Synced(verses(12_000L, 60_000L, "c"), "t")
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 200_000L, incomingDurationMs = 200_000L, configuredFadeMs = 10_000L,
            outgoingAudio = metrics("a", 200_000L), incomingAudio = metrics("b", 200_000L, bpm = 140.0),
            outgoingLyrics = a, incomingLyrics = b, djMode = DjMode.Normal,
        )
        assertTrue("debe mezclar: ${spec.reason}", spec.transitionDurationMs > 0L)
        assertNotEquals(DjBridgeStyle.OutroTail, spec.style)
        // Mientras aún se oye (lo menos: el filtro, 45 % de la mezcla) A no canta.
        val audible = (spec.transitionDurationMs * 0.45).toLong()
        assertEquals("A canta al irse: ${spec.style} ${spec.cueOutStartMs} + ${spec.transitionDurationMs} · ${spec.tempoNote}",
            0L, sungIn(a, spec.cueOutStartMs, spec.cueOutStartMs + audible))
        // Y B no entra a mitad de una frase: o en un hueco suyo o al empezar una.
        val startsPhrase = b.lines.any { l -> l.words.first().start in (spec.entryInMs - 60L)..(spec.entryInMs + 250L) }
        assertTrue("B entra cantando: ${spec.entryInMs}", startsPhrase || sungIn(b, spec.entryInMs, spec.entryInMs + 1_000L) == 0L)
    }

    @Test
    fun withoutAnyGapItCutsWithEchoWhenABlockEnds() {
        // Estribillo que se repite sin pausa hasta casi el final (180–196 s), y antes versos seguidos: ni un hueco de voz
        // en toda la ventana. Antes: «Enlace al final» (A entera) o un fundido encima de la letra.
        val chorus = (0 until 6).flatMap { round ->
            val s = 150_000L + round * 8_000L
            listOf(line(s, s + 3_600L, "oh oh esto es el estribillo", lineEnd = s + 4_000L), line(s + 4_000L, s + 7_600L, "y aquí se repite otra vez", lineEnd = s + 8_000L))
        }
        val a = Lyrics.Synced(verses(60_000L, 150_000L, "v") + chorus, "t")
        val spec = DjBridgePlanner.plan(
            outgoingDurationMs = 200_000L, incomingDurationMs = 200_000L, configuredFadeMs = 10_000L,
            outgoingAudio = metrics("a", 200_000L), incomingAudio = metrics("b", 200_000L, bpm = 140.0),
            outgoingLyrics = a, djMode = DjMode.Normal,
        )
        assertEquals("${spec.reason} · ${spec.tempoNote}", DjBridgeStyle.EnergyDropCut, spec.style)
        assertFalse("corta una palabra en ${spec.cueOutEndMs}", DjLyricMatcher.cutsWordOrPhrase(a, spec.cueOutEndMs))
        assertEquals("tras el corte A no vuelve a cantar en el respiro", 0L, sungIn(a, spec.cueOutEndMs, spec.cueOutEndMs + 120L))
        // Y las guardas del PC y del móvil lo dejan empezar: el corte no interrumpe ningún estribillo aunque su arranque
        // (el último beat) sea estribillo. Antes la mezcla nunca empezaba.
        val sections = SongSections.of(a.lines)
        assertTrue(ChorusSafety.allows(spec, sections, emptyList()))
        val plan = DjTransitionPlanner.fromBridgeSpec(spec, null, null)
        assertTrue(ChorusSafety.allowsLive(plan, spec.cueOutStartMs, 1.0, sections))
        // Un corte dentro de un estribillo, no.
        val chorusMid = sections.first { it.isChorus }.let { (it.startMs + it.endMs) / 2 }
        assertFalse(ChorusSafety.cutAllowed(chorusMid, sections))
    }

    @Test
    fun theLastLineDoesNotCoverTheOutro() {
        // LRCLIB: la última línea «dura» hasta el final de la canción (medido: «Hell no» acaba a 139 s y la línea a 154 s).
        val lines = listOf(
            line(120_000L, 126_000L, "I'm a Million Dollar Baby", lineEnd = 128_000L),
            line(128_000L, 131_500L, "Hell no", lineEnd = 132_000L),
            line(132_000L, 135_300L, "Hell no", lineEnd = 135_800L),
            line(135_800L, 139_200L, "Hell no", lineEnd = 154_400L),
        )
        val last = SongSections.of(lines).maxBy { it.endMs }
        assertTrue("acaba en ${last.endMs}", last.endMs <= 140_200L)
    }
}
