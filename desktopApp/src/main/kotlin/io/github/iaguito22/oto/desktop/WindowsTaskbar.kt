package io.github.iaguito22.oto.desktop

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.PointerByReference
import java.awt.Window

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
