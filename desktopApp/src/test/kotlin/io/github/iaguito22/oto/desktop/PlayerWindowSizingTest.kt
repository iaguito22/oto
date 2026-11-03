package io.github.iaguito22.oto.desktop

import io.github.iaguito22.oto.desktop.ui.PlayerForm
import org.junit.Assert.*
import org.junit.Test

class PlayerWindowSizingTest {
    @Test fun compactFormsIgnoreThePreviouslyExpandedShortAxis() {
        assertEquals(720 to 108, PlayerForm.Horizontal.openingSize(720, 900))
        assertEquals(96 to 900, PlayerForm.Vertical.openingSize(720, 900))
        assertEquals(280 to 108, PlayerForm.Horizontal.openingSize(1, 1))
        assertEquals(96 to 240, PlayerForm.Vertical.openingSize(1, 1))
    }

    @Test fun coverOpensAtPhoneAspectInsteadOfRestoringAnUnrelatedHeight() {
        assertEquals(360 to 640, PlayerForm.Full.openingSize())
        assertEquals(450 to 800, PlayerForm.Full.openingSize(450, 1200))
        assertEquals(900 to 1600, PlayerForm.Full.openingSize(2400, 100))
    }

    private fun savedGroup(): DockLayout = DockLayout().apply {
        create(DockId.Library, DockRect(20, 20, 800, 640))
        create(DockId.Player, DockRect(820, 20, 450, 640))
        dock(DockId.Player, DockId.Library, false)
    }.let { checkNotNull(decodeDockLayout(it.encode())) }

    @Test fun restoredHorizontalDetachesWithoutShrinkingTheLibrary() {
        val layout = savedGroup()
        layout.preparePlayerOpening(PlayerForm.Horizontal)
        assertEquals(108, layout.bounds.getValue(DockId.Player).height)
        assertEquals(640, layout.bounds.getValue(DockId.Library).height)
        assertEquals(listOf(DockId.Player), layout.group(DockId.Player).members)
    }

    @Test fun restoredVerticalAndCoverCanKeepTheSharedHeight() {
        val vertical = savedGroup()
        vertical.preparePlayerOpening(PlayerForm.Vertical)
        assertEquals(96, vertical.bounds.getValue(DockId.Player).width)
        assertEquals(640, vertical.bounds.getValue(DockId.Player).height)
        assertEquals(2, vertical.group(DockId.Player).members.size)
        val cover = savedGroup()
        cover.preparePlayerOpening(PlayerForm.Full)
        assertEquals(360, cover.bounds.getValue(DockId.Player).width)
        assertEquals(640, cover.bounds.getValue(DockId.Player).height)
    }

    @Test fun reopeningANormalizedLayoutIsStableAndManualResizeStillWorks() {
        val layout = savedGroup()
        layout.preparePlayerOpening(PlayerForm.Horizontal)
        val size = layout.bounds.getValue(DockId.Player)
        layout.preparePlayerOpening(PlayerForm.Horizontal)
        assertEquals(size, layout.bounds.getValue(DockId.Player))
        layout.resize(DockId.Player, 700, 300)
        assertEquals(300, layout.bounds.getValue(DockId.Player).height)
        layout.preparePlayerOpening(PlayerForm.Horizontal)
        assertEquals(700, layout.bounds.getValue(DockId.Player).width)
        assertEquals(108, layout.bounds.getValue(DockId.Player).height)
    }

    @Test fun maximizedPlayerUsesItsNormalSizeWhenOpeningAgain() {
        val layout = DockLayout()
        layout.create(DockId.Player, DockRect(20, 20, 450, 800))
        layout.maximize(DockId.Player, DockRect(0, 0, 1920, 1080))
        layout.preparePlayerOpening(PlayerForm.Full)
        assertEquals(DockRect(20, 20, 450, 800), layout.bounds.getValue(DockId.Player))
        assertFalse(layout.group(DockId.Player).maximized)
    }
}
