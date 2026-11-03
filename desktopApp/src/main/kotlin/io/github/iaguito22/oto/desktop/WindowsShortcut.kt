package io.github.iaguito22.oto.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal val isWindowsDesktop get() = System.getProperty("os.name").startsWith("Windows", true)
internal val supportsDesktopShortcut get() = isWindowsDesktop || System.getenv("HYPRLAND_INSTANCE_SIGNATURE") != null

internal data class DesktopShortcut(val modifiers: Int, val key: Int)

internal fun parseDesktopShortcut(text: String): DesktopShortcut? {
    val parts = text.uppercase().split(Regex("\\s*\\+\\s*")).map(String::trim)
    if (parts.size < 2 || !parts.last().matches(Regex("[A-Z0-9]"))) return null
    val flags = mapOf("ALT" to 1, "CTRL" to 2, "SHIFT" to 4, "SUPER" to 8)
    val modifiers = parts.dropLast(1)
    if (modifiers.distinct().size != modifiers.size || modifiers.any { it !in flags }) return null
    return DesktopShortcut(modifiers.fold(0) { value, name -> value or flags.getValue(name) }, parts.last()[0].code)
}

/** RegisterHotKey solo reserva la combinación elegida; no instala un capturador de teclado. */
internal class WindowsShortcut(shortcut: DesktopShortcut, toggle: () -> Unit) : AutoCloseable {
    private val ready = CountDownLatch(1)
    @Volatile private var threadId = 0
    @Volatile var issue: String? = null
        private set
    private val thread = Thread({
        var registered = false
        try {
            val user = User32.INSTANCE
            val message = WinUser.MSG()
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            user.PeekMessage(message, null, 0, 0, 0)
            registered = user.RegisterHotKey(null, HOTKEY_ID, shortcut.modifiers or 0x4000, shortcut.key)
            if (!registered) issue = "Windows no pudo registrar el atajo (error ${Native.getLastError()}). Prueba otra combinación."
            ready.countDown()
            if (registered) {
                while (user.GetMessage(message, null, 0, 0) > 0) {
                    if (message.message == WinUser.WM_HOTKEY && message.wParam.toInt() == HOTKEY_ID) toggle()
                }
            }
        } catch (error: Throwable) {
            issue = "No se pudo activar el atajo de Windows: ${error.message}"
        } finally {
            if (registered) User32.INSTANCE.UnregisterHotKey(null, HOTKEY_ID)
            ready.countDown()
        }
    }, "Oto-atajo-Windows").apply { isDaemon = true; start() }

    init { ready.await(3, TimeUnit.SECONDS) }

    override fun close() {
        ready.await(3, TimeUnit.SECONDS)
        if (thread.isAlive && threadId != 0) {
            User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, null, null)
            thread.join(2000)
        }
    }

    private companion object { const val HOTKEY_ID = 0x4f54 }
}
