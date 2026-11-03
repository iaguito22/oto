package io.github.iaguito22.oto.desktop

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class DesktopBrowserTest {
    @Test fun findsBraveAndEdgeInEitherProgramFilesDirectory() {
        val root = Files.createTempDirectory("oto-browser ").toFile()
        try {
            for (base in listOf("PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA")) {
                for (relative in listOf("BraveSoftware/Brave-Browser/Application/brave.exe", "Microsoft/Edge/Application/msedge.exe")) {
                    val binary = File(root, relative).apply { parentFile.mkdirs(); writeText(""); setExecutable(true) }
                    assertEquals(binary.path, findChromiumBrowser("Windows 11", mapOf(base to root.path)))
                    binary.delete()
                }
            }
            assertNull(findChromiumBrowser("Windows 11", emptyMap()))
        } finally { root.deleteRecursively() }
    }

    @Test fun configuredBrowserAndPathWorkWithoutWhich() {
        val root = Files.createTempDirectory("oto-browser ").toFile()
        try {
            val binary = File(root, "brave.exe").apply { writeText(""); setExecutable(true) }
            assertEquals(binary.path, findChromiumBrowser("Windows 11", mapOf("OTO_CHROME" to binary.path)))
            assertEquals(binary.path, findChromiumBrowser("Windows 11", mapOf("PATH" to root.path)))
            assertEquals(binary.path, findChromiumBrowser("Windows 11", mapOf("Path" to root.path)))
        } finally { root.deleteRecursively() }
    }
}
