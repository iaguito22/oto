package io.github.iaguito22.oto.desktop

import org.junit.Assert.*
import org.junit.Test

class DockLayoutTest {
    private fun layout(): DockLayout = DockLayout().apply {
        create(DockId.Library, DockRect(20, 20, 960, 740))
        create(DockId.Player, DockRect(1000, 30, 320, 560))
        assertTrue(dock(DockId.Player, DockId.Library, false))
    }
    private fun assertJoined(l: DockLayout, id: DockId) {
        val g = l.group(id)
        g.members.zipWithNext().forEach { (a,b) ->
            val left=l.bounds.getValue(a);val right=l.bounds.getValue(b)
            assertEquals(left.x+left.width,right.x)
            assertEquals(left.y,right.y)
            assertEquals(left.height,right.height)
        }
    }
    @Test fun reopeningInAnyOrderRestoresThreeWindowOrder() {
        val area = DockRect(0, 0, 2400, 1000)
        val orders = listOf(
            listOf(DockId.Player, DockId.Lyrics), listOf(DockId.Lyrics, DockId.Player),
            listOf(DockId.Player, DockId.Library), listOf(DockId.Library, DockId.Lyrics))
        for (closing in orders) {
            val l = DockLayout()
            l.create(DockId.Lyrics, DockRect(20, 20, 480, 640))
            l.create(DockId.Player, DockRect(600, 20, 360, 640))
            l.create(DockId.Library, DockRect(1000, 20, 960, 640))
            assertTrue(l.dock(DockId.Player, DockId.Lyrics, false))
            assertTrue(l.dock(DockId.Library, DockId.Player, false))
            val expected = l.group(DockId.Lyrics).members.toList()
            closing.forEach { l.close(it) }
            closing.forEach { assertTrue(l.open(it, null, area)) }
            assertEquals("cerrando $closing", expected, l.group(DockId.Library).members.toList())
        }
    }
    @Test fun outerEdgesResizeTheGroupAndReleaseMaximized() {
        val l = layout()
        assertTrue(l.maximize(DockId.Library, DockRect(0, 0, 1600, 900)))
        val right = l.bounds.getValue(DockId.Player).let { it.x + it.width }
        // El borde izquierdo mueve solo la izquierda; la derecha queda donde estaba.
        l.resizeEdge(DockId.Player, DockEdge.Left, 100)
        assertFalse(l.group(DockId.Library).maximized)
        assertEquals(100, l.bounds.getValue(DockId.Library).x)
        assertEquals(right, l.bounds.getValue(DockId.Player).let { it.x + it.width })
        l.resizeEdge(DockId.Library, DockEdge.Right, right - 50)
        assertEquals(right - 50, l.bounds.getValue(DockId.Player).let { it.x + it.width })
        l.resizeEdge(DockId.Library, DockEdge.Bottom, 700)
        l.resizeEdge(DockId.Library, DockEdge.Top, 60)
        assertEquals(60, l.bounds.getValue(DockId.Player).y)
        assertEquals(640, l.bounds.getValue(DockId.Library).height)
        assertJoined(l, DockId.Library)
        // Nunca por debajo del mínimo.
        l.resizeEdge(DockId.Library, DockEdge.Right, 0)
        assertEquals(DockId.Player.minWidth, l.bounds.getValue(DockId.Player).width)
    }
    @Test fun dockUsesPriorityHeightAndMovesTogether() {
        val l=layout()
        assertEquals(740,l.bounds.getValue(DockId.Player).height)
        l.move(DockId.Library,100,30)
        assertJoined(l,DockId.Library)
        assertEquals(120,l.bounds.getValue(DockId.Library).x)
        l.resize(DockId.Player,400,600)
        assertEquals(600,l.bounds.getValue(DockId.Library).height)
        assertJoined(l,DockId.Player)
    }
    @Test fun openingShorterLibraryKeepsExistingGroupHeight() {
        val l=DockLayout()
        l.create(DockId.Player,DockRect(20,20,320,700))
        l.create(DockId.Library,DockRect(340,20,640,480))
        assertTrue(l.dock(DockId.Library,DockId.Player,false,DockRect(0,0,1600,900)))
        assertEquals(700,l.bounds.getValue(DockId.Library).height)
        assertEquals(700,l.bounds.getValue(DockId.Player).height)
    }
    @Test fun attachingTallerQueueKeepsExistingGroupHeight() {
        val l=DockLayout()
        l.create(DockId.Library,DockRect(20,20,640,530))
        l.create(DockId.Queue,DockRect(660,20,420,694))
        assertTrue(l.dock(DockId.Queue,DockId.Library,false,DockRect(0,0,1600,900)))
        assertEquals(530,l.bounds.getValue(DockId.Library).height)
        assertEquals(530,l.bounds.getValue(DockId.Queue).height)
        assertJoined(l,DockId.Library)
    }
    @Test fun verticalResizeTracksTopEdgeAndKeepsGroupAligned() {
        val l=layout()
        l.resize(DockId.Player,320,650,110)
        assertEquals(110,l.bounds.getValue(DockId.Player).y)
        assertEquals(110,l.bounds.getValue(DockId.Library).y)
        assertEquals(650,l.bounds.getValue(DockId.Library).height)
        assertJoined(l,DockId.Player)
    }
    @Test fun dividerResizesAdjacentWindowsEvenWhenMaximized() {
        val l=layout()
        assertTrue(l.maximize(DockId.Library,DockRect(0,0,1600,900)))
        l.resizeDivider(DockId.Library,-200)
        assertEquals(1080,l.bounds.getValue(DockId.Library).width)
        assertEquals(520,l.bounds.getValue(DockId.Player).width)
        assertEquals(0,l.bounds.getValue(DockId.Library).x)
        assertEquals(1600,l.bounds.getValue(DockId.Player).let { it.x+it.width })
        l.resizeDivider(DockId.Library,9999)
        assertEquals(DockId.Player.minWidth,l.bounds.getValue(DockId.Player).width)
        assertJoined(l,DockId.Library)
        assertTrue(l.group(DockId.Library).maximized)
    }
    @Test fun closingAddedLibraryRestoresMaximizedWidthsAcrossRestart() {
        val l=DockLayout()
        l.create(DockId.Lyrics,DockRect(0,0,800,740))
        l.create(DockId.Player,DockRect(800,0,800,740))
        assertTrue(l.dock(DockId.Lyrics,DockId.Player,true))
        val area=DockRect(0,0,1600,900)
        assertTrue(l.maximize(DockId.Player,area))
        val before=l.bounds.toMap()
        assertTrue(l.open(DockId.Library,DockId.Player,area))
        val restored=decodeDockLayout(l.encode())!!
        restored.close(DockId.Library)
        assertEquals(before[DockId.Lyrics],restored.bounds[DockId.Lyrics])
        assertEquals(before[DockId.Player],restored.bounds[DockId.Player])
        assertJoined(restored,DockId.Player)
    }
    @Test fun secondaryCanSwapWithPrincipal() {
        val l=layout()
        val left=l.bounds.getValue(DockId.Library).x
        val right=l.bounds.getValue(DockId.Player).let { it.x + it.width }
        l.reorder(DockId.Player,DockId.Library)
        assertEquals(listOf(DockId.Player,DockId.Library),l.group(DockId.Library).members)
        assertEquals(DockId.Library,l.group(DockId.Player).leader)
        assertEquals(left,l.bounds.getValue(DockId.Player).x)
        assertEquals(right,l.bounds.getValue(DockId.Library).let { it.x + it.width })
        assertJoined(l,DockId.Library)
    }
    @Test fun detachCarriesOutwardDependents() {
        val l=layout()
        l.create(DockId.Queue,DockRect(0,0,420,580))
        l.dock(DockId.Queue,DockId.Player,false)
        l.detach(DockId.Player)
        assertEquals(listOf(DockId.Player,DockId.Queue),l.group(DockId.Player).members)
        assertEquals(listOf(DockId.Library),l.group(DockId.Library).members)
        assertJoined(l,DockId.Player)
    }
    @Test fun addQueueToMaximizedGroupShrinksLibraryAndRestoresWidths() {
        val l=layout()
        assertTrue(l.maximize(DockId.Library,DockRect(0,0,1600,900)))
        l.create(DockId.Queue,DockRect(40,40,420,580))
        assertTrue(l.dock(DockId.Queue,DockId.Player,false))
        assertEquals(860,l.bounds.getValue(DockId.Library).width)
        assertJoined(l,DockId.Library)
        l.maximize(DockId.Library,DockRect(0,0,1600,900))
        assertEquals(860,l.bounds.getValue(DockId.Library).width)
        assertEquals(320,l.bounds.getValue(DockId.Player).width)
        assertJoined(l,DockId.Library)
    }
    @Test fun restoringAfterOpeningLyricsKeepsEveryMemberOnScreen() {
        val l = layout()
        val area = DockRect(0, 0, 1536, 816)
        assertTrue(l.maximize(DockId.Library, area))
        assertTrue(l.open(DockId.Lyrics, DockId.Library, area))
        assertTrue(l.maximize(DockId.Library, area))
        val g = l.group(DockId.Library)
        assertFalse(g.maximized)
        assertJoined(l, DockId.Library)
        assertEquals(736, l.bounds.getValue(DockId.Library).width)
        assertEquals(320, l.bounds.getValue(DockId.Player).width)
        assertEquals(480, l.bounds.getValue(DockId.Lyrics).width)
        assertTrue(g.members.all { l.bounds.getValue(it).let { r -> r.x >= 0 && r.x + r.width <= area.width } })
    }
    @Test fun reducesOversizedSecondaryBeforeLibrary() {
        val l=layout()
        l.resize(DockId.Player,700,740)
        l.maximize(DockId.Library,DockRect(0,0,1500,900))
        l.create(DockId.Queue,DockRect(0,0,420,580))
        assertTrue(l.dock(DockId.Queue,DockId.Player,false))
        assertEquals(320,l.bounds.getValue(DockId.Player).width)
        assertEquals(760,l.bounds.getValue(DockId.Library).width)
    }
    @Test fun refusesDockWithoutBreakingExistingMaximizedGroup() {
        val l=layout()
        l.maximize(DockId.Library,DockRect(0,0,1100,800))
        val before=l.bounds.toMap()
        l.create(DockId.Queue,DockRect(30,30,420,580))
        assertFalse(l.dock(DockId.Queue,DockId.Player,false))
        before.forEach { (id,r) -> assertEquals(r,l.bounds[id]) }
        assertEquals(listOf(DockId.Queue),l.group(DockId.Queue).members)
        assertTrue(l.group(DockId.Library).maximized)
    }
    @Test fun detachFromMaximizedRestoresSizeAndFillsRemainingArea() {
        val l=layout()
        l.maximize(DockId.Library,DockRect(0,0,1600,900))
        l.detach(DockId.Player)
        assertEquals(DockRect(0,0,1600,900),l.bounds[DockId.Library])
        assertEquals(320,l.bounds.getValue(DockId.Player).width)
        assertEquals(740,l.bounds.getValue(DockId.Player).height)
        assertFalse(l.group(DockId.Player).maximized)
    }
    @Test fun closingMiddleCompactsAndKeepsMaximized() {
        val l=layout()
        l.create(DockId.Queue,DockRect(0,0,420,580));l.dock(DockId.Queue,DockId.Player,false)
        l.maximize(DockId.Library,DockRect(0,0,1800,900))
        l.close(DockId.Player)
        assertJoined(l,DockId.Library)
        assertEquals(1380,l.bounds.getValue(DockId.Library).width)
        l.close(DockId.Library)
        assertEquals(DockId.Queue,l.group(DockId.Queue).leader)
        assertEquals(1800,l.bounds.getValue(DockId.Queue).width)
    }
    @Test fun snappingOnlyAtOutsideEdgesWithVerticalAlignment() {
        val l=DockLayout()
        l.create(DockId.Library,DockRect(100,100,960,740))
        l.create(DockId.Player,DockRect(1068,110,320,560))
        assertEquals(DockCandidate(DockId.Player,DockId.Library,false),l.candidate(DockId.Player))
        l.move(DockId.Player,0,100)
        assertNull(l.candidate(DockId.Player))
    }
    @Test fun constraintMovesWholeGroupAcrossNegativeMonitorCoordinates() {
        val l=layout();l.move(DockId.Library,-3000,-100)
        l.constrain(DockId.Library,DockRect(-1920,0,1920,1080))
        assertJoined(l,DockId.Library)
        assertEquals(-1920,l.bounds.getValue(DockId.Library).x)
        assertEquals(0,l.bounds.getValue(DockId.Library).y)
    }
    @Test fun reorderSwapsOnSameSideWithoutMovingChief() {
        val l=layout()
        l.create(DockId.Lyrics,DockRect(0,0,480,740));l.dock(DockId.Lyrics,DockId.Player,false)
        l.create(DockId.Queue,DockRect(0,0,420,740));l.dock(DockId.Queue,DockId.Lyrics,false)
        val chief=l.bounds.getValue(DockId.Library)
        l.reorder(DockId.Player,DockId.Queue)
        assertEquals(listOf(DockId.Library,DockId.Queue,DockId.Lyrics,DockId.Player),l.group(DockId.Library).members)
        assertEquals(chief,l.bounds[DockId.Library]);assertJoined(l,DockId.Library)
    }
    @Test fun reorderAcrossChiefSwapsAndRecalculatesDependents() {
        val l=layout()
        l.create(DockId.Lyrics,DockRect(0,0,480,740));l.dock(DockId.Lyrics,DockId.Library,true)
        l.create(DockId.Queue,DockRect(0,0,420,740));l.dock(DockId.Queue,DockId.Player,false)
        val left=l.bounds.getValue(DockId.Lyrics).x
        val right=l.bounds.getValue(DockId.Queue).let { it.x + it.width }
        l.reorder(DockId.Player,DockId.Lyrics)
        assertEquals(listOf(DockId.Player,DockId.Library,DockId.Lyrics,DockId.Queue),l.group(DockId.Library).members)
        assertEquals(left,l.bounds.getValue(DockId.Player).x)
        assertEquals(right,l.bounds.getValue(DockId.Queue).let { it.x + it.width })
        l.detach(DockId.Player)
        assertEquals(listOf(DockId.Player),l.group(DockId.Player).members)
        assertEquals(listOf(DockId.Library,DockId.Lyrics,DockId.Queue),l.group(DockId.Library).members)
        assertJoined(l,DockId.Library)
    }
    @Test fun newPositionDeterminesCarriedDependents() {
        val l=layout()
        l.create(DockId.Lyrics,DockRect(0,0,480,740));l.dock(DockId.Lyrics,DockId.Player,false)
        l.create(DockId.Queue,DockRect(0,0,420,740));l.dock(DockId.Queue,DockId.Lyrics,false)
        l.reorder(DockId.Lyrics,DockId.Player)
        l.detach(DockId.Player)
        assertEquals(listOf(DockId.Player,DockId.Queue),l.group(DockId.Player).members)
        assertEquals(listOf(DockId.Library,DockId.Lyrics),l.group(DockId.Library).members)
    }
    @Test fun equalRankReceiverRemainsChief() {
        val l=DockLayout()
        l.create(DockId.Queue,DockRect(50,50,420,580))
        l.create(DockId.Lyrics,DockRect(0,0,480,680))
        l.dock(DockId.Lyrics,DockId.Queue,true)
        assertEquals(DockId.Queue,l.group(DockId.Queue).leader)
        l.move(DockId.Queue,20,30);assertJoined(l,DockId.Queue)
    }
    @Test fun reopenRecoversAttachmentAndStandalonePlacement() {
        val l=layout();val area=DockRect(0,0,1920,1080)
        l.close(DockId.Player);assertTrue(l.open(DockId.Player,DockId.Library,area))
        assertJoined(l,DockId.Library)
        l.detach(DockId.Player);l.move(DockId.Player,0,100)
        val rect=l.bounds.getValue(DockId.Player)
        l.close(DockId.Player);l.open(DockId.Player,DockId.Library,area)
        assertEquals(listOf(DockId.Player),l.group(DockId.Player).members)
        assertEquals(rect,l.bounds[DockId.Player])
    }
    @Test fun noLibraryUsesLyricsThenQueueAsFlexible() {
        val l=DockLayout()
        l.create(DockId.Player,DockRect(0,0,320,560))
        l.create(DockId.Lyrics,DockRect(0,0,480,680));l.dock(DockId.Lyrics,DockId.Player,false)
        l.create(DockId.Queue,DockRect(0,0,420,580));l.dock(DockId.Queue,DockId.Lyrics,false)
        assertTrue(l.maximize(DockId.Player,DockRect(0,0,1800,900)))
        assertEquals(1060,l.bounds.getValue(DockId.Lyrics).width)
        assertEquals(320,l.bounds.getValue(DockId.Player).width)
        l.close(DockId.Lyrics);assertEquals(1480,l.bounds.getValue(DockId.Queue).width)
    }
    @Test fun narrowScreensRefuseExtraWindowWithoutBreakingGroup() {
        val l=layout()
        val area=DockRect(60,10,1100,800)
        l.create(DockId.Queue,DockRect(100,100,420,580))
        assertFalse(l.dock(DockId.Queue,DockId.Player,false,area))
        assertJoined(l,DockId.Library)
        assertEquals(1,l.group(DockId.Queue).members.size)
    }
    @Test fun persistenceRoundTripsClosedWindowsAndMaximizedGeometry() {
        val l=layout()
        l.create(DockId.Lyrics,DockRect(0,0,480,680));l.dock(DockId.Lyrics,DockId.Player,false)
        l.close(DockId.Lyrics)
        l.maximize(DockId.Library,DockRect(60,10,1850,1060))
        val restored=decodeDockLayout(l.encode())!!
        assertEquals(l.encode(),restored.encode())
        restored.maximize(DockId.Library,DockRect(60,10,1850,1060))
        assertEquals(960,restored.bounds.getValue(DockId.Library).width)
    }
    @Test fun invalidPersistenceFallsBackInsteadOfCrashing() {
        assertNull(decodeDockLayout("not json"))
        assertNull(decodeDockLayout("{\"version\":99}"))
        val l=layout()
        val invalid=l.encode().replace("\"leader\":\"Library\"","\"leader\":\"Queue\"")
        assertNull(decodeDockLayout(invalid))
    }
    @Test fun hyprlandSessionRestoresPreviouslyMaximizedGroupsAndIgnoresMaximize() {
        val l=layout();l.maximize(DockId.Library,DockRect(0,0,1600,900))
        val session=DockSession(l,supportsMaximize=false,areas=listOf(DockRect(0,0,1920,1080)))
        assertFalse(l.group(DockId.Library).maximized)
        session.maximize(DockId.Library)
        assertFalse(l.group(DockId.Library).maximized)
        assertEquals(960,l.bounds.getValue(DockId.Library).width)
    }

