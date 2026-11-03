package io.github.iaguito22.oto.desktop.playback

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Mp3ExportTest {
    @Test fun convertsWebmAndPreservesExistingExport(): Unit = runBlocking {
        val root = Files.createTempDirectory("oto-mp3-test").toFile()
        try {
            val source = File(root, "source.webm")
            val generator = ProcessBuilder(DesktopTools.require("ffmpeg"), "-nostdin", "-v", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=0.2", "-c:a", "libopus", source.absolutePath).start()
            assertEquals(0, generator.waitFor())
            val first = Mp3Export.export(source, root, "Artista", "Prueba")
            val original = first.readBytes()
            val second = Mp3Export.export(source, root, "Artista", "Prueba")
            assertNotEquals(first, second)
            assertArrayEquals(original, first.readBytes())
            assertTrue(source.exists())
            val probe = ProcessBuilder(DesktopTools.require("ffprobe"), "-v", "error", "-show_entries",
                "stream=codec_name:format_tags=title,artist", "-of", "default=noprint_wrappers=1", second.absolutePath).start()
            val info = probe.inputStream.bufferedReader().readText()
            assertEquals(0, probe.waitFor())
            assertTrue(info.contains("codec_name=mp3"))
            assertTrue(info.contains("Prueba"))
            assertTrue(info.contains("Artista"))
            assertFalse(root.listFiles()!!.any { it.name.startsWith(".oto-export-") })
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidInputLeavesNoPartialMp3(): Unit = runBlocking {
        val root = Files.createTempDirectory("oto-mp3-error").toFile()
        try {
            val source = File(root, "broken.webm").apply { writeText("invalid audio") }
            try {
                Mp3Export.export(source, root, "Artista", "Prueba")
                fail("Debe rechazar audio no válido")
            } catch (_: IllegalStateException) { }
            assertEquals(listOf(source), root.listFiles()!!.toList())
            assertFalse(Mp3Export.fileName("A/B", "C:D?").contains('/'))
        } finally { root.deleteRecursively() }
    }
}
