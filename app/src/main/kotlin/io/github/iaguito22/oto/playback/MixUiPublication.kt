package io.github.iaguito22.oto.playback

import io.github.iaguito22.oto.dj.DjTransitionPlan
import io.github.iaguito22.oto.dj.ChorusSafety
import io.github.iaguito22.oto.dj.DjBridgeSpec
import io.github.iaguito22.oto.dj.DjBridgeStyle
import io.github.iaguito22.oto.dj.SongSection
import io.github.iaguito22.oto.dj.label

internal fun DjTransitionPlan.decisionDescription(outgoingDurationMs: Long, incomingDurationMs: Long?): String {
    val durationB = incomingDurationMs?.takeIf { it > 0L } ?: incomingAudio?.durationMs?.takeIf { it > 0L }
    return "motivo=${reason.ifBlank { "sin detalle" }}, A=$outgoingFadeStartMs..${outgoingFadeStartMs + fadeDurationMs}" +
        "/$outgoingDurationMs ms, B=$incomingStartMs/${durationB ?: "?"} ms"
}

/** Use the same plan that this step passes to handOver, including analysis/lyrics updates. */
internal fun liveMixUi(pair: Pair<String, String>, startMs: Long, fadeMs: Long, plan: DjTransitionPlan?, smart: Boolean): MixUi =
    if (plan == null && smart) MixUi(-1L, -1L, null, MixUiState.Preparing, pair)
    else MixUi(startMs, startMs + fadeMs / 2, plan?.bridgeSpec?.style, MixUiState.Live, pair,
        plan?.style?.label ?: "Fundido suave")

internal fun DjBridgeSpec.fitsSource(
    outgoingDurationMs: Long,
    outgoing: List<SongSection>,
    incoming: List<SongSection>,
): Boolean = (if (style == DjBridgeStyle.OutroTail) cueOutEndMs == outgoingDurationMs else cueOutEndMs <= outgoingDurationMs) &&
    ChorusSafety.allows(this, outgoing, incoming)

internal fun mixNext(currentIndex: Int, nextIndex: Int): Boolean =
    currentIndex >= 0 && nextIndex >= 0 && nextIndex != currentIndex

/** Same alignment budget for preparing, announcing and loading a bridge. null means it arrived too late. */
internal fun bridgeStartOffsetMs(
    positionMs: Long,
    preRollStartMs: Long,
    renderedPreRollMs: Long,
    minAlignMs: Long,
    latePrepareAheadMs: Long,
): Long? {
    val offset = if (positionMs > preRollStartMs - 150L) {
        (positionMs - preRollStartMs + latePrepareAheadMs).coerceAtLeast(0L)
    } else 0L
    return offset.takeIf { it <= renderedPreRollMs - minAlignMs }
}

internal data class MixUiConfiguration(val smart: Boolean, val fadeMs: Long, val djMode: String)

internal class MixUiPublication {
    var latest: MixUi? = null
    private var configuration: MixUiConfiguration? = null

    fun configure(value: MixUiConfiguration): Boolean {
        if (value == configuration) return false
        configuration = value
        latest = null
        return true
    }
}

/** Resource cleanup does not invalidate a decision already delivered to the queue. */
internal class MixNoticeDedup {
    private var latest: MixUi? = null
    private var outgoingIndex: Int? = null
    private var rejection: String? = null

    fun accept(ui: MixUi?, index: Int, rejectionKey: String? = null): Boolean {
        val occurrence = index.takeIf { ui != null }
        if (ui == latest && occurrence == outgoingIndex && rejectionKey == rejection) return false
        latest = ui
        outgoingIndex = occurrence
        rejection = rejectionKey
        return true
    }

    fun reset() {
        latest = null
        outgoingIndex = null
        rejection = null
    }
}

/** A failed transition can be retried after a real rewind in the same queue occurrence. */
internal class MixAttemptRecovery {
    private var pair: Triple<String, Int, String>? = null
    var skipped = false
        private set
    private var rewound = false

    fun watch(nextPair: Triple<String, Int, String>?): Boolean {
        if (pair == nextPair) return false
        pair = nextPair
        skipped = false
        rewound = false
        return true
    }

    fun skip() {
        skipped = true
        rewound = false
    }

    fun seek(track: Pair<String, Int>?, oldMs: Long, newMs: Long) {
        val outgoing = pair?.let { it.first to it.second }
        rewound = outgoing != null && track == outgoing && newMs < oldMs
    }

    fun rearm(canPrepare: Boolean): Boolean {
        if (!rewound || !canPrepare) return false
        skipped = false
        rewound = false
        return true
    }
}

/**
 * Un plan en vivo con el análisis de alguna de las dos a medias aún puede cambiar de estilo (medido en el móvil:
 * «Filtro + eco» → «Fundido suave» segundos después). Hasta que llegue el análisis entero o se confirme el relevo,
 * se anuncia como «Preparando» en vez de enseñar un nombre provisional.
 */
internal fun provisionalLivePlan(plan: DjTransitionPlan?, smart: Boolean): Boolean =
    smart && plan != null && !(plan.outgoingAudio?.coversWholeSong == true && plan.incomingAudio?.coversWholeSong == true)

/** Once a deck is loaded, a late render must not replace the notice of the selected audio path. */
internal fun selectedMixUi(
    live: MixUi,
    pending: MixUi,
    ready: MixUi?,
    committedBridge: MixUi?,
    committedLive: Boolean,
    canPrepare: Boolean,
    provisional: Boolean = false,
    canLive: Boolean = true,
): MixUi = when {
    committedBridge != null -> committedBridge
    committedLive && canLive -> live
    ready != null -> ready
    canPrepare || (canLive && provisional) -> pending
    canLive -> live
    else -> MixUi(-1L, -1L, null, MixUiState.None, live.pair)
}

/**
 * Resolves the queue notice without treating missing timing data as a rejected transition. Also while a mix is
 * sounding: the session already moved to the next song and its own pair is still pending (before, the queue showed
 * nothing for ~6 s during every bridge).
 */
internal fun publishedMixUi(
    pair: Pair<String, String>?,
    allowed: Boolean,
    latest: MixUi?,
): MixUi? {
    if (!allowed || pair == null) return null
    val matching = latest?.takeIf { it.pair == pair }
    if (matching?.state == MixUiState.None) return null
    return matching ?: MixUi(-1L, -1L, null, MixUiState.Preparing, pair)
}
