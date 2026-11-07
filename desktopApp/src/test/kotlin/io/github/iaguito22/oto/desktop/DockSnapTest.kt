package io.github.iaguito22.oto.desktop

import org.junit.Assert.*
import org.junit.Test

/**
 * Disposición real de Iago (07/11/2026): 1080p al 100 % como principal (barra de tareas abajo) y,
 * a su izquierda, un 2K vertical al 125 % (1440×2560 físicos) cuyo borde inferior está en y=619
 * físico. AWT (medido con ScreenProbeTest) lo ve en (-1440,-1941) con 1152×2048: origen físico, tamaño lógico.
 */
class DockSnapTest {
    private val primary = SnapScreen(DockRect(0, 0, 1920, 1080), DockRect(0, 0, 1920, 1032), 1.0)
    private val portrait = SnapScreen(DockRect(-1440, -1941, 1152, 2048), DockRect(-1440, -1941, 1152, 2048), 1.25)
    private val screens = listOf(primary, portrait)

    @Test fun halvesAndQuartersCoverTheWorkArea() {
        val w = primary.work
        assertEquals(DockRect(0, 0, 960, 1032), snapRect(SnapZone.Left, w))
        assertEquals(DockRect(960, 0, 960, 1032), snapRect(SnapZone.Right, w))
        assertEquals(DockRect(0, 516, 960, 516), snapRect(SnapZone.BottomLeft, w))
        assertEquals(w, snapRect(SnapZone.Top, w))
        // Anchura impar: la mitad derecha se queda el píxel sobrante, sin hueco ni solape.
        val odd = DockRect(-1440, -1941, 1151, 2047)
        val l = snapRect(SnapZone.Left, odd); val r = snapRect(SnapZone.Right, odd)
        assertEquals(odd.x + odd.width, r.x + r.width); assertEquals(l.x + l.width, r.x)
    }

    @Test fun windowsSnapRectsAreRecognisedOnEachScreen() {
        // Donde Windows deja la ventana arrastrada (rectángulos de AWT medidos en el arnés).
        assertEquals(SnapZone.Right, matchSnap(screens, DockRect(960, 0, 960, 1032), 2)!!.zone)
        assertEquals(SnapZone.BottomRight, matchSnap(screens, DockRect(960, 516, 960, 516), 2)!!.zone)
        val left = matchSnap(screens, DockRect(-1440, -1941, 576, 2048), 2)!!
        assertEquals(SnapZone.Left, left.zone); assertSame(portrait, left.screen)
        assertNull(matchSnap(screens, DockRect(37, 203, 961, 640), 2))
    }

    @Test fun physicalAndAwtCoordinatesRoundTrip() {
        // Centro de la vertical en AWT -> físico, como lo midió la sonda (-1240,-1041) -> (-1190,-816).
        assertEquals(java.awt.Point(-1190, -816), portrait.toPhysical(-1240, -1041))
        assertEquals(java.awt.Point(-1240, -1041), portrait.toAwt(-1190, -816))
        assertSame(portrait, screenAtPhysical(screens, -10, 600))
        assertSame(primary, screenAtPhysical(screens, -10, 700)) // fuera de ambas: la más cercana
    }

    @Test fun droppedGroupGoesToTheScreenItCoversMost() {
        // Medido en el arnés: soltado con el puntero en x=-1 (vertical) pero el conjunto en (-341,-127) físicos,
        // casi todo en la principal. Antes se calculaba con la escala de la vertical y volvía atrás.
        assertSame(primary, dropScreen(screens, -341, -127, 1000, 640))
        assertSame(portrait, dropScreen(screens, -1300, -1200, 1000, 640))
        // Por encima de la principal no hay pantalla: cuenta la que más tapa (la vertical, a la izquierda).
        assertSame(portrait, dropScreen(screens, -900, -400, 1000, 640))
    }

    @Test fun threeWindowsDroppedOnThePortraitShrinkInsteadOfSplitting() {
        // Medido: letra + Tu música + reproductor (480+960+360) no caben en 1152; antes la letra se separaba.
        val l = DockLayout().apply {
            create(DockId.Lyrics, DockRect(0, 196, 480, 640))
            create(DockId.Library, DockRect(480, 196, 960, 640)); assertTrue(dock(DockId.Lyrics, DockId.Library, true))
            create(DockId.Player, DockRect(1440, 196, 360, 640)); assertTrue(dock(DockId.Player, DockId.Library, false))
        }
        l.move(DockId.Library, -1400, -1400)
        l.settleInto(DockId.Library, portrait.work)
        val g = l.group(DockId.Library)
        assertEquals(listOf(DockId.Lyrics, DockId.Library, DockId.Player), g.members)
        val first = l.bounds.getValue(g.members.first()); val last = l.bounds.getValue(g.members.last())
        assertTrue(first.x >= portrait.work.x); assertTrue(last.x + last.width <= portrait.work.x + portrait.work.width)
        g.members.zipWithNext().forEach { (a, b) -> assertEquals(l.bounds.getValue(a).let { it.x + it.width }, l.bounds.getValue(b).x) }
        assertTrue(g.members.all { l.bounds.getValue(it).width >= it.minWidth })
        // De vuelta en la principal recuperan su ancho.
        l.move(DockId.Library, 1500, 1500)
        l.settleInto(DockId.Library, primary.work)
        assertEquals(listOf(480, 960, 360), g.members.map { l.bounds.getValue(it).width })
        assertTrue(l.squeezed.isEmpty())
    }

