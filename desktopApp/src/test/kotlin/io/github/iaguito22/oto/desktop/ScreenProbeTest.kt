package io.github.iaguito22.oto.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.*
import javax.swing.SwingUtilities

/**
 * Sonda con pantallas reales (no corre en CI): OTO_SCREEN_PROBE=1. Mueve ventanas sin marco entre
 * monitores y anota lo que AWT cree frente a lo que Windows tiene en píxeles físicos.
 */
class ScreenProbeTest {
    private fun physical(w: Window): String {
        val r = WinDef.RECT(); User32.INSTANCE.GetWindowRect(WinDef.HWND(Native.getWindowPointer(w)), r)
        return "phys=(${r.left},${r.top} ${r.right - r.left}x${r.bottom - r.top})"
    }
    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun report(label: String, w: Window, want: Rectangle? = null) {
        var line = ""
        onEdt {
            val b = w.bounds; val c = w.graphicsConfiguration
            line = "%-28s awt=(${b.x},${b.y} ${b.width}x${b.height}) ${physical(w)} screen=${c.device.iDstring} scale=${c.defaultTransform.scaleX}".format(label) +
                (if (want != null && want != b) "  <-- PEDIDO $want" else "")
        }
        println(line)
    }

    @Test fun reopenedWindowReturnsToTheScreenWhereItWasClosed() {
        assumeTrue(System.getenv("OTO_SCREEN_PROBE") == "1")
        val screens = snapScreens()
        val other = screens.firstOrNull { it.scale != 1.0 } ?: return
        val primary = screens.first { it !== other }
        val l = DockLayout()
        l.create(DockId.Player, DockRect(primary.work.x + 600, primary.work.y + 200, 320, 600))
        // Tu música, suelta en la otra pantalla, y cerrada allí.
        l.create(DockId.Library, DockRect(other.work.x + 100, other.work.y + 300, 900, 700))
        l.close(DockId.Library)
        val session = DockSession(l, areas = screens.map { it.work })
        session.open(DockId.Library, DockId.Player)
        val r = l.bounds.getValue(DockId.Library)
        println("reabierta en $r (otra pantalla ${other.work})")
        org.junit.Assert.assertEquals(DockRect(other.work.x + 100, other.work.y + 300, 900, 700), r)
        org.junit.Assert.assertEquals(listOf(DockId.Library), l.group(DockId.Library).members)
    }

    @Test fun probe() {
        assumeTrue(System.getenv("OTO_SCREEN_PROBE") == "1")
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        for (d in ge.screenDevices) {
            val c = d.defaultConfiguration
            println("screen ${d.iDstring} bounds=${c.bounds} insets=${Toolkit.getDefaultToolkit().getScreenInsets(c)} scale=${c.defaultTransform.scaleX}")
        }
        println("areas=${desktopAreas()}")
        val frame = Frame("probe"); frame.isUndecorated = true
        onEdt { frame.bounds = Rectangle(200, 200, 480, 600); frame.isVisible = true }
        Thread.sleep(600); report("inicio primaria", frame)
        val other = ge.screenDevices.map { it.defaultConfiguration }.firstOrNull { it.defaultTransform.scaleX != 1.0 }?.bounds
        if (other == null) { println("sin segunda pantalla escalada"); onEdt { frame.dispose() }; return }
        val targets = listOf(
            "centro secundaria" to Rectangle(other.x + 200, other.y + 900, 480, 600),
            "vuelta a primaria" to Rectangle(200, 200, 480, 600),
            "cruza borde (mitad izq)" to Rectangle(-240, 200, 480, 600),
            "cruza borde (casi primaria)" to Rectangle(-100, 200, 480, 600),
            "cruza borde (casi secund.)" to Rectangle(-380, 200, 480, 600),
            "secundaria pegada a x=0" to Rectangle(-480, 100, 480, 600),
            "primaria otra vez" to Rectangle(600, 300, 480, 600),
        )
        for ((label, want) in targets) {
            onEdt { frame.bounds = want }
            Thread.sleep(700); report(label, frame, want)
        }
        // Paso a paso como un arrastre: 30 px por evento desde la primaria a la secundaria.
        println("--- arrastre simulado con setLocation")
        var x = 400
        while (x > -900) {
            val target = x
            onEdt { frame.setLocation(target, 250) }
            Thread.sleep(40)
            x -= 60
        }
        Thread.sleep(700); report("tras arrastre a (-860,250)", frame, Rectangle(x + 60, 250, 480, 600))
        // Dos ventanas acopladas que cruzan: ¿se ven alineadas?
        println("--- conjunto que cruza el borde")
        val second = Frame("probe2"); second.isUndecorated = true
        onEdt { frame.bounds = Rectangle(-400, 200, 400, 600); second.bounds = Rectangle(0, 200, 400, 600); second.isVisible = true }
        Thread.sleep(800); report("izquierda (secundaria)", frame); report("derecha (primaria)", second)
        onEdt { frame.dispose(); second.dispose() }
    }
}
