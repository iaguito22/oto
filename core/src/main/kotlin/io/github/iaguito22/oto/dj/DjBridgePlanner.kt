package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.lyrics.Lyrics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Motor planificador de transiciones Oro/Oto DJ v2.
 *
 * Reglas de intervalos:
 * - **DJ Normal (`DjMode.Normal`)**: busca puntos óptimos estrictamente en los **últimos 30 s de la canción A**
 *   y los **primeros 30 s de la canción B**.
 * - **DJ rápido (`DjMode.Adhd`)**: recorta segmentos ~65–110 s alineados a frases/drops/valles evitando cortar voces o solos.
 * - **Listas normales (`isDjSession = false`)**: respeta el principio de B (`incomingFromStart`, saltando solo silencio)
 *   y mezcla dentro de los últimos 30 s de A con estilos seguros (`BeatMatchLongMix`, `FilterEcho`, `SmartFade`).
 */
object DjBridgePlanner {
    // Tramos secos de A antes y de B después: el relevo entre reproductores se cuadra en ellos con pulsos de velocidad
    // sobre el búfer BT (~1 s). Con pre-medición de shiftB en etapa 2 y steadyError promediado, 5,0 s y 5,2 s sobran.
    const val PRE_ROLL_MS = 5_000L
    const val POST_ROLL_MS = 5_200L
    const val NORMAL_WINDOW_MS = 30_000L

    /** DJ normal: la mezcla puede empezar hasta aquí antes del final de A y B entrar hasta aquí desde su principio. */
    const val NORMAL_OUT_WINDOW_MS = 75_000L
    const val NORMAL_IN_WINDOW_MS = 45_000L

    /** DJ rápido: B entra cerca del principio de su trozo (antes entraba hasta 18 s tarde y sonaba <40 s). */
    const val ADHD_IN_EARLY_MS = 8_000L
    const val ADHD_IN_LATE_MS = 4_000L

    private data class CandidatePair(
        val style: DjBridgeStyle,
        val cueOutStartMs: Long,
        val cueOutEndMs: Long,
        val entryInMs: Long,
        val transitionDurMs: Long,
        val pickupMs: Long,
        val silenceMs: Long,
        val tempoRatio: Double,
        val echoDelayMs: Double,
        val score: Double,
        val lyricLoop: LyricLoopPlanSpec? = null,
        val reason: String,
    )

