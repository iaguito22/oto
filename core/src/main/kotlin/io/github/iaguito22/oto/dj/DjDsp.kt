package io.github.iaguito22.oto.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Núcleo DSP en Kotlin puro para análisis y mezcla de DJ sin dependencias nativas ni FFmpeg.
 * Trabaja habitualmente sobre PCM estéreo interleaved (`[L0, R0, L1, R1, ...]`) a [DJ_SAMPLE_RATE] (48 kHz)
 * o mono normalizado en `[-1f, 1f]`.
 */
object DjDsp {
    const val DJ_SAMPLE_RATE = 48_000

    fun dbToLin(db: Double): Double = 10.0.pow(db / 20.0)

    fun dbToLinF(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

    fun linToDb(v: Double): Double = 20.0 * log10(max(v, 1e-9))

    /** Saturación musical suave para picos transitorios antes del limitador final. */
    fun softClip(x: Float): Float = (tanh((x / 1.08f).toDouble()) * 1.08).toFloat()

    fun hannWindow(n: Int): FloatArray = FloatArray(n) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / max(1, n)))).toFloat()
    }

    fun cosineWindow(n: Int): FloatArray = FloatArray(n) { i ->
        sin(PI * i / max(1, n)).toFloat()
    }

    /** Secciones biquad en forma directa II transpuesta (doble precisión en estado interno). */
    class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun reset() {
            z1 = 0.0
            z2 = 0.0
        }

        fun processSample(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun processInPlace(samples: DoubleArray, count: Int = samples.size) {
            for (i in 0 until count) {
                samples[i] = processSample(samples[i])
            }
        }

        companion object {
            private val INV_SQRT2 = 1.0 / sqrt(2.0)

            fun lowpass(cutoffHz: Double, sampleRate: Int, q: Double = INV_SQRT2): Biquad {
                val fc = cutoffHz.coerceIn(10.0, sampleRate * 0.45)
                val w0 = 2.0 * PI * fc / sampleRate
                val cosW = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                val b0 = (1.0 - cosW) * 0.5 / a0
                val b1 = (1.0 - cosW) / a0
                return Biquad(b0, b1, b0, (-2.0 * cosW) / a0, (1.0 - alpha) / a0)
            }

            fun highpass(cutoffHz: Double, sampleRate: Int, q: Double = INV_SQRT2): Biquad {
                val fc = cutoffHz.coerceIn(10.0, sampleRate * 0.45)
                val w0 = 2.0 * PI * fc / sampleRate
                val cosW = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                val b0 = (1.0 + cosW) * 0.5 / a0
                val b1 = -(1.0 + cosW) / a0
                return Biquad(b0, b1, b0, (-2.0 * cosW) / a0, (1.0 - alpha) / a0)
            }

            /** Pasa-todo de 2º orden para alinear fase en crossovers Linkwitz-Riley de 3 bandas. */
            fun allpass(cutoffHz: Double, sampleRate: Int, q: Double = INV_SQRT2): Biquad {
                val fc = cutoffHz.coerceIn(10.0, sampleRate * 0.45)
                val w0 = 2.0 * PI * fc / sampleRate
                val cosW = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                val b0 = (1.0 - alpha) / a0
                val b1 = (-2.0 * cosW) / a0
                return Biquad(b0, b1, 1.0, b1, b0)
            }
        }
    }

    /** Filtro pasa-banda Butterworth de 4º orden (cascada de 2 HPF + 2 LPF de 2º orden). */
    fun bandpassOrder4(mono: FloatArray, lowHz: Double, highHz: Double, sampleRate: Int): FloatArray {
        val hp1 = Biquad.highpass(lowHz, sampleRate)
        val hp2 = Biquad.highpass(lowHz, sampleRate)
        val lp1 = Biquad.lowpass(highHz, sampleRate)
        val lp2 = Biquad.lowpass(highHz, sampleRate)
        val out = FloatArray(mono.size)
        for (i in mono.indices) {
            var s = hp1.processSample(mono[i].toDouble())
            s = hp2.processSample(s)
            s = lp1.processSample(s)
            s = lp2.processSample(s)
            out[i] = s.toFloat()
        }
        return out
    }

    data class ThreeBandStereo(
        val low: FloatArray,
        val mid: FloatArray,
        val high: FloatArray,
    )

    /**
     * Crossover Linkwitz-Riley de 4º orden (24 dB/oct) en 3 bandas con reconstrucción de magnitud plana
     * (`low + mid + high` conserva amplitud unitaria gracias al pasa-todo emparejado en la rama aguda).
     */
    fun lr4ThreeBandStereo(
        stereo: FloatArray,
        sampleRate: Int,
        lowHz: Double = 240.0,
        highHz: Double = 2800.0,
    ): ThreeBandStereo {
        val n = stereo.size
        val low = FloatArray(n)
        val mid = FloatArray(n)
        val high = FloatArray(n)
        for (ch in 0..1) {
            val splitLo1 = Biquad.lowpass(lowHz, sampleRate)
            val splitLo2 = Biquad.lowpass(lowHz, sampleRate)
            val splitMidHp1 = Biquad.highpass(lowHz, sampleRate)
            val splitMidHp2 = Biquad.highpass(lowHz, sampleRate)
            val splitBandLp1 = Biquad.lowpass(highHz, sampleRate)
            val splitBandLp2 = Biquad.lowpass(highHz, sampleRate)
            val splitHi1 = Biquad.highpass(highHz, sampleRate)
            val splitHi2 = Biquad.highpass(highHz, sampleRate)
            val apHigh = Biquad.allpass(lowHz, sampleRate)
            var idx = ch
            while (idx < n) {
                val x = stereo[idx].toDouble()
                val lm = splitBandLp2.processSample(splitBandLp1.processSample(x))
                val lo = splitLo2.processSample(splitLo1.processSample(lm))
                val md = splitMidHp2.processSample(splitMidHp1.processSample(lm))
                val hiRaw = splitHi2.processSample(splitHi1.processSample(x))
                val hi = apHigh.processSample(hiRaw)
                low[idx] = lo.toFloat()
                mid[idx] = md.toFloat()
                high[idx] = hi.toFloat()
                idx += 2
            }
        }
        return ThreeBandStereo(low, mid, high)
    }

    /**
     * Pasa-bajos FIR simétrico de fase lineal exacta (desfase nulo, ventana Blackman de 2049 taps) mediante convolución
     * overlap-add por FFT estéreo simultánea (re=L, im=R). Mantiene la alineación transitoria exacta del bombo.
     */
    fun linearPhaseLowpassStereo(
        stereo: FloatArray,
        sampleRate: Int,
        cutoffHz: Double,
    ): FloatArray {
        val frames = stereo.size / 2
        if (frames <= 0) return FloatArray(0)
        val taps = 2049
        val half = taps / 2
        val fc = cutoffHz.coerceIn(20.0, sampleRate * 0.45) / sampleRate
        val h = DoubleArray(taps)
        var sumH = 0.0
        for (n in 0 until taps) {
            val k = n - half
            val sinc = if (k == 0) 2.0 * fc else sin(2.0 * PI * fc * k) / (PI * k)
            val w = 0.42 - 0.5 * cos(2.0 * PI * n / (taps - 1)) + 0.08 * cos(4.0 * PI * n / (taps - 1))
            val v = sinc * w
            h[n] = v
            sumH += v
        }
        val invSum = 1.0 / sumH
        val fftSize = 4096
        val blockLen = fftSize - taps + 1
        val fft = Radix2Fft(fftSize)
        val hRe = DoubleArray(fftSize)
        val hIm = DoubleArray(fftSize)
        for (n in 0 until taps) hRe[n] = h[n] * invSum
        fft.transform(hRe, hIm, inverse = false)

        val out = FloatArray(frames * 2)
        val paddedLen = frames + 2 * half
        val xRe = DoubleArray(fftSize)
        val xIm = DoubleArray(fftSize)
        var base = 0
        while (base < paddedLen) {
            val take = min(blockLen, paddedLen - base)
            for (i in 0 until take) {
                val idx = mirroredIndex(base + i - half, frames)
                xRe[i] = stereo[idx * 2].toDouble()
                xIm[i] = stereo[idx * 2 + 1].toDouble()
            }
            for (i in take until fftSize) {
                xRe[i] = 0.0
                xIm[i] = 0.0
            }
            fft.transform(xRe, xIm, inverse = false)
            for (k in 0 until fftSize) {
                val xr = xRe[k]
                val xi = xIm[k]
                val hr = hRe[k]
                val hi = hIm[k]
                xRe[k] = xr * hr - xi * hi
                xIm[k] = xr * hi + xi * hr
            }
            fft.transform(xRe, xIm, inverse = true)
            for (t in 0 until fftSize) {
                val dst = base + t - 2 * half
                if (dst in 0 until frames) {
                    out[dst * 2] += xRe[t].toFloat()
                    out[dst * 2 + 1] += xIm[t].toFloat()
                }
            }
            base += blockLen
        }
        return out
    }

    /** Crossover de 3 bandas complementario y de fase lineal cero-retardo (`low + mid + high == stereo` exacto). */
    fun linearPhaseThreeBandStereo(
        stereo: FloatArray,
        sampleRate: Int,
        lowHz: Double = 175.0,
        highHz: Double = 2_800.0,
    ): ThreeBandStereo {
        if (stereo.isEmpty()) return ThreeBandStereo(FloatArray(0), FloatArray(0), FloatArray(0))
        val low = linearPhaseLowpassStereo(stereo, sampleRate, lowHz)
        val lowMid = linearPhaseLowpassStereo(stereo, sampleRate, highHz)
        val mid = FloatArray(stereo.size) { i -> lowMid[i] - low[i] }
        val high = FloatArray(stereo.size) { i -> stereo[i] - lowMid[i] }
        return ThreeBandStereo(low, mid, high)
    }

    private fun mirroredIndex(idx: Int, frames: Int): Int {
        if (frames <= 1) return 0
        var p = idx
        while (p < 0 || p >= frames) {
            p = if (p < 0) -p else 2 * frames - 2 - p
        }
        return p
    }

    /** Descompone en 3 bandas LR4 una señal mono (para análisis espectral por compás). */
    fun lr4ThreeBandMono(
        mono: FloatArray,
        sampleRate: Int,
        lowHz: Double = 250.0,
        highHz: Double = 2500.0,
    ): ThreeBandStereo {
        val n = mono.size
        val low = FloatArray(n)
        val mid = FloatArray(n)
        val high = FloatArray(n)
        val splitLo1 = Biquad.lowpass(lowHz, sampleRate)
        val splitLo2 = Biquad.lowpass(lowHz, sampleRate)
        val splitMidHp1 = Biquad.highpass(lowHz, sampleRate)
        val splitMidHp2 = Biquad.highpass(lowHz, sampleRate)
        val splitBandLp1 = Biquad.lowpass(highHz, sampleRate)
        val splitBandLp2 = Biquad.lowpass(highHz, sampleRate)
        val splitHi1 = Biquad.highpass(highHz, sampleRate)
        val splitHi2 = Biquad.highpass(highHz, sampleRate)
        val apHigh = Biquad.allpass(lowHz, sampleRate)
        for (i in 0 until n) {
            val x = mono[i].toDouble()
            val lm = splitBandLp2.processSample(splitBandLp1.processSample(x))
            low[i] = splitLo2.processSample(splitLo1.processSample(lm)).toFloat()
            mid[i] = splitMidHp2.processSample(splitMidHp1.processSample(lm)).toFloat()
            high[i] = apHigh.processSample(splitHi2.processSample(splitHi1.processSample(x))).toFloat()
        }
        return ThreeBandStereo(low, mid, high)
    }

    /** Ponderación K oficial ITU-R BS.1770-4 parametrizada para cualquier frecuencia de muestreo. */
    class KWeightingFilter(sampleRate: Int) {
        private val stage1: Biquad
        private val stage2: Biquad

        init {
            // Etapa 1: estante de agudos (+4 dB sobre ~1.68 kHz).
            val f0 = 1681.974450955533
            val gainDb = 3.999843853973347
            val q1 = 0.7071752369554196
            val k1 = tan(PI * min(f0, sampleRate * 0.45) / sampleRate)
            val vh = 10.0.pow(gainDb / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a01 = 1.0 + k1 / q1 + k1 * k1
            stage1 = Biquad(
                b0 = (vh + vb * k1 / q1 + k1 * k1) / a01,
                b1 = 2.0 * (k1 * k1 - vh) / a01,
                b2 = (vh - vb * k1 / q1 + k1 * k1) / a01,
                a1 = 2.0 * (k1 * k1 - 1.0) / a01,
                a2 = (1.0 - k1 / q1 + k1 * k1) / a01,
            )

            // Etapa 2: pasa-altos RLB (~38.14 Hz).
            val f2 = 38.13547087602444
            val q2 = 0.5003270373238773
            val k2 = tan(PI * min(f2, sampleRate * 0.45) / sampleRate)
            val a02 = 1.0 + k2 / q2 + k2 * k2
            stage2 = Biquad(
                b0 = 1.0 / a02,
                b1 = -2.0 / a02,
                b2 = 1.0 / a02,
                a1 = 2.0 * (k2 * k2 - 1.0) / a02,
                a2 = (1.0 - k2 / q2 + k2 * k2) / a02,
            )
        }

        fun stepMono(sample: Double): Double =
            stage2.processSample(stage1.processSample(sample))

        fun processMono(mono: FloatArray): FloatArray {
            val out = FloatArray(mono.size)
            for (i in mono.indices) {
                out[i] = stage2.processSample(stage1.processSample(mono[i].toDouble())).toFloat()
            }
            return out
        }

        fun processStereoToMeanPower(stereo: FloatArray): DoubleArray {
            val frames = stereo.size / 2
            val s1L = KWeightingStagePair(stage1, stage2)
            val s1R = KWeightingStagePair(stage1Copy(), stage2Copy())
            val power = DoubleArray(frames)
            for (f in 0 until frames) {
                val l = s1L.step(stereo[f * 2].toDouble())
                val r = s1R.step(stereo[f * 2 + 1].toDouble())
                power[f] = l * l + r * r
            }
            return power
        }

        private fun stage1Copy(): Biquad = createStage1(1681.974450955533)

        private fun stage2Copy(): Biquad = createStage2()

        private val srRef = sampleRate
        private fun createStage1(f0: Double): Biquad {
            val gainDb = 3.999843853973347
            val q1 = 0.7071752369554196
            val k1 = tan(PI * min(f0, srRef * 0.45) / srRef)
            val vh = 10.0.pow(gainDb / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a01 = 1.0 + k1 / q1 + k1 * k1
            return Biquad(
                (vh + vb * k1 / q1 + k1 * k1) / a01,
                2.0 * (k1 * k1 - vh) / a01,
                (vh - vb * k1 / q1 + k1 * k1) / a01,
                2.0 * (k1 * k1 - 1.0) / a01,
                (1.0 - k1 / q1 + k1 * k1) / a01,
            )
        }

        private fun createStage2(): Biquad {
            val f2 = 38.13547087602444
            val q2 = 0.5003270373238773
            val k2 = tan(PI * min(f2, srRef * 0.45) / srRef)
            val a02 = 1.0 + k2 / q2 + k2 * k2
            return Biquad(1.0 / a02, -2.0 / a02, 1.0 / a02, 2.0 * (k2 * k2 - 1.0) / a02, (1.0 - k2 / q2 + k2 * k2) / a02)
        }

        private class KWeightingStagePair(private val s1: Biquad, private val s2: Biquad) {
            fun step(x: Double): Double = s2.processSample(s1.processSample(x))
        }
    }

    /** LUFS integrado (ITU-R BS.1770-4 con puerta de -70 LKFS y puerta relativa de -10 LU). */
    fun integratedLufsMono(mono: FloatArray, sampleRate: Int): Double {
        if (mono.isEmpty()) return -70.0
        val kw = KWeightingFilter(sampleRate).processMono(mono)
        val blockSamples = max(1, (0.40 * sampleRate).roundToInt())
        val stepSamples = max(1, (0.10 * sampleRate).roundToInt())
        if (kw.size <= blockSamples) {
            val meanSq = kw.sumOf { (it * it).toDouble() } / kw.size
            // Duplicado en 2 canales idénticos como en señal mono centrada -> factor 2.0 -> pero estándar usa z = meanSq por canal: L+R = 2*meanSq o mono?
            // Nota: en estéreo dual-mono E = msL + msR = 2 * meanSq -> -0.691 + 10*log10(2*ms) = +2.319 + 10*log10(ms), pero con un canal solo E = ms -> -0.691 + 10*log10(ms).
            // Para coherencia con ffmpeg/BS1770 de una señal mono tratada como 1 canal (C=1, g_i=1), usamos 1.0 * meanSq.
            return if (meanSq <= 1e-12) -70.0 else (-0.691 + 10.0 * log10(meanSq)).coerceIn(-70.0, 6.0)
        }
        val pref = DoubleArray(kw.size + 1)
        for (i in kw.indices) {
            val v = kw[i].toDouble()
            pref[i + 1] = pref[i] + v * v
        }
        val blockMeans = ArrayList<Double>((kw.size / stepSamples) + 1)
        val gatedAbsThreshold = 10.0.pow((-70.0 + 0.691) / 10.0)
        var pos = 0
        while (pos + blockSamples <= kw.size) {
            val ms = (pref[pos + blockSamples] - pref[pos]) / blockSamples
            if (ms > gatedAbsThreshold) blockMeans += ms
            pos += stepSamples
        }
        if (blockMeans.isEmpty()) return -70.0
        val avgAbs = blockMeans.average()
        val relThreshold = avgAbs * 0.1 // -10 LU
        val gated = blockMeans.filter { it >= relThreshold }
        val finalMs = (if (gated.isNotEmpty()) gated.average() else avgAbs).coerceAtLeast(1e-12)
        return (-0.691 + 10.0 * log10(finalMs)).coerceIn(-70.0, 6.0)
    }

    /** LUFS integrado sobre PCM estéreo interleaved a [sampleRate]. */
    fun integratedLufsStereo(stereo: FloatArray, sampleRate: Int): Double {
        val frames = stereo.size / 2
        if (frames == 0) return -70.0
        val power = KWeightingFilter(sampleRate).processStereoToMeanPower(stereo)
        val blockFrames = max(1, (0.40 * sampleRate).roundToInt())
        val stepFrames = max(1, (0.10 * sampleRate).roundToInt())
        if (frames <= blockFrames) {
            val ms = power.average()
            return if (ms <= 1e-12) -70.0 else (-0.691 + 10.0 * log10(ms)).coerceIn(-70.0, 6.0)
        }
        val pref = DoubleArray(frames + 1)
        for (i in 0 until frames) pref[i + 1] = pref[i] + power[i]
        val blocks = ArrayList<Double>((frames / stepFrames) + 1)
        val absGate = 10.0.pow((-70.0 + 0.691) / 10.0)
        var pos = 0
        while (pos + blockFrames <= frames) {
            val ms = (pref[pos + blockFrames] - pref[pos]) / blockFrames
            if (ms > absGate) blocks += ms
            pos += stepFrames
        }
        if (blocks.isEmpty()) return -70.0
        val avgAbs = blocks.average()
        val relGate = avgAbs * 0.1
        val finalMs = (blocks.filter { it >= relGate }.takeIf { it.isNotEmpty() }?.average() ?: avgAbs).coerceAtLeast(1e-12)
        return (-0.691 + 10.0 * log10(finalMs)).coerceIn(-70.0, 6.0)
    }

    /**
     * FFT radix-2 iterativa de Cooley-Tukey (sin asignaciones dentro de la transformada).
     */
    class Radix2Fft(val size: Int) {
        init {
            require(size > 1 && (size and (size - 1)) == 0) { "FFT size must be power of 2, was $size" }
        }

        private val bitRev = IntArray(size)
        private val cosTable = DoubleArray(size / 2)
        private val sinTable = DoubleArray(size / 2)
        val re = DoubleArray(size)
        val im = DoubleArray(size)

        init {
            val bits = Integer.numberOfTrailingZeros(size)
            for (i in 0 until size) {
                bitRev[i] = Integer.reverse(i) ushr (32 - bits)
            }
            for (k in 0 until size / 2) {
                val angle = -2.0 * PI * k / size
                cosTable[k] = cos(angle)
                sinTable[k] = sin(angle)
            }
        }

        /** Calcula el espectro de magnitud en `outMag[0 .. size/2]`. */
        fun magnitudeSpectrum(windowedFrame: DoubleArray, outMag: DoubleArray) {
            for (i in 0 until size) {
                val j = bitRev[i]
                re[i] = if (j < windowedFrame.size) windowedFrame[j] else 0.0
                im[i] = 0.0
            }
            var halfSize = 1
            while (halfSize < size) {
                val phaseStep = (size / 2) / halfSize
                var i = 0
                while (i < size) {
                    var tabIdx = 0
                    for (j in 0 until halfSize) {
                        val c = cosTable[tabIdx]
                        val s = sinTable[tabIdx]
                        val k = i + j + halfSize
                        val tRe = re[k] * c - im[k] * s
                        val tIm = re[k] * s + im[k] * c
                        re[k] = re[i + j] - tRe
                        im[k] = im[i + j] - tIm
                        re[i + j] += tRe
                        im[i + j] += tIm
                        tabIdx += phaseStep
                    }
                    i += halfSize * 2
                }
                halfSize = halfSize shl 1
            }
            val bins = min(outMag.size, size / 2 + 1)
            for (b in 0 until bins) {
                outMag[b] = sqrt(re[b] * re[b] + im[b] * im[b])
            }
        }

        /** Transformada discreta compleja in-place (`inverse = true` aplica IFFT normalizada por `1/size`). */
        fun transform(reBuf: DoubleArray, imBuf: DoubleArray, inverse: Boolean = false) {
            for (i in 0 until size) {
                val j = bitRev[i]
                if (i < j) {
                    val tr = reBuf[i]; reBuf[i] = reBuf[j]; reBuf[j] = tr
                    val ti = imBuf[i]; imBuf[i] = imBuf[j]; imBuf[j] = ti
                }
            }
            val sign = if (inverse) -1.0 else 1.0
            var halfSize = 1
            while (halfSize < size) {
                val phaseStep = (size / 2) / halfSize
                var i = 0
                while (i < size) {
                    var tabIdx = 0
                    for (j in 0 until halfSize) {
                        val c = cosTable[tabIdx]
                        val s = sinTable[tabIdx] * sign
                        val k = i + j + halfSize
                        val tRe = reBuf[k] * c - imBuf[k] * s
                        val tIm = reBuf[k] * s + imBuf[k] * c
                        reBuf[k] = reBuf[i + j] - tRe
                        imBuf[k] = imBuf[i + j] - tIm
                        reBuf[i + j] += tRe
                        imBuf[i + j] += tIm
                        tabIdx += phaseStep
                    }
                    i += halfSize * 2
                }
                halfSize = halfSize shl 1
            }
            if (inverse) {
                val invN = 1.0 / size
                for (i in 0 until size) {
                    reBuf[i] *= invN
                    imBuf[i] *= invN
                }
            }
        }
    }

    /**
     * Cytomic Zero-Delay-Feedback State Variable Filter por muestra para barridos sin saltos de ganancia ni cremalleras.
     */
    fun svfSweepStereo(
        stereo: FloatArray,
        sampleRate: Int,
        cutoffHz: FloatArray,
        highpass: Boolean,
        resonance: Float = 0.78f,
        steep24Db: Boolean = true,
    ): FloatArray {
        val frames = min(stereo.size / 2, cutoffHz.size)
        if (frames <= 0) return stereo.copyOf()
        val out = FloatArray(frames * 2)
        val k = 1f / resonance.coerceIn(0.45f, 3.5f)
        val invSr = 1.0f / sampleRate

        var s1ic1L = 0f; var s1ic2L = 0f; var s2ic1L = 0f; var s2ic2L = 0f
        var s1ic1R = 0f; var s1ic2R = 0f; var s2ic1R = 0f; var s2ic2R = 0f

        for (i in 0 until frames) {
            val fc = cutoffHz[i].coerceIn(18f, (sampleRate * 0.44f))
            val g = tan((PI * fc * invSr)).toFloat()
            val a1 = 1f / (1f + g * (g + k))
            val a2 = g * a1
            val a3 = g * a2

            // Canal L
            val v0L = stereo[i * 2]
            val v3L = v0L - s1ic2L
            val v1L = a1 * s1ic1L + a2 * v3L
            val v2L = s1ic2L + a2 * s1ic1L + a3 * v3L
            s1ic1L = 2f * v1L - s1ic1L
            s1ic2L = 2f * v2L - s1ic2L
            var yL = if (highpass) v0L - k * v1L - v2L else v2L

            // Canal R
            val v0R = stereo[i * 2 + 1]
            val v3R = v0R - s1ic2R
            val v1R = a1 * s1ic1R + a2 * v3R
            val v2R = s1ic2R + a2 * s1ic1R + a3 * v3R
            s1ic1R = 2f * v1R - s1ic1R
            s1ic2R = 2f * v2R - s1ic2R
            var yR = if (highpass) v0R - k * v1R - v2R else v2R

            if (steep24Db) {
                val p3L = yL - s2ic2L
                val p1L = a1 * s2ic1L + a2 * p3L
                val p2L = s2ic2L + a2 * s2ic1L + a3 * p3L
                s2ic1L = 2f * p1L - s2ic1L
                s2ic2L = 2f * p2L - s2ic2L
                yL = if (highpass) yL - k * p1L - p2L else p2L

                val p3R = yR - s2ic2R
                val p1R = a1 * s2ic1R + a2 * p3R
                val p2R = s2ic2R + a2 * s2ic1R + a3 * p3R
                s2ic1R = 2f * p1R - s2ic1R
                s2ic2R = 2f * p2R - s2ic2R
                yR = if (highpass) yR - k * p1R - p2R else p2R
            }
            out[i * 2] = yL
            out[i * 2 + 1] = yR
        }
        return out
    }

    /**
     * Delay ping-pong estéreo sincronizado al tempo de la pista saliente con realimentación amortiguada (LP+HP en el lazo).
     */
    fun pingPongDelayStereo(
        stereo: FloatArray,
        sampleRate: Int,
        delayMs: Double,
        feedbackEnv: FloatArray,
        sendEnv: FloatArray,
        wetEnv: FloatArray,
    ): FloatArray {
        val frames = minOf(stereo.size / 2, feedbackEnv.size, sendEnv.size, wetEnv.size)
        if (frames <= 0) return FloatArray(0)
        val d = (delayMs * sampleRate / 1000.0).roundToInt().coerceIn(240, max(240, min(sampleRate * 2, frames)))
        val bufL = FloatArray(d)
        val bufR = FloatArray(d)
        val out = FloatArray(frames * 2)

        val alphaLp = (1.0 - exp(-2.0 * PI * 3600.0 / sampleRate)).toFloat()
        val alphaHp = (1.0 - exp(-2.0 * PI * 300.0 / sampleRate)).toFloat()
        var lpL = 0f; var lpR = 0f
        var hpL = 0f; var hpR = 0f
        var pos = 0

        for (i in 0 until frames) {
            val tapL = bufL[pos]
            val tapR = bufR[pos]

            lpL += alphaLp * (tapL - lpL)
            lpR += alphaLp * (tapR - lpR)
            hpL += alphaHp * (lpL - hpL)
            hpR += alphaHp * (lpR - hpR)
            val cleanL = lpL - hpL
            val cleanR = lpR - hpR

            val xL = stereo[i * 2]
            val xR = stereo[i * 2 + 1]
            val send = sendEnv[i]
            val fb = feedbackEnv[i].coerceIn(0f, 0.92f)
            val monoIn = 0.5f * (xL + xR) * send

            // Cruce ping-pong L<->R manteniendo anchura en cola de eco.
            bufL[pos] = monoIn * 0.92f + (0.70f * cleanR + 0.30f * cleanL) * fb
            bufR[pos] = monoIn * 0.65f + (0.70f * cleanL + 0.30f * cleanR) * fb

            val wet = wetEnv[i]
            out[i * 2] = cleanL * wet
            out[i * 2 + 1] = cleanR * wet

            pos++
            if (pos == d) pos = 0
        }
        return out
    }

    /**
     * Reverberación estéreo tipo Freeverb/Schroeder (4 peines amortiguados paralelos + 2 pasa-todos por canal).
     * Ideal para crear colas atmosféricas en `filter_echo` y suavizar cortes rápidos.
     */
    fun stereoReverb(
        stereo: FloatArray,
        sampleRate: Int,
        sendEnv: FloatArray,
        wetEnv: FloatArray,
        roomSize: Float = 0.72f,
        damping: Float = 0.35f,
    ): FloatArray {
        val frames = minOf(stereo.size / 2, sendEnv.size, wetEnv.size)
        if (frames <= 0) return FloatArray(0)
        val scale = sampleRate / 44_100.0
        val combBase = intArrayOf(1116, 1188, 1277, 1356)
        val apBase = intArrayOf(556, 441)
        val stereoSpread = 23

        val fb = (0.70f + 0.24f * roomSize.coerceIn(0f, 1f)).coerceAtMost(0.95f)
        val damp1 = damping.coerceIn(0.05f, 0.85f)
        val damp2 = 1f - damp1

        class Comb(len: Int) {
            private val buf = FloatArray(max(64, len))
            private var idx = 0
            private var filterStore = 0f

            fun process(input: Float): Float {
                val y = buf[idx]
                filterStore = y * damp2 + filterStore * damp1
                buf[idx] = input + filterStore * fb
                idx++
                if (idx == buf.size) idx = 0
                return y
            }
        }

        class Allpass(len: Int) {
            private val buf = FloatArray(max(32, len))
            private var idx = 0

            fun process(input: Float): Float {
                val bufOut = buf[idx]
                val y = -input + bufOut
                buf[idx] = input + bufOut * 0.5f
                idx++
                if (idx == buf.size) idx = 0
                return y
            }
        }

        val combsL = combBase.map { Comb((it * scale).roundToInt()) }
        val combsR = combBase.map { Comb(((it + stereoSpread) * scale).roundToInt()) }
        val apsL = apBase.map { Allpass((it * scale).roundToInt()) }
        val apsR = apBase.map { Allpass(((it + stereoSpread) * scale).roundToInt()) }

        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val send = sendEnv[i]
            val wet = wetEnv[i] * 0.055f // ganancia calibrada de suma de 4 peines
            val inMono = 0.5f * (stereo[i * 2] + stereo[i * 2 + 1]) * send

            var sumL = 0f
            var sumR = 0f
            for (c in 0 until combsL.size) {
                sumL += combsL[c].process(inMono)
                sumR += combsR[c].process(inMono)
            }
            var apL = sumL
            var apR = sumR
            for (a in 0 until apsL.size) {
                apL = apsL[a].process(apL)
                apR = apsR[a].process(apR)
            }
            out[i * 2] = apL * wet
            out[i * 2 + 1] = apR * wet
        }
        return out
    }

    /**
     * Estiramiento temporal estéreo sin cambio de tono (WSOLA con ventana Hann de 42 ms y solapamiento del 75 %).
     * [speedCurve] define el ritmo instantáneo (>1 acelera la fuente -> salida más corta; <1 ralentiza -> salida más larga).
     */
    fun timeStretchStereo(
        stereo: FloatArray,
        sampleRate: Int,
        speedCurve: FloatArray,
    ): FloatArray {
        val srcFrames = stereo.size / 2
        if (srcFrames == 0 || speedCurve.isEmpty()) return FloatArray(0)

        var maxDev = 0f
        for (r in speedCurve) maxDev = max(maxDev, abs(r - 1f))
        if (maxDev < 0.004f) return stereo.copyOf()

        val mono = FloatArray(srcFrames) { f -> 0.5f * (stereo[f * 2] + stereo[f * 2 + 1]) }
        val win = ((0.042 * sampleRate).roundToInt() and 0x7FFFFFFE).coerceIn(512, 2400)
        val hopOut = win / 4 // 75 % de solapamiento COLA
        val tol = (0.009 * sampleRate).roundToInt().coerceIn(96, win / 2)

        val window = hannWindow(win)

        // Determinar longitud de salida integrando 1/speed sobre las muestras fuente.
        var outEst = 0.0
        for (i in 0 until srcFrames) {
            val r = speedCurve[min(i, speedCurve.lastIndex)].coerceIn(0.55f, 1.85f)
            outEst += 1.0 / r
        }
        val outFrames = max(win + hopOut, outEst.roundToInt())
        val out = FloatArray(outFrames * 2)
        val norm = FloatArray(outFrames)

        var srcPos = 0.0
        var prevSrcEnd = 0
        var outPos = 0
        val overlap = win - hopOut

        while (outPos < outFrames && srcPos < srcFrames) {
            val expected = srcPos.roundToInt().coerceIn(0, max(0, srcFrames - 1))
            var bestStart = expected
            if (outPos > 0 && prevSrcEnd in 0 until srcFrames) {
                var bestCorr = Double.NEGATIVE_INFINITY
                val minOff = max(-tol, -expected)
                val maxOff = min(tol, max(0, srcFrames - overlap - expected))
                var coarseBest = 0
                var k = minOff
                while (k <= maxOff) {
                    val cand = expected + k
                    var num = 0.0
                    var eA = 0.0
                    var eB = 0.0
                    var n = 0
                    val limit = minOf(overlap, srcFrames - cand, srcFrames - prevSrcEnd)
                    while (n < limit) {
                        val a = mono[prevSrcEnd + n].toDouble()
                        val b = mono[cand + n].toDouble()
                        num += a * b
                        eA += a * a
                        eB += b * b
                        n += 4
                    }
                    val den = sqrt(eA * eB) + 1e-8
                    val score = num / den
                    if (score > bestCorr) {
                        bestCorr = score
                        coarseBest = k
                    }
                    k += 4
                }
                for (f in max(minOff, coarseBest - 3)..min(maxOff, coarseBest + 3)) {
                    val cand = expected + f
                    var num = 0.0
                    var eA = 0.0
                    var eB = 0.0
                    val limit = minOf(overlap, srcFrames - cand, srcFrames - prevSrcEnd)
                    for (n in 0 until limit step 2) {
                        val a = mono[prevSrcEnd + n].toDouble()
                        val b = mono[cand + n].toDouble()
                        num += a * b
                        eA += a * a
                        eB += b * b
                    }
                    val score = num / (sqrt(eA * eB) + 1e-8)
                    if (score > bestCorr) {
                        bestCorr = score
                        bestStart = cand
                    }
                }
            }

            val availOut = outFrames - outPos
            val availIn = srcFrames - bestStart
            val block = minOf(win, availOut, availIn)
            for (n in 0 until block) {
                val w = window[n]
                val sIdx = (bestStart + n) * 2
                val oIdx = (outPos + n) * 2
                out[oIdx] += stereo[sIdx] * w
                out[oIdx + 1] += stereo[sIdx + 1] * w
                norm[outPos + n] += w
            }

            prevSrcEnd = (bestStart + hopOut).coerceIn(0, max(0, srcFrames - 1))
            // La posición ideal avanza sola, a la velocidad pedida en ese punto; el trozo elegido solo se desvía de
            // ella (±tol) para empalmar bien. Antes se arrastraba al elegido si iba por delante y nunca volvía:
            // cada canción acumulaba su propio adelanto y dos estiradas «al mismo tempo» se desfasaban (~40 ms).
            val curSrcIdx = srcPos.roundToInt().coerceIn(0, speedCurve.lastIndex)
            val rate = speedCurve[curSrcIdx].coerceIn(0.55f, 1.85f)
            srcPos += hopOut * rate
            outPos += hopOut
        }

        // Normalización por suma de ventanas Hann para ganancia unitaria exacta (0 dB).
        for (i in 0 until outFrames) {
            val d = norm[i]
            if (d > 1e-5f) {
                val inv = 1f / d
                out[i * 2] *= inv
                out[i * 2 + 1] *= inv
            }
        }
        return out
    }

    /**
     * Limitador anticipado de pico verdadero (~5 ms de lookahead, techo `-0.8 dBTP`).
     */
    fun truePeakLimiterStereo(
        stereo: FloatArray,
        sampleRate: Int,
        ceilingDb: Double = -0.8,
    ): FloatArray {
        val frames = stereo.size / 2
        if (frames == 0) return stereo.copyOf()
        val ceiling = dbToLinF(ceilingDb)
        val lookahead = max(1, (0.005 * sampleRate).roundToInt())
        val releaseCoeff = exp(-1.0 / (0.080 * sampleRate)).toFloat()
        val attackCoeff = exp(-1.0 / (0.0012 * sampleRate)).toFloat()

        // Ventana deslizante máxima de lookahead usando cola monótona O(N).
        val framePeak = FloatArray(frames) { i ->
            max(abs(stereo[i * 2]), abs(stereo[i * 2 + 1]))
        }
        val futurePeak = FloatArray(frames)
        val dq = IntArray(frames)
        var head = 0
        var tail = 0
        for (i in 0 until frames) {
            val winEnd = min(frames - 1, i + lookahead)
            if (i == 0) {
                for (k in 0..winEnd) {
                    while (tail > head && framePeak[dq[tail - 1]] <= framePeak[k]) tail--
                    dq[tail++] = k
                }
            } else if (i + lookahead < frames) {
                val k = i + lookahead
                while (tail > head && framePeak[dq[tail - 1]] <= framePeak[k]) tail--
                dq[tail++] = k
            }
            while (head < tail && dq[head] < i) head++
            futurePeak[i] = framePeak[dq[head]]
        }

        val out = FloatArray(frames * 2)
        var gain = 1.0f
        for (i in 0 until frames) {
            val pk = futurePeak[i]
            val targetGain = if (pk > ceiling) (ceiling / pk) else 1.0f
            gain = if (targetGain < gain) {
                attackCoeff * gain + (1f - attackCoeff) * targetGain
            } else {
                releaseCoeff * gain + (1f - releaseCoeff) * targetGain
            }
            val l = stereo[i * 2] * gain
            val r = stereo[i * 2 + 1] * gain
            out[i * 2] = softClip(l).coerceIn(-0.995f, 0.995f)
            out[i * 2 + 1] = softClip(r).coerceIn(-0.995f, 0.995f)
        }
        return out
    }

    /**
     * Alineación fina de fase de bombos entre dos ventanas estéreo centradas en `anchorOut` y `anchorIn`
     * mediante correlación cruzada en banda `[45..160 Hz]`. Devuelve desplazamiento en ms dentro de `±maxShiftMs`.
     */
    fun kickCorrelationShiftMs(
        monoOut: FloatArray,
        monoIn: FloatArray,
        sampleRate: Int,
        anchorOutFrame: Int,
        anchorInFrame: Int,
        windowSec: Double = 2.5,
        maxShiftMs: Double = 90.0,
    ): Double {
        val halfWin = (0.5 * windowSec * sampleRate).roundToInt().coerceIn(sampleRate / 2, sampleRate * 4)
        val outStart = (anchorOutFrame - halfWin).coerceIn(0, max(0, monoOut.size - 1))
        val outEnd = (anchorOutFrame + halfWin).coerceIn(outStart, monoOut.size)
        val inStart = (anchorInFrame - halfWin).coerceIn(0, max(0, monoIn.size - 1))
        val inEnd = (anchorInFrame + halfWin).coerceIn(inStart, monoIn.size)
        if (outEnd - outStart < sampleRate / 2 || inEnd - inStart < sampleRate / 2) return 0.0

        val step = max(1, sampleRate / 500) // ~2 ms de paso de correlación sobre envolvente rectificada
        val filtOut = bandpassOrder4(monoOut.copyOfRange(outStart, outEnd), 45.0, 160.0, sampleRate)
        val filtIn = bandpassOrder4(monoIn.copyOfRange(inStart, inEnd), 45.0, 160.0, sampleRate)

        val envOut = FloatArray(filtOut.size / step) { i ->
            var s = 0f
            val base = i * step
            for (k in 0 until step) s += abs(filtOut[base + k])
            s / step
        }
        val envIn = FloatArray(filtIn.size / step) { i ->
            var s = 0f
            val base = i * step
            for (k in 0 until step) s += abs(filtIn[base + k])
            s / step
        }
        val len = min(envOut.size, envIn.size)
        if (len < 50) return 0.0
        val maxShiftBins = (maxShiftMs / (step * 1000.0 / sampleRate)).roundToInt().coerceIn(1, len / 4)

        var bestLag = 0
        var bestCorr = Double.NEGATIVE_INFINITY
        for (lag in -maxShiftBins..maxShiftBins) {
            var num = 0.0
            var eA = 0.0
            var eB = 0.0
            val lo = max(0, -lag)
            val hi = min(len, len - lag)
            for (i in lo until hi) {
                val a = envOut[i].toDouble()
                val b = envIn[i + lag].toDouble()
                num += a * b
                eA += a * a
                eB += b * b
            }
            val c = num / (sqrt(eA * eB) + 1e-8) - 0.0005 * abs(lag)
            if (c > bestCorr) {
                bestCorr = c
                bestLag = lag
            }
        }
        return bestLag * (step * 1000.0 / sampleRate)
    }

    /**
     * Cuánto hay que mover la entrada de B (ms de su propia pista; positivo = más tarde) para que sus bombos caigan
     * encima de los de A al arrancar una mezcla a tempo. Compara [windowSec] de A desde [outStartFrame] con B leída a
     * [ratio]× desde [inStartFrame] (B ya al tempo de A), así una diferencia de tempo no emborrona la comparación.
     * Devuelve el desfase y la correlación (0..1); sin bombos claros, 0 ms.
     */
    fun kickPhaseShiftMs(
        monoOut: FloatArray,
        monoIn: FloatArray,
        sampleRate: Int,
        outStartFrame: Int,
        inStartFrame: Int,
        ratio: Double,
        maxShiftMs: Double,
        windowSec: Double = 6.0,
        minShiftMs: Double = -maxShiftMs,
    ): Pair<Double, Double> {
        val step = max(1, sampleRate / 1000) // ~1 ms por paso
        val r = ratio.coerceIn(0.75, 1.35)
        val minShiftBins = ((minShiftMs / 1000.0) * sampleRate / step).roundToInt()
        val maxShiftBins = ((maxShiftMs / 1000.0) * sampleRate / step).roundToInt().coerceAtLeast(minShiftBins)
        val winBins = ((windowSec * sampleRate) / step).roundToInt().coerceAtLeast(50)
        val margin = (0.25 * sampleRate).roundToInt()

        val outFrom = (outStartFrame - margin).coerceAtLeast(0)
        val outTo = min(monoOut.size, outStartFrame + winBins * step + margin)
        val needIn = (winBins * r).roundToInt() + max(0, maxShiftBins) + 2
        val inFrom = (inStartFrame + minShiftBins * step - margin).coerceAtLeast(0)
        val inTo = min(monoIn.size, inStartFrame + needIn * step + margin)
        if (outTo - outFrom < sampleRate || inTo - inFrom < sampleRate) return 0.0 to 0.0

        fun kickOnsetEnvelope(src: FloatArray, from: Int, to: Int): FloatArray {
            val filt = bandpassOrder4(src.copyOfRange(from, to), 35.0, 150.0, sampleRate)
            val bins = filt.size / step
            if (bins <= 1) return FloatArray(bins)
            // RMS deslizante de ~16 ms: aplana la oscilación de la onda subgrave (<150 Hz) conservando el ataque al milisegundo.
            val prefixSq = DoubleArray(filt.size + 1)
            for (nIdx in filt.indices) {
                val v = filt[nIdx].toDouble()
                prefixSq[nIdx + 1] = prefixSq[nIdx] + v * v
            }
            val halfWin = max(step, ((0.008 * sampleRate)).roundToInt())
            val logRms = FloatArray(bins)
            for (i in 0 until bins) {
                val center = i * step + step / 2
                val wFrom = (center - halfWin).coerceAtLeast(0)
                val wTo = (center + halfWin).coerceAtMost(filt.size)
                val meanSq = (prefixSq[wTo] - prefixSq[wFrom]) / max(1, wTo - wFrom)
                logRms[i] = kotlin.math.ln(1e-4 + sqrt(meanSq)).toFloat()
            }
            val rawOnset = FloatArray(bins)
            for (i in 1 until bins) {
                rawOnset[i] = max(0f, logRms[i] - logRms[i - 1])
            }
            // Suavizado ligero (~7 ms simétrico) para absorber micro-variaciones del WSOLA manteniendo el ataque exacto del bombo.
            val smooth = FloatArray(bins)
            val weights = floatArrayOf(1f / 16f, 2f / 16f, 3f / 16f, 4f / 16f, 3f / 16f, 2f / 16f, 1f / 16f)
            for (i in 0 until bins) {
                var acc = 0f
                for (k in -3..3) {
                    acc += rawOnset[(i + k).coerceIn(0, bins - 1)] * weights[k + 3]
                }
                smooth[i] = acc
            }
            return smooth
        }
        val envOut = kickOnsetEnvelope(monoOut, outFrom, outTo)
        val envIn = kickOnsetEnvelope(monoIn, inFrom, inTo)
        val outBase = (outStartFrame - outFrom) / step
        val inBase = (inStartFrame - inFrom) / step
        val n = min(winBins, envOut.size - outBase)
        if (n < 50) return 0.0 to 0.0

        fun evalLag(lag: Int, sampleStep: Int): Double {
            var sumA = 0.0
            var sumB = 0.0
            var cnt = 0
            var i = 0
            while (i < n) {
                val j = inBase + (i * r).roundToInt() + lag
                if (j in 0 until envIn.size) {
                    sumA += envOut[outBase + i]
                    sumB += envIn[j]
                    cnt++
                }
                i += sampleStep
            }
            if (cnt < max(25, n / (2 * sampleStep))) return Double.NEGATIVE_INFINITY
            val meanA = sumA / cnt
            val meanB = sumB / cnt
            var num = 0.0
            var eA = 0.0
            var eB = 0.0
            i = 0
            while (i < n) {
                val j = inBase + (i * r).roundToInt() + lag
                if (j in 0 until envIn.size) {
                    val a = envOut[outBase + i] - meanA
                    val b = envIn[j] - meanB
                    num += a * b
                    eA += a * a
                    eB += b * b
                }
                i += sampleStep
            }
            return num / (sqrt(eA * eB) + 1e-9)
        }

        val maxAbsSpan = max(1, max(abs(minShiftBins), abs(maxShiftBins)))
        var coarseLag = 0
        var bestCoarseWeighted = Double.NEGATIVE_INFINITY
        var lag = minShiftBins
        while (lag <= maxShiftBins) {
            val rawC = evalLag(lag, sampleStep = 2)
            if (rawC.isFinite()) {
                val prior = if (maxAbsSpan > 45) 1.0 - 0.04 * (abs(lag).toDouble() / maxAbsSpan) else 1.0
                val weighted = rawC * prior
                if (weighted > bestCoarseWeighted) {
                    bestCoarseWeighted = weighted
                    coarseLag = lag
                }
            }
            lag += 2
        }

        var bestLag = coarseLag
        var bestWeighted = Double.NEGATIVE_INFINITY
        var bestRawCorr = 0.0
        for (fineLag in max(minShiftBins, coarseLag - 24)..min(maxShiftBins, coarseLag + 24)) {
            val rawC = evalLag(fineLag, sampleStep = 1)
            if (!rawC.isFinite()) continue
            val prior = if (maxAbsSpan > 45) 1.0 - 0.04 * (abs(fineLag).toDouble() / maxAbsSpan) else 1.0
            val weighted = rawC * prior
            if (weighted > bestWeighted) {
                bestWeighted = weighted
                bestLag = fineLag
                bestRawCorr = rawC
            }
        }
        if (bestRawCorr < 0.06) return 0.0 to bestRawCorr.coerceAtLeast(0.0)
        return bestLag * (step * 1000.0 / sampleRate) to bestRawCorr
    }

    /** Extrae un segmento estéreo desde [stereo] en `[startSample .. startSample + lengthSamples]` con fade corto opcional de borde. */
    fun sliceStereo(stereo: FloatArray, startFrame: Int, lengthFrames: Int): FloatArray {
        val totalFrames = stereo.size / 2
        val safeLen = max(0, lengthFrames)
        val out = FloatArray(safeLen * 2)
        if (safeLen == 0 || totalFrames == 0) return out
        val srcFrom = max(0, startFrame)
        val dstOffset = max(0, -startFrame)
        val copyFrames = min(safeLen - dstOffset, totalFrames - srcFrom)
        if (copyFrames > 0) {
            System.arraycopy(stereo, srcFrom * 2, out, dstOffset * 2, copyFrames * 2)
        }
        return out
    }

    /** Convierte mono float a estéreo duplicado (útil en tests y fallback). */
    fun monoToStereo(mono: FloatArray): FloatArray {
        val out = FloatArray(mono.size * 2)
        for (i in mono.indices) {
            out[i * 2] = mono[i]
            out[i * 2 + 1] = mono[i]
        }
        return out
    }

    /** Convierte estéreo interleaved a mono promedio. */
    fun stereoToMono(stereo: FloatArray): FloatArray {
        val n = stereo.size / 2
        return FloatArray(n) { i -> 0.5f * (stereo[i * 2] + stereo[i * 2 + 1]) }
    }

    /** Convierte PCM16 interleaved little-endian en ByteArray a float normalizado `[-1, 1]`. */
    fun pcm16BytesToStereoFloat(bytes: ByteArray, byteCount: Int = bytes.size): FloatArray {
        val samples = byteCount / 2
        val out = FloatArray(samples)
        var p = 0
        for (i in 0 until samples) {
            val lo = bytes[p].toInt() and 0xFF
            val hi = bytes[p + 1].toInt()
            val s = (hi shl 8) or lo
            out[i] = (s / 32768f).coerceIn(-1f, 1f)
            p += 2
        }
        return out
    }

    /** Remuestrea estéreo interleaved mediante interpolación lineal con filtro pasa-bajos anti-aliasing simple si reduce SR. */
    fun resampleStereo(stereo: FloatArray, fromSr: Int, toSr: Int): FloatArray {
        if (fromSr <= 0 || toSr <= 0 || fromSr == toSr || stereo.isEmpty()) return stereo
        val inFrames = stereo.size / 2
        val outFrames = ((inFrames.toDouble() * toSr) / fromSr).roundToInt().coerceAtLeast(1)
        var src = stereo
        if (toSr < fromSr) {
            val cutoff = toSr * 0.44
            val lpL = Biquad.lowpass(cutoff, fromSr)
            val lpR = Biquad.lowpass(cutoff, fromSr)
            src = FloatArray(stereo.size)
            for (i in 0 until inFrames) {
                src[i * 2] = lpL.processSample(stereo[i * 2].toDouble()).toFloat()
                src[i * 2 + 1] = lpR.processSample(stereo[i * 2 + 1].toDouble()).toFloat()
            }
        }
        val out = FloatArray(outFrames * 2)
        val step = (inFrames - 1).toDouble() / max(1, outFrames - 1)
        for (i in 0 until outFrames) {
            val pos = i * step
            val idx0 = pos.toInt().coerceIn(0, inFrames - 1)
            val idx1 = min(inFrames - 1, idx0 + 1)
            val frac = (pos - idx0).toFloat()
            val l0 = src[idx0 * 2]
            val l1 = src[idx1 * 2]
            val r0 = src[idx0 * 2 + 1]
            val r1 = src[idx1 * 2 + 1]
            out[i * 2] = l0 + (l1 - l0) * frac
            out[i * 2 + 1] = r0 + (r1 - r0) * frac
        }
        return out
    }

    const val SYNC_TAP_SAMPLE_RATE = 12_000

    /**
     * Diezma un bloque estéreo [stereo] (a [sourceSr], por defecto 48 kHz) a mono de 12 kHz mediante promedio anti-alias
     * por cubo para correlación rápida de fase/desfase temporal (`SYNC_TAP_SAMPLE_RATE`).
     */
    fun stereoToMono12k(
        stereo: FloatArray,
        startFrame: Int = 0,
        frameCount: Int = stereo.size / 2 - startFrame,
        sourceSr: Int = DJ_SAMPLE_RATE,
    ): FloatArray {
        val totalFrames = stereo.size / 2
        val safeStart = startFrame.coerceIn(0, totalFrames)
        val avail = min(frameCount.coerceAtLeast(0), totalFrames - safeStart)
        if (avail <= 0 || sourceSr <= 0) return FloatArray(0)
        val outLen = ((avail.toDouble() * SYNC_TAP_SAMPLE_RATE) / sourceSr).toInt().coerceAtLeast(1)
        val ratio = sourceSr.toDouble() / SYNC_TAP_SAMPLE_RATE.toDouble()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val f0 = safeStart + (i * ratio).toInt().coerceIn(0, avail - 1)
            val f1 = (safeStart + ((i + 1) * ratio).toInt()).coerceIn(f0 + 1, safeStart + avail)
            var sum = 0f
            for (f in f0 until f1) {
                sum += 0.5f * (stereo[f * 2] + stereo[f * 2 + 1])
            }
            out[i] = sum / max(1, f1 - f0)
        }
        return out
    }

    /**
     * Mide el desfase exacto en milisegundos entre audio capturado de [liveMono12k] (anclado en [liveOriginMs])
     * y el audio de referencia [refMono12k] (anclado en [refOriginMs]) usando el tramo `[anchorMs .. anchorMs + windowMs)`.
     *
     * Devuelve `(shiftMs, correlation)` tal que `ref(anchor + shiftMs)` coincide con `live(anchor)`:
     * un valor positivo significa que la referencia va adelantada respecto al directo para el mismo instante nominal.
     */
    fun correlateShiftMs(
        liveMono12k: FloatArray,
        liveOriginMs: Long,
        refMono12k: FloatArray,
        refOriginMs: Long,
        anchorMs: Long,
        windowMs: Long = 380L,
        maxShiftMs: Int = 180,
    ): Pair<Double, Double>? {
        val sr = SYNC_TAP_SAMPLE_RATE
        val winN = ((windowMs * sr) / 1000L).toInt()
        if (winN < sr / 6) return null
        val i0 = (((anchorMs - liveOriginMs) * sr) / 1000.0).roundToInt()
        val j0 = (((anchorMs - refOriginMs) * sr) / 1000.0).roundToInt()
        if (i0 < 0 || i0 + winN > liveMono12k.size) return null

        var liveEnergy = 0.0
        for (k in 0 until winN) {
            val v = liveMono12k[i0 + k].toDouble()
            liveEnergy += v * v
        }
        if (liveEnergy < winN * 1e-6) return null

        val maxS = ((maxShiftMs * sr) / 1000.0).roundToInt()
        val sMin = max(-maxS, -j0)
        val sMax = min(maxS, refMono12k.size - (j0 + winN))
        if (sMax < sMin) return null

        val corrs = DoubleArray(sMax - sMin + 1)
        var bestIdx = -1
        var bestCorr = -1.0

        for (s in sMin..sMax) {
            val idx = s - sMin
            var num = 0.0
            var refEnergy = 0.0
            val baseRef = j0 + s
            for (k in 0 until winN) {
                val a = liveMono12k[i0 + k].toDouble()
                val b = refMono12k[baseRef + k].toDouble()
                num += a * b
                refEnergy += b * b
            }
            val c = if (refEnergy > 1e-9) num / sqrt(liveEnergy * refEnergy) else 0.0
            corrs[idx] = c
            if (c > bestCorr) {
                bestCorr = c
                bestIdx = idx
            }
        }
        if (bestIdx < 0) return null

        // Refinamiento parabólico sub-muestra sobre los 3 puntos alrededor del pico.
        var peakS = (sMin + bestIdx).toDouble()
        if (bestIdx in 1 until corrs.lastIndex) {
            val y0 = corrs[bestIdx - 1]
            val y1 = corrs[bestIdx]
            val y2 = corrs[bestIdx + 1]
            val denom = 2.0 * (y0 - 2.0 * y1 + y2)
            if (abs(denom) > 1e-12) {
                val delta = ((y0 - y2) / denom).coerceIn(-0.5, 0.5)
                peakS += delta
            }
        }
        return (peakS * 1000.0 / sr) to bestCorr
    }
}
