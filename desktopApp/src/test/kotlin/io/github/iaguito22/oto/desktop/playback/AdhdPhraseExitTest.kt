package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.dj.AdhdSegment
import io.github.iaguito22.oto.dj.SongSection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La salida forzada del DJ rápido va al límite de frase previsto y no lo persigue lectura a lectura. */
class AdhdPhraseExitTest {
    private val dur = 210_000L
    private val seg = AdhdSegment(20_000L, 103_000L, 83_000L, 99_000L, 4_000L, dur, phrasePointsMs = (0..26).map { it * 8_000L })

    @Test
    fun theTargetDoesNotMoveWhileReadingsPassIt() {
        // Fin previsto 103 s → primer límite 104 s. Lecturas a 103,9 / 104,15 / 104,4: se sale en 104,15 (no en 112).
        assertFalse(DesktopTransitionPlanner.forcedExitDue(seg, 103_900L, emptyList(), dur))
        assertTrue(DesktopTransitionPlanner.forcedExitDue(seg, 104_150L, emptyList(), dur))
        assertTrue(DesktopTransitionPlanner.forcedExitDue(seg, 104_400L, emptyList(), dur))
    }

    @Test
    fun aChorusStillWinsOverThePhrase() {
        val chorus = listOf(SongSection(101_000L, 118_000L, 1.0))
        // El estribillo manda: la salida espera a que acabe (y a su límite de frase siguiente, 120 s).
        assertFalse(DesktopTransitionPlanner.forcedExitDue(seg, 110_000L, chorus, dur))
        assertFalse(DesktopTransitionPlanner.forcedExitDue(seg, 118_700L, chorus, dur))
        assertTrue(DesktopTransitionPlanner.forcedExitDue(seg, 120_050L, chorus, dur))
    }
}
