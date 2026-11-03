package io.github.iaguito22.oto.desktop.playback

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** La exportación pertenece a la aplicación, no a la pantalla que la inicia. */
internal class Mp3ExportTask(
    private val scope: CoroutineScope,
    private val convert: suspend (DesktopDownloads.Entry, File) -> Unit,
) {
    data class Progress(
        val running: Boolean = false,
        val completed: Int = 0,
        val total: Int = 0,
        val message: String = "",
        val failures: List<String> = emptyList(),
    )

    private val mutable = MutableStateFlow(Progress())
    val progress = mutable.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start(entries: List<DesktopDownloads.Entry>, directory: File) {
        if (job != null || entries.isEmpty()) return
        val selection = entries.toList()
        mutable.value = Progress(running = true, total = selection.size)
        val task = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var done = 0
            val failures = mutableListOf<String>()
            try {
                for ((index, entry) in selection.withIndex()) {
                    ensureActive()
                    mutable.value = Progress(true, index, selection.size,
                        "Exportando ${index + 1} de ${selection.size}: ${entry.title}", failures.toList())
                    try {
                        convert(entry, directory)
                        done++
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failures += "${entry.artist} - ${entry.title}: ${e.message ?: "No se pudo exportar"}" }
                }
                mutable.value = Progress(completed = selection.size, total = selection.size,
                    message = "$done MP3 guardados en ${directory.absolutePath}" +
                        if (failures.isNotEmpty()) " · ${failures.size} no se pudieron exportar" else "",
                    failures = failures.toList())
            } catch (e: CancellationException) {
                mutable.value = mutable.value.copy(running = false,
                    message = "Exportación cancelada · $done MP3 guardados en ${directory.absolutePath}",
                    failures = failures.toList())
                throw e
            }
        }
        job = task
        task.invokeOnCompletion {
            synchronized(this) {
                job = null
                mutable.value = mutable.value.copy(running = false)
            }
        }
        task.start()
    }

    @Synchronized
    fun cancel() { job?.cancel() }
}
