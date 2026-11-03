package io.github.iaguito22.oto.desktop

import org.junit.Assert.*
import org.junit.Test

class InitialDesktopLayoutTest {
    private val area = DockRect(0, 0, 1920, 1080)

    @Test fun firstLaunchOnlyShowsTheCenteredPhoneShapedPlayer() {
        val layout = initialDockLayout(area)
        assertEquals(setOf(DockId.Player), layout.bounds.keys)
        assertEquals(DockRect(780,220,360,640), layout.bounds.getValue(DockId.Player))
        assertEquals(layout.encode(), checkNotNull(decodeDockLayout(layout.encode())).encode())
    }

    @Test fun openingLibraryAndLyricsInEitherOrderUsesTheSameArrangement() {
        for (order in listOf(listOf(DockId.Library,DockId.Lyrics),listOf(DockId.Lyrics,DockId.Library))) {
            val layout = initialDockLayout(area)
            order.forEach { assertTrue(layout.open(it,DockId.Player,area)) }
            assertEquals(listOf(DockId.Lyrics,DockId.Library,DockId.Player),layout.group(DockId.Player).members)
            assertTrue(layout.open(DockId.Queue,DockId.Library,area))
            assertEquals(listOf(DockId.Queue),layout.group(DockId.Queue).members)
            layout.close(DockId.Queue)
            layout.open(DockId.Queue,DockId.Player,area)
            assertEquals(listOf(DockId.Queue),layout.group(DockId.Queue).members)
        }
    }
}
