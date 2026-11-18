package io.github.iaguito22.oto.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.iaguito22.oto.dj.ChorusSafety
import io.github.iaguito22.oto.dj.SongSection
import io.github.iaguito22.oto.dj.SongSections
import io.github.iaguito22.oto.dj.DjBridgeRenderer
import io.github.iaguito22.oto.dj.DjBridgeSpec
import io.github.iaguito22.oto.dj.DjBridgeStyle
import io.github.iaguito22.oto.dj.DjDsp
import io.github.iaguito22.oto.dj.DjTempoMatch
import io.github.iaguito22.oto.dj.DjTransitionPlan
import io.github.iaguito22.oto.dj.DjTransitionStyle
import io.github.iaguito22.oto.dj.RenderedDjBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/** Plan de salida adelantada (p. ej. segmento corto en DJ rápido ADHD). */
data class TransitionPlan(val transitionAtMs: Long, val fadeDurationMs: Long)

/** En qué punto está la mezcla con la siguiente, para la cola: preparándose, lista o (sin puente) en vivo. */
enum class MixUiState(val id: String) {
    Preparing("preparing"),
    Ready("ready"),
    Live("live"),
    /** Mirada y descartada: esta pareja no se mezcla (muy corta, se buscó al final…). */
    None("none"),
    ;

