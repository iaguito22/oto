package io.github.iaguito22.oto.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Point
import java.awt.Rectangle

class PlayerOptionsPositionTest {
    @Test fun opensAbovePlayerAndStaysInsideScreen() {
        val screen=Rectangle(0,0,1536,816)
        assertEquals(Point(1145,112),optionsPosition(Rectangle(1100,500,360,120),screen))
        assertEquals(Point(25,168),optionsPosition(Rectangle(-20,40,360,120),screen))
        assertEquals(Point(1266,372),optionsPosition(Rectangle(1450,760,360,120),screen))
    }
}
