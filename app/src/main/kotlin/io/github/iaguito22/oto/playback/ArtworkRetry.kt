package io.github.iaguito22.oto.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Una carátula que no llega se vuelve a pedir sola: cada vez más espaciado y enseguida cuando vuelve la red. Antes,
 * tras un fallo con mala conexión, la app y la notificación se quedaban sin ella hasta que algo las rehacía.
 */
object ArtworkRetry {
    const val MAX_ATTEMPTS = 12

    private val _network = MutableStateFlow(0)
    /** Sube cada vez que hay red nueva (ver `OtoApp`). */
    val network: StateFlow<Int> = _network

    fun networkBack() = _network.update { it + 1 }

    /** 1 s, 2 s, 4 s… hasta 30 s. */
    fun delayMs(attempt: Int): Long = (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

    suspend fun <T : Any> retrying(
        network: StateFlow<Int> = this.network,
        delayMs: (Int) -> Long = ::delayMs,
        fetch: suspend () -> T?,
    ): T? {
        for (attempt in 0 until MAX_ATTEMPTS) {
            val seen = network.value
            fetch()?.let { return it }
            if (attempt == MAX_ATTEMPTS - 1) break
            withTimeoutOrNull(delayMs(attempt)) { network.first { it != seen } }
        }
        return null
    }
}

/**
 * Las carátulas de la notificación y del reproductor del sistema, por Coil (la misma caché que la app) y con
 * [ArtworkRetry]. Media3 vuelve a pedirla en cada cambio de la notificación: se le da la misma petición en curso.
 */
@UnstableApi
class ArtworkBitmapLoader(private val context: Context) : BitmapLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loads = object : LinkedHashMap<String, ListenableFuture<Bitmap>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ListenableFuture<Bitmap>>) = size > 4
    }

    override fun supportsMimeType(mimeType: String) = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        BitmapFactory.decodeByteArray(data, 0, data.size)?.let { Futures.immediateFuture(it) }
            ?: Futures.immediateFailedFuture(IOException("carátula ilegible"))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = synchronized(loads) {
        val key = uri.toString()
        loads[key]?.let { return it }
        val future = SettableFuture.create<Bitmap>()
        loads[key] = future
        scope.launch {
            val bitmap = ArtworkRetry.retrying {
                val result = SingletonImageLoader.get(context)
                    .execute(ImageRequest.Builder(context).data(key).allowHardware(false).build())
                (result as? SuccessResult)?.image?.toBitmap()
            }
            if (bitmap != null) future.set(bitmap)
            else {
                // Un fallo no se guarda: la próxima vez se vuelve a intentar.
                synchronized(loads) { if (loads[key] === future) loads.remove(key) }
                future.setException(IOException("sin carátula: $key"))
            }
        }
        future
    }
}
