package io.github.iaguito22.oto.desktop.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iaguito22.oto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopDownloadsTest {
    @Test fun removingActiveDownloadCancelsWorkBeforeDeletingWindowsFiles(): Unit = runBlocking {
        val root = Files.createTempDirectory("oto-download-cancel").toFile()
        OtoDirs.init(root, File(root, "cache"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = Prefs(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "prefs.preferences_pb") }),
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "lists.preferences_pb") }),
        )
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val part = File(root, "downloads/test.part")
        val downloads = DesktopDownloads(prefs, scope) { _, _ ->
            part.writeText("parcial")
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        try {
            downloads.start()
            val song = Heard("test", "Prueba", "Codex", null)
            prefs.setDownloadSong(song, true)
            withTimeout(5000) { started.await() }
            prefs.setDownloadSong(song, false)
            withTimeout(5000) { cancelled.await(); downloads.entries.first { "test" !in it } }
            assertFalse(part.exists())
            assertFalse(downloads.has("test"))
        } finally { downloads.stop(); scope.cancel(); root.deleteRecursively() }
    }
}
