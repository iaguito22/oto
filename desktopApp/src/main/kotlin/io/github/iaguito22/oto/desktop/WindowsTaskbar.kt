package io.github.iaguito22.oto.desktop

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.PointerByReference
import java.awt.Window
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

private val taskbarIcons by lazy {
    listOf(16, 20, 24, 32, 40, 48, 64, 128, 256).map { size ->
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.scale(size / 256.0, size / 256.0)
                g.color = Color(241, 241, 241); g.fillOval(4, 4, 248, 248)
                g.color = Color(31, 31, 31); g.fillOval(28, 28, 200, 200)
                g.color = Color(241, 241, 241); g.fillOval(61, 52, 95, 95)
            } finally { g.dispose() }
        }
    }
}

internal fun useSharpTaskbarIcons(window: Window) {
    if (System.getProperty("os.name").startsWith("Windows")) window.iconImages = taskbarIcons
}

private val isWindows = System.getProperty("os.name").startsWith("Windows")
private fun hwnd(window: Window) = com.sun.jna.platform.win32.WinDef.HWND(Native.getWindowPointer(window))

/**
 * Coloca [window] justo debajo de [above] sin activarla. Window.toFront de AWT puede
 * quitarle el primer plano a la ventana principal, y entonces Windows ya no la
 * minimiza al volver a clicar su botón de la barra de tareas.
 */
internal fun placeBelowWithoutActivation(window: Window, above: Window): Boolean {
    if (!isWindows || !window.isShowing || !above.isShowing) return false
    return runCatching {
        val user = com.sun.jna.platform.win32.User32.INSTANCE
        // SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE | SWP_NOOWNERZORDER
        user.SetWindowPos(hwnd(window), hwnd(above), 0, 0, 0, 0, 0x0001 or 0x0002 or 0x0010 or 0x0200)
    }.getOrDefault(false)
}

/** Restaura [window] de minimizada sin activarla y la deja debajo de [above]. */
internal fun restoreBelowWithoutActivation(window: Window, above: Window): Boolean {
    if (!isWindows || !window.isShowing) return false
    return runCatching {
        val target = hwnd(window)
        if (((window as? java.awt.Frame)?.extendedState ?: 0) and java.awt.Frame.ICONIFIED != 0)
            com.sun.jna.platform.win32.User32.INSTANCE.ShowWindow(target, 4) // SW_SHOWNOACTIVATE
        placeBelowWithoutActivation(window, above)
    }.getOrDefault(false)
}

private interface DeferUser32 : com.sun.jna.Library {
    fun BeginDeferWindowPos(count: Int): Pointer?
    fun DeferWindowPos(info: Pointer, window: com.sun.jna.platform.win32.WinDef.HWND, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Pointer?
    fun EndDeferWindowPos(info: Pointer): Boolean
}
private val deferUser by lazy { Native.load("user32", DeferUser32::class.java, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS) }

/** Desplazamiento en píxeles físicos de una ventana, medido desde su posición de partida. */
internal class NativeShift(val window: Window, val startX: Int, val startY: Int, val dx: Int, val dy: Int)

internal fun nativeShift(window: Window, dxLogical: Int, dyLogical: Int): NativeShift? {
    if (!isWindows || !window.isShowing) return null
    return runCatching {
        val rect = com.sun.jna.platform.win32.WinDef.RECT()
        if (!com.sun.jna.platform.win32.User32.INSTANCE.GetWindowRect(hwnd(window), rect)) return null
        val t = window.graphicsConfiguration.defaultTransform
        NativeShift(window, rect.left, rect.top, Math.round(dxLogical * t.scaleX).toInt(), Math.round(dyLogical * t.scaleY).toInt())
    }.getOrNull()
}

/**
 * Mueve todas las ventanas en una sola operación de Windows (DeferWindowPos): el escritorio
 * las recoloca juntas, sin repintar ni pasar por AWT/Compose en cada paso.
 */
internal fun applyNativeShift(shifts: List<NativeShift>, progress: Float): Boolean = runCatching {
    var info = deferUser.BeginDeferWindowPos(shifts.size) ?: return false
    for (s in shifts) {
        // SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER
        info = deferUser.DeferWindowPos(info, hwnd(s.window), null, s.startX + Math.round(s.dx * progress), s.startY + Math.round(s.dy * progress),
            0, 0, 0x0001 or 0x0004 or 0x0010 or 0x0200) ?: return false
    }
    deferUser.EndDeferWindowPos(info)
}.getOrDefault(false)

/** The shell owns taskbar buttons; changing them must not hide or recreate AWT windows. */
internal fun updateTaskbarEntry(window: Window, show: Boolean) {
    if (!System.getProperty("os.name").startsWith("Windows") || !window.isShowing) return
    runCatching {
        val ole = Ole32.INSTANCE
        val init = ole.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).toInt()
        try {
            val result = PointerByReference()
            val clsid = Guid.GUID("{56FDF344-FD6D-11d0-958A-006097C9A090}")
            val iid = Guid.GUID("{56FDF342-FD6D-11d0-958A-006097C9A090}")
            if (ole.CoCreateInstance(clsid, null, 1, iid, result).toInt() < 0) return@runCatching
            val taskbar = result.value
            val vtable = taskbar.getPointer(0)
            fun call(index: Int, vararg args: Any?): Int = Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
                .invokeInt(arrayOf(taskbar, *args))
            try {
                if (call(3) >= 0) call(if (show) 4 else 5, Native.getWindowPointer(window))
            } finally { call(2) }
        } finally { if (init >= 0) ole.CoUninitialize() }
    }
}