    fun plan(
        outgoingDurationMs: Long,
        incomingDurationMs: Long,
        configuredFadeMs: Long,
        outgoingAudio: AudioMetrics? = null,
        incomingAudio: AudioMetrics? = null,
        outgoingLyrics: Lyrics.Synced? = null,
        incomingLyrics: Lyrics.Synced? = null,
        outgoingVocals: List<SongSection> = emptyList(),
        incomingVocals: List<SongSection> = emptyList(),
        outgoingAdhd: AdhdSegment? = null,
        incomingAdhd: AdhdSegment? = null,
        tandaChanged: Boolean = false,
        djMode: DjMode = DjMode.Normal,
        isDjSession: Boolean = true,
        autoLength: Boolean = true,
        recentStyles: List<DjBridgeStyle> = emptyList(),
    ): DjBridgeSpec {
        val durA = max(1_000L, outgoingDurationMs)
        val durB = max(1_000L, incomingDurationMs.takeIf { it > 0L } ?: incomingAudio?.durationMs ?: 180_000L)
        // Con el análisis a medias, su «último sonido» es solo donde acaba lo analizado (medido: 56 s de 185): ahí no se
        // acaba la canción. Solo vale si cubre la canción entera (o es anterior a lo analizado: silencio de verdad).
        val outLastSound = outgoingAudio?.lastSoundMs?.takeIf {
            it > 4_000L && it <= durA &&
                (outgoingAudio.coversWholeSong || outgoingAudio.analyzedMs <= 0L || it < outgoingAudio.analyzedMs - 3_000L)
        } ?: durA
        val inFirstSound = incomingAudio?.firstSoundMs?.coerceIn(0L, min(8_000L, durB / 3))
            ?: incomingAudio?.introEndMs?.coerceIn(0L, min(4_500L, durB / 4))
            ?: 0L

        val barsA = effectiveBars(outgoingAudio, durA)
        val barsB = effectiveBars(incomingAudio, durB)

        val lufsA = outgoingAudio?.integratedLufs ?: outgoingAudio?.rmsDb ?: -14.0
        val lufsB = incomingAudio?.integratedLufs ?: incomingAudio?.rmsDb ?: -14.0
        val gainDiffDb = (lufsA - lufsB).coerceIn(-6.0, 6.0)
        val gainMatch = DjDsp.dbToLin(gainDiffDb * 0.85).toFloat().coerceIn(0.65f, 1.50f)

        val bpmA = outgoingAudio?.bpm?.takeIf { it > 20.0 } ?: 0.0
        val bpmB = incomingAudio?.bpm?.takeIf { it > 20.0 } ?: 0.0
        val beatReliable = isBeatMatchReady(outgoingAudio) && isBeatMatchReady(incomingAudio)
        val bpmRel = DjHarmonic.bpmRelation(bpmA, bpmB)
        val harmonic = DjHarmonic.harmonicCompatibility(outgoingAudio, incomingAudio)

        // Ventanas exactas según modo.
        val outMinMs: Long
        val outMaxMs: Long
        val inMinMs: Long
        val inMaxMs: Long

        when {
            !isDjSession -> {
                // Playlist/radio normal: últimos 30s de A y arranque real de B (solo salta silencio inicial).
                outMinMs = max(0L, outLastSound - NORMAL_WINDOW_MS)
                outMaxMs = max(outMinMs + 2_000L, outLastSound)
                inMinMs = inFirstSound
                inMaxMs = min(durB - 6_000L, inFirstSound + 4_000L).coerceAtLeast(inFirstSound)
            }
            djMode == DjMode.Adhd && outgoingAdhd != null -> {
                val adhdSafeOutEnd = (outgoingAdhd.endMs - 2_600L).coerceAtLeast(outgoingAdhd.startMs + 2_000L)
                val targetOut = outgoingAdhd.fadeOutStartMs.coerceIn(55_000L.coerceAtMost(outLastSound / 2), min(outLastSound, adhdSafeOutEnd))
                outMaxMs = minOf(outLastSound, adhdSafeOutEnd, outgoingAdhd.startMs + 108_000L, targetOut + 14_000L)
                    .coerceAtLeast(2_000L)
                outMinMs = max(outgoingAdhd.startMs + 56_000L, targetOut - 14_000L)
                    .coerceAtMost((outMaxMs - 2_000L).coerceAtLeast(0L))
                    .coerceAtLeast(0L)
                val targetIn = incomingAdhd?.startMs ?: min(durB / 4, 30_000L)
                inMinMs = max(inFirstSound, targetIn - ADHD_IN_EARLY_MS).coerceAtMost(durB - 25_000L).coerceAtLeast(inFirstSound)
                inMaxMs = min(durB - 20_000L, targetIn + ADHD_IN_LATE_MS).coerceAtLeast(inMinMs + 2_000L)
            }
            else -> {
                // DJ Normal: el cambio no tiene por qué ir pegado al final de A ni al principio de B (lo pidió Iago):
                // hasta 75 s antes del final de A (sin pasar de su mitad) y hasta 45 s dentro de B. Alejarse de los
                // bordes cuesta algo de puntuación (`edgeCost`): solo se va lejos si allí encaja mejor.
                outMinMs = max(max(0L, outLastSound - NORMAL_OUT_WINDOW_MS), durA / 2)
                outMaxMs = max(outMinMs + 2_000L, outLastSound)
                inMinMs = min(inFirstSound, min(NORMAL_WINDOW_MS, durB / 2))
                inMaxMs = min(min(NORMAL_IN_WINDOW_MS, durB / 3), durB - 6_000L).coerceAtLeast(inMinMs + 1_000L)
            }
        }

        // Lo que cuesta mezclar lejos del final de A o entrar tarde en B (por segundo, pasado un margen). Solo en el DJ
        // normal, que es el que tiene ventanas anchas.
        val wideWindows = isDjSession && djMode != DjMode.Adhd
        fun edgeCost(mixEndMs: Long, entryMs: Long): Double = if (!wideWindows) 0.0 else
            0.022 * ((outLastSound - mixEndMs - 14_000L).coerceAtLeast(0L) / 1000.0) +
                0.030 * ((entryMs - inFirstSound - 10_000L).coerceAtLeast(0L) / 1000.0)

        val candidatesA = selectOutCandidateBars(barsA, outMinMs, outMaxMs, durA)
        val candidatesB = selectInCandidateBars(barsB, inMinMs, inMaxMs, durB, !isDjSession)
        val zoneTimbreShock = hasTimbreShock(representativeBar(candidatesA), representativeBar(candidatesB))

        // 1. Evaluar `LyricLoopCut` (prioridad alta si ambas letras reales coinciden con intensidades compatibles).
        var lyricCandidate: CandidatePair? = null
        if (isDjSession && recentStyles.lastOrNull() != DjBridgeStyle.LyricLoopCut && outgoingLyrics != null && incomingLyrics != null) {
            val loopSpec = DjLyricMatcher.findSharedLoopMatch(
                outLyrics = outgoingLyrics,
                inLyrics = incomingLyrics,
                outMinMs = outMinMs + 1_000L,
                outMaxMs = max(outMinMs + 1_500L, outMaxMs - 1_200L),
                inMinMs = inMinMs,
                // En DJ rápido la ventana de B es estrecha; para una frase compartida se acepta entrar algo más tarde (el
                // trozo de B se recoloca desde donde entra y dura lo mínimo igual).
                inMaxMs = max(inMinMs + 1_000L, inMaxMs - 1_500L + (if (djMode == DjMode.Adhd) 18_000L else 6_000L)),
                bpmA = if (bpmA > 20.0) bpmA else 110.0,
            )
            if (loopSpec != null && loopSpec.confidence >= 0.35) {
                val barA = nearestBar(barsA, loopSpec.outLoopEndMs)
                val barB = nearestBar(barsB, loopSpec.inEntryMs)
                val bothMeasured = outgoingAudio != null && incomingAudio != null
                val lufsDiffTotal = abs(lufsA - lufsB)
                val lufsDiffLocal = abs(barA.lufs - barB.lufs)
                val drumDiff = abs(barA.drumEnergy - barB.drumEnergy)

                // Guardia de intensidad, tempo y armonía solo cuando existen mediciones reales (sin penalizar si falta B).
                val acousticOk = !bothMeasured ||
                    (lufsDiffTotal <= 5.5 && lufsDiffLocal <= 5.0 && drumDiff <= 0.42 && !hasTimbreShock(barA, barB))
                val tempoOk = !beatReliable || bpmRel.relDiffPct <= 9.5
                val harmonyOk = !bothMeasured ||
                    outgoingAudio.camelot.isNullOrBlank() ||
                    incomingAudio.camelot.isNullOrBlank() ||
                    harmonic >= 0.42
                if (acousticOk && tempoOk && harmonyOk) {
                    val beatAEst = if (bpmA > 20.0) 60_000.0 / bpmA else 545.0
                    val unitMs = if (loopSpec.doubleSpeedTail) {
                        // 1 compás (4 tiempos) a 1.0x sin chipmunk: 3×cell + 2×half (si cell~1t) o cell + tail + 2×half (si cell~2t).
                        if (loopSpec.loopDurationMs >= (beatAEst * 1.45).roundToLong()) {
                            loopSpec.loopDurationMs * 2L
                        } else {
                            loopSpec.loopDurationMs * 4L
                        }
                    } else {
                        loopSpec.loopDurationMs * loopSpec.repeats.coerceIn(3, 5).toLong()
                    }
                    val loopTransDur = (unitMs + 1_100L).coerceIn(2_400L, 9_500L)
                    lyricCandidate = CandidatePair(
                        style = DjBridgeStyle.LyricLoopCut,
                        cueOutStartMs = loopSpec.outLoopStartMs,
                        cueOutEndMs = loopSpec.outLoopEndMs,
                        entryInMs = loopSpec.inEntryMs,
                        transitionDurMs = loopTransDur,
                        pickupMs = 0L,
                        silenceMs = 0L,
                        tempoRatio = if (beatReliable && bpmRel.relDiffPct <= 9.5) bpmRel.ratioOutOverIn else 1.0,
                        echoDelayMs = 360.0,
                        score = 12.0 + loopSpec.confidence * 3.0,
                        lyricLoop = loopSpec,
                        reason = "Bucle vocal compartido: «${loopSpec.phraseText}»",
                    )
                }
            }
        }
        if (lyricCandidate != null) {
            return buildSpec(lyricCandidate, durA, durB, bpmA, bpmB, harmonic, gainMatch, outgoingAudio, incomingAudio)
        }

        // 2. Evaluar parejas `(barOut, barIn)` entre los estilos instrumentales/rítmicos.
        val avgLufsB = barsB.map { it.lufs }.average().let { if (it.isNaN()) lufsB else it }
        val avgDrumA = barsA.map { it.drumEnergy }.average().let { if (it.isNaN()) 0.4 else it }
        val avgDrumB = barsB.map { it.drumEnergy }.average().let { if (it.isNaN()) 0.4 else it }

        var best: CandidatePair? = null

        fun consider(cand: CandidatePair) {
            if (best == null || cand.score > best!!.score) best = cand
        }

        val beatA = if (bpmA > 20.0) 60_000.0 / bpmA else 500.0
        val defaultMixDur = when {
            configuredFadeMs > 0L -> configuredFadeMs.coerceIn(4_000L, 14_000L)
            else -> 8_500L
        }
        val fatigueBeat = varietyPenalty(DjBridgeStyle.BeatMatchLongMix, recentStyles)
        val fatigueDrop = varietyPenalty(DjBridgeStyle.EnergyDropCut, recentStyles)
        val fatigueEcho = varietyPenalty(DjBridgeStyle.FilterEcho, recentStyles)
        val fatigueFade = varietyPenalty(DjBridgeStyle.SmartFade, recentStyles)

        for (bOut in candidatesA) {
            val midVocalOut = DjLyricMatcher.isMidVocal(outgoingLyrics, bOut.endMs) ||
                inSection(outgoingVocals, bOut.endMs)
            val exitVocals = (DjLyricMatcher.vocalOccupancyRatio(outgoingLyrics, bOut.startMs, bOut.endMs)
                ?: vocalOverlapSections(outgoingVocals, bOut.startMs, bOut.endMs)
                ?: bOut.vocalRatio)
            val exitPenalty = (2.2 * exitVocals) + (2.3 * bOut.soloRisk) + (if (midVocalOut) 2.4 else 0.0)

            for (bIn in candidatesB) {
                val rawEntry = if (!isDjSession) inMinMs else bIn.startMs.coerceIn(inMinMs, inMaxMs)
                val midVocalIn = DjLyricMatcher.isMidVocal(incomingLyrics, rawEntry)
                val entryVocals = (DjLyricMatcher.vocalOccupancyRatio(incomingLyrics, rawEntry, rawEntry + 6_000L)
                    ?: vocalOverlapSections(incomingVocals, rawEntry, rawEntry + 6_000L)
                    ?: bIn.vocalRatio)

                val harshDiff = abs(bOut.harshness - bIn.harshness)
                val pairTimbreShock = hasTimbreShock(bOut, bIn)
                val lufsDiffLocal = abs(bOut.lufs - bIn.lufs)

                // A. `BeatMatchLongMix`
                if (beatReliable && bpmRel.relDiffPct <= 8.5 && !zoneTimbreShock && !pairTimbreShock && avgDrumA >= 0.16 && avgDrumB >= 0.16 && harmonic >= 0.45) {
                    val barsCount = if (autoLength) {
                        if (tandaChanged) 4 else if (bpmRel.relDiffPct <= 3.5 && harmonic >= 0.72) 6 else 4
                    } else 0
                    val mixDur = if (barsCount > 0) {
                        (barsCount * 4.0 * beatA).roundToLong().coerceIn(5_500L, min(14_000L, max(5_500L, durA - bOut.startMs)))
                    } else {
                        defaultMixDur.coerceAtMost(max(4_500L, durA - bOut.startMs))
                    }
                    // Que la mezcla no rebase la zona útil de A, y que arranque en un compás de A y en su beat (con B
                    // también en su beat): si no, los bombos de las dos van desfasados toda la mezcla.
                    val startOut = onGridStart(bOut.startMs, outMinMs, outMaxMs - mixDur, barsA, outgoingAudio)
                    val beatEntry = snapToBeat(rawEntry, incomingAudio, inMinMs, inMaxMs)
                    // Mejor si B entra en un cambio de sección y A termina la mezcla en otro (frases enteras).
                    val phraseFit = 0.6 * sectionLift(barsB, bIn) + 0.4 * sectionLift(barsA, nearestBar(barsA, startOut + mixDur))
                    val outVocWin = DjLyricMatcher.vocalOccupancyRatio(outgoingLyrics, startOut, startOut + mixDur)
                        ?: vocalOverlapSections(outgoingVocals, startOut, startOut + mixDur)
                        ?: bOut.vocalRatio
                    val inVocWin = DjLyricMatcher.vocalOccupancyRatio(incomingLyrics, beatEntry, beatEntry + mixDur)
                        ?: vocalOverlapSections(incomingVocals, beatEntry, beatEntry + mixDur)
                        ?: bIn.vocalRatio
                    val bothSingingPenalty = if (outVocWin > 0.25 && inVocWin > 0.25) {
                        3.4 * min(outVocWin, inVocWin) + (if (outVocWin > 0.55 && inVocWin > 0.55) 0.9 else 0.0)
                    } else {
                        0.0
                    }

                    val tempoCloseness = (1.0 - bpmRel.relDiffPct / 8.5).coerceIn(0.0, 1.0)
                    val score = 7.15 +
                        phraseFit +
                        1.85 * tempoCloseness +
                        1.25 * harmonic +
                        0.95 * min(bOut.kickSteady, bIn.kickSteady) -
                        bothSingingPenalty -
                        0.85 * exitPenalty -
                        0.40 * harshDiff -
                        (if (tandaChanged) 0.55 else 0.0) -
                        (lufsDiffLocal * 0.20) -
                        edgeCost(startOut + mixDur, beatEntry) -
                        fatigueBeat
                    consider(
                        CandidatePair(
                            style = DjBridgeStyle.BeatMatchLongMix,
                            cueOutStartMs = startOut,
                            cueOutEndMs = startOut + mixDur,
                            entryInMs = beatEntry,
                            transitionDurMs = mixDur,
                            pickupMs = 0L,
                            silenceMs = 0L,
                            tempoRatio = bpmRel.ratioOutOverIn,
                            echoDelayMs = 360.0,
                            score = score,
                            reason = "Cuadre ${"%.1f".format(bpmA)}↔${"%.1f".format(bpmB)} BPM · ${outgoingAudio?.camelot.orEmpty()}→${incomingAudio?.camelot.orEmpty()}",
                        ),
                    )
                }

                // C. `FilterEcho` (pasa-altos suave 12 dB/oct + LPF apertura en B; eco a negra con estela sobre B cuando va a pulso y en armonía)
                val echoCanBeatMatch = beatReliable && bpmRel.relDiffPct <= 9.5
                val useBeatEcho = echoCanBeatMatch &&
                    !tandaChanged &&
                    harmonic >= 0.45 &&
                    (outgoingAudio?.bpmConfidence ?: 0.0) >= 0.50 &&
                    (outgoingAudio?.beatGrid?.size ?: 0) >= 8 &&
                    avgDrumA < 0.52
                val maxEchoDur = if (echoCanBeatMatch) 8_500L else 6_800L
                val echoDur = (defaultMixDur * (if (echoCanBeatMatch) 0.90 else 0.78)).roundToLong()
                    .coerceIn(4_500L, min(maxEchoDur, max(4_500L, durA - bOut.startMs)))
                val echoStart = onGridStart(bOut.startMs, outMinMs, outMaxMs - echoDur, barsA, outgoingAudio)
                val echoEntry = if (echoCanBeatMatch) snapToBeat(rawEntry, incomingAudio, inMinMs, inMaxMs) else rawEntry
                val beatEcho = if (useBeatEcho) straightEchoMs(beatA) else 0.0
                val trueTimbreReason = zoneTimbreShock && pairTimbreShock
                val echoBoost = (if (trueTimbreReason) 2.55 else 0.0) +
                    (if (tandaChanged) 1.25 else 0.0) +
                    (if (harmonic < 0.36) 1.05 else 0.0) +
                    (if (bpmRel.relDiffPct > 14.0 && entryVocals > 0.35) 0.70 else 0.0) -
                    (if (pairTimbreShock && !zoneTimbreShock) 0.75 else 0.0)
                val echoScore = 4.15 +
                    echoBoost -
                    0.55 * exitPenalty -
                    0.45 * entryVocals -
                    edgeCost(echoStart + echoDur, echoEntry) -
                    fatigueEcho
                consider(
                    CandidatePair(
                        style = DjBridgeStyle.FilterEcho,
                        cueOutStartMs = echoStart,
                        cueOutEndMs = echoStart + echoDur,
                        entryInMs = echoEntry,
                        transitionDurMs = echoDur,
                        pickupMs = 0L,
                        silenceMs = 0L,
                        tempoRatio = if (echoCanBeatMatch) bpmRel.ratioOutOverIn else 1.0,
                        echoDelayMs = beatEcho,
                        score = echoScore,
                        reason = when {
                            useBeatEcho && trueTimbreReason -> "Filtro + eco a pulso para cambio de textura"
                            useBeatEcho -> "Filtro pasa-altos + eco a pulso"
                            trueTimbreReason -> "Filtro pasa-altos para cambio limpio de textura"
                            else -> "Filtro pasa-altos"
                        },
                    ),
                )

                // D. `SmartFade` (fundido musical cálido para temas más melódicos o tempos incompatibles sin choque tímbrico)
                val fadeTempoClose = beatReliable && bpmRel.relDiffPct <= 8.5
                val fadeBothDrums = avgDrumA >= 0.28 && avgDrumB >= 0.28
                val maxFadeDur = 7_200L
                val fadeDur = (if (fadeTempoClose) (defaultMixDur * 0.85).roundToLong() else (defaultMixDur * 0.78).roundToLong())
                    .coerceIn(4_000L, min(maxFadeDur, max(4_000L, durA - bOut.startMs)))
                val fadeStart = onGridStart(bOut.startMs, outMinMs, outMaxMs - fadeDur, barsA, outgoingAudio)
                val fadeEntry = if (fadeTempoClose) snapToBeat(rawEntry, incomingAudio, inMinMs, inMaxMs) else rawEntry
                val calmBonus = if (avgDrumA < 0.32 || avgDrumB < 0.32) 1.35 else 0.0
                val closeDrumFadePenalty = if (fadeTempoClose && fadeBothDrums) 2.35 else 0.0
                val textureMatch = (1.0 - harshDiff).coerceIn(0.0, 1.0)
                val dualVocalPenalty = if (exitVocals > 0.30 && entryVocals > 0.30) 2.0 * min(exitVocals, entryVocals) else 0.0
                val fadeScore = 4.65 +
                    calmBonus +
                    0.85 * harmonic +
                    0.65 * textureMatch -
                    closeDrumFadePenalty -
                    dualVocalPenalty -
                    0.65 * exitPenalty -
                    0.50 * entryVocals -
                    (if (tandaChanged) 1.6 else 0.0) -
                    (if (pairTimbreShock) 2.2 else 0.0) -
                    (if (zoneTimbreShock) 1.5 else 0.0) -
                    edgeCost(fadeStart + fadeDur, fadeEntry) -
                    fatigueFade
                consider(
                    CandidatePair(
                        style = DjBridgeStyle.SmartFade,
                        cueOutStartMs = fadeStart,
                        cueOutEndMs = fadeStart + fadeDur,
                        entryInMs = fadeEntry,
                        transitionDurMs = fadeDur,
                        pickupMs = 0L,
                        silenceMs = 0L,
                        tempoRatio = 1.0,
                        echoDelayMs = 320.0,
                        score = fadeScore,
                        reason = "Fundido musical suave",
                    ),
                )
            }
        }

        // B. `EnergyDropCut`: A sube hacia un estribillo/drop y, justo cuando iba a caer, se calla (eco amplio a pulso
        // + respiro de tensión) y entra el riser de B hasta el drop de B.
        if (isDjSession) {
            val dropsA = liftBars(barsA, outMinMs + 2_500L, outMaxMs)
            val dropsB = candidatesB.filter { bIn ->
                bIn.drumEnergy >= 0.36 && bIn.lufs >= avgLufsB - 1.5 &&
                    (rise(barsB, bIn).first >= 0.9 || bIn.drumEnergy >= 0.48 || bIn.kickSteady >= 0.55)
            }
            val bpmEff = when {
                bpmA <= 20.0 -> 120.0
                bpmA < 85.0 -> bpmA * 2.0
                bpmA > 168.0 -> bpmA * 0.5
                else -> bpmA
            }
            val effBeatA = 60_000.0 / bpmEff
            val gapBeats = if (bpmEff > 112.0) 4.0 else 3.0
            val preDropGap = (effBeatA * 0.5).roundToLong().coerceIn(180L, 460L)
            val gapEchoMs = (effBeatA * gapBeats).roundToLong().coerceIn(1_200L, 2_700L)
            val pickup = effBeatA.roundToLong().coerceIn(380L, 750L)
            val echoStepMs = (effBeatA * (if (bpmEff > 126.0) 2.0 else 1.0)).coerceIn(380.0, 950.0)
            for ((barA, liftA) in dropsA) {
                val dropAt = snapToBeat(barA.startMs, outgoingAudio, outMinMs + 2_000L, outMaxMs)
                val cutMs = dropAt - preDropGap
                // Cortar a A en mitad de una palabra suena a error; justo al acabar su subida, no.
                val cutsVocal = DjLyricMatcher.isMidVocal(outgoingLyrics, cutMs) || inSection(outgoingVocals, cutMs)
                val prevA = barsA.getOrNull(barsA.indexOf(barA) - 1) ?: barA
                val blindVocalCut = outgoingLyrics == null && outgoingVocals.isEmpty() &&
                    prevA.vocalRatio >= 0.45 && barA.vocalRatio >= 0.45
                for (bIn in dropsB) {
                    val entryMs = snapToBeat(bIn.startMs.coerceIn(inMinMs, inMaxMs), incomingAudio, inMinMs, inMaxMs)
                    if (DjLyricMatcher.isMidVocal(incomingLyrics, entryMs)) continue
                    val lufsJump = abs(barA.lufs - bIn.lufs)
                    if (lufsJump > 5.5) continue
                    val liftB = rise(barsB, bIn).first
                    val score = 6.0 +
                        1.6 * liftA +
                        liftB.coerceIn(0.0, 5.0) * 0.40 +
                        1.2 * bIn.drumEnergy +
                        (if (djMode == DjMode.Adhd) 0.55 else 0.0) +
                        (if (tandaChanged) 0.35 else 0.0) +
                        (if (beatReliable) 0.3 else -0.6) -
                        (if (cutsVocal) 2.8 else if (blindVocalCut) 1.65 else 0.0) -
                        1.2 * prevA.soloRisk -
                        (if (hasTimbreShock(prevA, bIn)) 0.8 else 0.0) -
                        lufsJump * 0.18 -
                        edgeCost(dropAt, entryMs) -
                        fatigueDrop
                    consider(
                        CandidatePair(
                            style = DjBridgeStyle.EnergyDropCut,
                            cueOutStartMs = (cutMs - pickup).coerceAtLeast(outMinMs),
                            cueOutEndMs = cutMs,
                            entryInMs = entryMs,
                            transitionDurMs = pickup + gapEchoMs + 750L,
                            pickupMs = pickup,
                            silenceMs = gapEchoMs,
                            tempoRatio = 1.0,
                            echoDelayMs = echoStepMs,
                            score = score,
                            reason = "Corte con ecos a pulso + respiro (${gapEchoMs} ms) y riser al drop",
                        ),
                    )
                }
            }
        }

        val winner = best ?: CandidatePair(
            style = DjBridgeStyle.SmartFade,
            cueOutStartMs = max(0L, outLastSound - 6_000L),
            cueOutEndMs = outLastSound,
            entryInMs = inMinMs,
            transitionDurMs = 6_000L,
            pickupMs = 0L,
            silenceMs = 0L,
            tempoRatio = 1.0,
            echoDelayMs = 340.0,
            score = 1.0,
            reason = "Fundido suave de respaldo",
        )
        return buildSpec(winner, durA, durB, bpmA, bpmB, harmonic, gainMatch, outgoingAudio, incomingAudio)
    }

