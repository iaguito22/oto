package io.github.iaguito22.oto.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import kotlin.math.min

/**
 * Pide el audio a trozos de [chunk] bytes, uno detrás de otro, como si fuera una sola petición.
 *
 * YouTube frena las peticiones largas: la canción entera de una vez llega a ~33 KB/s (apenas el doble de lo que gasta
 * al sonar en alta), y cualquier bajón de cobertura la dejaba sin colchón. Cada trozo, en cambio, llega a la velocidad
 * de la red (1 MB en ~0,2 s con wifi): medido el 20/10 con la misma URL.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(private val upstream: HttpDataSource, private val chunk: Long = CHUNK_BYTES) : DataSource {
    private var spec: DataSpec? = null
    private var position = 0L
    // Fin absoluto (exclusivo) si se sabe: por la petición o por el Content-Range del primer trozo.
    private var end = C.LENGTH_UNSET.toLong()
    private var asked = 0L
    private var got = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else dataSpec.position + dataSpec.length
        openChunk()
        return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - dataSpec.position
    }

    /** Abre el trozo que empieza en [position]; false si ya no queda nada. */
    private fun openChunk(): Boolean {
        val s = spec ?: return false
        if (end != C.LENGTH_UNSET.toLong() && position >= end) return false
        asked = if (end == C.LENGTH_UNSET.toLong()) chunk else min(chunk, end - position)
        got = 0
        upstream.open(s.subrange(position - s.position, asked))
        opened = true
        if (end == C.LENGTH_UNSET.toLong()) totalLength()?.let { end = it }
        return true
    }

    /** «bytes 0-1048575/3456789» → 3456789. */
    private fun totalLength(): Long? = upstream.responseHeaders.entries
        .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
        ?.value?.firstOrNull()?.substringAfterLast('/')?.trim()?.toLongOrNull()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) return C.RESULT_END_OF_INPUT
        var n = upstream.read(buffer, offset, length)
        if (n == C.RESULT_END_OF_INPUT) {
            upstream.close()
            opened = false
            // Un trozo que llega corto sin saber el total es que el archivo se acabó.
            if (end == C.LENGTH_UNSET.toLong() && got < asked) return C.RESULT_END_OF_INPUT
            if (!openChunk()) return C.RESULT_END_OF_INPUT
            n = upstream.read(buffer, offset, length)
        }
        if (n > 0) {
            position += n
            got += n
        }
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        opened = false
        spec = null
        upstream.close()
    }

    companion object {
        // Pequeño para que en el móvil un corte a mitad pierda poco; cada petición más cuesta solo una ida y vuelta.
        const val CHUNK_BYTES = 1L shl 20
    }
}