    @Test fun firstOpenBalancesBothSidesButRememberedPositionWins() {
        val l=layout();val area=DockRect(0,0,2400,1200)
        assertTrue(l.open(DockId.Queue,DockId.Library,area))
        assertEquals(listOf(DockId.Queue,DockId.Library,DockId.Player),l.group(DockId.Library).members)
        assertTrue(l.open(DockId.Lyrics,DockId.Library,area))
        assertEquals(listOf(DockId.Queue,DockId.Library,DockId.Player,DockId.Lyrics),l.group(DockId.Library).members)
        l.reorder(DockId.Queue,DockId.Lyrics)
        val order=l.group(DockId.Library).members.toList()
        l.close(DockId.Queue);l.open(DockId.Queue,DockId.Library,area)
        assertEquals(order,l.group(DockId.Library).members)
    }
    @Test fun recoveryKeepsDisconnectedMonitorWindowsReachableAndSplitsIfNeeded() {
        val l=layout()
        l.create(DockId.Queue,DockRect(0,0,420,740));l.dock(DockId.Queue,DockId.Player,false)
        l.move(DockId.Library,3000,0)
        l.recover(listOf(DockRect(60,10,1100,800)))
        assertTrue(l.groups.size>1)
        l.groups.forEach { g ->
            val first=l.bounds.getValue(g.members.first())
            assertTrue(first.x>=60);assertTrue(first.y>=10)
            assertJoined(l,g.leader)
        }
    }

