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
    /**
     * Dónde empieza de verdad la parte a la que se entra (el compás de la sección): la mezcla de entrada debería
     * acabar aquí, con la canción ya a su volumen. Igual a [startMs] si no se conoce.
     */
    val entrySectionMs: Long = startMs,
    /** Límites de frase de la canción ([PhraseMap]) para recolocar entradas y salidas sin caer a mitad de frase. */
    val phrasePointsMs: List<Long> = emptyList(),
    /** Si el inicio cae en un límite de frase. */
    val startOnPhrase: Boolean = false,
    /** Si el fundido de salida empieza en un límite de frase. */
    val exitOnPhrase: Boolean = false,
    /** Cuánto de la canción cubría el análisis de audio usado (0 sin análisis): para rehacer la salida si llega uno mejor. */
    val audioCoverageMs: Long = 0L,
    /** El PCM decodificado aporta tiempos de fuente. Una curva de la escucha en vivo puede cubrir lo mismo sin ellos. */
    val audioDecoded: Boolean = false,
    /** Se eligió una salida cercana al trozo porque conservar la anterior ocupaba el resto de la pista. */
    val fallbackExit: Boolean = false,
    /** Último recurso del DJ rápido cuando ni el fundido ni una respiración caben antes del final. */
    val forcedPhraseCutMs: Long? = null,
) {
    val minimumClipMs: Long get() = if (fallbackExit) AdhdPlanner.FALLBACK_MIN_CLIP_MS else AdhdPlanner.MIN_CLIP_MS
    /**
     * Si con esta letra/análisis el trozo puede salir mejor que como se calculó: llegó letra con forma (y no se usó) o
     * un análisis que cubre bastante más canción (aunque ya hubiera letra: los compases medidos afinan la salida).
     */
    fun improvesWith(sections: List<SongSection>, audio: AudioMetrics?): Boolean {
        if (!fromLyrics && sections.size >= 3) return true
        return hasBetterAudio(audio)
    }

    fun hasBetterAudio(audio: AudioMetrics?): Boolean {
        if (audio == null) return false
        val cov = AdhdPlanner.audioCoverageMs(audio, fullDurationMs)
        if (audio.analyzedMs <= 0L && audioDecoded) return false
        if (audio.analyzedMs > 0L && !audioDecoded && cov >= audioCoverageMs) return true
        if (cov <= audioCoverageMs) return false
        return cov >= fullDurationMs || cov - audioCoverageMs >= maxOf(15_000L, audioCoverageMs / 4L)
    }

    /**
     * El primer límite de frase en `[ms, ms + maxWaitMs]`, o [ms] si no hay ninguno tan cerca: para que una salida
     * forzada (sin plan de mezcla) empiece en frase y no a mitad de compás.
     */
    fun phraseExitAt(ms: Long, maxWaitMs: Long = PHRASE_WAIT_MS): Long =
        phrasePointsMs.firstOrNull { it in ms..(ms + maxWaitMs) && it < fullDurationMs } ?: ms

    /**
     * El mismo trozo empezando en [positionMs] (cuando la canción ya entró por otro sitio y saltar se oiría): conserva
     * el final si da para un trozo mínimo; si no, alarga hasta el primer límite de frase que lo dé.
     */
    fun startingAt(positionMs: Long): AdhdSegment {
        val start = positionMs.coerceIn(0L, fullDurationMs)
        val minimumRemaining = minimumClipMs - if (fallbackExit) 5_000L else 0L
        val keep = endMs >= start + minimumRemaining
        val mixKeep = mixOutDurationMs
        val fade = if (keep) fadeOutStartMs else phraseExitAt(start + AdhdPlanner.MIN_CLIP_MS - mixKeep)
        val end = if (keep) endMs else minOf(fullDurationMs, fade + mixKeep)
        val clip = (end - start).coerceAtLeast(1L)
        val mix = mixKeep.coerceAtMost(clip / 3L)
        return copy(
            startMs = start, endMs = end, clipDurationMs = clip,
            fadeOutStartMs = fade.coerceIn(start, (end - mix).coerceAtLeast(start)), mixOutDurationMs = mix,
            entrySectionMs = start, startOnPhrase = false,
            exitOnPhrase = keep && exitOnPhrase || (!keep && fade in phrasePointsMs),
            fallbackExit = keep && fallbackExit,
            forcedPhraseCutMs = forcedPhraseCutMs.takeIf { keep },
        )
    }

    /**
     * Objetivo FIJO de una salida forzada (sin mezcla, o porque la mezcla prevista no llegó a hacerse), a partir del
     * final previsto [plannedEndMs] (nunca de la posición: si no, cada lectura pasada de un límite buscaría el siguiente).
     * Busca una salida limpia. Un trozo que tuvo que reservar un corte de respaldo puede usar el límite de una línea.
     */
    fun forcedExitTargetMs(
        plannedEndMs: Long,
        sections: List<SongSection>,
        durationMs: Long = fullDurationMs,
        guardMs: Long = FORCED_GUARD_MS,
        /** La bajada va antes del objetivo (Android: baja y salta en él). Ver [SongSections.safeExitAt]. */
        duckBefore: Boolean = false,
    ): Long {
        val chorusSafe = SongSections.safeExitAt(plannedEndMs, sections, durationMs, guardMs, duckBefore)
        if (durationMs > AdhdPlanner.WHOLE_SONG_MS &&
            (chorusSafe == durationMs || chorusSafe - plannedEndMs > AdhdPlanner.FALLBACK_EXIT_WAIT_MS)
        ) {
            val last = minOf(durationMs - AdhdPlanner.FALLBACK_END_MARGIN_MS, plannedEndMs + AdhdPlanner.FALLBACK_EXIT_WAIT_MS)
            SongSections.rapidBreaths(sections).firstNotNullOfOrNull { gap ->
                val at = maxOf(plannedEndMs, gap.first + if (duckBefore) guardMs else 0L)
                val end = at + if (duckBefore) 0L else guardMs
                at.takeIf { it <= last && end <= gap.last - AdhdPlanner.FALLBACK_EXIT_TOLERANCE_MS &&
                    allowsForcedExitAt(it, sections, durationMs, guardMs, duckBefore) }
            }?.let { return it }
        }
        forcedPhraseCutMs?.let { cut ->
            val from = maxOf(cut, plannedEndMs).coerceAtMost(durationMs)
            if (chorusSafe > from + AdhdPlanner.PHRASE_CUT_LEAD_MS || chorusSafe == durationMs) {
                if (from == cut) return cut
                val to = minOf(durationMs - AdhdPlanner.FALLBACK_END_MARGIN_MS, from + AdhdPlanner.FALLBACK_EXIT_WAIT_MS)
                return AdhdPlanner.phraseCutIn(sections, phrasePointsMs, from, to, from) ?: from
            }
            if (from == cut) return maxOf(chorusSafe, cut)
        }
        val onPhrase = phraseExitAt(chorusSafe)
        return if (onPhrase < durationMs && SongSections.safeExitAt(onPhrase, sections, durationMs, guardMs, duckBefore) == onPhrase) onPhrase else chorusSafe
    }

    fun allowsForcedExitAt(
        atMs: Long,
        sections: List<SongSection>,
        durationMs: Long = fullDurationMs,
        guardMs: Long = FORCED_GUARD_MS,
        duckBefore: Boolean = false,
    ): Boolean {
        if (atMs !in 0L..durationMs) return false
        if (SongSections.safeExitAt(atMs, sections, durationMs, guardMs, duckBefore) == atMs) return true
        val start = atMs - if (duckBefore) guardMs else 0L
        val end = atMs + if (duckBefore) 0L else guardMs
        return SongSections.allowsRapidBreath(start, end, sections) ||
            forcedPhraseCutMs?.let { atMs >= it - AdhdPlanner.FALLBACK_EXIT_TOLERANCE_MS } == true
    }

    /** Si ya toca la salida forzada: pasado el objetivo (también tras buscar), en cuanto no se esté en un estribillo. */
    fun forcedExitDue(
        positionMs: Long,
        plannedEndMs: Long,
        sections: List<SongSection>,
        durationMs: Long = fullDurationMs,
        guardMs: Long = FORCED_GUARD_MS,
    ): Boolean = positionMs >= forcedExitTargetMs(plannedEndMs, sections, durationMs, guardMs) &&
        allowsForcedExitAt(positionMs, sections, durationMs, guardMs)

    private companion object {
        const val PHRASE_WAIT_MS = 9_000L
        const val FORCED_GUARD_MS = 260L
    }
}

