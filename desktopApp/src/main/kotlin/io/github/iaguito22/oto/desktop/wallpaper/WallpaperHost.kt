package io.github.iaguito22.oto.desktop.wallpaper

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import timber.log.Timber
import java.awt.Window
import java.io.File

internal val wallpaperSupported = System.getProperty("os.name").startsWith("Windows")

/** Un monitor en píxeles físicos (como los ve Windows). */
internal data class Monitor(val x: Int, val y: Int, val w: Int, val h: Int, val primary: Boolean)

private interface ExtraUser32 : StdCallLibrary {
    fun SendMessageTimeoutW(hwnd: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM, flags: Int, timeout: Int, result: Pointer?): WinDef.LRESULT
    fun SystemParametersInfoW(action: Int, param: Int, value: WString?, winIni: Int): Boolean
    fun MapWindowPoints(from: WinDef.HWND?, to: WinDef.HWND?, points: WinDef.RECT, count: Int): Int
    fun GetShellWindow(): WinDef.HWND?
    fun GetParent(hwnd: WinDef.HWND): WinDef.HWND?
    fun IsZoomed(hwnd: WinDef.HWND): Boolean
    fun IsIconic(hwnd: WinDef.HWND): Boolean
    fun GetAsyncKeyState(vKey: Int): Short
    fun GetSystemMetrics(index: Int): Int
    fun WindowFromPoint(point: WinDef.POINT.ByValue): WinDef.HWND?
    fun GetAncestor(hwnd: WinDef.HWND, flags: Int): WinDef.HWND?
    fun PaintDesktop(hdc: WinDef.HDC): Boolean
}
private interface ExtraGdi32 : StdCallLibrary { fun SetWindowOrgEx(hdc: WinDef.HDC, x: Int, y: Int, old: Pointer?): Boolean }
private val gdi32x by lazy { Native.load("gdi32", ExtraGdi32::class.java) }

/** El ratón sobre el escritorio, en píxeles de la ventana del fondo; [down] = botón principal pulsado. */
internal data class DesktopPointer(val x: Float, val y: Float, val down: Boolean)
private interface Dwm : StdCallLibrary { fun DwmGetWindowAttribute(hwnd: WinDef.HWND, attr: Int, value: IntByReference, size: Int): Int }
private interface Shell32Extra : StdCallLibrary { fun SHQueryUserNotificationState(state: IntByReference): Int }
private val user32x by lazy { Native.load("user32", ExtraUser32::class.java) }
private val shell32x by lazy { Native.load("shell32", Shell32Extra::class.java) }
private val dwm by lazy { Native.load("dwmapi", Dwm::class.java) }

/**
 * Meter una ventana de Oto detrás de los iconos del escritorio (el truco «WorkerW» de los fondos vivos).
 *
 * - Hasta Windows 11 23H2: tras el mensaje 0x052C a Progman, Explorer crea un WorkerW de primer nivel detrás de los
 *   iconos; la ventana se hace hija de ese WorkerW.
 * - Windows 11 24H2 y posteriores: el WorkerW queda DENTRO de Progman (debajo de SHELLDLL_DefView, que lleva los
 *   iconos). La ventana se hace hija de Progman y se coloca justo debajo de los iconos y encima del WorkerW.
 *
 * Al quitarla, Windows vuelve a pintar el fondo de siempre ([refreshWallpaper]).
 */
internal object WallpaperHost {
    private const val SPAWN_WORKERW = 0x052C
    private const val GWL_STYLE = -16
    private const val GWL_EXSTYLE = -20
    private const val WS_CHILD = 0x40000000
    private const val WS_POPUP = 0x80000000.toInt()
    private const val WS_CAPTION = 0x00C00000
    private const val WS_THICKFRAME = 0x00040000
    private const val WS_EX_TOOLWINDOW = 0x00000080
    private const val WS_EX_APPWINDOW = 0x00040000
    private const val WS_EX_NOACTIVATE = 0x08000000
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_SHOWWINDOW = 0x0040
    private const val SWP_FRAMECHANGED = 0x0020

    /** Dónde colgar la ventana: el padre y, si hace falta, la ventana que debe quedar justo encima (los iconos). */
    private data class Target(val parent: WinDef.HWND, val above: WinDef.HWND?, val newLayout: Boolean)

