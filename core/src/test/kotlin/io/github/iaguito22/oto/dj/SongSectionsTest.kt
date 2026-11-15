package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.lyrics.Line
import io.github.iaguito22.oto.lyrics.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongSectionsTest {
    /** Un bloque de [texts] desde [at], un verso cada 3,5 s que se canta durante 3 s. */
    private fun block(at: Long, texts: List<String>) = texts.mapIndexed { i, t ->
        val s = at + i * 3_500L
        Line(s, s + 3_000L, listOf(Word(s, s + 3_000L, t, false)))
    }

    private val verse1 = listOf("camino solo por la calle", "nadie sabe lo que siento", "la ciudad se queda quieta", "y yo sigo aqui despierto")
    private val verse2 = listOf("vuelvo a casa sin hablar", "las luces se apagan ya", "tu recuerdo no se va", "y la noche dice mas")
    private val chorus = listOf("dame una razon para quedarme", "dame una cancion para olvidarte", "esta noche es nuestra", "esta noche no se acaba")
    private val bridge = listOf("y si mañana no estas", "lo vuelvo a intentar")

    // 3:40. Intro 0–14 s, estrofa 14–28, estribillo 31–45, estrofa 60–74, estribillo 77–91, puente 120–127, estribillo 131–145.
    private val song = block(14_000L, verse1) + block(31_000L, chorus) + block(60_000L, verse2) + block(77_000L, chorus) +
        block(120_000L, bridge) + block(131_000L, chorus)

    @Test
    fun findsBlocksAndChoruses() {
        val sections = SongSections.of(song)
        assertEquals(6, sections.size)
        assertEquals(listOf(false, true, false, true, false, true), sections.map { it.isChorus })
        assertEquals(31_000L, sections[1].startMs)
        assertEquals(31_000L + 3 * 3_500L + 3_000L, sections[1].endMs)
    }

    @Test
    fun segmentStartsAndEndsAtBlockEdgesWithAWholeChorus() {
        val sections = SongSections.of(song)
        val seg = AdhdPlanner.segment(220_000L, "x", crossfadeMs = 4_000L, sections = sections)!!
        assertTrue("debe usar la letra", seg.fromLyrics)
        assertTrue("dura ${seg.clipDurationMs}", seg.clipDurationMs in AdhdPlanner.MIN_CLIP_MS..AdhdPlanner.MAX_CLIP_MS)
        // Entra antes de que empiece a cantarse un bloque y sin pisar el anterior.
        val first = sections.first { it.startMs >= seg.startMs }
        val before = sections.lastOrNull { it.endMs <= seg.startMs }
        assertTrue(seg.startMs <= first.startMs)
        if (before != null) assertTrue(seg.startMs > before.endMs)
        // El fundido de salida empieza con la voz ya terminada: nunca dentro de un bloque.
        assertFalse(
            "el fundido (${seg.fadeOutStartMs}) cae dentro de un bloque",
            sections.any { seg.fadeOutStartMs > it.startMs && seg.fadeOutStartMs < it.endMs },
        )
        // Incluye al menos un estribillo entero.
        assertTrue(sections.any { it.isChorus && it.startMs >= seg.startMs && it.endMs <= seg.fadeOutStartMs })
    }

    @Test
    fun withoutLyricsFallsBackToALongerGuess() {
        val seg = AdhdPlanner.segment(220_000L, "x", sections = emptyList())!!
        assertFalse(seg.fromLyrics)
        assertTrue(seg.clipDurationMs in AdhdPlanner.MIN_CLIP_MS..AdhdPlanner.MAX_CLIP_MS)
    }

    @Test
    fun startingAtKeepsTheEndAndAMinimumLength() {
        val seg = AdhdPlanner.segment(220_000L, "x")!!
        val moved = seg.startingAt(1_000L)
        assertEquals(1_000L, moved.startMs)
        assertTrue(moved.clipDurationMs >= AdhdPlanner.MIN_CLIP_MS)
        assertEquals(moved.endMs - moved.mixOutDurationMs, moved.fadeOutStartMs)
    }
}
