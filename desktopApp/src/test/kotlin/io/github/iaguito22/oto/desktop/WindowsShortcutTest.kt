package io.github.iaguito22.oto.desktop

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WindowsShortcutTest {
    @Test fun parsesOnlySupportedCombinations() {
        assertEquals(DesktopShortcut(3, 'O'.code), parseDesktopShortcut("ctrl + alt + o"))
        assertEquals(DesktopShortcut(6, '9'.code), parseDesktopShortcut("CTRL+SHIFT+9"))
        for (invalid in listOf("O", "CTRL + CTRL + O", "CTRL + F12", "CTRL + ", "BAD + O", "CTRL + O;exit")) {
            assertNull(invalid, parseDesktopShortcut(invalid))
        }
    }

    @Test fun windowsReleasesTheCombinationWhenClosed() {
        assumeTrue(isWindowsDesktop)
        val combination = parseDesktopShortcut("CTRL + ALT + SHIFT + 9")!!
        WindowsShortcut(combination) {}.use { first ->
            assertNull(first.issue)
            WindowsShortcut(combination) {}.use { duplicate -> assertNotNull(duplicate.issue) }
        }
        WindowsShortcut(combination) {}.use { assertNull(it.issue) }
    }
}
