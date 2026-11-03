package io.github.iaguito22.oto.desktop.playback

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*

internal object Mp3Export {
    fun fileName(artist: String, title: String): String {
        val clean = "$artist - $title".map { if (it.code < 32 || it in "<>:\"/\\|?*") '_' else it }.joinToString("").trim().trimEnd('.')
        return clean.take(160).ifBlank { "Canción" } + ".mp3"
    }

    suspend fun export(source: File, directory: File, artist: String, title: String): File = withContext(Dispatchers.IO) {
        check(source.isFile) { "El archivo descargado ya no está disponible" }
        check(directory.isDirectory) { "La carpeta de destino no existe" }
        val name = fileName(artist, title)
        val temporary = Files.createTempFile(directory.toPath(), ".oto-export-", ".mp3").toFile()
        var process: Process? = null
        try {
            process = ProcessBuilder(DesktopTools.require("ffmpeg"), "-nostdin", "-v", "error", "-y",
                "-i", source.absolutePath, "-map", "0:a:0", "-vn", "-c:a", "libmp3lame", "-q:a", "2",
                "-metadata", "title=$title", "-metadata", "artist=$artist", temporary.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            while (process.isAlive) delay(100)
            check(process.exitValue() == 0 && temporary.length() > 0) { "No se pudo convertir la canción a MP3" }
            var number = 1
            var target = File(directory, name)
            while (true) {
                try {
                    Files.move(temporary.toPath(), target.toPath())
                    break
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    target = File(directory, name.removeSuffix(".mp3") + " (${number++}).mp3")
                }
            }
            target
        } finally {
            process?.let { if (it.isAlive) { it.destroyForcibly(); it.waitFor() } }
            temporary.delete()
        }
    }
}
