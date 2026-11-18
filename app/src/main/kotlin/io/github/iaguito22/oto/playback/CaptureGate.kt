package io.github.iaguito22.oto.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Silencio en el propio audio (PCM), no con el volumen del reproductor.
 *
 * Al grabar la pantalla, Android copia el audio de cada reproductor ANTES de aplicarle su volumen (salida secundaria al
 * `r_submix`): lo que el relevo callaba con `ExoPlayer.volume = 0` se grababa entero (medido 15/11: el vídeo tenía la
 * canción sin mezclar). Con esta puerta cerrada, lo callado tampoco llega a la grabación.
 *
 * Actúa sobre lo que se escribe ahora y se oye tras el búfer del AudioTrack (~0,5–1 s): se abre antes de subir el
 * volumen y se cierra después de bajarlo; el volumen sigue mandando en lo que se oye y en el momento exacto del relevo.
 */
class CaptureGate {
    @Volatile var open: Boolean = true

    /**
     * Nivel de cada canción (la normalización de volumen), por id. Con el volumen del reproductor no llegaba a la
     * grabación: fuera de las mezclas el vídeo iba 6–8 dB más alto que el puente (que la lleva horneada).
     */
    @Volatile var levels: Map<String, Float> = emptyMap()
    /** Periodo de la línea de tiempo (uid) → id de la canción: lo pone el servicio al cambiar la cola. */
    @Volatile var periodIds: Map<Any, String> = emptyMap()
    // Desfase de cada tramo (lo que da el renderer al empezar a leerlo) → canción, para cambiar de nivel justo donde
    // empieza su audio y no cuando cambia la pantalla.
    private val streamIds = java.util.concurrent.ConcurrentHashMap<Long, String>()
    @Volatile private var currentId: String? = null
    private var lastLevel = 1f

    // Lo siguiente que pase sale ya en su estado, sin rampa (recién preparado o tras buscar).
    @Volatile private var snapNext = false
    private var gain = 1f
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var rate = 0
    @Volatile private var fadeAfterFlushMs = 0L
    @Volatile private var activeFadeMs = 0L
    @Volatile private var fadeFramesLeft = 0
    private var fadeTotalFrames = 0

    /** Entrada tras recuperar una búsqueda: la rampa viaja en el PCM, aunque el AudioTrack arranque tarde. */
    fun fadeInAfterFlush(durationMs: Long) {
        fadeAfterFlushMs = durationMs.coerceIn(0L, 1_000L)
    }

    /** Si el seek no produjo flush, retirar la petición al terminar su recuperación. */
    fun clearPendingFade() {
        fadeAfterFlushMs = 0L
    }

    private fun recoveryGain(): Float {
        if (fadeFramesLeft <= 0) return 1f
        val g = 1f - fadeFramesLeft.toFloat() / fadeTotalFrames.coerceAtLeast(1)
        fadeFramesLeft--
        return g
    }

    /** Cierra (o abre) sin rampa: para un reproductor que aún no suena. */
    fun set(open: Boolean) {
        this.open = open
        snapNext = true
    }

    fun configure(encoding: Int, channels: Int, rate: Int) {
        this.encoding = encoding
        this.channels = channels
        this.rate = rate
    }

    fun onFlush() {
        snapNext = true
        val requested = fadeAfterFlushMs
        if (requested > 0L) {
            activeFadeMs = requested
            fadeAfterFlushMs = 0L
        }
    }

    /** El renderer empieza a leer un tramo nuevo (otra canción): con qué desfase saldrá su audio. */
    fun onStreamRead(offsetUs: Long, periodUid: Any) {
        val id = periodIds[periodUid] ?: return
        streamIds[offsetUs] = id
        if (streamIds.size > 32) streamIds.keys.sorted().take(streamIds.size - 32).forEach { streamIds.remove(it) }
    }

    /** El audio que llega ya es de ese tramo: su nivel, de golpe (es la frontera entre canciones). */
    fun onStreamOutput(offsetUs: Long) {
        val id = streamIds[offsetUs] ?: return
        if (id != currentId) {
            currentId = id
            snapNext = true
            val l = levels[id]
            runCatching { MixLog.i("nivel PCM: $id ${l?.let { "%.1f dB".format(20 * kotlin.math.log10(it.coerceAtLeast(1e-4f))) } ?: "sin dato"}") }
        }
    }

    private fun level(): Float {
        val l = currentId?.let { levels[it] }
        if (l != null) lastLevel = l
        return lastLevel
    }

