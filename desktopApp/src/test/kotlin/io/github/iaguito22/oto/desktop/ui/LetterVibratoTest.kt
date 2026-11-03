package io.github.iaguito22.oto.desktop.ui

import org.junit.Assert.*
import org.junit.Test

class LetterVibratoTest {
    @Test fun onlySustainedRealLettersVibrateWhileActive() {
        assertEquals(0f, letterVibrato(500, 0, 1500, false), 0f)
        assertEquals(0f, letterVibrato(300, 0, 1500, true), 0f)
        assertEquals(0f, letterVibrato(1500, 0, 1500, true), 0f)
        assertEquals(0f, letterVibrato(400, 0, 500, true), 0f)
        assertTrue(kotlin.math.abs(letterVibrato(700, 0, 1500, true)) > .01f)
    }
}