    private fun buildSpec(
        win: CandidatePair,
        durA: Long,
        durB: Long,
        bpmA: Double,
        bpmB: Double,
        harmonic: Double,
        gainMatch: Float,
        outAudio: AudioMetrics?,
        inAudio: AudioMetrics?,
    ): DjBridgeSpec {
        // Ajustar el inicio de efectos con preRoll seco anterior para handoff limpio.
        val cueOutStart = win.cueOutStartMs.coerceIn(0L, max(0L, durA - 1_500L))
        val cueOutEnd = win.cueOutEndMs.coerceIn(cueOutStart + 400L, durA)
        val preRoll = min(PRE_ROLL_MS, cueOutStart)
        val preRollStart = cueOutStart - preRoll
        val entryIn = win.entryInMs.coerceIn(0L, max(0L, durB - 4_000L))
        val postRoll = min(POST_ROLL_MS, max(800L, durB - entryIn - 1_500L))

        val dryOffsetInB = when (win.style) {
            DjBridgeStyle.BeatMatchLongMix -> (win.transitionDurMs * win.tempoRatio).roundToLong() + 1_600L
            DjBridgeStyle.LyricLoopCut -> {
                // La entrada de B ya ha cantado la frase compartida + puente corto de estabilización a tempo.
                val phraseDur = (win.lyricLoop?.let { max(2_200L, it.inPhraseEndMs - it.inEntryMs + 1_600L) } ?: 2_600L)
                phraseDur
            }
            DjBridgeStyle.EnergyDropCut -> 750L
            DjBridgeStyle.FilterEcho -> if (bpmA > 20.0 && bpmB > 20.0 && DjHarmonic.bpmRelation(bpmA, bpmB).relDiffPct <= 9.5) {
                win.transitionDurMs
            } else {
                (win.transitionDurMs * 0.55).roundToLong().coerceIn(1_800L, 5_000L)
            }
            DjBridgeStyle.SmartFade -> win.transitionDurMs
        }
        val dryBResumeMs = (entryIn + dryOffsetInB + postRoll).coerceAtMost(durB)
        val lufsParity = 1.0 - (abs((outAudio?.integratedLufs ?: -14.0) - (inAudio?.integratedLufs ?: -14.0)) / 12.0).coerceIn(0.0, 1.0)
        val compat = (0.45 * harmonic + 0.35 * lufsParity + 0.20 * (if (win.style == DjBridgeStyle.BeatMatchLongMix || win.style == DjBridgeStyle.LyricLoopCut) 1.0 else 0.65)).coerceIn(0.1, 1.0)

        return DjBridgeSpec(
            style = win.style,
            preRollStartMs = preRollStart,
            cueOutStartMs = cueOutStart,
            cueOutEndMs = cueOutEnd,
            entryInMs = entryIn,
            dryBResumeMs = dryBResumeMs,
            preRollMs = preRoll,
            postRollMs = postRoll,
            transitionDurationMs = win.transitionDurMs,
            bpmA = bpmA,
            bpmB = bpmB,
            tempoMatchRatio = win.tempoRatio,
            bassSwapFrac = if (win.style == DjBridgeStyle.BeatMatchLongMix) 0.52f else 0.50f,
            echoDelayMs = win.echoDelayMs,
            reverbRoom = if (win.style == DjBridgeStyle.FilterEcho) 0.42f else 0.48f,
            cutoffStartHz = 65f,
            cutoffEndHz = 2_400f,
            pickupMs = win.pickupMs,
            silenceMs = win.silenceMs,
            kickShiftMs = 0.0,
            incomingGainMatch = gainMatch,
            lyricLoop = win.lyricLoop,
            compatibilityScore = compat,
            reason = win.reason,
        )
    }