    /** Si el audio pasaría sin tocar (abierta, a nivel 1 y sin rampa): entonces ni se copia. */
    fun passthrough(): Boolean = open && !snapNext && activeFadeMs == 0L && fadeFramesLeft == 0 && gain == 1f && level() == 1f

    /** Aplica la puerta a `[from, to)` de [buffer] (bytes PCM entrelazados), en su sitio. */
    fun apply(buffer: ByteBuffer, from: Int, to: Int) {
        val target = if (open) level() else 0f
        if (snapNext) {
            snapNext = false
            gain = target
        }
        val fadeMs = activeFadeMs
        if (fadeMs > 0L && rate > 0) {
            fadeTotalFrames = (fadeMs * rate / 1000L).toInt().coerceAtLeast(1)
            fadeFramesLeft = fadeTotalFrames
            activeFadeMs = 0L
        }
        if (gain == 1f && target == 1f && fadeFramesLeft == 0) return
        val ch = channels.coerceAtLeast(1)
        val step = 1f / max(1f, RAMP_SECONDS * rate)
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var i = from
                while (i + 2 * ch <= to) {
                    gain = next(gain, target, step)
                    val recovery = recoveryGain()
                    for (c in 0 until ch) {
                        val at = i + 2 * c
                        b.putShort(at, (b.getShort(at) * gain * recovery).toInt().toShort())
                    }
                    i += 2 * ch
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                var i = from
                while (i + 4 * ch <= to) {
                    gain = next(gain, target, step)
                    val recovery = recoveryGain()
                    for (c in 0 until ch) {
                        val at = i + 4 * c
                        b.putFloat(at, b.getFloat(at) * gain * recovery)
                    }
                    i += 4 * ch
                }
            }
            else -> gain = target
        }
    }

    private fun next(g: Float, target: Float, step: Float): Float = when {
        g < target -> (g + step).coerceAtMost(target)
        g > target -> (g - step).coerceAtLeast(target)
        else -> g
    }

    internal companion object {
        const val RAMP_SECONDS = 0.03f
    }
}

/** Pasa el audio por [gate] antes de [delegate] (y después de lo que lo envuelva, p. ej. la toma de [AudioLevels]). */
@OptIn(UnstableApi::class)
class CaptureGateSink(delegate: AudioSink, private val gate: CaptureGate) : ForwardingAudioSink(delegate) {
    // Los búferes del decodificador son de solo lectura (medido: ReadOnlyBufferException): con la puerta actuando se
    // pasa una copia. Si el sink no se la traga entera, el renderer vuelve con el MISMO original y hay que devolverle la
    // misma copia (DefaultAudioSink exige el mismo objeto), avanzando el original lo que se consumió de ella.
    private var scratch: ByteBuffer? = null
    private var copyOf: ByteBuffer? = null
    private var pendingCopy: ByteBuffer? = null
    private var pendingOriginal: ByteBuffer? = null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        gate.configure(inputFormat.pcmEncoding, inputFormat.channelCount, inputFormat.sampleRate)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val copy = pendingCopy
        if (copy != null && buffer === copyOf) return feedCopy(buffer, copy, presentationTimeUs, encodedAccessUnitCount)
        if (pendingOriginal === buffer) {
            val done = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            if (!buffer.hasRemaining()) pendingOriginal = null
            return done
        }
        pendingCopy = null
        copyOf = null
        pendingOriginal = null
        if (gate.passthrough()) {
            val done = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            if (buffer.hasRemaining()) pendingOriginal = buffer
            return done
        }
        val size = buffer.remaining()
        val s = scratch?.takeIf { it.capacity() >= size }
            ?: ByteBuffer.allocateDirect(max(size, 16_384)).order(ByteOrder.nativeOrder()).also { scratch = it }
        s.clear()
        s.put(buffer.duplicate())
        s.flip()
        gate.apply(s, 0, s.limit())
        copyOf = buffer
        return feedCopy(buffer, s, presentationTimeUs, encodedAccessUnitCount)
    }

    private fun feedCopy(original: ByteBuffer, copy: ByteBuffer, pts: Long, count: Int): Boolean {
        val before = copy.position()
        val done = super.handleBuffer(copy, pts, count)
        original.position(original.position() + (copy.position() - before))
        if (copy.hasRemaining()) {
            pendingCopy = copy
        } else {
            pendingCopy = null
            copyOf = null
        }
        return done
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        gate.onStreamOutput(outputStreamOffsetUs)
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun flush() {
        gate.onFlush()
        pendingCopy = null
        copyOf = null
        pendingOriginal = null
        super.flush()
    }
}
