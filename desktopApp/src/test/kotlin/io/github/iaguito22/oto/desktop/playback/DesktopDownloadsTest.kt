package io.github.iaguito22.oto.desktop.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iaguito22.oto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class DesktopDownloadsTest {
    @Test fun removingActiveDownloadCancelsWorkBeforeDeletingWindowsFiles(): Unit = runBlocking {
        val root = Files.createTempDirectory("oto-download-cancel").toFile()
        OtoDirs.init(root, File(root, "cache"))
        val errors = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> errors += error })
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
            // Márgenes amplios: en la CI o con otra compilación a la vez, 5 s no llegaban (no es una prueba de velocidad).
            val begun = withTimeoutOrNull(30_000) { started.await() }
            assertNotNull("la descarga no empezó; entradas=${downloads.entries.value}; errores=${errors.joinToString { it.stackTraceToString() }}", begun)
            prefs.setDownloadSong(song, false)
            assertNotNull("no se canceló la descarga en curso", withTimeoutOrNull(30_000) { cancelled.await() })
            assertNotNull("la entrada siguió en la lista", withTimeoutOrNull(30_000) { downloads.entries.first { "test" !in it } })
            assertFalse(part.exists())
            assertFalse(downloads.has("test"))
            assertTrue("errores en las corrutinas: $errors", errors.isEmpty())
        } finally {
            downloads.stop()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            root.deleteRecursively()
        }
    }
}
