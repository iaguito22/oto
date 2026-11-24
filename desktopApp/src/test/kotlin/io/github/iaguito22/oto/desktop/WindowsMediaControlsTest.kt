package io.github.iaguito22.oto.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowsMediaControlsTest {
    @Test
    fun parameterizedIidsMatchWinRt() {
        // IIterable<String> e IVector<String>, publicados en los SDK de Windows.
        assertEquals("{E2FCC7C1-3BFC-5A0B-B2B0-72E769D1CB7E}", parameterizedIid("pinterface({faa585ea-6214-4217-afda-7f46de5869b3};string)").toGuidString().uppercase())
        assertEquals("{98B9ACC1-4B56-532E-AC73-03D5291CCA90}", parameterizedIid("pinterface({913337e9-11a1-4345-a3a2-4e7f956e222d};string)").toGuidString().uppercase())
    }

    @Test
    fun buttonPressedHandlerIid() {
        assertEquals("{0557E996-7B23-5BAE-AA81-EA0D671143A4}", BUTTON_PRESSED_HANDLER.toGuidString().uppercase())
    }
}
