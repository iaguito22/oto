package io.github.iaguito22.oto.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import io.github.iaguito22.oto.OtoDirs
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/**
 * Guarda en `errores.log` (carpeta de datos de Oto) cualquier error que tumbe una ventana o un hilo,
 * con la traza completa. El instalado no tiene consola: sin esto, el aviso de error se perdía al cerrarlo.
 * El archivo se recorta al pasar de ~1 MB para no crecer sin límite.
 */
internal object ErrorLog {
    private const val MAX_BYTES = 1_000_000L
    val file: File get() = File(OtoDirs.filesDir, "errores.log")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write("hilo ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized
    fun write(where: String, error: Throwable) {
        runCatching {
            val f = file
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast((MAX_BYTES / 2).toInt()))
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
            f.appendText("--- ${LocalDateTime.now()} · Oto ${System.getProperty("jpackage.app-version") ?: "dev"} · $where\n$trace\n")
        }
    }

    /** Registra el error y deja el comportamiento de siempre de Compose (aviso y cierre de la ventana). */
    @OptIn(ExperimentalComposeUiApi::class)
    val windows = WindowExceptionHandlerFactory { window ->
        val fallback = DefaultWindowExceptionHandlerFactory.exceptionHandler(window)
        WindowExceptionHandler { error ->
            write("ventana ${(window as? java.awt.Frame)?.title ?: window.name}", error)
            fallback.onException(error)
        }
    }
}
