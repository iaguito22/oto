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
    // Tramos secos de A antes y de B después: el relevo entre reproductores se cuadra en ellos con pulsos de velocidad.
    // Cada ronda cuesta pulso + búfer + 200 ms: con auriculares BT de ~1 s de búfer, ~1,6 s. Medido el 17/11 en el
    // Nothing: con 5,0/5,2 s solo cabía una ronda y el relevo se hacía con el resto (13–15 ms, golpe doble audible).
    // 2 s más a cada lado dejan sitio a una segunda ronda.
    const val PRE_ROLL_MS = 7_000L
    const val POST_ROLL_MS = 7_200L
    const val NORMAL_WINDOW_MS = 30_000L

    /** DJ normal: la mezcla puede empezar hasta aquí antes del final de A y B entrar hasta aquí desde su principio. */
    const val NORMAL_OUT_WINDOW_MS = 75_000L
    const val NORMAL_IN_WINDOW_MS = 45_000L

    /** DJ rápido: B entra cerca del principio de su trozo (antes entraba hasta 18 s tarde y sonaba <40 s). */
    // El trozo ya incluye la preparación antes de la voz. Solo reservar un ajuste corto de beat; ocho segundos
    // adicionales devolvían al inicio del archivo muchas entradas elegidas en el primer estribillo.
    const val ADHD_IN_EARLY_MS = 1_500L
    const val ADHD_IN_LATE_MS = 4_000L
    /** DJ rápido: lo que debería quedar de B tras una entrada de respaldo fuera de su ventana (un trozo entero). */
    const val ADHD_MIN_B_LEFT_MS = 70_000L
    /** DJ rápido: cuánto puede pasarse la mezcla del final previsto del trozo (para que quepa empezando en frase). */
    const val ADHD_OUT_LATE_MS = 6_000L
    private const val ADHD_FALLBACK_OUT_LATE_MS = 28_000L
    /** DJ rápido: lo que tiene que haber sonado un trozo para poder salir antes de un estribillo que llena su salida. */
    const val ADHD_EARLY_EXIT_MIN_LISTEN_MS = 30_000L

    /**
     * Margen antes de un estribillo con el que entra B cuando empieza por él (listas): cubre la corrección de fase del
     * render (±0,4 beat, ver [ChorusSafety.allows]) sin que la entrada acabe dentro.
     */
    const val INCOMING_CHORUS_LEAD_MS = 450L

    /** Respaldo: fundido corto mínimo cuando tras el último estribillo de A no caben los 6 s habituales. */
    const val MIN_FALLBACK_FADE_MS = 3_000L
    private const val OUTRO_TAIL_MS = 1_250L
    private const val OUTRO_DRY_A_MS = 650L
    private const val MAX_OUTRO_SILENCE_OVERLAP_MS = 3_000L

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
        /** `FilterEcho`: B entra a tempo y en fase (bombo en las dos y estiramiento dentro del límite). */
        val beatAligned: Boolean = false,
        /** Límite de estiramiento (%) que se aplicó a esta pareja (cuadre o eco alineado), para el registro. */
        val stretchLimit: Double? = null,
        /** Instante de A que debe caer en frase (por defecto el arranque de la mezcla; en el corte al drop, el drop). */
        val phraseOutMs: Long? = null,
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

        // Use the lyrics even when callers have not cached the derived sections yet.
        val sectionsA = (outgoingVocals + outgoingLyrics?.let { SongSections.of(it.lines) }.orEmpty()).distinct()
        val sectionsB = (incomingVocals + incomingLyrics?.let { SongSections.of(it.lines) }.orEmpty()).distinct()
        fun protectsChorus(start: Long, end: Long, entry: Long): Boolean =
            !SongSections.overlapsChorus(start, end, sectionsA) &&
                sectionsB.none { it.isChorus && entry > it.startMs && entry < it.endMs }

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
        // Lo que se estira B para ir al tempo de A (no la diferencia de BPM: con mitad/doble no es lo mismo).
        val stretchPct = DjTempoMatch.stretchPct(bpmRel.ratioOutOverIn)
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
                // Si B abre cantando un estribillo y su primer sonido medido cae ya dentro (la letra marca el verso unos
                // ms antes que el detector, o el detector toma el final de la intro), entrar ahí contaba como «a mitad del
                // estribillo» y TODAS las parejas se descartaban: sin mezcla (6 de 11 en la lista de house, 18/11). B
                // empieza por su principio: se entra un poco antes de ese estribillo, sin saltarse nada cantado.
                inMinMs = sectionsB.filter { it.isChorus && inFirstSound > it.startMs && inFirstSound < it.endMs }
                    .minOfOrNull { (it.startMs - INCOMING_CHORUS_LEAD_MS).coerceAtLeast(0L) } ?: inFirstSound
                inMaxMs = min(durB - 6_000L, inFirstSound + 4_000L).coerceAtLeast(inMinMs)
            }
            djMode == DjMode.Adhd && outgoingAdhd != null -> {
                // [outMaxMs] es el límite del FINAL de la mezcla. Antes era el fin del trozo − 2,6 s: la salida planeada
                // (fin − mezcla) no cabía y la mezcla se adelantaba a un compás cualquiera, a veces dentro de la voz.
                val targetOut = outgoingAdhd.fadeOutStartMs.coerceIn(55_000L.coerceAtMost(outLastSound / 2), outLastSound)
                outMaxMs = minOf(outLastSound, max(outgoingAdhd.endMs + ADHD_OUT_LATE_MS, targetOut + 14_000L), outgoingAdhd.startMs + 122_000L)
                    .coerceAtLeast(2_000L)
                outMinMs = max(min(outgoingAdhd.startMs + 56_000L, outgoingAdhd.fadeOutStartMs), targetOut - 14_000L)
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

        // Cruce estirado + vuelta + tramo seco final tienen que caber en B (si no, el puente se rellenaría con ceros y el
        // relevo WAV→B se quedaría sin tramo seco donde cuadrarse).
        fun fitsInB(entryMs: Long, crossMs: Long, ratio: Double): Boolean =
            entryMs + (crossMs * ratio).roundToLong() + DjTempoMatch.bridgeTailSourceMs(bpmA, ratio) + POST_ROLL_MS + 1_500L <= durB

        fun entryInChorusB(ms: Long): Boolean = sectionsB.any { it.isChorus && ms > it.startMs && ms < it.endMs }
        /**
         * La entrada de B llevada a su beat, sin que el ajuste (hasta medio beat) la meta en un estribillo que la entrada
         * sin ajustar respetaba (antes esa pareja se descartaba entera): si pasa, al beat anterior o, si no, sin ajustar.
         */
        fun snapEntry(raw: Long): Long {
            val snapped = snapToBeat(raw, incomingAudio, inMinMs, inMaxMs)
            if (!entryInChorusB(snapped) || entryInChorusB(raw)) return snapped
            val back = snapToBeat(raw, incomingAudio, inMinMs, raw)
            return if (!entryInChorusB(back)) back else raw
        }
        val beatMsA = if (bpmA > 20.0) 60_000.0 / bpmA else 500.0
        /**
         * Limitar el ajuste PCM al borde de la voz. Si la rejilla de B ya cae cerca de una posición permitida, una
         * entrada con estribillo desde el primer golpe también puede cuadrar sin saltarse su ataque.
         */
        fun phaseBoundsB(entry: Long): LongRange {
            val choruses = sectionsB.filter { it.isChorus }
            val first = choruses.filter { it.startMs >= entry }.minOfOrNull { it.startMs }
            val previous = choruses.filter { it.endMs <= entry }.maxOfOrNull { it.endMs }
            val minShift = maxOf(-420L, -entry, (previous ?: 0L) - entry)
            val maxShift = minOf(DjTempoMatch.MAX_PHASE_SHIFT_MS, first?.minus(entry) ?: Long.MAX_VALUE)
            return minShift..maxShift
        }
        fun phaseRoomB(entry: Long): Boolean {
            if (entryInChorusB(entry)) return false
            val room = if (bpmB > 20.0) min(DjTempoMatch.MAX_PHASE_SHIFT_MS, (57_600.0 / bpmB).roundToLong()) else DjTempoMatch.MAX_PHASE_SHIFT_MS
            val bounds = phaseBoundsB(entry)
            if (bounds.last >= room) return true
            val beat = incomingAudio?.nearestBeatMs(entry) ?: return false
            val period = incomingAudio.beatPeriodMs ?: return false
            val offset = beat - entry
            return listOf(offset, offset - period, offset + period).any {
                it >= bounds.first - 45.0 && it <= bounds.last + 45.0
            }
        }
        /**
         * Arranque de A que solo pisa la cola vocal ([SongSections.VOCAL_TAIL_MS]) de un estribillo ya acabado (o el final de
         * su última palabra, tras ajustarlo al beat): se retrasa a cuando la cola termina, en el beat siguiente si lo hay.
         * El DJ rápido sale «en el compás tras la última palabra», a 200–250 ms (`AdhdPlanner`), y la guarda pide 600 ms:
         * se descartaba su propia salida y quedaban respaldos o mezclas fuera de frase (29 y 49 de 85 el 18/11).
         * La cola sigue protegida entera; solo se deja de tirar la pareja.
         */
        fun pastChorusTail(start: Long, hi: Long): Long {
            val s = sectionsA.filter {
                it.isChorus && it.seamFreeMs == 0L &&
                    start >= it.endMs - (beatMsA * 0.6).roundToLong() && start < it.endMs + SongSections.VOCAL_TAIL_MS
            }.maxByOrNull { it.endMs } ?: return start
            val clear = s.endMs + SongSections.VOCAL_TAIL_MS
            if (clear > hi) return start
            return snapToBeat(clear, outgoingAudio, clear, hi)
        }

        // DJ rápido: los límites de frase de cada trozo (ver [AdhdSegment.phrasePointsMs]) son candidatos siempre (no
        // solo los compases más tranquilos), para que haya mezclas que empiecen y entren en frase.
        val adhd = isDjSession && djMode == DjMode.Adhd
        // La salida y la entrada que eligió el propio trozo también valen (con o sin compases medidos).
        val phraseA = if (adhd && outgoingAdhd != null && outgoingAdhd.phrasePointsMs.isNotEmpty()) {
            (outgoingAdhd.phrasePointsMs + outgoingAdhd.fadeOutStartMs).distinct().sorted()
        } else emptyList()
        val phraseB = if (adhd && incomingAdhd != null && incomingAdhd.phrasePointsMs.isNotEmpty()) {
            (incomingAdhd.phrasePointsMs + incomingAdhd.startMs).distinct().sorted()
        } else emptyList()
        val beatTolA = phraseTolerance(bpmA)
        val beatTolB = phraseTolerance(bpmB)
        // Además de los compases más tranquilos (máx. 10), justo al acabar cada bloque cantado de A, pasada su cola: son las
        // salidas sin voz por definición y la poda por compases podía dejarlas fuera (sobre todo con estribillos largos).
        val sectionExitsA = sectionsA.map { it.endMs + SongSections.VOCAL_TAIL_MS }
            .filter { it in outMinMs..(outMaxMs - 2_000L) }
        val candidatesA = (selectOutCandidateBars(barsA, outMinMs, outMaxMs, durA) +
            phraseA.filter { it in outMinMs..(outMaxMs - 2_000L) }.map { barAt(barsA, it) } +
            sectionExitsA.map { barAt(barsA, it) }).distinctBy { it.startMs }
        // En sesiones de DJ, B también puede entrar justo tras un estribillo suyo (su verso siguiente).
        val chorusExitsB = if (!isDjSession) emptyList() else sectionsB.filter { it.isChorus }
            .map { it.endMs + SongSections.VOCAL_TAIL_MS }.filter { it in inMinMs..inMaxMs }
        val candidatesB = (selectInCandidateBars(barsB, inMinMs, inMaxMs, durB, !isDjSession) +
            phraseB.filter { it in inMinMs..inMaxMs }.map { barAt(barsB, it) } +
            chorusExitsB.map { barAt(barsB, it) }).distinctBy { it.startMs }
        val zoneTimbreShock = hasTimbreShock(representativeBar(candidatesA), representativeBar(candidatesB))

        // 1. Evaluar `LyricLoopCut` (prioridad alta si ambas letras reales coinciden con intensidades compatibles).
        var lyricCandidate: CandidatePair? = null
        if (recentStyles.lastOrNull() != DjBridgeStyle.LyricLoopCut && outgoingLyrics != null && incomingLyrics != null) {
            val match = DjLyricMatcher.findSharedLoopMatch(
                outLyrics = outgoingLyrics,
                inLyrics = incomingLyrics,
                outMinMs = outMinMs + 1_000L,
                outMaxMs = max(outMinMs + 1_500L, outMaxMs - 1_200L),
                inMinMs = inMinMs,
                // En DJ rápido la ventana de B es estrecha; para una frase compartida se acepta entrar algo más tarde (el
                // trozo de B se recoloca desde donde entra y dura lo mínimo igual).
                inMaxMs = if (!isDjSession) min(inMaxMs, inMinMs + 1_500L) else
                    max(inMinMs + 1_000L, inMaxMs - 1_500L + (if (djMode == DjMode.Adhd) 18_000L else 6_000L)),
                bpmA = if (bpmA > 20.0) bpmA else 110.0,
                requireOpeningPhrase = !isDjSession,
            )
            val loopSpec = if (isDjSession) match else match?.copy(inEntryMs = inMinMs)
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
                // Estirar B para cuadrar la frase también es cambiar su velocidad: solo con bombo al pulso en las dos
                // (como pidió Iago) y hasta el límite con voz (6 %); si no, la frase entra a su velocidad.
                val loopKicks = DjTempoMatch.kickOnBeat(barsA, loopSpec.outLoopStartMs, loopSpec.outLoopEndMs) >= DjTempoMatch.MIN_KICK_ON_BEAT &&
                    DjTempoMatch.kickOnBeat(barsB, loopSpec.inEntryMs, max(loopSpec.inPhraseEndMs, loopSpec.inEntryMs + 2_000L)) >=
                    DjTempoMatch.MIN_KICK_ON_BEAT
                // El tope solo importa si se va a estirar: sin bombo la frase entra a 1× y el tempo no la descarta.
                val tempoOk = !beatReliable || !loopKicks || stretchPct <= DjTempoMatch.MAX_STRETCH_PCT
                val harmonyOk = !bothMeasured ||
                    outgoingAudio.camelot.isNullOrBlank() ||
                    incomingAudio.camelot.isNullOrBlank() ||
                    harmonic >= 0.42
                if (acousticOk && tempoOk && harmonyOk &&
                    protectsChorus(loopSpec.outLoopStartMs, loopSpec.outLoopEndMs, loopSpec.inEntryMs)) {
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
                    // Solo se estira si además cabe en B la vuelta a 1× (si no, la frase entra a su velocidad).
                    val loopStretch = beatReliable && loopKicks && stretchPct <= DjTempoMatch.MAX_STRETCH_PCT &&
                        fitsInB(loopSpec.inEntryMs, loopTransDur, bpmRel.ratioOutOverIn)
                    lyricCandidate = CandidatePair(
                        style = DjBridgeStyle.LyricLoopCut,
                        cueOutStartMs = loopSpec.outLoopStartMs,
                        cueOutEndMs = loopSpec.outLoopEndMs,
                        entryInMs = loopSpec.inEntryMs,
                        transitionDurMs = loopTransDur,
                        pickupMs = 0L,
                        silenceMs = 0L,
                        tempoRatio = if (loopStretch) bpmRel.ratioOutOverIn else 1.0,
                        echoDelayMs = 360.0,
                        score = 12.0 + loopSpec.confidence * 3.0,
                        lyricLoop = loopSpec,
                        reason = "Bucle vocal compartido: «${loopSpec.phraseText}»",
                    )
                }
            }
        }
        lyricCandidate?.let { win ->
            val loop = win.lyricLoop!!
            val kA = DjTempoMatch.kickOnBeat(barsA, loop.outLoopStartMs, loop.outLoopEndMs)
            val kB = DjTempoMatch.kickOnBeat(barsB, loop.inEntryMs, max(loop.inPhraseEndMs, loop.inEntryMs + 2_000L))
            val note = "estira %.1f %%, bombo A %.2f B %.2f, bucle vocal %s".format(
                DjTempoMatch.stretchPct(win.tempoRatio), kA, kB,
                if (DjTempoMatch.isStretched(win.tempoRatio)) "estirado (lím %.0f %%)".format(DjTempoMatch.MAX_STRETCH_PCT) else "a 1×",
            )
            return buildSpec(win, durA, durB, bpmA, bpmB, harmonic, gainMatch, outgoingAudio, incomingAudio).copy(tempoNote = note)
        }

        // 2. Evaluar parejas `(barOut, barIn)` entre los estilos instrumentales/rítmicos.
        val avgLufsA = barsA.map { it.lufs }.average().let { if (it.isNaN()) lufsA else it }
        val avgLufsB = barsB.map { it.lufs }.average().let { if (it.isNaN()) lufsB else it }
        val avgDrumA = barsA.map { it.drumEnergy }.average().let { if (it.isNaN()) 0.4 else it }
        val avgDrumB = barsB.map { it.drumEnergy }.average().let { if (it.isNaN()) 0.4 else it }

        var best: CandidatePair? = null
        // Por qué no salió el cuadre de beats (para `oto.mix`): mejor bombo visto y descartes por motivo.
        var bestKickA = 0.0
        var bestKickB = 0.0
        var rejKick = 0
        var rejVoice = 0
        var rejFit = 0
        var rejTimbre = 0
        var limitSeen = DjTempoMatch.MAX_STRETCH_INSTRUMENTAL_PCT

        // DJ rápido: las mezclas que no empiezan en frase, cortan un bloque cantado de A o hacen entrar B a mitad de
        // verso solo valen si no hay ninguna otra (cubo «relajado», con castigo); nunca por encima de una limpia.
        var bestRelaxed: CandidatePair? = null
        var rejPhrase = 0
        var rejVocal = 0
        // Descartes por estribillo, por lado (para atribuir un «sin mezcla» en `oto.mix`).
        var rejChorusA = 0
        var rejChorusB = 0
        fun adhdClean(cand: CandidatePair): Boolean {
            if (!adhd || cand.style == DjBridgeStyle.LyricLoopCut) return true
            val refOut = cand.phraseOutMs ?: cand.cueOutStartMs
            val onPhraseA = phraseA.isEmpty() || phraseA.any { abs(it - refOut) <= beatTolA }
            val onPhraseB = phraseB.isEmpty() || phraseB.any { abs(it - cand.entryInMs) <= beatTolB }
            if (!onPhraseA || !onPhraseB) { rejPhrase++; return false }
            val outVoc = DjLyricMatcher.vocalOccupancyRatio(outgoingLyrics, cand.cueOutStartMs, cand.cueOutEndMs)
                ?: vocalOverlapSections(sectionsA, cand.cueOutStartMs, cand.cueOutEndMs)
            // El corte al drop tiene su propia guarda (no parte palabras y solo corta la voz en la costura pre-estribillo →
            // estribillo, que es justo donde debe cortar).
            val cutsA = cand.style != DjBridgeStyle.EnergyDropCut && (
                inSection(sectionsA, cand.cueOutStartMs) || DjLyricMatcher.isMidVocal(outgoingLyrics, cand.cueOutStartMs) ||
                    (outVoc != null && outVoc > 0.25)
                )
            val cutsB = inSection(sectionsB, cand.entryInMs) || DjLyricMatcher.isMidVocal(incomingLyrics, cand.entryInMs)
            if (cutsA || cutsB) { rejVocal++; return false }
            return true
        }
        /**
         * Si [pastChorusTail] retrasó el arranque solo por la cola vocal, la frase de referencia sigue siendo el compás
         * candidato (el límite de frase está tras la última palabra; la mezcla empieza en cuanto acaba su cola).
         */
        fun tailShiftedPhrase(barMs: Long, gridMs: Long, startMs: Long): Long? =
            barMs.takeIf { startMs != gridMs && abs(gridMs - barMs) <= beatTolA }
        /** DJ rápido: premio si la sección de B llega cuando acaba la mezcla (B ya a su volumen) y no antes. */
        fun adhdLanding(cand: CandidatePair): Double {
            val target = incomingAdhd?.entrySectionMs ?: return 0.0
            if (!adhd || target <= incomingAdhd.startMs || cand.style == DjBridgeStyle.LyricLoopCut) return 0.0
            val inSpan = (cand.transitionDurMs * cand.tempoRatio).roundToLong()
            val arrives = cand.entryInMs + inSpan
            val barMs = if (bpmB > 20.0) 240_000.0 / bpmB else 2_000.0
            val off = (target - arrives) / barMs // >0: la sección llega después de la mezcla
            return when {
                off < -0.5 -> -0.9 // la voz de B llega con A todavía delante
                off <= 2.5 -> 0.8
                else -> 0.8 - 0.15 * (off - 2.5).coerceAtMost(8.0)
            }
        }
        fun consider(raw: CandidatePair) {
            // A chorus is a constraint, not a score which style/tempo/variety can outweigh.
            if (!protectsChorus(raw.cueOutStartMs, raw.cueOutEndMs, raw.entryInMs)) {
                if (SongSections.overlapsChorus(raw.cueOutStartMs, raw.cueOutEndMs, sectionsA)) rejChorusA++
                if (entryInChorusB(raw.entryInMs)) rejChorusB++
                return
            }
            val cand = raw.copy(score = raw.score + adhdLanding(raw))
            if (adhdClean(cand)) {
                if (best == null || cand.score > best!!.score) best = cand
            } else {
                val relaxed = cand.copy(score = cand.score - 3.0)
                if (bestRelaxed == null || relaxed.score > bestRelaxed!!.score) bestRelaxed = relaxed
            }
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

        // Puertas de la canción entera para el cuadre (las de la pareja se cuentan abajo).
        val beatGate = when {
            !beatReliable -> "pulso dudoso"
            stretchPct > DjTempoMatch.MAX_STRETCH_INSTRUMENTAL_PCT -> "tempo lejos"
            zoneTimbreShock -> "choque de timbre"
            avgDrumA < 0.16 || avgDrumB < 0.16 -> "poca batería (A %.2f B %.2f)".format(avgDrumA, avgDrumB)
            harmonic < DjTempoMatch.MIN_BEAT_MATCH_HARMONY -> "armonía %.2f".format(harmonic)
            else -> null
        }
        val beatGateOpen = beatGate == null

        for (bOut in candidatesA) {
            val midVocalOut = DjLyricMatcher.isMidVocal(outgoingLyrics, bOut.endMs) ||
                inSection(outgoingVocals, bOut.endMs)
            val exitVocals = (DjLyricMatcher.vocalOccupancyRatio(outgoingLyrics, bOut.startMs, bOut.endMs)
                ?: vocalOverlapSections(outgoingVocals, bOut.startMs, bOut.endMs)
                ?: bOut.vocalRatio)
            val exitPenalty = (2.2 * exitVocals) + (2.3 * bOut.soloRisk) + (if (midVocalOut) 2.4 else 0.0)
            val outLowIntensity = (
                0.55 * ((avgLufsA - bOut.lufs - 0.8) / 3.5).coerceIn(0.0, 1.0) +
                    0.45 * ((0.38 - bOut.drumEnergy) / 0.22).coerceIn(0.0, 1.0)
                ).coerceIn(0.0, 1.0)

            for (bIn in candidatesB) {
                val rawEntry = if (!isDjSession) inMinMs else bIn.startMs.coerceIn(inMinMs, inMaxMs)
                val midVocalIn = DjLyricMatcher.isMidVocal(incomingLyrics, rawEntry)
                val entryVocals = (DjLyricMatcher.vocalOccupancyRatio(incomingLyrics, rawEntry, rawEntry + 6_000L)
                    ?: vocalOverlapSections(incomingVocals, rawEntry, rawEntry + 6_000L)
                    ?: bIn.vocalRatio)
                val inCalmZone = (
                    0.50 * ((avgLufsB - bIn.lufs - 0.6) / 3.5).coerceIn(0.0, 1.0) +
                        0.50 * ((0.40 - bIn.drumEnergy) / 0.24).coerceIn(0.0, 1.0)
                    ).coerceIn(0.0, 1.0)

                val harshDiff = abs(bOut.harshness - bIn.harshness)
                val pairTimbreShock = hasTimbreShock(bOut, bIn)
                val lufsDiffLocal = abs(bOut.lufs - bIn.lufs)
                // Aproximación (ventanas fijas, tope instrumental) de si esta pareja se podría cuadrar: SOLO para no
                // castigar el fundido cuando el cuadre no es posible. Las puertas reales están en cada estilo.
                val pairMayBeatMatch = beatReliable &&
                    stretchPct <= DjTempoMatch.MAX_STRETCH_INSTRUMENTAL_PCT &&
                    DjTempoMatch.kickOnBeat(barsA, bOut.startMs, bOut.endMs + 4_000L) >= DjTempoMatch.MIN_KICK_ON_BEAT &&
                    DjTempoMatch.kickOnBeat(barsB, rawEntry, rawEntry + 6_000L) >= DjTempoMatch.MIN_KICK_ON_BEAT

                // A. `BeatMatchLongMix`
                if (beatGateOpen && pairTimbreShock) rejTimbre++
                if (beatReliable && stretchPct <= DjTempoMatch.MAX_STRETCH_INSTRUMENTAL_PCT && !zoneTimbreShock && !pairTimbreShock && avgDrumA >= 0.16 && avgDrumB >= 0.16 && harmonic >= DjTempoMatch.MIN_BEAT_MATCH_HARMONY) {
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
                    val startGrid = onGridStart(bOut.startMs, outMinMs, outMaxMs - mixDur, barsA, outgoingAudio)
                    val startOut = pastChorusTail(startGrid, outMaxMs - mixDur)
                    val beatEntry = snapEntry(rawEntry)
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
                    // B se estira mientras suenan juntas y durante su vuelta a 1×: si canta ahí, el límite es más bajo.
                    val ratio = bpmRel.ratioOutOverIn
                    val stretchedSpanMs = (mixDur * ratio).roundToLong() + DjTempoMatch.returnGlideSourceMs(bpmA, ratio)
                    val inVocStretched = DjLyricMatcher.vocalOccupancyRatio(incomingLyrics, beatEntry, beatEntry + stretchedSpanMs)
                        ?: vocalOverlapSections(incomingVocals, beatEntry, beatEntry + stretchedSpanMs)
                        ?: bIn.vocalRatio
                    val maxStretch = DjTempoMatch.maxStretchPct(inVocStretched)
                    // Cuadrar beats solo funciona con bombo al pulso en las dos durante el cruce (no basta con un
                    // pulso fiable: hi-hats o una guitarra rítmica también lo dan).
                    val kickA = DjTempoMatch.kickOnBeat(barsA, startOut, startOut + mixDur)
                    val kickB = DjTempoMatch.kickOnBeat(barsB, beatEntry, beatEntry + (mixDur * ratio).roundToLong())
                    val kicksOk = kickA >= DjTempoMatch.MIN_KICK_ON_BEAT && kickB >= DjTempoMatch.MIN_KICK_ON_BEAT

                    val tempoCloseness = (1.0 - stretchPct / maxStretch).coerceIn(0.0, 1.0)
                    val score = 7.15 +
                        phraseFit +
                        1.85 * tempoCloseness +
                        1.25 * harmonic +
                        1.10 * min(kickA, kickB) -
                        bothSingingPenalty -
                        0.85 * exitPenalty -
                        0.40 * harshDiff -
                        (if (tandaChanged) 0.55 else 0.0) -
                        (lufsDiffLocal * 0.20) -
                        edgeCost(startOut + mixDur, beatEntry) -
                        fatigueBeat
                    val fits = fitsInB(beatEntry, mixDur, ratio)
                    bestKickA = max(bestKickA, kickA)
                    bestKickB = max(bestKickB, kickB)
                    limitSeen = min(limitSeen, maxStretch)
                    when {
                        !kicksOk -> rejKick++
                        stretchPct > maxStretch -> rejVoice++
                        !fits || !phaseRoomB(beatEntry) -> rejFit++
                    }
                    if (stretchPct <= maxStretch && kicksOk && fits && phaseRoomB(beatEntry)) consider(
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
                            stretchLimit = maxStretch,
                            phraseOutMs = tailShiftedPhrase(bOut.startMs, startGrid, startOut),
                        ),
                    )
                }

                // C. `FilterEcho` (pasa-altos suave 12 dB/oct + LPF apertura en B; eco a negra con estela sobre B cuando va a pulso y en armonía)
                // Eco a tempo: B entra en fase desde el principio del cruce y, si hace falta, se estira como en el
                // cuadre de beats; así que pide lo mismo, en las ventanas reales del eco: bombo en las dos y
                // estiramiento dentro del límite según cante B en la parte estirada.
                val sameTempo = beatReliable && !DjTempoMatch.isStretched(bpmRel.ratioOutOverIn)
                val alignedEchoDur = (defaultMixDur * 0.90).roundToLong()
                    .coerceIn(4_500L, min(8_500L, max(4_500L, durA - bOut.startMs)))
                val alignedEchoStart = pastChorusTail(
                    onGridStart(bOut.startMs, outMinMs, outMaxMs - alignedEchoDur, barsA, outgoingAudio), outMaxMs - alignedEchoDur,
                )
                val alignedEchoEntry = snapEntry(rawEntry)
                var echoLimit = DjTempoMatch.MAX_STRETCH_INSTRUMENTAL_PCT
                val echoCanBeatMatch = beatReliable && phaseRoomB(alignedEchoEntry) && run {
                    val r = bpmRel.ratioOutOverIn
                    val span = (alignedEchoDur * r).roundToLong() + DjTempoMatch.returnGlideSourceMs(bpmA, r)
                    val voc = DjLyricMatcher.vocalOccupancyRatio(incomingLyrics, alignedEchoEntry, alignedEchoEntry + span)
                        ?: vocalOverlapSections(incomingVocals, alignedEchoEntry, alignedEchoEntry + span)
                        ?: bIn.vocalRatio
                    echoLimit = DjTempoMatch.maxStretchPct(voc)
                    stretchPct <= echoLimit &&
                        fitsInB(alignedEchoEntry, alignedEchoDur, r) &&
                        DjTempoMatch.kickOnBeat(barsA, alignedEchoStart, alignedEchoStart + alignedEchoDur) >= DjTempoMatch.MIN_KICK_ON_BEAT &&
                        DjTempoMatch.kickOnBeat(barsB, alignedEchoEntry, alignedEchoEntry + (alignedEchoDur * r).roundToLong()) >=
                        DjTempoMatch.MIN_KICK_ON_BEAT
                }
                // Eco a negra de A: con B cuadrada en fase, o al mismo tempo si B entra sin batería (si la lleva y no está en
                // fase, los ecos de A y sus golpes no coinciden y suena a golpe doble).
                val useBeatEcho = (echoCanBeatMatch || (sameTempo && bIn.drumEnergy < 0.30)) &&
                    !tandaChanged &&
                    harmonic >= 0.45 &&
                    (outgoingAudio?.bpmConfidence ?: 0.0) >= 0.50 &&
                    (outgoingAudio?.beatGrid?.size ?: 0) >= 8 &&
                    avgDrumA < 0.52
                val maxEchoDur = if (echoCanBeatMatch) 8_500L else 6_800L
                val echoDur = (defaultMixDur * (if (echoCanBeatMatch) 0.90 else 0.78)).roundToLong()
                    .coerceIn(4_500L, min(maxEchoDur, max(4_500L, durA - bOut.startMs)))
                val echoGrid = onGridStart(bOut.startMs, outMinMs, outMaxMs - echoDur, barsA, outgoingAudio)
                val echoStart = pastChorusTail(echoGrid, outMaxMs - echoDur)
                val echoEntry = if (echoCanBeatMatch) snapEntry(rawEntry) else rawEntry
                val beatEcho = if (useBeatEcho) straightEchoMs(beatA) else 0.0
                val trueTimbreReason = zoneTimbreShock && pairTimbreShock
                val calmZoneFilterBoost = 1.30 * outLowIntensity + 0.65 * inCalmZone
                val echoBoost = (if (trueTimbreReason) 2.55 else 0.0) +
                    (if (tandaChanged) 1.25 else 0.0) +
                    (if (harmonic < 0.36) 1.05 else 0.0) +
                    (if (bpmRel.relDiffPct > 14.0 && entryVocals > 0.35) 0.70 else 0.0) +
                    calmZoneFilterBoost -
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
                        beatAligned = echoCanBeatMatch,
                        stretchLimit = if (echoCanBeatMatch) echoLimit else null,
                        phraseOutMs = tailShiftedPhrase(bOut.startMs, echoGrid, echoStart),
                        reason = when {
                            useBeatEcho && trueTimbreReason -> "Filtro + eco a pulso para cambio de textura"
                            useBeatEcho -> "Filtro pasa-altos + eco a pulso"
                            trueTimbreReason -> "Filtro pasa-altos para cambio limpio de textura"
                            else -> "Filtro pasa-altos"
                        },
                    ),
                )

                // D. `SmartFade` (fundido musical cálido para temas más melódicos, zonas de baja intensidad o tempos incompatibles)
                // Tempo cercano (sin pedir bombo): el fundido dura algo más y B entra en su beat.
                val fadeTempoClose = beatReliable && stretchPct <= DjTempoMatch.MAX_STRETCH_INSTRUMENTAL_PCT
                val fadeBothDrums = avgDrumA >= 0.28 && avgDrumB >= 0.28
                val localCalmPair = bOut.drumEnergy < 0.30 || bIn.drumEnergy < 0.30 ||
                    (outLowIntensity >= 0.45 && inCalmZone >= 0.35)
                val maxFadeDur = 7_200L
                val fadeDur = (if (fadeTempoClose) (defaultMixDur * 0.85).roundToLong() else (defaultMixDur * 0.78).roundToLong())
                    .coerceIn(4_000L, min(maxFadeDur, max(4_000L, durA - bOut.startMs)))
                val fadeGrid = onGridStart(bOut.startMs, outMinMs, outMaxMs - fadeDur, barsA, outgoingAudio)
                val fadeStart = pastChorusTail(fadeGrid, outMaxMs - fadeDur)
                val fadeEntry = if (fadeTempoClose) snapEntry(rawEntry) else rawEntry
                // Fundido a tempo (opcional en el renderizador, `beatAligned = true`): B al tempo de A y en fase durante el
                // cruce, y vuelta a 1× ya sola. Pide lo mismo que el cuadre en las ventanas reales del fundido: bombo al
                // pulso en las dos (A en su cruce, B en el audio que gasta), límite 6 % con voz / 8 % instrumental en el
                // cruce + vuelta, y que todo eso más el tramo seco quepa en B. Si no, el fundido de siempre a 1×.
                val fadeRatio = bpmRel.ratioOutOverIn
                val fadeSpanB = DjTempoMatch.lockedCrossSourceMs(fadeDur, bpmA, fadeRatio)
                var fadeLimit = DjTempoMatch.MAX_STRETCH_INSTRUMENTAL_PCT
                val fadeLock = fadeTempoClose && harmonic >= 0.36 && phaseRoomB(fadeEntry) && run {
                    val voc = DjLyricMatcher.vocalOccupancyRatio(incomingLyrics, fadeEntry, fadeEntry + fadeSpanB)
                        ?: vocalOverlapSections(incomingVocals, fadeEntry, fadeEntry + fadeSpanB)
                        ?: bIn.vocalRatio
                    fadeLimit = DjTempoMatch.maxStretchPct(voc)
                    stretchPct <= fadeLimit &&
                        fadeEntry + fadeSpanB + POST_ROLL_MS + 1_500L <= durB &&
                        DjTempoMatch.kickOnBeat(barsA, fadeStart, fadeStart + fadeDur) >= DjTempoMatch.MIN_KICK_ON_BEAT &&
                        DjTempoMatch.kickOnBeat(barsB, fadeEntry, fadeEntry + (fadeDur * fadeRatio).roundToLong()) >=
                        DjTempoMatch.MIN_KICK_ON_BEAT
                }
                val calmBonus = (if (avgDrumA < 0.32 || avgDrumB < 0.32) 1.35 else 0.0) +
                    1.50 * outLowIntensity +
                    0.95 * inCalmZone
                // Castigo al fundido solo si el cuadre de beats era posible (con bombo): si no, el fundido es lo mejor.
                // A tempo y en fase los bombos ya no chocan: ese castigo no aplica (el cuadre suele seguir ganando por
                // puntuación propia; el fundido a tempo es la alternativa cuando el cuadre está cansado o no cabe).
                val closeDrumFadePenalty = if (!fadeLock && pairMayBeatMatch && fadeBothDrums && !localCalmPair) 2.35 else 0.0
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
                        tempoRatio = if (fadeLock) fadeRatio else 1.0,
                        echoDelayMs = 320.0,
                        score = fadeScore,
                        beatAligned = fadeLock,
                        stretchLimit = if (fadeLock) fadeLimit else null,
                        phraseOutMs = tailShiftedPhrase(bOut.startMs, fadeGrid, fadeStart),
                        reason = if (fadeLock) "Fundido musical suave a tempo" else "Fundido musical suave",
                    ),
                )
            }
        }

        // B. `EnergyDropCut`: A sube hacia un estribillo/drop y, justo cuando iba a caer, se calla (eco amplio a pulso
        // + respiro de tensión) y entra el riser de B hasta el drop de B. Nunca cortar palabras, frases ni en mitad de estribillo.
        run {
            val dropsA = liftBars(barsA, outMinMs + 2_500L, outMaxMs)
            // A clean instrumental break is also a musical place to release A into a strong opening in B.
            val breaksA = if (beatReliable && (!isDjSession || adhd)) outgoingAudio?.bars.orEmpty().zipWithNext().mapNotNull { (before, after) ->
                val lufsDrop = before.lufs - after.lufs
                val drumDrop = before.drumEnergy - after.drumEnergy
                (after.startMs to (0.4 + 0.1 * lufsDrop).coerceAtMost(0.95)).takeIf {
                    after.startMs in (outMinMs + 2_500L)..outMaxMs && before.endMs == after.startMs &&
                        dropsA.none { (lift) -> lift.startMs == after.endMs } &&
                        before.vocalRatio < 0.24 && after.vocalRatio < 0.24 &&
                        before.kickOnBeat >= DjTempoMatch.MIN_KICK_ON_BEAT &&
                        before.drumEnergy >= 0.48 && lufsDrop >= 2.0 && drumDrop >= 0.18
                }
            }.toMap() else emptyMap()
            val dropExits = dropsA + barsA.mapNotNull { bar -> breaksA[bar.startMs]?.let { bar to it } }
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
            for ((barA, liftA) in dropExits) {
                val atBreak = barA.startMs in breaksA
                val dropAt = snapToBeat(barA.startMs, outgoingAudio, outMinMs + 2_000L, outMaxMs)
                val cutMs = if (atBreak) dropAt else dropAt - preDropGap
                val gapMs = if (atBreak) (effBeatA * 2.0).roundToLong().coerceIn(750L, 1_150L) else gapEchoMs
                val prevA = barsA.getOrNull(barsA.indexOf(barA) - 1) ?: barA
                val (dlA, ddA) = rise(barsA, barA)

                // Nunca cortar en mitad de un estribillo ni partiendo palabras o versos sin respiro.
                val secAtCut = outgoingVocals.firstOrNull { cutMs in (it.startMs + 250L)..(it.endMs - 250L) }
                if (secAtCut?.isChorus == true) continue
                val preChorusIntoChorus = outgoingVocals.any { it.isChorus && it.startMs in (cutMs - 120L)..(dropAt + 2_200L) }
                if (DjLyricMatcher.cutsWordOrPhrase(outgoingLyrics, cutMs, allowChorusSeam = preChorusIntoChorus && !atBreak)) continue
                if (atBreak && (secAtCut != null ||
                        (DjLyricMatcher.vocalOccupancyRatio(outgoingLyrics, cutMs - pickup, cutMs) ?: 0.0) > 0.05)) continue
                if (secAtCut != null) {
                    val nearSecEnd = cutMs >= secAtCut.endMs - 1_400L && (dlA >= 1.5 || ddA >= 0.14)
                    if (outgoingLyrics == null || (!preChorusIntoChorus && !nearSecEnd)) continue
                }

                // Sin letra ni estructura vocal conocida: exigir una subida clara sin voz densa encima.
                val blindNoLyrics = outgoingLyrics == null && outgoingVocals.isEmpty()
                if (blindNoLyrics && !atBreak) {
                    if (dlA < 1.6 && ddA < 0.14) continue
                    if (prevA.vocalRatio >= 0.35 || (prevA.vocalRatio >= 0.30 && barA.vocalRatio >= 0.28)) continue
                }
                val blindVocalRisk = blindNoLyrics && (prevA.vocalRatio >= 0.27 || barA.vocalRatio >= 0.30)
                val blindSoftBumpPenalty = if (blindNoLyrics && liftA < 0.42) 1.4 else 0.0
                val adhdBonus = when {
                    !adhd -> 0.0
                    preChorusIntoChorus -> 0.65
                    !blindNoLyrics -> 0.50
                    liftA >= 0.55 && prevA.vocalRatio < 0.27 && barA.vocalRatio < 0.27 -> 0.55
                    else -> 0.0
                }
                for (bIn in dropsB) {
                    val entryMs = if (isDjSession) snapEntry(bIn.startMs.coerceIn(inMinMs, inMaxMs)) else inMinMs
                    if (DjLyricMatcher.isMidVocal(incomingLyrics, entryMs)) continue
                    if (atBreak && (bIn.drumEnergy < 0.55 || bIn.kickOnBeat < DjTempoMatch.MIN_KICK_ON_BEAT ||
                            incomingAudio?.nearestBeatMs(entryMs)?.let { abs(it - entryMs) <= 100.0 } != true)) continue
                    val lufsJump = abs((if (atBreak) prevA.lufs else barA.lufs) - bIn.lufs)
                    if (lufsJump > 5.5) continue
                    val liftB = rise(barsB, bIn).first
                    val score = 5.95 +
                        1.6 * liftA +
                        liftB.coerceIn(0.0, 5.0) * 0.40 +
                        1.2 * bIn.drumEnergy +
                        adhdBonus +
                        (if (preChorusIntoChorus) 0.40 else 0.0) +
                        (if (tandaChanged) 0.35 else 0.0) +
                        (if (beatReliable) 0.3 else -0.6) -
                        (if (blindVocalRisk) 2.2 else 0.0) -
                        blindSoftBumpPenalty -
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
                            transitionDurMs = pickup + gapMs + 750L,
                            pickupMs = pickup,
                            silenceMs = gapMs,
                            tempoRatio = 1.0,
                            echoDelayMs = echoStepMs,
                            score = score,
                            phraseOutMs = dropAt,
                            reason = if (atBreak) "Corte en el descanso instrumental + entrada al pulso"
                                else "Corte con ecos a pulso + respiro (${gapMs} ms) y riser al drop",
                        ),
                    )
                }
            }
        }

        // If the segment/window contains no clean candidate, wait until a whole chorus has finished.
        // Include section ends explicitly: pruning to ten quiet bars can otherwise discard every safe exit.
        val fallbackDur = 6_000L.coerceAtMost(durA)
        // DJ rápido: antes los límites de frase (B en su entrada prevista, A tras acabar un bloque), y fuera de la
        // ventana también: mejor salir algo tarde que no mezclar y oír la canción entera hasta el final.
        val fallbackEntry = (phraseB.filter { it in inMinMs..inMaxMs }.sortedBy { abs(it - (incomingAdhd?.startMs ?: it)) } +
            listOf(inMinMs) + sectionsB.filter { it.isChorus }.flatMap { listOf(it.startMs, it.endMs + SongSections.VOCAL_TAIL_MS) } +
            listOfNotNull(inMaxMs.takeIf { adhd }))
            .firstOrNull { it in inMinMs..inMaxMs && sectionsB.none { s -> s.isChorus && it > s.startMs && it < s.endMs } }
            // Toda la ventana cae dentro de un estribillo de B (su trozo empieza a mitad de uno): se entra en el borde más
            // cercano (su inicio o su final) aunque quede fuera. Antes no había mezcla y el aviso de la cola desaparecía
            // (medido el 17/11 en DJ rápido, dos veces en 15 min).
            // Solo en DJ rápido (en listas normales B empieza por el principio); antes los bordes que dejan un trozo
            // entero de B (≥ 70 s), y solo si no hay, los que dejan al menos 20 s.
            ?: sectionsB.takeIf { adhd }.orEmpty().filter { it.isChorus }
                .flatMap { listOf(it.startMs, it.endMs + SongSections.VOCAL_TAIL_MS) }
                .filter { it >= inFirstSound && it <= durB - 20_000L && sectionsB.none { s -> s.isChorus && it > s.startMs && it < s.endMs } }
                .sortedWith(compareBy({ it > durB - ADHD_MIN_B_LEFT_MS }, { abs(it - (incomingAdhd?.startMs ?: inMinMs)) }))
                .firstOrNull()
        // Si tras el último bloque protegido de A no caben los 6 s (estribillo hasta pocos segundos del final, medido en
        // listas y en reguetón del DJ normal: «sin mezcla» y cambio seco), un fundido más corto sobre la cola instrumental,
        // de al menos [MIN_FALLBACK_FADE_MS] y sin pisar nada protegido. Solo mientras A sigue sonando.
        // En rápido, un estribillo puede acabar un poco después de la ventana. Darle margen aún permite salir antes
        // del final de la pista, pero una salida alternativa que ya eligió el trozo tiene su propia ventana.
        val fallbackEnd = if (adhd && outgoingAdhd != null && outgoingAdhd.endMs < durA) {
            val limit = if (outgoingAdhd.fallbackExit) outMaxMs else max(outMaxMs, outgoingAdhd.endMs + ADHD_FALLBACK_OUT_LATE_MS)
            min(durA - AdhdPlanner.FALLBACK_END_MARGIN_MS, limit)
        } else durA
        val minFallback = if (adhd) 2_000L else MIN_FALLBACK_FADE_MS
        fun fallbackLen(start: Long): Long = min(fallbackDur, fallbackEnd - start)
        val fallbackStarts = (phraseA.filter { it >= outMinMs && !inSection(sectionsA, it) } +
            listOf(outMinMs, (outMaxMs - fallbackDur).coerceAtLeast(outMinMs)) +
            listOfNotNull(outgoingAdhd?.fadeOutStartMs.takeIf { adhd }) +
            sectionsA.map { it.endMs + SongSections.VOCAL_TAIL_MS }).distinct().filter {
            val len = fallbackLen(it)
            it >= outMinMs && len >= min(fallbackDur, minFallback) && it <= outLastSound - 1_500L &&
                fallbackEntry != null && protectsChorus(it, it + len, fallbackEntry)
        }
        val windowFallbackStart = fallbackStarts.minWithOrNull(
            // Mejor con los 6 s enteros; entre iguales, DJ rápido el primero (sale de su trozo cuanto antes) y el resto el
            // más cercano al final de A (antes era el primero de la ventana: hasta 75 s antes del final en DJ normal).
            compareBy<Long>({ fallbackLen(it) < fallbackDur }, { if (adhd) it else abs(outLastSound - fallbackDur - it) }),
        )
        // DJ rápido sin salida limpia en su ventana (toda cae en un estribillo de A): antes de ese estribillo, si el trozo ya
        // ha sonado [ADHD_EARLY_EXIT_MIN_LISTEN_MS]; la más tardía (lo más cerca del final previsto), y mejor en un límite
        // de frase sin voz. Antes se esperaba a que acabara el estribillo, que solía ser el final de la canción (escucha
        // del 24/11: fundidos de respaldo a 153 de 160 s y a 199 de 204 s, en pleno estribillo).
        val earlyFallbackStart = if (windowFallbackStart == null && adhd && outgoingAdhd != null && fallbackEntry != null) {
            val earliest = outgoingAdhd.startMs + ADHD_EARLY_EXIT_MIN_LISTEN_MS
            fun fits(start: Long) = start in earliest until outMinMs && start + fallbackDur <= outLastSound &&
                protectsChorus(start, start + fallbackDur, fallbackEntry)
            phraseA.filter { !inSection(sectionsA, it) && fits(it) }.maxOrNull()
                ?: (sectionsA.filter { it.isChorus }.map { it.startMs - fallbackDur } +
                    sectionsA.map { it.endMs + SongSections.VOCAL_TAIL_MS }).filter(::fits).maxOrNull()
        } else null
        val fallbackStart = windowFallbackStart ?: earlyFallbackStart
        val fallbackLength = when {
            windowFallbackStart != null -> fallbackLen(windowFallbackStart)
            earlyFallbackStart != null -> fallbackDur
            else -> 0L
        }
        // Ventanas y descartes: sin esto un «sin mezcla» no se podía repartir entre la salida de A y la entrada de B.
        val noMixWhy = "descartes por estribillo A $rejChorusA, B $rejChorusB; ventana A %.1f–%.1f s, B %.1f–%.1f s".format(
            outMinMs / 1000.0, outMaxMs / 1000.0, inMinMs / 1000.0, inMaxMs / 1000.0,
        ) + (if (fallbackEntry == null) "; B sin entrada fuera de estribillo" else "")
        val outroCanKeepSegment = !adhd || outgoingAdhd == null || outgoingAdhd.endMs >= durA
        val outro = outroCanKeepSegment && fallbackStart == null && fallbackEntry != null && durA >= PRE_ROLL_MS * 2 &&
            durB - fallbackEntry >= POST_ROLL_MS + OUTRO_TAIL_MS
        // If whole-track analysis found dead air at the end, start B there while keeping every sample of A.
        val outroBStart = if (outro && fallbackEntry != null && outgoingAudio?.coversWholeSong == true) {
            val lastChorusEnd = sectionsA.filter { it.isChorus }.maxOfOrNull { it.endMs + SongSections.VOCAL_TAIL_MS } ?: 0L
            val overlapBudget = min(MAX_OUTRO_SILENCE_OVERLAP_MS, durB - fallbackEntry - POST_ROLL_MS)
            maxOf(outLastSound + 300L, lastChorusEnd, durA - overlapBudget).coerceAtMost(durA)
        } else durA
        val winner = best ?: bestRelaxed ?: CandidatePair(
            style = if (outro) DjBridgeStyle.OutroTail else DjBridgeStyle.SmartFade,
            cueOutStartMs = fallbackStart ?: (if (outro) outroBStart - OUTRO_DRY_A_MS else durA),
            cueOutEndMs = fallbackStart?.plus(fallbackLength) ?: durA,
            entryInMs = fallbackEntry ?: inMinMs,
            transitionDurMs = if (outro) OUTRO_DRY_A_MS + max(OUTRO_TAIL_MS, durA - outroBStart) else fallbackLength,
            pickupMs = 0L,
            silenceMs = 0L,
            tempoRatio = 1.0,
            echoDelayMs = 340.0,
            score = 1.0,
            reason = when {
                outro -> "Enlace al final: se conserva el estribillo completo"
                fallbackStart == null -> "Sin mezcla: proteger el estribillo completo ($noMixWhy)"
                fallbackStart == earlyFallbackStart -> "Fundido antes del estribillo (el trozo sale antes; $noMixWhy)"
                fallbackLength < fallbackDur -> "Fundido corto tras el estribillo completo"
                else -> "Fundido tras el estribillo completo"
            },
        )
        val phraseNote = if (!adhd) "" else when {
            best != null -> ", en frase"
            bestRelaxed != null -> ", FUERA de frase (descartes: frase $rejPhrase, voz $rejVocal)"
            else -> ", respaldo"
        }
        // Bombo de la pareja ELEGIDA, en las mismas ventanas que miró la puerta (la de B en audio de B: cruce × ratio).
        val winKickA = DjTempoMatch.kickOnBeat(barsA, winner.cueOutStartMs, winner.cueOutEndMs)
        val winKickB = DjTempoMatch.kickOnBeat(
            barsB, winner.entryInMs, winner.entryInMs + (max(2_000L, winner.transitionDurMs) * winner.tempoRatio).roundToLong(),
        )
        val aligned = winner.style == DjBridgeStyle.BeatMatchLongMix ||
            ((winner.style == DjBridgeStyle.FilterEcho || winner.style == DjBridgeStyle.SmartFade) && winner.beatAligned)
        val tempoNote = buildString {
            // Lo que se aplica de verdad (ratio del plan elegido) y, si no es lo mismo, lo que pedía la pareja: antes decía
            // «estira 2,6 %» con el puente a 1× (18/11).
            val applied = DjTempoMatch.stretchPct(winner.tempoRatio)
            append("estira %.1f %%".format(applied))
            if (abs(applied - stretchPct) >= 0.05) append(" (pareja %.1f %%)".format(stretchPct))
            append(phraseNote)
            if (best == null && bestRelaxed == null) append(" ($noMixWhy)")
            if (aligned) {
                append(" (lím %.0f %%), bombo A %.2f B %.2f".format(winner.stretchLimit ?: limitSeen, winKickA, winKickB))
                append(
                    when (winner.style) {
                        DjBridgeStyle.BeatMatchLongMix -> ", cuadre"
                        DjBridgeStyle.SmartFade -> ", fundido a tempo"
                        else -> ", eco alineado"
                    },
                )
            } else {
                append(", bombo A %.2f B %.2f, sin alinear".format(winKickA, winKickB))
                if (beatGate != null) {
                    append(" (cuadre cerrado: $beatGate)")
                } else {
                    append(" (mejor visto A %.2f B %.2f, lím mín %.0f %%)".format(bestKickA, bestKickB, limitSeen))
                    append("; descartes de cuadre: bombo $rejKick, voz $rejVoice, no cabe $rejFit, timbre $rejTimbre")
                }
            }
        }
        val spec = buildSpec(winner, durA, durB, bpmA, bpmB, harmonic, gainMatch, outgoingAudio, incomingAudio)
        val phaseBounds = if (winner.style == DjBridgeStyle.BeatMatchLongMix || winner.beatAligned) {
            phaseBoundsB(spec.entryInMs)
        } else null
        return spec.copy(tempoNote = tempoNote, kickShiftRangeMs = phaseBounds)
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
        val cueOutStart = win.cueOutStartMs.coerceIn(0L, durA)
        val cueOutEnd = win.cueOutEndMs.coerceIn(cueOutStart, durA)
        val preRoll = min(PRE_ROLL_MS, cueOutStart)
        val preRollStart = cueOutStart - preRoll
        val entryIn = win.entryInMs.coerceIn(0L, max(0L, durB - 4_000L))
        val postRoll = min(POST_ROLL_MS, max(800L, durB - entryIn - 1_500L))

        val dryOffsetInB = when (win.style) {
            // Cruce a tempo de A + vuelta de B a 1× ya sola + margen para el empalme con el tramo seco.
            DjBridgeStyle.BeatMatchLongMix -> (win.transitionDurMs * win.tempoRatio).roundToLong() +
                DjTempoMatch.bridgeTailSourceMs(bpmA, win.tempoRatio)
            DjBridgeStyle.LyricLoopCut -> {
                // La entrada de B ya ha cantado la frase compartida + puente corto de estabilización a tempo.
                // Igual que el renderizador: max(frase, 1,1 s) + cola (≥1,6 s o la vuelta a 1×).
                val tail = max(1_600L, DjTempoMatch.returnGlideSourceMs(bpmA, win.tempoRatio))
                val phraseDur = (win.lyricLoop?.let { max(2_200L, max(it.inPhraseEndMs - it.inEntryMs, 1_100L) + tail) } ?: (1_100L + tail))
                phraseDur
            }
            DjBridgeStyle.EnergyDropCut -> 750L
            DjBridgeStyle.OutroTail -> win.transitionDurMs - OUTRO_DRY_A_MS
            DjBridgeStyle.FilterEcho -> if (win.beatAligned && DjTempoMatch.isStretched(win.tempoRatio)) {
                (win.transitionDurMs * win.tempoRatio).roundToLong() + DjTempoMatch.bridgeTailSourceMs(bpmA, win.tempoRatio)
            } else if (win.beatAligned) {
                win.transitionDurMs + 1_800L + 800L // cola de 1,8 s + corrección de fase máxima
            } else {
                // Lo que gasta el render sin alinear: B entra al 48 % del cruce y suena 1,8 s de cola tras él. Antes se
                // reservaba 0,55× cruce (1,8–5 s): con 6,8 s de cruce faltaban ~1,6 s y se comían el tramo seco de B.
                (win.transitionDurMs * 0.52).roundToLong() + 1_800L
            }
            // A tempo: cruce estirado + vuelta (o cruce + corrección de fase máxima a 1×), igual que el renderizador.
            DjBridgeStyle.SmartFade -> if (win.beatAligned) {
                DjTempoMatch.lockedCrossSourceMs(win.transitionDurMs, bpmA, win.tempoRatio)
            } else win.transitionDurMs
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
            beatAligned = if (win.style == DjBridgeStyle.FilterEcho || win.style == DjBridgeStyle.SmartFade) win.beatAligned else null,
            incomingGainMatch = gainMatch,
            lyricLoop = win.lyricLoop,
            compatibilityScore = compat,
            reason = win.reason,
            outroIncomingOffsetMs = OUTRO_DRY_A_MS.takeIf { win.style == DjBridgeStyle.OutroTail },
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
            // Sin bombo supuesto (-1): lo no analizado no puede habilitar un cuadre de beats.
            val template = recent.last().copy(
                kickOnBeat = -1.0,
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
            if (inside.size <= 10) return inside
            val clean = inside.sortedBy { it.vocalRatio + it.soloRisk * 0.8 }.take(7)
            val calm = inside.sortedBy { it.drumEnergy + (it.lufs * 0.08) + it.vocalRatio * 1.4 }.take(4)
            return (clean + calm).distinct().take(10)
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
            if (inside.size <= 10) return inside
            val punchy = inside.sortedByDescending { it.drumEnergy + (it.lufs * 0.06) - it.vocalRatio }.take(6)
            val calm = inside.sortedBy { it.vocalRatio + it.drumEnergy * 0.65 + (it.lufs * 0.04) }.take(4)
            return (punchy + calm).distinct().take(10)
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
        }.sortedByDescending { it.second }.take(10)

    /** Cuánto cambia la música al empezar [bar] (0..1): un cambio de sección o de frase, donde mezclar suena natural. */
    private fun sectionLift(bars: List<BarFeature>, bar: BarFeature): Double {
        val i = bars.indexOf(bar)
        if (i <= 0) return 0.0
        val prev = bars[i - 1]
        val loud = ((abs(bar.lufs - prev.lufs) - 1.0) / 3.0).coerceIn(0.0, 1.0)
        val drums = ((abs(bar.drumEnergy - prev.drumEnergy) - 0.08) / 0.25).coerceIn(0.0, 1.0)
        return 0.5 * loud + 0.5 * drums
    }

    /** Tolerancia para «cae en un límite de frase»: algo más de medio beat. */
    private fun phraseTolerance(bpm: Double): Long =
        if (bpm > 20.0) (60_000.0 / bpm * 0.6).roundToLong().coerceIn(220L, 450L) else 450L

    /** Un compás que empieza justo en [ms] (las métricas del compás real más cercano). */
    private fun barAt(bars: List<BarFeature>, ms: Long): BarFeature {
        val near = nearestBar(bars, ms)
        return if (near.startMs == ms) near else near.copy(startMs = ms, endMs = ms + near.durationMs)
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

    /** Mismos umbrales que usa el DJ al elegir canciones cuadrables (viven en [DjTempoMatch]). */
    private fun isBeatMatchReady(m: AudioMetrics?): Boolean = DjTempoMatch.isBeatMatchReady(m)

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