    private fun effectiveBars(audio: AudioMetrics?, durationMs: Long): List<BarFeature> {
        if (audio != null && audio.bars.isNotEmpty()) {
            val bars = audio.bars
            val lastEnd = bars.last().endMs
            if (lastEnd >= durationMs - 4_000L) return bars
            // Análisis a medias (solo el principio bajado): sin compases para el resto, el cambio caía en lo ya analizado,
            // a veces antes de donde iba la canción (medido: cambio en 54 s sonando en 108 s) y no había mezcla hasta
            // que acababa el análisis. Lo que falta se rellena con compases como los últimos medidos (sin voz supuesta).
            val step = bars.takeLast(8).map { it.durationMs }.sorted().let { it[it.size / 2] }.coerceIn(1_000L, 4_000L)
            val recent = bars.takeLast(8)
            val template = recent.last().copy(
                lufs = recent.map { it.lufs }.average(),
                drumEnergy = recent.map { it.drumEnergy }.average(),
                vocalRatio = recent.map { it.vocalRatio }.average(),
            )
            val out = ArrayList<BarFeature>(bars)
            var t = lastEnd
            while (t < durationMs) {
                val end = min(durationMs, t + step)
                out += template.copy(startMs = t, endMs = end)
                t = end
            }
            return out
        }
        val list = ArrayList<BarFeature>()
        val step = 2_000L
        var t = 0L
        while (t < durationMs) {
            val end = min(durationMs, t + step)
            val eStats = audio?.windowEnergyStats(t, end)
            val lvl = (eStats?.first ?: 50.0) / 100.0
            val lufs = (audio?.integratedLufs ?: audio?.rmsDb ?: -14.0) + (lvl - 0.5) * 8.0
            list += BarFeature(
                startMs = t,
                endMs = end,
                localBpm = audio?.bpm ?: 0.0,
                lufs = lufs,
                lowRms = lvl * 0.45,
                midRms = lvl * 0.35,
                highRms = lvl * 0.25,
                kickSteady = if ((audio?.bpmConfidence ?: 0.0) >= 0.4) 0.65 else 0.2,
                drumEnergy = (audio?.beatStrength ?: 0.4) * (0.6 + 0.5 * lvl),
                harshness = 0.2,
                vocalRatio = 0.25,
            )
            t = end
        }
        return list
    }

