package io.github.iaguito22.oto.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.github.iaguito22.oto.QualityMode
import java.io.IOException
import kotlin.math.min

/**
 * La calidad de una canción. Se decide una vez, al empezar a cargarla, y no cambia hasta que acaba: cada una se
 * guarda en la caché con su propia clave (`id` la alta, `id~l` la baja), así nunca se mezclan trozos de dos formatos.
 */
enum class Tier(val suffix: String) {
    High(""), Low("~l");

    fun key(id: String) = id + suffix

    companion object {
        fun idOf(key: String) = key.substringBefore('~')
        /** La que pide la clave, si la pide (una canción que se pasó a baja por no arrancar). */
        fun forcedBy(key: String) = entries.firstOrNull { it.suffix.isNotEmpty() && key.endsWith(it.suffix) }
    }
}

/** Lo que manda al elegir: los ajustes y cómo va la red justo ahora. */
data class Conditions(val mode: QualityMode, val dataSaver: Boolean, val metered: Boolean, val bandwidthBps: Long)

/**
 * Qué calidad pedir para una canción que aún no ha empezado a cargar. Si ya está entera en alta en la caché, esa:
 * no gasta datos ni puede cortarse.
 */
fun chooseTier(c: Conditions, cachedHigh: Boolean): Tier = when {
    cachedHigh -> Tier.High
    c.dataSaver && c.metered -> Tier.Low
    c.mode == QualityMode.Max -> Tier.High
    // Auto: alta si la conexión da para bajarla bastante más deprisa de lo que suena; si no, la que no se corta.
    c.bandwidthBps >= AUTO_HIGH_BPS -> Tier.High
    else -> Tier.Low
}

// La alta ronda los 150 kbps: con algo más del triple, una canción entera (que se baja de golpe, ver ChunkedDataSource)
// llega en poco más de un minuto, muy por delante de lo que suena. Medido con un proxy lento: a ~330 kbps reales, una
// de 2:40 en alta tardó ~60 s.
const val AUTO_HIGH_BPS = 500_000L

/**
 * Cuánto carga por delante: la que suena entera y la siguiente entera, en cuanto se puede (una canción son 2-6 MB),
 * así un túnel o una zona sin cobertura a mitad no la corta. Más allá, nada: con 20 min de colchón ExoPlayer seguía
 * bajando 4 o 5 de la radio, datos que no se oyen si las pasas. Con Máxima, además, no arranca hasta tener unos
 * segundos de colchón. [playing]: el índice de la que suena (la cola de Oto va en el orden en que suena, también en
 * aleatorio).
 */
@OptIn(UnstableApi::class)
class WholeSongLoadControl(private val mode: () -> QualityMode, private val playing: () -> Int) : LoadControl {
    private val base = build()

    // Uno a uno: `by` no reenvía los métodos por defecto de la interfaz de Java (y esos lanzan «not implemented»).
    override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        base.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId): Allocator = base.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId) = base.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = base.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val uid = parameters.mediaPeriodId.periodUid
        if (!parameters.timeline.isEmpty && parameters.timeline.getIndexOfPeriod(uid) != C.INDEX_UNSET) {
            val window = parameters.timeline.getPeriodByUid(uid, period).windowIndex
            if (window > playing() + 1) return false
        }
        return base.shouldContinueLoading(parameters)
    }
    private val period = Timeline.Period()
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long) =
        base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        if (mode() == QualityMode.Max) parameters.bufferedDurationUs >= (if (parameters.rebuffering) MAX_REBUFFER_US else MAX_START_US)
        else base.shouldStartPlayback(parameters)

    private companion object {
        const val MAX_START_US = 6_000_000L
        const val MAX_REBUFFER_US = 12_000_000L

        fun build(): LoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(WHOLE_MS, WHOLE_MS, START_MS, REBUFFER_MS)
            .setTargetBufferBytes(BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 20 min: cualquier canción entera (una sesión de DJ de una hora, a trozos de 20 min).
        const val WHOLE_MS = 20 * 60 * 1000
        const val START_MS = 1_500
        const val REBUFFER_MS = 4_000
        const val BUFFER_BYTES = 48 shl 20
    }
}

/**
 * Sin red, ExoPlayer reintenta la carga sin rendirse (cada vez algo más tarde, hasta cada 5 s) en vez de dar error:
 * mientras quede colchón no se nota, y en cuanto vuelve la cobertura sigue desde donde iba. Lo que no es de red
 * (una URL rechazada, un vídeo que no se puede poner) sigue siendo un error, que [PlaybackService] arregla.
 */
@OptIn(UnstableApi::class)
class PatientErrorPolicy(private val isNetwork: (IOException) -> Boolean) : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int) = Int.MAX_VALUE

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = info.exception
        if (!isNetwork(e)) return C.TIME_UNSET
        return min(info.errorCount * 1_000L, 5_000L)
    }
}

/** Un fallo de la conexión (no de lo que respondió el servidor). */
@OptIn(UnstableApi::class)
fun IOException.isConnectionFailure(): Boolean =
    this is HttpDataSource.HttpDataSourceException && this !is HttpDataSource.InvalidResponseCodeException &&
        this !is HttpDataSource.InvalidContentTypeException
