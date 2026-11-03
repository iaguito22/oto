package io.github.iaguito22.oto

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OtoDirsTest {
    @Test fun windowsCacheHasItsOwnDirectoryBelowLocalAppData() {
        val base = File("perfil Windows ñ/Local")
        assertEquals(File(base, "oto/Cache"), desktopCacheDirectory("windows 11", base))
        assertNotEquals(File(base, "Oto").canonicalFile, desktopCacheDirectory("Windows 11", base).canonicalFile)
    }
    @Test fun linuxRetainsItsExistingCacheLocation() {
        val base = File("xdg-cache")
        assertEquals(File(base, "oto"), desktopCacheDirectory("Linux", base))
    }
}