    private fun selectOutCandidateBars(bars: List<BarFeature>, minMs: Long, maxMs: Long, dur: Long): List<BarFeature> {
        val inside = bars.filter { it.endMs >= minMs + 2_000L && it.startMs <= maxMs - 500L }
        if (inside.isNotEmpty()) {
            return if (inside.size <= 8) inside else inside.sortedBy { it.vocalRatio + it.soloRisk * 0.8 }.take(8)
        }
        // Ningún compás en la ventana: uno al final de la ventana, no el último que haya (podía quedar muy antes).
        if (maxMs > minMs) return listOf(BarFeature((maxMs - 4_000L).coerceAtLeast(minMs), maxMs))
        return listOf(bars.lastOrNull() ?: BarFeature(max(0L, dur - 6_000L), dur))
    }

    private fun selectInCandidateBars(bars: List<BarFeature>, minMs: Long, maxMs: Long, dur: Long, fromStartOnly: Boolean): List<BarFeature> {
        if (fromStartOnly) {
            val first = bars.firstOrNull { it.endMs > minMs } ?: bars.firstOrNull() ?: BarFeature(minMs, min(dur, minMs + 2_000L))
            return listOf(first)
        }
        val inside = bars.filter { it.startMs in minMs..maxMs }
        if (inside.isNotEmpty()) {
            return if (inside.size <= 8) inside else inside.sortedByDescending { it.drumEnergy + (it.lufs * 0.06) - it.vocalRatio }.take(8)
        }
        return listOf(bars.firstOrNull { it.endMs > minMs } ?: bars.firstOrNull() ?: BarFeature(0L, min(dur, 2_000L)))
    }

