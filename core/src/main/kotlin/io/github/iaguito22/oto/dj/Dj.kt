package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.lyrics.Lyrics
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/** Información preparada de un tema del DJ para la cola NextUp y razones visibles en UI. */
data class DjPickInfo(
    val track: Track,
    val kind: Kind,
    val tanda: String?,
    val reason: String,
    val isBridge: Boolean = false,
    val bridgeFrom: String? = null,
    val bridgeTo: String? = null,
    val adhdSegment: AdhdSegment? = null,
)

/**
 * Un cambio en la cola del DJ por tempo ([Dj.retuneUpcomingTempo]): la canción [removed] no se podía cuadrar con
 * [previous] y entra [replacement] (o, agotados los intentos, vuelve la original).
 */
data class DjTempoSwap(
    val previous: Track,
    val removed: Track,
    val verdict: DjTempoMatch.TempoVerdict,
    /** La que queda en su lugar (null: no salió ninguna). */
    val replacement: Track?,
    /** Lo que ya se sabe de la nueva frente a [previous] (lo normal: sin medir hasta que se precargue). */
    val replacementVerdict: DjTempoMatch.TempoVerdict?,
    /** Se agotaron los intentos y vuelve la primera que eligió el DJ. */
    val restoredOriginal: Boolean,
    val attempt: Int,
    val nextUp: List<DjPickInfo>,
) {
    /** Para `oto.mix`: «DJ: X cambiada por tempo (A 100 → X 128, estira 22 %) → Y 104». */
    fun describe(): String {
        if (restoredOriginal) {
            return "DJ: ninguna encaja con «${previous.title}» tras $attempt intentos; vuelve «${replacement?.title ?: "?"}»"
        }
        val why = when (verdict.fit) {
            DjTempoMatch.TempoFit.NoKick -> "sin bombo al pulso"
            DjTempoMatch.TempoFit.NoPulse -> "sin pulso claro"
            DjTempoMatch.TempoFit.KeyClash -> "tonos que chocan"
            else -> "estira ${pct(verdict.stretchPct)} %"
        }
        val next = replacement?.let { r ->
            val v = replacementVerdict
            val bpm = v?.bpmB?.takeIf { it > 0.0 }?.let { bpm(it) } ?: "BPM sin medir"
            "«${r.title}» $bpm"
        } ?: "ninguna"
        return "DJ: «${removed.title}» cambiada por tempo (${previous.title} ${bpm(verdict.bpmA)} → " +
            "${removed.title} ${bpm(verdict.bpmB)}, $why) → $next (intento $attempt/${Dj.MAX_TEMPO_SWAPS})"
    }

    private fun bpm(v: Double) = if (v > 0.0) "%.0f".format(v) else "?"
    private fun pct(v: Double) = "%.1f".format(v)
}

/**
 * El DJ, para cuando no sabes qué poner. Pincha por tandas, como uno de verdad: cada tanda gira alrededor de un
 * artista tuyo y de los que se le parecen (por tus listas y por YouTube), y mezcla tres cosas:
 *  - tuyas que llevabas tiempo sin oír (~70 % en modos normales),
 *  - canciones de tus artistas que no tienes y artistas nuevos relacionados (~30 % en modos normales).
 *
 * Adapta la selección al [DjMood] activo (incluyendo hora del día en [DjMood.Auto]), la continuidad entre la canción
 * N y N+1 (artistas, colaboradores, radio y ambiente/género), el enfriamiento obligatorio de canciones repetidas, la
 * reacción en vivo (saltos rápidos < 20 s vs escuchas completas, me gusta o guardados) y el modo [DjMode.Adhd] con
 * segmentos cortos.
 *
 * No es seguro entre hilos: se usa siempre desde el mismo (el principal en el servicio).
 */
