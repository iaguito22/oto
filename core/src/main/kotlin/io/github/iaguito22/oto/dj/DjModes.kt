package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.importer.Matcher
import kotlin.math.abs

/** Mood del DJ para adaptar energía, continuidad y apertura al descubrimiento. */
enum class DjMood(val label: String) {
    Auto("Auto"),
    Energia("Energía"),
    Chill("Chill"),
    Foco("Foco"),
    Descubrir("Descubrir"),
}

/** Modo de sesión del DJ: reproducción normal de canciones enteras o segmentos cortos (ADHD). */
enum class DjMode(val label: String) {
    Normal("Normal"),
    Adhd("DJ rápido (ADHD)"),
}

/** Reacciones en vivo del usuario mientras suena el DJ. */
enum class DjReaction {
    /** Salto rápido (< 20 s): enfría esta rama y dirección en la sesión. */
    FastSkip,
    /** Escucha completa de la canción o del segmento ADHD: refuerza similares. */
    FullListen,
    /** Me gusta en vivo: refuerza canciones similares durante las siguientes 4-5 pistas. */
    Like,
    /** Guardado en una lista: refuerza canciones similares durante las siguientes 4-5 pistas. */
    Save,
}

/** Metadatos de reproducción para una pista en modo DJ rápido (ADHD). */
data class AdhdSegment(
    /** Punto de inicio recomendado dentro de la pista (ms), saltando la intro. */
    val startMs: Long,
    /** Fin del segmento corto (ms). */
    val endMs: Long,
    /** Duración efectiva del segmento (`endMs - startMs`). */
    val clipDurationMs: Long,
    /** Instante de la pista (ms) donde arranca la mezcla/fundido de salida. */
    val fadeOutStartMs: Long,
    /** Duración de la mezcla/fundido de salida (ms). */
    val mixOutDurationMs: Long,
    /** Duración total de la pista usada para calcular el segmento (ms). */
    val fullDurationMs: Long,
)

/** Calcula segmentos cortos (~45-75 s) saltando la intro (~25-35 %) para el modo DJ rápido (ADHD). */
object AdhdPlanner {
    const val MIN_CLIP_MS = 45_000L
    const val MAX_CLIP_MS = 75_000L
    const val DEFAULT_MIX_MS = 4_000L

    fun segment(
        durationMs: Long,
        trackId: String = "",
        crossfadeMs: Long = DEFAULT_MIX_MS,
        mood: DjMood = DjMood.Auto,
    ): AdhdSegment? {
        if (durationMs <= 0L) return null
        // Pistas muy cortas: no tiene sentido recortar por debajo de su duración natural.
        if (durationMs <= 50_000L) {
            val mixMs = effectiveMixMs(crossfadeMs, durationMs)
            val fadeStart = (durationMs - mixMs).coerceAtLeast(0L)
            return AdhdSegment(0L, durationMs, durationMs, fadeStart, mixMs, durationMs)
        }

        val jitter = deterministicUnit(trackId)
        val skipRatio = when (mood) {
            DjMood.Energia -> 0.29 + 0.06 * jitter
            DjMood.Chill, DjMood.Foco -> 0.25 + 0.04 * jitter
            DjMood.Auto, DjMood.Descubrir -> 0.26 + 0.07 * jitter
        }
        val targetClip = when (mood) {
            DjMood.Energia -> 48_000L + (jitter * 10_000.0).toLong()
            DjMood.Chill, DjMood.Foco -> 60_000L + (jitter * 14_000.0).toLong()
            DjMood.Auto, DjMood.Descubrir -> 52_000L + (jitter * 14_000.0).toLong()
        }.coerceIn(MIN_CLIP_MS, MAX_CLIP_MS)

        // En temas largos (> 5 min), no saltar más allá de ~110 s para no pasar el primer clímax.
        val rawStart = (durationMs * skipRatio).toLong().let {
            if (durationMs > 300_000L) minOf(it, 110_000L) else it
        }

        val minFloorStart = (durationMs * 0.15).toLong()
        var startMs = rawStart
        var clipMs = targetClip
        if (startMs + clipMs > durationMs) {
            startMs = (durationMs - clipMs).coerceAtLeast(minFloorStart)
            if (startMs + clipMs > durationMs) {
                clipMs = (durationMs - startMs).coerceAtLeast(minOf(MIN_CLIP_MS, durationMs))
                startMs = (durationMs - clipMs).coerceAtLeast(0L)
            }
        }
        val endMs = (startMs + clipMs).coerceAtMost(durationMs)
        val actualClip = (endMs - startMs).coerceAtLeast(1L)
        val mixMs = effectiveMixMs(crossfadeMs, actualClip)
        val fadeStart = (endMs - mixMs).coerceAtLeast(startMs)

        return AdhdSegment(
            startMs = startMs,
            endMs = endMs,
            clipDurationMs = actualClip,
            fadeOutStartMs = fadeStart,
            mixOutDurationMs = mixMs,
            fullDurationMs = durationMs,
        )
    }

