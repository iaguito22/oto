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
    Adhd("DJ rápido"),
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
    /** Si se eligió por la forma de la canción (letra sincronizada) o a ojo. */
    val fromLyrics: Boolean = false,
    /** Si se eligió o afinó con análisis real del audio (drop/intro/outro/beats). */
    val fromAudio: Boolean = false,
) {
    /**
     * El mismo trozo empezando en [positionMs] (cuando la canción ya entró por otro sitio y saltar se oiría): conserva
     * el final si da para un trozo mínimo; si no, alarga lo justo.
     */
    fun startingAt(positionMs: Long): AdhdSegment {
        val start = positionMs.coerceIn(0L, fullDurationMs)
        val end = maxOf(endMs, minOf(fullDurationMs, start + AdhdPlanner.MIN_CLIP_MS))
        val clip = (end - start).coerceAtLeast(1L)
        val mix = mixOutDurationMs.coerceAtMost(clip / 3L)
        return copy(startMs = start, endMs = end, clipDurationMs = clip, fadeOutStartMs = (end - mix).coerceAtLeast(start), mixOutDurationMs = mix)
    }
}

/**
 * Elige el trozo de cada canción en el DJ rápido (ADHD): entre ~70 y ~110 s, sin la intro.
 *
 * Con letra sincronizada se mira la forma de la canción ([SongSections]): el trozo empieza justo antes de que entre
 * la voz de un bloque (la canción sube con el fundido y la voz llega ya a su volumen) y termina cuando acaba de
 * cantarse un bloque, de modo que la mezcla de salida cae en el hueco instrumental y nunca a mitad de un estribillo.
 * Se prefiere un trozo que incluya un estribillo entero y empiece hacia el primer cuarto de la canción.
 * Sin letra, a ojo: un poco antes de un tercio de la canción, con la misma duración.
 */
object AdhdPlanner {
    const val MIN_CLIP_MS = 70_000L
    const val MAX_CLIP_MS = 110_000L
    const val TARGET_CLIP_MS = 85_000L
    const val DEFAULT_MIX_MS = 4_000L
    /** Margen tras la última palabra antes de empezar a bajar, y antes de la primera al entrar. */
    private const val TAIL_GAP_MS = 250L
    /** Las canciones así de cortas suenan enteras. */
    private const val WHOLE_SONG_MS = 100_000L

    fun segment(
        durationMs: Long,
        trackId: String = "",
        crossfadeMs: Long = DEFAULT_MIX_MS,
        mood: DjMood = DjMood.Auto,
        sections: List<SongSection> = emptyList(),
        audio: AudioMetrics? = null,
    ): AdhdSegment? {
        if (durationMs <= 0L) return null
        if (durationMs <= WHOLE_SONG_MS) {
            val mixMs = effectiveMixMs(crossfadeMs, durationMs)
            return AdhdSegment(0L, durationMs, durationMs, (durationMs - mixMs).coerceAtLeast(0L), mixMs, durationMs)
        }
        fromSections(durationMs, crossfadeMs, sections, audio)?.let { return it }
        if (audio != null && (audio.dropMs > 0L || audio.introEndMs > 0L || audio.bpmConfidence >= 0.25)) {
            return fromAudioMetrics(durationMs, crossfadeMs, mood, audio)
        }
        return guess(durationMs, trackId, crossfadeMs)
    }