    private fun findTarget(): Target? {
        val u = User32.INSTANCE
        val progman = u.FindWindow("Progman", null) ?: return null
        // Pide a Explorer que cree el WorkerW (con los dos parámetros que usan los fondos vivos conocidos).
        user32x.SendMessageTimeoutW(progman, SPAWN_WORKERW, WinDef.WPARAM(0xD), WinDef.LPARAM(0x1), 0, 1000, null)
        user32x.SendMessageTimeoutW(progman, SPAWN_WORKERW, WinDef.WPARAM(0), WinDef.LPARAM(0), 0, 1000, null)
        // 24H2+: WorkerW hijo de Progman.
        val defViewInProgman = u.FindWindowEx(progman, null, "SHELLDLL_DefView", null)
        val childWorker = u.FindWindowEx(progman, null, "WorkerW", null)
        if (defViewInProgman != null && childWorker != null) return Target(progman, defViewInProgman, true)
        // Clásico: la ventana de primer nivel que lleva SHELLDLL_DefView y, detrás, un WorkerW hermano.
        var worker: WinDef.HWND? = null
        u.EnumWindows({ top, _ ->
            val view = u.FindWindowEx(top, null, "SHELLDLL_DefView", null)
            if (view != null) worker = u.FindWindowEx(null, top, "WorkerW", null)
            true
        }, null)
        worker?.let { return Target(it, null, false) }
        // Si Explorer no creó nada, al menos detrás de los iconos de Progman.
        return defViewInProgman?.let { Target(progman, it, true) }
    }

    /** Monitores, en el orden de Windows; el principal marcado. */
    fun monitors(): List<Monitor> {
        if (!wallpaperSupported) return emptyList()
        val out = ArrayList<Monitor>()
        runCatching {
            User32.INSTANCE.EnumDisplayMonitors(null, null, { hMon, _, _, _ ->
                val info = WinUser.MONITORINFOEX()
                if (User32.INSTANCE.GetMonitorInfo(hMon, info).booleanValue()) {
                    val r = info.rcMonitor
                    out += Monitor(r.left, r.top, r.right - r.left, r.bottom - r.top, info.dwFlags and 1 != 0)
                }
                1
            }, null)
        }.onFailure { Timber.w(it, "No se pudieron leer los monitores") }
        return out.sortedByDescending { it.primary }
    }

    /** Cuelga [window] detrás de los iconos ocupando [monitor]. Devuelve false si no se pudo (y la ventana se oculta). */
    fun attach(window: Window, monitor: Monitor): Boolean {
        if (!wallpaperSupported) return false
        return runCatching {
            val u = User32.INSTANCE
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val target = findTarget() ?: error("No se encontró el escritorio de Windows (Progman/WorkerW)")
            // Sin marco, sin botón en la barra de tareas y sin poder activarse: una hija más del escritorio.
            var style = u.GetWindowLong(hwnd, GWL_STYLE)
            style = (style and (WS_POPUP or WS_CAPTION or WS_THICKFRAME).inv()) or WS_CHILD
            u.SetWindowLong(hwnd, GWL_STYLE, style)
            var ex = u.GetWindowLong(hwnd, GWL_EXSTYLE)
            ex = (ex and WS_EX_APPWINDOW.inv()) or WS_EX_TOOLWINDOW or WS_EX_NOACTIVATE
            u.SetWindowLong(hwnd, GWL_EXSTYLE, ex)
            u.SetParent(hwnd, target.parent)
            // Coordenadas del monitor dentro del padre.
            val r = WinDef.RECT().apply { left = monitor.x; top = monitor.y; right = monitor.x + monitor.w; bottom = monitor.y + monitor.h }
            user32x.MapWindowPoints(null, target.parent, r, 2)
            // 24H2+: justo debajo de los iconos (insertAfter = SHELLDLL_DefView), es decir, encima del WorkerW del fondo.
            val after = target.above
            u.SetWindowPos(hwnd, after, r.left, r.top, r.right - r.left, r.bottom - r.top,
                SWP_NOACTIVATE or SWP_SHOWWINDOW or SWP_FRAMECHANGED or (if (after == null) 0x0004 /* SWP_NOZORDER */ else 0))
            Timber.i("Fondo vivo colgado (${if (target.newLayout) "Progman, 24H2+" else "WorkerW clásico"}) en ${monitor.w}x${monitor.h}")
            true
        }.onFailure { Timber.w(it, "No se pudo poner el fondo vivo detrás de los iconos") }.getOrDefault(false)
    }