/**
 * Elige el trozo de cada canción en el DJ rápido (ADHD): entre ~70 y ~110 s, que suene a una canción corta con su
 * principio y su final.
 *
 * - Entra en un límite de frase ([PhraseMap]) unos compases antes de una sección, con al menos la duración de la mezcla
 *   de margen: la canción ya está arriba cuando llega la voz. Mejor si esa sección lleva a un estribillo.
 * - Incluye un estribillo entero si la canción lo tiene a su alcance.
 * - Sale en el límite de frase justo después de acabar un bloque cantado (mejor tras un estribillo), y el fundido de
 *   salida no puede comerse la voz del bloque siguiente.
 *
 * Sin letra, por el análisis (subida/drop y valles de energía) llevado a límites de frase; sin nada, a ojo.
 * [fixedStartMs]: la canción ya está sonando desde ahí (solo se elige el final). [minFadeStartMs]: el fundido de
 * salida no puede empezar antes (la que suena solo puede alargarse hacia delante).
 */
object AdhdPlanner {
    const val MIN_CLIP_MS = 70_000L
    const val MAX_CLIP_MS = 110_000L
    const val TARGET_CLIP_MS = 85_000L
    const val DEFAULT_MIX_MS = 4_000L
    /** La canción que suena solo puede mover su salida hasta aquí por delante de donde va (preparar la mezcla). */
    const val LIVE_EXIT_LEAD_MS = 20_000L
    /** Margen tras la última palabra antes de empezar a bajar, y antes de la primera al entrar. */
    private const val TAIL_GAP_MS = 250L
    /** Las canciones así de cortas suenan enteras. */
    internal const val WHOLE_SONG_MS = 100_000L
    internal const val FALLBACK_EXIT_WAIT_MS = 12_000L
    internal const val FALLBACK_END_MARGIN_MS = 2_000L
    internal const val FALLBACK_EXIT_TOLERANCE_MS = 150L
    internal const val FALLBACK_MIN_CLIP_MS = 55_000L
    /** Reserva antes del comienzo de una línea para acabar la bajada sobre la frase saliente. */
    internal const val PHRASE_CUT_LEAD_MS = 400L
    private const val FALLBACK_DUCK_MS = 400L
    private const val FALLBACK_MIN_MIX_MS = 2_000L
    /** Hasta dónde se busca el compás de salida tras la última palabra. */
    private const val MAX_EXIT_WAIT_MS = 9_000L
    /** Una entrada más larga que esto antes de la voz ya no es «unos compases antes». */
    private const val MAX_LEAD_MS = 17_000L

