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
    fun aRapidBreathRequiresWordTimingAndAlsoWaitsForBackingVocals() {
        val lines = listOf(chorus[0], chorus[1], chorus[0], chorus[1]).mapIndexed { index, text ->
            val start = index * 2_000L
            val backing = if (index == 1) listOf(Word(1_000L, 2_500L, "coro", false)) else emptyList()
            Line(start, start + 700L, listOf(Word(start, start + 700L, text, false)), bg = backing)
        }
        val timed = SongSections.of(lines)
        assertTrue(timed.single().isChorus)
        assertFalse(SongSections.allowsRapidBreath(900L, 1_350L, timed))
        assertTrue(SongSections.allowsRapidBreath(3_000L, 3_450L, timed))
        assertTrue(SongSections.overlapsChorus(3_000L, 3_450L, timed))
        assertTrue(SongSections.rapidBreaths(SongSections.of(lines.map { it.copy(real = false) })).isEmpty())
    }

    @Test
    fun aMinutesLongRepeatedBlockIsSplitAndDoesNotLockTheExitToTheEnd() {
        // Como un reguetón: dos minutos de versos que se repiten sin pausas de la voz (antes: un solo «estribillo» de
        // 39–156 s y la salida forzada del DJ rápido esperaba al final de la canción).
        val hook = listOf("mami ven conmigo esta noche", "que la noche es larga contigo", "bailando pegao hasta abajo", "no me digas que no mami")
        val cycles = (0 until 9).flatMap { hook }
        val lines = block(40_000L, cycles) // 36 versos × 3,5 s ≈ 40–166 s, sin huecos
        val sections = SongSections.of(lines)
        assertTrue("se parte: ${sections.map { it.startMs to it.endMs }}", sections.size >= 3)
        assertTrue(sections.all { it.endMs - it.startMs <= 45_000L })
        val exit = SongSections.safeExitAt(71_000L, sections, 180_000L, 400L)
        assertTrue("sale en una unión cercana, no al final: $exit", exit < 100_000L)
        // La bajada acaba antes del final del último verso de una parte (con holgura), antes de que vuelva a empezar.
        assertTrue(sections.any { it.seamFreeMs > 0L && exit >= it.endMs - 1_500L && exit + 400L <= it.endMs })
        assertFalse(SongSections.overlapsChorus(exit, exit + 400L, sections))
        // Un fundido largo desde la unión se metería en la parte siguiente: no.
        assertTrue(SongSections.overlapsChorus(exit, exit + 10_000L, sections))
    }

    @Test
    fun seamsOfALongRepeatedBlockOnlyAllowAQuickExitThatEndsWithTheLine() {
        // Versos pegados sin ningún hueco (letra con tiempos estimados): el final de un verso es el inicio del siguiente.
        val hook = listOf("mami ven conmigo esta noche", "que la noche es larga contigo", "bailando pegao hasta abajo", "no me digas que no mami")
        val texts = (0 until 9).flatMap { hook }
        val lines = texts.indices.map { i ->
            val s = 40_000L + i * 3_500L
            Line(s, s + 3_500L, listOf(Word(s, s + 3_500L, texts[i], false)))
        }
        val sections = SongSections.of(lines)
        val before = sections.first { it.seamFreeMs > 0L }
        val seam = before.endMs
        // La bajada (400 ms) vale durante un tramo (el monitor mira cada 20 ms) y nunca entra en el verso que empieza.
        assertEquals(seam - 1_000L, SongSections.safeExitAt(seam - 1_000L, sections, 200_000L, 400L))
        assertEquals(seam - 1_000L, SongSections.safeExitAt(seam - 1_000L, sections, 200_000L, 400L, fadeBefore = true))
        // Bajada DESPUÉS (PC): acaba 150 ms antes del verso nuevo; valen comprobaciones algo tardías hasta seam − 400.
        val target = SongSections.safeExitAt(before.startMs + 2_000L, sections, 200_000L, 400L)
        assertEquals(seam - 550L, target)
        for (late in listOf(target, target + 1L, target + 100L, seam - 400L)) {
            assertEquals(late, SongSections.safeExitAt(late, sections, 200_000L, 400L))
        }
        // Bajada ANTES (Android): salta 150 ms antes del verso nuevo, con la bajada sobre el final del verso que acaba.
        val jump = SongSections.safeExitAt(before.startMs + 2_000L, sections, 200_000L, 400L, fadeBefore = true)
        assertEquals(seam - 150L, jump)
        assertFalse(SongSections.overlapsChorus(jump - 400L, jump, sections))
        // El salto llega algo tarde (cada 20 ms) y, sin otra bajada, sigue valiendo antes del verso nuevo.
        assertEquals(jump + 40L, SongSections.safeExitAt(jump + 40L, sections, 200_000L, 0L))
        // Comprobación tardía, ya pasada la unión: nunca un punto anterior, y la bajada entera fuera de lo protegido.
        for (late in listOf(seam, seam + 200L, seam + 2_000L)) {
            for (before in listOf(true, false)) {
                val next = SongSections.safeExitAt(late, sections, 200_000L, 400L, fadeBefore = before)
                assertTrue("tardía $late (antes=$before) → $next", next > late)
                val (from, to) = if (before) next - 400L to next else next to next + 400L
                assertFalse("tardía $late (antes=$before) → $next", SongSections.overlapsChorus(from, to, sections))
            }
        }
        assertFalse(SongSections.overlapsChorus(seam - 400L, seam, sections))
        assertTrue(SongSections.overlapsChorus(seam - 100L, seam + 300L, sections))
        assertTrue(SongSections.overlapsChorus(seam, seam + 400L, sections))
        // Tarde para esta unión: a la siguiente, acabando también con un verso.
        val late = SongSections.safeExitAt(seam - 100L, sections, 200_000L, 400L)
        assertTrue("salida tardía: $late", late > seam)
        assertTrue(sections.none { it.isChorus && late > it.startMs && late + 400L <= it.endMs && it.seamFreeMs == 0L })
        assertFalse(SongSections.overlapsChorus(late, late + 400L, sections))
        // Un fundido largo no cabe en ninguna unión.
        assertTrue(SongSections.overlapsChorus(seam - 6_000L, seam, sections))
    }

    @Test
    fun withAGapBetweenPartsBothValidatorsAgree() {
        // Versos de 3 s cada 3,5 s: hay medio segundo sin voz entre versos (también en las uniones).
        val hook = listOf("mami ven conmigo esta noche", "que la noche es larga contigo", "bailando pegao hasta abajo", "no me digas que no mami")
        val sections = SongSections.of(block(40_000L, (0 until 9).flatMap { hook }))
        val before = sections.first { it.seamFreeMs > 0L }
        val after = sections.first { it.startMs > before.endMs }
        for (at in (before.endMs - 1_500L)..(after.startMs + 300L) step 50L) {
            for (fadeBefore in listOf(true, false)) {
                val got = SongSections.safeExitAt(at, sections, 200_000L, 400L, fadeBefore)
                assertTrue("nunca antes: $at → $got", got >= at)
                val (from, to) = if (fadeBefore) got - 400L to got else got to got + 400L
                assertFalse("$at (antes=$fadeBefore) → $got", SongSections.overlapsChorus(from, to, sections))
            }
        }
    }

    @Test
    fun aNormalChorusIsNotSplit() {
        assertTrue(SongSections.of(song).none { it.part })
    }

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

    @Test
    fun breathBetweenRepeatedLinesDoesNotSplitTheChorus() {
        fun spaced(at: Long) = chorus.mapIndexed { i, text ->
            val start = at + i * 5_000L
            Line(start, start + 3_000L, listOf(Word(start, start + 3_000L, text, false)))
        }
        val sections = SongSections.of(spaced(30_000L) + spaced(90_000L))
        assertEquals(2, sections.size)
        assertTrue(sections.all { it.isChorus })
        assertEquals(48_600L, SongSections.safeExitAt(34_000L, sections, 180_000L))
    }

    @Test
    fun forcedExitWaitsThroughOverlappingChoruses() {
        val sections = listOf(SongSection(20_000L, 40_000L, 1.0), SongSection(35_000L, 50_000L, 1.0))
        assertEquals(50_600L, SongSections.safeExitAt(30_000L, sections, 180_000L))
        assertEquals(10_000L, SongSections.safeExitAt(10_000L, sections, 180_000L))
        assertEquals(50_600L, SongSections.safeExitAt(19_900L, sections, 180_000L, 300L))
    }

    @Test
    fun contiguousVerseCannotHideAChorusAndItsUniqueTag() {
        val unique = (1..8).map { "a unique verse line number $it" }
        val first = block(10_000L, unique) + block(38_000L, chorus + "one special final tag")
        val sections = SongSections.of(first + block(100_000L, chorus))
        val protected = sections.first { it.isChorus && it.startMs == 38_000L }
        assertTrue(protected.endMs >= 55_000L)
        assertTrue(SongSections.overlapsChorus(45_000L, 51_000L, sections))
        assertFalse(SongSections.overlapsChorus(56_000L, 60_000L, sections))
    }
}
