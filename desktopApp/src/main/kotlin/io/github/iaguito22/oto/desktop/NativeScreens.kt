package io.github.iaguito22.oto.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.*
import java.util.concurrent.atomic.AtomicReferenceArray
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

/** Fallback cuando el puntero cae en el lienzo de Compose en vez del borde no cliente. */
internal fun beginSingleWindowResize(window: Window, edge: Int): Boolean {
    if (!onWindows || !window.isShowing || isZoomed(window)) return false
    return runCatching {
        setSnappable(window, true)
        val cursor = WinDef.POINT()
        if (!User32.INSTANCE.GetCursorPos(cursor)) return false
        val pt = (cursor.x and 0xFFFF) or ((cursor.y and 0xFFFF) shl 16)
        User32.INSTANCE.PostMessage(hwndOf(window), WM_OTO_RESIZE, WinDef.WPARAM((0xF000 or edge).toLong()), WinDef.LPARAM(pt.toLong()))
        true
    }.getOrDefault(false)
}

private const val WM_OTO_ROUND = 0x8000 + 0x4F9
private const val WM_SIZING = 0x0214
private const val WM_EXITSIZEMOVE = 0x0232
private const val WM_OTO_RESIZE = 0x8000 + 0x4F8
private const val WM_OTO_MOVE = 0x8000 + 0x4F7
private const val WM_NCCALCSIZE = 0x0083
private const val WM_NCHITTEST = 0x0084
private const val WM_NCDESTROY = 0x0082
private const val WM_NCLBUTTONDOWN = 0x00A1
private const val WM_SYSCOMMAND = 0x0112
private const val WM_NCLBUTTONDBLCLK = 0x00A3
private interface WndProc : com.sun.jna.win32.StdCallLibrary.StdCallCallback {
    fun callback(hwnd: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT
}
private interface WinProcs : com.sun.jna.win32.StdCallLibrary {
    fun SetWindowLongPtrW(hwnd: WinDef.HWND, index: Int, proc: WndProc): Pointer?
    fun GetWindowLongPtrW(hwnd: WinDef.HWND, index: Int): Pointer?
    fun CallWindowProcW(previous: Pointer, hwnd: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT
    fun DefWindowProcW(hwnd: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT
    fun ScreenToClient(hwnd: WinDef.HWND, point: WinDef.POINT): Boolean
    fun ReleaseCapture(): Boolean
}
private val procs by lazy { Native.load("user32", WinProcs::class.java) }
/** Ventanas con el procedimiento de Oto delante del de AWT; la referencia evita que se libere el callback. */
private val subclassed = java.util.concurrent.ConcurrentHashMap<Pointer, WndProc>()
private val managedFrames = java.util.concurrent.ConcurrentHashMap.newKeySet<Pointer>()
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
internal fun installTaskbarMessageHook(window: Window): Boolean =
    onWindows && window.isDisplayable && runCatching { subclass(hwndOf(window), manageFrame = false) }.getOrDefault(false)

private fun subclass(hwnd: WinDef.HWND, caption: SingleCaptionZones? = null, manageFrame: Boolean = true): Boolean {
    val key = hwnd.pointer
    if (subclassed.containsKey(key)) {
        if (manageFrame) managedFrames.add(key)
        return true
    }
    val previous = procs.GetWindowLongPtrW(hwnd, -4) ?: return false
    val proc = object : WndProc {
        override fun callback(hwnd: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT {
            if (dispatchTaskbarThumbnailMessage(key, msg, wParam.toInt())) return WinDef.LRESULT(0)
            when (msg) {
                WM_OTO_MOVE -> {
                    procs.ReleaseCapture()
                    // Vuelve cuando Windows ha terminado el bucle, con su Snap o maximizado ya aplicado.
                    try { return procs.DefWindowProcW(hwnd, 0x0112, WinDef.WPARAM(0xF012), WinDef.LPARAM(0)) }
                    finally { moveEnded[key] = System.nanoTime() }
                }
                WM_OTO_ROUND -> if (caption != null) {
                    val radius = wParam.toInt()
                    if (sizingRadii.containsKey(key)) sizingRadii[key] = radius
                    else applySingleWindowRegion(hwnd, radius.takeIf { it > 0 })
                    return WinDef.LRESULT(0)
                }
                WM_SIZING -> if (caption != null && !sizingRadii.containsKey(key)) {
                    sizingRadii[key] = singleRegions[key]?.radius ?: 0
                    // Una región del tamaño antiguo corta la imagen mientras la ventana crece.
                    applySingleWindowRegion(hwnd, null)
                }
                WM_EXITSIZEMOVE -> if (caption != null) {
                    sizingRadii.remove(key)?.takeIf { it > 0 }?.let { radius ->
                        if (!deferPos.IsZoomed(hwnd) && !runCatching { deferPos.IsWindowArranged(hwnd) }.getOrDefault(false)) {
                            applySingleWindowRegion(hwnd, radius)
                        }
                    }
                }
                WM_OTO_RESIZE -> if (caption != null) {
                    if (!primaryButtonDown()) return WinDef.LRESULT(0)
                    procs.ReleaseCapture()
                    return procs.DefWindowProcW(hwnd, WM_SYSCOMMAND, wParam, lParam)
                }
                WM_NCCALCSIZE -> if (wParam.toLong() != 0L && key in managedFrames) {
                    if (caption != null && deferPos.IsZoomed(hwnd)) {
                        val user = User32.INSTANCE
                        val rect = Pointer(lParam.toLong())
                        val proposed = WinDef.RECT().apply {
                            left = rect.getInt(0)
                            top = rect.getInt(4)
                            right = rect.getInt(8)
                            bottom = rect.getInt(12)
                        }
                        val monitor = user.MonitorFromRect(proposed, 2)
                        val info = WinUser.MONITORINFO()
                        if (user.GetMonitorInfo(monitor, info).booleanValue()) {
                            rect.setInt(0, info.rcWork.left)
                            rect.setInt(4, info.rcWork.top)
                            rect.setInt(8, info.rcWork.right)
                            rect.setInt(12, info.rcWork.bottom)
                        }
                    }
                    return WinDef.LRESULT(0)
                }
                WM_NCHITTEST -> if (caption != null) {
                    val bounds = WinDef.RECT()
                    if (User32.INSTANCE.GetWindowRect(hwnd, bounds)) {
                        val x = lParam.toLong().toShort().toInt()
                        val y = (lParam.toLong() shr 16).toShort().toInt()
                        if (!deferPos.IsZoomed(hwnd)) {
                            val result = resizeHit(x, y, bounds, singleResizeBorder(hwnd), caption.fixedHeight || caption.onMiniDoubleClick != null,
                                singleRegions[key]?.radius ?: 0)
                            if (result != 0) return WinDef.LRESULT(result.toLong())
                        }
                        val client = WinDef.POINT(x, y)
                        if (procs.ScreenToClient(hwnd, client) && caption.contains(client.x, client.y)) {
                            return WinDef.LRESULT(2) // HTCAPTION
                        }
                    }
                }
                WM_NCLBUTTONDOWN -> if (caption != null && wParam.toInt() in 10..17) {
                    // AWT trata una ventana sin decorar como área cliente. El borde es de Windows.
                    procs.ReleaseCapture()
                    return procs.DefWindowProcW(hwnd, msg, wParam, lParam)
                }
                WM_SYSCOMMAND -> if (caption != null && (wParam.toInt() and 0xFFF0) == 0xF000) {
                    return procs.DefWindowProcW(hwnd, msg, wParam, lParam)
                }
                WM_NCLBUTTONDBLCLK -> if (wParam.toInt() == 2) {
                    caption?.onMiniDoubleClick?.let { expand ->
                        EventQueue.invokeLater { expand() }
                        return WinDef.LRESULT(0)
                    }
                }
                WM_NCDESTROY -> {
                    subclassed.remove(key)
                    moveEnded.remove(key)
                    singleRegions.remove(key)
                    sizingRadii.remove(key)
                    managedFrames.remove(key)
                    forgetTaskbarThumbnail(key)
                }
            }
            return procs.CallWindowProcW(previous, hwnd, msg, wParam, lParam)
        }
    }
    subclassed[key] = proc
    if (procs.SetWindowLongPtrW(hwnd, -4, proc) == null) { subclassed.remove(key); return false }
    if (manageFrame) managedFrames.add(key)
    return true
}

private interface WindowDpi : com.sun.jna.win32.StdCallLibrary {
    fun GetDpiForWindow(hwnd: WinDef.HWND): Int
}

private val windowDpi by lazy { Native.load("user32", WindowDpi::class.java) }

private fun singleResizeBorder(hwnd: WinDef.HWND): Int =
    (5 * runCatching { windowDpi.GetDpiForWindow(hwnd) }.getOrDefault(96) / 96).coerceAtLeast(5)

private fun resizeHit(x: Int, y: Int, bounds: WinDef.RECT, border: Int, fixedHeight: Boolean, radius: Int): Int {
    val left = x - bounds.left
    val right = bounds.right - 1 - x
    val top = y - bounds.top
    val bottom = bounds.bottom - 1 - y
    val horizontal = minOf(left, right)
    val vertical = minOf(top, bottom)
    if (fixedHeight) return when {
        left < border -> 10
        right < border -> 11
        else -> 0
    }
    if (horizontal < radius && vertical < radius) {
        val distance = kotlin.math.hypot((radius - horizontal).toDouble(), (radius - vertical).toDouble())
        if (radius - distance < border) return when {
            left <= right && top <= bottom -> 13
            left > right && top <= bottom -> 14
            left <= right -> 16
            else -> 17
        }
    }
    return when {
        top < border -> when { left < border -> 13; right < border -> 14; else -> 12 }
        bottom < border -> when { left < border -> 16; right < border -> 17; else -> 15 }
        left < border -> 10
        right < border -> 11
        else -> 0
    }
}

/** Zonas vacías del encabezado, en píxeles del área cliente. Las leen los mensajes de Windows. */
internal class SingleCaptionZones {
    private val rects = AtomicReferenceArray<DockRect>(3)
    @Volatile var suspended = false
    @Volatile var fixedHeight = false
    @Volatile var onMiniDoubleClick: (() -> Unit)? = null

    fun set(index: Int, bounds: DockRect?) { rects.set(index, bounds) }

    fun contains(x: Int, y: Int): Boolean {
        if (suspended) return false
        for (i in 0 until rects.length()) {
            val r = rects.get(i) ?: continue
            if (x >= r.x && x < r.x + r.width && y >= r.y && y < r.y + r.height) return true
        }
        return false
    }
}

private interface SingleWindowDwm : com.sun.jna.win32.StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: com.sun.jna.ptr.IntByReference, size: Int): Int
}

/** Oto dibuja todo el contenido. Windows sigue gestionando el marco y el Snap. */
internal fun enableSingleWindowFrame(window: Window, caption: SingleCaptionZones): Boolean {
    if (!onWindows || !window.isShowing) return false
    return runCatching {
        val hwnd = hwndOf(window)
        if (!subclass(hwnd, caption)) return false
        val user = User32.INSTANCE
        val style = user.GetWindowLong(hwnd, -16)
        val frame = 0x00C00000 or 0x00080000 or 0x00020000 // WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX
        if (style and frame != frame) user.SetWindowLong(hwnd, -16, style or frame)
        setSnappable(window, true)
        runCatching {
            val dwm = Native.load("dwmapi", SingleWindowDwm::class.java)
            dwm.DwmSetWindowAttribute(hwnd, 33, com.sun.jna.ptr.IntByReference(2), 4) // DWMWCP_ROUND
            dwm.DwmSetWindowAttribute(hwnd, 20, com.sun.jna.ptr.IntByReference(1), 4) // modo oscuro
        }
        true
    }.getOrDefault(false)
}

internal fun toggleSingleWindowMaximized(window: Window): Boolean = runCatching {
    val hwnd = hwndOf(window)
    val command = if (deferPos.IsZoomed(hwnd)) 0xF120 else 0xF030 // SC_RESTORE / SC_MAXIMIZE
    User32.INSTANCE.PostMessage(hwnd, 0x0112, WinDef.WPARAM(command.toLong()), WinDef.LPARAM(0))
    true
}.getOrDefault(false)

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

private interface RegionGdi : com.sun.jna.win32.StdCallLibrary {
    fun DeleteObject(region: WinDef.HRGN): Boolean
    fun CreateRoundRectRgn(left: Int, top: Int, right: Int, bottom: Int, w: Int, h: Int): WinDef.HRGN?
}
private val regionGdi by lazy { Native.load("gdi32", RegionGdi::class.java) }

private data class SingleRegion(val width: Int, val height: Int, val radius: Int?)
// Solo el hilo que atiende los mensajes de la ventana toca sus regiones.
private val singleRegions = java.util.concurrent.ConcurrentHashMap<Pointer, SingleRegion>()
private val sizingRadii = java.util.concurrent.ConcurrentHashMap<Pointer, Int>()

/** Recorta la ventana con esquinas de [radiusPx] (null = sin recorte, vuelve el redondeo normal de Windows 11). */
internal fun setSingleWindowRounded(window: Window, radiusPx: Int?) {
    if (!onWindows || !window.isShowing) return
    runCatching {
        User32.INSTANCE.PostMessage(hwndOf(window), WM_OTO_ROUND, WinDef.WPARAM((radiusPx ?: 0).toLong()), WinDef.LPARAM(0))
    }
}

private fun applySingleWindowRegion(hwnd: WinDef.HWND, radiusPx: Int?) {
    runCatching {
        val bounds = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(hwnd, bounds)) return
        val shape = if (radiusPx == null) SingleRegion(0, 0, null)
            else SingleRegion(bounds.right - bounds.left, bounds.bottom - bounds.top, radiusPx)
        if (singleRegions[hwnd.pointer] == shape) return
        val region = radiusPx?.let { regionGdi.CreateRoundRectRgn(0, 0, shape.width + 1, shape.height + 1, it * 2, it * 2) }
        if (radiusPx != null && region == null) return
        if (User32.INSTANCE.SetWindowRgn(hwnd, region, true) == 0) {
            if (region != null) regionGdi.DeleteObject(region)
            return
        }
        singleRegions[hwnd.pointer] = shape
        runCatching {
            val dwm = Native.load("dwmapi", SingleWindowDwm::class.java)
            dwm.DwmSetWindowAttribute(hwnd, 33, com.sun.jna.ptr.IntByReference(if (radiusPx == null) 2 else 1), 4)
        }
    }
}