    private fun fromSections(
        durationMs: Long,
        crossfadeMs: Long,
        sections: List<SongSection>,
        audio: AudioMetrics? = null,
    ): AdhdSegment? {
        if (sections.size < 3) return null
        val mix = effectiveMixMs(crossfadeMs, TARGET_CLIP_MS)
        val effectiveLimit = if (audio != null && audio.outroStartMs > MIN_CLIP_MS) minOf(durationMs, audio.outroStartMs) else durationMs
        var best: AdhdSegment? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in sections.indices) {
            val first = sections[i]
            // Que la canción vaya subiendo con el fundido y la voz llegue ya a su volumen, sin colar el final del
            // bloque anterior.
            val prevEnd = sections.getOrNull(i - 1)?.endMs ?: 0L
            val minStart = maxOf(prevEnd + TAIL_GAP_MS, audio?.introEndMs ?: 0L)
            val rawStart = maxOf(minStart, first.startMs - (mix * 0.8).toLong(), 0L)
            val start = if (audio != null && first.startMs - rawStart > 350L) {
                audio.snapBeatMs(rawStart, minMs = minStart, maxMs = first.startMs - 150L, preferDownbeat = true)
            } else {
                rawStart
            }
            if (start < durationMs * 0.12) continue
            for (j in i until sections.size) {
                val last = sections[j]
                val nextStart = sections.getOrNull(j + 1)?.startMs ?: effectiveLimit
                // El fundido de salida empieza cuando acaba la voz; la que entra tapa el resto.
                val fadeStart = minOf(last.endMs + TAIL_GAP_MS, nextStart)
                val end = minOf(fadeStart + mix, effectiveLimit)
                val clip = end - start
                if (clip < MIN_CLIP_MS) continue
                if (clip > MAX_CLIP_MS) break
                val window = sections.subList(i, j + 1)
                var score = 0.0
                if (window.any { it.isChorus }) score += 3.0
                if (last.isChorus) score += 1.0 // sale justo después de un estribillo: el mejor momento
                if (first.isChorus && i > 0 && !sections[i - 1].isChorus) score -= 0.5
                score -= 2.0 * kotlin.math.abs(start / durationMs.toDouble() - 0.27)
                score -= kotlin.math.abs(clip - TARGET_CLIP_MS) / TARGET_CLIP_MS.toDouble()
                // Un hueco de voz amplio al entrar y al salir: transiciones más limpias.
                score += 0.15 * ((first.startMs - prevEnd) / 1000.0).coerceAtMost(4.0)
                score += 0.15 * ((nextStart - last.endMs) / 1000.0).coerceAtMost(4.0)
                // Si tenemos análisis de energía real, premiamos entrar y salir por valles relajados, nunca sobre picos.
                if (audio != null && audio.energyCurve.isNotEmpty()) {
                    audio.windowEnergyStats(start, start + mix)?.let { (avgIn, maxIn) ->
                        score += ((52.0 - avgIn) / 22.0).coerceIn(-2.0, 2.0)
                        if (maxIn >= 78 && avgIn >= 60.0) score -= 1.2
                    }
                    val fadeOutStart = (end - mix).coerceAtLeast(start)
                    audio.windowEnergyStats(fadeOutStart, end)?.let { (avgOut, maxOut) ->
                        score += ((52.0 - avgOut) / 18.0).coerceIn(-2.6, 2.6)
                        if (maxOut >= 78 && avgOut >= 60.0) score -= 1.8
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    best = AdhdSegment(
                        startMs = start,
                        endMs = end,
                        clipDurationMs = clip,
                        fadeOutStartMs = (end - mix).coerceAtLeast(start),
                        mixOutDurationMs = mix,
                        fullDurationMs = durationMs,
                        fromLyrics = true,
                        fromAudio = audio != null,
                    )
                }
            }
        }
        return best
    }

