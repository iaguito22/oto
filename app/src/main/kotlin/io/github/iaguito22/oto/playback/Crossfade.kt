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
 * Para que el relevo no se oiga, [tail] se prepara antes, parado en el punto exacto, y arranca un poco antes de que
 * [player] llegue a él: lo que tarda en sonar desde que se le pide ([lead]), que se mide en cada relevo.
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
    private var lead = 120L

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
        val left = at - pos
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
            t.setMediaItem(item, at)
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
        handOver(t, at, fade)
        return IDLE_MS
    }

    private suspend fun handOver(t: ExoPlayer, at: Long, fade: Long) {
        val outGain = gain()
        t.volume = outGain
        t.play()
        // Hasta que la de [tail] suena de verdad (su posición avanza), [player] sigue con ella.
        val asked = System.nanoTime()
        while (t.currentPosition <= at && System.nanoTime() - asked < 1_000_000_000L) {
            if (!player.isPlaying) { stopTail(); return }
            delay(4)
        }
        val drift = player.currentPosition - t.currentPosition
        // Lo que tardó en sonar, para la próxima vez (sin pasarse: un tirón puntual no lo descoloca).
        lead = (lead + drift * 7 / 10).coerceIn(0, 400)
        Timber.d("fundido: relevo con %d ms de desfase, adelanto %d ms", drift, lead)
        onFadeIn(0f)
        player.seekToNextMediaItem()
        val from = player.currentMediaItemIndex
        preparedFor = null
        val start = System.nanoTime()
        while (true) {
            val p = ((System.nanoTime() - start) / 1e6 / fade).toFloat().coerceIn(0f, 1f)
            // Potencia constante: a mitad las dos suenan a un 70 %, y no se nota un bajón.
            t.volume = outGain * cos(p * PI.toFloat() / 2)
            onFadeIn(sin(p * PI.toFloat() / 2))
            // Pausar o pasar de canción a mano corta el fundido: la que entra, a su volumen; la que se va, fuera.
            // playWhenReady y no isPlaying: justo tras el salto la que entra está un momento cargando, y eso no es pausar.
            if (p >= 1f || !player.playWhenReady || player.currentMediaItemIndex != from) break
            delay(FRAME_MS)
        }
        onFadeIn(1f)
        stopTail()
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
    }
}