    @Test fun mixedScaleMonitorGapPicksNearestScreen() {
        // 2K al 125 % (2048 lógicos) y a su derecha 1080p al 100 %, que AWT empieza en x=2560.
        val left=DockRect(0,0,2048,1112); val right=DockRect(2560,0,1920,1040)
        assertEquals(left,nearestArea(listOf(left,right),2100,300))
        assertEquals(right,nearestArea(listOf(left,right),2500,300))
        assertEquals(right,nearestArea(listOf(left,right),3000,300))
        val l=layout()
        l.move(DockId.Library,2400-20,0)
        l.recover(listOf(left,right))
        val first=l.bounds.getValue(l.group(DockId.Library).members.first())
        assertTrue(first.x>=2560)
        assertJoined(l,DockId.Library)
    }

    @Test fun detachingAndClosingOnLeftKeepChiefAnchored() {
        val l=layout();l.create(DockId.Queue,DockRect(0,0,420,740));l.dock(DockId.Queue,DockId.Library,true)
        val chief=l.bounds.getValue(DockId.Library)
        l.detach(DockId.Queue);assertEquals(chief,l.bounds[DockId.Library])
        l.dock(DockId.Queue,DockId.Library,true);l.close(DockId.Queue)
        assertEquals(chief,l.bounds[DockId.Library])
    }
    @Test fun reservedAreaHandlesScaledNegativeAndDisconnectedCoordinates() {
        val raw="""[{"width":3840,"height":2160,"scale":2,"transform":0,"x":-1920,"y":0,"reserved":[40,10,10,10],"focused":true}]"""
        val expected=DockRect(-1880,10,1870,1060)
        assertEquals(expected,hyprDesktopArea(raw,java.awt.Point(-1000,100)))
        assertEquals(expected,hyprDesktopArea(raw,java.awt.Point(-9000,-9000)))
        val rotated=raw.replace("\"transform\":0","\"transform\":1")
        assertEquals(DockRect(-1880,10,1030,1900),hyprDesktopArea(rotated,java.awt.Point(-1000,100)))
    }

}