    private fun effectiveMixMs(crossfadeMs: Long, clipDurationMs: Long): Long {
        val base = if (crossfadeMs > 0L) crossfadeMs else DEFAULT_MIX_MS
        val maxMix = (clipDurationMs / 3L).coerceAtLeast(1_000L)
        return base.coerceIn(minOf(2_000L, maxMix), minOf(10_000L, maxMix))
    }

    private fun deterministicUnit(seed: String): Double {
        if (seed.isEmpty()) return 0.5
        val h = seed.hashCode().toLong() and 0x7FFF_FFFFL
        return (h % 1000L) / 999.0
    }
}

/** Calcula el segmento ADHD de una pista usando [durationMs] o [Track.seconds]. */
fun Track.adhdSegment(
    durationMs: Long? = null,
    crossfadeMs: Long = AdhdPlanner.DEFAULT_MIX_MS,
    mood: DjMood = DjMood.Auto,
): AdhdSegment? {
    val totalMs = durationMs ?: seconds?.let { it * 1000L } ?: return null
    return AdhdPlanner.segment(totalMs, id, crossfadeMs, mood)
}

/** Perfil heurístico de energía, calma, foco y etiquetas de escena/género (0.0 a 1.0). */
data class VibeScore(
    val energy: Double = 0.5,
    val chill: Double = 0.5,
    val focus: Double = 0.5,
    val tags: Set<String> = emptySet(),
) {
    /** Similitud de ambiente/género entre dos pistas (0.0 a 1.0). */
    fun synergyWith(other: VibeScore): Double {
        val dist = (abs(energy - other.energy) + abs(chill - other.chill) + abs(focus - other.focus)) / 3.0
        val closeness = (1.0 - dist).coerceIn(0.0, 1.0)
        val sharedTags = if (tags.isNotEmpty() && other.tags.isNotEmpty()) {
            val inter = tags.intersect(other.tags).size
            val union = tags.union(other.tags).size
            if (union > 0) inter.toDouble() / union else 0.0
        } else 0.0
        return (0.65 * closeness + 0.35 * sharedTags).coerceIn(0.0, 1.0)
    }

    fun blend(other: VibeScore, weightOther: Double): VibeScore {
        val w = weightOther.coerceIn(0.0, 1.0)
        val keep = 1.0 - w
        return VibeScore(
            energy = (energy * keep + other.energy * w).coerceIn(0.0, 1.0),
            chill = (chill * keep + other.chill * w).coerceIn(0.0, 1.0),
            focus = (focus * keep + other.focus * w).coerceIn(0.0, 1.0),
            tags = tags + other.tags,
        )
    }
}

internal object VibeAnalyzer {
    private val ENERGY_WORDS = setOf(
        "gym", "gimnasio", "workout", "entreno", "cardio", "run", "running", "correr",
        "fiesta", "party", "club", "dance", "baila", "perreo", "reggaeton", "regueton",
        "trap", "phonk", "hardstyle", "techno", "edm", "house", "rock", "metal", "punk",
        "hyper", "hype", "energia", "energy", "power", "pump", "remix", "sped", "nightcore",
        "festival", "rave", "bass", "banger", "motiva", "motivacion", "sprint", "upbeat",
    )