    @Test fun nativeWinArrowPlacementIsRecognised() {
        assertEquals(SnapZone.Left, matchSnap(screens, DockRect(0, 0, 960, 1032))!!.zone)
        assertEquals(SnapZone.Right, matchSnap(screens, DockRect(-864, -1941, 576, 2048))!!.zone)
        assertNull(matchSnap(screens, DockRect(100, 100, 800, 600)))
    }

    private fun group(): DockLayout = DockLayout().apply {
        create(DockId.Library, DockRect(100, 100, 960, 740))
        create(DockId.Player, DockRect(1060, 100, 320, 740))
        assertTrue(dock(DockId.Player, DockId.Library, false))
    }
    private fun assertJoinedInside(l: DockLayout, area: DockRect) {
        val m = l.group(DockId.Library).members
        m.zipWithNext().forEach { (a, b) ->
            val x = l.bounds.getValue(a); val y = l.bounds.getValue(b)
            assertEquals(x.x + x.width, y.x); assertEquals(x.y, y.y); assertEquals(x.height, y.height)
        }
        val first = l.bounds.getValue(m.first()); val last = l.bounds.getValue(m.last())
        assertEquals(area.x, first.x); assertEquals(area.x + area.width, last.x + last.width)
        assertEquals(area.y, first.y); assertEquals(area.height, first.height)
    }

    @Test fun snappingTheWholeGroupFillsTheHalfAndRestoresTheNormalSize() {
        val l = group(); val normal = l.bounds.toMap()
        val left = snapRect(SnapZone.Left, primary.work)
        assertTrue(l.snap(DockId.Library, left))
        assertJoinedInside(l, left)
        assertEquals(320, l.bounds.getValue(DockId.Player).width)
        // De la mitad izquierda a la derecha, y de ahí a maximizado: siguen juntas y llenan el hueco.
        val right = snapRect(SnapZone.Right, primary.work)
        assertTrue(l.snap(DockId.Library, right)); assertJoinedInside(l, right)
        assertTrue(l.snap(DockId.Library, primary.work)); assertJoinedInside(l, primary.work)
        // Restaurar vuelve al tamaño de antes del primer ajuste.
        assertTrue(l.maximize(DockId.Library, primary.work))
        assertFalse(l.group(DockId.Library).maximized)
        assertEquals(normal[DockId.Library]!!.width, l.bounds.getValue(DockId.Library).width)
        assertEquals(normal[DockId.Library]!!.height, l.bounds.getValue(DockId.Library).height)
    }

    @Test fun snappingIntoTheNarrowPortraitHalfFailsCleanly() {
        val l = group()
        l.create(DockId.Lyrics, DockRect(0, 0, 480, 740)); assertTrue(l.dock(DockId.Lyrics, DockId.Player, false))
        val before = l.bounds.toMap()
        // 576 lógicos de ancho no caben Tu música (640 mín.) + reproductor + letra.
        assertFalse(l.snap(DockId.Library, snapRect(SnapZone.Left, portrait.work)))
        assertEquals(before, l.bounds.toMap())
        assertFalse(l.group(DockId.Library).maximized)
        // Pero la vertical entera sí: letra y reproductor encogen solo lo necesario (sin bajar de su mínimo).
        assertTrue(l.snap(DockId.Library, portrait.work)); assertJoinedInside(l, portrait.work)
        assertEquals(DockId.Library.minWidth, l.bounds.getValue(DockId.Library).width)
        assertTrue(l.group(DockId.Library).members.all { l.bounds.getValue(it).width >= it.minWidth })
    }

    @Test fun snappedGroupMovingToAnotherSnapKeepsFailedAttemptHarmless() {
        val l = group()
        val left = snapRect(SnapZone.Left, primary.work)
        assertTrue(l.snap(DockId.Library, left))
        val before = l.bounds.toMap()
        // Un cuarto de la vertical (576×1024) no cabe: el conjunto se queda en su mitad.
        assertFalse(l.snap(DockId.Library, DockRect(-1440, -1941, 400, 1024)))
        assertEquals(before[DockId.Library], l.bounds[DockId.Library])
        assertTrue(l.group(DockId.Library).maximized)
    }
}
