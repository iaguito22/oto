package io.github.iaguito22.oto.desktop

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Window
import java.io.File

/** Selector de carpetas del Explorador (IFileOpenDialog), sin el aspecto clásico de Swing. */
internal fun windowsExportFolder(owner: Window?): File? {
    val ole = Ole32.INSTANCE
    val initialized = ole.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).toInt()
    check(initialized >= 0) { "No se pudo iniciar el selector de Windows" }
    fun call(pointer: Pointer, index: Int, vararg args: Any?): Int =
        Function.getFunction(pointer.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
            .invokeInt(arrayOf(pointer, *args))
    fun checkResult(code: Int) { check(code >= 0) { "El selector de Windows devolvió un error (0x${Integer.toHexString(code)})" } }
    try {
        val result = PointerByReference()
        checkResult(ole.CoCreateInstance(Guid.GUID("{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"), null, 1,
            Guid.GUID("{D57C7288-D4AD-4768-BE02-9D969532D960}"), result).toInt())
        val dialog = result.value
        try {
            val options = IntByReference()
            checkResult(call(dialog, 10, options))
            // FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST
            checkResult(call(dialog, 9, options.value or 0x20 or 0x40 or 0x800))
            checkResult(call(dialog, 17, WString("Carpeta para los archivos MP3")))
            checkResult(call(dialog, 18, WString("Guardar MP3 aquí")))
            val shown = call(dialog, 3, owner?.takeIf { it.isDisplayable }?.let { Native.getWindowPointer(it) })
            if (shown == 0x800704C7.toInt()) return null
            checkResult(shown)
            val itemResult = PointerByReference()
            checkResult(call(dialog, 20, itemResult))
            val item = itemResult.value
            try {
                val name = PointerByReference()
                checkResult(call(item, 5, 0x80058000.toInt(), name)) // SIGDN_FILESYSPATH
                try { return File(name.value.getWideString(0)) }
                finally { ole.CoTaskMemFree(name.value) }
            } finally { call(item, 2) }
        } finally { call(dialog, 2) }
    } finally { ole.CoUninitialize() }
}