class Dj(
    private val profile: Profile,
    private val catalog: Catalog,
    private val memory: Memory,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    mood: DjMood = DjMood.Auto,
    mode: DjMode = DjMode.Normal,
    /** Por dónde va (para el registro y las pruebas). */
    private val log: (String) -> Unit = {},
    /** Un género tuyo al que ceñirse (ver [GenreStore.top]); null: todo lo tuyo. */
    genre: DjGenre? = null,
    /** El género de una canción, si se sabe (el suyo o el de su artista; ver [GenreStore.genreOf]). */
    private val genreOf: (Track) -> String? = { null },
    /** Caché compartida de análisis real del audio (BPM, fases, loudness, drop). */
    private val analysisCache: AudioAnalysisCache? = null,
    /** Lector opcional de letras en caché para favorecer ganchos vocales y evitar choques sin lecturas de red. */
    private val lyricsPeek: ((String) -> Lyrics?)? = null,
) {
    /** Lo que ha puesto y cuánto te quedaste (null: aún suena). [vias]: los artistas tuyos que llevaron a ella. */
    class Pick(
        val track: Track,
        val kind: Kind,
        val vias: Set<String>,
        var reward: Double? = null,
        var adhdSegment: AdhdSegment? = null,
        val tanda: String? = null,
        val reason: String? = null,
        val isBridge: Boolean = false,
    ) {
        /** El salto ya enfrió su rama (lo cuentan [heard] y [react]: que no cuente dos veces). */
        var skipApplied = false
    }

    private class Candidate(
        val track: Track,
        val kind: Kind,
        val vias: Set<String>,
        var weight: Double,
        val target: String? = null,
        val isBridge: Boolean = false,
        val bridgeFrom: String? = null,
        val bridgeTo: String? = null,
        val customReason: String? = null,
        var consensusCount: Int = 1,
        var sourceRank: Int = 99,
        var radioConfirmed: Boolean = false,
        var onlyVibeMatched: Boolean = false,
    )

    private data class AnchorSnapshot(
        val anchor: Profile.Artist,
        val region: Map<String, Double>,
        val block: Int,
        val blockLen: Int,
        val skips: Int,
        val jumpAway: Boolean,
    )

    private data class StagedPick(
        val candidate: Candidate,
        val tanda: String?,
        val reason: String,
        val adhdSegment: AdhdSegment?,
        val snapshotBefore: AnchorSnapshot,
        val snapshotAfter: AnchorSnapshot,
    ) {
        fun toInfo() = DjPickInfo(
            track = candidate.track,
            kind = candidate.kind,
            tanda = tanda,
            reason = reason,
            isBridge = candidate.isBridge || candidate.kind == Kind.BRIDGE,
            bridgeFrom = candidate.bridgeFrom,
            bridgeTo = candidate.bridgeTo,
            adhdSegment = adhdSegment,
        )
    }

    private class Remate(
        val vibe: VibeScore,
        val vias: Set<String>,
        val genre: String?,
        val sourceArtist: String,
        var remaining: Int = 2,
    )

    /** Un artista que no es tuyo y los cercanos a ti que, según YouTube, se le parecen. */
    private class Cited(val id: String) {
        val by = HashSet<String>()
    }

    /** Refuerzo temporal en la sesión tras una escucha completa, me gusta o guardado (dura 4-5 canciones). */
    private class Boost(val track: Track, val vias: Set<String>, val vibe: VibeScore, var remaining: Int, val strength: Double)

    /** Enfriamiento temporal de una rama/dirección tras un salto rápido (< 20 s). */
    private class CooldownBranch(val who: String, val vias: Set<String>, val vibe: VibeScore, var remaining: Int)

    var mood: DjMood = mood
        private set

    var mode: DjMode = mode
        private set

    private val now = profile.now
    val picks = ArrayList<Pick>()
    private val keys = HashSet<String>()
    private val pool = LinkedHashMap<String, Candidate>()
    private val explored = HashSet<String>()
    private val radioSeeded = HashSet<String>()
    private val met = HashSet<String>()
    private val cited = HashMap<String, Cited>()
    /** Artistas tuyos que YouTube junta (por clave) aunque tus listas no. */
    private val links = HashMap<String, HashSet<String>>()
    /** Tuyas que salen en la radio de lo que suena: encajan con la tanda. */
    private val inRadio = HashSet<String>()
    /**
     * Artistas apartados tras un salto rápido → elecciones que les quedan fuera. El primer salto los aparta un rato
     * ([BAN_FIRST]); el segundo de la sesión, mucho más ([BAN_AGAIN]). Guardarla u oírla entera los devuelve.
     */
    private val banned = HashMap<String, Int>()
    private val skippedArtists = HashMap<String, Int>()

    /** Lo que una reacción cambió en la tanda [anchorKey] (ver [syncReactionIntoSnapshots]). */
    private class RegionNudge(val anchorKey: String, val values: Map<String, Double>, val lower: Boolean)
    private val nudges = ArrayList<RegionNudge>()
    /** Tanda → saltos seguidos tras la última reacción: manda sobre lo guardado de antes de ella. */
    private var reactionSkips: Pair<String, Int>? = null
    /** Artistas nuevos con los que te quedaste: se acercan a su tanda. */
    private val warm = HashSet<String>()
    private val boosts = ArrayList<Boost>()
    private val cooledBranches = ArrayList<CooldownBranch>()
    private val jobs = ArrayList<Job>()
    private val fullTrackIds = HashSet<String>()
    private val vetoed = HashSet<String>()
    private val upcoming = ArrayDeque<StagedPick>()
    private val pickSnapshots = ArrayList<AnchorSnapshot>()
    private val trackTandas = HashMap<String, String>()
    private val trackReasons = HashMap<String, String>()
    private var pendingRemate: Remate? = null

    /** Para las pruebas: hay un Remate armado. */
    internal val remateArmed get() = pendingRemate != null

    /** Para las pruebas: elecciones que le quedan al refuerzo de esa canción y su fuerza (null si no hay). */
    internal fun boostOf(trackKey: String): Pair<Int, Double>? =
        boosts.firstOrNull { it.track.key == trackKey }?.let { it.remaining to it.strength }

    /**
     * Canciones apartadas de la cola por tempo: no salen en las próximas [TEMPO_SKIP_PICKS] elecciones (no es un
     * veto: ni se castiga al artista ni se aparta para siempre). Clave: id y key → elecciones que quedan.
     */
    private val tempoSkipped = HashMap<String, Int>()

    /** Lo que ya se intentó para el hueco detrás de [prev] (por identidad: si cambia lo de antes, se empieza de cero). */
    private class TempoSlot(val prev: StagedPick, val original: StagedPick) {
        var attempts = 0
        /** Agotados los intentos: no se insiste en este hueco. Encajar NO cierra: se vuelve a mirar en cada llamada. */
        var exhausted = false
        /** La que ya se anunció que encaja (para no repetirlo en el registro). */
        var notedFit: StagedPick? = null
    }

    private val tempoSlots = ArrayList<TempoSlot>()

    /** Cambios por tempo desde la última confirmada: tope por canción para que no haya cascadas de precargas. */
    private var tempoSwapsSinceCommit = 0

    /**
     * Estado de la cola y de la revisión por tempo antes de una revisión, para deshacerla si mientras tanto cambió lo
     * que suena (el PC mueve el índice sin esperar al candado del DJ). Sin red ni elecciones confirmadas en medio:
     * [retuneUpcomingTempo] solo toca la cola preparada y su propio estado.
     */
    class TempoCheckpoint internal constructor(internal val restore: () -> Boolean)

    fun tempoCheckpoint(): TempoCheckpoint {
        val saved = upcoming.toList()
        val skipped = HashMap(tempoSkipped)
        val slots = tempoSlots.map { s ->
            TempoSlot(s.prev, s.original).also {
                it.attempts = s.attempts
                it.exhausted = s.exhausted
                it.notedFit = s.notedFit
            }
        }
        val swaps = tempoSwapsSinceCommit
        return TempoCheckpoint {
            // Si se confirmó alguna de la cola entretanto (avance), el punto ya no vale.
            if (saved.any { s -> s.candidate.track.key in keys || s.candidate.track.id in keys }) {
                false
            } else {
                upcoming.clear()
                saved.forEach { upcoming.addLast(it) }
                tempoSkipped.clear()
                tempoSkipped.putAll(skipped)
                tempoSlots.clear()
                tempoSlots += slots
                tempoSwapsSinceCommit = swaps
                committedSnapshot?.let(::restoreSnapshot)
                true
            }
        }
    }

    /** Deja la cola y la revisión como en [cp] (no deshace elecciones confirmadas: si hubo alguna, no hace nada). */
    fun rollbackTempo(cp: TempoCheckpoint): Boolean = cp.restore()

    /** La tanda: su centro y los artistas que la forman, con lo cerca que están (0–1). */
    private lateinit var anchor: Profile.Artist
    private val region = HashMap<String, Double>()
    private var block = 0
    private var blockLen = 5
    private var skips = 0
    private var relaxLevel = 0
    private var committedSnapshot: AnchorSnapshot? = null

    /** Cambia el mood en vivo para que la siguiente canción ya se adapte sin reiniciar la sesión. */
    fun setMood(newMood: DjMood) {
        if (mood == newMood) return
        mood = newMood
        log("mood: ${newMood.label}")
        invalidateUpcoming()
        blockLen = minOf(blockLen, block + 1)
    }

    var genre: DjGenre? = genre
        private set

    /** Pide pasar a otro sitio de lo tuyo en la próxima tanda (ver [skipTanda]). */
    private var jumpAway = false
    private var skippedRegion: Set<String> = emptySet()

    /** Se ciñe a un género (null: todo) en vivo; la próxima ya es de él. */
    fun setGenre(newGenre: DjGenre?) {
        if (genre?.name == newGenre?.name) return
        genre = newGenre
        log("género: ${newGenre?.name ?: "todos"}")
        invalidateUpcoming()
        blockLen = minOf(blockLen, block)
    }

    /** Con un género elegido, la tanda es de ese género (aunque el artista sea más de otro); si no, el suyo. */
    private fun formatTanda(a: Profile.Artist): String? =
        genre?.name ?: a.songs.values.mapNotNull(genreOf).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** Género activo de la tanda actual si se conoce (sin «Como …»); null si no se sabe. */
    val currentGenre: String?
        get() = tanda

    /**
     * Género activo de la tanda actual si se conoce (p. ej. «Urbano latino»); null si no se sabe o antes de empezar.
     */
    val tanda: String?
        get() {
            // Recién elegido un género, la tanda ya es de él (sin esperar a la próxima canción).
            genre?.let { return it.name }
            val lastPick = picks.lastOrNull()
            if (lastPick != null) return lastPick.tanda
            val snap = committedSnapshot
            if (snap != null) return formatTanda(snap.anchor)
            if (!::anchor.isInitialized) return null
            return formatTanda(anchor)
        }

    /** El género/tanda exacto asignado a [trackId] (útil cuando hay temas pre-encolados); null si no se conoce. */
    fun tandaOf(trackId: String): String? =
        trackTandas[trackId]
            ?: upcoming.firstOrNull { it.candidate.track.id == trackId }?.tanda
            ?: picks.lastOrNull { it.track.id == trackId }?.tanda
            ?: if (picks.none { it.track.id == trackId } && upcoming.none { it.candidate.track.id == trackId }) tanda else null

    /** Razón breve de por qué el DJ eligió [trackId] (p. ej. «Puente de X → Y», «Clásico tuyo», etc.). */
    fun reasonOf(trackId: String): String? =
        trackReasons[trackId]
            ?: upcoming.firstOrNull { it.candidate.track.id == trackId }?.reason
            ?: picks.lastOrNull { it.track.id == trackId }?.reason

    /**
     * Salta el ambiente actual: enfría la tanda entera (su centro, los que la forman y su vibra) y la próxima canción
     * ya es de otra tanda, lejos de esta. Limpia los temas pre-preparados de [upcoming].
     */
    fun skipTanda() {
        invalidateUpcoming()
        if (!::anchor.isInitialized) return
        val vibe = anchor.vibe
        skippedRegion = region.keys.toSet()
        cooledBranches += CooldownBranch(anchor.key, skippedRegion, vibe, remaining = 8)
        for (c in pool.values) if (c.vias.any { it in skippedRegion }) c.weight *= 0.3
        log("salto de tanda: ${anchor.name}")
        jumpAway = true
        block = blockLen
        committedSnapshot = snapshot()
    }

    /**
     * Cuánto encaja con el género elegido. Manda el de la canción (un artista puede tocar varios); si no se sabe, que
     * sea de un artista tuyo de ese género o llegue por uno.
     */
    private fun genreFit(track: Track, vias: Set<String>): Double {
        val g = genre ?: return looseGenreFit(track)
        if (track.key in g.songs) return 1.0
        val known = genreOf(track)
        return when {
            known == g.name -> 1.0
            known != null -> 0.06
            track.who in g.artists -> 0.8
            vias.any { it in g.artists } -> 0.6
            else -> 0.1
        }
    }

    /** El género principal de [a] (lo más repetido de lo suyo), o null si no se sabe. */
    private fun mainGenre(a: Profile.Artist): String? {
        if (mainGenres.containsKey(a.key)) return mainGenres[a.key]
        return a.songs.values.mapNotNull(genreOf).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            .also { mainGenres[a.key] = it }
    }

    /** Caché de [mainGenre] para una elección; se vacía en cada una porque los géneros van llegando de la red. */
    private val mainGenres = HashMap<String, String?>()

    /**
     * Con «Todo» (sin género elegido) el género de la tanda que suena guía sin obligar: antes cualquier canción valía
     * igual y en DJ rápido (tandas de 3–5 trozos) se pasaba de pop en inglés a reguetón sin transición (medido el 17/11).
     */
    private fun looseGenreFit(track: Track): Double {
        if (!::anchor.isInitialized) return 1.0
        val tandaGenre = mainGenre(anchor) ?: return 1.0
        val known = genreOf(track) ?: return LOOSE_UNKNOWN_GENRE_FIT
        return if (known == tandaGenre) 1.0 else LOOSE_OTHER_GENRE_FIT
    }

    /**
     * Con «Todo», cuánto pesa dentro de la tanda un artista suyo de otro estilo que el centro. Antes solo se miraba el
     * género de cada canción, después de elegir artista: si salía un artista de otro estilo, todas sus canciones lo eran y
     * una salía igual (escucha del 25/11: «el DJ cambia mucho de género o pone canciones muy diferentes»).
     */
    private fun looseArtistGenreFit(a: Profile.Artist): Double {
        if (genre != null || !::anchor.isInitialized || a.key == anchor.key) return 1.0
        val tandaGenre = mainGenre(anchor) ?: return 1.0
        val g = mainGenre(a) ?: return LOOSE_UNKNOWN_GENRE_FIT
        return if (g == tandaGenre) 1.0 else LOOSE_OTHER_ARTIST_FIT
    }

    /**
     * Con «Todo», cuánto pesa [a] como centro de la siguiente tanda según el género de la que acaba ([fromGenre]). Cuanto
     * más se lleva en ese género, más fácil es cambiar: variedad poco a poco, no un salto a cada tanda (en una hora de
     * DJ rápido, sin esto, 4 de cada 6 sesiones de prueba no cambiaban nunca de estilo).
     */
    private fun genreContinuity(a: Profile.Artist, fromGenre: String?): Double {
        if (genre != null || fromGenre == null) return 1.0
        val g = mainGenre(a) ?: return LOOSE_UNKNOWN_ANCHOR_FIT
        if (g == fromGenre) return 1.0
        val streak = genreStreak(fromGenre)
        return when {
            streak >= LOOSE_FREE_AFTER_PICKS -> 1.0
            streak >= LOOSE_EASIER_AFTER_PICKS -> LOOSE_EASIER_ANCHOR_FIT
            else -> LOOSE_OTHER_ANCHOR_FIT
        }
    }

    /** Cuántas elecciones seguidas (hechas y ya preparadas) llevan la etiqueta de tanda [g], contando desde la última. */
    private fun genreStreak(g: String): Int {
        var n = 0
        for (i in upcoming.indices.reversed()) { if (upcoming[i].tanda == g) n++ else return n }
        for (i in picks.indices.reversed()) { if (picks[i].tanda == g) n++ else return n }
        return n
    }

    /** Para elegir el centro de una tanda: cuánto de lo suyo es del género elegido. */
    private fun artistGenreShare(a: Profile.Artist): Double {
        val g = genre ?: return 1.0
        if (a.key in g.artists) return 1.0
        val n = a.songs.keys.count { it in g.songs }
        return if (n == 0) 0.03 else (n.toDouble() / a.songs.size).coerceAtLeast(0.15)
    }

    /** Cambia entre modo Normal y DJ rápido (ADHD) en vivo. */
    fun setMode(newMode: DjMode) {
        if (mode == newMode) return
        mode = newMode
        log("modo: ${newMode.label}")
        invalidateUpcoming()
    }

    /** Calcula el segmento ADHD para [track] (usando [durationMs] y análisis de audio si no hay letra). */
    fun adhdSegment(
        track: Track,
        durationMs: Long? = null,
        crossfadeMs: Long = AdhdPlanner.DEFAULT_MIX_MS,
        /** La forma de la canción, de su letra sincronizada ([SongSections.of]); vacía si no hay. */
        sections: List<SongSection> = emptyList(),
        audio: AudioMetrics? = analysisCache?.get(track.id),
        /** La canción ya suena desde aquí: solo se elige su salida ([AdhdPlanner.segment]). */
        fixedStartMs: Long? = null,
        /** La salida no puede empezar antes de esto (la que suena solo se alarga hacia delante). */
        minFadeStartMs: Long = 0L,
    ): AdhdSegment? {
        if (track.id in fullTrackIds) return null
        return track.adhdSegment(durationMs, crossfadeMs, mood, sections, audio, fixedStartMs, minFadeStartMs)?.also { seg ->
            picks.lastOrNull { it.track.id == track.id }?.let { it.adhdSegment = seg }
        }
    }

    /** Anula el recorte ADHD solo para [trackId] (p. ej. «Oír esta entera») sin apagar el modo rápido. */
    fun playFullOnce(trackId: String): Boolean {
        fullTrackIds += trackId
        picks.lastOrNull { it.track.id == trackId }?.adhdSegment = null
        for (i in upcoming.indices) {
            if (upcoming[i].candidate.track.id == trackId) {
                upcoming[i] = upcoming[i].copy(adhdSegment = null)
            }
        }
        log("oír entera esta vez: $trackId")
        return true
    }

    fun isFullOnce(trackId: String): Boolean = trackId in fullTrackIds

    /** Plan de transición compartido para Android y Desktop entre [outgoing] e [incoming]. */
    fun transitionPlan(
        outgoing: Track,
        incoming: Track,
        outgoingDurationMs: Long,
        configuredFadeMs: Long,
        tandaChanged: Boolean = false,
        outgoingAdhd: AdhdSegment? = null,
        incomingAdhd: AdhdSegment? = null,
        outgoingVocalEndMs: Long? = null,
        incomingVocalStartMs: Long? = null,
        outgoingVocals: List<SongSection> = emptyList(),
        incomingVocals: List<SongSection> = emptyList(),
        /** El DJ decide cuánto dura la mezcla (mezcla inteligente activada). */
        autoLength: Boolean = false,
        incomingDurationMs: Long = 0L,
        outgoingLyrics: Lyrics.Synced? = null,
        incomingLyrics: Lyrics.Synced? = null,
        recentStyles: List<DjBridgeStyle> = emptyList(),
    ): DjTransitionPlan {
        val outTanda = tandaOf(outgoing.id)
        val inTanda = tandaOf(incoming.id)
        val shifted = tandaChanged || (outTanda != null && inTanda != null && outTanda != inTanda)
        val effectiveInDur = incomingDurationMs.takeIf { it > 0L } ?: (incoming.seconds?.let { it * 1000L } ?: 0L)
        val outLyric = outgoingLyrics ?: (lyricsPeek?.invoke(outgoing.id) as? Lyrics.Synced)
        val inLyric = incomingLyrics ?: (lyricsPeek?.invoke(incoming.id) as? Lyrics.Synced)
        return DjTransitionPlanner.plan(
            outgoingDurationMs = outgoingDurationMs,
            configuredFadeMs = configuredFadeMs,
            outgoingAudio = analysisCache?.get(outgoing.id),
            incomingAudio = analysisCache?.get(incoming.id),
            outgoingVibe = effectiveVibe(outgoing),
            incomingVibe = effectiveVibe(incoming),
            outgoingGenre = genreOf(outgoing),
            incomingGenre = genreOf(incoming),
            tandaChanged = shifted,
            outgoingAdhd = outgoingAdhd,
            incomingAdhd = incomingAdhd,
            outgoingVocalEndMs = outgoingVocalEndMs,
            incomingVocalStartMs = incomingVocalStartMs,
            outgoingVocals = outgoingVocals,
            incomingVocals = incomingVocals,
            autoLength = autoLength,
            incomingDurationMs = effectiveInDur,
            outgoingLyrics = outLyric,
            incomingLyrics = inLyric,
            djMode = mode,
            isDjSession = true,
            recentStyles = recentStyles,
        )
    }

    /** La primera: una tuya, al momento, sin esperar a la red. null si no tienes nada guardado. */
    fun start(): Track? {
        memory.decay()
        upcoming.clear()
        repeat(10) { i ->
            relaxLevel = when {
                i < 5 -> 0
                i < 7 -> 1
                i < 9 -> 2
                else -> 3
            }
            setAnchor(chooseAnchor(null) ?: return null)
            log("tanda: ${anchor.name}, para empezar (${mood.label})")
            familiar()?.let { return commitPick(it) }
        }
        return null
    }

    /**
     * Arranca el DJ usando [seed] (y su artista/ambiente) como ancla inicial de la primera tanda.
     * Con [playSeedFirst] = true, devuelve [seed] como primer tema; si es false, devuelve ya el siguiente tema afín.
     */
    fun startFrom(seed: Track, playSeedFirst: Boolean = true): Track? {
        memory.decay()
        upcoming.clear()
        val seedArtist = profile.find(seed.artistId, seed.artist) ?: Profile.Artist(seed.who).apply {
            name = seed.artist
            id = seed.artistId
            songs[seed.key] = seed
            vibe = effectiveVibe(seed)
            affinity = 1.0
        }
        setAnchor(seedArtist)
        // Si el artista semilla no estaba en la biblioteca, completamos la vecindad por género y vibra real.
        if (profile.artists[seedArtist.key] == null) {
            val seedGenre = genreOf(seed)
            profile.core.asSequence()
                .map { a ->
                    val vibeSim = seedArtist.vibe.synergyWith(a.vibe)
                    val gSim = if (seedGenre != null && a.songs.values.any { genreOf(it) == seedGenre }) 0.35 else 0.0
                    a to (vibeSim * 0.7 + gSim).coerceAtMost(0.95)
                }
                .filter { it.second >= 0.48 }
                .sortedByDescending { it.second }
                .take(REGION)
                .forEach { (a, s) -> region.merge(a.key, s, ::maxOf) }
            val around = region.keys.filter { it != seedArtist.key }.mapNotNull(profile.artists::get).take(3)
            around.forEach(::explore)
        }
        log("tanda: ${anchor.name}, arrancando desde ${seed.title}")
        if (playSeedFirst) {
            val kind = if (seed.key in profile.weight) Kind.FAMILIAR else Kind.NEW
            val cand = Candidate(
                track = seed,
                kind = kind,
                vias = setOf(seedArtist.key),
                weight = 1.0,
                customReason = "Semilla · ${seed.artist}",
            )
            return commitPick(cand, isSeed = true)
        }
        keys += seed.key
        keys += seed.id
        if (seed.base.length >= 4) keys += "~" + seed.base
        radio(seed)
        relaxLevel = 0
        familiar()?.let { return commitPick(it) }
        repeat(4) { i ->
            relaxLevel = i
            familiar()?.let { return commitPick(it) }
        }
        return start()
    }

    /**
     * La siguiente. Si ya había temas preparados en [upcoming], toma el primero y confirma su estado sin alterar
     * cooldowns previos; si no, planifica uno nuevo.
     */
    suspend fun next(): Track? {
        if (upcoming.isNotEmpty()) {
            val staged = upcoming.removeFirst()
            restoreSnapshot(staged.snapshotAfter)
            val committed = commitPick(staged.candidate, precomputedTanda = staged.tanda, precomputedReason = staged.reason, precomputedAdhd = staged.adhdSegment)
            return committed
        }
        val cand = planNextCandidate(waitForNetwork = true) ?: return null
        return commitPick(cand)
    }

    /**
     * Confirma de forma síncrona (sin red) los temas ya preparados en [upcoming] hasta [trackId] incluido,
     * dejando intactos los elementos posteriores en [upcoming] (cola estable al pasar a la de arriba).
     */
    fun consumeUpcomingThrough(trackId: String): Track? {
        val idx = upcoming.indexOfFirst { it.candidate.track.id == trackId }
        if (idx < 0) return null
        var committed: Track? = null
        repeat(idx + 1) {
            val staged = upcoming.removeFirst()
            restoreSnapshot(staged.snapshotAfter)
            committed = commitPick(
                staged.candidate,
                precomputedTanda = staged.tanda,
                precomputedReason = staged.reason,
                precomputedAdhd = staged.adhdSegment,
            )
        }
        return committed
    }

    /** Devuelve los próximos temas preparados sin comprometerlos todavía en [picks] ni en [memory]. */
    fun nextUp(): List<DjPickInfo> = upcoming.map { it.toInfo() }

    /** Prepara hasta [count] próximos temas (NextUp) para previsualizar en cola o vetar/reemplazar antes de sonar. */
    suspend fun prepareNextUp(count: Int = 3): List<DjPickInfo> {
        val target = count.coerceIn(1, 6)
        if (!::anchor.isInitialized) return emptyList()
        if (upcoming.size >= target) return upcoming.take(target).map { it.toInfo() }
        val savedLive = snapshot()
        try {
            if (upcoming.isNotEmpty()) {
                restoreSnapshot(upcoming.last().snapshotAfter)
                block++
            }
            while (upcoming.size < target) {
                val before = snapshot()
                val cand = planNextCandidate(waitForNetwork = upcoming.isEmpty()) ?: break
                val after = snapshot()
                val tandaLabel = formatTanda(anchor)
                val reason = buildReason(cand)
                val seg = if (mode == DjMode.Adhd && cand.track.id !in fullTrackIds) {
                    cand.track.adhdSegment(mood = mood, audio = analysisCache?.get(cand.track.id))
                } else null
                if (tandaLabel != null) trackTandas[cand.track.id] = tandaLabel else trackTandas.remove(cand.track.id)
                trackReasons[cand.track.id] = reason
                // Para revisar una sesión escuchada: también las que se preparan por adelantado (antes solo se apuntaba la
                // primera de cada sesión y el 25/11 no se podía saber por qué salía cada una).
                log(
                    "preparada: ${cand.track.artist} — ${cand.track.title} (${cand.kind.name.lowercase()}, $reason, tanda ${tandaLabel ?: "?"}, " +
                        "género ${genreOf(cand.track) ?: "?"}, tempo ${tempoVerdictOf(cand.track)?.fit?.name ?: "no cuenta"})",
                )
                upcoming.addLast(StagedPick(cand, tandaLabel, reason, seg, before, after))
                block++
            }
        } finally {
            restoreSnapshot(committedSnapshot ?: savedLive)
        }
        return upcoming.map { it.toInfo() }
    }

    /**
     * Veta un tema preparado en NextUp (o pre-encolado sin reproducir) y lo reemplaza al instante sin romper [picks]
     * ni los cooldowns de sesión.
     */
    suspend fun vetoNextUp(trackId: String, refillCount: Int = 3): List<DjPickInfo> {
        drain()
        vetoed += trackId
        val stagedIdx = upcoming.indexOfFirst { it.candidate.track.id == trackId }
        if (stagedIdx >= 0) {
            val targetTrack = upcoming[stagedIdx].candidate.track
            vetoed += targetTrack.key
            pool.values.filter { it.track.who == targetTrack.who }.forEach { it.weight *= 0.45 }
            val keepPrefix = upcoming.take(stagedIdx)
            upcoming.clear()
            keepPrefix.forEach { upcoming.addLast(it) }
            val restoreTo = keepPrefix.lastOrNull()?.snapshotAfter ?: committedSnapshot
            if (restoreTo != null) restoreSnapshot(restoreTo)
            return prepareNextUp(refillCount)
        }
        // También soporta vetar un pick recién pre-encolado con next() antes de escucharse (reward == null).
        val pickIdx = picks.indexOfLast { it.track.id == trackId && it.reward == null }
        if (pickIdx > 0) {
            val removed = picks.removeAt(pickIdx)
            vetoed += removed.track.key
            pool.values.filter { it.track.who == removed.track.who }.forEach { it.weight *= 0.45 }
            if (pickIdx < pickSnapshots.size) {
                val prevSnap = pickSnapshots[pickIdx - 1]
                pickSnapshots.subList(pickIdx, pickSnapshots.size).clear()
                committedSnapshot = prevSnap
                restoreSnapshot(prevSnap)
            }
            upcoming.clear()
            return prepareNextUp(refillCount)
        }
        return nextUp()
    }

    private fun tempoOf(id: String): DjTempoMatch.TempoProfile? = analysisCache?.tempoProfile(id)

    /**
     * Revisa la cola preparada: si una canción (nunca la siguiente inmediata, cuyo puente ya se prepara) no se podrá
     * cuadrar con la de antes (tempo a más del 6 %, u 8 % si entra sin voz, contando mitad o doble, o sin bombo al
     * pulso), la cambia por otra elegida igual que la original (lo normal: de la misma tanda; si la tanda se queda sin
     * nada, el DJ pasa a otra como siempre). La apartada no sale en unas pocas elecciones, sin castigar a su artista. Como mucho
     * [MAX_TEMPO_SWAPS] cambios por hueco; si ninguna encaja, vuelve la original. Solo cambia algo con análisis de las
     * dos (lo sin medir se deja: se mira otra vez cuando llegue). Hace como mucho un cambio por llamada: lo nuevo
     * hay que precargarlo y analizarlo antes de juzgarlo.
     *
     * [note] recibe avisos para el registro (p. ej. que la nueva sí encaja).
     */
    suspend fun retuneUpcomingTempo(
        refillCount: Int = 3,
        /** Ids que no se pueden tocar (la que suena, la siguiente del reproductor, lo que se está preparando). */
        protect: Set<String> = emptySet(),
        note: (String) -> Unit = {},
    ): DjTempoSwap? {
        if (!::anchor.isInitialized || analysisCache == null) return null
        tempoSlots.removeAll { slot -> upcoming.none { it === slot.prev } }
        // Nada en o antes de una protegida: cambiar un hueco rehace todo lo de detrás.
        val firstFree = maxOf(1, upcoming.indexOfLast { it.candidate.track.id in protect } + 1)
        for (i in firstFree until upcoming.size) {
            val prev = upcoming[i - 1]
            val cur = upcoming[i]
            val slot = tempoSlots.firstOrNull { it.prev === prev }
            if (slot?.exhausted == true) continue
            // Siempre con los perfiles de ahora: un análisis entero puede cambiar lo que dijo el principio, y la de
            // este hueco puede haber cambiado (veto, otro relleno).
            val verdict = DjTempoMatch.tempoVerdict(
                tempoOf(prev.candidate.track.id),
                tempoOf(cur.candidate.track.id),
                maxStretchPct = incomingStretchLimit(cur),
            ).let { v ->
                if (v.fit != DjTempoMatch.TempoFit.Fits) return@let v
                fastMixBlock(prev.candidate.track.id, cur.candidate.track.id) { cur.adhdSegment?.startMs ?: 0L }
                    ?.let { v.copy(fit = it) } ?: v
            }
            when {
                verdict.fit == DjTempoMatch.TempoFit.Fits -> {
                    if (slot != null && slot.attempts > 0 && slot.notedFit !== cur) {
                        slot.notedFit = cur
                        note(
                            "DJ: «${cur.candidate.track.title}» encaja con «${prev.candidate.track.title}» " +
                                "(${"%.0f".format(verdict.bpmA)} → ${"%.0f".format(verdict.bpmB)}, estira ${"%.1f".format(verdict.stretchPct)} %)",
                        )
                    }
                }
                verdict.incomingBlocks -> {
                    val s = slot ?: TempoSlot(prev, cur).also { tempoSlots += it }
                    // El presupuesto limita elecciones nuevas, nunca la vuelta a la original.
                    if (s.attempts >= MAX_TEMPO_SWAPS || tempoSwapsSinceCommit >= MAX_TEMPO_SWAPS_PER_SONG) {
                        s.exhausted = true
                        if (cur === s.original) continue
                        restoreTempoOriginal(i, s, verdict, refillCount)?.let { return it }
                        continue
                    }
                    s.attempts++
                    tempoSwapsSinceCommit++
                    val removed = cur.candidate.track
                    setAside(removed)
                    val replacement = replaceFrom(i, prev, refillCount)
                    if (replacement == null) {
                        // No salió otra: vuelve la original (si no lo era ya) y no se insiste. Si tampoco puede volver
                        // (vetada), la cola ya está recortada: se devuelve igual el cambio para que quien llama
                        // publique la cola de verdad (si no, el reproductor seguiría con la quitada).
                        s.exhausted = true
                        return restoreTempoOriginal(i, s, verdict, refillCount, attemptOverride = s.attempts)
                            ?: DjTempoSwap(
                                previous = prev.candidate.track,
                                removed = removed,
                                verdict = verdict,
                                replacement = null,
                                replacementVerdict = null,
                                restoredOriginal = false,
                                attempt = s.attempts,
                                nextUp = nextUp(),
                            )
                    }
                    val newVerdict = DjTempoMatch.tempoVerdict(tempoOf(prev.candidate.track.id), tempoOf(replacement.id))
                    return DjTempoSwap(
                        previous = prev.candidate.track,
                        removed = removed,
                        verdict = verdict,
                        replacement = replacement,
                        replacementVerdict = newVerdict,
                        restoredOriginal = false,
                        attempt = s.attempts,
                        nextUp = nextUp(),
                    )
                }
                // Sin medir: se espera.
            }
        }
        return null
    }

    /**
     * Lo que se puede estirar la entrante [cur]: 8 % solo si su letra dice que entra sin voz (los primeros 45 s desde
     * donde empieza a sonar); sin letra, el 6 % de cuando canta (casi todo lo que suena canta pronto).
     */
    private fun incomingStretchLimit(cur: StagedPick): Double =
        stretchLimitOf(cur.candidate.track.id, cur.adhdSegment?.startMs ?: 0L)

    /**
     * Dónde empezará a sonar [track]: el inicio de su trozo en DJ rápido (el mismo que tendrá en la cola), si no el
     * principio. Así la elección mira la voz en el mismo sitio que la revisión de la cola.
     */
    private fun entryOf(track: Track): Long {
        if (mode != DjMode.Adhd || track.id in fullTrackIds) return 0L
        upcoming.firstOrNull { it.candidate.track.id == track.id }?.let { return it.adhdSegment?.startMs ?: 0L }
        val audio = analysisCache?.get(track.id)
        // Se pide para cada candidata al elegir: el trozo solo cambia con el ánimo o con un análisis nuevo.
        val memoKey = "${track.id}|${mood.name}|${audio?.analyzedAt ?: 0L}|${audio?.analyzedMs ?: 0L}"
        entryMemo[memoKey]?.let { return it }
        if (entryMemo.size > 4_000) entryMemo.clear()
        return (track.adhdSegment(mood = mood, audio = audio)?.startMs ?: 0L).also { entryMemo[memoKey] = it }
    }
    private val entryMemo = HashMap<String, Long>()

    /**
     * DJ rápido: lo que, aparte del tempo, cierra el cuadre en el puente y ya se sabe al elegir: tonos que chocan
     * (armonía por debajo de [DjTempoMatch.MIN_BEAT_MATCH_HARMONY]) o sin bombo al pulso donde entra [curId] (los
     * [ENTRY_KICK_WINDOW_MS] desde [entry]). Medido con las canciones de Iago (23/11): elegir solo por tempo
     * cuadraba el 25 % de las mezclas y con esto el 47 %. null: nada en contra, o sin medir (no se castiga lo que no se
     * sabe). En el DJ normal no se mira: mezcla cada 3–4 minutos y elegir así le quitaría variedad.
     */
    private fun fastMixBlock(prevId: String, curId: String, entry: () -> Long): DjTempoMatch.TempoFit? {
        if (mode != DjMode.Adhd) return null
        val b = analysisCache?.get(curId) ?: return null
        if (DjHarmonic.harmonicCompatibility(analysisCache.get(prevId), b) < DjTempoMatch.MIN_BEAT_MATCH_HARMONY) {
            return DjTempoMatch.TempoFit.KeyClash
        }
        if (b.bars.isEmpty()) return null
        val entryMs = entry()
        val until = entryMs + ENTRY_KICK_WINDOW_MS
        val window = b.bars.filter { it.endMs > entryMs && it.startMs < until }
        // Compases medidos hasta el final de la ventana y con bombo medido (los análisis viejos dan -1).
        if (window.isEmpty() || b.bars.none { it.endMs >= until } || window.any { it.kickOnBeat < 0.0 }) return null
        return if (DjTempoMatch.kickOnBeat(window, entryMs, until) < DjTempoMatch.MIN_KICK_ON_BEAT) DjTempoMatch.TempoFit.NoKick else null
    }

    private fun stretchLimitOf(trackId: String, fromMs: Long): Double {
        val lyrics = lyricsPeek?.invoke(trackId) as? Lyrics.Synced ?: return DjTempoMatch.MAX_STRETCH_PCT
        val vocals = DjLyricMatcher.vocalOccupancyRatio(lyrics, fromMs, fromMs + 45_000L) ?: return DjTempoMatch.MAX_STRETCH_PCT
        return DjTempoMatch.maxStretchPct(vocals)
    }

    private fun setAside(t: Track) {
        tempoSkipped[t.id] = TEMPO_SKIP_PICKS
        tempoSkipped[t.key] = TEMPO_SKIP_PICKS
    }

    /** Quita de la cola desde [index] y la vuelve a llenar detrás de [prev]. Devuelve la nueva en [index]. */
    private suspend fun replaceFrom(index: Int, prev: StagedPick, refillCount: Int): Track? {
        val keep = upcoming.take(index)
        upcoming.clear()
        keep.forEach { upcoming.addLast(it) }
        committedSnapshot?.let(::restoreSnapshot)
        prepareNextUp(refillCount)
        return upcoming.getOrNull(index)?.takeIf { upcoming[index - 1] === prev }?.candidate?.track
    }

    /** Agotados los intentos: vuelve la primera que eligió el DJ para ese hueco (si sigue valiendo). */
    private suspend fun restoreTempoOriginal(
        index: Int,
        slot: TempoSlot,
        lastVerdict: DjTempoMatch.TempoVerdict,
        refillCount: Int,
        attemptOverride: Int = slot.attempts,
    ): DjTempoSwap? {
        val original = slot.original
        val o = original.candidate.track
        // Vetada por el usuario o ya sonada: no vuelve.
        val stillFree = o.key !in keys && o.id !in keys && "~" + o.base !in keys && o.id !in vetoed && o.key !in vetoed &&
            upcoming.none { it.candidate.track.id == o.id }
        val current = upcoming.getOrNull(index)
        if (!stillFree || current === original) return null
        val removed = current?.candidate?.track
        removed?.let(::setAside)
        tempoSkipped.remove(o.id)
        tempoSkipped.remove(o.key)
        val keep = upcoming.take(index)
        upcoming.clear()
        keep.forEach { upcoming.addLast(it) }
        upcoming.addLast(original)
        original.tanda?.let { trackTandas[o.id] = it } ?: trackTandas.remove(o.id)
        trackReasons[o.id] = original.reason
        committedSnapshot?.let(::restoreSnapshot)
        prepareNextUp(refillCount)
        return DjTempoSwap(
            previous = slot.prev.candidate.track,
            removed = removed ?: o,
            verdict = lastVerdict,
            replacement = o,
            replacementVerdict = null,
            restoredOriginal = true,
            attempt = attemptOverride,
            nextUp = nextUp(),
        )
    }

    private suspend fun planNextCandidate(waitForNetwork: Boolean): Candidate? {
        drain()
        mainGenres.clear()
        relaxLevel = 0
        val moodMismatch = ::anchor.isInitialized && (anchorMoodScore(anchor) < 0.72 || artistGenreShare(anchor) < 0.3)
        val remateCandidate = if (!jumpAway && skips < 2 && !moodMismatch) {
            pendingRemate?.takeIf { it.remaining > 0 }?.let { remate ->
                familiarForRemate(remate)?.also { pendingRemate = null }
            }
        } else null
        var c = remateCandidate ?: if (block >= blockLen || skips >= 2 || moodMismatch || jumpAway) transition() else inBlock()
        if (c == null && waitForNetwork && jobs.any { it.isActive }) {
            withTimeoutOrNull(WAIT_MS) { settle() }
            c = if (jumpAway) transition() else inBlock()
        }
        if (c != null) return c
        // La tanda se quedó sin nada que poner: a otra.
        repeat(10) { i ->
            relaxLevel = when {
                i < 5 -> 0
                i < 7 -> 1
                i < 9 -> 2
                else -> 3
            }
            val nextA = chooseAnchor(anchor) ?: return null
            setAnchor(nextA)
            jumpAway = false
            skippedRegion = emptySet()
            log("tanda: ${anchor.name}, la otra se agotó")
            familiar()?.let { return it }
        }
        return null
    }

    /** Cuánto te quedaste con [id]: actualiza memoria y dispara enfriamiento o refuerzo en vivo. */
    fun heard(id: String, ms: Long, dur: Long) {
        drain()
        val p = picks.lastOrNull { it.track.id == id && it.reward == null } ?: return
        val bypassAdhd = id in fullTrackIds
        val clipDur = if (mode == DjMode.Adhd && !bypassAdhd) {
            (p.adhdSegment ?: if (dur > 0L) AdhdPlanner.segment(dur, id, mood = mood, audio = analysisCache?.get(id))?.also { p.adhdSegment = it } else null)?.clipDurationMs
        } else null
        val effectiveDur = if (mode == DjMode.Adhd && !bypassAdhd && clipDur != null && clipDur > 0L) clipDur else dur
        val r = reward(ms, effectiveDur)
        p.reward = r
        memory.learn(p.kind, r)
        memory.save(now)

        val isFastSkip = ms < FAST_SKIP_MS && (effectiveDur <= 0L || ms < effectiveDur / 2L)
        val isFullListen = when {
            isFastSkip -> false
            mode == DjMode.Adhd && !bypassAdhd && clipDur != null && clipDur > 0L -> ms >= (clipDur * 0.75).toLong()
            else -> r >= HOOKED
        }
        when {
            isFastSkip -> applyFastSkip(p)
            isFullListen -> applyBoost(p, DjReaction.FullListen)
            r < SKIPPED -> applyFastSkip(p)
            else -> {
                skips = 0
                syncReactionIntoSnapshots(emptyMap())
            }
        }
    }

    /** Reacción explícita en vivo (salto rápido, escucha completa, me gusta o guardado en lista). */
    fun react(id: String, reaction: DjReaction) {
        drain()
        val p = picks.lastOrNull { it.track.id == id } ?: return
        when (reaction) {
            DjReaction.FastSkip -> {
                if (p.reward == null) {
                    p.reward = 0.0
                    memory.learn(p.kind, 0.0)
                    memory.save(now)
                }
                applyFastSkip(p)
            }
            DjReaction.FullListen, DjReaction.Like, DjReaction.Save -> {
                if (p.reward == null && reaction == DjReaction.FullListen) {
                    p.reward = 1.0
                    memory.learn(p.kind, 1.0)
                    memory.save(now)
                }
                applyBoost(p, reaction)
            }
        }
    }

    /** Espera a lo que se esté pidiendo. Sin tope: solo para pruebas; [planNextCandidate] la llama con [WAIT_MS]. */
    internal suspend fun settle() {
        // Aplicar lo que llegó puede pedir más (conocer a los citados): se espera hasta que no quede nada.
        while (true) {
            drain()
            val active = jobs.filter { it.isActive }
            if (active.isEmpty()) break
            active.joinAll()
        }
    }

    // ---- Elegir ----

    private fun effectiveVibe(track: Track, vias: Set<String> = emptySet()): VibeScore =
        profile.vibeOf(track, vias, analysisCache?.get(track.id))

    private fun effectiveLastPick(): Pick? = upcoming.lastOrNull()?.let {
        Pick(it.candidate.track, it.candidate.kind, it.candidate.vias, tanda = it.tanda, reason = it.reason, isBridge = it.candidate.isBridge)
    } ?: picks.lastOrNull()

    private fun recentDiscoveryRun(): Int {
        var run = 0
        for (i in upcoming.lastIndex downTo 0) {
            if (upcoming[i].candidate.kind == Kind.FAMILIAR) return run
            run++
        }
        for (i in picks.lastIndex downTo 0) {
            if (picks[i].kind == Kind.FAMILIAR) return run
            run++
        }
        return run
    }

    private fun totalPicksIncludingStaged(): Int = picks.size + upcoming.size

    private fun totalDiscoveryIncludingStaged(): Int =
        picks.count { it.kind != Kind.FAMILIAR } + upcoming.count { it.candidate.kind != Kind.FAMILIAR }

    private fun inBlock(): Candidate? {
        // Tras gustar/guardar un tema nuevo o descubrimiento, rematamos con un tema muy tuyo del mismo rollo.
        pendingRemate?.takeIf { it.remaining > 0 }?.let { remate ->
            familiarForRemate(remate)?.let { hit ->
                pendingRemate = null
                return hit
            }
        }

        val maxDiscRun = if (mood == DjMood.Descubrir) 4 else 1
        val run = recentDiscoveryRun()
        if (run >= maxDiscRun) return familiar()
        if (totalPicksIncludingStaged() < WARM_UP) familiar()?.let { return it }

        val deep = eligible(Kind.DEEP)
        val fresh = eligible(Kind.NEW)
        val kinds = buildList {
            add(Kind.FAMILIAR)
            if (deep.isNotEmpty()) add(Kind.DEEP)
            if (fresh.isNotEmpty()) add(Kind.NEW)
        }
        val shares = ratioAdjustedShares()
        return when (weighted(kinds) { shares.getValue(it) * lift(it) }) {
            Kind.DEEP -> pickDiscoveryCandidate(deep)
            Kind.NEW -> pickDiscoveryCandidate(fresh)
            else -> familiar() ?: pickDiscoveryCandidate(deep + fresh)
        }
    }

    private fun familiarForRemate(remate: Remate): Candidate? {
        val candidates = ArrayList<Pair<Candidate, Double>>()
        for (a in profile.core) {
            if (!allowed(a.key) || a.songs.isEmpty()) continue
            val viaSet = setOf(a.key)
            for (t in a.songs.values) {
                if (!ok(t)) continue
                val tv = effectiveVibe(t, viaSet)
                val syn = remate.vibe.synergyWith(tv)
                val sameGenre = remate.genre != null && genreOf(t) == remate.genre
                val sameVia = a.key in remate.vias || (region[a.key] ?: 0.0) >= 0.4
                if (syn < 0.60 && !sameGenre && !sameVia) continue
                val baseW = (profile.weight[t.key] ?: 1.0).pow(0.65) *
                    (0.5 + syn) *
                    (if (sameGenre) 1.45 else 1.0) *
                    (if (sameVia) 1.35 else 1.0) *
                    moodFit(t, viaSet) *
                    genreFit(t, viaSet)
                candidates += Candidate(
                    track = t,
                    kind = Kind.FAMILIAR,
                    vias = viaSet,
                    weight = 1.0,
                    customReason = "Remate tras ${remate.sourceArtist}",
                ) to baseW
            }
        }
        return styleThenTempo(candidates, { it.second }) { it.first.track }?.first
    }

    /** Regula la proporción objetivo (~90 % conocida / ~10 % descubrimiento en modos normales; invertida en Descubrir). */
    private fun ratioAdjustedShares(): Map<Kind, Double> {
        val base = if (mood == DjMood.Descubrir) SHARE_DISCOVER else SHARE_NORMAL
        val targetDisc = if (mood == DjMood.Descubrir) 0.65 else 0.10
        val tolerance = if (mood == DjMood.Descubrir) 0.05 else 0.02
        val total = totalPicksIncludingStaged()
        if (total < WARM_UP) return base
        val discCount = totalDiscoveryIncludingStaged()
        val actualDisc = discCount.toDouble() / total
        val famFactor = when {
            actualDisc > targetDisc + tolerance -> if (mood == DjMood.Descubrir) 2.8 else 3.6
            actualDisc < targetDisc - tolerance -> if (mood == DjMood.Descubrir) 0.42 else 0.55
            else -> 1.0
        }
        val discFactor = when {
            actualDisc > targetDisc + tolerance -> if (mood == DjMood.Descubrir) 0.32 else 0.14
            actualDisc < targetDisc - tolerance -> if (mood == DjMood.Descubrir) 2.4 else 1.85
            else -> 1.0
        }
        return mapOf(
            Kind.FAMILIAR to base.getValue(Kind.FAMILIAR) * famFactor,
            Kind.DEEP to base.getValue(Kind.DEEP) * discFactor,
            Kind.NEW to base.getValue(Kind.NEW) * discFactor,
        )
    }

    /** Fin de tanda: un puente real (colaboración, género, BPM/vibra o YouTube) que enlace saliente y destino. */
    private fun transition(): Candidate? {
        if (jumpAway) {
            // Saltaste el ambiente: a otra parte de lo tuyo, lejos de esta tanda (sin puentes ni parientes).
            val from = anchor
            val avoided = skippedRegion.ifEmpty { region.keys.toSet() }
            for (level in 0..3) {
                relaxLevel = level
                val nextA = chooseAnchor(from, avoidedRegions = avoided, requireAvailableTrack = true)
                if (nextA != null) {
                    setAnchor(nextA)
                    jumpAway = false
                    skippedRegion = emptySet()
                    log("tanda: ${anchor.name}, saltando el ambiente de ${from.name}")
                    familiar()?.let {
                        return Candidate(
                            track = it.track,
                            kind = it.kind,
                            vias = it.vias,
                            weight = it.weight,
                            customReason = "Nuevo ambiente",
                        )
                    }
                }
            }
            relaxLevel = 0
            jumpAway = false
            skippedRegion = emptySet()
            return null
        }

        val maxDiscRun = if (mood == DjMood.Descubrir) 4 else 1
        val run = recentDiscoveryRun()
        val targetDisc = if (mood == DjMood.Descubrir) 0.65 else 0.10
        val total = totalPicksIncludingStaged()
        val actualDisc = if (total == 0) 0.0 else totalDiscoveryIncludingStaged().toDouble() / total
        val allowNewBridge = if (mood == DjMood.Descubrir) {
            run < maxDiscRun && actualDisc <= targetDisc + 0.08
        } else {
            run < maxDiscRun && total >= 3 && actualDisc <= targetDisc + 0.02
        }
        val baseBridgeProb = if (mood == DjMood.Descubrir) 0.88 else 0.78
        val bridgeProb = (baseBridgeProb * lift(Kind.BRIDGE)).coerceIn(0.35, 0.95)

        if (random.nextDouble() < bridgeProb) {
            findBestBridge(allowNewBridge)?.let { (bridgeCand, destArtist) ->
                val from = anchor
                setAnchor(destArtist)
                log("tanda: ${anchor.name}, por el puente ${bridgeCand.track.title} (${from.name} → ${destArtist.name})")
                return Candidate(
                    track = bridgeCand.track,
                    kind = bridgeCand.kind,
                    vias = bridgeCand.vias,
                    weight = bridgeCand.weight,
                    target = destArtist.key,
                    isBridge = true,
                    bridgeFrom = from.name,
                    bridgeTo = destArtist.name,
                    customReason = "Puente de ${from.name} → ${destArtist.name}",
                )
            }
        }

        val far = region.keys.flatMap { links[it].orEmpty() }.distinct()
            .mapNotNull(profile.artists::get)
            .filter { it.key !in region && it.songs.isNotEmpty() && allowed(it.key) && profile.sim(anchor, it) < FAR && it.songs.values.any(::ok) }
        val farGenre = if (genre == null) mainGenre(anchor) else null
        val linked = weighted(far) { it.affinity.pow(0.7) * artistMoodAndCooldownWeight(it) * genreContinuity(it, farGenre) }
        val from = anchor
        val skipped = skips >= 2
        setAnchor(linked ?: chooseAnchor(anchor, requireAvailableTrack = true) ?: chooseAnchor(anchor) ?: return null)
        log("tanda: ${anchor.name}, ${if (linked != null) "pariente en YouTube de" else "salto desde"} ${from.name}${if (skipped) " (pasaste dos)" else ""}")
        return familiar()
    }

    private fun findBestBridge(allowNewBridge: Boolean): Pair<Candidate, Profile.Artist>? {
        val from = anchor
        val fromRegion = region.keys.toSet()
        val fromVibe = from.vibe
        val fromGenre = from.songs.values.mapNotNull(genreOf).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val fromTrack = effectiveLastPick()?.track
        val fromBpm = fromTrack?.let { analysisCache?.get(it.id) }?.takeIf { it.bpm != null && it.bpmConfidence >= 0.25 }?.bpm

        val destOptions = profile.core.filter {
            it !== from && it.key !in fromRegion && it.songs.isNotEmpty() && allowed(it.key) && artistGenreShare(it) >= 0.15
        }
        if (destOptions.isEmpty()) return null
        val destByKey = destOptions.associateBy { it.key }

        val scoredBridges = ArrayList<Pair<Pair<Candidate, Profile.Artist>, Double>>()

        fun evaluateBridge(cand: Candidate, dest: Profile.Artist, explicitCollab: Boolean) {
            val candVibe = effectiveVibe(cand.track, cand.vias)
            val candGenre = genreOf(cand.track)
            val destGenre = dest.songs.values.mapNotNull(genreOf).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val candBpm = analysisCache?.get(cand.track.id)?.takeIf { it.bpm != null && it.bpmConfidence >= 0.25 }?.bpm
            val destBpm = dest.songs.values.asSequence()
                .mapNotNull { analysisCache?.get(it.id)?.takeIf { m -> m.bpm != null && m.bpmConfidence >= 0.25 }?.bpm }
                .firstOrNull()

            // Sinergia con la tanda saliente (N).
            val synOut = maxOf(
                fromVibe.synergyWith(candVibe),
                if (cand.track.who in fromRegion || cand.vias.any { it in fromRegion }) 0.72 else 0.0,
                if (fromGenre != null && candGenre == fromGenre) 0.70 else 0.0,
            ).coerceIn(0.05, 1.0)

            // Sinergia con la tanda entrante (N+1).
            val synIn = maxOf(
                dest.vibe.synergyWith(candVibe),
                if (cand.track.who == dest.key || dest.key in collaboratorKeys(cand.track)) 0.85 else 0.0,
                if (destGenre != null && candGenre == destGenre) 0.70 else 0.0,
            ).coerceIn(0.05, 1.0)

            // Media armónica: obliga a encajar con AMBAS tandas (saliente y destino).
            val bothSyn = (2.0 * synOut * synIn) / (synOut + synIn + 1e-6)
            if (!explicitCollab && bothSyn < 0.50) return

            val candMoodFit = moodFit(cand.track, cand.vias)
            val candGenreFit = genreFit(cand.track, cand.vias)
            if (candMoodFit < 0.45 || candGenreFit < 0.35) return

            val bpmBonus = when {
                fromBpm != null && candBpm != null -> {
                    val diffOut = DjHarmonic.bpmRelation(fromBpm, candBpm).relDiffPct
                    val diffIn = destBpm?.let { DjHarmonic.bpmRelation(candBpm, it).relDiffPct } ?: diffOut
                    val worst = maxOf(diffOut, diffIn)
                    when {
                        worst <= 4.5 -> 0.42
                        worst <= 9.5 -> 0.24
                        worst >= 20.0 -> -0.28
                        else -> 0.0
                    }
                }
                else -> 0.0
            }
            val genreBonus = when {
                candGenre != null && candGenre == fromGenre && candGenre == destGenre -> 0.26
                candGenre != null && (candGenre == fromGenre || candGenre == destGenre) -> 0.14
                else -> 0.0
            }
            val collabBonus = if (explicitCollab) 0.48 else 0.0
            val consensusBonus = when {
                cand.consensusCount >= 3 -> 1.45
                cand.consensusCount == 2 -> 1.25
                cand.radioConfirmed -> 1.10
                cand.kind == Kind.FAMILIAR -> 1.15
                else -> 0.68
            }
            val rankBonus = when {
                cand.kind == Kind.FAMILIAR -> 1.10
                cand.sourceRank == 0 -> 1.30
                cand.sourceRank == 1 -> 1.15
                cand.sourceRank == 2 -> 1.0
                cand.sourceRank <= 4 -> 0.85
                else -> 0.55
            }
            val w = ((bothSyn + bpmBonus + genreBonus + collabBonus).coerceAtLeast(0.05)) *
                candMoodFit *
                candGenreFit *
                // Con «Todo», un puente a otro estilo solo si es una colaboración de verdad; si no, pesa como cualquier
                // cambio de tanda a otro género (medido el 17/11: «puente» de Harry Styles a reguetón por la vibra).
                (if (explicitCollab) 1.0 else genreContinuity(dest, fromGenre)) *
                artistMoodAndCooldownWeight(dest) *
                cand.weight *
                consensusBonus *
                rankBonus
            if (w > 0.08) scoredBridges += (cand to dest) to w
        }

        // 1. Puentes nuevos en el pool (Kind.BRIDGE o candidatos de descubrimiento que unen dos mundos).
        if (allowNewBridge) {
            for (c in pool.values) {
                if (!ok(c.track)) continue
                if (c.kind == Kind.BRIDGE && c.target != null) {
                    val dest = destByKey[c.target] ?: continue
                    evaluateBridge(c, dest, explicitCollab = false)
                } else if (c.kind == Kind.NEW || c.kind == Kind.DEEP) {
                    val collabs = collaboratorKeys(c.track)
                    val hitDest = collabs.firstNotNullOfOrNull { destByKey[it] }
                    val touchesOut = c.track.who in fromRegion || collabs.any { it in fromRegion } || c.vias.any { it in fromRegion }
                    if (hitDest != null && touchesOut) {
                        evaluateBridge(c, hitDest, explicitCollab = true)
                    }
                }
            }
        }

        // 2. Canciones bisagra en tu biblioteca (colaboraciones reales feat./& o temas compartidos de género/vibra/BPM).
        for (a in profile.core) {
            if (a.songs.isEmpty()) continue
            for (t in a.songs.values) {
                if (!ok(t)) continue
                val collabs = collaboratorKeys(t)
                val inOut = t.who in fromRegion || collabs.any { it in fromRegion }
                val destCollab = collabs.firstNotNullOfOrNull { destByKey[it] }
                if (inOut && destCollab != null) {
                    val cand = Candidate(t, Kind.FAMILIAR, setOf(a.key), 1.0)
                    evaluateBridge(cand, destCollab, explicitCollab = true)
                    continue
                }
                // Bisagra por género/vibra/BPM compartido si el artista está en la frontera entre ambas tandas.
                val tGenre = genreOf(t)
                if (tGenre != null && tGenre == fromGenre) {
                    val matchingDest = destOptions.filter { d ->
                        d.songs.values.any { genreOf(it) == tGenre }
                    }.take(3)
                    for (dest in matchingDest) {
                        evaluateBridge(Candidate(t, Kind.FAMILIAR, setOf(a.key), 0.75), dest, explicitCollab = false)
                    }
                }
            }
        }

        return weighted(scoredBridges) { it.second }?.first
    }

    /** Una tuya de la tanda: combina afinidad de tanda, continuidad N→N+1, arco de energía, mood/hora y refuerzos. */
    private fun familiar(): Candidate? {
        val artists = region.keys.mapNotNull(profile.artists::get)
            .filter { it.songs.isNotEmpty() && allowed(it.key) }
            .toMutableList()
        while (artists.isNotEmpty()) {
            val bases = artists.associateWith { artist ->
                if (artist.songs.values.none(::ok)) 0.0 else (region[artist.key] ?: 0.0).coerceAtLeast(0.05) *
                    artist.affinity.pow(0.45) *
                    artistRepeatWeight(artist.key) *
                    branchMultiplier(artist.key, setOf(artist.key), artist.vibe) *
                    artistGenreShare(artist) *
                    looseArtistGenreFit(artist)
            }
            val topBase = bases.values.maxOrNull() ?: 0.0
            val a = weighted(artists) { artist ->
                val base = bases[artist] ?: 0.0
                if (base <= 0.0) 0.0 else base * artistTempoBias(artist.songs.values.filter(::ok), plausible = base >= STYLE_PLAUSIBLE * topBase)
            } ?: return null
            val t = styleThenTempo(a.songs.values.filter(::ok), { track ->
                (profile.weight[track.key] ?: 1.0).pow(0.65) *
                    forgotten(track) *
                    moodFit(track, setOf(a.key)) *
                    energyArcFit(track, setOf(a.key)) *
                    smooth(track, Kind.FAMILIAR, setOf(a.key)) *
                    boostMultiplier(track, setOf(a.key)) *
                    branchMultiplier(track.who, setOf(a.key), effectiveVibe(track, setOf(a.key))) *
                    (if (track.key in inRadio) 1.55 else 1.0) *
                    genreFit(track, setOf(a.key))
            }) { it }
            if (t != null) return Candidate(t, Kind.FAMILIAR, setOf(a.key), 1.0)
            artists -= a
        }
        return null
    }

    /**
     * Primero el estilo, luego el tempo (escucha del 25/11: «el DJ cambia mucho de género o pone canciones muy
     * diferentes»). Antes el tempo filtraba antes de pesar género, ánimo, energía y continuidad: si solo una canción
     * encajaba en tempo, salía esa aunque fuera de otro estilo. Ahora solo cuentan las que pesan al menos
     * [STYLE_PLAUSIBLE] de la mejor y, entre ellas, el tempo manda como antes ([tempoGate]).
     */
    private fun <T> styleThenTempo(items: List<T>, weight: (T) -> Double, track: (T) -> Track): T? {
        val scored = items.map { it to weight(it) }.filter { it.second > 0.0 }
        val top = scored.maxOfOrNull { it.second } ?: return null
        val plausible = scored.filter { it.second >= STYLE_PLAUSIBLE * top }
        return weighted(tempoGate(plausible) { track(it.first) }) { it.second }?.first
    }

    private fun eligible(kind: Kind) = pool.values.filter { it.kind == kind && ok(it.track) && near(it) > 0 }

    /**
     * Elige un candidato de descubrimiento concentrando el muestreo en los mejores según consenso,
     * ranking de catálogo y afinidad real, en lugar de diluirlos entre decenas de temas flojos.
     */
    private fun pickDiscoveryCandidate(items: List<Candidate>): Candidate? {
        if (items.isEmpty()) return null
        val topCount = if (mood == DjMood.Descubrir) 6 else 3
        // Las mejores por estilo y afinidad, y entre ellas el tempo (antes el tempo filtraba primero: ver [styleThenTempo]).
        val ranked = items.asSequence()
            .map { it to score(it) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(topCount)
            .toList()
        if (ranked.isEmpty()) return null
        return weighted(tempoGate(ranked) { it.first.track }) { it.second.pow(2.2) }?.first
    }

    private fun score(c: Candidate): Double {
        var w = c.weight *
            near(c) *
            smooth(c.track, c.kind, c.vias) *
            moodFit(c.track, c.vias) *
            energyArcFit(c.track, c.vias) *
            artistRepeatWeight(c.track.who) *
            boostMultiplier(c.track, c.vias) *
            branchMultiplier(c.track.who, c.vias, effectiveVibe(c.track, c.vias)) *
            genreFit(c.track, c.vias)
        val consensusFactor = when {
            c.consensusCount >= 3 -> 1.75
            c.consensusCount == 2 -> 1.40
            c.radioConfirmed -> 0.95
            else -> 0.65
        }
        val rankFactor = when {
            c.sourceRank == 0 -> 1.45
            c.sourceRank == 1 -> 1.25
            c.sourceRank == 2 -> 1.10
            c.sourceRank in 3..5 -> 0.90
            else -> 0.48
        }
        w *= consensusFactor * rankFactor
        if (c.radioConfirmed && c.consensusCount >= 2) w *= 1.35
        if (c.onlyVibeMatched) w *= 0.35
        profile.artists[c.track.who]?.let { a ->
            if (c.kind == Kind.DEEP) w *= a.affinity.pow(0.55)
            w *= (profile.djRate(a) / 0.6)
        }
        return w
    }

    /**
     * Moldea el arco de energía dentro de la tanda (`warm-up → subida → pico → respiro`) para dar dinámica natural.
     */
    private fun energyArcFit(track: Track, vias: Set<String>): Double {
        if (!::anchor.isInitialized || blockLen <= 1) return 1.0
        val progress = (block.toDouble() / (blockLen - 1).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val base = when (mood) {
            DjMood.Energia -> maxOf(anchor.vibe.energy, 0.68)
            DjMood.Chill -> minOf(anchor.vibe.energy, 0.36)
            DjMood.Foco -> 0.44
            else -> anchor.vibe.energy
        }
        val targetEnergy = when {
            block == 0 -> (base - 0.12).coerceIn(0.15, 0.85) // warm-up
            progress < 0.55 -> (base + 0.06).coerceIn(0.20, 0.92) // subida
            progress < 0.85 -> (base + 0.18).coerceIn(0.25, 0.98) // pico
            else -> (base - 0.08).coerceIn(0.15, 0.85) // respiro final antes de cambiar de tanda
        }
        val e = effectiveVibe(track, vias).energy
        return (1.18 - 0.78 * abs(e - targetEnergy)).coerceIn(0.58, 1.25)
    }

    /**
     * Continuidad entre la canción anterior (N) y la candidata (N+1): combina similitud en tus listas,
     * enlaces de YouTube, colaboradores compartidos (incluyendo `feat.`), cercanía de BPM/género, radio y vibra real.
     */
    private fun synergy(track: Track, vias: Set<String>): Double {
        val prev = effectiveLastPick() ?: return 1.0
        if (prev.track.who == track.who) return 1.0

        // Colaboradores compartidos entre todos los artistas listados y `feat.` en el título.
        val prevNames = collaboratorKeys(prev.track)
        val prevIds = prev.track.artistIds.filterNotNullTo(HashSet())
        val candNames = collaboratorKeys(track)
        val sharedCollab = candNames.any { it in prevNames } ||
            track.artistIds.any { it != null && it in prevIds }
        if (sharedCollab) return 0.92

        var graphBest = 0.0
        for ((x, wx) in sides(prev.track.who, prev.vias)) for ((y, wy) in sides(track.who, vias)) {
            val s = if (x === y) 1.0 else maxOf(profile.sim(x, y), if (links[x.key]?.contains(y.key) == true) LINKED else 0.0)
            graphBest = maxOf(graphBest, s * wx * wy)
        }
        if (vias.isNotEmpty() && prev.vias.any { it in vias }) {
            graphBest = maxOf(graphBest, 0.45)
        }

        val prevVibe = effectiveVibe(prev.track, prev.vias)
        val candVibe = effectiveVibe(track, vias)
        val vibeSim = prevVibe.synergyWith(candVibe)
        val radioBonus = if (track.key in inRadio) 0.25 else 0.0

        val prevGenre = genreOf(prev.track)
        val candGenre = genreOf(track)
        val genreBonus = if (prevGenre != null && candGenre != null && prevGenre == candGenre) 0.14 else 0.0

        val prevAudio = analysisCache?.get(prev.track.id)
        val candAudio = analysisCache?.get(track.id)
        val contrastRelax = if (::anchor.isInitialized && block == 0) 0.5 else 1.0
        val harmAdj = if (prevAudio != null && candAudio != null && prevAudio.keyConfidence >= 0.25 && candAudio.keyConfidence >= 0.25) {
            ((DjHarmonic.harmonicCompatibility(prevAudio, candAudio) - 0.55) * 0.36 * contrastRelax)
        } else 0.0

        val lufsAdj = if (prevAudio != null && candAudio != null) {
            val ld = abs(prevAudio.integratedLufs - candAudio.integratedLufs)
            when {
                ld <= 3.5 -> 0.05
                ld >= 7.5 -> -0.09 * contrastRelax
                else -> 0.0
            }
        } else 0.0

        val timbreAdj = if (prevAudio != null && candAudio != null && prevAudio.bars.isNotEmpty() && candAudio.bars.isNotEmpty()) {
            val tailA = prevAudio.bars.takeLast(minOf(8, prevAudio.bars.size))
            val headB = candAudio.bars.take(minOf(8, candAudio.bars.size))
            val hDiff = abs(tailA.map { it.harshness }.average() - headB.map { it.harshness }.average())
            val tDiff = abs(tailA.map { it.tonalBrightness }.average() - headB.map { it.tonalBrightness }.average())
            if (hDiff > 0.36 || tDiff > 1.70) -0.12 * contrastRelax else 0.0
        } else 0.0

        val lyricAdj = if (lyricsPeek != null && DjLyricMatcher.hasSharedSingablePhrase(lyricsPeek.invoke(prev.track.id), lyricsPeek.invoke(track.id))) {
            0.09
        } else 0.0

        return (0.58 * graphBest + 0.24 * vibeSim + radioBonus + genreBonus + harmAdj + lufsAdj + timbreAdj + lyricAdj).coerceIn(0.0, 1.0)
    }

    /** Un artista tuyo es él mismo; uno nuevo, los tuyos que llevaron a él (un poco menos). */
    private fun sides(who: String, vias: Set<String>): List<Pair<Profile.Artist, Double>> =
        profile.artists[who]?.takeIf { it.lists.isNotEmpty() }?.let { listOf(it to 1.0) }
            ?: vias.mapNotNull(profile.artists::get).map { it to 0.8 }

    /** Cuánto pesa encadenar bien: evita saltos bruscos de género/energía, más estricto en Foco/Chill. */
    private fun smooth(track: Track, kind: Kind, vias: Set<String>): Double {
        val syn = synergy(track, vias)
        val base = FLOW_FLOOR + syn
        val strictness = when (mood) {
            DjMood.Foco -> 1.45
            DjMood.Chill -> 1.3
            DjMood.Auto -> if (isNightHour()) 1.25 else 1.05
            else -> 1.0
        }
        val kindFactor = if (kind == Kind.NEW) 0.95 else 1.0
        return base.pow(strictness) * kindFactor * tempoFit(track)
    }

    /**
     * Cuánto ayuda el tempo a que la mezcla salga con los beats encima («Cuadre de beats», las que más gustan). Va
     * aparte de la sinergia (que se recorta a 1 y se lo comía): multiplica. Hasta ±8 % (también a mitad o doble) el
     * puente las cuadra; muy distinto solo puede ir con filtro, fundido o corte. Sin análisis de la candidata, un
     * poco menos que una que encaja seguro. En DJ rápido se mezcla cada minuto y pico: pesa más.
     */
    /** Para las pruebas: lo que pesa el tempo de [track] detrás de la última de la cola (o la que suena). */
    internal fun tempoFitOf(track: Track): Double = tempoFit(track)

    private fun tempoFit(track: Track): Double {
        val prev = effectiveLastPick() ?: return 1.0
        // Del índice de tempo: sobrevive a las subidas de versión del análisis (si no, tras cada una, a ciegas).
        // Mismo umbral de pulso que el puente: con pulso dudoso el puente no cuadra y el tempo da igual.
        val a = tempoOf(prev.track.id)?.takeIf { DjTempoMatch.isBeatMatchReady(it) } ?: return 1.0
        // La saliente sin bombo (medida entera) no se cuadra con nada: el tempo de la candidata no cambia la mezcla.
        if (a.kickWhole && a.kickKnown && a.kickShare < DjTempoMatch.MIN_KICK_SHARE) return 1.0
        val b = tempoOf(track.id) ?: return 0.78
        // Sabida y sin pulso claro (también la entera sin BPM, bpm 0): no se cuadrará. A medias, aún sin medir.
        if (!DjTempoMatch.isBeatMatchReady(b)) return if (b.wholeSong) 0.6 else 0.78
        // Lo mismo que mira el puente: lo que hay que estirar B (contando mitad o doble).
        val stretch = DjTempoMatch.stretchPct(DjHarmonic.bpmRelation(a.bpm, b.bpm).ratioOutOverIn)
        val sure = minOf(a.confidence, b.confidence) >= AudioMetrics.BEAT_MATCH_CONFIDENCE ||
            (a.gridBeats >= 8 && b.gridBeats >= 8)
        // Sin bombo en la candidata el puente no cuadra aunque el tempo encaje: apenas suma.
        val kickFactor = when {
            // Mismo criterio que la revisión: sin bombo solo con la entera o bastantes compases (no por una intro).
            b.kickKnown && b.kickShare < DjTempoMatch.MIN_KICK_SHARE &&
                (b.kickWhole || b.kickBars >= DjTempoMatch.MIN_KICK_BARS_PARTIAL) -> 0.2
            b.kickKnown && a.kickKnown -> 1.1
            else -> 1.0
        }
        // DJ rápido: con el tempo dentro, que tampoco lo cierren el tono o la falta de bombo donde entra. Si lo
        // cierran, cuenta como un tempo lejos (por debajo de una sin medir, que aún puede encajar).
        val fastBlocked = stretch <= DjTempoMatch.MAX_STRETCH_INSTRUMENTAL_PCT &&
            fastMixBlock(prev.track.id, track.id) { entryOf(track) } != null
        val gain = when {
            fastBlocked -> -0.35
            stretch <= 4.0 -> 1.35
            stretch <= DjTempoMatch.MAX_STRETCH_PCT -> 0.95
            // Solo se cuadra si entra sin voz (mismo criterio que la revisión de la cola: si no, la elige y luego la cambia).
            stretch <= DjTempoMatch.MAX_STRETCH_INSTRUMENTAL_PCT &&
                stretchLimitOf(track.id, entryOf(track)) > DjTempoMatch.MAX_STRETCH_PCT -> 0.95
            stretch <= 14.0 -> 0.10
            stretch >= 20.0 -> -0.68
            else -> -0.35
        }.let { if (it > 0.0) it * kickFactor * (if (sure) 1.0 else 0.65) else it } *
            (if (mode == DjMode.Adhd) 1.25 else 1.0)
        val lyric = if (lyricsPeek != null && DjLyricMatcher.hasSharedSingablePhrase(lyricsPeek.invoke(prev.track.id), lyricsPeek.invoke(track.id))) 0.35 else 0.0
        return (1.0 + gain + lyric).coerceIn(0.22, 3.0)
    }

    /**
     * Cómo encaja [track] en tempo detrás de la última de la cola (o la que suena), con los mismos límites que la
     * revisión de la cola: Fits, lejos (estirar de más, sin bombo, sin pulso o, en DJ rápido, tonos que chocan) o Unknown
     * (sin medir). null si el tempo no cuenta: la saliente no se puede cuadrar con nada o no hay saliente.
     */
    private fun tempoVerdictOf(track: Track): DjTempoMatch.TempoVerdict? {
        val prev = effectiveLastPick() ?: return null
        val v = DjTempoMatch.tempoVerdict(tempoOf(prev.track.id), tempoOf(track.id), stretchLimitOf(track.id, entryOf(track)))
        if (v.fit == DjTempoMatch.TempoFit.NotApplicable) return null
        if (v.fit != DjTempoMatch.TempoFit.Fits) return v
        return fastMixBlock(prev.track.id, track.id) { entryOf(track) }?.let { v.copy(fit = it) } ?: v
    }

    /**
     * Tempo casi obligatorio (Iago, 24/11: en DJ normal 17 de 18 mezclas salieron «tempo lejos» y casi nunca hubo cuadre
     * de beats, lo que más gusta). Si alguna de [items] encaja, solo esas; si no, ninguna que se sepa lejana mientras
     * quede alguna sin medir. Si nada encaja ni queda sin medir, todas (mejor una lejana que parar).
     */
    private fun <T> tempoGate(items: List<T>, track: (T) -> Track): List<T> {
        if (items.size < 2) return items
        val verdicts = items.map { it to tempoVerdictOf(track(it)) }
        if (verdicts.all { it.second == null }) return items
        verdicts.filter { it.second?.fit == DjTempoMatch.TempoFit.Fits }.map { it.first }.takeIf { it.isNotEmpty() }?.let { return it }
        return verdicts.filter { it.second?.fit == DjTempoMatch.TempoFit.Unknown || it.second == null }.map { it.first }.ifEmpty { items }
    }

    /**
     * Peso de un artista según lo que puede poner a tempo: con alguna que encaja, más; con todas lejanas, menos. Solo
     * sube a los que ya pesan en la tanda ([plausible]): antes ×2,6 frente a ×0,12 hacía que mandaran los artistas del
     * borde de la tanda con una canción a tempo y salían «canciones muy diferentes» (escucha del 25/11).
     */
    private fun artistTempoBias(valid: List<Track>, plausible: Boolean): Double {
        val fits = valid.map { tempoVerdictOf(it) }
        return when {
            fits.all { it == null } -> 1.0
            fits.any { it?.fit == DjTempoMatch.TempoFit.Fits } -> if (plausible) 1.8 else 1.0
            fits.any { it == null || it.fit == DjTempoMatch.TempoFit.Unknown } -> 1.0
            else -> 0.45
        }
    }

    /** Puntuación de [track] según el [mood] activo y la hora del día. */
    private fun moodFit(track: Track, vias: Set<String>): Double {
        val v = effectiveVibe(track, vias)
        val artist = profile.find(track.artistId, track.artist)
        val nightBias = profile.nightBias(artist)
        return when (mood) {
            DjMood.Energia -> (0.15 + 2.2 * v.energy) * (1.15 - 0.65 * v.chill)
            DjMood.Chill -> (0.15 + 2.2 * v.chill) * (1.15 - 0.65 * v.energy)
            DjMood.Foco -> (0.15 + 2.1 * v.focus + 0.4 * v.chill) * (1.1 - 0.55 * maxOf(0.0, v.energy - 0.55))
            DjMood.Descubrir -> 0.75 + 0.5 * timeOfDayFit(v, nightBias)
            DjMood.Auto -> timeOfDayFit(v, nightBias)
        }.coerceIn(0.08, 3.2)
    }

    private fun timeOfDayFit(v: VibeScore, nightBias: Double): Double {
        val hour = profile.hourOfDay
        return when {
            // Noche y madrugada: más calmado y afín a escuchas nocturnas.
            hour in 22..23 || hour in 0..5 -> {
                val base = 0.30 + 1.55 * v.chill + 0.35 * (1.0 - v.energy)
                (base * (1.0 + 0.45 * nightBias)).coerceAtLeast(0.12)
            }
            // Día y tarde: más energía y ritmo activo.
            hour in 10..18 -> {
                val base = 0.35 + 1.50 * v.energy + 0.25 * (1.0 - v.chill)
                (base * (1.0 - 0.35 * nightBias)).coerceAtLeast(0.15)
            }
            // Mañana temprana: arranque equilibrado.
            hour in 6..9 -> 0.65 + 0.45 * v.energy + 0.25 * v.focus
            // Media tarde / noche temprana: transición suave.
            else -> 0.65 + 0.45 * v.chill + 0.25 * v.energy
        }
    }

    private fun isNightHour(): Boolean {
        val h = profile.hourOfDay
        return h in 22..23 || h in 0..5
    }

    private fun anchorMoodScore(a: Profile.Artist): Double {
        val songs = a.songs.values
        if (songs.isEmpty()) return 1.0
        val avg = songs.take(8).map { moodFit(it, setOf(a.key)) }.average()
        return avg.coerceIn(0.1, 3.0)
    }

    private fun artistMoodAndCooldownWeight(a: Profile.Artist): Double {
        val exp = if (mood == DjMood.Energia || mood == DjMood.Chill || mood == DjMood.Foco) 2.2 else 1.5
        return anchorMoodScore(a).pow(exp) * cooldown(a) * artistRepeatWeight(a.key)
    }

    /** Lo cerca que está de la tanda lo que llevó a ella; si la citan varios tuyos, más. */
    private fun near(c: Candidate): Double {
        if (c.kind == Kind.DEEP || c.kind == Kind.FAMILIAR) return (region[c.vias.first()] ?: 0.0)
        val sum = c.vias.sumOf { region[it] ?: 0.0 }
        return sum.coerceAtMost(1.5) * if (c.vias.size >= 2) 1.3 else 1.0
    }

    private fun snapshot(): AnchorSnapshot =
        AnchorSnapshot(anchor, HashMap(region), block, blockLen, skips, jumpAway)

    private fun restoreSnapshot(s: AnchorSnapshot) {
        anchor = s.anchor
        region.clear()
        region.putAll(s.region)
        block = s.block
        blockLen = s.blockLen
        skips = s.skips
        jumpAway = s.jumpAway
        applyNudges(s.anchor.key)
    }

    private fun invalidateUpcoming() {
        if (upcoming.isEmpty()) return
        upcoming.clear()
        committedSnapshot?.let(::restoreSnapshot)
    }

    private fun buildReason(c: Candidate): String {
        c.customReason?.let { return it }
        if (c.kind == Kind.BRIDGE || c.isBridge) {
            val from = c.bridgeFrom ?: if (::anchor.isInitialized) anchor.name else ""
            val to = c.bridgeTo ?: c.target?.let { profile.artists[it]?.name ?: it } ?: ""
            if (from.isNotBlank() && to.isNotBlank()) return "Puente de $from → $to"
        }
        return when (c.kind) {
            Kind.FAMILIAR -> "Clásico tuyo"
            Kind.DEEP -> "De tus artistas"
            Kind.NEW -> "Descubrimiento"
            Kind.BRIDGE -> "Puente entre tandas"
        }
    }

    private fun commitPick(
        c: Candidate,
        isSeed: Boolean = false,
        precomputedTanda: String? = null,
        precomputedReason: String? = null,
        precomputedAdhd: AdhdSegment? = null,
    ): Track {
        pool.remove(c.track.key)
        val first = isSeed || block == 0
        val seg = precomputedAdhd ?: if (mode == DjMode.Adhd && c.track.id !in fullTrackIds) adhdSegment(c.track) else null
        val tandaLabel = precomputedTanda ?: if (::anchor.isInitialized) formatTanda(anchor) else null
        val reason = precomputedReason ?: buildReason(c)
        // Frente a la anterior: después de añadirla a [picks] se comparaba consigo misma y siempre salía «Fits».
        val tempoNote = if (precomputedReason == null) tempoVerdictOf(c.track)?.fit?.name ?: "no cuenta" else null
        val p = Pick(
            track = c.track,
            kind = c.kind,
            vias = c.vias,
            adhdSegment = seg,
            tanda = tandaLabel,
            reason = reason,
            isBridge = c.isBridge || c.kind == Kind.BRIDGE,
        )
        picks += p
        if (tandaLabel != null) trackTandas[c.track.id] = tandaLabel
        trackReasons[c.track.id] = reason
        // Para revisar luego una sesión escuchada: por qué salió cada una (tipo, motivo, género y tempo frente a la anterior).
        if (tempoNote != null) log(
            "elegida: ${c.track.artist} — ${c.track.title} (${c.kind.name.lowercase()}, $reason, género ${genreOf(c.track) ?: "?"}, tempo $tempoNote)",
        )
        keys += c.track.key
        keys += c.track.id
        if (c.track.base.length >= 4) keys += "~" + c.track.base
        memory.rememberSong(c.track, now)
        advanceWindowCounters()
        tempoSwapsSinceCommit = 0
        if (tempoSkipped.isNotEmpty()) {
            val it = tempoSkipped.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                e.setValue(e.value - 1)
                if (e.value <= 0) it.remove()
            }
        }
        pendingRemate?.let {
            it.remaining--
            if (it.remaining <= 0) pendingRemate = null
        }
        block++
        if (::anchor.isInitialized) {
            // Ya suena otra tanda: lo que las reacciones cambiaron en las anteriores no vale si se vuelve a ellas.
            nudges.removeAll { it.anchorKey != anchor.key }
            if (reactionSkips?.first != anchor.key) reactionSkips = null
            val snap = snapshot()
            pickSnapshots += snap
            committedSnapshot = snap
        }
        if (first) radio(c.track)
        return c.track
    }

    private fun advanceWindowCounters() {
        val bIt = boosts.iterator()
        while (bIt.hasNext()) {
            val b = bIt.next()
            b.remaining--
            if (b.remaining <= 0) bIt.remove()
        }
        val cIt = cooledBranches.iterator()
        while (cIt.hasNext()) {
            val cb = cIt.next()
            cb.remaining--
            if (cb.remaining <= 0) cIt.remove()
        }
        val banIt = banned.entries.iterator()
        while (banIt.hasNext()) {
            val e = banIt.next()
            e.setValue(e.value - 1)
            if (e.value <= 0) banIt.remove()
        }
    }

    private fun ok(t: Track): Boolean {
        // Enfriamiento obligatorio de canción en la sesión actual: nunca se repite una misma canción.
        if (t.id in vetoed || t.key in vetoed) return false
        if (t.id in tempoSkipped || t.key in tempoSkipped) return false
        if (t.key in keys || t.id in keys || "~" + t.base in keys) return false
        if (upcoming.any {
                val u = it.candidate.track
                u.key == t.key || u.id == t.id || (t.base.length >= 4 && u.base == t.base)
            }
        ) return false
        when (relaxLevel) {
            0 -> {
                if (t.id in profile.djRecent || t.key in profile.djRecent) return false
                if (memory.isRecent(t, now)) return false
                profile.heardAt[t.key]?.let { if (now - it < RECENTLY) return false }
            }
            1 -> {
                // Con bibliotecas muy pequeñas, solo se relaja pasado > 1 día; nunca lo oído hoy.
                if (memory.isRecent(t, now, DAY)) return false
                profile.heardAt[t.key]?.let { if (now - it < DAY) return false }
            }
            2 -> {
                // Biblioteca (o género) muy corta: vale lo de hoy, pero nunca lo de las últimas horas.
                if (memory.isRecent(t, now, RECENT_HOURS)) return false
                profile.heardAt[t.key]?.let { if (now - it < RECENT_HOURS) return false }
            }
            else -> {
                // Último recurso si no queda ninguna otra canción fuera de la sesión actual.
            }
        }
        t.seconds?.let { if (it < 60 || it > 600) return false }
        if (t.upload) return false
        return allowed(t.who)
    }

    /**
     * Sin cooldown estricto de artista: solo evita repetir exactamente el mismo artista en la canción inmediatamente
     * anterior (cuando hay alternativas) y respeta los artistas/ramas saltados en esta sesión.
     */
    private fun allowed(who: String): Boolean {
        if (who in banned && relaxLevel < 3) return false
        if (relaxLevel == 0 && effectiveLastPick()?.track?.who == who) return false
        return true
    }

    /** Penalización suave por repetición del mismo artista en la sesión, sin bloquearlo de forma estricta. */
    private fun artistRepeatWeight(who: String): Double {
        val count = picks.count { it.track.who == who } + upcoming.count { it.candidate.track.who == who }
        val recentCount = (picks.takeLast(4).map { it.track } + upcoming.takeLast(2).map { it.candidate.track })
            .takeLast(4)
            .count { it.who == who }
        return (1.0 / (1.0 + 0.45 * count)) * (1.0 / (1.0 + 0.8 * recentCount))
    }

    // ---- Reacción en vivo ----

    private fun applyFastSkip(p: Pick) {
        if (p.skipApplied) return
        p.skipApplied = true
        skips++
        val times = skippedArtists.merge(p.track.who, 1, Int::plus) ?: 1
        banned.merge(p.track.who, if (times >= 2) BAN_AGAIN else BAN_FIRST, ::maxOf)
        val vibe = effectiveVibe(p.track, p.vias)
        cooledBranches += CooldownBranch(p.track.who, p.vias, vibe, remaining = 6)

        // Enfriar los boosts que apuntaban a esta misma rama.
        boosts.removeAll { b -> b.track.who == p.track.who || b.vias.any { it in p.vias } }
        if (pendingRemate != null && (p.track.who in pendingRemate!!.vias || p.vias.any { it in pendingRemate!!.vias })) {
            pendingRemate = null
        }

        // Reducir peso de candidatos de la misma rama/vía/estilo en el pool.
        for (c in pool.values) {
            val cv = effectiveVibe(c.track, c.vias)
            when {
                c.track.who == p.track.who -> c.weight *= 0.1
                c.vias.any { it in p.vias } -> c.weight *= 0.3
                vibe.synergyWith(cv) >= 0.78 -> c.weight *= 0.55
            }
        }
        // Enfriar también las vías de la región para pivotar hacia otra dirección a partir de los nuevos huecos
        // (sin borrar de golpe las que el usuario ya ve preparadas en la cola NextUp).
        val cooled = HashMap<String, Double>()
        for (v in p.vias + p.track.who) {
            region[v]?.let { cooled[v] = (it * 0.45).coerceAtLeast(0.05) }
        }
        region.putAll(cooled)
        syncReactionIntoSnapshots(cooled, onlyLower = true)
        log("skip rápido (<20s): enfriando rama de ${p.track.artist}")
    }

    /**
     * Lleva lo que cambió una reacción (el contador de saltos y la tanda) a los estados guardados: al de la última
     * confirmada y a los de la cola preparada. Sin esto, preparar la cola o pasar a la siguiente vuelve a un estado
     * de antes de la reacción y el salto o la escucha entera no cambian nada.
     */
    private fun syncReactionIntoSnapshots(changes: Map<String, Double>, onlyLower: Boolean = false) {
        if (!::anchor.isInitialized) return
        // No se tocan los estados guardados (la cola por tempo los sigue por identidad): se apunta el ajuste y
        // [restoreSnapshot] lo aplica a cualquier estado de la misma tanda.
        reactionSkips = anchor.key to skips
        if (changes.isNotEmpty()) {
            nudges += RegionNudge(anchor.key, HashMap(changes), onlyLower)
            while (nudges.size > MAX_NUDGES) nudges.removeAt(0)
        }
    }

    private fun applyNudges(anchorKey: String) {
        reactionSkips?.let { (k, n) -> if (k == anchorKey) skips = n }
        for (n in nudges) {
            if (n.anchorKey != anchorKey) continue
            for ((k, v) in n.values) {
                val old = region[k]
                when {
                    n.lower -> if (old != null) region[k] = minOf(old, v)
                    old == null -> region[k] = v
                    else -> region[k] = maxOf(old, v)
                }
            }
        }
    }

    private fun applyBoost(p: Pick, reaction: DjReaction) {
        // Me gusta es pasajero (Iago lo usa para algo del momento, no para decir «esto es lo mío»): empuja poco,
        // menos que oírla entera, no borra los saltos seguidos, no saca al artista del apartado ni arma Remate.
        // Guardarla u oírla entera sí.
        // (Los saltos a cero se apuntan para la cola en el syncReactionIntoSnapshots de abajo.)
        if (reaction != DjReaction.Like) {
            skips = 0
            banned.remove(p.track.who)
            skippedArtists.remove(p.track.who)
        }
        val strength = when (reaction) {
            DjReaction.Save -> 3.0
            DjReaction.FullListen -> 2.1
            DjReaction.Like -> 1.2
            DjReaction.FastSkip -> 0.0
        }
        val vibe = effectiveVibe(p.track, p.vias)
        // Evitar duplicar exactamente el mismo boost si ya existe para la misma canción y reacción igual o mayor.
        val existing = boosts.firstOrNull { it.track.key == p.track.key }
        if (existing != null) {
            // Una reacción más floja (Me gusta tras Guardar) no alarga el refuerzo que ya había; una igual
            // (dos Me gusta) o más fuerte lo renueva entero.
            if (strength >= existing.strength) existing.remaining = 5
            if (strength > existing.strength) {
                boosts.remove(existing)
                boosts += Boost(p.track, p.vias, vibe, remaining = 5, strength = strength)
            }
        } else {
            boosts += Boost(p.track, p.vias, vibe, remaining = 5, strength = strength)
        }
        // Mantener viva la zona de la tanda y traer más temas afines.
        val warmed = LinkedHashMap<String, Double>()
        p.vias.forEach { warmed[it] = 0.6 }
        warmed[p.track.who] = 0.9
        for ((k, v) in warmed) region.merge(k, v, ::maxOf)
        syncReactionIntoSnapshots(warmed)
        if (p.kind != Kind.FAMILIAR) {
            follow(p.track)
            val explicitRemate = reaction == DjReaction.Save
            val targetDisc = if (mood == DjMood.Descubrir) 0.65 else 0.10
            val total = totalPicksIncludingStaged().coerceAtLeast(1)
            val actualDisc = totalDiscoveryIncludingStaged().toDouble() / total
            val ratioRemate = reaction != DjReaction.Like && mood != DjMood.Descubrir && actualDisc >= targetDisc
            if (explicitRemate || ratioRemate) {
                pendingRemate = Remate(
                    vibe = vibe,
                    vias = p.vias + p.track.who,
                    genre = genreOf(p.track),
                    sourceArtist = p.track.artist,
                    remaining = 2,
                )
            }
        }
        // Me gusta tampoco pide radio, ni con una familiar: es lo del momento, no un rumbo para la sesión.
        if (reaction != DjReaction.Like && (p.kind == Kind.FAMILIAR || reaction == DjReaction.Save)) {
            radio(p.track)
        }
        log("refuerzo (${reaction.name}): ${p.track.artist} — ${p.track.title}")
    }

    private fun boostMultiplier(track: Track, vias: Set<String>): Double {
        if (boosts.isEmpty()) return 1.0
        val cv = effectiveVibe(track, vias)
        var mult = 1.0
        for (b in boosts) {
            val artistSim = maxOf(
                if (track.who == b.track.who) 0.85 else 0.0,
                if (vias.any { it in b.vias || it == b.track.who }) 0.75 else 0.0,
                graphArtistSim(b.track.who, track.who),
                if (track.key in inRadio) 0.6 else 0.0,
            )
            val vibeSim = b.vibe.synergyWith(cv)
            val totalSim = maxOf(artistSim, vibeSim * 0.75)
            if (totalSim >= 0.45) {
                mult *= 1.0 + b.strength * totalSim * (b.remaining / 5.0)
            }
        }
        return mult.coerceAtMost(8.0)
    }

    private fun branchMultiplier(who: String, vias: Set<String>, vibe: VibeScore): Double {
        if (cooledBranches.isEmpty()) return 1.0
        var mult = 1.0
        for (cb in cooledBranches) {
            when {
                who == cb.who -> mult *= 0.15
                vias.any { it in cb.vias || it == cb.who } -> mult *= 0.35
                cb.vibe.synergyWith(vibe) >= 0.78 -> mult *= 0.55
            }
        }
        return mult.coerceAtLeast(0.05)
    }

    private fun graphArtistSim(aKey: String, bKey: String): Double {
        if (aKey == bKey) return 1.0
        val a = profile.artists[aKey] ?: return 0.0
        val b = profile.artists[bKey] ?: return 0.0
        return maxOf(profile.sim(a, b), if (links[aKey]?.contains(bKey) == true) LINKED else 0.0)
    }

    // ---- La tanda ----

    private fun setAnchor(a: Profile.Artist) {
        anchor = a
        memory.anchors[a.key] = now
        block = 0
        skips = 0
        blockLen = if (mode == DjMode.Adhd) random.nextInt(3, 6) else random.nextInt(4, 7)
        val before = region.keys.toSet()
        region.clear()
        region[a.key] = 1.0
        profile.core.asSequence()
            .filter { it !== a }
            .map { it to profile.sim(a, it) }
            .filter { it.second >= NEAR }
            .sortedByDescending { it.second }
            .take(REGION)
            .forEach { (b, s) -> region[b.key] = s }
        // Los parientes en YouTube, salvo los de la tanda anterior: si no, la nueva arrastraría lo de antes.
        links[a.key]?.forEach { if (it !in before || profile.artists[it]?.let { b -> profile.sim(a, b) >= NEAR } == true) region.merge(it, 0.5, ::maxOf) }
        // Se pide a YouTube el centro y unos pocos de alrededor, no siempre los mismos.
        explore(a)
        val around = region.keys.filter { it != a.key }.mapNotNull(profile.artists::get).toMutableList()
        repeat(3) { weighted(around) { region.getValue(it.key) * it.affinity.pow(0.5) }?.let { around -= it; explore(it) } }
    }

    /**
     * El centro de una tanda: alguien muy tuyo acorde al [mood] activo y con algo de enfriamiento frente a sesiones
     * previas. Si viene de otra tanda, prefiere continuidad en vez de saltos bruscos.
     */
    private fun chooseAnchor(
        from: Profile.Artist?,
        avoidedRegions: Set<String> = emptySet(),
        requireAvailableTrack: Boolean = false,
    ): Profile.Artist? {
        val base = profile.core.filter { it.songs.isNotEmpty() && it !== from && (from == null || allowed(it.key)) }
        val filtered = if (requireAvailableTrack) {
            base.filter { a -> a.songs.values.any(::ok) }.ifEmpty { emptyList() }
        } else base
        if (filtered.isEmpty()) return null
        val options = filtered.filter { it.songs.size >= 2 }.ifEmpty { filtered }
        // Saltar el ambiente busca justo otra cosa: ahí no se tira del género de la tanda que se deja.
        val fromGenre = if (genre == null && from != null && !jumpAway) mainGenre(from) else null
        val weight = { a: Profile.Artist ->
            a.affinity.pow(0.65) * artistMoodAndCooldownWeight(a) * branchMultiplier(a.key, setOf(a.key), a.vibe) *
                artistGenreShare(a).pow(1.5) * genreContinuity(a, fromGenre)
        }
        val avoided = if (avoidedRegions.isNotEmpty()) avoidedRegions else region.keys
        if (from != null && jumpAway) {
            // Lejos de la tanda que saltaste: ni sus artistas ni los que se le parecen.
            val away = options.filter { it.key !in avoided }
            if (away.isNotEmpty()) {
                return weighted(away) { a ->
                    weight(a) * (1.0 - profile.sim(from, a)).pow(3) * (1.0 - from.vibe.synergyWith(a.vibe)).coerceAtLeast(0.1)
                } ?: weighted(away, weight)
            }
            return weighted(options, weight)
        }
        if (from == null || random.nextDouble() < JUMP) return weighted(options, weight)
        // Con «Todo», los del mismo género que la tanda que acaba también son opción aunque no se parezcan tanto: si
        // no, solo quedaban parientes de otro estilo y el peso por género no tenía a quién favorecer.
        // Tras una racha larga en ese género, también los de otros estilos, aunque no se parezcan (si no, nunca cambiaba).
        val longStreak = fromGenre != null && genreStreak(fromGenre) >= LOOSE_EASIER_AFTER_PICKS
        val kin = options.map { it to profile.sim(from, it) }.filter { (a, s) ->
            a.key !in region && (s >= 0.03 || (fromGenre != null && (longStreak || mainGenre(a) == fromGenre)))
        }
        return weighted(kin) { (a, s) -> weight(a) * exp(-((s - 0.15) / 0.12).pow(2)) }?.first ?: weighted(options, weight)
    }

    /** Lo que fue centro hace poco tarda en volver a serlo (casi nada hoy, la mitad en tres días…). */
    private fun cooldown(a: Profile.Artist) = memory.anchors[a.key]?.let { 1 - 0.9 * exp(-(now - it).coerceAtLeast(0L).toDouble() / (5 * DAY)) } ?: 1.0

    /** Cuanto más tiempo sin oírla, mejor (hasta tres meses). Si no se sabe, como si hiciera bastante. */
    private fun forgotten(t: Track) = profile.heardAt[t.key]?.let { minOf(1.0, sqrt((now - it).coerceAtLeast(0L).toDouble() / (90 * DAY))) } ?: 0.8

    // ---- Lo que se pide fuera ----

    /** Lo de un artista tuyo: las suyas que no tienes, a quién se parece y, de ahí, artistas nuevos. */
    /**
     * Lo que se pide fuera solo trae datos: lo que cambian ([pool], [region], [cited], [links]…) se deja en [inbox] y
     * lo aplica [drain] en el hilo del DJ, antes de elegir, reaccionar o esperar. En el PC las peticiones van en otro
     * hilo que la elección y, si tocaban el estado a la vez, podían romperlo (ConcurrentModificationException).
     */
    private val inbox = ConcurrentLinkedQueue<() -> Unit>()

    private fun post(change: () -> Unit) {
        inbox += change
    }

    private fun drain() {
        while (true) (inbox.poll() ?: break).invoke()
        jobs.removeAll { !it.isActive }
    }

    private fun explore(a: Profile.Artist) {
        if (!explored.add(a.key)) return
        val known = a.id ?: memory.ids[a.key]
        jobs += scope.launch {
            val id = known ?: catalog.find(a.name)?.also { found -> post { memory.ids[a.key] = found } } ?: return@launch
            val info = catalog.artist(id) ?: return@launch
            val songs = catalog.songs(info)
            post {
                for ((rid, name) in info.related) {
                    val b = profile.find(rid, name)
                    if (b != null && b.songs.isNotEmpty()) link(a.key, b.key) else cite(rid, a.key)
                }
                songs.forEachIndexed { rank, t ->
                    if (t.who == a.key || t.artistId == id) {
                        if (t.key !in profile.weight) {
                            add(
                                Candidate(
                                    track = t,
                                    kind = Kind.DEEP,
                                    vias = setOf(a.key),
                                    weight = 1.15 / (1.0 + rank * 0.45),
                                    consensusCount = if (a.affinity >= 0.4) 2 else 1,
                                    sourceRank = rank,
                                ),
                            )
                        }
                    }
                }
                meet()
            }
        }
    }

    /** Conoce a los artistas nuevos más citados desde la tanda: sus canciones más oídas y si hacen de puente. */
    private fun meet() {
        val best = cited.values.filter { it.id !in met }
            .map { c ->
                val baseScore = c.by.sumOf { k ->
                    (region[k] ?: 0.0) * (profile.artists[k]?.affinity?.pow(0.4) ?: 1.0)
                }
                c to baseScore * (if (c.by.size >= 2) 1.75 else 1.0)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
            .filter { met.add(it.id) }
        if (best.isEmpty()) return
        jobs += scope.launch {
            val found = best.mapNotNull { c -> catalog.artist(c.id)?.let { c to it } }
            if (found.isNotEmpty()) post { meetFound(found) }
        }
    }

    private fun meetFound(found: List<Pair<Cited, ArtistInfo>>) {
        val anchorGenres = anchor.songs.values.mapNotNull(genreOf).toSet()
        for ((c, info) in found) {
            val who = artistKey(info.name)
            // Los tuyos a los que él se parece también lo citan, aunque no salga en sus páginas.
            val mine = info.related.mapNotNull { (id, n) -> profile.find(id, n)?.takeIf { it.songs.isNotEmpty() } }
            mine.forEach { c.by += it.key }
            val topTracks = info.top.filter { (it.who == who || it.artistId == c.id) && it.key !in profile.weight }.take(4)
            topTracks.forEachIndexed { rank, t ->
                val collabs = collaboratorKeys(t)
                val tGenre = genreOf(t)
                val tVibe = effectiveVibe(t, c.by)
                // Destino puente: por related de YouTube, colaboración explícita o género compartido fuerte (no vibra suelta).
                val farRelated = mine.filter { it.key !in region && it.songs.size >= 2 && profile.sim(anchor, it) < FAR }
                val farCollab = collabs.mapNotNull { profile.artists[it] }
                    .filter { it.key !in region && it.songs.isNotEmpty() && it.key != anchor.key }
                val farGenreStrong = if (farRelated.isEmpty() && farCollab.isEmpty() && tGenre != null) {
                    profile.core.filter { a ->
                        a.key !in region && a.key != anchor.key && a.songs.isNotEmpty() &&
                            a.songs.values.any { genreOf(it) == tGenre } &&
                            (anchorGenres.isEmpty() || tGenre in anchorGenres || a.vibe.synergyWith(tVibe) >= 0.65)
                    }
                } else emptyList()
                val destPool = (farCollab + farRelated + farGenreStrong).distinctBy { it.key }
                val target = weighted(destPool) { d ->
                    val collabBoost = if (d.key in collabs) 2.5 else 1.0
                    d.affinity.pow(0.7) * cooldown(d) * collabBoost * (0.4 + d.vibe.synergyWith(tVibe))
                }
                val strongBridgeLink = target != null && (
                    target.key in collabs ||
                        farRelated.any { it.key == target.key } ||
                        (tGenre != null && target.vibe.synergyWith(tVibe) >= 0.65)
                    )
                val bridge = strongBridgeLink && (rank == 0 || target.key in collabs)
                val consensusFactor = if (c.by.size >= 2) 1.45 else 0.85
                val weight = (1.15 / (1.0 + rank * 0.55)) * consensusFactor
                add(
                    Candidate(
                        track = t,
                        kind = if (bridge) Kind.BRIDGE else Kind.NEW,
                        vias = c.by,
                        weight = weight,
                        target = target?.key,
                        isBridge = bridge,
                        bridgeFrom = if (bridge) anchor.name else null,
                        bridgeTo = target?.name,
                        consensusCount = c.by.size,
                        sourceRank = rank,
                    ),
                )
            }
        }
    }

    /** La radio de lo que abre cada tanda (o de un refuerzo): suma cohesión y aporta descubrimientos cercanos. */
    private fun radio(t: Track) {
        if (!radioSeeded.add(t.id)) return
        val center = if (::anchor.isInitialized) anchor.key else t.who
        jobs += scope.launch {
            val found = catalog.radio(t.id).drop(1)
            post { radioFound(found, center) }
        }
    }

    private fun radioFound(found: List<Track>, center: String) {
        run {
            found.forEachIndexed { rank, r ->
                pool[r.key]?.let {
                    it.weight *= 1.45
                    it.consensusCount += 1
                    it.radioConfirmed = true
                    return@forEachIndexed
                }
                if (r.key in profile.weight) { inRadio += r.key; return@forEachIndexed }
                val lib = profile.find(r.artistId, r.artist)?.takeIf { it.songs.isNotEmpty() }
                val radioWeight = if (rank < 6) 0.45 / (1.0 + rank * 0.25) else 0.14
                if (lib != null) {
                    add(
                        Candidate(
                            track = r,
                            kind = Kind.DEEP,
                            vias = setOf(lib.key),
                            weight = (radioWeight * 1.15).coerceAtLeast(0.20),
                            consensusCount = 2,
                            sourceRank = rank + 2,
                            radioConfirmed = true,
                        ),
                    )
                } else {
                    add(
                        Candidate(
                            track = r,
                            kind = Kind.NEW,
                            vias = setOf(center),
                            weight = radioWeight,
                            consensusCount = 1,
                            sourceRank = rank + 2,
                            radioConfirmed = true,
                        ),
                    )
                }
            }
        }
    }

    /** Te quedaste con algo nuevo: lo suyo y sus parecidos se acercan a la tanda. */
    private fun follow(t: Track) {
        warm += t.who
        region.merge(t.who, 0.9, ::maxOf)
        val id = t.artistId ?: return
        if (!explored.add(t.who)) return
        jobs += scope.launch {
            val info = catalog.artist(id) ?: return@launch
            post {
                for ((rid, name) in info.related) {
                    val b = profile.find(rid, name)
                    if (b != null && b.songs.isNotEmpty()) region.merge(b.key, 0.5, ::maxOf) else cite(rid, t.who)
                }
                info.top.filter { it.who == t.who && it.key !in profile.weight }.forEachIndexed { rank, s ->
                    add(
                        Candidate(
                            track = s,
                            kind = Kind.NEW,
                            vias = setOf(t.who),
                            weight = 0.95 / (1.0 + rank * 0.5),
                            consensusCount = 2,
                            sourceRank = rank,
                        ),
                    )
                }
                meet()
            }
        }
    }

    private fun link(a: String, b: String) {
        links.getOrPut(a) { HashSet() } += b
        links.getOrPut(b) { HashSet() } += a
        if (a == anchor.key || b == anchor.key) region.merge(if (a == anchor.key) b else a, 0.5, ::maxOf)
    }

    private fun cite(id: String, by: String) {
        cited.getOrPut(id) { Cited(id) }.by += by
    }

    private fun add(c: Candidate) {
        // Relleno que no es una canción: cortes de sesiones de DJ, intros, karaoke, versiones para niños…
        if (c.track.id in vetoed || c.track.key in vetoed) return
        if (JUNK_TITLE.containsMatchIn(c.track.title) || JUNK_ARTIST.containsMatchIn(c.track.artist)) return
        if (c.track.key in keys || c.track.key in profile.weight || c.track.base + "|" + c.track.who in profile.bases) return
        val had = pool[c.track.key]
        if (had == null) {
            pool[c.track.key] = c
        } else {
            val mergedConsensus = maxOf(had.consensusCount, c.consensusCount) +
                if (had.vias != c.vias || (had.radioConfirmed != c.radioConfirmed)) 1 else 0
            val mergedRank = minOf(had.sourceRank, c.sourceRank)
            val mergedRadio = had.radioConfirmed || c.radioConfirmed
            val winner = if (had.weight < c.weight) c else had
            winner.consensusCount = mergedConsensus
            winner.sourceRank = mergedRank
            winner.radioConfirmed = mergedRadio
            winner.onlyVibeMatched = had.onlyVibeMatched && c.onlyVibeMatched
            pool[c.track.key] = winner
        }
    }

    // ---- Azar ----

    private inline fun <T> weighted(items: Collection<T>, w: (T) -> Double): T? {
        val ws = DoubleArray(items.size)
        var total = 0.0
        items.forEachIndexed { i, x -> ws[i] = w(x).coerceAtLeast(0.0); total += ws[i] }
        if (total <= 0) return null
        var r = random.nextDouble() * total
        items.forEachIndexed { i, x -> r -= ws[i]; if (r <= 0) return x }
        return items.last()
    }

    /**
     * Cuánto más (o menos) de [k] poner, por muestreo de Thompson: cuánto te quedas con lo de ese tipo frente a lo
     * normal para él (las tuyas siempre se oyen más que lo nuevo; no se trata de eso).
     */
    private fun lift(k: Kind): Double {
        val (s, n) = memory.arms.getValue(k).let { it[0] to it[1] }
        val base = BASE.getValue(k)
        val theta = beta(1 + PRIOR * base + s, 1 + PRIOR * (1 - base) + (n - s).coerceAtLeast(0.0))
        return (theta / base).pow(1.2)
    }

    private fun beta(a: Double, b: Double): Double {
        val x = gamma(a)
        return x / (x + gamma(b))
    }

    // Marsaglia y Tsang.
    private fun gamma(shape: Double): Double {
        if (shape < 1) return gamma(shape + 1) * random.nextDouble().pow(1 / shape)
        val d = shape - 1.0 / 3
        val c = 1 / sqrt(9 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = sqrt(-2 * ln(1 - random.nextDouble())) * cos(2 * PI * random.nextDouble())
                v = 1 + c * x
            } while (v <= 0)
            v = v * v * v
            if (ln(1 - random.nextDouble()) < 0.5 * x * x + d - d * v + d * ln(v)) return d * v
        }
    }

    companion object {
        const val FAST_SKIP_MS = 20_000L
        /** Elecciones que un artista queda fuera tras el primer salto rápido de la sesión, y tras el segundo. */
        const val BAN_FIRST = 6
        const val BAN_AGAIN = 30
        private const val MAX_NUDGES = 16

        /** Cambios por tempo como mucho por hueco de la cola; luego vuelve la original. */
        const val MAX_TEMPO_SWAPS = 3

        /** DJ rápido: cuánto de la entrante, desde donde empieza a sonar, tiene que llevar bombo para cuadrarla. */
        const val ENTRY_KICK_WINDOW_MS = 8_000L

        /** Cambios por tempo como mucho mientras suena una canción (entre todos los huecos). */
        const val MAX_TEMPO_SWAPS_PER_SONG = 4

        /** Elecciones que una canción apartada por tempo no puede volver a salir. */
        const val TEMPO_SKIP_PICKS = 8
        private val JUNK_TITLE = Regex("""\([^)]*\bmixed\b[^)]*\)|\bmix cut\b|\b(abgt|asot|gdjb)\s?\d+|\b(intro|interlude|outro|skit|karaoke)\b""", RegexOption.IGNORE_CASE)
        private val JUNK_ARTIST = Regex("""kidz bop|karaoke|tribute|rockabye baby|\b8[- ]bit\b""", RegexOption.IGNORE_CASE)
        /** La primera es siempre tuya (sin esperar a la red). */
        private const val WARM_UP = 1
        private const val WAIT_MS = 5_000L
        private const val REGION = 12
        private const val NEAR = 0.08
        private const val FAR = 0.06
        /** Cuántos cambios de tanda sin puente van a cualquier otra parte de lo tuyo. */
        private const val JUMP = 0.12
        // Con «Todo»: canciones y centros de tanda de otro género que la tanda que suena (ver [looseGenreFit]).
        // 24/11: con 0,4 se colaban canciones sueltas de otro estilo en la tanda (dubstep y EDM dentro de una de Bad
        // Bunny en DJ normal); el cambio de estilo va por los cambios de tanda.
        private const val LOOSE_OTHER_GENRE_FIT = 0.12
        private const val LOOSE_UNKNOWN_GENRE_FIT = 0.7
        // Ver [styleThenTempo]: lo que tiene que pesar una candidata frente a la mejor para que el tempo pueda elegirla.
        private const val STYLE_PLAUSIBLE = 0.35
        // Ver [looseArtistGenreFit]: un artista de la tanda de otro estilo que su centro.
        private const val LOOSE_OTHER_ARTIST_FIT = 0.15
        private const val LOOSE_OTHER_ANCHOR_FIT = 0.2
        private const val LOOSE_UNKNOWN_ANCHOR_FIT = 0.6
        // Llevando muchas seguidas del mismo género, cambiar de estilo cuesta menos (≈ 3 tandas) y luego nada (≈ 4–5).
        private const val LOOSE_EASIER_AFTER_PICKS = 9
        private const val LOOSE_FREE_AFTER_PICKS = 14
        private const val LOOSE_EASIER_ANCHOR_FIT = 0.5
        private const val SKIPPED = 0.15
        /** Lo que no tiene nada que ver con la anterior aún puede salir, pero mucho menos. */
        private const val FLOW_FLOOR = 0.04
        /** Dos tuyos que YouTube junta aunque tus listas no: se parecen al menos esto. */
        private const val LINKED = 0.3
        private const val HOOKED = 0.75
        private const val RECENTLY = 21 * DAY
        private const val RECENT_HOURS = 3 * 3_600_000L
        private const val PRIOR = 3.0
        private val SHARE_NORMAL = mapOf(Kind.FAMILIAR to 0.90, Kind.DEEP to 0.05, Kind.NEW to 0.05)
        private val SHARE_DISCOVER = mapOf(Kind.FAMILIAR to 0.35, Kind.DEEP to 0.25, Kind.NEW to 0.40)
        /** Lo que se suele escuchar de cada tipo: con eso se compara lo tuyo. */
        private val BASE = mapOf(Kind.FAMILIAR to 0.8, Kind.DEEP to 0.6, Kind.NEW to 0.5, Kind.BRIDGE to 0.5)
    }
}
