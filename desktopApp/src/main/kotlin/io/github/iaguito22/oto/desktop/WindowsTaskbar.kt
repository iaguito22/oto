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