    /** Vuelve a pintar el fondo de pantalla de siempre (al apagar el fondo vivo o salir de Oto). */
    fun refreshWallpaper() {
        if (!wallpaperSupported) return
        runCatching {
            val path = currentWallpaperPath()
            // SPI_SETDESKWALLPAPER con la misma ruta y sin guardar nada: solo repinta. Sin ruta, null repinta igual.
            user32x.SystemParametersInfoW(0x0014, 0, path?.let { WString(it) }, 0)
        }.onFailure { Timber.w(it, "No se pudo repintar el fondo de Windows") }
    }

    /**
     * Tu fondo de pantalla tal y como lo pinta Windows en [m] (con su recorte, ajuste, presentación…): píxeles BGRA de
     * arriba abajo, o null. Mejor que leer el archivo: el archivo del registro y el que se ve no siempre coinciden
     * (24/11: la hora sola dejaba un recuadro con el fondo desplazado y con otro zoom).
     */
    fun paintDesktop(m: Monitor): ByteArray? {
        if (!wallpaperSupported || m.w <= 0 || m.h <= 0) return null
        return runCatching {
            val gdi = com.sun.jna.platform.win32.GDI32.INSTANCE
            val screen = User32.INSTANCE.GetDC(null)
            val dc = gdi.CreateCompatibleDC(screen)
            val bmi = com.sun.jna.platform.win32.WinGDI.BITMAPINFO().apply {
                bmiHeader.biWidth = m.w; bmiHeader.biHeight = -m.h; bmiHeader.biPlanes = 1; bmiHeader.biBitCount = 32
                bmiHeader.biCompression = com.sun.jna.platform.win32.WinGDI.BI_RGB
            }
            val bits = com.sun.jna.ptr.PointerByReference()
            val bmp = gdi.CreateDIBSection(dc, bmi, com.sun.jna.platform.win32.WinGDI.DIB_RGB_COLORS, bits, null, 0)
            try {
                check(bmp != null && bits.value != null) { "CreateDIBSection" }
                val old = gdi.SelectObject(dc, bmp)
                gdi32x.SetWindowOrgEx(dc, m.x, m.y, null)
                val ok = user32x.PaintDesktop(dc)
                gdi.SelectObject(dc, old)
                check(ok) { "PaintDesktop" }
                bits.value.getByteArray(0, m.w * m.h * 4)
            } finally {
                bmp?.let { gdi.DeleteObject(it) }; gdi.DeleteDC(dc); User32.INSTANCE.ReleaseDC(null, screen)
            }
        }.onFailure { Timber.w(it, "No se pudo pintar el fondo de Windows") }.getOrNull()
    }

    /** El archivo del fondo de pantalla actual (o la copia que guarda Windows), si se puede leer. */
    fun currentWallpaperFile(): File? {
        if (!wallpaperSupported) return null
        currentWallpaperPath()?.let { File(it) }?.takeIf { it.isFile }?.let { return it }
        return System.getenv("APPDATA")?.let { File(it, "Microsoft/Windows/Themes/TranscodedWallpaper") }?.takeIf { it.isFile }
    }