    companion object {
        fun fromId(id: String?): MixUiState? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Lo que la cola y la barra de tiempo saben de la próxima mezcla: dónde empieza ([startMs]) y dónde pasa a oírse la
 * siguiente ([switchMs]), en la que suena; con qué estilo y si está lista.
 */
data class MixUi(
    val startMs: Long,
    val switchMs: Long,
    val style: DjBridgeStyle?,
    val state: MixUiState,
    /** (la que suena, la siguiente) a las que se refiere: el servicio solo la enseña mientras sean esas dos. */
    val pair: Pair<String, String>? = null,
    val styleLabel: String? = style?.label,
)

/**
 * Registro de los relevos, también en la versión publicada (Timber solo escribe en debug): una línea por fase, para
 * poder medir los cortes con `adb logcat -s oto.mix`.
 */
object MixLog {
    fun i(msg: String) {
        android.util.Log.i("oto.mix", msg)
    }
}

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
 * en un momento. Además, tras saltar [player] a la siguiente (o buscar su segmento ADHD), [tail] mantiene la pista
 * saliente sin bajar hasta que [player] está listo y sonando, evitando huecos de silencio por buffering.
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
    /** Mezcla inteligente tipo Apple Music (curva S equal-power + cruce de graves). */
    private val smartMix: () -> Boolean = { true },
    /** Plan personalizado de transición para el ítem actual (p. ej. modo ADHD). */
    private val transitionPlan: (MediaItem, Long) -> TransitionPlan? = { _, _ -> null },
    /** Posición inicial (ms) al saltar a la siguiente canción en [handOver]. */
    private val nextStartPosition: (MediaItem) -> Long = { 0L },
    private val incomingProcessor: BassSwapAudioProcessor? = null,
    private val outgoingProcessor: BassSwapAudioProcessor? = null,
    private val outgoingEchoProcessor: EchoOutFilterSweepAudioProcessor? = null,
    /** Planificado DJ (beat-snap, tempo-nudge, echo-out + filtro, igualación RMS). */
    private val djMixPlan: (outgoing: MediaItem, incoming: MediaItem, outgoingDurMs: Long, requestedFadeMs: Long) -> DjTransitionPlan? = { _, _, _, _ -> null },
    private val onMixingChanged: (Boolean) -> Unit = {},
    private val sliceDecoder: AndroidAudioSliceDecoder? = null,
    private val cacheKeyFor: (MediaItem) -> String = { it.mediaId },
    private val gainFor: (String?) -> Float = { 1f },
    private val bridgeCacheDir: File? = null,
    private val onPrepareIncomingAnalysis: (trackId: String, cacheKey: String) -> Unit = { _, _ -> },
    private val onMixUiChanged: (MixUi?) -> Unit = {},
    /** Puerta PCM de [player] (ver [CaptureGate]): callado en el audio, no solo con el volumen. */
    private val sessionGate: CaptureGate? = null,
    private val sectionsFor: (String) -> List<SongSection> = { emptyList() },
) {
    private var plannedExit: Pair<Pair<String, Int>, Long>? = null
    fun plannedExitFor(key: Pair<String, Int>): Long? = plannedExit?.takeIf { it.first == key }?.second
    private fun safeBridge(bridge: PreparedBridge): Boolean =
        ChorusSafety.allows(bridge.spec, sectionsFor(bridge.outgoingId), sectionsFor(bridge.incomingId))

    private var tail: ExoPlayer? = null
    // Bucle de beats de la que se va (va en [tail], antes de los filtros).
    private val loop = BeatLoopAudioProcessor()
    private var tailSink: LoopSink? = null
    // Puerta PCM de [tail]: cerrada mientras suena callado (la grabación de pantalla no ve su volumen).
    private val tailGate = CaptureGate()
    private var job: Job? = null
    // La que [tail] tiene preparada (su id y su posición en la cola).
    private var preparedFor: Pair<String, Int>? = null
    private var preparedBridgeWav: PreparedBridge? = null
    private var preparedFromMs: Long = 0L
    private var preparedAtMs: Long = 0L
    private val mixAttempt = MixAttemptRecovery()
    private var deckDiscontinued = false
    private val seekListener = object : Player.Listener {
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (handingOver) return
            deckDiscontinued = true
            val previous = old.mediaItem?.mediaId?.let { it to old.mediaItemIndex }
            val current = new.mediaItem?.mediaId?.let { it to new.mediaItemIndex }
            if (previous != current || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                mixAttempt.watch(null)
            } else if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                mixAttempt.seek(current, old.positionMs, new.positionMs)
            }
        }
    }
    private var lead = 200L
    private var mixingActive = false
    @Volatile var handingOver: Boolean = false
        private set

    private var bridgeRenderJob: Job? = null
    private var bridgeRenderKey: Triple<String, Int, String>? = null
    private var preparedBridge: PreparedBridge? = null
    private var preparedBridgeFailedFor: Triple<String, Int, String>? = null
    private var preparedAnalysisFor: Triple<String, Int, String>? = null
    private val mixNotice = MixNoticeDedup()
    private var mixUiRevision = 0L

    /** Settings can change while paused, when step() does not recalculate a plan. */
    fun invalidateMixUi() {
        mixUiRevision++
        mixNotice.reset()
        // An unplayed deck was prepared under the old settings; recalculate it when playback resumes.
        if (!handingOver) {
            stopTail()
            clearBridge()
            preparedAnalysisFor = null
        }
    }

    private fun bridgeOffsetMs(positionMs: Long, preRollStartMs: Long, preRollMs: Long): Long? =
        bridgeStartOffsetMs(positionMs, preRollStartMs, preRollMs,
            BRIDGE_MIN_PREROLL_ALIGN_MS, BRIDGE_LATE_PREPARE_AHEAD_MS)
    // Si un puente no se pudo hacer (la siguiente aún bajando), cuándo volver a intentarlo.
    private var bridgeRetryAtNs = 0L
    private var bridgeRenderStartedNs = 0L
    // Lo que tarda la siguiente en sonar tras saltar a ella con el puente sonando: se suma al salto y se aprende.
    private var bSeekLeadMs = 150L
    private var reseekLeadMs = 200L
    // Caché de sesión del desfase de códec medido (`ExoPlayer` vivo vs `sliceDecoder`) por `trackId`.
    private val measuredTrackShiftMs = LinkedHashMap<String, Long>(32, 0.75f, true)
    private val progressiveFactory by lazy { ProgressiveMediaSource.Factory(FileDataSource.Factory()) }

    private data class PreparedBridge(
        val outgoingKey: Pair<String, Int>,
        val outgoingId: String,
        val incomingId: String,
        val wavFile: File,
        /** El plan con el que se hizo (sin los retoques del renderizado): si el plan cambia, se rehace. */
        val plannedSpec: DjBridgeSpec,
        val spec: DjBridgeSpec,
        /** Dónde, dentro del WAV, pasa a oírse la siguiente: ahí cambia de canción lo que se ve. */
        val switchWavMs: Long,
        val renderedPreRollMs: Long,
        val dryBStartOffsetMs: Long,
        val totalMs: Long,
        val handoffBTrackMs: Long,
        val endBTrackMs: Long,
        val bakedGainA: Float,
        val bakedGainB: Float,
        val preRoll12k: FloatArray,
        /** Referencia seca de B a 12 kHz desde algo antes de `handoffBTrackMs` hasta el final del post-roll. */
        val syncRefB12k: FloatArray,
        val syncRefBOriginMs: Long,
        val sliceInfo: String,
    )

    private fun rememberTrackShift(trackId: String, shiftMs: Long) {
        synchronized(measuredTrackShiftMs) {
            measuredTrackShiftMs[trackId] = shiftMs
            if (measuredTrackShiftMs.size > 64) {
                val eldest = measuredTrackShiftMs.keys.firstOrNull()
                if (eldest != null) measuredTrackShiftMs.remove(eldest)
            }
        }
    }

    private fun lookupTrackShift(trackId: String): Long? =
        synchronized(measuredTrackShiftMs) { measuredTrackShiftMs[trackId] }

    init {
        cleanBridgeDir()
    }

    private fun setMixing(active: Boolean) {
        if (mixingActive == active) return
        mixingActive = active
        onMixingChanged(active)
    }

    private fun notifyMixUi(ui: MixUi?, why: String? = null, rejectionKey: String? = null) {
        if (!mixNotice.accept(ui, player.currentMediaItemIndex, rejectionKey)) return
        // Diagnóstico del aviso de la cola: qué se publica y por qué (solo al cambiar).
        MixLog.i("aviso: " + (ui?.let { "${it.pair?.first}→${it.pair?.second} ${it.state.id}" + (it.styleLabel?.let { l -> " «$l»" } ?: "") } ?: "nada") +
            (why?.let { " ($it)" } ?: ""))
        onMixUiChanged(ui)
    }

    // Último plan registrado (pareja, estilo, si las dos estaban analizadas enteras): para ver en `oto.mix` si el plan
    // cambia al llegar el análisis completo o se queda con el primero.
    private var loggedPlan: Triple<Triple<String, Int, String>, String?, Pair<Boolean, Boolean>>? = null

    private fun logPlanChange(pairKey: Triple<String, Int, String>?, plan: DjTransitionPlan?, pos: Long) {
        if (pairKey == null || plan == null) return
        val whole = (plan.outgoingAudio?.coversWholeSong == true) to (plan.incomingAudio?.coversWholeSong == true)
        val now = Triple(pairKey, plan.bridgeSpec?.style?.id ?: plan.style.name, whole)
        if (now == loggedPlan) return
        val before = loggedPlan?.takeIf { it.first == pairKey }
        loggedPlan = now
        MixLog.i(
            "plan ${pairKey.first}→${pairKey.third}: ${now.second}" + (before?.let { " (antes ${it.second})" } ?: "") +
                ", análisis entero A=${whole.first} B=${whole.second}, cambio en ${(plan.bridgeSpec?.cueOutStartMs ?: plan.outgoingFadeStartMs) / 1000} s, ahora ${pos / 1000} s" +
                (plan.bridgeSpec?.tempoNote?.takeIf { it.isNotEmpty() }?.let { "; $it" } ?: ""),
        )
    }

    /** Dónde pasaría a oírse la siguiente con este plan, antes de renderizarlo (la mitad de la transición, o el drop). */
    private fun plannedSwitchMs(spec: DjBridgeSpec): Long =
        spec.preRollStartMs + RenderedDjBridge.audibleSwitchMs(spec, spec.preRollMs, spec.preRollMs + spec.transitionDurationMs)

    /** Si dos planes del mismo par suenan distinto (otro estilo, otros puntos o otra duración): hay que rehacer el puente. */
    private fun materiallyDifferent(a: DjBridgeSpec, b: DjBridgeSpec): Boolean =
        a.style != b.style ||
            abs(a.cueOutStartMs - b.cueOutStartMs) > 120L ||
            (a.style == DjBridgeStyle.OutroTail && a.cueOutEndMs != b.cueOutEndMs) ||
            abs(a.entryInMs - b.entryInMs) > 120L ||
            abs(a.transitionDurationMs - b.transitionDurationMs) > 250L ||
            abs(a.tempoMatchRatio - b.tempoMatchRatio) > DjTempoMatch.STRETCH_EPS ||
            a.beatAligned != b.beatAligned ||
            a.kickShiftRangeMs != b.kickShiftRangeMs

    private fun cleanBridgeDir() {
        runCatching {
            bridgeCacheDir?.listFiles()?.forEach { f ->
                if (f.name.endsWith(".wav") || f.name.endsWith(".tmp")) f.delete()
            }
        }
    }

    private fun clearBridge(onlyIf: PreparedBridge? = null) {
        if (onlyIf == null) {
            bridgeRenderJob?.cancel()
            bridgeRenderJob = null
            bridgeRenderKey = null
            preparedBridgeFailedFor = null
            bridgeRetryAtNs = 0L
            val prev = preparedBridge
            preparedBridge = null
            if (prev != null && prev !== preparedBridgeWav) {
                runCatching { prev.wavFile.delete() }
            }
        } else {
            if (preparedBridge === onlyIf) preparedBridge = null
            if (preparedBridgeWav !== onlyIf) {
                runCatching { onlyIf.wavFile.delete() }
            }
        }
    }

    /** Empieza a vigilar el final de cada canción. */
    fun start() {
        if (job == null) player.addListener(seekListener)
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
        job = null
        player.removeListener(seekListener)
        clearBridge()
        cleanBridgeDir()
        setMixing(false)
        resetProcessors()
        tail?.release()
        tail = null
    }

    /** Un vistazo: devuelve cuánto esperar hasta el siguiente. */
    private suspend fun step(): Long {
        if (deckDiscontinued) {
            deckDiscontinued = false
            stopTail()
        }
        val item = player.currentMediaItem
        if (item == null || !player.isPlaying || player.repeatMode == Player.REPEAT_MODE_ONE || !player.hasNextMediaItem()) {
            stopTail()
            if (item == null || !player.hasNextMediaItem()) {
                mixAttempt.watch(null)
                clearBridge()
                preparedAnalysisFor = null
                notifyMixUi(null)
            }
            return IDLE_MS
        }
        val dur = player.duration
        val pos = player.currentPosition
        val me = item.mediaId to player.currentMediaItemIndex
        val nextIndex = player.currentMediaItemIndex + 1
        val nextItem = if (nextIndex < player.mediaItemCount) player.getMediaItemAt(nextIndex) else null
        val pairKey = nextItem?.let { Triple(item.mediaId, me.second, it.mediaId) }
        val activeRenderKey = bridgeRenderKey
        val existingBridgeKey = preparedBridge?.let { Triple(it.outgoingId, it.outgoingKey.second, it.incomingId) }
        if (pairKey == null || (activeRenderKey != null && activeRenderKey != pairKey) ||
            (existingBridgeKey != null && existingBridgeKey != pairKey)
        ) {
            clearBridge()
        }
        if (pairKey == null || (preparedAnalysisFor != null && preparedAnalysisFor != pairKey)) {
            preparedAnalysisFor = null
        }
        if (nextItem == null || pairKey == null || !sequentialMixNext(me.second, player.nextMediaItemIndex)) {
            mixAttempt.watch(null)
            stopTail()
            clearBridge()
            val actualNext = player.nextMediaItemIndex.takeIf { it in 0 until player.mediaItemCount }
                ?.let(player::getMediaItemAt)
            notifyMixUi(actualNext?.let { MixUi(-1L, -1L, null, MixUiState.None, item.mediaId to it.mediaId) }, "no consecutiva")
            return IDLE_MS
        }
        if (mixAttempt.watch(pairKey)) stopTail()
        val uiPair = item.mediaId to nextItem.mediaId
        val noMix = MixUi(-1L, -1L, null, MixUiState.None, uiPair)

        // Duration is often unknown while buffering.
        if (dur == C.TIME_UNSET || dur <= 0L) {
            plannedExit = null
            notifyMixUi(MixUi(-1L, -1L, null, MixUiState.Preparing, uiPair))
            return IDLE_MS
        }
        val adhdPlan = transitionPlan(item, dur)
        val baseFade = adhdPlan?.fadeDurationMs ?: length()
        val mixPlan = if (baseFade > 0L) djMixPlan(item, nextItem, dur, baseFade) else null
        val fade = mixPlan?.fadeDurationMs ?: baseFade
        val outroTail = mixPlan?.bridgeSpec?.style == DjBridgeStyle.OutroTail
        val transitionAt = adhdPlan?.transitionAtMs ?: dur
        // Sin mezcla en DJ rápido (p. ej. el plan no halla salida que respete un estribillo): la salida sigue siendo el
        // fin del trozo, no el final de la canción. Antes la salida forzada esperaba al final (medido el 17/11: «Faint»
        // sonó 28 s de más y acabó en salto seco).
        plannedExit = me to (if (fade <= 0L) (if (adhdPlan != null) transitionAt.coerceIn(0L, dur) else dur) else
            (mixPlan?.outgoingFadeStartMs ?: (transitionAt - fade).coerceAtLeast(0L)) + fade)
        // Las muy cortas (o sin duración aún) no se funden: se comería media canción.
        // (Con [baseFade]: el plan del DJ puede alargar el fundido sobre una cola instrumental, y no por eso se salta.)
        if (fade <= 0 || transitionAt <= 0L ||
            (!outroTail && (transitionAt < baseFade * 2 || (adhdPlan == null && dur < baseFade * 3)))) {
            stopTail()
            clearBridge()
            // «Ninguna» para esta pareja (no null): null es «aún sin mirar» y la cola pondría «Preparando».
            notifyMixUi(noMix,
                "fade=$fade dur=$dur transitionAt=$transitionAt base=$baseFade plan=${mixPlan != null}" +
                    (mixPlan?.let { ", " + it.decisionDescription(dur, nextItem.mediaMetadata.durationMs) } ?: ""),
                rejectionKey = mixPlan?.reason?.takeIf { fade <= 0L })
            return IDLE_MS
        }
        val rawAt = mixPlan?.outgoingFadeStartMs ?: (transitionAt - fade).coerceAtLeast(0L)
        val at = SongSections.safeExitAt(rawAt, sectionsFor(item.mediaId), dur, fade)
        // Un corte al drop o un bucle vocal puede seguir sonando en el WAV después de agotar su tramo de A.
        // La ventana del fundido en vivo no decide si ese puente cabe: el WAV se valida por sus propios puntos.
        val liveFits = !outroTail && at + fade <= dur
        plannedExit = me to (at + fade).coerceAtMost(dur)
        // A stale WAV loses eligibility immediately, even when loaded or too late to render a replacement.
        val invalidBridge = (listOfNotNull(preparedBridge, preparedBridgeWav)).firstOrNull {
            !it.spec.fitsSource(dur, sectionsFor(it.outgoingId), sectionsFor(it.incomingId))
        }
        if (invalidBridge != null) {
            if (preparedBridgeWav === invalidBridge) stopTail()
            clearBridge(onlyIf = invalidBridge)
            bridgeRenderJob?.cancel()
            bridgeRenderJob = null
            MixLog.i("puente descartado: los puntos ya no respetan la duración o el estribillo")
        }

        val bridgeSpec = mixPlan?.bridgeSpec?.takeIf {
            it.fitsSource(dur, sectionsFor(item.mediaId), sectionsFor(nextItem.mediaId))
        }
        logPlanChange(pairKey, mixPlan, pos)
        val smart = smartMix()
        if (!smart) clearBridge()
        if (preparedFor != null && preparedFor != me) stopTail()
        val readyBridge = preparedBridge?.takeIf {
            smart && it.outgoingKey == me && it.incomingId == nextItem.mediaId && safeBridge(it) &&
                (it === preparedBridgeWav || bridgeOffsetMs(pos, it.spec.preRollStartMs, it.renderedPreRollMs) != null)
        }
        val switchToBridge = readyBridge != null && pos <= readyBridge.spec.preRollStartMs - BRIDGE_SWITCH_MARGIN_MS
        if (preparedFor == me && preparedBridgeWav == null && (preparedAtMs != at || switchToBridge)) {
            stopTail()
        }
        val bridgeSupported = smart && sliceDecoder != null && bridgeCacheDir != null
        val canStartBridge = bridgeSupported && bridgeSpec != null && bridgeSpec.preRollStartMs - pos >= BRIDGE_MIN_PREPARE_LEFT_MS
        val liveFrom = if (liveFits) liveMixPreparationMs(
            positionMs = pos,
            fadeStartMs = at,
            fadeEndMs = min(dur - OUT_MARGIN_MS, at + fade),
            deckStartMs = preparedFromMs.takeIf { preparedFor == me && preparedBridgeWav == null },
            startLeadMs = lead,
            // The silent deck has no reliable pending value until it starts, but shares the session's audio route.
            pendingMs = AudioLevels.pendingMs(),
        ) else null
        val wasSkipped = mixAttempt.skipped || preparedBridgeFailedFor == pairKey
        if (mixAttempt.rearm(liveFrom != null || readyBridge != null || canStartBridge)) {
            preparedBridgeFailedFor = null
            bridgeRetryAtNs = 0L
            if (wasSkipped) MixLog.i("pareja ${uiPair.first}→${uiPair.second}: rearmada tras retroceder (pos=$pos at=$at)")
        }
        if (mixAttempt.skipped) {
            stopTail()
            notifyMixUi(noMix, "intento descartado; pos=$pos at=$at fade=$fade")
            return IDLE_MS
        }
        if (bridgeSupported && bridgeSpec != null) {
            val leftToPreRoll = bridgeSpec.preRollStartMs - pos
            if (leftToPreRoll <= BRIDGE_ANALYSIS_AHEAD_MS && preparedAnalysisFor != pairKey) {
                preparedAnalysisFor = pairKey
                onPrepareIncomingAnalysis(nextItem.mediaId, cacheKeyFor(nextItem))
            }
            val preparedForPair = preparedBridge?.takeIf {
                Triple(it.outgoingId, it.outgoingKey.second, it.incomingId) == pairKey
            }
            // Si el plan cambió desde que se hizo (análisis completo, letra que llegó, trozo del DJ rápido recolocado),
            // se rehace mientras haya tiempo; el anterior sigue valiendo hasta que esté el nuevo.
            val stale = preparedForPair != null && preparedBridgeWav !== preparedForPair && preparedFor != me &&
                min(leftToPreRoll, preparedForPair.spec.preRollStartMs - pos) >= BRIDGE_RERENDER_MIN_LEFT_MS &&
                // A safe render can fall back to 1× or omit phase correction. Only a new plan makes it stale.
                materiallyDifferent(preparedForPair.plannedSpec, bridgeSpec)
            // Esperamos a tener el análisis entero de ambas antes de renderizar pronto; si no llega, a 14 s del pre-roll
            // renderizamos con lo que haya para no quedarnos sin puente.
            val analysed = mixPlan.incomingAudio?.coversWholeSong == true && mixPlan.outgoingAudio?.coversWholeSong == true
            val inWindow = leftToPreRoll >= BRIDGE_MIN_PREPARE_LEFT_MS && (
                analysed ||
                    (leftToPreRoll <= BRIDGE_PREPARE_AHEAD_MS && (mixPlan.incomingAudio?.coversWholeSong == true || leftToPreRoll <= BRIDGE_FORCE_PREPARE_AHEAD_MS))
                )
            // Un puente que no acaba (decodificación colgada) dejaba «Preparando la mezcla» para siempre.
            if (bridgeRenderJob?.isActive == true && System.nanoTime() - bridgeRenderStartedNs > BRIDGE_RENDER_TIMEOUT_MS * 1_000_000L) {
                MixLog.i("puente: lleva más de ${BRIDGE_RENDER_TIMEOUT_MS / 1000} s preparándose, se cancela")
                bridgeRenderJob?.cancel()
                bridgeRetryAtNs = System.nanoTime() + BRIDGE_RETRY_MS * 1_000_000L
            }
            val shouldStartRender = (preparedForPair == null || stale) &&
                bridgeRenderJob?.isActive != true &&
                pairKey != preparedBridgeFailedFor &&
                System.nanoTime() >= bridgeRetryAtNs &&
                inWindow
            if (shouldStartRender) {
                if (stale) MixLog.i("puente: el plan cambió (${preparedForPair?.plannedSpec?.style?.id} → ${bridgeSpec.style.id}), se rehace")
                launchBridgeRender(
                    pairKey = pairKey,
                    outgoingKey = me,
                    outgoing = item,
                    incoming = nextItem,
                    outgoingDurMs = dur,
                    spec = bridgeSpec,
                )
            }
        }

        val canPrepareBridge = bridgeSupported && bridgeSpec != null &&
            (canStartBridge || bridgeRenderJob?.isActive == true) && pairKey != preparedBridgeFailedFor &&
            bridgeOffsetMs(pos, bridgeSpec.preRollStartMs, bridgeSpec.preRollMs) != null
        val loadedBridge = preparedBridgeWav?.takeIf { preparedFor == me }
        fun PreparedBridge.asUi() = MixUi(spec.cueOutStartMs, spec.preRollStartMs + switchWavMs,
            spec.style, MixUiState.Ready, uiPair)
        // handOver receives this step's mixPlan too: analysis/lyrics may have changed it since deck preparation.
        val liveUi = liveMixUi(uiPair, at, fade, mixPlan, smart)
        val pendingUi = bridgeSpec?.let {
            MixUi(it.cueOutStartMs, plannedSwitchMs(it), it.style, MixUiState.Preparing, uiPair)
        } ?: MixUi(-1L, -1L, null, MixUiState.Preparing, uiPair)
        val notice = selectedMixUi(
            live = liveUi,
            pending = pendingUi,
            ready = readyBridge?.asUi(),
            committedBridge = loadedBridge?.asUi(),
            committedLive = preparedFor == me && loadedBridge == null,
            canPrepare = canPrepareBridge,
            provisional = provisionalLivePlan(mixPlan, smart),
            canLive = liveFrom != null,
        )
        if (notice.state == MixUiState.None) {
            stopTail()
            if (outroTail) {
                notifyMixUi(notice, "enlace al final sin puente preparado: pos=$pos dur=$dur",
                    rejectionKey = "enlace al final sin puente preparado")
            } else if (liveFits) {
                notifyMixUi(notice,
                    "sin margen de preparación: pos=$pos at=$at fade=$fade dur=$dur preRoll=${bridgeSpec?.preRollStartMs}")
            } else {
                notifyMixUi(notice,
                    "salida segura tarde: raw=$rawAt at=$at fade=$fade dur=$dur plan=${mixPlan?.bridgeSpec?.style?.id ?: mixPlan?.style?.name}",
                    rejectionKey = "salida segura tarde")
            }
            return IDLE_MS
        }
        notifyMixUi(notice, "pos=$pos at=$at plan=${mixPlan != null} bridgeable=$canPrepareBridge")

        if (preparedFor == me && loadedBridge == null && liveFrom == null) stopTail()
        val usingBridge = if (preparedFor == me) loadedBridge else readyBridge
        usingBridge?.let { plannedExit = me to it.spec.cueOutEndMs }
        var bridgeWavSeekMs = 0L
        val targetFrom = if (preparedFor == me) {
            bridgeWavSeekMs = usingBridge?.let { (preparedFromMs - it.spec.preRollStartMs).coerceAtLeast(0L) } ?: 0L
            preparedFromMs
        } else if (usingBridge != null) {
            bridgeWavSeekMs = bridgeOffsetMs(pos, usingBridge.spec.preRollStartMs, usingBridge.renderedPreRollMs)
                ?: return FRAME_MS
            usingBridge.spec.preRollStartMs + bridgeWavSeekMs
        } else liveFrom ?: return IDLE_MS
        val activeBridge = usingBridge
        val left = targetFrom - pos
        if (left > PREPARE_MS) return (left - PREPARE_MS).coerceIn(50, IDLE_MS)

        val t = tail ?: build().also { tail = it }
        if (preparedFor == null) {
            // En modo normal, si la siguiente aún no ha empezado a bajar cuando ya estamos a punto de preparar,
            // esperamos un poco antes de renunciar; solo saltamos si justo al relevar sigue sin colchón.
            if (activeBridge == null && adhdPlan == null && (mixPlan == null || mixPlan.incomingStartMs == 0L)) {
                val nextBuffered = player.totalBufferedDuration - (dur - pos)
                if (nextBuffered < MIN_PREPARE_READY_MS && left > 400L) {
                    return 200L
                }
            }
            preparedFor = me
            preparedBridgeWav = activeBridge
            preparedFromMs = targetFrom
            preparedAtMs = activeBridge?.spec?.cueOutStartMs ?: at
            resetProcessors()
            t.setPlaybackSpeed(1f)
            if (activeBridge != null) {
                t.setMediaSource(progressiveFactory.createMediaSource(MediaItem.fromUri(Uri.fromFile(activeBridge.wavFile))), bridgeWavSeekMs)
            } else {
                loop.startHintUs = targetFrom * 1000L
                t.setMediaItem(item, targetFrom)
            }
            t.volume = 0f
            tailGate.set(open = false)
            t.playWhenReady = false
            t.prepare()
            return FRAME_MS
        }
        if (left > lead) return FRAME_MS.coerceAtMost(left - lead).coerceAtLeast(1)
        if (t.playbackState != Player.STATE_READY) {
            // Si aún está preparando con margen antes de pasar de largo, esperamos un poco en vez de rendirnos en 16 ms.
            val canStillFade = pos < preparedAtMs - MIN_PRE_HANDOVER_MS ||
                (activeBridge == null && pos + MIN_VIABLE_FADE_MS < at + fade)
            if (t.playbackState == Player.STATE_BUFFERING && pos < targetFrom + PREPARE_READY_WAIT_MS && canStillFade) {
                return FRAME_MS
            }
            if (activeBridge != null) {
                Timber.d("puente: WAV no listo a tiempo, fallback en vivo"); MixLog.i("puente: WAV no listo, fallback en vivo")
                clearBridge(onlyIf = activeBridge)
                stopTail()
                return FRAME_MS
            }
            Timber.d("fundido: la que se va no estaba lista"); MixLog.i("sin fundido: la que se va no estaba lista")
            stopTail()
            mixAttempt.skip()
            return FRAME_MS
        }
        if (activeBridge != null) {
            handOverBridge(t, activeBridge, bridgeWavSeekMs)
            return IDLE_MS
        }
        if (adhdPlan == null && (mixPlan == null || mixPlan.incomingStartMs == 0L) && player.totalBufferedDuration - (dur - pos) < NEXT_READY_MS) {
            Timber.d("fundido: la siguiente no está lista, sin fundido"); MixLog.i("sin fundido: la siguiente no está lista")
            stopTail()
            mixAttempt.skip()
            return FRAME_MS
        }
        handOver(t, uiPair, fade, mixPlan)
        return FRAME_MS
    }

    private suspend fun handOver(t: ExoPlayer, pair: Pair<String, String>, requestedFade: Long, plan: DjTransitionPlan?) {
        val outGain = gain()
        val index = player.currentMediaItemIndex
        var interrupted = false
        var ownSeek = false
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !ownSeek) interrupted = true
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) interrupted = true
            }
        }
        fun seekSession(atIndex: Int, positionMs: Long) {
            ownSeek = true
            try { player.seekTo(atIndex, positionMs) } finally { ownSeek = false }
        }
        fun sessionCancelled(expectedIndex: Int, expectedId: String) =
            interrupted || !player.playWhenReady || player.currentMediaItemIndex != expectedIndex ||
                player.currentMediaItem?.mediaId != expectedId || t.playerError != null
        fun cancelled() = sessionCancelled(index, pair.first) || !player.isPlaying
        handingOver = true
        player.addListener(listener)
        try {
            t.setPlaybackSpeed(1f)
            t.volume = 0f
            // El bucle se arma antes de que [tail] arranque: su audio aún no ha pasado por el procesador.
            val loopPlan = plan?.takeIf {
                it.loopBeats > 0 && it.loopBeatMs > 0.0 && smartMix() &&
                    it.style != DjTransitionStyle.LyricLoopCut && it.style != DjTransitionStyle.EnergyDropCut
            }
            if (loopPlan != null) loop.arm(loopPlan.loopStartMs.toDouble(), loopPlan.loopBeats * loopPlan.loopBeatMs, loopPlan.loopBeatMs)
            val pPosAtPlay = player.currentPosition
            val tPosAtPlay = t.currentPosition
            t.play()
            // Sonando callado hasta que su posición es la de verdad. Pausar o pasar de canción mientras, lo cancela.
            val asked = System.nanoTime()
            while (System.nanoTime() - asked < SETTLE_MS * 1_000_000L) {
                if (cancelled()) { stopTail(); return }
                delay(FRAME_MS)
            }
            // Medimos el retardo real de arranque por deltas para que un late-prepare no corrompa [lead], y
            // convergemos por EMA hacia ese retardo real (no sumándolo encima de [lead], que lo hacía divergir).
            val playerMoved = player.currentPosition - pPosAtPlay
            val tailMoved = t.currentPosition - tPosAtPlay
            lead = nextLeadMs(lead, playerMoved, tailMoved)

            var drift = player.currentPosition - t.currentPosition
            if (abs(drift) > COARSE_SEEK_THRESHOLD_MS) {
                val reseek = (player.currentPosition + lead).coerceAtLeast(0L)
                loop.startHintUs = reseek * 1000L
                t.seekTo(reseek)
                val reSettleStart = System.nanoTime()
                while (System.nanoTime() - reSettleStart < SETTLE_MS * 1_000_000L) {
                    if (cancelled()) { stopTail(); return }
                    delay(FRAME_MS)
                }
                drift = player.currentPosition - t.currentPosition
            }
            // La alineación inicial tampoco puede gastar el tiempo de la puerta. Reservamos el mayor búfer
            // admitido: el pendiente real aún puede cambiar mientras se asienta el AudioTrack.
            val initialGateAtMs = preparedAtMs - companionLiveGateLeadMs(1_500L)
            val initialBudget = companionLiveRealignBudgetMs(initialGateAtMs - player.currentPosition)
                ?.coerceAtMost(ALIGN_BUDGET_MS)
            val aligned = if (initialBudget != null) {
                align(t, initialBudget, ::cancelled) ?: return
            } else {
                steadyError({ player.currentPosition - t.currentPosition }, ::cancelled) ?: run { stopTail(); return }
            }
            if (cancelled()) return
            // Sin cuadrar del todo pero cerca: relevo más largo a igual potencia en vez de quedarse sin fundido (corte seco).
            if (abs(aligned) > LIVE_SOFT_ACCEPT_MS) {
                MixLog.i("sin fundido: no se alineó ($drift → $aligned ms)")
                mixAttempt.skip()
                stopTail()
                return
            }
            val outDurationMs = player.duration
            val outLeftBeforeSwap = outDurationMs.takeIf { it != C.TIME_UNSET }?.let { it - t.currentPosition - OUT_MARGIN_MS }
            if (outLeftBeforeSwap != null && outLeftBeforeSwap < MIN_VIABLE_FADE_MS) {
                MixLog.i("sin fundido: quedó poco margen tras alinear (${outLeftBeforeSwap} ms)")
                mixAttempt.skip()
                stopTail()
                return
            }

            Timber.d("fundido: desfase %d ms → %d ms, adelanto %d ms", drift, aligned, lead)
            MixLog.i("relevo: desfase ${drift} → ${aligned} ms, relevo ${swapMsFor(aligned)} ms, adelanto $lead ms, pos ${player.currentPosition}")

            // El reloj de la copia recién arrancada aún se asienta: se comprueba otra vez antes del cambio y, si se ha
            // movido y da tiempo, se reajusta (sin retrasar el cambio planeado).
            var recheckedLive = false
            // La puerta se drena ANTES del cambio: si no, el relevo llegaba ~0,6 s tarde respecto al compás planeado.
            val gateLeadMs = companionLiveGateLeadMs(tailSink?.pendingMs())
            val gateAtMs = preparedAtMs - gateLeadMs
            if (player.currentPosition > gateAtMs + FRAME_MS) {
                MixLog.i("sin fundido: preparación tardía para la puerta (${player.currentPosition - gateAtMs} ms)")
                mixAttempt.skip()
                stopTail()
                return
            }
            while (player.currentPosition < gateAtMs) {
                if (cancelled() || SongSections.overlapsChorus(preparedAtMs, preparedAtMs + requestedFade,
                        sectionsFor(player.currentMediaItem?.mediaId.orEmpty()))) { stopTail(); return }
                if (!recheckedLive && gateAtMs - player.currentPosition <= LIVE_RECHECK_LEAD_MS) {
                    recheckedLive = true
                    val now = steadyError({ player.currentPosition - t.currentPosition }, ::cancelled) ?: run { stopTail(); return }
                    // Tras medir: lo que queda menos el asentado y la medida final de [align], que van fuera de su plazo.
                    val budget = companionLiveRealignBudgetMs(gateAtMs - player.currentPosition)
                    if (abs(now) > ACCEPT_MS && budget != null) {
                        val again = align(t, budget, ::cancelled) ?: return
                        MixLog.i("relevo: antes del cambio iba a $now ms, reajustado a $again ms (quedan ${gateAtMs - player.currentPosition} ms hasta la puerta)")
                    }
                }
                delay(FRAME_MS)
            }
            if (SongSections.overlapsChorus(player.currentPosition, player.currentPosition + requestedFade,
                    sectionsFor(player.currentMediaItem?.mediaId.orEmpty()))) { stopTail(); return }
            if (!openGateAhead(
                    tailGate, t,
                    tailSink?.pendingMs(),
                    { outDurationMs == C.TIME_UNSET || outDurationMs - t.currentPosition - OUT_MARGIN_MS > MIN_VIABLE_FADE_MS + 400L },
                    ::cancelled,
                )
            ) {
                stopTail()
                return
            }
            // Entre el ajuste y aquí pasan la espera y el drenaje de la puerta: el reloj recién arrancado se mueve.
            // Se mide de nuevo (mediana recortada) y el relevo se dimensiona con lo que hay ahora, no con lo de antes.
            fun liveSwapCancelled() = cancelled() || !t.isPlaying
            val timedSwap = BridgeHandoff.awaitLiveSwap(
                plannedEndMs = preparedAtMs, positionMs = { player.currentPosition },
                ready = { !liveSwapCancelled() }, cancelled = ::cancelled,
                measureError = { steadyError({ player.currentPosition - t.currentPosition }, ::liveSwapCancelled) },
                durationMs = ::swapMsFor, maxDriftMs = LIVE_SOFT_ACCEPT_MS,
                measureMs = ALIGN_MEASURE_MS, toleranceMs = FRAME_MS,
                nowMs = { System.nanoTime() / 1_000_000L }, waitFrame = { delay(FRAME_MS) },
            )
            if (timedSwap == null) {
                if (cancelled()) return
                MixLog.i("sin fundido: sin margen o sincronía para el relevo en su instante")
                mixAttempt.skip()
                stopTail()
                return
            }
            val atSwap = timedSwap.errorMs
            if (atSwap != aligned) MixLog.i("relevo: al relevar err=$atSwap ms (relevo ${swapMsFor(atSwap)} ms)")
            // Relevo rápido equal-power (24 ms): suficiente para evitar clic al cambiar volúmenes y lo bastante corto
            // para no producir filtro peine/hueco en graves entre dos copias casi idénticas.
            setMixing(true)
            val swap = System.nanoTime()
            val swapMs = timedSwap.durationMs
            MixLog.i("relevo en vivo: inicio pos=${player.currentPosition}, fin previsto=$preparedAtMs, swap=${swapMs}ms, err=${atSwap}ms")
            while (true) {
                val q = ((System.nanoTime() - swap) / 1e6 / swapMs).toFloat().coerceIn(0f, 1f)
                if (cancelled()) {
                    onFadeIn(1f)
                    stopTail()
                    return
                }
                val angle = q * (PI.toFloat() / 2f)
                t.volume = outGain * sin(angle)
                onFadeIn(cos(angle))
                if (q >= 1f) break
                delay(2)
            }
            t.volume = outGain
            onFadeIn(0f)
            MixLog.i("relevo en vivo: fin pos=${player.currentPosition}, error temporal=${player.currentPosition - preparedAtMs}ms")

            val nextIndex = player.currentMediaItemIndex + 1
            val currentItem = player.currentMediaItem
            val nextItem = if (nextIndex < player.mediaItemCount) player.getMediaItemAt(nextIndex) else null
            if (cancelled() || nextItem?.mediaId != pair.second ||
                !sequentialMixNext(index, player.nextMediaItemIndex)) return
            val smart = smartMix()
            val nextStartMs = (plan?.incomingStartMs ?: nextItem?.let(nextStartPosition) ?: 0L).coerceAtLeast(0L)
            // Sin mezcla inteligente, fundido clásico aunque haya plan del DJ.
            val style = if (!smart) DjTransitionStyle.SmoothEqualPower else plan?.style
            val matched = style == DjTransitionStyle.BeatMatchLongMix
            val smoothAtTempo = style == DjTransitionStyle.SmoothEqualPower && smart && plan?.phaseLock == true
            // La entrante va siempre a 1×; la que se va se pone al tempo de la entrante (y ya no vuelve: se apaga).
            if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
            // También el filtro con eco a tempo: el plan solo trae velocidad ≠ 1 si hay bombo en las dos y cabe en el límite.
            val tempoMatched = matched || smoothAtTempo ||
                (style == DjTransitionStyle.EchoOutFilterSweep && plan?.outgoingSpeed?.let { it != 1.0 } == true)
            val tailSpeed = if (tempoMatched) {
                plan?.outgoingSpeed?.coerceIn(1.0 / DjTempoMatch.MAX_RATIO, 1.0 / DjTempoMatch.MIN_RATIO)?.toFloat() ?: 1f
            } else 1f
            val rampStart = System.nanoTime()
            var tailApplied = 1f
            var tailStepAt = 0L
            fun rampTail(now: Long) {
                if (tailSpeed == 1f || tailApplied == tailSpeed) return
                val q = ((now - rampStart) / 1e6 / TAIL_RAMP_MS).toFloat().coerceIn(0f, 1f)
                val v = if (q >= 1f) tailSpeed else 1f + (tailSpeed - 1f) * q
                if (q >= 1f || (abs(v - tailApplied) >= MIN_SPEED_STEP && now - tailStepAt >= TEMPO_STEP_INTERVAL_MS * 1_000_000L)) {
                    t.setPlaybackSpeed(v)
                    tailApplied = v
                    tailStepAt = now
                }
            }

            seekSession(nextIndex, nextStartMs.takeIf { it > 0L } ?: C.TIME_UNSET)
            val next = nextIndex
            preparedFor = null

            // Esperar a que [player] esté realmente reproduciendo (tras buscar nextStartMs o llenar colchón) manteniendo
            // [tail] al volumen de salida para que nunca haya un corte de silencio entre pistas.
            val reserveFade = requestedFade.coerceAtMost(MIN_FADE_MS)
            val waitStart = System.nanoTime()
            fun tailLeftMs(): Long? = outDurationMs.takeIf { it != C.TIME_UNSET }
                ?.let { ((it - t.currentPosition - OUT_MARGIN_MS) / t.playbackParameters.speed).toLong() }
            while (!sessionCancelled(next, pair.second) &&
                (player.playbackState != Player.STATE_READY || !player.isPlaying)
            ) {
                rampTail(System.nanoTime())
                val leftNow = tailLeftMs()
                if (leftNow != null && leftNow <= reserveFade) break
                if (t.playbackState == Player.STATE_ENDED) break
                if ((System.nanoTime() - waitStart) / 1_000_000L >= MAX_INCOMING_WAIT_MS) break
                delay(FRAME_MS)
            }

            if (sessionCancelled(next, pair.second)) {
                return
            }

            val targetFade = plan?.fadeDurationMs?.takeIf { it > 0L } ?: requestedFade
            var deferredForChorus = false
            while (!sessionCancelled(next, pair.second) && t.playbackState != Player.STATE_ENDED) {
                val sourceNow = t.currentPosition
                val spans = sectionsFor(currentItem?.mediaId.orEmpty())
                val maxSourceSpeed = maxOf(t.playbackParameters.speed, tailSpeed, 1f).toDouble()
                if (ChorusSafety.allowsLive(sourceNow, targetFade, maxSourceSpeed, spans)) {
                    if (!deferredForChorus) break
                    // Restore B's entry after holding A, then recheck after B's seek/buffer delay.
                    seekSession(next, nextStartMs)
                    val reseekAt = System.nanoTime()
                    while (!sessionCancelled(next, pair.second) &&
                        (!player.isPlaying || player.playbackState != Player.STATE_READY) &&
                        System.nanoTime() - reseekAt < MAX_INCOMING_WAIT_MS * 1_000_000L) delay(FRAME_MS)
                    deferredForChorus = false
                    continue
                }
                if (!deferredForChorus) {
                    MixLog.i("fundido aplazado: esperar al estribillo completo de A")
                    deferredForChorus = true
                    loop.disarm()
                }
                t.volume = outGain
                onFadeIn(0f)
                delay(FRAME_MS)
            }
            if (sessionCancelled(next, pair.second)) return
            if (deferredForChorus) seekSession(next, nextStartMs)
            val outLeft = tailLeftMs()
            // Nunca alargar el fundido por encima del audio real que le queda a [tail]; si no, [tail] se corta en seco.
            val length = if (outLeft != null) targetFade.coerceAtMost(outLeft.coerceAtLeast(MIN_ABORT_FADE_MS)) else targetFade.coerceAtLeast(MIN_ABORT_FADE_MS)
            val gainMatch = plan?.incomingGainMatch?.coerceIn(0.65f, 1.45f) ?: 1f
            val echoProc = outgoingEchoProcessor
            val rolled = style == DjTransitionStyle.LoopRollOut
            val filtered = (style == DjTransitionStyle.EchoOutFilterSweep || rolled) && plan?.useHighPassSweep == true && echoProc != null
            val echoWet = if (filtered && !rolled) plan?.echoOutWet ?: 0f else 0f
            // Sin mezcla inteligente o sin bucle en el plan, fuera el bucle.
            val looped = plan?.loopBeats?.let { it > 0 } == true && style != DjTransitionStyle.SmoothEqualPower && loop.isArmed
            if (!looped) loop.disarm()
            var loopLogged = false
            var division = 1
            if (echoProc != null && filtered && echoWet > 0f) {
                echoProc.configureEffect(
                    delayMs = plan!!.echoDelayMs.toInt().coerceIn(80, 1000),
                    feedback = plan.echoFeedback,
                )
            }
            val swapAt = plan?.bassSwapAt?.coerceIn(0.2f, 0.8f) ?: 0.5f
            // El relevo de graves dura un beat (o lo que serían 500 ms sin pulso conocido).
            val beatMs = plan?.incomingAudio?.beatPeriodMs ?: 500.0
            val swapWidth = (beatMs / length).toFloat().coerceIn(0.03f, 0.12f)
            val inAudio = plan?.incomingAudio
            val outAudio = plan?.outgoingAudio
            // Eco alineado a 1× (mismo tempo, mitad/doble) también: la fase se corrige aunque no cambie la velocidad.
            val phaseLock = smart &&
                (DjTempoMatch.livePhaseLock(plan) || (smoothAtTempo && outAudio != null && inAudio != null))

            val start = System.nanoTime()
            val waitedMs = (start - waitStart) / 1_000_000L
            MixLog.i(
                "salto a ${nextItem?.mediaId} en $nextStartMs ms (espera ${waitedMs}ms), fundido $length ms, " +
                    "${style ?: if (smart) "inteligente" else "clásica"} (${plan?.reason ?: "sin plan"}), " +
                    "BPM ${outAudio?.bpm ?: "?"}/${"%.2f".format(outAudio?.bpmConfidence ?: 0.0)} → " +
                    "${inAudio?.bpm ?: "?"}/${"%.2f".format(inAudio?.bpmConfidence ?: 0.0)}, saliente a ${"%.3f".format(tailSpeed)}×",
            )
            var heardIn = false
            var p = 0f
            var inSpeed = 1f
            var lastPhaseAt = 0L
            var phaseLogs = 0
            var locked = false
            while (!sessionCancelled(next, pair.second)) {
                val now = System.nanoTime()
                if (!heardIn && player.isPlaying) {
                    heardIn = true
                    MixLog.i("entra sonando a los ${(now - start) / 1_000_000} ms (pos ${player.currentPosition})")
                }
                rampTail(now)
                p = ((now - start) / 1e6 / length).toFloat().coerceIn(0f, 1f)
                val matchEnvelope = if (gainMatch != 1f) {
                    val settleT = SmartMixMath.smoothStep(((p - 0.55f) / 0.45f).coerceIn(0f, 1f))
                    gainMatch + (1f - gainMatch) * settleT
                } else 1f

                when {
                    style == null || style == DjTransitionStyle.SmoothEqualPower -> {
                        // Sin plan del DJ (listas normales) o mezcla inteligente apagada: curvas de siempre.
                        t.volume = outGain * SmartMixMath.outgoingGain(p, smart)
                        onFadeIn((SmartMixMath.incomingGain(p, smart) * matchEnvelope).coerceIn(0f, 1.35f))
                        outgoingProcessor?.cutAmount = SmartMixMath.outgoingBassCut(p, smart)
                        incomingProcessor?.cutAmount = SmartMixMath.incomingBassCut(p, smart)
                    }
                    else -> {
                        if (rolled) {
                            t.volume = outGain * SmartMixMath.loopRollOutgoingGain(p)
                            onFadeIn((SmartMixMath.loopRollIncomingGain(p) * matchEnvelope).coerceIn(0f, 1.35f))
                        } else {
                            t.volume = outGain * SmartMixMath.djOutgoingGain(p, swapAt, echoWet > 0f)
                            onFadeIn((SmartMixMath.djIncomingGain(p, swapAt, matched) * matchEnvelope).coerceIn(0f, 1.35f))
                        }
                        incomingProcessor?.cutAmount = SmartMixMath.djBassCut(p, swapAt, swapWidth, incoming = true)
                        outgoingProcessor?.cutAmount = SmartMixMath.djBassCut(p, swapAt, swapWidth, incoming = false)
                        if (filtered && echoProc != null && plan != null) {
                            val (dry, wet, feed) = SmartMixMath.djEcho(p, echoWet)
                            echoProc.updateSweep(
                                cutoffHz = SmartMixMath.djHighPassHz(p, plan.highPassStartHz, plan.highPassEndHz),
                                dry = dry,
                                wet = wet,
                                feed = feed,
                            )
                        }
                    }
                }

                if (looped) {
                    val d = SmartMixMath.loopDivision(p, rolled, plan!!.loopBeats)
                    if (d != division) {
                        division = d
                        loop.division = d
                        MixLog.i("bucle: ${plan.loopBeats / d.toFloat()} beats a p=${"%.2f".format(p)}")
                    }
                    if (!loopLogged && loop.loopStartedUs >= 0L) {
                        loopLogged = true
                        MixLog.i(
                            "bucle: empieza en ${loop.loopStartedUs / 1000} ms (pedido ${plan.loopStartMs}), " +
                                "${plan.loopBeats} beats = ${loop.loopLenUs / 1000} ms",
                        )
                    }
                }

                // Fase: con los tempos ya iguales, se corrige la entrante (aún baja) acelerándola o frenándola un poco
                // hasta que sus beats caen sobre los de la que se va. Antes del relevo de graves; después, a 1×.
                if (phaseLock && heardIn && now - lastPhaseAt >= PHASE_STEP_MS * 1_000_000L) {
                    lastPhaseAt = now
                    if (p < swapAt - swapWidth && !locked) {
                        val outOff = outAudio!!.beatOffset(loop.audiblePositionMs(t.currentPosition).toLong())
                        val inOff = inAudio!!.beatOffset(player.currentPosition)
                        if (outOff != null && inOff != null) {
                            // En ms de reproducción (la saliente va a `tailSpeed`, la entrante a `inSpeed`), sobre el
                            // periodo más corto: vale también a mitad/doble tempo.
                            val errMs = DjTempoMatch.phaseErrorMs(
                                outOff, (outAudio.beatPeriodMs ?: 500.0) / tailApplied,
                                inOff, (inAudio.beatPeriodMs ?: 500.0) / inSpeed,
                            )
                            // Lejos, más deprisa (la entrante aún apenas se oye); cerca, con suavidad.
                            val maxNudge = if (abs(errMs) > PHASE_FAR_MS) PHASE_MAX_NUDGE_FAR else PHASE_MAX_NUDGE
                            val v = if (abs(errMs) <= PHASE_OK_MS) 1f
                            else (1.0 - errMs / PHASE_CORRECT_MS).toFloat().coerceIn(1f - maxNudge, 1f + maxNudge)
                            if (abs(v - inSpeed) >= 0.002f || (v == 1f && inSpeed != 1f)) {
                                player.setPlaybackSpeed(v)
                                inSpeed = v
                            }
                            if (v == 1f && abs(errMs) <= PHASE_OK_MS) {
                                locked = true
                                MixLog.i("fase cuadrada: ${"%.0f".format(errMs)} ms a p=${"%.2f".format(p)}")
                            } else if (phaseLogs++ % 4 == 0) {
                                MixLog.i("fase: ${"%.0f".format(errMs)} ms → entrante a ${"%.3f".format(v)}× (p=${"%.2f".format(p)})")
                            }
                        }
                    } else if (inSpeed != 1f) {
                        player.setPlaybackSpeed(1f)
                        inSpeed = 1f
                        if (!locked) MixLog.i("fase sin cuadrar del todo al llegar al relevo")
                    }
                }

                if (p >= 1f || t.playbackState == Player.STATE_ENDED) break
                delay(FRAME_MS)
            }
            val why = when {
                p >= 1f -> "completo"
                t.playbackState == Player.STATE_ENDED -> "fin de pista saliente"
                !player.playWhenReady -> "pausa"
                interrupted -> "búsqueda o pausa"
                else -> "cambio de canción"
            }
            MixLog.i("fin del fundido: $why en p=${"%.2f".format(p)}, la que se va a ${"%.2f".format(t.volume)}")
        } finally {
            player.removeListener(listener)
            handingOver = false
            setMixing(false)
            resetProcessors()
            if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
            onFadeIn(1f)
            stopTail()
        }
    }

    private fun expLerp(start: Float, end: Float, p: Float): Float {
        val lo = start.coerceAtLeast(20f)
        val hi = end.coerceAtLeast(lo)
        return exp(ln(lo) + (ln(hi) - ln(lo)) * p.coerceIn(0f, 1f))
    }

    private fun resetProcessors() {
        incomingProcessor?.cutAmount = 0f
        outgoingProcessor?.cutAmount = 0f
        outgoingEchoProcessor?.resetEffect()
        loop.disarm()
    }

    /**
     * Ajusta la velocidad de [t] (callada) hasta que va a la par de [player]. Devuelve el desfase que queda, o null si
     * se canceló. Evita reconfigurar la velocidad en cada fotograma para que Sonic no deje discontinuidades.
     */
    private suspend fun align(t: ExoPlayer, budgetMs: Long, gone: () -> Boolean): Long? {
        val until = System.nanoTime() + budgetMs * 1_000_000L
        var good = 0
        var drift = player.currentPosition - t.currentPosition
        var anySpeedChange = false
        var lastSpeedUpdateAt = 0L
        repeat(ALIGN_ROUNDS) { round ->
            good = 0
            while (System.nanoTime() < until && good < 3) {
                if (gone()) { t.setPlaybackSpeed(1f); stopTail(); return null }
                drift = player.currentPosition - t.currentPosition
                if (abs(drift) <= ALIGNED_MS) {
                    good++
                } else {
                    good = 0
                    val now = System.nanoTime()
                    val v = 1f + (drift / ALIGN_GAIN_MS).coerceIn(-ALIGN_MAX_NUDGE, ALIGN_MAX_NUDGE)
                    val curSpeed = t.playbackParameters.speed
                    val enoughTime = (now - lastSpeedUpdateAt) >= ALIGN_STEP_MS * 1_000_000L
                    if (abs(drift) > ALIGN_DEADBAND_MS && enoughTime && abs(curSpeed - v) >= MIN_ALIGN_SPEED_DELTA) {
                        t.setPlaybackSpeed(v)
                        anySpeedChange = true
                        lastSpeedUpdateAt = now
                    }
                }
                delay(FRAME_MS)
            }
            val changed = t.playbackParameters.speed != 1f
            if (changed) {
                t.setPlaybackSpeed(1f)
                anySpeedChange = true
            }
            if (changed) delay(SPEED_SETTLE_MS) else if (round == 0) delay(NO_CHANGE_SETTLE_MS)
            if (gone()) { stopTail(); return null }
            // Mediana recortada, como en el relevo: una sola lectura del reloj BT puede ir 10–20 ms desviada.
            drift = steadyError({ player.currentPosition - t.currentPosition }, gone) ?: run { stopTail(); return null }
            if (abs(drift) <= ACCEPT_MS) return drift
            if (System.nanoTime() >= until) return drift
        }
        return drift
    }

    private fun launchBridgeRender(
        pairKey: Triple<String, Int, String>,
        outgoingKey: Pair<String, Int>,
        outgoing: MediaItem,
        incoming: MediaItem,
        outgoingDurMs: Long,
        spec: DjBridgeSpec,
    ) {
        val decoder = sliceDecoder ?: return
        val dir = bridgeCacheDir ?: return
        bridgeRenderJob?.cancel()
        bridgeRenderKey = pairKey
        bridgeRenderStartedNs = System.nanoTime()
        val outCacheKey = cacheKeyFor(outgoing)
        val inCacheKey = cacheKeyFor(incoming)
        val gainA = gainFor(outgoing.mediaId)
        val gainB = gainFor(incoming.mediaId)
        val uiRevision = mixUiRevision
        val inDurHint = incoming.mediaMetadata.durationMs?.takeIf { it > 0L }

        val aFrom = (spec.preRollStartMs - 600L).coerceAtLeast(0L)
        val aTo = (max(spec.cueOutEndMs, spec.cueOutStartMs + spec.transitionDurationMs) + 1_600L).coerceAtMost(outgoingDurMs)
        val minInAnchor = min(spec.entryInMs, spec.lyricLoop?.inPhraseStartMs ?: spec.entryInMs)
        val bFrom = (minInAnchor - 3_200L).coerceAtLeast(0L)
        val maxBTo = max(spec.dryBResumeMs + 3_500L, spec.entryInMs + spec.transitionDurationMs + spec.postRollMs + 4_500L)
        val bTo = inDurHint?.let { min(maxBTo, it) } ?: maxBTo

        bridgeRenderJob = scope.launch {
            val startedNs = System.nanoTime()
            val tmpFile = File(dir, "dj_bridge_${startedNs}.tmp")
            val wavFile = File(dir, "dj_bridge_${startedNs}.wav")
            var published = false
            try {
                val (sliceA, sliceB) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val defA = async { decoder.decodeWindow(outgoing.mediaId, outCacheKey, aFrom, aTo) }
                        val defB = async { decoder.decodeWindow(incoming.mediaId, inCacheKey, bFrom, bTo) }
                        defA.await() to defB.await()
                    }
                }
                if (sliceA == null || sliceB == null) {
                    MixLog.i("puente ${spec.style.id}: tramos insuficientes (A=${sliceA != null}, B=${sliceB != null})")
                    if (spec.preRollStartMs - player.currentPosition < 5_000L) {
                        preparedBridgeFailedFor = pairKey
                    } else {
                        // Aún bajando: se vuelve a probar en unos segundos, no en cada vistazo.
                        bridgeRetryAtNs = System.nanoTime() + BRIDGE_RETRY_MS * 1_000_000L
                    }
                    return@launch
                }
                val (rendered, preRoll12k, syncRefB) = withContext(Dispatchers.Default) {
                    val r = DjBridgeRenderer.renderSliceBridge(
                        spec = spec,
                        sliceA = sliceA.stereoPcm,
                        sliceAOriginMs = sliceA.originMs,
                        sliceB = sliceB.stereoPcm,
                        sliceBOriginMs = sliceB.originMs,
                        gainA = gainA,
                        gainB = gainB,
                    )
                    val pre12 = DjDsp.stereoToMono12k(r.stereoPcm, 0, r.preRollFrames, r.sampleRate)
                    val totalBFrames = sliceB.stereoPcm.size / 2
                    val bAnchorMs = (r.handoffBTrackMs - 3_400L).coerceAtLeast(sliceB.originMs)
                    val syncStartFrame = (((bAnchorMs - sliceB.originMs) / 1000.0) * r.sampleRate)
                        .roundToInt().coerceIn(0, totalBFrames)
                    val syncEndFrame = (((r.endBTrackMs - sliceB.originMs) / 1000.0) * r.sampleRate)
                        .roundToInt().coerceIn(syncStartFrame, totalBFrames)
                    val postFrames = r.totalFrames - r.dryBStartOffsetFrames
                    val syncPair = if (syncEndFrame - syncStartFrame >= postFrames) {
                        val originMs = sliceB.originMs + ((syncStartFrame * 1000.0) / r.sampleRate).roundToLong()
                        DjDsp.stereoToMono12k(sliceB.stereoPcm, syncStartFrame, syncEndFrame - syncStartFrame, r.sampleRate) to originMs
                    } else {
                        DjDsp.stereoToMono12k(r.stereoPcm, r.dryBStartOffsetFrames, postFrames, r.sampleRate) to r.handoffBTrackMs
                    }
                    Triple(r, pre12, syncPair)
                }
                val sliceInfo =
                    "A=${sliceA.mime}@${sliceA.sourceSampleRate}/d${sliceA.encoderDelay}(+${sliceA.originMs - aFrom}ms) " +
                        "B=${sliceB.mime}@${sliceB.sourceSampleRate}/d${sliceB.encoderDelay}(+${sliceB.originMs - bFrom}ms)"
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    rendered.writeWavPcm16(tmpFile)
                    if (!tmpFile.renameTo(wavFile)) {
                        tmpFile.copyTo(wavFile, overwrite = true)
                        tmpFile.delete()
                    }
                }
                if (!isActive || bridgeRenderKey != pairKey || uiRevision != mixUiRevision) {
                    return@launch
                }
                // Si había uno anterior de esta pareja (se rehízo porque cambió el plan): si ya está cargado para sonar
                // o el nuevo llega sin tiempo para su pre-roll, se queda el anterior (cambiarlo ahí dejaba el relevo a
                // 10 s y la mezcla se caía); si no, se tira el anterior.
                if (!ChorusSafety.allows(rendered.spec, sectionsFor(outgoing.mediaId), sectionsFor(incoming.mediaId))) {
                    MixLog.i("puente descartado tras renderizar: estribillo protegido")
                    return@launch
                }
                val old = preparedBridge?.takeIf { it.outgoingKey == outgoingKey && it.incomingId == incoming.mediaId }
                if (old != null && (old === preparedBridgeWav || rendered.spec.preRollStartMs - player.currentPosition < BRIDGE_RERENDER_MIN_LEFT_MS / 2)) {
                    MixLog.i("puente: el nuevo (${rendered.spec.style.id}) llega tarde, sigue el de antes (${old.spec.style.id})")
                    return@launch
                }
                preparedBridge?.let { prev ->
                    if (prev !== preparedBridgeWav) runCatching { prev.wavFile.delete() }
                }
                preparedBridge = PreparedBridge(
                    outgoingKey = outgoingKey,
                    outgoingId = outgoing.mediaId,
                    incomingId = incoming.mediaId,
                    wavFile = wavFile,
                    plannedSpec = spec,
                    spec = rendered.spec,
                    switchWavMs = rendered.audibleSwitchMs,
                    renderedPreRollMs = rendered.preRollMs,
                    dryBStartOffsetMs = rendered.dryBStartOffsetMs,
                    totalMs = rendered.totalMs,
                    handoffBTrackMs = rendered.handoffBTrackMs,
                    endBTrackMs = rendered.endBTrackMs,
                    bakedGainA = gainA,
                    bakedGainB = gainB,
                    preRoll12k = preRoll12k,
                    syncRefB12k = syncRefB.first,
                    syncRefBOriginMs = syncRefB.second,
                    sliceInfo = sliceInfo,
                )
                published = true
                MixLog.i(
                    "puente ${rendered.spec.style.id} listo en ${(System.nanoTime() - startedNs) / 1_000_000} ms " +
                        "(${rendered.totalMs} ms, pre=${rendered.preRollMs} ms, cambio=${rendered.audibleSwitchMs} ms, " +
                        "dryB=${rendered.dryBStartOffsetMs} ms → B=${rendered.handoffBTrackMs}..${rendered.endBTrackMs} ms, " +
                        "fase B=${"%.0f".format(rendered.spec.kickShiftMs)} ms, alineado B=${rendered.spec.beatAligned}, " +
                        "velocidad B=${"%.3f".format(rendered.spec.tempoMatchRatio)}×, " +
                        "a ${(rendered.spec.preRollStartMs - player.currentPosition) / 1000} s, $sliceInfo): " +
                        rendered.spec.reason + (rendered.spec.tempoNote.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Timber.d(t, "puente: error preparando WAV")
                if (uiRevision == mixUiRevision && bridgeRenderKey == pairKey) preparedBridgeFailedFor = pairKey
            } finally {
                if (!published) runCatching { wavFile.delete() }
                runCatching { tmpFile.delete() }
            }
        }
    }

    private suspend fun handOverBridge(t: ExoPlayer, bridge: PreparedBridge, initialWavOffsetMs: Long) {
        val index = player.currentMediaItemIndex
        val outGainNow = gain()
        val tailScale = (outGainNow / bridge.bakedGainA.coerceAtLeast(0.05f)).coerceIn(0.5f, 1.5f)
        // Media3 entrega los listeners dentro de seekTo en el hilo de aplicación (Main.immediate).
        var interrupted = false
        fun nowMs() = System.nanoTime() / 1_000_000L
        val sessionClock = BridgeHandoff.ContinuousPlayback().apply { update(player.isPlaying, nowMs()) }
        val wavClock = BridgeHandoff.ContinuousPlayback().apply { update(t.isPlaying, nowMs()) }
        val wavListener = object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                wavClock.update(false, nowMs()); wavClock.update(t.isPlaying, nowMs())
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { wavClock.update(isPlaying, nowMs()) }
            // Como en la sesión: un atasco del WAV obliga a recalentar su reloj (el enclavamiento de «estable» se suelta).
            override fun onPlaybackStateChanged(state: Int) {
                wavClock.update(state == Player.STATE_READY && t.isPlaying, nowMs())
            }
        }
        var ownSeek = false
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                sessionClock.update(false, nowMs()); sessionClock.update(player.isPlaying, nowMs())
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !ownSeek) interrupted = true
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) interrupted = true
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { sessionClock.update(isPlaying, nowMs()) }
            override fun onPlaybackStateChanged(state: Int) {
                // Cualquier estado que no sea READY (carga, fin, parada) suelta el «estable» enclavado.
                sessionClock.update(state == Player.STATE_READY && player.isPlaying, nowMs())
                if (state == Player.STATE_BUFFERING) {
                    MixLog.i("puente: sesión cargando en ${player.currentPosition} ms, WAV=${t.currentPosition} ms")
                }
            }
        }
        fun seekSession(atIndex: Int, positionMs: Long) {
            ownSeek = true
            try { player.seekTo(atIndex, positionMs) } finally { ownSeek = false }
        }
        fun sessionCancelled(atIndex: Int) = !player.playWhenReady || interrupted ||
            player.currentMediaItemIndex != atIndex ||
            player.currentMediaItem?.mediaId != (if (atIndex == index) bridge.outgoingId else bridge.incomingId) ||
            t.playerError != null
        // Para el registro: por qué se canceló un puente ya en marcha (pausa, búsqueda, cambio de canción o error).
        fun whyCancel(stage: String, atIndex: Int): String {
            val why = when {
                !player.playWhenReady -> "pausa"
                interrupted -> "búsqueda o pausa"
                player.currentMediaItemIndex != atIndex -> "cambió de canción"
                t.playerError != null -> "error del puente"
                else -> "otra canción en su sitio"
            }
            return "puente ${bridge.spec.style.id}: cancelado en $stage ($why)"
        }
        fun stableClocks() = sessionClock.stable(nowMs(), AudioLevels.pendingMs()) &&
            wavClock.stable(nowMs(), tailSink?.pendingMs())
        fun readyToSwap() = player.playbackState == Player.STATE_READY && player.isPlaying &&
            t.playbackState == Player.STATE_READY && t.isPlaying && stableClocks() &&
            abs(player.playbackParameters.speed - 1f) < 0.001f && abs(t.playbackParameters.speed - 1f) < 0.001f
        player.addListener(listener)
        t.addListener(wavListener)
        // Hasta el relevo A→WAV solo suena A: si se deja antes por algo pasajero, el puente se conserva y el siguiente
        // paso lo vuelve a cargar con desplazamiento mientras quede pre-roll para alinear (`bridgeStartOffsetMs`).
        var relayStarted = false
        handingOver = true
        try {
            t.setPlaybackSpeed(1f)
            t.volume = 0f
            resetProcessors()

            if (initialWavOffsetMs <= 0L) {
                val lateFromStart = (player.currentPosition - bridge.spec.preRollStartMs).coerceAtLeast(0L)
                if (lateFromStart > BRIDGE_COARSE_SEEK_MS && lateFromStart + BRIDGE_MIN_PREROLL_ALIGN_MS < bridge.renderedPreRollMs) {
                    t.seekTo(lateFromStart + lead)
                }
            }
            val pPosAtPlay = player.currentPosition
            val tPosAtPlay = t.currentPosition
            t.play()

            fun cancelledPre() = sessionCancelled(index) || player.playerError != null || !safeBridge(bridge)
            // Por qué se deja el puente antes del relevo (antes salía sin registro y no había forma de saberlo).
            fun whyPre(stage: String): String {
                val why = when {
                    !player.playWhenReady -> "pausa"
                    interrupted -> "búsqueda o pausa"
                    player.currentMediaItemIndex != index -> "cambió de canción"
                    t.playerError != null || player.playerError != null -> "error de reproducción"
                    !safeBridge(bridge) -> "estribillo protegido"
                    !player.isPlaying -> "la sesión no suena (${player.playbackState})"
                    !t.isPlaying -> "el puente no suena (${t.playbackState})"
                    !stableClocks() -> "reloj aún inestable"
                    else -> "sin cancelar: margen o espera agotados"
                }
                return "puente ${bridge.spec.style.id}: fallo en $stage ($why, pos=${player.currentPosition}, wav=${t.currentPosition})"
            }
            val asked = System.nanoTime()
            while (System.nanoTime() - asked < BRIDGE_SETTLE_MS * 1_000_000L) {
                if (cancelledPre()) {
                    MixLog.i(whyPre("arranque"))
                    stopTail()
                    return
                }
                delay(FRAME_MS)
            }
            lead = nextLeadMs(lead, player.currentPosition - pPosAtPlay, t.currentPosition - tPosAtPlay)
            val measuredShiftA = measureWaveShiftMs(
                refMono12k = bridge.preRoll12k,
                refOriginTrackMs = bridge.spec.preRollStartMs,
                refDurationMs = bridge.renderedPreRollMs,
                maxWaitMs = 420L,
                aborted = ::cancelledPre,
            )
            if (cancelledPre()) {
                MixLog.i(whyPre("medición de A"))
                return
            }
            measuredShiftA?.let { rememberTrackShift(bridge.outgoingId, it) }
            val shiftA = measuredShiftA ?: lookupTrackShift(bridge.outgoingId)
            if (shiftA == null) {
                MixLog.i("sin puente: no se pudo verificar el audio de A")
                preparedBridgeFailedFor = Triple(bridge.outgoingId, bridge.outgoingKey.second, bridge.incomingId)
                return
            }

            var drift = ((player.currentPosition - bridge.spec.preRollStartMs) + shiftA) - t.currentPosition
            if (abs(drift) > BRIDGE_COARSE_SEEK_MS) {
                val reseek = ((player.currentPosition - bridge.spec.preRollStartMs) + shiftA + lead)
                    .coerceIn(0L, (bridge.renderedPreRollMs - BRIDGE_MIN_PREROLL_ALIGN_MS).coerceAtLeast(0L))
                t.seekTo(reseek)
                val reStart = System.nanoTime()
                while (System.nanoTime() - reStart < BRIDGE_SETTLE_MS * 1_000_000L) {
                    if (cancelledPre()) {
                        MixLog.i(whyPre("recolocación"))
                        stopTail()
                        return
                    }
                    delay(FRAME_MS)
                }
                drift = ((player.currentPosition - bridge.spec.preRollStartMs) + shiftA) - t.currentPosition
            }

            val entryGateReserve = (tailSink?.pendingMs() ?: 600L).coerceIn(0L, 1_500L) + GATE_MARGIN_MS
            val alignDeadlineWavMs = (bridge.renderedPreRollMs - entryGateReserve - BridgeHandoff.CHECK_RESERVE_MS).coerceAtLeast(450L)
            while (!stableClocks() && player.isPlaying && t.isPlaying && t.currentPosition < alignDeadlineWavMs) {
                if (cancelledPre()) { MixLog.i(whyPre("espera del reloj")); return }
                delay(FRAME_MS)
            }
            var aligned = alignBridgePreRoll(t, bridge.spec.preRollStartMs, shiftA, alignDeadlineWavMs) {
                cancelledPre() || !stableClocks()
            }
            // Un tropiezo del reloj (rebuffer breve) no es motivo para tirar el puente: A sigue sonando sola y entera.
            // Una sola vez, si aún cabe esperar al reloj y alinear antes del límite; pausa, búsqueda, cambio de canción,
            // error o estribillo siguen cancelando del todo.
            if (aligned == null && !cancelledPre()) {
                MixLog.i(whyPre("alineación, se reintenta"))
                t.setPlaybackSpeed(1f)
                val steady = BridgeHandoff.awaitStablePlayback(
                    stable = { player.isPlaying && t.isPlaying && stableClocks() },
                    roomLeft = { t.currentPosition < alignDeadlineWavMs - RETRY_GUARD_MS },
                    cancelled = ::cancelledPre,
                    nowMs = { System.nanoTime() / 1_000_000L }, waitFrame = { delay(FRAME_MS) },
                )
                if (!steady) MixLog.i(whyPre("espera para reintentar (límite wav=$alignDeadlineWavMs)"))
                if (steady) {
                    aligned = alignBridgePreRoll(t, bridge.spec.preRollStartMs, shiftA, alignDeadlineWavMs) {
                        cancelledPre() || !stableClocks()
                    }
                }
            }
            if (aligned == null) {
                MixLog.i(whyPre("alineación (límite wav=$alignDeadlineWavMs)"))
                stopTail()
                if (!cancelledPre()) preparedBridgeFailedFor = Triple(bridge.outgoingId, bridge.outgoingKey.second, bridge.incomingId)
                return
            }
            // Un fundido largo entre copias desfasadas también duplica los golpes: conservar A si no se cuadró.
            if (abs(aligned) > BridgeHandoff.MAX_DRIFT_MS) {
                MixLog.i("sin puente: no se alineó pre-roll ($drift → $aligned ms, shiftA=$shiftA ms)")
                preparedBridgeFailedFor = Triple(bridge.outgoingId, bridge.outgoingKey.second, bridge.incomingId)
                clearBridge(onlyIf = bridge)
                stopTail()
                return
            }

            // La grabación de pantalla solo oye el PCM: el puente se abre ahí antes del relevo (lo que ya había en su búfer
            // sale callado) y [player] se cierra después. Lo que se oye lo sigue mandando el volumen.
            // Etapa 1 -> 2: relevo coherente A -> WAV.
            setMixing(true)
            MixLog.i(
                "puente ${bridge.spec.style.id}: relevo A→WAV (${drift}→${aligned} ms, shiftA=${shiftA}ms, pos=${player.currentPosition}, " +
                    "wav=${t.currentPosition}, ${syncClockSummary()}), ${bridge.spec.reason}",
            )
            var entryAGain = 1f
            var entryWavGain = 0f
            var entryResult: BridgeHandoff.Result
            var entryRetried = false
            while (true) {
                if (!openGateAhead(tailGate, t, tailSink?.pendingMs(), { t.currentPosition < bridge.renderedPreRollMs - 200L }, ::cancelledPre)) {
                    MixLog.i(whyPre("puerta PCM (wav=${t.currentPosition}/${bridge.renderedPreRollMs})"))
                    // Tras un reajuste ya no queda pre-roll: no volver a intentar este puente en el siguiente vistazo.
                    if (entryRetried && !cancelledPre()) {
                        preparedBridgeFailedFor = Triple(bridge.outgoingId, bridge.outgoingKey.second, bridge.incomingId)
                    }
                    stopTail()
                    return
                }
                // El reloj recién arrancado aún se mueve mientras se drena la puerta: se vuelve a medir aquí y, si cabe
                // otro pulso, solo se releva bien cuadrado.
                val strict = companionRetryFits(t.currentPosition, alignDeadlineWavMs, tailSink?.pendingMs())
                entryResult = BridgeHandoff.transfer(
                    snapshot = { BridgeHandoff.Snapshot(readyToSwap(),
                        ((player.currentPosition - bridge.spec.preRollStartMs) + shiftA) - t.currentPosition,
                        bridge.renderedPreRollMs - t.currentPosition) },
                    cancelled = ::cancelledPre,
                    nowMs = { System.nanoTime() / 1_000_000L },
                    waitFrame = { delay(2L) },
                    waitSample = { delay(BridgeHandoff.SAMPLE_GAP_MS) },
                    durationMs = ::swapMsFor,
                    setGains = { a, wav ->
                        if (wav > 0f) relayStarted = true
                        entryAGain = a; entryWavGain = wav
                        onFadeIn(a); t.volume = tailScale * wav
                    },
                    startDriftMs = if (strict) BridgeHandoff.TIGHT_DRIFT_MS else BridgeHandoff.MAX_DRIFT_MS,
                    onStartError = { e -> MixLog.i("puente: A→WAV al relevar err=$e ms${if (strict) "" else " (sin margen para reajustar)"}") },
                )
                if (entryResult != BridgeHandoff.Result.OutOfSync || !strict || entryWavGain > 0f) break
                tailGate?.open = false
                entryRetried = true
                val again = alignBridgePreRoll(t, bridge.spec.preRollStartMs, shiftA, alignDeadlineWavMs - RETRY_GUARD_MS, retry = true) {
                    cancelledPre() || !stableClocks()
                }
                if (again == null) {
                    MixLog.i(whyPre("realineación"))
                    stopTail()
                    if (!cancelledPre()) preparedBridgeFailedFor = Triple(bridge.outgoingId, bridge.outgoingKey.second, bridge.incomingId)
                    return
                }
                MixLog.i("puente: A→WAV reajustado a $again ms")
            }
            if (entryResult != BridgeHandoff.Result.Complete) {
                if (entryWavGain > 0f) {
                    BridgeHandoff.restoreOutgoing(entryAGain, entryWavGain, ::cancelledPre,
                        nowMs = { System.nanoTime() / 1_000_000L }, waitFrame = { delay(2L) },
                        setGains = { a, wav -> onFadeIn(a); t.volume = tailScale * wav },
                    )
                }
                MixLog.i("puente: relevo A→WAV cancelado ($entryResult), sigue A")
                if (!cancelledPre()) preparedBridgeFailedFor = Triple(bridge.outgoingId, bridge.outgoingKey.second, bridge.incomingId)
                return
            }
            t.volume = tailScale
            onFadeIn(0f)
            sessionGate?.open = false

            // Saltar el reproductor de sesión silenciado a la canción B cerca de la entrega en seco.
            val nextIndex = index + 1
            if (nextIndex >= player.mediaItemCount) {
                onFadeIn(1f)
                stopTail()
                MixLog.i("puente ${bridge.spec.style.id}: no hay siguiente en la cola, sigue A")
                return
            }
            // La que se va sigue sonando callada en su sitio (lo que se ve sigue siendo ella, a la par que el puente)
            // hasta que en el puente pasa a oírse la siguiente: solo entonces salta la sesión a B, y con ella la
            // carátula, la letra, la notificación y Android Auto. Antes saltaba al empezar el puente, segundos antes.
            val seekAtWav = min(bridge.switchWavMs, bridge.dryBStartOffsetMs - BRIDGE_B_SEEK_MARGIN_MS)
            while (t.currentPosition < seekAtWav) {
                // The WAV may already carry dry A while new lyrics arrive: it is still reversible before effects.
                if (t.currentPosition < bridge.renderedPreRollMs && !safeBridge(bridge)) {
                    onFadeIn(1f); stopTail(); clearBridge(onlyIf = bridge)
                    MixLog.i("puente ${bridge.spec.style.id}: se deja en el tramo seco (llegó letra que protege un estribillo)")
                    return
                }
                if (sessionCancelled(index)) {
                    onFadeIn(1f)
                    stopTail()
                    MixLog.i(whyCancel("puente seco", index))
                    return
                }
                // Si la que se va (callada) está a punto de acabarse, se salta ya: que no pase sola a la siguiente.
                if (player.duration != C.TIME_UNSET && player.duration - player.currentPosition < 700L) break
                delay(FRAME_MS)
            }
            if (sessionCancelled(index) || !sequentialMixNext(index, player.nextMediaItemIndex) ||
                nextIndex >= player.mediaItemCount || player.getMediaItemAt(nextIndex).mediaId != bridge.incomingId
            ) {
                MixLog.i("puente ${bridge.spec.style.id}: cancelado antes de saltar a B (búsqueda, pausa o cambió la cola)")
                return
            }
            val remainingBeforeDryB = (bridge.dryBStartOffsetMs - t.currentPosition).coerceAtLeast(0L)
            val priorShiftB = lookupTrackShift(bridge.incomingId) ?: 0L
            val initialBSeek = (bridge.handoffBTrackMs - remainingBeforeDryB - priorShiftB + bSeekLeadMs).coerceAtLeast(0L)
            if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
            seekSession(nextIndex, initialBSeek)
            val bSeekAtNs = System.nanoTime()
            val next = player.currentMediaItemIndex
            preparedFor = null
            preparedBridgeWav = null

            fun abortedDuringBridge(): Boolean =
                sessionCancelled(next) || t.playbackState == Player.STATE_ENDED

            // Etapa 2: reproducir la transición DSP íntegra desde el WAV mientras B se amortigua y medimos su desfase de códec.
            var measuredShiftB: Long? = null
            var lastShiftProbeNs = 0L
            val syncRefBDurMs = (bridge.syncRefB12k.size * 1000L) / DjDsp.SYNC_TAP_SAMPLE_RATE
            while (!abortedDuringBridge() && t.currentPosition < bridge.dryBStartOffsetMs - 40L) {
                val nowNs = System.nanoTime()
                if (measuredShiftB == null &&
                    player.playbackState == Player.STATE_READY &&
                    player.isPlaying &&
                    nowNs - bSeekAtNs >= 500_000_000L &&
                    nowNs - lastShiftProbeNs >= 180_000_000L
                ) {
                    lastShiftProbeNs = nowNs
                    measuredShiftB = correlateWaveShiftNow(
                        refMono12k = bridge.syncRefB12k,
                        refOriginTrackMs = bridge.syncRefBOriginMs,
                        refDurationMs = syncRefBDurMs,
                    )?.first?.also { s ->
                        rememberTrackShift(bridge.incomingId, s)
                        MixLog.i("puente ${bridge.spec.style.id}: shiftB pre-medido=${s}ms")
                    }
                }
                delay(FRAME_MS)
            }
            if (sessionCancelled(next)) {
                MixLog.i(whyCancel("saltando a B", next))
                return
            }

            // Etapa 3: entramos en el post-roll 100% seco de B (tempo 1.0×). Esperar a que player esté listo y sincronizado.
            while (!abortedDuringBridge() && t.currentPosition < bridge.totalMs - 180L &&
                (player.playbackState != Player.STATE_READY || !player.isPlaying)
            ) {
                delay(FRAME_MS)
            }
            if (sessionCancelled(next)) {
                MixLog.i(whyCancel("esperando a B", next))
                return
            }

            if (measuredShiftB == null) {
                measuredShiftB = measureWaveShiftMs(
                    refMono12k = bridge.syncRefB12k,
                    refOriginTrackMs = bridge.syncRefBOriginMs,
                    refDurationMs = syncRefBDurMs,
                    maxWaitMs = 380L,
                    aborted = ::abortedDuringBridge,
                )?.also { rememberTrackShift(bridge.incomingId, it) }
            }
            val knownShiftB = measuredShiftB ?: lookupTrackShift(bridge.incomingId)
            val shiftB = knownShiftB ?: 0L

            fun targetTrackBMs(): Long =
                bridge.handoffBTrackMs + (t.currentPosition - bridge.dryBStartOffsetMs).coerceAtLeast(0L) - shiftB

            fun postAlignRoom(): Boolean {
                val gateReserve = (AudioLevels.pendingMs() ?: 600L).coerceIn(0L, 1_500L) + GATE_MARGIN_MS
                return t.isPlaying && t.currentPosition < bridge.totalMs - gateReserve - BridgeHandoff.CHECK_RESERVE_MS
            }
            suspend fun awaitPostClocks() = BridgeHandoff.awaitStablePlayback(
                stable = ::stableClocks, roomLeft = ::postAlignRoom,
                cancelled = { sessionCancelled(next) || player.playerError != null },
                nowMs = { System.nanoTime() / 1_000_000L }, waitFrame = { delay(FRAME_MS) },
            )

            var postAligned: Long? = null
            if (awaitPostClocks()) {
                val coarseB = steadyError({ targetTrackBMs() - player.currentPosition }, { sessionCancelled(next) })
                    ?: (targetTrackBMs() - player.currentPosition)
                if (measuredShiftB != null || abs(coarseB) < 600L) {
                    val oldLead = bSeekLeadMs
                    bSeekLeadMs = (bSeekLeadMs + (coarseB * 0.6).roundToLong()).coerceIn(0L, 900L)
                    MixLog.i("puente: B tras saltar err=$coarseB ms (adelanto $oldLead → $bSeekLeadMs ms)")
                }
                if (abs(coarseB) > BRIDGE_COARSE_SEEK_MS) {
                    val seekTargetB = (targetTrackBMs() + reseekLeadMs)
                        .coerceAtMost((bridge.endBTrackMs - 260L).coerceAtLeast(bridge.handoffBTrackMs - shiftB))
                    seekSession(next, seekTargetB)
                    val waitBStart = System.nanoTime()
                    while (!abortedDuringBridge() && t.currentPosition < bridge.totalMs - 180L &&
                        (player.playbackState != Player.STATE_READY || !player.isPlaying) &&
                        (System.nanoTime() - waitBStart) < 1_200_000_000L
                    ) {
                        delay(FRAME_MS)
                    }
                    if (awaitPostClocks()) {
                        val afterReseek = targetTrackBMs() - player.currentPosition
                        reseekLeadMs = (reseekLeadMs + (afterReseek * 0.65).roundToLong()).coerceIn(0L, 900L)
                        MixLog.i("puente: B tras recolocar err=$afterReseek ms (adelanto ahora $reseekLeadMs ms)")
                    }
                }

                // Alinear player SILENCIADO al WAV audible mediante pulsos calibrados (1.0× fijo tras drenar búfer).
                if (awaitPostClocks()) {
                    postAligned = alignPlayerToBridge(t, bridge, shiftB) { sessionCancelled(next) || !stableClocks() }
                }
            }

            if (sessionCancelled(next)) {
                MixLog.i(whyCancel("antes de WAV→B", next))
                return
            }

            // El WAV puede cubrir una carga breve: solo recuperamos con búsqueda al agotar su margen.
            val exitWavScale = t.volume
            var exitBGain = 0f
            var gateReady = false
            var lastExit: BridgeHandoff.Result? = null
            val exitResult = BridgeHandoff.exitWhileRoom(
                attempt = {
                    if (knownShiftB == null) BridgeHandoff.Result.NotReady else {
                        gateReady = openGateAhead(
                            sessionGate, player, AudioLevels.pendingMs(),
                            { t.currentPosition < bridge.totalMs - BridgeHandoff.CHECK_RESERVE_MS && t.playbackState != Player.STATE_ENDED },
                            { sessionCancelled(next) },
                        )
                        // Medido de nuevo tras drenar la puerta: con margen para otro pulso solo se releva bien cuadrado
                        // (si no, `exitWhileRoom` cierra la puerta, reajusta y reintenta).
                        val strict = companionRetryFits(t.currentPosition, postAlignMaxWavMs(bridge), AudioLevels.pendingMs())
                        BridgeHandoff.transfer(
                            snapshot = { BridgeHandoff.Snapshot(gateReady && readyToSwap(), targetTrackBMs() - player.currentPosition,
                                bridge.totalMs - t.currentPosition) },
                            cancelled = { sessionCancelled(next) },
                            nowMs = { System.nanoTime() / 1_000_000L },
                            waitFrame = { delay(2L) }, waitSample = { delay(BridgeHandoff.SAMPLE_GAP_MS) },
                            durationMs = ::swapMsFor,
                            setGains = { wav, b -> t.volume = exitWavScale * wav; exitBGain = b; onFadeIn(b) },
                            startDriftMs = if (strict) BridgeHandoff.TIGHT_DRIFT_MS else BridgeHandoff.MAX_DRIFT_MS,
                            onStartError = { e -> MixLog.i("puente: WAV→B al relevar err=$e ms${if (strict) "" else " (sin margen para reajustar)"}") },
                        )
                    }.also { lastExit = it }
                },
                roomLeft = { postAlignRoom() && knownShiftB != null && player.playerError == null },
                restoreBridge = {
                    MixLog.i("puente: reintentar WAV→B (${lastExit}), quedan ${bridge.totalMs - t.currentPosition} ms")
                    BridgeHandoff.restoreOutgoing((t.volume / exitWavScale.coerceAtLeast(0.001f)).coerceAtLeast(0f), exitBGain,
                        cancelled = { sessionCancelled(next) }, nowMs = { System.nanoTime() / 1_000_000L }, waitFrame = { delay(2L) },
                        setGains = { wav, b -> t.volume = exitWavScale * wav; exitBGain = b; onFadeIn(b) },
                    )
                    sessionGate?.open = false
                    gateReady = false
                },
                prepareRetry = {
                    if (awaitPostClocks()) {
                        postAligned = alignPlayerToBridge(t, bridge, shiftB, retry = true) { sessionCancelled(next) || !stableClocks() }
                    }
                },
                cancelled = { sessionCancelled(next) }, nowMs = { System.nanoTime() / 1_000_000L }, waitFrame = { delay(FRAME_MS) },
            )
            if (exitResult == BridgeHandoff.Result.Cancelled) { MixLog.i(whyCancel("relevo WAV→B", next)); return }
            if (exitResult != BridgeHandoff.Result.Complete) {
                MixLog.i("puente: recuperar WAV→B ($exitResult, err=${targetTrackBMs() - player.currentPosition} ms, alineador=$postAligned, audio verificado=${knownShiftB != null})")
                val recovery = BridgeHandoff.recover(
                    outgoingGain = (t.volume / exitWavScale.coerceAtLeast(0.001f)).coerceAtLeast(0f),
                    incomingGain = exitBGain,
                    canContinueDestination = {
                        gateReady && knownShiftB != null && player.playbackState == Player.STATE_READY && player.isPlaying &&
                            sessionClock.stable(nowMs(), AudioLevels.pendingMs()) &&
                            abs(player.playbackParameters.speed - 1f) < 0.001f &&
                            abs(targetTrackBMs() - player.currentPosition) <= BridgeHandoff.CONTINUE_DRIFT_MS
                    },
                    bridgePositionMs = { t.currentPosition },
                    stopBridge = { t.pause() },
                    destinationForBridge = { wavMs -> bridge.handoffBTrackMs +
                        (wavMs - bridge.dryBStartOffsetMs).coerceAtLeast(0L) - shiftB },
                    seekDestination = { at ->
                        // Abrir antes de buscar: el primer PCM de la nueva posición ya debe ser audible en la captura.
                        sessionGate?.open = true
                        sessionGate?.fadeInAfterFlush(BridgeHandoff.RECOVERY_FADE_MS)
                        player.setPlaybackSpeed(1f)
                        val mustPrepare = player.playerError != null
                        seekSession(next, at)
                        if (mustPrepare) player.prepare()
                    },
                    destinationReady = { player.playbackState == Player.STATE_READY && player.isPlaying },
                    destinationRevision = { sessionClock.revision },
                    destinationPositionMs = { player.currentPosition },
                    destinationEnded = { player.playbackState == Player.STATE_ENDED },
                    cancelled = { sessionCancelled(next) },
                    nowMs = { System.nanoTime() / 1_000_000L },
                    waitFrame = { delay(FRAME_MS) },
                    setGains = { wav, b -> t.volume = exitWavScale * wav; onFadeIn(b) },
                )
                MixLog.i("puente: recuperación WAV→B $recovery, B=${player.currentPosition} ms")
                return
            }
            t.volume = 0f
            onFadeIn(1f)
            val finalPostErr = targetTrackBMs() - player.currentPosition
            MixLog.i("puente ${bridge.spec.style.id}: relevo WAV→B completo (err=$finalPostErr ms, shiftB=${shiftB}ms, B=${player.currentPosition} ms, ${syncClockSummary()})")
        } catch (cancelled: CancellationException) {
            MixLog.i("puente ${bridge.spec.style.id}: cancelado en tarea de relevo " +
                "(${bridge.outgoingId}→${bridge.incomingId}, relevo iniciado=$relayStarted, " +
                "pista=${player.currentMediaItem?.mediaId}, pos=${player.currentPosition}, WAV=${t.currentPosition})")
            throw cancelled
        } finally {
            // Un seek a la misma posición puede no vaciar el sink: no heredar su rampa en otra búsqueda.
            sessionGate?.clearPendingFade()
            player.removeListener(listener)
            t.removeListener(wavListener)
            handingOver = false
            setMixing(false)
            resetProcessors()
            if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
            onFadeIn(1f)
            stopTail()
            val failed = preparedBridgeFailedFor == Triple(bridge.outgoingId, bridge.outgoingKey.second, bridge.incomingId)
            if (relayStarted || failed || !safeBridge(bridge)) {
                clearBridge(onlyIf = bridge)
                notifyMixUi(null)
            } else {
                // El aviso de la cola sigue siendo válido: el siguiente vistazo lo confirma o lo cambia.
                MixLog.i("puente ${bridge.spec.style.id}: se conserva para reintentar mientras quede pre-roll (pos=${player.currentPosition})")
            }
        }
    }

    /**
     * Alineación predictiva por pulso de velocidad finito seguido de drenaje completo del búfer [AudioTrack] a 1.0×,
     * evitando la oscilación por retardo puro que produce un lazo cerrado sobre hardware con ~430–750 ms de latencia.
     */
    private suspend fun pulseAlign(
        target: ExoPlayer,
        wavPlayer: ExoPlayer,
        aborted: () -> Boolean,
        canSettle: () -> Boolean,
        nudgeStopWavMs: Long,
        maxWavPosMs: Long,
        tag: String,
        pendingOf: () -> Long?,
        acceptMs: Long = BRIDGE_ACCEPT_MS,
        confirmSettled: Boolean = true,
        errorMs: () -> Long,
    ): Long? {
        if (!target.isPlaying || !wavPlayer.isPlaying) return null
        var err = steadyError(errorMs, aborted) ?: return null
        if (abs(err) <= acceptMs) {
            // Medido en el Nothing: justo tras arrancar, el reloj aún salta ~20 ms en un instante. Antes de dar por bueno
            // "sin pulso", otra medida un poco después tiene que decir lo mismo.
            // Solo si la confirmación entera cabe antes del límite (que reserva puerta y relevo): si no, vale la primera.
            if (!confirmSettled || !companionConfirmFits(wavPlayer.currentPosition, maxWavPosMs)) return err
            val firstErr = err
            val confirmAt = System.nanoTime() + SETTLED_CONFIRM_GAP_MS * 1_000_000L
            while (System.nanoTime() < confirmAt) {
                if (aborted() || !target.isPlaying || !wavPlayer.isPlaying) return null
                if (!canSettle()) return firstErr
                delay(FRAME_MS)
            }
            err = steadyError(errorMs, aborted) ?: return null
            if (abs(err - firstErr) > SETTLED_CONFIRM_MS) MixLog.i("$tag: el reloj aún se movía ($firstErr → $err ms)")
            if (abs(err) <= acceptMs) return err
        }
        repeat(ALIGN_ROUNDS) { round ->
            val settleMs = max(BRIDGE_SPEED_SETTLE_MS, (pendingOf() ?: 0L).coerceIn(0L, 1_450L) + 200L)
            // Tras asentar aún hay que medir: si no cabe, el límite corta esa medida y el ajuste se pierde.
            val availForPulse = maxWavPosMs - settleMs - 40L - ALIGN_MEASURE_MS - wavPlayer.currentPosition
            // Si se acelera el propio WAV (entrada A→WAV), el pulso gasta posición a v×, no a 1×: se cuenta con el máximo.
            val pulseRoom = companionPulseRoomMs(availForPulse, sameClock = target === wavPlayer)
            if (aborted() || wavPlayer.currentPosition >= nudgeStopWavMs || pulseRoom < 200L) return err
            val desiredPulse = if (abs(err) > 85L) BRIDGE_LONG_PULSE_MS else BRIDGE_PULSE_MS
            val pulseMs = pulseRoom.coerceAtMost(desiredPulse)
            val gainFactor = if (round == 0) 0.88f else 0.95f
            val v = (1f + (err * gainFactor) / pulseMs.toFloat()).coerceIn(1f - BRIDGE_PULSE_MAX_NUDGE, 1f + BRIDGE_PULSE_MAX_NUDGE)
            val startErr = err
            target.setPlaybackSpeed(v)
            val pulseEnd = System.nanoTime() + pulseMs * 1_000_000L
            while (System.nanoTime() < pulseEnd && canSettle()) {
                if (aborted() || !target.isPlaying || !wavPlayer.isPlaying) {
                    target.setPlaybackSpeed(1f)
                    return null
                }
                delay(FRAME_MS)
            }
            target.setPlaybackSpeed(1f)
            // El cambio de velocidad se oye (y se refleja en la posición) cuando sale lo que ya estaba en el búfer del
            // AudioTrack (~1 s en BT). Medir antes leía medio pulso: se pasaba de largo y el relevo quedaba desfasado.
            val settleEnd = System.nanoTime() + settleMs * 1_000_000L
            while (System.nanoTime() < settleEnd && canSettle()) {
                if (aborted() || !target.isPlaying || !wavPlayer.isPlaying) return null
                delay(FRAME_MS)
            }
            err = steadyError(errorMs, aborted) ?: return null
            MixLog.i("$tag r=$round err=$startErr→$err v=${"%.3f".format(v)} pulse=${pulseMs}ms wav=${wavPlayer.currentPosition}")
            if (abs(err) <= acceptMs) return err
        }
        return err
    }

    /**
     * Abre la puerta PCM [gate] y espera a que salga lo que ya estaba en el búfer del AudioTrack ([pendingMs] + margen),
     * así la grabación de pantalla oye el relevo en su sitio. Solo tiene éxito con avance continuo suficiente:
     * sin margen, con buffering o al cancelar devuelve false; la salida del puente pasa a recuperación.
     */
    private suspend fun openGateAhead(gate: CaptureGate?, source: ExoPlayer, pendingMs: Long?, roomLeft: () -> Boolean, aborted: () -> Boolean): Boolean {
        if (gate == null || gate.open) return true
        var revision = 0L
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) revision++ }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_BUFFERING) revision++ }
        }
        source.addListener(listener)
        return try {
            gate.open = true
            val drained = BridgeHandoff.awaitGateDrain(pendingMs,
                snapshot = { BridgeHandoff.GateSnapshot(source.isPlaying, source.currentPosition, revision) },
                roomLeft = roomLeft, cancelled = aborted,
                nowMs = { System.nanoTime() / 1_000_000L }, waitFrame = { delay(FRAME_MS) },
            ) == BridgeHandoff.Result.Complete
            // Abrir sin drenar no cuenta como éxito en el siguiente intento.
            if (!drained) gate.open = false
            drained
        } finally { source.removeListener(listener) }
    }

    /** El desfase medido varias veces seguidas (media recortada de las 3 centrales) para sortear pausas del AudioTimestamp BT. */
    private suspend fun steadyError(errorMs: () -> Long, aborted: () -> Boolean): Long? {
        val samples = LongArray(ERROR_SAMPLES)
        for (i in samples.indices) {
            if (aborted()) return null
            samples[i] = errorMs()
            if (i < samples.lastIndex) delay(ERROR_SAMPLE_GAP_MS)
        }
        return trimmedMedianErrorMs(samples)
    }

    /** Lo que dura el relevo de un reproductor al otro: corto lineal si van clavados; si no, equal-power breve. */
    private fun swapMsFor(errMs: Long): Long = companionSwapMsFor(errMs)

    private suspend fun alignBridgePreRoll(
        t: ExoPlayer,
        preRollStartMs: Long,
        shiftA: Long,
        maxWavPosMs: Long,
        retry: Boolean = false,
        cancelled: () -> Boolean,
    ): Long? {
        val nudgeStopWavMs = (maxWavPosMs - BRIDGE_SPEED_SETTLE_MS - 220L).coerceAtLeast(250L)
        fun gone() = cancelled() || !player.isPlaying || !t.isPlaying
        val res = pulseAlign(
            target = t,
            wavPlayer = t,
            aborted = ::gone,
            canSettle = { t.currentPosition < maxWavPosMs },
            nudgeStopWavMs = nudgeStopWavMs,
            maxWavPosMs = maxWavPosMs,
            tag = if (retry) "preRoll reintento" else "preRoll",
            pendingOf = { tailSink?.pendingMs() },
            acceptMs = if (retry) RETRY_ACCEPT_MS else BRIDGE_ACCEPT_MS,
            confirmSettled = !retry,
        ) { ((player.currentPosition - preRollStartMs) + shiftA) - t.currentPosition }
        // Quien llama registra el motivo (antes de parar [t]) y decide si reintenta o para.
        return res
    }

    /** Hasta dónde del WAV puede acabar un ajuste de B y aún quedar sitio para drenar la puerta y relevar. */
    private fun postAlignMaxWavMs(bridge: PreparedBridge): Long {
        val gateReserve = (AudioLevels.pendingMs() ?: 600L).coerceIn(0L, 1_500L) + GATE_MARGIN_MS
        return (bridge.totalMs - gateReserve - BridgeHandoff.CHECK_RESERVE_MS).coerceAtLeast(bridge.dryBStartOffsetMs)
    }

    private suspend fun alignPlayerToBridge(
        t: ExoPlayer, bridge: PreparedBridge, shiftB: Long, retry: Boolean = false, cancelled: () -> Boolean,
    ): Long? {
        // En un reintento el ajuste acaba antes del límite: tras él aún tiene que caber el relevo permisivo.
        val maxWavPos = postAlignMaxWavMs(bridge) - if (retry) RETRY_GUARD_MS else 0L
        val nudgeStopWavPos = (maxWavPos - BRIDGE_SPEED_SETTLE_MS - 200L).coerceAtLeast(bridge.dryBStartOffsetMs + 200L)
        // Una medida ya empezada no se corta por posición (eso dejaba el ajuste en null y forzaba la recuperación);
        // el límite solo frena pulsos y asentados.
        fun stopped() = cancelled() || !player.isPlaying || !t.isPlaying ||
            t.playerError != null || t.playbackState == Player.STATE_ENDED
        return pulseAlign(
            target = player,
            wavPlayer = t,
            aborted = ::stopped,
            canSettle = { !stopped() && t.currentPosition < maxWavPos },
            nudgeStopWavMs = nudgeStopWavPos,
            maxWavPosMs = maxWavPos,
            tag = if (retry) "postRoll reintento" else "postRoll",
            pendingOf = { AudioLevels.pendingMs() },
            acceptMs = if (retry) RETRY_ACCEPT_MS else BRIDGE_ACCEPT_MS,
            confirmSettled = !retry,
        ) {
            val targetB = bridge.handoffBTrackMs + (t.currentPosition - bridge.dryBStartOffsetMs).coerceAtLeast(0L) - shiftB
            targetB - player.currentPosition
        }
    }

    private suspend fun correlateWaveShiftNow(
        refMono12k: FloatArray,
        refOriginTrackMs: Long,
        refDurationMs: Long,
    ): Pair<Long, Double>? {
        if (refMono12k.isEmpty() || refDurationMs < 600L) return null
        val snap = AudioLevels.snapshotTap12k() ?: return null
        val (live, liveOrigin) = snap
        val winMs = 380L
        val marginMs = 190L
        val refEndMs = refOriginTrackMs + refDurationMs
        val liveEnd = liveOrigin + (live.size * 1000L) / DjDsp.SYNC_TAP_SAMPLE_RATE
        val minAnchor = max(liveOrigin, refOriginTrackMs + marginMs)
        val maxAnchor = min(liveEnd - winMs, refEndMs - winMs - marginMs)
        if (maxAnchor < minAnchor) return null
        val anchors = if (maxAnchor - minAnchor >= 220L) listOf(maxAnchor, (minAnchor + maxAnchor) / 2L) else listOf(maxAnchor)
        for (anchor in anchors) {
            val res = withContext(Dispatchers.Default) {
                DjDsp.correlateShiftMs(
                    liveMono12k = live,
                    liveOriginMs = liveOrigin,
                    refMono12k = refMono12k,
                    refOriginMs = refOriginTrackMs,
                    anchorMs = anchor,
                    windowMs = winMs,
                )
            }
            if (res != null && res.second >= 0.65) {
                MixLog.i("sync pcm: shift=${"%.1f".format(res.first)}ms (r=${"%.2f".format(res.second)}, anchor=$anchor)")
                return res.first.roundToLong() to res.second
            }
        }
        return null
    }

    private suspend fun measureWaveShiftMs(
        refMono12k: FloatArray,
        refOriginTrackMs: Long,
        refDurationMs: Long,
        maxWaitMs: Long,
        aborted: () -> Boolean,
    ): Long? {
        if (refMono12k.isEmpty() || refDurationMs < 600L) return null
        val deadline = System.nanoTime() + maxWaitMs * 1_000_000L
        while (!aborted()) {
            correlateWaveShiftNow(refMono12k, refOriginTrackMs, refDurationMs)?.let { return it.first }
            if (System.nanoTime() >= deadline) break
            delay(FRAME_MS)
        }
        return null
    }

    private fun syncClockSummary(): String =
        "pPend=${AudioLevels.pendingMs()}ms(${AudioLevels.formatSummary()}),wPend=${tailSink?.pendingMs()}ms(${tailSink?.formatSummary()})"

    private fun stopTail() {
        // Nunca dejar [player] callado en el PCM al acabar o cancelar un relevo.
        sessionGate?.open = true
        tailGate.set(open = false)
        val detachedBridge = preparedBridgeWav
        preparedFor = null
        preparedBridgeWav = null
        preparedFromMs = 0L
        preparedAtMs = 0L
        resetProcessors()
        val t = tail ?: return
        t.volume = 0f
        if (t.playbackParameters.speed != 1f) t.setPlaybackSpeed(1f)
        if (t.playbackState != Player.STATE_IDLE || t.mediaItemCount > 0) {
            t.stop()
            t.clearMediaItems()
        }
        if (detachedBridge != null && detachedBridge !== preparedBridge) {
            runCatching { detachedBridge.wavFile.delete() }
        }
    }

    private fun build(): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val processors = listOfNotNull(loop, outgoingProcessor, outgoingEchoProcessor).toTypedArray()
                val sink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(processors)
                    .build()
                return LoopSink(CaptureGateSink(sink, tailGate), loop).also { tailSink = it }
            }
        }
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sources)
            // Sin foco de audio: si lo pidiera, le quitaría el suyo a [player], que se pararía.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build()
    }

    internal companion object {
        const val IDLE_MS = 500L
        const val FRAME_MS = 16L
        // Con qué antelación se prepara la que se va (de la caché: está entera).
        const val PREPARE_MS = 2_500L
        const val PREPARE_GRACE_MS = 400L
        const val DYNAMIC_PREPARE_AHEAD_MS = 1_500L
        const val PREPARE_READY_WAIT_MS = 950L
        // De la siguiente, al menos esto ya bajado.
        const val NEXT_READY_MS = 3_000L
        const val MIN_PREPARE_READY_MS = 1_500L
        // Cuánto suena [tail] callado antes del relevo, y lo que dura el relevo.
        const val SETTLE_MS = 550L
        const val ALIGN_BUDGET_MS = 2_000L
        // El fundido termina al menos esto antes de que se acabe la que se va.
        const val OUT_MARGIN_MS = 150L
        const val MIN_FADE_MS = 1_000L
        const val MIN_VIABLE_FADE_MS = 350L
        const val MIN_ABORT_FADE_MS = 120L
        const val MIN_PRE_HANDOVER_MS = 300L
        const val SWAP_MS = BridgeHandoff.LINEAR_SWAP_MS
        const val SWAP_LOOSE_MS = 48L
        const val SWAP_COHERENT_MAX_ERR_MS = 10L
        const val ERROR_SAMPLES = 5
        const val ERROR_SAMPLE_GAP_MS = 12L
        const val MAX_INCOMING_WAIT_MS = 7_500L
        // Cuánto se le da para ponerse a la par, cuánto desfase se acepta y cuánto se la puede acelerar o frenar.
        const val ALIGN_MS = 3_000L
        // En vivo: cuándo se vuelve a mirar antes del cambio, margen que queda libre y mínimo de ajuste útil.
        const val LIVE_RECHECK_LEAD_MS = 1_800L
        const val LIVE_REALIGN_SAFETY_MS = 120L
        const val LIVE_REALIGN_MIN_NUDGE_MS = 300L
        const val ALIGN_STEP_MS = 80L
        const val ALIGN_DEADBAND_MS = 4L
        const val MIN_ALIGN_SPEED_DELTA = 0.004f
        // Lo que se deja pasar, callado, tras el último cambio de velocidad: más que la latencia del Bluetooth.
        const val SPEED_SETTLE_MS = 550L
        const val NO_CHANGE_SETTLE_MS = 80L
        const val ALIGN_ROUNDS = 4
        // Por encima de esto el relevo deja de ser el corto de 24 ms y se oye como un golpe doble.
        const val ACCEPT_MS = SWAP_COHERENT_MAX_ERR_MS
        const val ALIGNED_MS = 6L
        const val ALIGN_GAIN_MS = 700f
        const val ALIGN_MAX_NUDGE = 0.20f
        const val COARSE_SEEK_THRESHOLD_MS = 250L
        const val MIN_NUDGE_DIFF = 0.005f
        const val TEMPO_STEP_INTERVAL_MS = 80L
        const val MIN_SPEED_STEP = 0.004f
        // La saliente cambia de tempo poco a poco, mientras suena sola (la entrante aún no ha arrancado).
        const val TAIL_RAMP_MS = 1_500L
        // Cuadre de fase: cada cuánto se mide, cuánto desfase se da por bueno, en cuánto se corrige y hasta qué punto.
        const val PHASE_STEP_MS = 120L
        const val PHASE_OK_MS = 14.0
        const val PHASE_CORRECT_MS = 1_200.0
        const val PHASE_MAX_NUDGE = 0.04f
        const val PHASE_MAX_NUDGE_FAR = 0.08f
        const val PHASE_FAR_MS = 80.0
        // Preparación anticipada de puentes pre-renderizados v2.
        const val BRIDGE_ANALYSIS_AHEAD_MS = 45_000L
        const val BRIDGE_PREPARE_AHEAD_MS = 22_000L
        const val BRIDGE_FORCE_PREPARE_AHEAD_MS = 14_000L
        const val BRIDGE_MIN_PREPARE_LEFT_MS = 1_100L
        const val BRIDGE_SWITCH_MARGIN_MS = 250L
        const val BRIDGE_LATE_PREPARE_AHEAD_MS = 250L
        const val BRIDGE_MIN_PREROLL_ALIGN_MS = 1_200L
        // AudioTrack en el móvil con BT almacena ~1000 ms en el búfer: usamos pulso fijo + drenaje a 1.0× sin sobreoscilación.
        const val BRIDGE_SETTLE_MS = 450L
        const val BRIDGE_SPEED_SETTLE_MS = 680L
        const val BRIDGE_PULSE_MS = 320L
        const val BRIDGE_LONG_PULSE_MS = 420L
        const val BRIDGE_PULSE_MAX_NUDGE = 0.25f
        const val BRIDGE_COARSE_SEEK_MS = 95L
        const val BRIDGE_ACCEPT_MS = 8L
        const val BRIDGE_HARD_MAX_DRIFT_MS = BridgeHandoff.MAX_DRIFT_MS
        const val LIVE_SOFT_ACCEPT_MS = 150L
        // Si un puente no se pudo hacer aún (la siguiente bajando), cada cuánto se reintenta.
        const val BRIDGE_RETRY_MS = 4_000L
        const val BRIDGE_RENDER_TIMEOUT_MS = 30_000L
        // Lo que se espera de más, tras el búfer, al abrir la puerta PCM antes de un relevo.
        const val GATE_MARGIN_MS = 80L
        // Se rehace un puente con el plan nuevo solo si aún queda esto hasta su pre-roll.
        const val BRIDGE_RERENDER_MIN_LEFT_MS = 6_000L
        // B salta como tarde esto antes de su tramo seco del puente (tiempo para cargar y alinearse callada).
        const val BRIDGE_B_SEEK_MARGIN_MS = 1_200L

        /** Lo que tarda [steadyError] en tomar sus muestras. */
        const val ALIGN_MEASURE_MS = (ERROR_SAMPLES - 1) * ERROR_SAMPLE_GAP_MS

        /**
         * Cabe un reintento entero antes de [maxWavPosMs]: devolver las ganancias, medir, un pulso completo, asentar y
         * volver a medir, con holgura de fotogramas. Así el reintento acaba antes del límite y aún queda el relevo final
         * (permisivo) con su puerta, que ese límite ya reserva.
         */
        internal fun companionRetryFits(wavPosMs: Long, maxWavPosMs: Long, pendingMs: Long?): Boolean {
            val settleMs = max(BRIDGE_SPEED_SETTLE_MS, (pendingMs ?: 0L).coerceIn(0L, 1_450L) + 200L)
            // Antes del reajuste: la medida del propio relevo, devolver ganancias y la primera medida del reajuste.
            val beforeAlign = ALIGN_MEASURE_MS + BridgeHandoff.LINEAR_SWAP_MS + ALIGN_MEASURE_MS + RETRY_SLACK_MS
            // El reajuste: lo que [pulseAlign] exige para dar un pulso entero, dentro del límite recortado del reintento
            // (a la velocidad máxima, por si el pulso es del propio WAV).
            val align = settleMs + 40L + ALIGN_MEASURE_MS + (BRIDGE_PULSE_MS * (1f + BRIDGE_PULSE_MAX_NUDGE)).toLong()
            return maxWavPosMs - RETRY_GUARD_MS - wavPosMs >= beforeAlign + align
        }
        const val RETRY_SLACK_MS = 2L * FRAME_MS
        /** El reintento acaba así de antes del límite: holgura para retrasos de `delay` y su medida final. */
        const val RETRY_GUARD_MS = ALIGN_MEASURE_MS + 3L * FRAME_MS
        /** En un reintento se da un pulso por poco que falte: aceptar 8 ms otra vez solo repetiría el drenaje. */
        const val RETRY_ACCEPT_MS = 4L
        const val SETTLED_CONFIRM_GAP_MS = 220L

        /** Pulso que cabe en [availMs] de posición del WAV; con el mismo reloj, a la velocidad máxima del pulso. */
        internal fun companionPulseRoomMs(availMs: Long, sameClock: Boolean): Long =
            if (sameClock) (availMs / (1f + BRIDGE_PULSE_MAX_NUDGE)).toLong() else availMs

        /** Cuánto antes del cambio en vivo se abre la puerta: drenarla, medir y el relevo más largo acaban a su hora. */
        internal fun companionLiveGateLeadMs(pendingMs: Long?): Long =
            (pendingMs ?: 600L).coerceIn(0L, 1_500L) + GATE_MARGIN_MS + ALIGN_MEASURE_MS +
                companionSwapMsFor(LIVE_SOFT_ACCEPT_MS) + FRAME_MS

        /** Arranque, posible recolocación, ajuste completo y su asentado antes de abrir la puerta PCM. */
        internal fun companionLivePrepareLeadMs(): Long =
            2L * SETTLE_MS + ALIGN_BUDGET_MS + SPEED_SETTLE_MS + ALIGN_MEASURE_MS + FRAME_MS +
                LIVE_REALIGN_SAFETY_MS + companionLiveGateLeadMs(1_500L)

        /** The earliest usable deck start must leave enough room for settling and opening the PCM gate. */
        internal fun liveMixPreparationMs(
            positionMs: Long,
            fadeStartMs: Long,
            fadeEndMs: Long,
            deckStartMs: Long?,
            startLeadMs: Long,
            pendingMs: Long?,
        ): Long? {
            if (fadeEndMs - fadeStartMs < MIN_VIABLE_FADE_MS) return null
            val idealStart = (fadeStartMs - companionLivePrepareLeadMs()).coerceAtLeast(0L)
            val start = deckStartMs ?: if (positionMs > idealStart - PREPARE_GRACE_MS) {
                positionMs + DYNAMIC_PREPARE_AHEAD_MS
            } else idealStart
            val audibleHandoffPreparation = max(positionMs, start - startLeadMs) + SETTLE_MS + ALIGN_MEASURE_MS
            val gateDeadline = fadeStartMs - companionLiveGateLeadMs(pendingMs)
            return start.takeIf { audibleHandoffPreparation <= gateDeadline + FRAME_MS }
        }

        /** La confirmación (espera + medida) cabe antes de [maxWavPosMs] sin pasar del límite que guarda puerta y relevo. */
        internal fun companionConfirmFits(wavPosMs: Long, maxWavPosMs: Long): Boolean =
            maxWavPosMs - wavPosMs >= SETTLED_CONFIRM_GAP_MS + ALIGN_MEASURE_MS + FRAME_MS

        /**
         * Plazo para [align] antes del cambio en vivo: tras su plazo aún asienta ([SPEED_SETTLE_MS]) y mide
         * ([ALIGN_MEASURE_MS]); todo debe acabar [LIVE_REALIGN_SAFETY_MS] antes. Null si no da para un ajuste útil.
         */
        internal fun companionLiveRealignBudgetMs(leftMs: Long): Long? {
            val budget = leftMs - LIVE_REALIGN_SAFETY_MS - SPEED_SETTLE_MS - ALIGN_MEASURE_MS - FRAME_MS
            return budget.takeIf { it >= LIVE_REALIGN_MIN_NUDGE_MS }?.coerceAtMost(ALIGN_MS)
        }
        const val SETTLED_CONFIRM_MS = 3L

        internal fun companionSwapMsFor(errMs: Long): Long = when {
            abs(errMs) <= SWAP_COHERENT_MAX_ERR_MS -> SWAP_MS
            abs(errMs) <= BRIDGE_HARD_MAX_DRIFT_MS -> SWAP_LOOSE_MS
            // Desfase grande: relevo largo (2,5× el desfase) para que se oiga como un fundido corto y no como un salto.
            else -> (abs(errMs) * 5L / 2L).coerceIn(90L, 300L)
        }

        internal fun trimmedMedianErrorMs(samples: LongArray): Long {
            if (samples.isEmpty()) return 0L
            val sorted = samples.copyOf().apply { sort() }
            return if (sorted.size >= 5) {
                ((sorted[1] + sorted[2] + sorted[3]) / 3.0).roundToLong()
            } else {
                sorted[sorted.size / 2]
            }
        }

        /**
         * Actualiza el adelanto estimado [currentLeadMs] convergiendo por EMA hacia el retardo real de arranque
         * (`playerMovedMs - tailMovedMs`), evitando que un retardo constante haga divergir `lead` hasta 1000 ms.
         */
        fun nextLeadMs(currentLeadMs: Long, playerMovedMs: Long, tailMovedMs: Long): Long {
            val actualLagMs = (playerMovedMs - tailMovedMs).coerceIn(0L, 1_000L)
            return ((currentLeadMs.coerceIn(0L, 1_000L) * 4L + actualLagMs * 6L) / 10L).coerceIn(0L, 1_000L)
        }
    }
}
