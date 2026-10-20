package io.github.iaguito22.oto

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil3.SingletonImageLoader
import io.github.iaguito22.oto.playback.Tier
import java.io.File

/**
 * Lo que Oto guarda en el móvil, con su tope. Nada crece sin límite: el audio se recorta solo (las menos usadas
 * primero), las carátulas y las respuestas de YouTube también, y lo del DJ se poda al arrancar. Todo junto no pasa
 * de [TOTAL_BYTES].
 */
object Storage {
    const val AUDIO_BYTES = 1L shl 30 // 1 GB: unas 200 canciones en alta
    const val IMAGES_BYTES = 200L shl 20
    const val HTTP_BYTES = 50L shl 20 // la caché de OkHttp de InnerTube (ver InnerTube.kt)
    const val DJ_BYTES = 20L shl 20
    const val TOTAL_BYTES = AUDIO_BYTES + IMAGES_BYTES + HTTP_BYTES + DJ_BYTES

    // Lo más largo que el DJ guarda algo (ver YouTubeCatalog): pasado eso ya no se lee, solo ocupa.
    private const val DJ_MAX_AGE_MS = 8L * 7 * 24 * 3600 * 1000

    /** Lo del DJ: fuera lo caducado y, si aun así pasa del tope, lo más viejo. */
    fun trimDj(dir: File) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (now - f.lastModified() < DJ_MAX_AGE_MS && total <= DJ_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    /** Cuánto ocupa ahora todo lo que se puede borrar (la caché de la app entera, también lo de WebView). */
    fun used(app: OtoApp): Long = app.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * Vacía el audio (menos la que suena, que se está leyendo), las carátulas y lo del DJ. Las listas, los me gusta
     * y el historial no son caché: no se tocan.
     */
    @OptIn(UnstableApi::class)
    fun clear(app: OtoApp, playingId: String?) {
        val keep = playingId?.let { id -> Tier.entries.map { it.key(id) }.toSet() }.orEmpty()
        val cache = app.audioCache
        cache.keys.filter { it !in keep }.forEach { runCatching { cache.removeResource(it) } }
        SingletonImageLoader.get(app).let { it.diskCache?.clear(); it.memoryCache?.clear() }
        File(app.cacheDir, "dj").listFiles()?.forEach { it.delete() }
    }
}
