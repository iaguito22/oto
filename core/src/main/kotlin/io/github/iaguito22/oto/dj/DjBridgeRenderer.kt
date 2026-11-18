package io.github.iaguito22.oto.dj

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Puente de audio ya renderizado en PCM estéreo a 48 kHz listo para reproducción directa o guardado como WAV.
 *
 * Estructura exacta en el eje temporal del puente:
 * - `[0 .. preRollMs)`: tramo 100% seco de la canción A (`scale = gainA`) para relevo sin chasquido desde ExoPlayer.
 * - `[preRollMs .. dryBStartOffsetMs)`: transición DSP sample-accurate entre A y B.
 * - `[dryBStartOffsetMs .. totalMs)`: tramo 100% seco de la canción B (`tempo = 1.0x`, `scale = gainB`) correspondiente
 *   exactamente a `[handoffBTrackMs .. spec.dryBResumeMs]` dentro de la canción B.
 */
data class RenderedDjBridge(
    val stereoPcm: FloatArray,
    val sampleRate: Int,
    val totalFrames: Int,
    val preRollFrames: Int,
    val dryBStartOffsetFrames: Int,
    /** Instante dentro de la canción B (ms) en el que empieza el tramo seco final del puente. */
    val handoffBTrackMs: Long,
    /** Instante dentro de la canción B (ms) donde acaba el archivo de puente (`handoffBTrackMs + postRollMs`). */
    val endBTrackMs: Long,
    val spec: DjBridgeSpec,
) {
    val totalMs: Long get() = (totalFrames * 1000.0 / sampleRate).roundToLong()
    val preRollMs: Long get() = (preRollFrames * 1000.0 / sampleRate).roundToLong()
    val dryBStartOffsetMs: Long get() = (dryBStartOffsetFrames * 1000.0 / sampleRate).roundToLong()

    /** Instante del puente (ms) en el que el oído ya cambia de canción: ahí debe cambiar también lo que se ve. */
    val audibleSwitchMs: Long get() = audibleSwitchMs(spec, preRollMs, dryBStartOffsetMs)

    companion object {
        /**
         * Dónde pasa a mandar B dentro del puente: en el corte del drop justo al entrar su tiempo 1 (tras la recogida y
         * el silencio); en el resto, a mitad de la transición (las dos se oyen igual).
         */
        fun audibleSwitchMs(spec: DjBridgeSpec, preRollMs: Long, dryBStartOffsetMs: Long): Long {
            val transitionMs = (dryBStartOffsetMs - preRollMs).coerceAtLeast(0L)
            val inside = when (spec.style) {
                DjBridgeStyle.EnergyDropCut -> spec.pickupMs.coerceAtLeast(250L) + spec.silenceMs.coerceIn(160L, 3_200L)
                DjBridgeStyle.OutroTail -> spec.outroIncomingOffsetMs ?: (spec.cueOutEndMs - spec.cueOutStartMs)
                // Con cuadre de tempo el puente sigue después del cruce mientras B vuelve a 1× (ya sola): el cambio
                // es a mitad del cruce, no a mitad de todo el tramo.
                DjBridgeStyle.BeatMatchLongMix, DjBridgeStyle.FilterEcho ->
                    if (spec.transitionDurationMs > 0L && (spec.style == DjBridgeStyle.BeatMatchLongMix ||
                            DjTempoMatch.isStretched(spec.tempoMatchRatio) || spec.beatAligned == true)
                    ) {
                        min(transitionMs, spec.transitionDurationMs) / 2
                    } else transitionMs / 2
                // Fundido a tempo con B estirada: igual que el cuadre, el tramo sigue con la vuelta de B a 1× ya sola.
                DjBridgeStyle.SmartFade ->
                    if (spec.transitionDurationMs > 0L && spec.beatAligned == true && DjTempoMatch.isStretched(spec.tempoMatchRatio)) {
                        min(transitionMs, spec.transitionDurationMs) / 2
                    } else transitionMs / 2
                else -> transitionMs / 2
            }
            return preRollMs + inside.coerceIn(0L, transitionMs)
        }
    }

    /** Escribe el puente como PCM16 WAV estándar (cabecera de 44 bytes) para lectura instantánea por ExoPlayer. */
    fun writeWavPcm16(targetFile: File) {
        targetFile.parentFile?.mkdirs()
        val dataBytes = totalFrames * 4 // 2 canales * 2 bytes
        val totalSize = 36 + dataBytes
        BufferedOutputStream(FileOutputStream(targetFile), 65_536).use { out ->
            fun writeAscii(s: String) { for (ch in s) out.write(ch.code) }
            fun writeIntLE(v: Int) {
                out.write(v and 0xFF)
                out.write((v ushr 8) and 0xFF)
                out.write((v ushr 16) and 0xFF)
                out.write((v ushr 24) and 0xFF)
            }
            fun writeShortLE(v: Int) {
                out.write(v and 0xFF)
                out.write((v ushr 8) and 0xFF)
            }
            writeAscii("RIFF")
            writeIntLE(totalSize)
            writeAscii("WAVEfmt ")
            writeIntLE(16)
            writeShortLE(1) // PCM
            writeShortLE(2) // estéreo
            writeIntLE(sampleRate)
            writeIntLE(sampleRate * 4)
            writeShortLE(4)
            writeShortLE(16)
            writeAscii("data")
            writeIntLE(dataBytes)

            val chunk = ByteArray(16_384)
            var bi = 0
            for (i in 0 until totalFrames * 2) {
                val sample = (stereoPcm[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                chunk[bi++] = (sample and 0xFF).toByte()
                chunk[bi++] = ((sample ushr 8) and 0xFF).toByte()
                if (bi == chunk.size) {
                    out.write(chunk, 0, bi)
                    bi = 0
                }
            }
            if (bi > 0) out.write(chunk, 0, bi)
        }
    }
}

/**
 * Renderizador estéreo sample-accurate de las transiciones DJ a partir de los tramos decodificados de A y B.
 */
object DjBridgeRenderer {
    fun renderSliceBridge(
        spec: DjBridgeSpec,
        sliceA: FloatArray,
        sliceAOriginMs: Long,
        sliceB: FloatArray,
        sliceBOriginMs: Long,
        gainA: Float = 1.0f,
        gainB: Float = 1.0f,
        sampleRate: Int = DjDsp.DJ_SAMPLE_RATE,
    ): RenderedDjBridge {
        fun msToFrameA(ms: Long): Int = (((ms - sliceAOriginMs).coerceAtLeast(0L) / 1000.0) * sampleRate).roundToInt()
        fun msToFrameB(ms: Long): Int = (((ms - sliceBOriginMs).coerceAtLeast(0L) / 1000.0) * sampleRate).roundToInt()

        // Afinar la fase de los bombos sobre el PCM real en `BeatMatchLongMix`, en `FilterEcho` cuando entra a tempo y en
        // el fundido suave a tempo: cubrimos hasta ±0.48 beats (sin tope de 260 ms para temas < 115 BPM) y sin pedir
        // audio anterior al inicio del slice.
        val filterEchoBeatAligned = spec.style == DjBridgeStyle.FilterEcho && echoBeatAligned(spec)
        val smartFadeLock = spec.style == DjBridgeStyle.SmartFade && smartFadeTempoLocked(spec) &&
            lockFitsInSlice(spec, sliceB.size / 2, sliceBOriginMs, sampleRate)
        val refinedShiftMs = if (spec.style == DjBridgeStyle.BeatMatchLongMix || filterEchoBeatAligned || smartFadeLock) {
            val monoA = DjDsp.stereoToMono(sliceA)
            val monoB = DjDsp.stereoToMono(sliceB)
            val ratio = beatMatchRatio(spec)
            val beatMs = if (spec.bpmA > 20.0) 60_000.0 / spec.bpmA else 500.0
            val durFrames = (((spec.transitionDurationMs / 1000.0) * sampleRate).roundToInt()).coerceIn(sampleRate * 2, sampleRate * 12)
            val outOffsetFrames = if (filterEchoBeatAligned) {
                filterEchoBStartFrames(spec, durFrames, tempoMatched = true, sr = sampleRate)
            } else {
                0
            }
            val halfBeatInMs = (beatMs * 0.52 * ratio).coerceIn(80.0, 410.0)
            val availBeforeBMs = (spec.entryInMs - sliceBOriginMs).coerceAtLeast(0L).toDouble()
            val minShiftMs = max(-min(halfBeatInMs, availBeforeBMs), spec.kickShiftRangeMs?.first?.toDouble() ?: -420.0)
            val naturalMaxShiftMs = if (availBeforeBMs >= halfBeatInMs * 0.7) {
                halfBeatInMs
            } else {
                (beatMs * 0.96 * ratio).coerceIn(halfBeatInMs, 800.0)
            }
            val maxShiftMs = min(naturalMaxShiftMs, spec.kickShiftRangeMs?.last?.toDouble() ?: 800.0)
            DjDsp.kickPhaseShiftMs(
                monoOut = monoA,
                monoIn = monoB,
                sampleRate = sampleRate,
                outStartFrame = msToFrameA(spec.cueOutStartMs) + outOffsetFrames,
                inStartFrame = msToFrameB(spec.entryInMs),
                ratio = ratio,
                maxShiftMs = maxShiftMs,
                minShiftMs = minShiftMs,
            ).first
        } else 0.0
        val effectiveSpec = spec.copy(kickShiftMs = refinedShiftMs)

        val preRollFrames = ((spec.preRollMs / 1000.0) * sampleRate).roundToInt().coerceAtLeast(0)
        val preRoll = DjDsp.sliceStereo(sliceA, msToFrameA(spec.preRollStartMs), preRollFrames)
        scaleStereoInPlace(preRoll, gainA)

        // Durante el puente usamos la relación LUFS calculada y, en el post-roll seco de B, convergemos exactamente a `gainB`.
        // Sin subir por encima de la ganancia unidad: una entrante ya masterizada a ×1,45 entraba de lleno en el limitador
        // durante toda la vuelta de tempo (se oía comprimida/distorsionada hasta el relevo al archivo).
        val mixInGain = (gainA * spec.incomingGainMatch).coerceIn(gainB * 0.65f, gainB * 1.45f)
            .coerceAtMost(max(gainB, 1f))

        val transitionStereo: FloatArray
        val handoffBMs: Long
        when (spec.style) {
            DjBridgeStyle.LyricLoopCut -> {
                val res = renderLyricLoop(effectiveSpec, sliceA, sliceAOriginMs, sliceB, sliceBOriginMs, gainA, mixInGain, sampleRate)
                transitionStereo = res.first
                handoffBMs = res.second
            }
            DjBridgeStyle.BeatMatchLongMix -> {
                val res = renderBeatMatch(effectiveSpec, sliceA, sliceAOriginMs, sliceB, sliceBOriginMs, gainA, mixInGain, sampleRate)
                transitionStereo = res.first
                handoffBMs = res.second
            }
            DjBridgeStyle.EnergyDropCut -> {
                val res = renderEnergyDropCut(effectiveSpec, sliceA, sliceAOriginMs, sliceB, sliceBOriginMs, gainA, mixInGain, sampleRate)
                transitionStereo = res.first
                handoffBMs = res.second
            }
            DjBridgeStyle.FilterEcho -> {
                val res = renderFilterEcho(effectiveSpec, sliceA, sliceAOriginMs, sliceB, sliceBOriginMs, gainA, mixInGain, sampleRate)
                transitionStereo = res.first
                handoffBMs = res.second
            }
            DjBridgeStyle.SmartFade -> {
                val res = renderSmartFade(effectiveSpec, sliceA, sliceAOriginMs, sliceB, sliceBOriginMs, gainA, mixInGain, sampleRate, smartFadeLock)
                transitionStereo = res.first
                handoffBMs = res.second
            }
            DjBridgeStyle.OutroTail -> {
                val res = renderOutroTail(effectiveSpec, sliceA, sliceAOriginMs, sliceB, sliceBOriginMs, gainA, mixInGain, sampleRate)
                transitionStereo = res.first
                handoffBMs = res.second
            }
        }

        // Empalmes con continuidad de fase al entrar y salir de la transición.

        val postFrames = ((spec.postRollMs / 1000.0) * sampleRate).roundToInt().coerceAtLeast(sampleRate / 2)
        val postBStartFrame = (((handoffBMs - sliceBOriginMs) / 1000.0) * sampleRate).roundToInt()
        val availableB = (sliceB.size / 2 - postBStartFrame).coerceAtLeast(0)
        val actualPostFrames = min(postFrames, availableB).coerceAtLeast(1)
        val postRoll = DjDsp.sliceStereo(sliceB, postBStartFrame, actualPostFrames)
        // Rampa suave de volumen en los primeros 600 ms del post-roll desde mixInGain hasta gainB exacto.
        val rampFrames = min(actualPostFrames, (0.60 * sampleRate).roundToInt())
        for (f in 0 until actualPostFrames) {
            val t = if (f < rampFrames) smoothStep(f.toFloat() / rampFrames) else 1f
            val g = mixInGain + (gainB - mixInGain) * t
            postRoll[f * 2] *= g
            postRoll[f * 2 + 1] *= g
        }

        val transFrames = transitionStereo.size / 2
        val totalFrames = preRollFrames + transFrames + actualPostFrames
        val merged = FloatArray(totalFrames * 2)
        System.arraycopy(preRoll, 0, merged, 0, preRoll.size)
        System.arraycopy(transitionStereo, 0, merged, preRollFrames * 2, transitionStereo.size)
        System.arraycopy(postRoll, 0, merged, (preRollFrames + transFrames) * 2, postRoll.size)

        // Micro-crossfade de 4 ms en los empalmes preRoll->transición y transición->postRoll para evitar discontinuidades.
        smoothSpliceSeam(merged, preRollFrames, sampleRate)
        smoothSpliceSeam(merged, preRollFrames + transFrames, sampleRate)

        val limited = DjDsp.truePeakLimiterStereo(merged, sampleRate, ceilingDb = -0.8)
        val postMsExact = (actualPostFrames * 1000.0 / sampleRate).roundToLong()

        // Lo que de verdad lleva el WAV (para `oto.mix` y el cambio audible): velocidad de B y fase aplicadas, no las
        // pedidas. Antes «velocidad B» repetía el ratio del plan aunque el estilo lo ignorase.
        val applied = appliedTempoRatio(spec, smartFadeLock)
        val appliedShift = if (spec.style == DjBridgeStyle.BeatMatchLongMix || filterEchoBeatAligned || smartFadeLock) {
            refinedShiftMs.coerceIn(-420.0, 800.0)
        } else 0.0
        val reportedSpec = effectiveSpec.copy(
            tempoMatchRatio = applied,
            kickShiftMs = appliedShift,
            beatAligned = if (spec.style == DjBridgeStyle.SmartFade && spec.beatAligned != null) smartFadeLock else effectiveSpec.beatAligned,
        )

        return RenderedDjBridge(
            stereoPcm = limited,
            sampleRate = sampleRate,
            totalFrames = totalFrames,
            preRollFrames = preRollFrames,
            dryBStartOffsetFrames = preRollFrames + transFrames,
            handoffBTrackMs = handoffBMs,
            endBTrackMs = handoffBMs + postMsExact,
            spec = reportedSpec,
        )
    }

    private fun renderOutroTail(
        spec: DjBridgeSpec,
        sliceA: FloatArray,
        originA: Long,
        sliceB: FloatArray,
        originB: Long,
        gainA: Float,
        gainB: Float,
        sr: Int,
    ): Pair<FloatArray, Long> {
        fun frames(ms: Long) = (ms * sr / 1_000.0).roundToInt()
        val aStart = frames(spec.cueOutStartMs - originA)
        val aFrames = frames(spec.cueOutEndMs - spec.cueOutStartMs)
        val incomingOffsetMs = spec.outroIncomingOffsetMs ?: (spec.cueOutEndMs - spec.cueOutStartMs)
        val incomingOffset = frames(incomingOffsetMs)
        val bMs = spec.transitionDurationMs - incomingOffsetMs
        val bFrames = frames(bMs)
        val bStart = frames(spec.entryInMs - originB)
        // Codecs may end slightly before the declared duration or timestamp the first packet a few ms after zero.
        // Keep that short padding in the bridge clock, and reject windows with missing music.
        val codecPadding = frames(45)
        require(aStart >= 0 && aFrames > 0 && aStart + aFrames <= sliceA.size / 2 + codecPadding)
        require(incomingOffset in 1..aFrames && bStart >= -codecPadding && bFrames > 0 && bStart + bFrames <= sliceB.size / 2)
        val dryA = DjDsp.sliceStereo(sliceA, aStart, aFrames)
        val dryB = DjDsp.sliceStereo(sliceB, bStart, bFrames)
        val mixed = FloatArray(max(aFrames, incomingOffset + bFrames) * 2)

        // A stays at its playback gain up to its natural end. A quiet filtered tap gives it a tail over the intact B.
        val edge = frames(12).coerceAtLeast(1)
        val delay = min(frames(290), incomingOffset).coerceAtLeast(1)
        val echoFrames = min(bFrames, frames(1_250L))
        for (channel in 0..1) {
            var low = 0f
            for (i in 0 until aFrames) {
                val exit = ((aFrames - i - 1).toFloat() / edge).coerceAtMost(1f)
                mixed[i * 2 + channel] = dryA[i * 2 + channel] * gainA * smoothStep(exit)
            }
            for (i in 0 until echoFrames) {
                val tapFrame = i % delay
                low += (dryA[(incomingOffset - delay + tapFrame) * 2 + channel] - low) * 0.06f
                val tapFalloff = 0.20f / (1 shl (i / delay).coerceAtMost(8))
                val tapEdge = min(tapFrame, delay - 1 - tapFrame)
                val echo = low * gainA * tapFalloff * smoothStep((tapEdge.toFloat() / edge).coerceAtMost(1f)) *
                    (1f - smoothStep(i.toFloat() / echoFrames))
                mixed[(incomingOffset + i) * 2 + channel] += echo
            }
            for (i in 0 until bFrames) {
                val sinceFirstSample = i + min(bStart, 0)
                val enter = smoothStep((sinceFirstSample.toFloat() / edge).coerceIn(0f, 1f))
                mixed[(incomingOffset + i) * 2 + channel] += dryB[i * 2 + channel] * gainB * enter
            }
        }
        return mixed to (spec.entryInMs + bMs)
    }

    /** 1. `lyric_loop`: repetición rítmica 1.0x fiel al pulso (sin chipmunk) + filtro progresivo + cola ping-pong sobre B. */
    private fun renderLyricLoop(
        spec: DjBridgeSpec,
        sliceA: FloatArray,
        originA: Long,
        sliceB: FloatArray,
        originB: Long,
        gainA: Float,
        gainB: Float,
        sr: Int,
    ): Pair<FloatArray, Long> {
        val lp = spec.lyricLoop ?: return renderFilterEcho(spec, sliceA, originA, sliceB, originB, gainA, gainB, sr)
        val unitStart = (((lp.outLoopStartMs - originA) / 1000.0) * sr).roundToInt().coerceAtLeast(0)
        val beatMs = if (spec.bpmA > 20.0) 60_000.0 / spec.bpmA else 545.0
        val beatFrames = ((beatMs / 1000.0) * sr).roundToInt().coerceIn((0.30 * sr).roundToInt(), sr)
        val unitFrames = (((lp.loopDurationMs / 1000.0) * sr).roundToInt()).coerceIn((0.22 * sr).roundToInt(), sr * 2)

        // Secuencia rítmica exacta a velocidad natural 1.0x (siempre 1 compás 4/4 si hay remate de corcheas).
        val slices = ArrayList<Pair<Int, Int>>()
        val halfFrames: Int
        if (lp.doubleSpeedTail && unitFrames >= (1.45 * beatFrames).roundToInt()) {
            val tailFrames = (unitFrames / 2).coerceAtLeast((0.18 * sr).roundToInt())
            halfFrames = (unitFrames / 4).coerceAtLeast((0.14 * sr).roundToInt())
            slices += unitStart to unitFrames
            slices += (unitStart + unitFrames - tailFrames) to tailFrames
            val halfStart = unitStart + unitFrames - halfFrames
            slices += halfStart to halfFrames
            slices += halfStart to halfFrames
        } else if (lp.doubleSpeedTail) {
            halfFrames = (unitFrames / 2).coerceAtLeast((0.14 * sr).roundToInt())
            val halfStart = unitStart + unitFrames - halfFrames
            repeat(3) { slices += unitStart to unitFrames }
            repeat(2) { slices += halfStart to halfFrames }
        } else {
            halfFrames = (unitFrames / 2).coerceAtLeast((0.14 * sr).roundToInt())
            repeat(lp.repeats.coerceIn(3, 5)) { slices += unitStart to unitFrames }
        }

        val loopFrames = slices.sumOf { it.second }.coerceAtLeast(1)
        val loopBuf = FloatArray(loopFrames * 2)
        val crossFrames = minOf((0.010 * sr).roundToInt(), unitFrames / 6, halfFrames / 5).coerceAtLeast(32)
        var cursor = 0
        slices.forEachIndexed { idx, (srcStart, nominal) ->
            val block = DjDsp.sliceStereo(sliceA, srcStart, nominal)
            scaleStereoInPlace(block, gainA)
            if (idx > 0) {
                val cfPre = min(crossFrames, min(srcStart, cursor))
                if (cfPre > 16) {
                    val preLead = DjDsp.sliceStereo(sliceA, srcStart - cfPre, cfPre)
                    scaleStereoInPlace(preLead, gainA)
                    for (k in 0 until cfPre) {
                        val u = k.toFloat() / cfPre
                        val gOut = cos(u * PI * 0.5).toFloat()
                        val gIn = sin(u * PI * 0.5).toFloat()
                        val dst = cursor - cfPre + k
                        loopBuf[dst * 2] = loopBuf[dst * 2] * gOut + preLead[k * 2] * gIn
                        loopBuf[dst * 2 + 1] = loopBuf[dst * 2 + 1] * gOut + preLead[k * 2 + 1] * gIn
                    }
                } else {
                    val edge = min((0.004 * sr).roundToInt(), min(nominal / 4, cursor / 4))
                    for (k in 0 until edge) {
                        val u = k.toFloat() / max(1, edge)
                        val gOut = 1f - u
                        loopBuf[(cursor - edge + k) * 2] *= gOut
                        loopBuf[(cursor - edge + k) * 2 + 1] *= gOut
                        block[k * 2] *= u
                        block[k * 2 + 1] *= u
                    }
                }
            }
            System.arraycopy(block, 0, loopBuf, cursor * 2, nominal * 2)
            cursor += nominal
        }

        // Filtro progresivo 12 dB/oct (HPF 115->460 Hz y LPF suave 13.8k->8.4k Hz) con precalentamiento previo al loop.
        val warmFrames = min((0.18 * sr).roundToInt(), unitStart)
        val warmBuf = FloatArray((warmFrames + loopFrames) * 2)
        if (warmFrames > 0) {
            val preA = DjDsp.sliceStereo(sliceA, unitStart - warmFrames, warmFrames)
            scaleStereoInPlace(preA, gainA)
            System.arraycopy(preA, 0, warmBuf, 0, warmFrames * 2)
        }
        System.arraycopy(loopBuf, 0, warmBuf, warmFrames * 2, loopFrames * 2)
        val totalSweep = warmFrames + loopFrames
        val hpfHz = FloatArray(totalSweep) { i ->
            if (i < warmFrames) 115f else {
                val p = (i - warmFrames).toFloat() / max(1, loopFrames - 1)
                (115.0 * (460.0 / 115.0).pow(p.toDouble().pow(1.2))).toFloat()
            }
        }
        val lpfHz = FloatArray(totalSweep) { i ->
            if (i < warmFrames) 13_800f else {
                val p = (i - warmFrames).toFloat() / max(1, loopFrames - 1)
                (13_800.0 * (8_400.0 / 13_800.0).pow(p.toDouble())).toFloat()
            }
        }
        val hpWarm = DjDsp.svfSweepStereo(warmBuf, sr, hpfHz, highpass = true, resonance = 0.78f, steep24Db = false)
        val lpWarm = DjDsp.svfSweepStereo(hpWarm, sr, lpfHz, highpass = false, resonance = 0.72f, steep24Db = false)
        val loopFiltered = DjDsp.sliceStereo(lpWarm, warmFrames, loopFrames)

        // Entrada de B con la frase compartida sincronizada a tempo (`tempoMatchRatio`) y relevo suave al tramo seco final.
        val ratio = beatMatchRatio(spec)
        val inStartFrame = (((lp.inEntryMs - originB) / 1000.0) * sr).roundToInt().coerceAtLeast(0)
        val phraseLeadFrames = max(((lp.inPhraseEndMs - lp.inEntryMs) / 1000.0 * sr).roundToInt(), (1.1 * sr).roundToInt())
        // Tras la frase compartida B ya suena sola: vuelve a 1× en beats (ver `DjTempoMatch.returnGlideMs`).
        val tailLeadFrames = max(
            (1.6 * sr).roundToInt(),
            ((DjTempoMatch.returnGlideSourceMs(spec.bpmA, ratio) / 1000.0) * sr).roundToInt(),
        )
        val rawNeededB = phraseLeadFrames + tailLeadFrames
        val rawB = DjDsp.sliceStereo(sliceB, inStartFrame, rawNeededB)
        scaleStereoInPlace(rawB, gainB)
        val stretchedB = if (DjTempoMatch.isStretched(ratio)) {
            val speedB = FloatArray(rawB.size / 2) { i ->
                if (i < phraseLeadFrames) {
                    ratio.toFloat()
                } else {
                    val u = ((i - phraseLeadFrames).toFloat() / max(1, tailLeadFrames - 1)).coerceIn(0f, 1f)
                    (ratio + (1.0 - ratio) * smoothStep(u)).toFloat()
                }
            }
            DjDsp.timeStretchStereo(rawB, sr, speedB)
        } else {
            rawB
        }
        val bFrames = stretchedB.size / 2

        val seamOverlap = minOf((0.012 * sr).roundToInt(), loopFrames / 10, bFrames / 10).coerceAtLeast(32)
        val bStartInOut = loopFrames - seamOverlap
        val total = max(loopFrames, bStartInOut + bFrames)
        val paddedLoop = FloatArray(total * 2)
        System.arraycopy(loopFiltered, 0, paddedLoop, 0, loopFrames * 2)

        // Semilla del último medio beat alimentando ping-pong a corchea (0.5 * beat) + reverb sutil sobre el ataque de B.
        val seedFrames = min(loopFrames, ((beatMs * 0.5).coerceIn(200.0, 500.0) / 1000.0 * sr).roundToInt())
        val seedStart = loopFrames - seedFrames
        val tailDecayFrames = max(1, min(total - loopFrames, (1.15 * sr).roundToInt()))
        val sendEcho = FloatArray(total) { i ->
            when {
                i < seedStart -> 0f
                i < loopFrames -> smoothStep((i - seedStart).toFloat() / max(1, (0.020 * sr).roundToInt()).coerceAtMost(seedFrames))
                else -> 0f
            }
        }
        val echoFb = FloatArray(total) { i ->
            if (i < loopFrames) 0.42f else {
                val u = ((i - loopFrames).toFloat() / tailDecayFrames).coerceIn(0f, 1f)
                0.42f * (1f - 0.72f * u)
            }
        }
        val echoWet = FloatArray(total) { i ->
            when {
                i < seedStart -> 0f
                i < loopFrames -> 0.34f * smoothStep((i - seedStart).toFloat() / max(1, seedFrames))
                else -> 0.34f * (1f - smoothStep(((i - loopFrames).toFloat() / tailDecayFrames).coerceIn(0f, 1f)))
            }
        }
        val echoDelay = (beatMs * 0.5).coerceIn(180.0, 480.0)
        val echo = DjDsp.pingPongDelayStereo(paddedLoop, sr, echoDelay, echoFb, sendEcho, echoWet)

        val revSendStart = (loopFrames - min(loopFrames, beatFrames)).coerceAtLeast(0)
        val revDecayFrames = max(1, min(total - loopFrames, (0.95 * sr).roundToInt()))
        val sendRev = FloatArray(total) { i ->
            if (i in revSendStart until loopFrames) {
                smoothStep((i - revSendStart).toFloat() / max(1, loopFrames - revSendStart)) * 0.36f
            } else 0f
        }
        val wetRev = FloatArray(total) { i ->
            when {
                i < revSendStart -> 0f
                i < loopFrames -> 0.14f * smoothStep((i - revSendStart).toFloat() / max(1, loopFrames - revSendStart))
                else -> 0.14f * (1f - smoothStep(((i - loopFrames).toFloat() / revDecayFrames).coerceIn(0f, 1f)))
            }
        }
        val rev = DjDsp.stereoReverb(paddedLoop, sr, sendRev, wetRev, roomSize = 0.42f)

        val out = FloatArray(total * 2)
        for (i in 0 until loopFrames) {
            val tailFade = if (i >= bStartInOut) {
                val u = (i - bStartInOut).toFloat() / seamOverlap
                cos(u * PI * 0.5).toFloat()
            } else 1f
            out[i * 2] = loopFiltered[i * 2] * tailFade
            out[i * 2 + 1] = loopFiltered[i * 2 + 1] * tailFade
        }
        for (i in 0 until total) {
            out[i * 2] += echo[i * 2] + rev[i * 2]
            out[i * 2 + 1] += echo[i * 2 + 1] + rev[i * 2 + 1]
        }
        for (i in 0 until bFrames) {
            val gIn = if (i < seamOverlap) sin((i.toFloat() / seamOverlap) * PI * 0.5).toFloat() else 1f
            val dst = bStartInOut + i
            if (dst < total) {
                out[dst * 2] += stretchedB[i * 2] * gIn
                out[dst * 2 + 1] += stretchedB[i * 2 + 1] * gIn
            }
        }
        val handoffBMs = if (DjTempoMatch.isStretched(ratio)) {
            blendContinuityHandoffB(
                out = out,
                total = total,
                bFrames = bFrames,
                sliceB = sliceB,
                approxEndFrameB = inStartFrame + rawB.size / 2,
                originB = originB,
                gainB = gainB,
                minHandoffMs = lp.inEntryMs,
                sr = sr,
            )
        } else {
            val bConsumedMs = ((bFrames * 1000.0) / sr).roundToLong()
            lp.inEntryMs + bConsumedMs
        }
        return out to handoffBMs
    }

    /** 2. `beat_mix`: A intacta a 1.0x + B a tempo constante durante el cruce + swap constante de graves al beat 8 + planeo solo de B. */
    private fun renderBeatMatch(
        spec: DjBridgeSpec,
        sliceA: FloatArray,
        originA: Long,
        sliceB: FloatArray,
        originB: Long,
        gainA: Float,
        gainB: Float,
        sr: Int,
    ): Pair<FloatArray, Long> {
        val ratio = beatMatchRatio(spec)
        val startAFrame = (((spec.cueOutStartMs - originA) / 1000.0) * sr).roundToInt().coerceAtLeast(0)
        val aFrames = (((spec.cueOutEndMs - spec.cueOutStartMs) / 1000.0) * sr).roundToInt().coerceAtLeast(sr * 2)

        // Dejar a A 100% sin re-muestrear (1.0x) y precalentar el filtro FIR para conservar ataque y timbre intactos.
        val (lowA, midA, highA) = threeBandWithWarmupStereo(
            slice = sliceA,
            startFrame = startAFrame,
            lengthFrames = aFrames,
            sr = sr,
            lowHz = 160.0,
            highHz = 2_600.0,
            scale = gainA,
        )

        val shiftBMs = spec.kickShiftMs.coerceIn(-420.0, 800.0)
        val entryBFrame = (((spec.entryInMs + shiftBMs - originB).coerceAtLeast(0.0) / 1000.0) * sr).roundToInt()
        val overlapRawB = (aFrames * ratio).roundToInt().coerceAtLeast(sr)
        // Vuelta de B a su velocidad cuando A ya no suena: 8–16 beats según cuánto se estiró (antes 1,6 s fijos, que
        // sonaban a acelerón o frenazo).
        val glideOutFrames = ((DjTempoMatch.renderedGlideMs(spec.bpmA, ratio) / 1000.0) * sr).roundToInt()
        val glideRawB = (glideOutFrames * (ratio + 1.0) * 0.5).roundToInt().coerceAtLeast(sr / 2)
        val dryTailRawB = (0.20 * sr).roundToInt()
        val rawB = DjDsp.sliceStereo(sliceB, entryBFrame, overlapRawB + glideRawB + dryTailRawB)
        scaleStereoInPlace(rawB, gainB)
        val rawBFrames = rawB.size / 2

        val stretchedB = if (DjTempoMatch.isStretched(ratio)) {
            val speedB = FloatArray(rawBFrames) { i ->
                when {
                    i < overlapRawB -> ratio.toFloat()
                    i < overlapRawB + glideRawB -> {
                        val u = smoothStep(((i - overlapRawB).toFloat() / max(1, glideRawB - 1)).coerceIn(0f, 1f))
                        (ratio + (1.0 - ratio) * u).toFloat()
                    }
                    else -> 1.0f
                }
            }
            DjDsp.timeStretchStereo(rawB, sr, speedB)
        } else {
            rawB
        }
        val bFrames = stretchedB.size / 2

        val approxEndB = entryBFrame + rawBFrames
        val (lowB, midB, highB) = threeBandOfStretchedB(
            sliceB = sliceB,
            entryBFrame = entryBFrame,
            rawBFrames = rawBFrames,
            stretchedB = stretchedB,
            gainB = gainB,
            sr = sr,
            lowHz = 160.0,
            highHz = 2_600.0,
        )

        val total = max(aFrames, bFrames)
        val out = FloatArray(total * 2)
        val swapAt = spec.bassSwapFrac.coerceIn(0.35f, 0.72f)
        val swapWidth = if (spec.bpmA > 20.0 && aFrames > 0) {
            ((0.50 * (60.0 / spec.bpmA) * sr) / aFrames).toFloat().coerceIn(0.024f, 0.090f)
        } else 0.048f

        for (i in 0 until total) {
            if (i < aFrames) {
                val p = i.toFloat() / max(1, aFrames - 1)
                val swapU = ((p - (swapAt - swapWidth * 0.5f)) / swapWidth).coerceIn(0f, 1f)
                val thetaBass = smoothStep(swapU) * (PI.toFloat() * 0.5f)

                val outLowG = cos(thetaBass) * (1f - smoothStep(((p - 0.85f) / 0.10f).coerceIn(0f, 1f)))
                val outMidG = cos(smoothStep(((p - 0.16f) / 0.68f).coerceIn(0f, 1f)) * PI * 0.5).toFloat()
                val outHighG = cos(smoothStep(((p - 0.10f) / 0.82f).coerceIn(0f, 1f)) * PI * 0.5).toFloat()

                val inHighG = sin(smoothStep((p / 0.44f).coerceIn(0f, 1f)) * PI * 0.5).toFloat()
                val inMidG = sin(smoothStep(((p - 0.06f) / 0.62f).coerceIn(0f, 1f)) * PI * 0.5).toFloat()
                val inLowG = sin(thetaBass)

                var l = lowA[i * 2] * outLowG + midA[i * 2] * outMidG + highA[i * 2] * outHighG
                var r = lowA[i * 2 + 1] * outLowG + midA[i * 2 + 1] * outMidG + highA[i * 2 + 1] * outHighG
                if (i < bFrames) {
                    l += lowB[i * 2] * inLowG + midB[i * 2] * inMidG + highB[i * 2] * inHighG
                    r += lowB[i * 2 + 1] * inLowG + midB[i * 2 + 1] * inMidG + highB[i * 2 + 1] * inHighG
                }
                out[i * 2] = l
                out[i * 2 + 1] = r
            } else if (i < bFrames) {
                out[i * 2] = lowB[i * 2] + midB[i * 2] + highB[i * 2]
                out[i * 2 + 1] = lowB[i * 2 + 1] + midB[i * 2 + 1] + highB[i * 2 + 1]
            }
        }

        // Tiempo real consumido en los samples originales de B, afinado por correlación para empatar al sample con postRoll.
        val handoffMsExact = blendContinuityHandoffB(
            out = out,
            total = total,
            bFrames = bFrames,
            sliceB = sliceB,
            approxEndFrameB = approxEndB,
            originB = originB,
            gainB = gainB,
            minHandoffMs = spec.entryInMs,
            sr = sr,
        )
        return out to handoffMsExact
    }

    /** Alinea el tramo final de B estirado con [sliceB] seco mediante correlación y aplica un micro-crossfade de 12 ms. */
    private fun blendContinuityHandoffB(
        out: FloatArray,
        total: Int,
        bFrames: Int,
        sliceB: FloatArray,
        approxEndFrameB: Int,
        originB: Long,
        gainB: Float,
        minHandoffMs: Long,
        sr: Int,
    ): Long {
        // Ventana de 250 ms (antes 40 ms): en un tramo casi callado entre dos golpes, 40 ms se parecen a cualquier
        // otro silencio y el ancla caía en el borde de la búsqueda (medido: costura a +90 ms, un trozo de B repetido).
        val tailFrames = min((0.250 * sr).roundToInt(), bFrames / 4).coerceAtLeast(128)
        val matchedFrameB = findBestContinuityEndFrame(
            stretched = out,
            stretchedEndFrame = total,
            sliceB = sliceB,
            approxEndFrameB = approxEndFrameB,
            sr = sr,
            winFrames = tailFrames,
        )
        // El relevo público usa milisegundos enteros. Empalmar contra ese mismo sample evita que el redondeo
        // posterior (hasta medio ms) cambie la fase justo al empezar el tramo seco, especialmente en tonos sostenidos.
        val handoffMs = (originB + ((matchedFrameB * 1000.0) / sr).roundToLong()).coerceAtLeast(minHandoffMs)
        val anchorFrameB = (((handoffMs - originB) * sr) / 1000.0).roundToInt()
            .coerceIn(0, sliceB.size / 2)
        val xf = min((0.012 * sr).roundToInt(), min(total / 2, anchorFrameB))
        if (xf > 16) {
            for (k in 0 until xf) {
                val srcFrame = anchorFrameB - xf + k
                if (srcFrame in 0 until (sliceB.size / 2)) {
                    val dstFrame = total - xf + k
                    val u = k.toFloat() / xf
                    val gStretched = cos(u * PI * 0.5).toFloat()
                    val gDry = sin(u * PI * 0.5).toFloat()
                    out[dstFrame * 2] = out[dstFrame * 2] * gStretched + sliceB[srcFrame * 2] * gainB * gDry
                    out[dstFrame * 2 + 1] = out[dstFrame * 2 + 1] * gStretched + sliceB[srcFrame * 2 + 1] * gainB * gDry
                }
            }
        }
        return handoffMs
    }

    private fun findBestContinuityEndFrame(
        stretched: FloatArray,
        stretchedEndFrame: Int,
        sliceB: FloatArray,
        approxEndFrameB: Int,
        sr: Int,
        winFrames: Int,
    ): Int {
        val totalBFrames = sliceB.size / 2
        if (totalBFrames <= winFrames + 16 || stretchedEndFrame <= winFrames) {
            return approxEndFrameB.coerceIn(0, totalBFrames)
        }
        val searchRadius = (0.090 * sr).roundToInt()
        val sStart = stretchedEndFrame - winFrames
        var bestEnd = approxEndFrameB.coerceIn(winFrames, totalBFrames)
        var bestScore = Double.NEGATIVE_INFINITY
        val loEnd = (approxEndFrameB - searchRadius).coerceIn(winFrames, totalBFrames)
        val hiEnd = (approxEndFrameB + searchRadius).coerceIn(loEnd, totalBFrames)

        fun corrAt(candEnd: Int, step: Int): Double {
            val bStart = candEnd - winFrames
            var num = 0.0
            var e1 = 0.0
            var e2 = 0.0
            var k = 0
            while (k < winFrames) {
                val a = stretched[(sStart + k) * 2].toDouble() + stretched[(sStart + k) * 2 + 1]
                val b = sliceB[(bStart + k) * 2].toDouble() + sliceB[(bStart + k) * 2 + 1]
                num += a * b
                e1 += a * a
                e2 += b * b
                k += step
            }
            return num / (kotlin.math.sqrt(e1 * e2) + 1e-8)
        }

        // La posición calculada (`approxEndFrameB`) ya es buena: alejarse de ella cuesta un poco, y si nada se parece de
        // verdad (correlación < 0,3) se queda ella.
        fun distCost(c: Int): Double = 0.15 * abs(c - approxEndFrameB).toDouble() / searchRadius
        var cand = loEnd
        var coarseEnd = bestEnd
        while (cand <= hiEnd) {
            val sc = corrAt(cand, 4) - distCost(cand)
            if (sc > bestScore) {
                bestScore = sc
                coarseEnd = cand
            }
            cand += 4
        }
        bestScore = Double.NEGATIVE_INFINITY
        var bestCorr = 0.0
        for (fine in (coarseEnd - 6).coerceAtLeast(loEnd)..(coarseEnd + 6).coerceAtMost(hiEnd)) {
            val corr = corrAt(fine, 1)
            val sc = corr - distCost(fine)
            if (sc > bestScore) {
                bestScore = sc
                bestCorr = corr
                bestEnd = fine
            }
        }
        return if (bestCorr >= 0.3) bestEnd else approxEndFrameB.coerceIn(winFrames, totalBFrames)
    }

    /** 3. `energy_drop_cut`: A entera y brillante hasta el corte + eco ping-pong/reverb con respiro a -36 dB + riser LPF en B al drop. */
    private fun renderEnergyDropCut(
        spec: DjBridgeSpec,
        sliceA: FloatArray,
        originA: Long,
        sliceB: FloatArray,
        originB: Long,
        gainA: Float,
        gainB: Float,
        sr: Int,
    ): Pair<FloatArray, Long> {
        val pickupFrames = ((spec.pickupMs.coerceAtLeast(250L) / 1000.0) * sr).roundToInt()
        val silenceFrames = ((spec.silenceMs.coerceIn(160L, 3_200L) / 1000.0) * sr).roundToInt()
        val dropLeadFrames = (0.75 * sr).roundToInt()

        val cutFrame = pickupFrames
        val dropFrame = pickupFrames + silenceFrames
        val totalFrames = dropFrame + dropLeadFrames

        val startAFrame = (((spec.cueOutStartMs - originA) / 1000.0) * sr).roundToInt().coerceAtLeast(0)
        val rawA = DjDsp.sliceStereo(sliceA, startAFrame, pickupFrames)
        scaleStereoInPlace(rawA, gainA)

        val out = FloatArray(totalFrames * 2)
        // Mantener a A sin apagar antes del corte: solo micro-fundido de 6 ms en el borde para evitar clic.
        val exitFade = min(pickupFrames / 4, (0.006 * sr).roundToInt()).coerceAtLeast(32)
        for (i in 0 until pickupFrames) {
            val env = if (i >= pickupFrames - exitFade) {
                val u = (i - (pickupFrames - exitFade)).toFloat() / exitFade
                cos(u * PI * 0.5).toFloat()
            } else 1f
            out[i * 2] = rawA[i * 2] * env
            out[i * 2 + 1] = rawA[i * 2 + 1] * env
        }

        // Semilla del último beat de A alimentando ping-pong delay + reverb de sala y respiro dramático (-36 dB) antes de B.
        val beatMs = if (spec.bpmA > 20.0) 60_000.0 / spec.bpmA else 500.0
        val seedFrames = min(pickupFrames, ((beatMs.coerceIn(300.0, 950.0) / 1000.0) * sr).roundToInt())
        val seedStart = cutFrame - seedFrames
        val tailBleed = min(dropLeadFrames, (0.015 * sr).roundToInt())
        val fxLen = dropFrame + tailBleed
        val fxIn = FloatArray(fxLen * 2)
        val seedAttack = min(seedFrames / 3, (0.012 * sr).roundToInt()).coerceAtLeast(1)
        for (i in seedStart until cutFrame) {
            val k = i - seedStart
            val w = if (k < seedAttack) smoothStep(k.toFloat() / seedAttack) else 1f
            val tail = if (i >= cutFrame - exitFade) {
                val u = (i - (cutFrame - exitFade)).toFloat() / exitFade
                cos(u * PI * 0.5).toFloat()
            } else 1f
            fxIn[i * 2] = rawA[i * 2] * w * tail
            fxIn[i * 2 + 1] = rawA[i * 2 + 1] * w * tail
        }

        val echoDelayMs = spec.echoDelayMs.coerceIn(220.0, 960.0)
        val sendEnv = FloatArray(fxLen) { i ->
            when {
                i < seedStart -> 0f
                i < seedStart + seedAttack -> smoothStep((i - seedStart).toFloat() / seedAttack)
                i < cutFrame -> 1f
                else -> 0f
            }
        }
        val fbEnv = FloatArray(fxLen) { 0.45f }
        val wetOne = FloatArray(fxLen) { 1.0f }
        val echoWet = DjDsp.pingPongDelayStereo(fxIn, sr, echoDelayMs, fbEnv, sendEnv, wetOne)
        val revWet = DjDsp.stereoReverb(fxIn, sr, sendEnv, wetOne, roomSize = spec.reverbRoom.coerceIn(0.40f, 0.56f))

        val postRampIn = min(silenceFrames / 6, (0.015 * sr).roundToInt()).coerceAtLeast(1)
        val breathFrames = min((silenceFrames * 0.45).roundToInt(), ((beatMs * 0.90) / 1000.0 * sr).roundToInt())
            .coerceAtLeast(min(silenceFrames / 2, (0.16 * sr).roundToInt()))
        val duckStart = (silenceFrames - breathFrames).coerceAtLeast(min(silenceFrames / 3, silenceFrames - postRampIn))
        for (k in 0 until (silenceFrames + tailBleed)) {
            val idx = cutFrame + k
            if (idx >= totalFrames) break
            val duckGain = when {
                k < silenceFrames && k < duckStart -> {
                    if (k < postRampIn) smoothStep(k.toFloat() / postRampIn) else 1f
                }
                k < silenceFrames -> {
                    val u = ((k - duckStart).toFloat() / max(1, silenceFrames - duckStart)).coerceIn(0f, 1f)
                    1f - 0.985f * smoothStep(u)
                }
                else -> {
                    val u = ((k - silenceFrames).toFloat() / max(1, tailBleed)).coerceIn(0f, 1f)
                    0.015f * (1f - u)
                }
            }
            val fxL = (0.72f * echoWet[idx * 2] + 0.22f * revWet[idx * 2]) * duckGain
            val fxR = (0.72f * echoWet[idx * 2 + 1] + 0.22f * revWet[idx * 2 + 1]) * duckGain
            val mid = 0.5f * (fxL + fxR)
            val side = 0.5f * (fxL - fxR)
            out[idx * 2] += mid + 0.78f * side
            out[idx * 2 + 1] += mid - 0.78f * side
        }

        // Subida LPF resonante de B (380 -> 16500 Hz) durante el respiro + transición de 18 ms al golpe seco.
        val entryBFrame = (((spec.entryInMs - originB) / 1000.0) * sr).roundToInt().coerceAtLeast(0)
        val maxRiserFrames = min(silenceFrames, ((beatMs * 1.15).coerceIn(380.0, 900.0) / 1000.0 * sr).roundToInt())
        val riserFrames = min(maxRiserFrames, entryBFrame)
        val hasContinuousRiser = riserFrames >= (0.08 * sr).roundToInt()

        val rawB = DjDsp.sliceStereo(sliceB, entryBFrame, dropLeadFrames)
        scaleStereoInPlace(rawB, gainB)
        System.arraycopy(rawB, 0, out, dropFrame * 2, rawB.size)

        if (hasContinuousRiser) {
            val seamFrames = min(dropLeadFrames / 4, (0.018 * sr).roundToInt()).coerceAtLeast(32)
            val warmRiser = min((0.08 * sr).roundToInt(), entryBFrame - riserFrames)
            val totalRiserBuf = warmRiser + riserFrames + seamFrames
            val rawRiserFull = DjDsp.sliceStereo(sliceB, entryBFrame - riserFrames - warmRiser, totalRiserBuf)
            scaleStereoInPlace(rawRiserFull, gainB)
            val riseHz = FloatArray(totalRiserBuf) { i ->
                when {
                    i < warmRiser -> 380f
                    i < warmRiser + riserFrames -> {
                        val p = (i - warmRiser).toFloat() / max(1, riserFrames - 1)
                        (380.0 * (16_500.0 / 380.0).pow(p.toDouble().pow(1.15))).toFloat()
                    }
                    else -> 16_500f
                }
            }
            val sweptRiser = DjDsp.svfSweepStereo(rawRiserFull, sr, riseHz, highpass = false, resonance = 0.95f, steep24Db = false)
            val dstRiserStart = dropFrame - riserFrames
            val attackUp = min(riserFrames / 5, (0.035 * sr).roundToInt()).coerceAtLeast(1)
            for (i in 0 until riserFrames) {
                val p = i.toFloat() / max(1, riserFrames - 1)
                val startEnv = if (i < attackUp) smoothStep(i.toFloat() / attackUp) else 1f
                val g = startEnv * (0.14f + 0.86f * smoothStep(p).toDouble().pow(0.85).toFloat())
                val dst = dstRiserStart + i
                val sIdx = warmRiser + i
                out[dst * 2] += sweptRiser[sIdx * 2] * g
                out[dst * 2 + 1] += sweptRiser[sIdx * 2 + 1] * g
            }
            for (k in 0 until seamFrames) {
                val u = k.toFloat() / max(1, seamFrames)
                val gSwept = cos(u * PI * 0.5).toFloat()
                val gDry = sin(u * PI * 0.5).toFloat()
                val dst = dropFrame + k
                val sIdx = warmRiser + riserFrames + k
                out[dst * 2] = sweptRiser[sIdx * 2] * gSwept + rawB[k * 2] * gDry
                out[dst * 2 + 1] = sweptRiser[sIdx * 2 + 1] * gSwept + rawB[k * 2 + 1] * gDry
            }
        } else {
            val atk = min((0.003 * sr).roundToInt(), dropLeadFrames)
            for (i in 0 until atk) {
                val g = i.toFloat() / atk
                out[(dropFrame + i) * 2] *= g
                out[(dropFrame + i) * 2 + 1] *= g
            }
        }

        val bMs = ((dropLeadFrames * 1000.0) / sr).roundToLong()
        return out to (spec.entryInMs + bMs)
    }

    /** 4. `filter_echo`: barrido SVF HPF 12 dB/oct en A + apertura LPF en B + estela de eco cayendo sobre B (estilo oto-dj-lab). */
    private fun renderFilterEcho(
        spec: DjBridgeSpec,
        sliceA: FloatArray,
        originA: Long,
        sliceB: FloatArray,
        originB: Long,
        gainA: Float,
        gainB: Float,
        sr: Int,
    ): Pair<FloatArray, Long> {
        val durFrames = (((spec.transitionDurationMs / 1000.0) * sr).roundToInt()).coerceIn(sr * 2, sr * 12)
        val startAFrame = (((spec.cueOutStartMs - originA) / 1000.0) * sr).roundToInt()
        val warmFrames = min((0.35 * sr).roundToInt(), max(0, startAFrame))
        val rawAWithWarm = DjDsp.sliceStereo(sliceA, startAFrame - warmFrames, warmFrames + durFrames)
        scaleStereoInPlace(rawAWithWarm, gainA)

        // Barrido HPF suave de 12 dB/oct (`52 -> 1750 Hz`) sin resonancia nasal tras calentar el filtro SVF.
        val hpfEnd = spec.cutoffEndHz.coerceIn(900f, 1_750f)
        val totalSweepFrames = warmFrames + durFrames
        val hpfHz = FloatArray(totalSweepFrames) { i ->
            if (i < warmFrames) {
                val w = i.toFloat() / max(1, warmFrames)
                (25f * (52f / 25f).pow(w)).coerceIn(25f, 60f)
            } else {
                val p = (i - warmFrames).toFloat() / max(1, durFrames - 1)
                (52f * (hpfEnd / 52f).pow(p)).coerceIn(45f, 1_800f)
            }
        }
        val filteredFull = DjDsp.svfSweepStereo(rawAWithWarm, sr, hpfHz, highpass = true, resonance = 0.76f, steep24Db = false)
        val filteredA = DjDsp.sliceStereo(filteredFull, warmFrames, durFrames)

        val tempoMatched = echoBeatAligned(spec)

        val bStartIdx = filterEchoBStartFrames(spec, durFrames, tempoMatched, sr)
        val tailOutFrames = (1.8 * sr).roundToInt()
        val targetOutBFrames = (durFrames - bStartIdx) + tailOutFrames
        // Con B estirada: tempo constante mientras suena A (si no, se desfasan) y vuelta a 1× ya sola.
        val glideOutFrames = ((DjTempoMatch.renderedGlideMs(spec.bpmA, spec.tempoMatchRatio) / 1000.0) * sr).roundToInt()
        val shiftBMs = if (tempoMatched) spec.kickShiftMs.coerceIn(-420.0, 800.0) else 0.0
        val entryBFrame = (((spec.entryInMs + shiftBMs - originB).coerceAtLeast(0.0) / 1000.0) * sr).roundToInt()
        val useRatio = tempoMatched && DjTempoMatch.isStretched(spec.tempoMatchRatio)
        val rawB: FloatArray
        val bFrames: Int
        val approxEndFrameB: Int
        if (useRatio) {
            val ratio = DjTempoMatch.clampRatio(spec.tempoMatchRatio)
            val overlapOutFrames = (durFrames - bStartIdx).coerceAtLeast(1)
            val overlapRawB = (overlapOutFrames * ratio).roundToInt().coerceAtLeast(sr / 2)
            val glideRawB = (glideOutFrames * (ratio + 1.0) * 0.5).roundToInt()
            val dryTailRawB = (0.20 * sr).roundToInt()
            val sliceRawB = DjDsp.sliceStereo(sliceB, entryBFrame, overlapRawB + glideRawB + dryTailRawB)
            scaleStereoInPlace(sliceRawB, gainB)
            val nRawB = sliceRawB.size / 2
            val speedB = FloatArray(nRawB) { i ->
                when {
                    i < overlapRawB -> ratio.toFloat()
                    i < overlapRawB + glideRawB -> {
                        val u = smoothStep(((i - overlapRawB).toFloat() / max(1, glideRawB - 1)).coerceIn(0f, 1f))
                        (ratio + (1.0 - ratio) * u).toFloat()
                    }
                    else -> 1.0f
                }
            }
            rawB = DjDsp.timeStretchStereo(sliceRawB, sr, speedB)
            bFrames = rawB.size / 2
            approxEndFrameB = entryBFrame + nRawB
        } else {
            rawB = DjDsp.sliceStereo(sliceB, entryBFrame, targetOutBFrames)
            scaleStereoInPlace(rawB, gainB)
            bFrames = rawB.size / 2
            approxEndFrameB = entryBFrame + bFrames
        }

        // Apertura suave LPF sobre B (`360 -> 19500 Hz`) y empate limpio al B seco antes del post-roll.
        val bSwept = rawB.copyOf()
        if (bFrames >= 64) {
            val openFrames = min(
                bFrames,
                if (tempoMatched) (durFrames * 0.72f).roundToInt() else ((durFrames - bStartIdx) * 0.42f).roundToInt(),
            ).coerceAtLeast(64)
            val lpfStart = if (tempoMatched) 360f else 520f
            val lpfHz = FloatArray(bFrames) { i ->
                if (i < openFrames) {
                    val u = i.toFloat() / max(1, openFrames - 1)
                    (lpfStart * (19_500f / lpfStart).pow(u)).coerceIn(300f, 19_500f)
                } else 19_500f
            }
            val lpfFiltered = DjDsp.svfSweepStereo(rawB, sr, lpfHz, highpass = false, resonance = 0.72f, steep24Db = false)
            val seam = min(bFrames - openFrames, (0.025 * sr).roundToInt())
            System.arraycopy(lpfFiltered, 0, bSwept, 0, openFrames * 2)
            for (k in 0 until seam) {
                val u = smoothStep(k.toFloat() / max(1, seam))
                val idx = openFrames + k
                bSwept[idx * 2] = lpfFiltered[idx * 2] * (1f - u) + rawB[idx * 2] * u
                bSwept[idx * 2 + 1] = lpfFiltered[idx * 2 + 1] * (1f - u) + rawB[idx * 2 + 1] * u
            }
        }

        // Eco y reverb alimentados solo al final agudo de A y extendidos libremente sobre el tramo de B.
        val total = max(durFrames, bStartIdx + bFrames)
        // La cola del eco se apaga en ~1,8 s aunque B siga más (vuelta a 1×): si no, el eco se alargaría con ella.
        val tailSpan = max(1, min(total - durFrames, (1.8 * sr).roundToInt()))
        val filteredPadded = FloatArray(total * 2)
        System.arraycopy(filteredA, 0, filteredPadded, 0, durFrames * 2)

        val sendEnvFull = FloatArray(total) { i ->
            if (i >= durFrames) 0f else {
                val p = i.toFloat() / max(1, durFrames - 1)
                if (tempoMatched) {
                    smoothStep(((p - 0.16f) / 0.56f).coerceIn(0f, 1f)) * (1f - 0.35f * smoothStep(((p - 0.78f) / 0.22f).coerceIn(0f, 1f)))
                } else {
                    smoothStep(((p - 0.18f) / 0.26f).coerceIn(0f, 1f)) * (1f - 0.35f * smoothStep(((p - 0.54f) / 0.24f).coerceIn(0f, 1f)))
                }
            }
        }

        val useEcho = spec.echoDelayMs > 1.0
        val echo = if (useEcho) {
            val fbLevel = if (tempoMatched) 0.45f else 0.34f
            val wetPeak = if (tempoMatched) 0.38f else 0.32f
            val fbEnvFull = FloatArray(total) { i ->
                if (i < durFrames) fbLevel else {
                    val tFrac = (i - durFrames).toFloat() / tailSpan
                    (fbLevel * (1f - 0.65f * tFrac)).coerceAtLeast(0.08f)
                }
            }
            val wetEnvFull = FloatArray(total) { i ->
                if (i < durFrames) {
                    val p = i.toFloat() / max(1, durFrames - 1)
                    smoothStep(((p - 0.16f) / 0.28f).coerceIn(0f, 1f)) * wetPeak
                } else {
                    val tFrac = ((i - durFrames).toFloat() / tailSpan).coerceIn(0f, 1f)
                    (1f - smoothStep(tFrac)) * wetPeak
                }
            }
            DjDsp.pingPongDelayStereo(filteredPadded, sr, spec.echoDelayMs, fbEnvFull, sendEnvFull, wetEnvFull)
        } else null

        val reverbWetFull = FloatArray(total) { i ->
            if (i < durFrames) {
                val p = i.toFloat() / max(1, durFrames - 1)
                smoothStep(((p - 0.18f) / 0.32f).coerceIn(0f, 1f)) * 0.15f
            } else {
                val tFrac = ((i - durFrames).toFloat() / tailSpan).coerceIn(0f, 1f)
                (1f - smoothStep(tFrac)) * 0.15f
            }
        }
        val reverb = DjDsp.stereoReverb(filteredPadded, sr, sendEnvFull, reverbWetFull, roomSize = spec.reverbRoom.coerceIn(0.32f, 0.48f))

        val out = FloatArray(total * 2)
        for (i in 0 until total) {
            if (i < durFrames) {
                val p = i.toFloat() / max(1, durFrames - 1)
                val dryA = if (tempoMatched) {
                    cos(smoothStep(p) * PI * 0.5).toFloat()
                } else {
                    1f - smoothStep(((p - 0.14f) / 0.44f).coerceIn(0f, 1f))
                }
                out[i * 2] += filteredA[i * 2] * dryA
                out[i * 2 + 1] += filteredA[i * 2 + 1] * dryA
            }
            if (echo != null && i * 2 + 1 < echo.size) {
                out[i * 2] += echo[i * 2]
                out[i * 2 + 1] += echo[i * 2 + 1]
            }
            if (i * 2 + 1 < reverb.size) {
                out[i * 2] += reverb[i * 2]
                out[i * 2 + 1] += reverb[i * 2 + 1]
            }
        }
        for (i in 0 until bFrames) {
            val gB = if (tempoMatched) {
                if (bStartIdx + i < durFrames) {
                    val p = (bStartIdx + i).toFloat() / max(1, durFrames - 1)
                    sin(smoothStep(((p - 0.06f) / 0.84f).coerceIn(0f, 1f)) * PI * 0.5).toFloat()
                } else 1f
            } else {
                val fadeLen = min(bFrames, ((durFrames - bStartIdx) * 0.72f).roundToInt()).coerceAtLeast(sr / 3)
                if (i < fadeLen) sin(smoothStep(i.toFloat() / fadeLen) * PI * 0.5).toFloat() else 1f
            }
            val dst = bStartIdx + i
            out[dst * 2] += bSwept[i * 2] * gB
            out[dst * 2 + 1] += bSwept[i * 2 + 1] * gB
        }
        val handoffMs = if (useRatio && bStartIdx + bFrames >= total && bFrames > sr / 2) {
            blendContinuityHandoffB(
                out = out,
                total = total,
                bFrames = bFrames,
                sliceB = sliceB,
                approxEndFrameB = approxEndFrameB,
                originB = originB,
                gainB = gainB,
                minHandoffMs = (spec.entryInMs + shiftBMs).roundToLong().coerceAtLeast(originB),
                sr = sr,
            )
        } else {
            originB + (((entryBFrame + bFrames) * 1000.0) / sr).roundToLong()
        }
        return out to handoffMs
    }

    /**
     * 5. `smart_fade`: curva S equal-power + FIR precalentado + cruce limpio de graves a 150 Hz.
     *
     * Con [tempoLocked] (el plan lo marca con `beatAligned = true` tras comprobar bombo en las dos y el límite de
     * estiramiento) B entra en la fase de A medida sobre el PCM y, si hace falta, a tempo de A durante el fundido;
     * después vuelve a 1× ya sola (mismas reglas que el cuadre de beats). Sin eso, el fundido de siempre a 1×.
     */
    private fun renderSmartFade(
        spec: DjBridgeSpec,
        sliceA: FloatArray,
        originA: Long,
        sliceB: FloatArray,
        originB: Long,
        gainA: Float,
        gainB: Float,
        sr: Int,
        tempoLocked: Boolean = false,
    ): Pair<FloatArray, Long> {
        if (tempoLocked) return renderLockedSmartFade(spec, sliceA, originA, sliceB, originB, gainA, gainB, sr)
        val durFrames = (((spec.transitionDurationMs / 1000.0) * sr).roundToInt()).coerceIn(sr * 2, sr * 12)
        val startAFrame = (((spec.cueOutStartMs - originA) / 1000.0) * sr).roundToInt().coerceAtLeast(0)
        val entryBFrame = (((spec.entryInMs - originB) / 1000.0) * sr).roundToInt().coerceAtLeast(0)

        val (lowA, midA, highA) = threeBandWithWarmupStereo(
            slice = sliceA,
            startFrame = startAFrame,
            lengthFrames = durFrames,
            sr = sr,
            lowHz = 150.0,
            highHz = 2_400.0,
            scale = gainA,
        )
        val (lowB, midB, highB) = threeBandWithWarmupStereo(
            slice = sliceB,
            startFrame = entryBFrame,
            lengthFrames = durFrames,
            sr = sr,
            lowHz = 150.0,
            highHz = 2_400.0,
            scale = gainB,
        )
        val farTempo = spec.bpmA > 20.0 && spec.bpmB > 20.0 && DjHarmonic.bpmRelation(spec.bpmA, spec.bpmB).relDiffPct > 8.5

        val out = FloatArray(durFrames * 2)
        for (i in 0 until durFrames) {
            val s = smoothStep(i.toFloat() / max(1, durFrames - 1))
            val outG = cos(s * PI * 0.5).toFloat()
            val inG = sin(s * PI * 0.5).toFloat()
            val outLowMult = if (farTempo) {
                1f - smoothStep((s / 0.48f).coerceIn(0f, 1f))
            } else {
                1f - smoothStep(((s - 0.08f) / 0.50f).coerceIn(0f, 1f))
            }
            val inLowMult = if (farTempo) {
                smoothStep(((s - 0.44f) / 0.48f).coerceIn(0f, 1f))
            } else {
                0.15f + 0.85f * smoothStep(((s - 0.35f) / 0.55f).coerceIn(0f, 1f))
            }

            out[i * 2] = (lowA[i * 2] * outLowMult + midA[i * 2] + highA[i * 2]) * outG +
                (lowB[i * 2] * inLowMult + midB[i * 2] + highB[i * 2]) * inG
            out[i * 2 + 1] = (lowA[i * 2 + 1] * outLowMult + midA[i * 2 + 1] + highA[i * 2 + 1]) * outG +
                (lowB[i * 2 + 1] * inLowMult + midB[i * 2 + 1] + highB[i * 2 + 1]) * inG
        }
        val bConsumedMs = ((durFrames * 1000.0) / sr).roundToLong()
        return out to (spec.entryInMs + bConsumedMs)
    }

    /** Fundido suave con B en fase (y a tempo de A si hace falta) y vuelta a 1× de B ya sola. */
    private fun renderLockedSmartFade(
        spec: DjBridgeSpec,
        sliceA: FloatArray,
        originA: Long,
        sliceB: FloatArray,
        originB: Long,
        gainA: Float,
        gainB: Float,
        sr: Int,
    ): Pair<FloatArray, Long> {
        val durFrames = (((spec.transitionDurationMs / 1000.0) * sr).roundToInt()).coerceIn(sr * 2, sr * 12)
        val startAFrame = (((spec.cueOutStartMs - originA) / 1000.0) * sr).roundToInt().coerceAtLeast(0)
        val ratio = beatMatchRatio(spec)
        val stretched = DjTempoMatch.isStretched(ratio)
        val shiftBMs = spec.kickShiftMs.coerceIn(-420.0, 800.0)
        val entryBFrame = (((spec.entryInMs + shiftBMs - originB).coerceAtLeast(0.0) / 1000.0) * sr).roundToInt()

        val (lowA, midA, highA) = threeBandWithWarmupStereo(
            slice = sliceA,
            startFrame = startAFrame,
            lengthFrames = durFrames,
            sr = sr,
            lowHz = 150.0,
            highHz = 2_400.0,
            scale = gainA,
        )
        val lowB: FloatArray
        val midB: FloatArray
        val highB: FloatArray
        val bFrames: Int
        var approxEndB = entryBFrame + durFrames
        if (stretched) {
            // Mismo reparto que el cuadre de beats: tempo constante mientras suena A, vuelta en beats ya sola, cola seca.
            val overlapRawB = (durFrames * ratio).roundToInt().coerceAtLeast(sr)
            val glideOutFrames = ((DjTempoMatch.renderedGlideMs(spec.bpmA, ratio) / 1000.0) * sr).roundToInt()
            val glideRawB = (glideOutFrames * (ratio + 1.0) * 0.5).roundToInt().coerceAtLeast(sr / 2)
            val dryTailRawB = (0.20 * sr).roundToInt()
            val rawB = DjDsp.sliceStereo(sliceB, entryBFrame, overlapRawB + glideRawB + dryTailRawB)
            scaleStereoInPlace(rawB, gainB)
            val rawBFrames = rawB.size / 2
            val speedB = FloatArray(rawBFrames) { i ->
                when {
                    i < overlapRawB -> ratio.toFloat()
                    i < overlapRawB + glideRawB -> {
                        val u = smoothStep(((i - overlapRawB).toFloat() / max(1, glideRawB - 1)).coerceIn(0f, 1f))
                        (ratio + (1.0 - ratio) * u).toFloat()
                    }
                    else -> 1.0f
                }
            }
            val stretchedB = DjDsp.timeStretchStereo(rawB, sr, speedB)
            bFrames = stretchedB.size / 2
            approxEndB = entryBFrame + rawBFrames
            val bands = threeBandOfStretchedB(sliceB, entryBFrame, rawBFrames, stretchedB, gainB, sr, 150.0, 2_400.0)
            lowB = bands.first
            midB = bands.second
            highB = bands.third
        } else {
            val bands = threeBandWithWarmupStereo(sliceB, entryBFrame, durFrames, sr, 150.0, 2_400.0, gainB)
            lowB = bands.first
            midB = bands.second
            highB = bands.third
            bFrames = durFrames
        }

        val total = max(durFrames, bFrames)
        val out = FloatArray(total * 2)
        for (i in 0 until total) {
            if (i < durFrames) {
                // Curvas del fundido con tempo cercano (las dos en fase: los graves pueden solaparse sin golpe doble).
                val s = smoothStep(i.toFloat() / max(1, durFrames - 1))
                val outG = cos(s * PI * 0.5).toFloat()
                val inG = sin(s * PI * 0.5).toFloat()
                val outLowMult = 1f - smoothStep(((s - 0.08f) / 0.50f).coerceIn(0f, 1f))
                val inLowMult = 0.15f + 0.85f * smoothStep(((s - 0.35f) / 0.55f).coerceIn(0f, 1f))
                var l = (lowA[i * 2] * outLowMult + midA[i * 2] + highA[i * 2]) * outG
                var r = (lowA[i * 2 + 1] * outLowMult + midA[i * 2 + 1] + highA[i * 2 + 1]) * outG
                if (i < bFrames) {
                    l += (lowB[i * 2] * inLowMult + midB[i * 2] + highB[i * 2]) * inG
                    r += (lowB[i * 2 + 1] * inLowMult + midB[i * 2 + 1] + highB[i * 2 + 1]) * inG
                }
                out[i * 2] = l
                out[i * 2 + 1] = r
            } else if (i < bFrames) {
                out[i * 2] = lowB[i * 2] + midB[i * 2] + highB[i * 2]
                out[i * 2 + 1] = lowB[i * 2 + 1] + midB[i * 2 + 1] + highB[i * 2 + 1]
            }
        }
        val handoffMs = if (stretched) {
            blendContinuityHandoffB(
                out = out,
                total = total,
                bFrames = bFrames,
                sliceB = sliceB,
                approxEndFrameB = approxEndB,
                originB = originB,
                gainB = gainB,
                minHandoffMs = (spec.entryInMs + shiftBMs).roundToLong().coerceAtLeast(originB),
                sr = sr,
            )
        } else {
            originB + (((entryBFrame + durFrames) * 1000.0) / sr).roundToLong()
        }
        return out to handoffMs
    }

    /** Las tres bandas de B ya estirada, con medio segundo de B seca a cada lado para precalentar el FIR. */
    private fun threeBandOfStretchedB(
        sliceB: FloatArray,
        entryBFrame: Int,
        rawBFrames: Int,
        stretchedB: FloatArray,
        gainB: Float,
        sr: Int,
        lowHz: Double,
        highHz: Double,
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val bFrames = stretchedB.size / 2
        val prePadB = min((0.50 * sr).roundToInt(), entryBFrame)
        val approxEndB = entryBFrame + rawBFrames
        val postPadB = min((0.50 * sr).roundToInt(), (sliceB.size / 2 - approxEndB).coerceAtLeast(0))
        val warmB = FloatArray((prePadB + bFrames + postPadB) * 2)
        if (prePadB > 0) {
            val preB = DjDsp.sliceStereo(sliceB, entryBFrame - prePadB, prePadB)
            scaleStereoInPlace(preB, gainB)
            System.arraycopy(preB, 0, warmB, 0, prePadB * 2)
        }
        System.arraycopy(stretchedB, 0, warmB, prePadB * 2, bFrames * 2)
        if (postPadB > 0) {
            val postB = DjDsp.sliceStereo(sliceB, approxEndB, postPadB)
            scaleStereoInPlace(postB, gainB)
            System.arraycopy(postB, 0, warmB, (prePadB + bFrames) * 2, postPadB * 2)
        }
        val bandsBFull = DjDsp.linearPhaseThreeBandStereo(warmB, sr, lowHz = lowHz, highHz = highHz)
        return Triple(
            DjDsp.sliceStereo(bandsBFull.low, prePadB, bFrames),
            DjDsp.sliceStereo(bandsBFull.mid, prePadB, bFrames),
            DjDsp.sliceStereo(bandsBFull.high, prePadB, bFrames),
        )
    }

    /**
     * Si el fundido suave va a tempo: lo pide el plan (`beatAligned = true`, que solo pone tras comprobar bombo al pulso
     * en las dos y el límite de estiramiento según la voz de B), con los dos tempos, dentro del 8 % y con un ratio
     * coherente con esos tempos (un plan incoherente no se fuerza: sonaría a golpes desfasados). Sin el dato, nunca.
     */
    internal fun smartFadeTempoLocked(spec: DjBridgeSpec): Boolean {
        if (spec.style != DjBridgeStyle.SmartFade || spec.beatAligned != true) return false
        if (spec.bpmA <= 20.0 || spec.bpmB <= 20.0 || spec.transitionDurationMs <= 0L) return false
        if (DjTempoMatch.stretchPct(spec.tempoMatchRatio) > DjTempoMatch.MAX_STRETCH_INSTRUMENTAL_PCT) return false
        val expected = DjHarmonic.bpmRelation(spec.bpmA, spec.bpmB).ratioOutOverIn
        return abs(spec.tempoMatchRatio / expected - 1.0) <= LOCK_RATIO_TOLERANCE
    }

    /** Diferencia máxima entre el ratio del plan y el de sus BPM para fiarse de él (0,6 %: ~36 ms en 6 s a 120 BPM). */
    private const val LOCK_RATIO_TOLERANCE = 0.006

    /**
     * Si el tramo decodificado de B alcanza para el fundido a tempo (cruce estirado + vuelta + cola + corrección de fase)
     * y medio segundo de tramo seco. Si no, el fundido va a 1× sin tocar la fase: mejor eso que un relevo sin tramo seco.
     */
    private fun lockFitsInSlice(spec: DjBridgeSpec, sliceBFrames: Int, originB: Long, sr: Int): Boolean {
        val needMs = DjTempoMatch.lockedCrossSourceMs(spec.transitionDurationMs, spec.bpmA, beatMatchRatio(spec)) + 500L
        val sliceEndMs = originB + ((sliceBFrames * 1000.0) / sr).roundToLong()
        return spec.entryInMs + needMs <= sliceEndMs
    }

    /** Velocidad de B que el renderizado aplica de verdad en cada estilo (1 si no la estira). */
    internal fun appliedTempoRatio(spec: DjBridgeSpec, smartFadeLock: Boolean): Double {
        val r = when (spec.style) {
            DjBridgeStyle.BeatMatchLongMix -> beatMatchRatio(spec)
            DjBridgeStyle.LyricLoopCut -> if (spec.lyricLoop != null) beatMatchRatio(spec) else filterEchoRatio(spec)
            DjBridgeStyle.FilterEcho -> filterEchoRatio(spec)
            DjBridgeStyle.SmartFade -> if (smartFadeLock) beatMatchRatio(spec) else 1.0
            DjBridgeStyle.EnergyDropCut, DjBridgeStyle.OutroTail -> 1.0
        }
        return if (DjTempoMatch.isStretched(r)) r else 1.0
    }

    private fun filterEchoRatio(spec: DjBridgeSpec): Double =
        if (echoBeatAligned(spec) && DjTempoMatch.isStretched(spec.tempoMatchRatio)) DjTempoMatch.clampRatio(spec.tempoMatchRatio) else 1.0

    private fun threeBandWithWarmupStereo(
        slice: FloatArray,
        startFrame: Int,
        lengthFrames: Int,
        sr: Int,
        lowHz: Double,
        highHz: Double,
        scale: Float,
        warmupFrames: Int = (1.2 * sr).roundToInt(),
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val totalInSlice = slice.size / 2
        val safeStart = startFrame.coerceAtLeast(0)
        val prePad = min(warmupFrames, safeStart)
        val availAfter = (totalInSlice - (safeStart + lengthFrames)).coerceAtLeast(0)
        val postPad = min(warmupFrames, availAfter)
        val padded = DjDsp.sliceStereo(slice, safeStart - prePad, prePad + lengthFrames + postPad)
        scaleStereoInPlace(padded, scale)
        val bands = DjDsp.linearPhaseThreeBandStereo(padded, sr, lowHz = lowHz, highHz = highHz)
        return Triple(
            DjDsp.sliceStereo(bands.low, prePad, lengthFrames),
            DjDsp.sliceStereo(bands.mid, prePad, lengthFrames),
            DjDsp.sliceStereo(bands.high, prePad, lengthFrames),
        )
    }

    private fun smoothSpliceSeam(stereo: FloatArray, frameBoundary: Int, sr: Int) {
        val half = min((0.004 * sr).roundToInt(), min(frameBoundary, (stereo.size / 2) - frameBoundary))
        if (half <= 2) return
        // Pequeño alisado C1 de diferencia justo en el punto de unión para garantizar pendiente cero sin salto.
        val jumpL = stereo[frameBoundary * 2] - stereo[(frameBoundary - 1) * 2]
        val jumpR = stereo[frameBoundary * 2 + 1] - stereo[(frameBoundary - 1) * 2 + 1]
        if (abs(jumpL) < 0.02f && abs(jumpR) < 0.02f) return
        for (k in 0 until half) {
            val w = 0.5f * (1f - (k.toFloat() / half))
            val aIdx = (frameBoundary - 1 - k) * 2
            val bIdx = (frameBoundary + k) * 2
            stereo[aIdx] += jumpL * w * 0.5f
            stereo[aIdx + 1] += jumpR * w * 0.5f
            stereo[bIdx] -= jumpL * w * 0.5f
            stereo[bIdx + 1] -= jumpR * w * 0.5f
        }
    }

    private fun scaleStereoInPlace(buf: FloatArray, scale: Float) {
        if (abs(scale - 1f) < 1e-4f) return
        for (i in buf.indices) buf[i] *= scale
    }

    /** En `filter_echo` a tempo, B entra desde el tiempo 0 alineada en fase; con BPM lejanos espera al 48% para que A vacíe graves. */
    private fun filterEchoBStartFrames(spec: DjBridgeSpec, durFrames: Int, tempoMatched: Boolean, sr: Int): Int {
        if (!tempoMatched) return (durFrames * 0.48f).roundToInt()
        return 0
    }

    /**
     * `FilterEcho` con B a tempo y en fase: lo dice el plan (`beatAligned`). Solo en specs hechos a mano sin ese dato se
     * deduce de los BPM como antes.
     */
    private fun echoBeatAligned(spec: DjBridgeSpec): Boolean = spec.beatAligned
        ?: ((spec.bpmA > 20.0 && spec.bpmB > 20.0 && DjHarmonic.bpmRelation(spec.bpmA, spec.bpmB).relDiffPct <= 9.5) ||
            DjTempoMatch.isStretched(spec.tempoMatchRatio))

    /** Velocidad a la que B va al tempo de A (`bpmA / bpmB efectivo`); 1 si falta alguno de los dos tempos. */
    private fun beatMatchRatio(spec: DjBridgeSpec): Double =
        if (spec.bpmA > 20.0 && spec.bpmB > 20.0) DjTempoMatch.clampRatio(spec.tempoMatchRatio) else 1.0

    private fun smoothStep(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return (0.5f * (1f - cos(c * PI).toFloat()))
    }
}