    private fun currentWallpaperPath(): String? = runCatching {
        Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, "Control Panel\\Desktop", "WallPaper").takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Cómo coloca Windows el fondo: rellenar (por defecto), ajustar, expandir, centrar o mosaico. */
    fun wallpaperFit(): String = runCatching {
        val style = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, "Control Panel\\Desktop", "WallpaperStyle").trim()
        val tile = runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, "Control Panel\\Desktop", "TileWallpaper").trim() }.getOrDefault("0")
        when {
            tile == "1" -> "mosaico"
            style == "6" -> "ajustar"
            style == "2" -> "expandir"
            style == "0" -> "centrar"
            else -> "rellenar"   // 10 (rellenar) y 22 (extender)
        }
    }.getOrDefault("rellenar")

    /**
     * ¿Está tapado el escritorio de [monitor]? Entonces no se ve el fondo y no hace falta pintarlo.
     * Sí, si hay algo a pantalla completa (juego, vídeo, presentación: lo dice Windows) o si alguna ventana visible
     * (aunque no sea la activa) está maximizada o a pantalla completa en ese monitor. No cuentan las minimizadas, las
     * de otros escritorios virtuales (ocultas por DWM) ni las del propio escritorio y la barra de tareas.
     */
    fun desktopHidden(monitor: Monitor): Boolean {
        if (!wallpaperSupported) return false
        if (somethingFullscreen()) return true
        return runCatching {
            val u = User32.INSTANCE; var covered = false
            val cx = monitor.x + monitor.w / 2; val cy = monitor.y + monitor.h / 2
            u.EnumWindows({ h, _ ->
                if (covered || !u.IsWindowVisible(h) || user32x.IsIconic(h)) return@EnumWindows true
                val cloaked = IntByReference(); dwm.DwmGetWindowAttribute(h, 14 /* DWMWA_CLOAKED */, cloaked, 4)
                if (cloaked.value != 0) return@EnumWindows true
                val cls = CharArray(64); val n = u.GetClassName(h, cls, cls.size)
                if (String(cls, 0, n) in IGNORED) return@EnumWindows true
                val r = WinDef.RECT(); if (!u.GetWindowRect(h, r)) return@EnumWindows true
                if (cx < r.left || cx >= r.right || cy < r.top || cy >= r.bottom) return@EnumWindows true
                val full = r.left <= monitor.x && r.top <= monitor.y && r.right >= monitor.x + monitor.w && r.bottom >= monitor.y + monitor.h
                if (user32x.IsZoomed(h) || full) covered = true
                true
            }, null)
            covered
        }.getOrDefault(false)
    }
    /**
     * Dónde está el ratón, solo si lo que hay debajo es el escritorio (los iconos o el fondo; no otra ventana) y cae en
     * [window]. La ventana del fondo está detrás de los iconos y no recibe clics: los controles se pulsan mirando el
     * ratón así. Coordenadas en píxeles de la ventana ([pxW] × [pxH], lo que mide su lienzo).
     */
    fun pointer(window: Window, pxW: Int, pxH: Int): DesktopPointer? {
        if (!wallpaperSupported || pxW <= 0) return null
        return runCatching {
            val u = User32.INSTANCE
            val pt = WinDef.POINT(); if (!u.GetCursorPos(pt)) return null
            val under = user32x.WindowFromPoint(WinDef.POINT.ByValue(pt.x, pt.y)) ?: return null
            val root = user32x.GetAncestor(under, 2 /* GA_ROOT */) ?: under
            val cls = CharArray(32); val n = u.GetClassName(root, cls, cls.size); val name = String(cls, 0, n)
            if (name != "Progman" && name != "WorkerW") return null
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val r = WinDef.RECT(); if (!u.GetWindowRect(hwnd, r) || r.right <= r.left) return null
            if (pt.x < r.left || pt.x >= r.right || pt.y < r.top || pt.y >= r.bottom) return null
            // los botones del ratón cambiados (zurdos): el principal es el derecho
            val vk = if (user32x.GetSystemMetrics(23 /* SM_SWAPBUTTON */) != 0) 0x02 else 0x01
            val down = user32x.GetAsyncKeyState(vk).toInt() and 0x8000 != 0
            DesktopPointer((pt.x - r.left) * pxW.toFloat() / (r.right - r.left), (pt.y - r.top) * pxH.toFloat() / (r.bottom - r.top), down)
        }.getOrNull()
    }

    private val IGNORED = setOf("Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd", "Windows.UI.Core.CoreWindow")

    /** ¿Algo a pantalla completa delante? (SHQueryUserNotificationState y la ventana activa). */
    fun somethingFullscreen(): Boolean {
        if (!wallpaperSupported) return false
        return runCatching {
            val st = IntByReference()
            if (shell32x.SHQueryUserNotificationState(st) == 0 && st.value in listOf(2, 3, 4)) return true   // ocupado, D3D, presentación
            val u = User32.INSTANCE
            val fg = u.GetForegroundWindow() ?: return false
            if (fg == user32x.GetShellWindow() || fg == u.FindWindow("Progman", null)) return false
            val cls = CharArray(64); val n = u.GetClassName(fg, cls, cls.size); val name = String(cls, 0, n)
            if (name == "WorkerW" || name == "Progman" || name == "Shell_TrayWnd") return false
            val wr = WinDef.RECT(); if (!u.GetWindowRect(fg, wr)) return false
            val mon = u.MonitorFromWindow(fg, 2) ?: return false
            val info = WinUser.MONITORINFO(); if (!u.GetMonitorInfo(mon, info).booleanValue()) return false
            val m = info.rcMonitor
            wr.left <= m.left && wr.top <= m.top && wr.right >= m.right && wr.bottom >= m.bottom
        }.getOrDefault(false)
    }

}
