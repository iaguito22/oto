package io.github.iaguito22.oto.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.*
import javax.swing.JComponent
import javax.swing.JWindow
import kotlin.math.roundToInt

private val onWindows = System.getProperty("os.name").startsWith("Windows")

/** Pantallas como las ve AWT, con su escala (ver [SnapScreen]). */
internal fun snapScreens(): List<SnapScreen> = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val c = device.defaultConfiguration; val b = c.bounds
        SnapScreen(DockRect(b.x, b.y, b.width, b.height), desktopArea(Point(b.x + b.width / 2, b.y + b.height / 2)),
            if (onWindows) c.defaultTransform.scaleX else 1.0)
    }
}.getOrDefault(emptyList())

/** Botón principal pulsado ahora mismo (respeta «intercambiar botones» de Windows). */
internal fun primaryButtonDown(): Boolean {
    if (!onWindows) return false
    return runCatching {
        val swapped = User32.INSTANCE.GetSystemMetrics(23) != 0 // SM_SWAPBUTTON
        User32.INSTANCE.GetAsyncKeyState(if (swapped) 0x02 else 0x01).toInt() and 0x8000 != 0
    }.getOrDefault(false)
}

private fun hwndOf(window: Window) = WinDef.HWND(Native.getWindowPointer(window))

internal fun physicalBounds(window: Window): DockRect? {
    if (!onWindows || !window.isShowing) return null
    return runCatching {
        val r = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(hwndOf(window), r)) null else DockRect(r.left, r.top, r.right - r.left, r.bottom - r.top)
    }.getOrNull()
}

