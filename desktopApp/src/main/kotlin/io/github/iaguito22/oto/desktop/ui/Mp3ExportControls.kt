package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.iaguito22.oto.desktop.playback.*
import java.io.File
import kotlinx.coroutines.*

@Composable
internal fun Mp3ExportControls(downloads: DesktopDownloads) {
    val entries by downloads.entries.collectAsState()
    val ready = entries.values.filter { it.state == DesktopDownloads.State.Done }.sortedBy { it.title }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    val progress by downloads.mp3Export.progress.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var selectionError by remember { mutableStateOf("") }
    val busy = choosing || progress.running
    fun export(selection: List<DesktopDownloads.Entry>) {
        expanded = false
        choosing = true
        selectionError = ""
        scope.launch {
            try {
                val directory = chooseExportFolder() ?: return@launch
                downloads.mp3Export.start(selection, directory)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { selectionError = e.message ?: "No se pudo abrir la carpeta" }
            finally { choosing = false }
        }
    }
    Column {
        TextButton(enabled = ready.isNotEmpty() && !busy, onClick = { expanded = true }) {
            Icon(MobileIcons.download, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "Exportando MP3…" else "Exportar como MP3…")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Todas las descargadas (${ready.size})") }, onClick = { export(ready) })
            HorizontalDivider()
            ready.forEach { entry ->
                DropdownMenuItem(text = { Text("${entry.artist} - ${entry.title}") }, onClick = { export(listOf(entry)) })
            }
        }
        if (progress.running) {
            LinearProgressIndicator(
                progress = { if (progress.total > 0) progress.completed.toFloat() / progress.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { downloads.mp3Export.cancel() }) { Text("Cancelar exportación") }
        }
        val status = selectionError.ifEmpty { progress.message }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (progress.failures.isNotEmpty()) {
            var showFailures by remember { mutableStateOf(false) }
            TextButton(onClick = { showFailures = !showFailures }) { Text(if (showFailures) "Ocultar errores" else "Ver errores") }
            if (showFailures) Text(progress.failures.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private suspend fun chooseExportFolder(): File? = suspendCancellableCoroutine { continuation ->
    java.awt.EventQueue.invokeLater {
        if (continuation.isActive) {
            try {
                val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                val result = if (System.getProperty("os.name").startsWith("Windows", true)) {
                    io.github.iaguito22.oto.desktop.windowsExportFolder(owner)
                } else {
                    val chooser = javax.swing.JFileChooser().apply {
                        dialogTitle = "Carpeta para los archivos MP3"
                        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                    }
                    if (chooser.showSaveDialog(owner) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
                }
                if (continuation.isActive) continuation.resumeWith(Result.success(result))
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
        }
    }
}
