package io.github.iaguito22.oto.desktop.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.EOFException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI

/** Tamaño de cada trozo: el mismo que usa Android y que evita el estrangulamiento de YouTube. */
const val DOWNLOAD_CHUNK_BYTES = 1_048_576L

/**
 * Baja una URL por trozos de 1 MB con `Range`, reanudando desde lo que ya haya en [outFile].
 *
 * - Si el servidor ignora el `Range` (200 en vez de 206) y ya había datos, se empieza de cero para no corromper.
 * - [knownLength] puede ser null: se deduce del `Content-Range` o de la primera respuesta.
 * - [onProgress] recibe (descargado, total); total puede ser 0 mientras no se sepa.
 *
 * Devuelve el número de bytes del archivo al terminar.
 */
suspend fun downloadChunked(
    streamUrl: String,
    headers: Map<String, String>,
    knownLength: Long?,
    outFile: File,
    onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
): Long = withContext(Dispatchers.IO) {
    outFile.parentFile?.mkdirs()
    var offset = if (outFile.exists()) outFile.length() else 0L
    var total = knownLength ?: -1L

    RandomAccessFile(outFile, "rw").use { raf ->
        raf.seek(offset)
        while (currentCoroutineContext().isActive && (total < 0L || offset < total)) {
            val end = if (total > 0L) minOf(offset + DOWNLOAD_CHUNK_BYTES - 1L, total - 1L)
            else offset + DOWNLOAD_CHUNK_BYTES - 1L
            val conn = (URI.create(streamUrl).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                // Cabecera Range de verdad: el parámetro `range` de la URL devuelve 200 y el archivo entero,
                // así que al reanudar se duplicarían los trozos.
                setRequestProperty("Range", "bytes=$offset-$end")
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK && offset > 0L) {
                    // El servidor devolvió el archivo entero: lo de antes sobra.
                    raf.setLength(0)
                    raf.seek(0)
                    offset = 0L
                }
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code en el trozo $offset-$end")
                }
                if (total < 0L) {
                    val range = conn.getHeaderField("Content-Range")
                    total = range?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0L } ?: -1L
                }
                val cl = conn.contentLengthLong
                if (code == HttpURLConnection.HTTP_OK && cl >= 0L) total = cl
                if (total < 0L && cl > 0L && cl < DOWNLOAD_CHUNK_BYTES) {
                    total = offset + cl
                }
                val buf = ByteArray(32 * 1024)
                var readInChunk = 0L
                conn.inputStream.use { ins ->
                    while (currentCoroutineContext().isActive) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        offset += n
                        readInChunk += n
                        onProgress(offset, if (total > 0L) total else 0L)
                    }
                }
                currentCoroutineContext().ensureActive()
                // Un cuerpo corto no completa una descarga cuyo tamaño ya conocemos.
                if (total >= 0L && offset < minOf(end + 1, total)) {
                    throw EOFException("El audio se interrumpió: $offset de $total bytes")
                }
                if (code == HttpURLConnection.HTTP_OK || (total < 0L && readInChunk < DOWNLOAD_CHUNK_BYTES)) total = offset
            } finally {
                conn.disconnect()
            }
        }
    }
    currentCoroutineContext().ensureActive()
    onProgress(offset, offset)
    offset
}