private interface DeferPos : com.sun.jna.Library {
    fun BeginDeferWindowPos(count: Int): Pointer?
    fun DeferWindowPos(info: Pointer, window: WinDef.HWND, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Pointer?
    fun EndDeferWindowPos(info: Pointer): Boolean
    fun SystemParametersInfoW(action: Int, param: Int, out: com.sun.jna.ptr.IntByReference, winIni: Int): Boolean
    fun IsZoomed(window: WinDef.HWND): Boolean
    fun IsWindowArranged(window: WinDef.HWND): Boolean
}
private val deferPos by lazy { Native.load("user32", DeferPos::class.java) }

/**
 * Arrastre del propio Windows (el mismo bucle que la barra de título): así Windows hace su Snap (mitades,
 * cuartos, maximizar arriba, diseños de Windows 11) y el cambio de escala entre pantallas. Windows solo
 * ajusta ventanas que se pueden redimensionar y maximizar, así que se le dan esos estilos durante el gesto.
 */
internal fun beginSystemMove(window: Window): Boolean {
    if (!onWindows || !window.isShowing) return false
    return runCatching {
        val hwnd = hwndOf(window)
        if (!subclass(hwnd)) return false
        moveEnded.remove(hwnd.pointer)
        setSnappable(window, true)
        // Lo atiende el hilo de la ventana (ver [subclass]): suelta la captura de AWT y entra en el bucle de mover.
        User32.INSTANCE.PostMessage(hwnd, WM_OTO_MOVE, WinDef.WPARAM(0), WinDef.LPARAM(0))
        true
    }.getOrDefault(false)
}

private const val WM_OTO_MOVE = 0x8000 + 0x4F7
private const val WM_NCCALCSIZE = 0x0083
private interface WndProc : com.sun.jna.win32.StdCallLibrary.StdCallCallback {
    fun callback(hwnd: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT
}
private interface WinProcs : com.sun.jna.win32.StdCallLibrary {
    fun SetWindowLongPtrW(hwnd: WinDef.HWND, index: Int, proc: WndProc): Pointer?
    fun GetWindowLongPtrW(hwnd: WinDef.HWND, index: Int): Pointer?
    fun CallWindowProcW(previous: Pointer, hwnd: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT
    fun DefWindowProcW(hwnd: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT
    fun ReleaseCapture(): Boolean
}
private val procs by lazy { Native.load("user32", WinProcs::class.java) }
/** Ventanas con el procedimiento de Oto delante del de AWT; la referencia evita que se libere el callback. */
private val subclassed = java.util.concurrent.ConcurrentHashMap<Pointer, WndProc>()
private val moveEnded = java.util.concurrent.ConcurrentHashMap<Pointer, Long>()

/** Momento (nanoTime) en que acabó el último bucle de mover de Windows en [window], o null si sigue. */
internal fun systemMoveEnded(window: Window): Long? =
    if (!onWindows || !window.isShowing) System.nanoTime() else runCatching { moveEnded[hwndOf(window).pointer] }.getOrNull()

/**
 * Un procedimiento propio delante del de AWT, que corre en el hilo de la ventana:
 * - [WM_OTO_MOVE]: la captura del ratón es de ese hilo (AWT la toma al pulsar), y con ella puesta Windows
 *   no arranca el bucle de mover. Se suelta allí y se entra en SC_DRAGMOVE como desde una barra de título.
 * - WM_NCCALCSIZE: con WS_THICKFRAME Windows reservaría un marco; toda la ventana sigue siendo contenido.
 */
private fun subclass(hwnd: WinDef.HWND): Boolean {
    val key = hwnd.pointer
    if (subclassed.containsKey(key)) return true
    val previous = procs.GetWindowLongPtrW(hwnd, -4) ?: return false
    val proc = object : WndProc {
        override fun callback(hwnd: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT {
            when (msg) {
                WM_OTO_MOVE -> {
                    procs.ReleaseCapture()
                    // Vuelve cuando Windows ha terminado el bucle, con su Snap o maximizado ya aplicado.
                    try { return procs.DefWindowProcW(hwnd, 0x0112, WinDef.WPARAM(0xF012), WinDef.LPARAM(0)) }
                    finally { moveEnded[key] = System.nanoTime() }
                }
                WM_NCCALCSIZE -> if (wParam.toLong() != 0L) return WinDef.LRESULT(0)
            }
            return procs.CallWindowProcW(previous, hwnd, msg, wParam, lParam)
        }
    }
    subclassed[key] = proc
    if (procs.SetWindowLongPtrW(hwnd, -4, proc) == null) { subclassed.remove(key); return false }
    return true
}

/** WS_THICKFRAME | WS_MAXIMIZEBOX: sin ellos Windows no ofrece Snap a la ventana. */
internal fun setSnappable(window: Window, on: Boolean) {
    if (!onWindows || !window.isShowing) return
    runCatching {
        val hwnd = hwndOf(window)
        val style = User32.INSTANCE.GetWindowLong(hwnd, -16)
        val bits = 0x00040000 or 0x00010000
        val next = if (on) style or bits else style and bits.inv()
        if (next == style) return
        User32.INSTANCE.SetWindowLong(hwnd, -16, next)
        // SWP_FRAMECHANGED | NOMOVE | NOSIZE | NOZORDER | NOACTIVATE
        User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x0020 or 0x0002 or 0x0001 or 0x0004 or 0x0010)
    }
}

/** Windows la tiene ajustada (Snap: mitad, cuarto, tercio...). Windows 10 1903 o posterior. */
internal fun isArranged(window: Window): Boolean =
    onWindows && window.isShowing && runCatching { deferPos.IsWindowArranged(hwndOf(window)) }.getOrDefault(false)

internal fun isZoomed(window: Window): Boolean =
    onWindows && window.isShowing && runCatching { deferPos.IsZoomed(hwndOf(window)) }.getOrDefault(false)

/** Mueve varias ventanas a la vez a posiciones físicas, sin cambiar su tamaño ni activarlas. */
internal fun placePhysical(moves: List<Pair<Window, Point>>): Boolean {
    if (!onWindows || moves.isEmpty()) return false
    return runCatching {
        var info = deferPos.BeginDeferWindowPos(moves.size) ?: return false
        for ((w, p) in moves) {
            if (!w.isShowing) continue
            // SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER
            info = deferPos.DeferWindowPos(info, hwndOf(w), null, p.x, p.y, 0, 0, 0x0001 or 0x0004 or 0x0010 or 0x0200) ?: return false
        }
        deferPos.EndDeferWindowPos(info)
    }.getOrDefault(false)
}

/**
 * Ajustes de Windows (Configuración > Sistema > Multitarea): si el usuario apagó «Ajustar ventanas»,
 * Oto tampoco ajusta. SPI_GETWINARRANGING (0x0082) es el interruptor general; SPI_GETDOCKMOVING
 * (0x0090), ajustar al arrastrar a los bordes; SPI_GETDRAGFROMMAXIMIZE (0x008C), restaurar al arrastrar.
 */
internal object WindowsSnapSettings {
    private fun flag(action: Int): Boolean {
        if (!onWindows) return true
        return runCatching {
            val out = com.sun.jna.ptr.IntByReference()
            if (deferPos.SystemParametersInfoW(action, 0, out, 0)) out.value != 0 else true
        }.getOrDefault(true)
    }
    val arranging get() = flag(0x0082)
    val dragFromMaximize get() = arranging && flag(0x008C)
}

/** Escala de la pantalla en la que Windows tiene ahora la ventana. */
internal fun windowScale(window: Window): Double =
    if (onWindows) runCatching { window.graphicsConfiguration.defaultTransform.scaleX }.getOrDefault(1.0) else 1.0

internal fun Int.scaled(scale: Double) = (this * scale).roundToInt()