    /**
     * Arranque de una mezcla en A: el compás candidato si deja sitio a la mezcla; si no, el último compás que lo deja
     * (antes se recortaba a un instante cualquiera, fuera de compás). Siempre ajustado al beat real de A.
     */
    internal fun onGridStart(candidateMs: Long, earliestMs: Long, latestMs: Long, bars: List<BarFeature>, audio: AudioMetrics?): Long {
        val lo = max(0L, earliestMs)
        val hi = max(lo, latestMs)
        val base = when {
            candidateMs in lo..hi -> candidateMs
            candidateMs > hi -> bars.lastOrNull { it.startMs in lo..hi }?.startMs ?: hi
            else -> bars.firstOrNull { it.startMs in lo..hi }?.startMs ?: lo
        }
        return snapToBeat(base, audio, lo, hi)
    }

    /** [ms] llevado al beat más cercano de la rejilla real, si lo hay cerca y queda dentro de `[lo, hi]`. */
    internal fun snapToBeat(ms: Long, audio: AudioMetrics?, lo: Long = 0L, hi: Long = Long.MAX_VALUE): Long {
        if (audio == null) return ms
        val canSnap = audio.bpmConfidence >= 0.3 ||
            (audio.bpmConfidence >= 0.18 && audio.beatGrid.size >= 8 && audio.beatStrength >= 0.25)
        if (!canSnap) return ms
        val period = audio.beatPeriodMs ?: return ms
        val rawBeat = audio.nearestBeatMs(ms)
            ?: audio.beatPhaseMs.takeIf { it >= 0L }?.let { ph -> ph + kotlin.math.round((ms - ph) / period) * period }
            ?: return ms
        if (abs(rawBeat - ms) > period * 0.55) return ms
        val candidates = listOf(rawBeat, rawBeat + period, rawBeat - period).map { it.roundToLong() }
        return candidates.firstOrNull { it in lo..hi } ?: ms
    }