    /** Margen de preparación al recolocar la salida. Si antes acababa en seco al final, aún cabe rescatar un trozo. */
    fun liveMinFadeStartMs(current: AdhdSegment, positionMs: Long): Long =
        maxOf(positionMs + LIVE_EXIT_LEAD_MS, if (current.canEscapeEnd()) current.startMs else current.fadeOutStartMs)

    /** La posición de entrada no cambia. Una salida de respaldo puede quedar antes del final que se iba a usar. */
    fun isLiveExitUpdate(current: AdhdSegment, fresh: AdhdSegment, positionMs: Long): Boolean {
        val escapesEnd = (current.canEscapeEnd() || fresh.fallbackExit) &&
            fresh.endMs <= current.fullDurationMs - FALLBACK_END_MARGIN_MS
        val earliestEnd = if (escapesEnd) fresh.fadeOutStartMs else maxOf(current.endMs, fresh.fadeOutStartMs)
        return fresh.startMs == current.startMs &&
            fresh.fadeOutStartMs >= liveMinFadeStartMs(current, positionMs) &&
            fresh.endMs in earliestEnd..current.fullDurationMs &&
            (fresh.fadeOutStartMs != current.fadeOutStartMs || fresh.endMs != current.endMs)
    }

    private fun AdhdSegment.canEscapeEnd(): Boolean =
        fullDurationMs > WHOLE_SONG_MS && endMs == fullDurationMs && mixOutDurationMs == 0L

    /** Conserva el ancla de entrada original al aceptar una salida nueva durante la reproducción. */
    fun liveExitUpdate(current: AdhdSegment, fresh: AdhdSegment, positionMs: Long): AdhdSegment? =
        fresh.takeIf { isLiveExitUpdate(current, it, positionMs) }?.copy(
            entrySectionMs = current.entrySectionMs,
            startOnPhrase = current.startOnPhrase,
        )

    fun segment(
        durationMs: Long,
        trackId: String = "",
        crossfadeMs: Long = DEFAULT_MIX_MS,
        mood: DjMood = DjMood.Auto,
        sections: List<SongSection> = emptyList(),
        audio: AudioMetrics? = null,
        fixedStartMs: Long? = null,
        minFadeStartMs: Long = 0L,
    ): AdhdSegment? {
        if (durationMs <= 0L) return null
        val fixed = fixedStartMs?.coerceIn(0L, durationMs)
        if (durationMs <= WHOLE_SONG_MS) {
            val start = fixed ?: 0L
            val mixMs = effectiveMixMs(crossfadeMs, durationMs - start).coerceAtMost(durationMs - start)
            val fade = SongSections.safeExitAt((durationMs - mixMs).coerceAtLeast(start), sections, durationMs, mixMs)
            return AdhdSegment(start, durationMs, (durationMs - start).coerceAtLeast(1L), fade, durationMs - fade, durationMs,
                audioCoverageMs = audioCoverageMs(audio, durationMs), audioDecoded = (audio?.analyzedMs ?: 0L) > 0L)
        }
        val phrases = PhraseMap.of(durationMs, sections, audio)
        // Una letra que no cae donde el audio tiene voz (desplazada) no elige el trozo; los estribillos los sigue
        // protegiendo quien planifica la mezcla con las secciones originales.
        val shape = if (PhraseMap.lyricsUsable(durationMs, sections, audio)) sections else emptyList()
        val byLyrics = fromSections(durationMs, crossfadeMs, shape, audio, phrases, fixed, minFadeStartMs)
        val seg = byLyrics ?: run {
            val byAudio = if (audio != null && (audio.dropMs > 0L || audio.introEndMs > 0L ||
                    audio.bpmConfidence >= 0.25 || audio.energyCurve.isNotEmpty() || phrases.points.isNotEmpty())) {
                fromAudioMetrics(durationMs, crossfadeMs, mood, audio, phrases, fixed, minFadeStartMs)
            } else null
            val fallback = byAudio ?: guess(durationMs, trackId, crossfadeMs, fixed, minFadeStartMs)
            protectFallbackChoruses(fallback, shape, phrases, fixed, minFadeStartMs)
        }
        return seg.copy(phrasePointsMs = phrases.times, audioCoverageMs = audioCoverageMs(audio, durationMs),
            audioDecoded = (audio?.analyzedMs ?: 0L) > 0L)
    }