    private val CHILL_WORDS = setOf(
        "chill", "relax", "calm", "calma", "noche", "night", "madrugada", "sleep", "dormir",
        "suave", "soft", "acoustic", "acustico", "acustica", "slowed", "reverb", "ballad",
        "balada", "sad", "triste", "melancolia", "lluvia", "rain", "sunset", "atardecer",
        "dream", "jazz", "soul", "bossa", "folk", "unplugged", "lento", "tranqui", "lofi",
    )

    private val FOCUS_WORDS = setOf(
        "focus", "foco", "study", "estudio", "estudiar", "trabajar", "work", "coding",
        "programar", "lofi", "instrumental", "ambient", "ost", "soundtrack", "bso",
        "classical", "clasica", "piano", "synthwave", "reading", "leer", "meditacion",
        "zen", "concentration", "concentracion", "orchestral", "score",
    )

    private val GENRE_TAGS = mapOf(
        "rock" to "rock", "metal" to "metal", "punk" to "rock", "indie" to "indie",
        "pop" to "pop", "kpop" to "kpop", "jpop" to "jpop", "anime" to "anime",
        "ost" to "ost", "soundtrack" to "ost", "bso" to "ost", "score" to "ost",
        "techno" to "electronic", "house" to "electronic", "edm" to "electronic",
        "electronic" to "electronic", "electronica" to "electronic", "synthwave" to "synthwave",
        "phonk" to "phonk", "trap" to "urban", "rap" to "urban", "hip" to "urban",
        "reggaeton" to "latin", "regueton" to "latin", "latin" to "latin", "latino" to "latin",
        "salsa" to "latin", "bachata" to "latin", "lofi" to "lofi", "ambient" to "ambient",
        "jazz" to "jazz", "soul" to "soul", "rnb" to "rnb", "folk" to "folk",
        "acoustic" to "acoustic", "acustico" to "acoustic", "piano" to "instrumental",
        "instrumental" to "instrumental", "classical" to "classical", "clasica" to "classical",
        "chill" to "chill", "gym" to "workout", "workout" to "workout", "fiesta" to "party",
    )

    fun fromText(text: String, durationSeconds: Int? = null): VibeScore {
        if (text.isBlank() && durationSeconds == null) return VibeScore()
        val tokens = Matcher.norm(text).split(' ').filter { it.isNotEmpty() }
        var energy = 0.5
        var chill = 0.5
        var focus = 0.45
        val tags = HashSet<String>()

        var eHits = 0
        var cHits = 0
        var fHits = 0
        for (tok in tokens) {
            if (tok in ENERGY_WORDS) eHits++
            if (tok in CHILL_WORDS) cHits++
            if (tok in FOCUS_WORDS) fHits++
            GENRE_TAGS[tok]?.let { tags += it }
        }
        if (eHits > 0) {
            energy = (energy + 0.22 * minOf(eHits, 2)).coerceAtMost(0.98)
            chill = (chill - 0.16 * minOf(eHits, 2)).coerceAtLeast(0.05)
        }
        if (cHits > 0) {
            chill = (chill + 0.22 * minOf(cHits, 2)).coerceAtMost(0.98)
            energy = (energy - 0.16 * minOf(cHits, 2)).coerceAtLeast(0.05)
        }
        if (fHits > 0) {
            focus = (focus + 0.25 * minOf(fHits, 2)).coerceAtMost(0.98)
            if (eHits == 0) energy = (energy - 0.08).coerceAtLeast(0.15)
        }
        if (durationSeconds != null) {
            when {
                durationSeconds in 90..175 -> energy = (energy + 0.05).coerceAtMost(0.98)
                durationSeconds >= 280 -> {
                    chill = (chill + 0.05).coerceAtMost(0.98)
                    focus = (focus + 0.05).coerceAtMost(0.98)
                }
            }
        }
        return VibeScore(energy, chill, focus, tags)
    }
}