    /** (subida de volumen en dB, subida de batería) de [bar] respecto a los dos compases anteriores. */
    private fun rise(bars: List<BarFeature>, bar: BarFeature): Pair<Double, Double> {
        val i = bars.indexOf(bar)
        if (i <= 0) return 0.0 to 0.0
        val prev = bars.subList(max(0, i - 2), i)
        return (bar.lufs - prev.map { it.lufs }.average()) to (bar.drumEnergy - prev.map { it.drumEnergy }.average())
    }

    /**
     * Compases de A en `[lo, hi]` donde la música sube de golpe (empieza un estribillo o un drop), con cuánto (0..1).
     * El corte va justo antes de ellos.
     */
    private fun liftBars(bars: List<BarFeature>, lo: Long, hi: Long): List<Pair<BarFeature, Double>> =
        bars.filter { it.startMs in lo..hi }.mapNotNull { bar ->
            val (dl, dd) = rise(bars, bar)
            val lift = 0.55 * ((dl - 0.8) / 3.0).coerceIn(0.0, 1.0) + 0.45 * ((dd - 0.05) / 0.25).coerceIn(0.0, 1.0)
            if ((dl >= 1.2 || (dd >= 0.12 && dl >= 0.3)) && lift >= 0.25) bar to lift else null
        }.sortedByDescending { it.second }.take(6)