    /** Cobertura que se compara al decidir si merece la pena recalcular un trozo de esta duración. */
    fun audioCoverageMs(audio: AudioMetrics?, durationMs: Long): Long = when {
        audio?.coversWholeSong == true -> durationMs
        else -> PhraseMap.measuredUntil(durationMs, audio)
    }

    private data class StartOption(
        val startMs: Long,
        val sectionMs: Long,
        val sectionIndex: Int,
        val onPhrase: Boolean,
        /** Castigo si la voz llega antes de que acabe de subir la canción o si se cuela la cola del bloque anterior. */
        val penalty: Double,
    )

    private fun fromSections(
        durationMs: Long,
        crossfadeMs: Long,
        sections: List<SongSection>,
        audio: AudioMetrics?,
        phrases: PhraseMap,
        fixedStart: Long?,
        minFadeStart: Long,
    ): AdhdSegment? {
        if (sections.size < 3) return null
        val mix = effectiveMixMs(crossfadeMs, TARGET_CLIP_MS)
        val limit = audio?.lastSoundMs?.takeIf { audio.coversWholeSong && it > MIN_CLIP_MS && it <= durationMs } ?: durationMs
        val measuredEnd = PhraseMap.measuredUntil(durationMs, audio)
        val firstSound = audio?.firstSoundMs?.coerceIn(0L, 8_000L) ?: 0L
        val hasChorus = sections.any { it.isChorus }

        val starts = ArrayList<StartOption>()
        if (fixedStart != null) {
            val idx = sections.indexOfFirst { it.endMs > fixedStart }
            if (idx < 0) return null
            starts += StartOption(fixedStart, fixedStart, idx, fixedStart in phrases.times, 0.0)
        } else {
            // La voz tiene que llegar con la canción ya arriba: al menos la mezcla de entrada (+ un respiro) antes.
            val leadNeed = mix + 300L
            for ((i, s) in sections.withIndex()) {
                val prevEnd = sections.getOrNull(i - 1)?.endMs
                val minStart = maxOf(firstSound, prevEnd?.plus(TAIL_GAP_MS) ?: 0L)
                val sectionPoint = phrases.points.firstOrNull { it.kind == PhraseKind.SectionStart && it.sectionIndex == i }?.ms ?: s.startMs
                val latest = s.startMs - leadNeed
                val onPhrase = phrases.lastIn(maxOf(minStart, s.startMs - MAX_LEAD_MS), latest)
                if (onPhrase != null) {
                    starts += StartOption(onPhrase.ms, sectionPoint, i, true, 0.0)
                    continue
                }
                if (s.startMs - minStart < 1_000L) continue // sin hueco: se entraría con voz
                val raw = maxOf(minStart, latest)
                val start = audio.snapMeasuredBeat(
                    raw, minStart, minOf(s.startMs - 150L, maxOf(raw, latest)),
                    measuredEnd, preferDownbeat = true,
                )
                // La voz llega antes de que la entrada acabe de subir: cuanto más, peor.
                val short = ((leadNeed - (s.startMs - start)).coerceAtLeast(0L)).toDouble() / leadNeed
                starts += StartOption(start, sectionPoint, i, false, 1.6 * short)
            }
        }

        var best: AdhdSegment? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (opt in starts) {
            val start = opt.startMs
            val reachableChorus = sections.any { it.isChorus && it.startMs >= start && it.endMs + MAX_EXIT_WAIT_MS <= start + MAX_CLIP_MS }
            for (j in opt.sectionIndex until sections.size) {
                val last = sections[j]
                val next = sections.getOrNull(j + 1)
                val nextStart = next?.startMs ?: limit
                // El fundido empieza en el compás que sigue a la última palabra (frase cerrada); si no hay compás
                // conocido, en cuanto acaba la voz.
                val tail = if (last.isChorus && last.seamFreeMs == 0L) SongSections.VOCAL_TAIL_MS else TAIL_GAP_MS
                val exit = phrases.firstIn(last.endMs + tail, minOf(nextStart, last.endMs + MAX_EXIT_WAIT_MS))
                val fadeStart = exit?.ms ?: minOf(last.endMs + tail, nextStart)
                if (fadeStart < minFadeStart) continue
                val end = minOf(fadeStart + mix, limit)
                if (end - fadeStart < minOf(mix, 1_500L)) continue
                val clip = end - start
                if (clip < MIN_CLIP_MS) continue
                if (clip > MAX_CLIP_MS) break
                // El fundido no puede comerse un estribillo y casi nada de la voz del bloque siguiente.
                val spill = ((end - nextStart).coerceAtLeast(0L)).toDouble() / (end - fadeStart).coerceAtLeast(1L)
                if (SongSections.overlapsChorus(fadeStart, end, sections)) continue
                if (spill > 0.5) continue
                val window = sections.subList(opt.sectionIndex, j + 1)
                var score = 0.0
                when {
                    window.any { it.isChorus } -> score += 3.0
                    reachableChorus && fixedStart == null -> score -= 3.0 // podía tener estribillo y no lo tiene
                    hasChorus -> score -= 0.8
                }
                if (last.isChorus) score += 1.5 // sale justo después de un estribillo: el mejor momento
                val firstSec = sections[opt.sectionIndex]
                val nextSec = sections.getOrNull(opt.sectionIndex + 1)
                if (!firstSec.isChorus && nextSec?.isChorus == true) score += 1.0 // entra en lo que lleva al estribillo
                val firstChorus = window.firstOrNull { it.isChorus }
                if (firstChorus != null && firstChorus.startMs - start <= 40_000L) score += 0.6
                if (firstSec.isChorus && opt.sectionIndex > 0 && !sections[opt.sectionIndex - 1].isChorus) score -= 0.3
                if (opt.onPhrase) score += 0.7
                if (exit != null) score += 0.7
                score -= opt.penalty
                score -= 3.0 * spill
                // Antes mejor que tarde (sin porcentajes fijos): suele ser la primera vuelta de estrofa y estribillo.
                score -= 1.2 * (start / durationMs.toDouble())
                score -= kotlin.math.abs(clip - TARGET_CLIP_MS) / TARGET_CLIP_MS.toDouble()
                // Un hueco de voz amplio al entrar y al salir: transiciones más limpias.
                val prevEnd = sections.getOrNull(opt.sectionIndex - 1)?.endMs ?: 0L
                if (fixedStart == null) score += 0.15 * ((firstSec.startMs - prevEnd) / 1000.0).coerceAtMost(4.0)
                score += 0.15 * ((nextStart - last.endMs) / 1000.0).coerceAtMost(4.0)
                // Con energía real: entrar y salir por valles relajados, nunca sobre picos.
                if (audio != null && audio.energyCurve.isNotEmpty()) {
                    if (fixedStart == null && start + mix <= measuredEnd) audio.windowEnergyStats(start, start + mix)?.let { (avgIn, maxIn) ->
                        score += ((52.0 - avgIn) / 22.0).coerceIn(-2.0, 2.0) * 0.6
                        if (maxIn >= 78 && avgIn >= 60.0) score -= 1.2
                    }
                    if (end <= measuredEnd) audio.windowEnergyStats(fadeStart, end)?.let { (avgOut, maxOut) ->
                        score += ((52.0 - avgOut) / 18.0).coerceIn(-2.6, 2.6) * 0.6
                        if (maxOut >= 78 && avgOut >= 60.0) score -= 1.8
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    best = AdhdSegment(
                        startMs = start,
                        endMs = end,
                        clipDurationMs = clip,
                        fadeOutStartMs = fadeStart,
                        mixOutDurationMs = end - fadeStart,
                        fullDurationMs = durationMs,
                        fromLyrics = true,
                        fromAudio = audio != null,
                        entrySectionMs = opt.sectionMs,
                        startOnPhrase = opt.onPhrase,
                        exitOnPhrase = exit != null,
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
        phrases: PhraseMap,
        fixedStart: Long?,
        minFadeStart: Long,
    ): AdhdSegment {
        val targetClip = when (mood) {
            DjMood.Energia -> 78_000L
            DjMood.Chill, DjMood.Foco -> 94_000L
            else -> TARGET_CLIP_MS
        }.coerceIn(MIN_CLIP_MS, MAX_CLIP_MS)
        val mixBase = effectiveMixMs(crossfadeMs, targetClip)
        val outro = audio.outroStartMs.takeIf { audio.coversWholeSong && it > WHOLE_SONG_MS } ?: durationMs
        val effectiveEnd = minOf(durationMs, maxOf(outro, (fixedStart ?: 0L) + MIN_CLIP_MS, minFadeStart + mixBase))
        val measuredEnd = PhraseMap.measuredUntil(durationMs, audio)
        val latestStart = (effectiveEnd - MIN_CLIP_MS).coerceAtLeast(0L)
        val intro = audio.introEndMs.takeIf { it < measuredEnd } ?: audio.firstSoundMs
        val minStart = intro.coerceIn(0L, latestStart)
        var startOnPhrase = false
        var entrySection: Long
        val start: Long
        if (fixedStart != null) {
            start = fixedStart
            entrySection = fixedStart
            startOnPhrase = fixedStart in phrases.times
        } else {
            // Entrar en la respiración suave previa al drop/estribillo para que suba de forma natural y no encima del pico.
            val preDropBreath = if (audio.energyCurve.isNotEmpty()) {
                audio.findPreDropBreathMs(
                    minMs = minStart,
                    maxDropMs = minOf(latestStart, measuredEnd - mixBase - 8_000L, maxOf(audio.dropMs, (durationMs * 0.42).toLong())),
                    leadMinMs = maxOf(4_000L, mixBase),
                    leadMaxMs = 10_000L,
                    mixWindowMs = mixBase,
                )
            } else null
            val rawStart = when {
                preDropBreath != null -> preDropBreath
                audio.dropMs in (minStart + 5_001L) until measuredEnd ->
                    (audio.dropMs - maxOf(mixBase + 1_500L, 5_500L)).coerceAtLeast(minStart)
                else -> {
                    val fallbackMin = maxOf(minStart, (durationMs * 0.14).toLong()).coerceAtMost(latestStart)
                    val fallbackMax = minOf(latestStart, measuredEnd - mixBase, (durationMs * 0.36).toLong())
                    val soft = if (fallbackMin <= fallbackMax) {
                        audio.findSoftTransitionStartMs(fallbackMin, fallbackMax, mixBase, preferDownbeat = true)
                    } else null
                    soft ?: maxOf(minStart, (durationMs * 0.18).toLong())
                }
            }.coerceIn(minStart, latestStart)
            // La subida que se busca (drop o cambio de energía) es la «sección»: se entra en frase, con margen para la
            // mezcla antes de ella.
            val rise = phrases.points.firstOrNull {
                it.ms > rawStart + 1_000L && it.ms <= rawStart + 14_000L &&
                    (it.kind == PhraseKind.Drop || it.kind == PhraseKind.EnergyChange || it.kind == PhraseKind.SectionStart)
            }
            val phraseStart = if (rise != null) {
                phrases.lastIn(maxOf(minStart, rise.ms - MAX_LEAD_MS), minOf(latestStart, rise.ms - mixBase - 300L))
            } else {
                phrases.lastIn(maxOf(minStart, rawStart - 8_000L), minOf(latestStart, rawStart + 1_200L))
            }
            start = phraseStart?.ms ?: audio.snapMeasuredBeat(
                rawStart, minStart, minOf(rawStart + 1_200L, latestStart), measuredEnd, preferDownbeat = true,
            )
            startOnPhrase = phraseStart != null
            entrySection = rise?.ms?.takeIf { phraseStart != null } ?: start
        }

        // Salida: un límite de frase en un valle/puente de baja energía dentro de [70s, 110s]; si no, el valle a secas.
        val minEnd = minOf(effectiveEnd, maxOf(start + MIN_CLIP_MS, minFadeStart + mixBase))
        val maxEnd = minOf(effectiveEnd, maxOf(start + MAX_CLIP_MS, minEnd))
        val phraseFade = phrases.points
            .filter { it.ms >= maxOf(minEnd - mixBase, minFadeStart) && it.ms + mixBase <= maxEnd }
            .minByOrNull { p ->
                val energy = if (p.ms + mixBase <= measuredEnd) {
                    audio.windowEnergyStats(p.ms, p.ms + mixBase)?.let { it.first + 0.5 * it.second } ?: 50.0
                } else 50.0
                val wantLower = if (p.kind == PhraseKind.Grid) 4.0 else 0.0
                energy + wantLower + kotlin.math.abs(p.ms + mixBase - (start + targetClip)) / 1_500.0
            }
        val fadeSearchMin = maxOf(minEnd - mixBase, start, minFadeStart)
        val fadeSearchMax = minOf(maxEnd, measuredEnd) - mixBase
        val softFadeStart = if (phraseFade == null && fadeSearchMax - fadeSearchMin >= 4_000L) {
            audio.findSoftTransitionStartMs(
                minMs = fadeSearchMin,
                maxMs = fadeSearchMax,
                windowMs = mixBase,
                preferLate = false,
                preferDownbeat = false,
            )
        } else null

        val rawEnd = when {
            phraseFade != null -> phraseFade.ms + mixBase
            softFadeStart != null -> (softFadeStart + mixBase).coerceIn(minEnd, maxEnd)
            else -> minOf(effectiveEnd, maxOf(start + targetClip, minEnd))
        }
        val end = if (phraseFade == null && softFadeStart == null && rawEnd - start >= MIN_CLIP_MS + 1_500L) {
            audio.snapMeasuredBeat(
                rawEnd, maxOf(start + MIN_CLIP_MS, rawEnd - 1_500L, minFadeStart + mixBase),
                maxEnd, measuredEnd, preferDownbeat = false,
            )
        } else {
            rawEnd.coerceAtLeast(minEnd)
        }
        val clip = (end - start).coerceAtLeast(1L)
        val mix = effectiveMixMs(crossfadeMs, clip)
        val fade = phraseFade?.ms ?: (end - mix).coerceIn(maxOf(start, minFadeStart).coerceAtMost(end), end)
        return AdhdSegment(
            startMs = start,
            endMs = end,
            clipDurationMs = clip,
            fadeOutStartMs = fade,
            mixOutDurationMs = end - fade,
            fullDurationMs = durationMs,
            fromLyrics = false,
            fromAudio = true,
            entrySectionMs = entrySection,
            startOnPhrase = startOnPhrase,
            exitOnPhrase = phraseFade != null,
        )
    }

    /** No se usa la rejilla extrapolada para un punto que el análisis parcial todavía no ha medido. */
    private fun AudioMetrics?.snapMeasuredBeat(
        target: Long, min: Long, max: Long, coveredUntil: Long, preferDownbeat: Boolean,
    ): Long = if (this != null && target <= coveredUntil && min <= minOf(max, coveredUntil)) {
        snapBeatMs(target, min, minOf(max, coveredUntil), preferDownbeat)
    } else target

    /** También se respetan los estribillos cuando no hay suficientes secciones para elegir el trozo por la letra. */
    private fun protectFallbackChoruses(
        segment: AdhdSegment, sections: List<SongSection>, phrases: PhraseMap, fixedStart: Long?, minFadeStart: Long,
    ): AdhdSegment {
        if (sections.none { it.isChorus }) return segment
        val start = if (fixedStart == null) {
            sections.firstOrNull { it.isChorus && segment.startMs > it.startMs && segment.startMs < it.endMs }
                ?.startMs?.coerceAtLeast(0L) ?: segment.startMs
        } else segment.startMs
        val mix = segment.mixOutDurationMs
        val duration = segment.fullDurationMs
        val safe = SongSections.safeExitAt(segment.fadeOutStartMs, sections, duration, mix)
        val fade = if (safe == segment.fadeOutStartMs) safe else {
            phrases.points.firstOrNull {
                it.ms in safe..minOf(safe + MAX_EXIT_WAIT_MS, duration - mix) &&
                    !SongSections.overlapsChorus(it.ms, it.ms + mix, sections)
            }?.ms ?: safe
        }
        val end = minOf(duration, fade + mix)
        if (safe > segment.fadeOutStartMs && (end == duration ||
                end - segment.endMs > FALLBACK_EXIT_WAIT_MS || end - start > MAX_CLIP_MS + FALLBACK_EXIT_WAIT_MS)
        ) {
            boundedFallbackExit(segment, start, sections, phrases, minFadeStart)?.let { return it }
        }
        if (start == segment.startMs && fade == segment.fadeOutStartMs) return segment
        return segment.copy(
            startMs = start, endMs = end, clipDurationMs = (end - start).coerceAtLeast(1L),
            fadeOutStartMs = fade, mixOutDurationMs = end - fade,
            entrySectionMs = if (start == segment.startMs) segment.entrySectionMs else start,
            startOnPhrase = if (start == segment.startMs) segment.startOnPhrase else start in phrases.times,
            exitOnPhrase = if (fade == segment.fadeOutStartMs) segment.exitOnPhrase else fade in phrases.times,
        )
    }

    private fun boundedFallbackExit(
        segment: AdhdSegment,
        start: Long,
        sections: List<SongSection>,
        phrases: PhraseMap,
        minFadeStart: Long,
    ): AdhdSegment? {
        val earliestEnd = maxOf(start + FALLBACK_MIN_CLIP_MS, minFadeStart)
        val latestEnd = minOf(segment.endMs + FALLBACK_EXIT_WAIT_MS, start + MAX_CLIP_MS + FALLBACK_EXIT_WAIT_MS,
            segment.fullDurationMs - FALLBACK_END_MARGIN_MS)
        if (earliestEnd > latestEnd) return null
        val betweenSections = buildList {
            var quietFrom = start
            for (section in sections.sortedBy { it.startMs }) {
                val quietUntil = section.startMs - TAIL_GAP_MS
                if (quietUntil >= quietFrom) add(quietFrom..quietUntil)
                quietFrom = maxOf(quietFrom, section.endMs + SongSections.VOCAL_TAIL_MS)
            }
            if (quietFrom <= latestEnd) add(quietFrom..latestEnd)
        }
        fun candidates(windows: List<LongRange>, mix: Boolean): List<Pair<Long, Long>> = windows.mapNotNull { range ->
            val from = maxOf(range.first, minFadeStart)
            val minimum = if (mix) FALLBACK_MIN_MIX_MS else FALLBACK_DUCK_MS
            val firstEnd = maxOf(earliestEnd, from + minimum)
            val lastEnd = minOf(latestEnd, range.last - FALLBACK_EXIT_TOLERANCE_MS)
            if (lastEnd < firstEnd) return@mapNotNull null
            val end = segment.endMs.coerceIn(firstEnd, lastEnd)
            val length = minOf(segment.mixOutDurationMs, end - from)
            if (mix && length < minimum) return@mapNotNull null
            if (!mix && !segment.allowsForcedExitAt(end, sections, guardMs = FALLBACK_DUCK_MS, duckBefore = true)) {
                return@mapNotNull null
            }
            val fade = if (mix) phrases.lastIn(end - length, end - minimum)?.ms ?: end - length else end
            fade to end
        }
        val repeatedSeams = sections.filter { it.seamFreeMs > 0L }.map { (it.endMs - it.seamFreeMs)..(it.endMs - 1L) }
        val byDistance = compareBy<Pair<Long, Long>>({ it.second < segment.endMs }, { kotlin.math.abs(it.second - segment.endMs) })
        val clean = candidates(betweenSections, mix = true).minWithOrNull(byDistance)
            ?: candidates(betweenSections + repeatedSeams + SongSections.rapidBreaths(sections), mix = false).minWithOrNull(byDistance)
        val phraseCut = if (clean == null) {
            val target = segment.endMs.coerceIn(earliestEnd, latestEnd)
            phraseCutIn(sections, phrases.times, earliestEnd, latestEnd, target) ?: target
        } else null
        val (fade, end) = clean ?: (checkNotNull(phraseCut) to phraseCut)
        return segment.copy(
            startMs = start, endMs = end, clipDurationMs = end - start, fadeOutStartMs = fade,
            mixOutDurationMs = end - fade, entrySectionMs = if (start == segment.startMs) segment.entrySectionMs else start,
            startOnPhrase = if (start == segment.startMs) segment.startOnPhrase else start in phrases.times,
            exitOnPhrase = fade in phrases.times,
            fallbackExit = true,
            forcedPhraseCutMs = phraseCut,
        )
    }

    /** La siguiente línea completa es preferible a cortar a mitad de compás. Si el final queda cerca, busca hacia atrás. */
    internal fun phraseCutIn(sections: List<SongSection>, phrases: List<Long>, from: Long, to: Long, target: Long): Long? {
        fun nearest(points: List<Long>): Long? = points.filter { it in from..to }
            .minWithOrNull(compareBy({ it < target }, { kotlin.math.abs(it - target) }))
        val lineStarts = sections.flatMap { section -> section.lineStartsMs.ifEmpty { listOf(section.startMs) } }
        return nearest(lineStarts.map { it - PHRASE_CUT_LEAD_MS }) ?: nearest(phrases)
    }

    private fun guess(durationMs: Long, trackId: String, crossfadeMs: Long, fixedStart: Long?, minFadeStart: Long): AdhdSegment {
        val jitter = deterministicUnit(trackId)
        val clip = (TARGET_CLIP_MS - 10_000L + (jitter * 20_000.0).toLong()).coerceIn(MIN_CLIP_MS, MAX_CLIP_MS)
        // Un poco antes de un tercio: suele caer al principio de la segunda estrofa o del primer estribillo. Es lo único
        // que se adivina; en cuanto llega la letra o el análisis, la canción que suena alarga su salida a una frase.
        var start = fixedStart ?: (durationMs * (0.22 + 0.06 * jitter)).toLong()
        if (fixedStart == null) {
            if (durationMs > 300_000L) start = minOf(start, 100_000L)
            if (start + clip > durationMs) start = (durationMs - clip).coerceAtLeast((durationMs * 0.1).toLong())
        }
        val mix0 = effectiveMixMs(crossfadeMs, clip)
        val end = minOf(maxOf(start + clip, minFadeStart + mix0), durationMs)
        val actual = (end - start).coerceAtLeast(1L)
        val mix = effectiveMixMs(crossfadeMs, actual).coerceAtMost(end - start)
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
    fixedStartMs: Long? = null,
    minFadeStartMs: Long = 0L,
): AdhdSegment? {
    val totalMs = durationMs ?: seconds?.let { it * 1000L } ?: audio?.durationMs?.takeIf { it > 0L } ?: return null
    return AdhdPlanner.segment(totalMs, id, crossfadeMs, mood, sections, audio, fixedStartMs, minFadeStartMs)
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
