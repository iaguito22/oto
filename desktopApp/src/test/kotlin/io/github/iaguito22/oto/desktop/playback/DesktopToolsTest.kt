package io.github.iaguito22.oto.desktop.playback

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DesktopToolsTest {
    @Test fun packagedToolsWorkWithoutPathAndWindowsPathIsCaseInsensitive() {
        val root = Files.createTempDirectory("oto-tools ").toFile()
        val windows = System.getProperty("os.name").startsWith("Windows", true)
        val name = "ffplay" + if(windows) ".exe" else ""
        try {
            val binary = root.resolve("audio/$name").apply { parentFile.mkdirs();writeText("");setExecutable(true) }
            assertEquals(binary.absolutePath, DesktopTools.find("ffplay", emptyMap(), root.path))
            assertEquals(binary.absolutePath, DesktopTools.find("ffplay", mapOf("PATH" to binary.parent), null))
            if(windows)assertEquals(binary.absolutePath, DesktopTools.find("ffplay", mapOf("Path" to binary.parent), null))
        } finally { root.deleteRecursively() }
    }
}