    /** Cuánto cambia la música al empezar [bar] (0..1): un cambio de sección o de frase, donde mezclar suena natural. */
    private fun sectionLift(bars: List<BarFeature>, bar: BarFeature): Double {
        val i = bars.indexOf(bar)
        if (i <= 0) return 0.0
        val prev = bars[i - 1]
        val loud = ((abs(bar.lufs - prev.lufs) - 1.0) / 3.0).coerceIn(0.0, 1.0)
        val drums = ((abs(bar.drumEnergy - prev.drumEnergy) - 0.08) / 0.25).coerceIn(0.0, 1.0)
        return 0.5 * loud + 0.5 * drums
    }

    private fun nearestBar(bars: List<BarFeature>, ms: Long): BarFeature =
        bars.minByOrNull { abs(it.midMs - ms) } ?: BarFeature(max(0L, ms - 1_000L), ms + 1_000L)

    private fun inSection(sections: List<SongSection>, ms: Long): Boolean =
        sections.any { ms in (it.startMs + 250L)..(it.endMs - 250L) }

    private fun vocalOverlapSections(sections: List<SongSection>, startMs: Long, endMs: Long): Double? {
        if (sections.isEmpty() || endMs <= startMs) return null
        var covered = 0L
        for (s in sections) {
            val lo = max(startMs, s.startMs)
            val hi = min(endMs, s.endMs)
            if (hi > lo) covered += (hi - lo)
        }
        return (covered.toDouble() / (endMs - startMs)).coerceIn(0.0, 1.0)
    }

    private fun hasTimbreShock(out: BarFeature, inBar: BarFeature): Boolean {
        val harshDiff = abs(out.harshness - inBar.harshness)
        val maxHarsh = max(out.harshness, inBar.harshness)
        val tiltDiff = abs(out.tonalBrightness - inBar.tonalBrightness)
        val activeOut = (out.lowRms + out.midRms + out.highRms) >= 0.08
        val activeIn = (inBar.lowRms + inBar.midRms + inBar.highRms) >= 0.08
        return (harshDiff >= 0.38 && maxHarsh >= 0.44) ||
            (activeOut && activeIn && tiltDiff >= 1.85 && harshDiff >= 0.16)
    }

    private fun representativeBar(bars: List<BarFeature>): BarFeature {
        if (bars.isEmpty()) return BarFeature(0L, 2_000L)
        val active = bars.filter { (it.lowRms + it.midRms + it.highRms) >= 0.08 }.ifEmpty { bars }
        fun median(sel: (BarFeature) -> Double): Double {
            val s = active.map(sel).sorted()
            return s[s.size / 2]
        }
        return active.first().copy(
            lowRms = median { it.lowRms },
            midRms = median { it.midRms },
            highRms = median { it.highRms },
            harshness = median { it.harshness },
        )
    }

    private fun varietyPenalty(style: DjBridgeStyle, recentStyles: List<DjBridgeStyle>): Double {
        if (recentStyles.isEmpty()) return 0.0
        val lastSame = if (recentStyles.lastOrNull() == style) 1.85 else 0.0
        val recentCount = recentStyles.takeLast(3).count { it == style }
        val freqPenalty = when {
            recentCount >= 2 -> 1.65
            recentCount == 1 && recentStyles.lastOrNull() != style -> 0.65
            else -> 0.0
        }
        return lastSame + freqPenalty
    }

    private fun isBeatMatchReady(m: AudioMetrics?): Boolean {
        if (m == null || (m.bpm ?: 0.0) <= 20.0) return false
        if (m.bpmConfidence >= 0.35 && m.beatGrid.isNotEmpty()) return true
        return m.bpmConfidence >= 0.18 && m.beatGrid.size >= 8 && m.beatStrength >= 0.28
    }

    /** Eco a pulso de negra (`1.0 * beatMs`, o `0.5 * beatMs` si el tempo detectado es muy lento) para caer siempre sobre el pulso. */
    private fun straightEchoMs(beatMs: Double): Double {
        if (beatMs <= 20.0) return 500.0
        return when {
            beatMs in 380.0..750.0 -> beatMs
            beatMs > 750.0 -> (beatMs * 0.5).coerceIn(380.0, 750.0)
            else -> (beatMs * 2.0).coerceIn(380.0, 750.0)
        }
    }
}
