package io.github.iaguito22.oto.desktop.playback

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class Mp3ExportTaskTest {
    private fun entry(id: String) = DesktopDownloads.Entry(id, id, "Artista", null, "$id.webm")

    @Test fun survivesScreenCancellationAndRejectsConcurrentExport() = runBlocking {
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val screen = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var calls = 0
        val task = Mp3ExportTask(app) { _, _ ->
            calls++
            entered.complete(Unit)
            release.await()
            finished.complete(Unit)
        }
        try {
            screen.launch { task.start(listOf(entry("uno")), File(".")) }.join()
            withTimeout(5000) { entered.await() }
            screen.cancel()
            task.start(listOf(entry("dos")), File("."))
            assertTrue(task.progress.value.running)
            release.complete(Unit)
            withTimeout(5000) {
                finished.await()
                while (task.progress.value.running) yield()
            }
            assertEquals(1, calls)
            assertEquals(1, task.progress.value.completed)
            assertTrue(task.progress.value.message.contains("1 MP3 guardados"))
        } finally { app.cancel(); screen.cancel() }
    }

    @Test fun cancellationStopsBatchAndReportsFailures() = runBlocking {
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val blocked = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val task = Mp3ExportTask(app) { entry, _ ->
            calls += entry.id
            if (entry.id == "error") error("Archivo no disponible")
            try { blocked.complete(Unit); awaitCancellation() }
            finally { stopped.complete(Unit) }
        }
        try {
            task.start(listOf(entry("error"), entry("espera"), entry("omitida")), File("."))
            withTimeout(5000) { blocked.await() }
            task.cancel()
            withTimeout(5000) {
                stopped.await()
                while (task.progress.value.running) yield()
            }
            assertEquals(listOf("error", "espera"), calls)
            assertTrue(task.progress.value.message.contains("cancelada"))
            assertTrue(task.progress.value.failures.single().contains("Archivo no disponible"))
        } finally { app.cancel() }
    }
}
