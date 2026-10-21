package io.github.iaguito22.oto.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fundido entre canciones: el final de una suena a la vez que el principio de la siguiente, una apagándose y la otra
 * subiendo.
 *
 * El reproductor de la sesión ([player]) pasa a la siguiente justo al empezar el fundido, y lo que queda de la que se va
 * lo pone otro, [tail], sin sesión ni foco de audio, que se apaga poco a poco. Así la cola, la notificación, la letra y
 * todo lo demás no se enteran de nada: para ellos, la siguiente empezó unos segundos antes. Solo cuando una acaba
 * sola; pasar de canción a mano corta como siempre.
 *
 * Para que el relevo no se oiga, [tail] se prepara antes, parado en su punto, y arranca callado un poco antes de que
 * [player] llegue a él: lo que tarda en sonar desde que se le pide ([lead]), que se mide en cada relevo. Lo que dice
 * de su posición nada más arrancar es una estimación (el audio tarda en salir de verdad, y con Bluetooth cientos de
 * ms): por eso suenan los dos a la vez [SETTLE_MS], él sin volumen, y solo entonces se pasa el sonido de uno a otro
 * en un momento. Callar [player] en cuanto [tail] decía sonar dejaba un hueco: el mini corte al entrar la otra.
 */
@OptIn(UnstableApi::class)
class Crossfade(
    private val context: Context,
    private val player: ExoPlayer,
    private val sources: MediaSource.Factory,
    private val scope: CoroutineScope,
    /** Cuánto dura, en ms (0 = sin fundido). */
    private val length: () -> Long,
    /** El volumen de la que suena (el de la normalización de YouTube). */
    private val gain: () -> Float,
    /** Cuánto ha subido la que entra: 0 → 1. El servicio lo multiplica por su volumen. */
    private val onFadeIn: (Float) -> Unit,
) {
    private var tail: ExoPlayer? = null
    private var job: Job? = null
    // La que [tail] tiene preparada (su id y su posición en la cola), o la que se decidió no fundir.
    private var preparedFor: Pair<String, Int>? = null
    private var skipped: Pair<String, Int>? = null
    private var lead = 200L

    /** Empieza a vigilar el final de cada canción. */
    fun start() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                val wait = step()
                delay(wait)
            }
        }
    }

    fun release() {
        job?.cancel()
        tail?.release()
        tail = null
    }

    /** Un vistazo: devuelve cuánto esperar hasta el siguiente. */
    private suspend fun step(): Long {
        val fade = length()
        val item = player.currentMediaItem
        if (fade <= 0 || item == null || !player.isPlaying || player.repeatMode == Player.REPEAT_MODE_ONE || !player.hasNextMediaItem()) {
            stopTail()
            return IDLE_MS
        }
        val dur = player.duration
        val pos = player.currentPosition
        val me = item.mediaId to player.currentMediaItemIndex
        // Las muy cortas (o sin duración aún) no se funden: se comería media canción.
        if (dur == C.TIME_UNSET || dur < fade * 3 || me == skipped) return IDLE_MS
        val at = dur - fade
        // [tail] arranca antes del fundido: lo que tarda en asentarse.
        val from = at - SETTLE_MS - ALIGN_BUDGET_MS
        val left = from - pos
        if (preparedFor != null && preparedFor != me) stopTail()
        if (left > PREPARE_MS) return (left - PREPARE_MS).coerceIn(50, IDLE_MS)

        val t = tail ?: build().also { tail = it }
        if (preparedFor == null) {
            // Si la siguiente aún no ha empezado a bajar, fundir sería meter un silencio en medio: se deja acabar sola.
            if (player.totalBufferedDuration - (dur - pos) < NEXT_READY_MS) {
                Timber.d("fundido: la siguiente no está lista, sin fundido")
                skipped = me
                return IDLE_MS
            }
            preparedFor = me
            t.setMediaItem(item, from)
            t.volume = 0f
            t.playWhenReady = false
            t.prepare()
            return FRAME_MS
        }
        if (left > lead) return FRAME_MS.coerceAtMost(left - lead).coerceAtLeast(1)
        if (t.playbackState != Player.STATE_READY) {
            // No llegó a tiempo: mejor sin fundido que con un hueco.
            Timber.d("fundido: la que se va no estaba lista")
            stopTail()
            skipped = me
            return IDLE_MS
        }
        handOver(t, fade)
        return IDLE_MS
    }

    private suspend fun handOver(t: ExoPlayer, fade: Long) {
        val outGain = gain()
        val index = player.currentMediaItemIndex
        t.volume = 0f
        t.play()
        // Sonando callado hasta que su posición es la de verdad. Pausar o pasar de canción mientras, lo cancela.
        val asked = System.nanoTime()
        fun cancelled() = !player.playWhenReady || player.currentMediaItemIndex != index || t.playerError != null
        while (System.nanoTime() - asked < SETTLE_MS * 1_000_000L) {
            if (cancelled()) { stopTail(); return }
            delay(FRAME_MS)
        }
        val drift = player.currentPosition - t.currentPosition
        // Lo que tardó en sonar, para la próxima vez (sin pasarse: un tirón puntual no lo descoloca).
        lead = (lead + drift * 7 / 10).coerceIn(0, 1000)
        // Y para esta: nunca arranca clavada (en un Nothing Phone (2), hasta 90 ms por delante), y pasar el sonido con ese desfase se
        // oía como un corte. Callada, se la acelera o se la frena un poco hasta que va a la par.
        val aligned = align(t, index) ?: return
        Timber.d("fundido: desfase %d ms → %d ms, adelanto %d ms", drift, aligned, lead)
        // El paso de uno a otro: la misma música, así que lineal (a mitad suman lo mismo que una).
        val swap = System.nanoTime()
        while (true) {
            val q = ((System.nanoTime() - swap) / 1e6 / SWAP_MS).toFloat().coerceIn(0f, 1f)
            if (!player.playWhenReady || player.currentMediaItemIndex != index) { onFadeIn(1f); stopTail(); return }
            t.volume = outGain * q
            onFadeIn(1f - q)
            if (q >= 1f) break
            delay(4)
        }
        onFadeIn(0f)
        player.seekToNextMediaItem()
        val next = player.currentMediaItemIndex
        preparedFor = null
        val start = System.nanoTime()
        while (true) {
            val p = ((System.nanoTime() - start) / 1e6 / fade).toFloat().coerceIn(0f, 1f)
            // Potencia constante: a mitad las dos suenan a un 70 %, y no se nota un bajón.
            t.volume = outGain * cos(p * PI.toFloat() / 2)
            onFadeIn(sin(p * PI.toFloat() / 2))
            // Pausar o pasar de canción a mano corta el fundido: la que entra, a su volumen; la que se va, fuera.
            // playWhenReady y no isPlaying: justo tras el salto la que entra está un momento cargando, y eso no es pausar.
            if (p >= 1f || !player.playWhenReady || player.currentMediaItemIndex != next) break
            delay(FRAME_MS)
        }
        onFadeIn(1f)
        stopTail()
    }

    /**
     * Ajusta la velocidad de [t] (callada) hasta que va a la par de [player]. Devuelve el desfase que queda, o null si
     * se canceló. La posición ya tiene en cuenta la velocidad, así que se puede ir mirando mientras.
     */
    private suspend fun align(t: ExoPlayer, index: Int): Long? {
        val until = System.nanoTime() + ALIGN_MS * 1_000_000L
        var good = 0
        var drift = player.currentPosition - t.currentPosition
        while (System.nanoTime() < until) {
            if (!player.playWhenReady || player.currentMediaItemIndex != index || t.playerError != null) {
                t.setPlaybackSpeed(1f); stopTail(); return null
            }
            drift = player.currentPosition - t.currentPosition
            if (abs(drift) <= ALIGNED_MS) {
                if (t.playbackParameters.speed != 1f) t.setPlaybackSpeed(1f)
                if (++good >= 4) break
            } else {
                good = 0
                // Proporcional: 100 ms de desfase → un 10 %, que se nota poco y lo corrige en menos de un segundo.
                t.setPlaybackSpeed(1f + (drift / 1000f).coerceIn(-MAX_NUDGE, MAX_NUDGE))
            }
            delay(FRAME_MS)
        }
        t.setPlaybackSpeed(1f)
        return drift
    }

    private fun stopTail() {
        preparedFor = null
        val t = tail ?: return
        if (t.playbackState != Player.STATE_IDLE || t.mediaItemCount > 0) {
            t.stop()
            t.clearMediaItems()
        }
    }

    private fun build() = ExoPlayer.Builder(context)
        .setMediaSourceFactory(sources)
        // Sin foco de audio: si lo pidiera, le quitaría el suyo a [player], que se pararía.
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .build()

    private companion object {
        const val IDLE_MS = 500L
        const val FRAME_MS = 16L
        // Con qué antelación se prepara la que se va (de la caché: está entera).
        const val PREPARE_MS = 2_500L
        // De la siguiente, al menos esto ya bajado.
        const val NEXT_READY_MS = 3_000L
        // Cuánto suena [tail] callado antes del relevo, y lo que dura el relevo.
        const val SETTLE_MS = 600L
        // Lo que suele tardar en ponerse a la par: así el fundido sigue empezando más o menos donde tocaba.
        const val ALIGN_BUDGET_MS = 600L
        const val SWAP_MS = 80L
        // Cuánto se le da para ponerse a la par, cuánto desfase se acepta y cuánto se la puede acelerar o frenar.
        const val ALIGN_MS = 2_000L
        const val ALIGNED_MS = 6L
        const val MAX_NUDGE = 0.12f
    }
}
