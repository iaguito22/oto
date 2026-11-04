package io.github.iaguito22.oto.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.iaguito22.oto.desktop.ui.ArtworkLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Graphics
import timber.log.Timber
import java.lang.management.ManagementFactory

/**
 * Ahorro de RAM agresivo. Con él activo:
 * - la JVM devuelve al sistema la memoria que no usa (márgenes de montón bajos y GC periódico);
 * - Skia guarda muy poca caché de dibujo y de letras;
 * - cada poco se recoge basura y se vacían esas cachés;
 * - con todas las ventanas ocultas se sueltan las carátulas y Windows recorta la memoria residente.
 * Al desactivarlo se vuelven a poner los valores normales.
 */
internal object RamSaver {
    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val normalFontCache = runCatching { Graphics.fontCacheLimit }.getOrDefault(2 shl 20)
    private val normalResourceCache = runCatching { Graphics.resourceCacheTotalLimit }.getOrDefault(32 shl 20)

    fun apply(enabled: Boolean) {
        ArtworkLoader.setRamSaver(enabled)
        runCatching {
            if (enabled) {
                Graphics.fontCacheLimit = 512 shl 10
                Graphics.resourceCacheTotalLimit = 4 shl 20
            } else {
                Graphics.fontCacheLimit = normalFontCache
                Graphics.resourceCacheTotalLimit = normalResourceCache
            }
        }.onFailure { Timber.w(it, "No se pudo ajustar la caché de Skia") }
        // El orden importa: MinHeapFreeRatio nunca puede superar a MaxHeapFreeRatio.
        if (enabled) {
            vmOption("MinHeapFreeRatio", "5"); vmOption("MaxHeapFreeRatio", "15")
            vmOption("G1PeriodicGCInterval", "20000")
        } else {
            vmOption("MaxHeapFreeRatio", "70"); vmOption("MinHeapFreeRatio", "40")
            vmOption("G1PeriodicGCInterval", "0")
        }
    }

    /**
     * Con todo oculto: suelta carátulas y cachés de dibujo, compacta el montón y deja que
     * Windows saque de la memoria residente lo que no se toca. Una recolección completa
     * para unos milisegundos el programa, así que solo se hace cuando no hay nada en pantalla.
     */
    suspend fun trimHidden() = withContext(Dispatchers.Default) {
        ArtworkLoader.clear()
        runCatching { Graphics.purgeAllCaches() }
        System.gc()
        if (isWindows) runCatching {
            val process = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcess()
            Psapi.INSTANCE.EmptyWorkingSet(process.pointer)
        }.onFailure { Timber.w(it, "No se pudo recortar la memoria residente") }
    }

    /** Mientras todo siga oculto, repite el recorte de vez en cuando: el audio vuelve a tocar memoria. */
    suspend fun whileHidden() {
        delay(1500)
        while (kotlin.coroutines.coroutineContext.isActive) {
            trimHidden()
            delay(120_000)
        }
    }

    private fun vmOption(name: String, value: String) {
        runCatching {
            ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean::class.java).setVMOption(name, value)
        }.onFailure { Timber.d("Opción JVM $name no disponible: ${it.message}") }
    }

    private interface Psapi : Library {
        fun EmptyWorkingSet(process: Pointer?): Boolean
        companion object { val INSTANCE: Psapi by lazy { Native.load("psapi", Psapi::class.java) } }
    }
}
