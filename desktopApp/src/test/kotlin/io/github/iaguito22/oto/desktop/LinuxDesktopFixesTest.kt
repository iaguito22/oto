package io.github.iaguito22.oto.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LinuxDesktopFixesTest {
    @Test
    fun `closes only when neither tray nor global shortcut is available`() {
        assertFalse(shouldQuitWhenNoTray(traySupported = true, shortcutSupported = false, shortcutIssue = null))
        assertFalse(shouldQuitWhenNoTray(traySupported = false, shortcutSupported = true, shortcutIssue = null))
        assertTrue(shouldQuitWhenNoTray(traySupported = false, shortcutSupported = false, shortcutIssue = null))
        assertTrue(shouldQuitWhenNoTray(traySupported = false, shortcutSupported = true, shortcutIssue = "No se pudo registrar"))
    }

    @Test
    fun `portable linux launcher resolves symlinks`() {
        val script = listOf(File("tools/package-linux-portable.py"), File("../tools/package-linux-portable.py"))
            .firstOrNull(File::isFile) ?: return
        val content = script.readText()
        assertTrue("El lanzador debe resolver /usr/bin/oto con readlink -f", "readlink -f" in content)
    }
}