    private fun fromAudioMetrics(
        durationMs: Long,
        crossfadeMs: Long,
        mood: DjMood,
        audio: AudioMetrics,
    ): AdhdSegment {
        val effectiveEnd = if (audio.outroStartMs > WHOLE_SONG_MS) minOf(durationMs, audio.outroStartMs) else durationMs
        val targetClip = when (mood) {
            DjMood.Energia -> 78_000L
            DjMood.Chill, DjMood.Foco -> 94_000L
            else -> TARGET_CLIP_MS
        }.coerceIn(MIN_CLIP_MS, MAX_CLIP_MS)
        val mixBase = effectiveMixMs(crossfadeMs, targetClip)
        val minStart = audio.introEndMs.coerceIn(0L, (effectiveEnd - MIN_CLIP_MS).coerceAtLeast(0L))
        // Entrar en la respiración suave previa al drop/estribillo para que suba de forma natural y no encima del pico.
        val preDropBreath = if (audio.energyCurve.isNotEmpty()) {
            audio.findPreDropBreathMs(
                minMs = minStart,
                maxDropMs = minOf(effectiveEnd - MIN_CLIP_MS, maxOf(audio.dropMs, (durationMs * 0.42).toLong())),
                leadMinMs = maxOf(4_000L, mixBase),
                leadMaxMs = 10_000L,
                mixWindowMs = mixBase,
            )
        } else null
        var rawStart = when {
            preDropBreath != null -> preDropBreath
            audio.dropMs > minStart + 5_000L -> (audio.dropMs - maxOf(mixBase + 1_500L, 5_500L)).coerceAtLeast(minStart)
            else -> {
                val fallbackMin = maxOf(minStart, (durationMs * 0.14).toLong())
                val fallbackMax = minOf(effectiveEnd - MIN_CLIP_MS, (durationMs * 0.36).toLong()).coerceAtLeast(fallbackMin)
                audio.findSoftTransitionStartMs(
                    minMs = fallbackMin,
                    maxMs = fallbackMax,
                    windowMs = mixBase,
                    preferLate = false,
                    preferDownbeat = true,
                ) ?: maxOf(minStart, (durationMs * 0.18).toLong())
            }
        }
        if (rawStart + MIN_CLIP_MS > effectiveEnd) {
            rawStart = (effectiveEnd - MIN_CLIP_MS).coerceAtLeast(minStart)
        }
        val maxSnapStart = maxOf(minStart, minOf(rawStart + 1_200L, effectiveEnd - MIN_CLIP_MS))
        val start = audio.snapBeatMs(rawStart, minMs = minStart, maxMs = maxSnapStart, preferDownbeat = true)

        // Salida: buscar un valle/puente de baja energía dentro de [70s, 110s] en vez de cortar encima de un estribillo.
        val minEnd = minOf(effectiveEnd, start + MIN_CLIP_MS)
        val maxEnd = minOf(effectiveEnd, start + MAX_CLIP_MS)
        val softFadeStart = if (audio.energyCurve.isNotEmpty() && maxEnd - minEnd >= 4_000L) {
            val fadeSearchMin = (minEnd - mixBase).coerceAtLeast(start)
            val fadeSearchMax = (maxEnd - mixBase).coerceAtLeast(fadeSearchMin)
            audio.findSoftTransitionStartMs(
                minMs = fadeSearchMin,
                maxMs = fadeSearchMax,
                windowMs = mixBase,
                preferLate = false,
                preferDownbeat = false,
            )
        } else null

        val rawEnd = if (softFadeStart != null) {
            (softFadeStart + mixBase).coerceIn(minEnd, maxEnd)
        } else {
            minOf(effectiveEnd, start + targetClip)
        }
        val end = if (softFadeStart == null && rawEnd - start >= MIN_CLIP_MS + 1_500L) {
            audio.snapBeatMs(
                targetMs = rawEnd,
                minMs = maxOf(start + MIN_CLIP_MS, rawEnd - 1_500L),
                maxMs = effectiveEnd,
                preferDownbeat = false,
            )
        } else {
            rawEnd.coerceAtLeast(minEnd)
        }
        val clip = (end - start).coerceAtLeast(1L)
        val mix = effectiveMixMs(crossfadeMs, clip)
        return AdhdSegment(
            startMs = start,
            endMs = end,
            clipDurationMs = clip,
            fadeOutStartMs = (end - mix).coerceAtLeast(start),
            mixOutDurationMs = mix,
            fullDurationMs = durationMs,
            fromLyrics = false,
            fromAudio = true,
        )
    }

    private fun guess(durationMs: Long, trackId: String, crossfadeMs: Long): AdhdSegment {
        val jitter = deterministicUnit(trackId)
        val clip = (TARGET_CLIP_MS - 10_000L + (jitter * 20_000.0).toLong()).coerceIn(MIN_CLIP_MS, MAX_CLIP_MS)
        // Un poco antes de un tercio: suele caer al principio de la segunda estrofa o del primer estribillo.
        var start = (durationMs * (0.22 + 0.06 * jitter)).toLong()
        if (durationMs > 300_000L) start = minOf(start, 100_000L)
        if (start + clip > durationMs) start = (durationMs - clip).coerceAtLeast((durationMs * 0.1).toLong())
        val end = minOf(start + clip, durationMs)
        val actual = (end - start).coerceAtLeast(1L)
        val mix = effectiveMixMs(crossfadeMs, actual)
        return AdhdSegment(start, end, actual, (end - mix).coerceAtLeast(start), mix, durationMs)
    }

    private fun effectiveMixMs(crossfadeMs: Long, clipDurationMs: Long): Long {
        val base = if (crossfadeMs > 0L) crossfadeMs else DEFAULT_MIX_MS
        val maxMix = (clipDurationMs / 3L).coerceAtLeast(1_000L)
        return base.coerceIn(minOf(2_000L, maxMix), minOf(12_000L, maxMix))
    }

    private fun deterministicUnit(seed: String): Double {
        if (seed.isEmpty()) return 0.5
        val h = seed.hashCode().toLong() and 0x7FFF_FFFFL
        return (h % 1000L) / 999.0
    }
}

/** Calcula el segmento ADHD de una pista usando [durationMs] o [Track.seconds], su letra y/o su análisis de audio. */
fun Track.adhdSegment(
    durationMs: Long? = null,
    crossfadeMs: Long = AdhdPlanner.DEFAULT_MIX_MS,
    mood: DjMood = DjMood.Auto,
    sections: List<SongSection> = emptyList(),
    audio: AudioMetrics? = null,
): AdhdSegment? {
    val totalMs = durationMs ?: seconds?.let { it * 1000L } ?: audio?.durationMs?.takeIf { it > 0L } ?: return null
    return AdhdPlanner.segment(totalMs, id, crossfadeMs, mood, sections, audio)
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
