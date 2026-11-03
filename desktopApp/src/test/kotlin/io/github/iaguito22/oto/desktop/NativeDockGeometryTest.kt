package io.github.iaguito22.oto.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeDockGeometryTest {
    @Test fun openingAWindowDoesNotInterpretDpiSeamRoundingAsAResize() {
        val expected = DockRect(0,88,656,640)
        assertEquals(NativeDockChange.None,nativeDockChange(expected,expected,1))
        assertEquals(NativeDockChange.None,nativeDockChange(expected.copy(width=657),expected,1))
    }
    @Test fun onePixelPositionRoundingCannotDetachThePlayer() {
        val expected = DockRect(1135,88,360,640)
        assertEquals(NativeDockChange.None,nativeDockChange(expected.copy(x=1134),expected,0))
        assertEquals(NativeDockChange.None,nativeDockChange(expected.copy(x=1136,y=89),expected,0))
    }
    @Test fun realMovesAndResizesStillReachTheDockController() {
        val expected = DockRect(1135,88,360,640)
        assertEquals(NativeDockChange.Move,nativeDockChange(expected.copy(x=1160),expected,0))
        assertEquals(NativeDockChange.Resize,nativeDockChange(expected.copy(width=400),expected,0))
        assertEquals(NativeDockChange.Resize,nativeDockChange(expected.copy(y=70,height=658),expected,0))
    }
}
