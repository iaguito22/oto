package io.github.iaguito22.oto.desktop.wallpaper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Espectro en vivo para el fondo: 96 bandas de 30 Hz a 14 kHz, escala de −90 a −22 dB, ganancia automática, subida
 * rápida y bajada lenta, picos que caen, historial de 16 cuadros y «golpe» de bajo.
 *
 * - Medios y agudos: FFT de 2048 con ventana de Blackman (como el AnalyserNode del laboratorio).
 * - Graves (bandas por debajo de [LONG_HZ]): FFT de 8192 con ventana de Hann, 5,9 Hz por bin. Con la de 2048 (23 Hz
 *   por bin) una nota de bajo encendía todas las barras de graves y no se distinguían las notas.
 * - Reparto de las barras: logarítmico en medios y agudos, menos denso en graves (ln(1 + f/[K])), para que una nota de
 *   bajo no ocupe media docena de barras.
 * - Inclinación de [TILT_DB] dB por octava: la música pierde energía hacia los agudos y, sin compensarla, los platos y
 *   el brillo casi no movían las barras.
 *
 * [samples] lleva [NL] muestras; la ventana corta son las [N] que acaban [AHEAD] antes del final, así las dos ventanas
 * se centran en el mismo instante. Quien lee el audio lo pide [AHEAD] muestras adelantado.
 *
 * Como en Oto no hay análisis previo de la canción, los instrumentos (bombo, caja, platos, bajo, voz) y la energía se
 * calculan aquí en vivo, a partir de las propias bandas.
 */
internal class Spectrum(private val sampleRate: Int = 48_000) {
    companion object {
        const val N = 2048
        const val NL = 8192
        const val AHEAD = (NL - N) / 2
        /** Donde acaba la ventana corta dentro de [samples]: lo que «suena ahora». */
        const val SHORT_END = NL - AHEAD
        const val NBAND = 96
        const val HIST = 16
        private const val F0 = 30.0
        private const val F1 = 14_000.0
        private const val K = 80.0
        private const val LONG_HZ = 320.0
        private const val TILT_DB = 5.5
        private fun scale(hz: Double) = ln(1 + hz / K)
        private val S0 = scale(F0); private val S1 = scale(F1)
        /** Frecuencia del borde [x] de las bandas (0 = 30 Hz, [NBAND] = 14 kHz). */
        fun bandHz(x: Double) = K * (kotlin.math.exp(S0 + x / NBAND * (S1 - S0)) - 1)
        fun bandOf(hz: Double) = (NBAND * (scale(hz) - S0) / (S1 - S0)).roundToInt().coerceIn(0, NBAND)
        val BASS_BANDS = bandOf(140.0)
    }

    val samples = FloatArray(NL)
    private val re = FloatArray(NL)
    private val im = FloatArray(NL)
    private val window = FloatArray(N) { i -> val a = 2 * PI * i / (N - 1); (.42 - .5 * cos(a) + .08 * cos(2 * a)).toFloat() }
    private val windowLong = FloatArray(NL) { i -> (.5 - .5 * cos(2 * PI * i / (NL - 1))).toFloat() }
    private val smoothMag = FloatArray(N / 2)
    private val bytes = FloatArray(N / 2)               // 0…1, como getByteFrequencyData / 255
    private val longBins = (LONG_HZ * 1.25 * NL / sampleRate).toInt() + 4
    private val smoothMagLong = FloatArray(longBins)
    private val bytesLong = FloatArray(longBins)
    private var longAcc = 1f
    /** dB que se suman a cada bin para compensar que la música pierde energía hacia los agudos (0 dB en 1 kHz). */
    private fun tilt(hz: Double) = (TILT_DB * ln(max(hz, 20.0) / 1000.0) / ln(2.0)).toFloat()
    private val tiltShort = FloatArray(N / 2) { tilt(it * sampleRate.toDouble() / N) }
    private val tiltLong = FloatArray(longBins) { tilt(it * sampleRate.toDouble() / NL) }

    /** Cada banda: si sale de la ventana larga, y o bien los bins que cubre o, si es más estrecha que dos bins, su centro. */
    private class Band(val long: Boolean, val from: Int, val until: Int, val center: Float)
    private val bands = Array(NBAND) { i ->
        val f0 = bandHz(i.toDouble()); val f1 = bandHz(i + 1.0)
        val long = f1 <= LONG_HZ
        val hz = sampleRate.toDouble() / (if (long) NL else N)
        val b0 = (f0 / hz).toInt()
        Band(long, b0, max(b0 + 1, kotlin.math.ceil(f1 / hz).toInt()), (((f0 + f1) / 2) / hz).toFloat())
    }
    private fun bandValue(b: Band): Float {
        val src = if (b.long) bytesLong else bytes
        if (b.until - b.from <= 2) {   // más estrecha que dos bins: el valor en su centro, sin repetir el del bin entero
            val k = b.center.toInt().coerceIn(0, src.size - 2); val f = (b.center - k).coerceIn(0f, 1f)
            return src[k] * (1 - f) + src[k + 1] * f
        }
        var m = 0f
        for (j in b.from until min(b.until, src.size)) m = max(m, src[j])
        return m
    }

    val band = FloatArray(NBAND)       // crudo normalizado (sube y baja al instante)
    val cur = FloatArray(NBAND)        // con subida rápida y bajada lenta
    val spec = FloatArray(NBAND)       // cur suavizado entre bandas vecinas
    val peak = FloatArray(NBAND)
    private val peakV = FloatArray(NBAND)
    private val hist = Array(HIST) { FloatArray(NBAND) }
    private var histHead = 0
    private var histAcc = 0f
    private var agc = .3f
    var liveBass = 0f; private set
    private var bassAvg = .2f
    var punch = 0f; private set

    // instrumentos en vivo (0…1)
    var kick = 0f; private set
    var snare = 0f; private set
    var hat = 0f; private set
    var bass = 0f; private set
    var voice = 0f; private set
    var energy = .3f; private set
    var build = 0f; private set
    var drop = 0f; private set
    var slow = 0f; private set
    private val voiceRange = bandOf(250.0) until bandOf(3500.0)
    private val longBands = bands.count { it.long }

    // golpes: energía de cada rango (dB) en los últimos cuadros, para ver cuánto ha subido en ~30 ms
    private val pw = FloatArray(N / 2)                  // potencia de cada bin en este cuadro, sin suavizar
    private fun binsOf(f0: Double, f1: Double) = max(1, (f0 * N / sampleRate).toInt()) until min(N / 2, (f1 * N / sampleRate).toInt() + 1)
    private val kBins = binsOf(35.0, 120.0)             // bombo
    private val lBins = binsOf(30.0, 160.0)             // graves (bajo + bombo), para el drop
    private val bBins = binsOf(150.0, 400.0)            // cuerpo de la caja (y de las voces graves)
    private val mBins = binsOf(1500.0, 5000.0)          // cuerpo de ruido de la caja
    private val hBins = binsOf(6000.0, 14_000.0)        // platos (y el brillo de la caja)
    private val aBins = binsOf(30.0, 14_000.0)
    /** Para comparar medios y agudos por hercio y no por total (el rango de agudos es más del doble de ancho). */
    private val mhWidth = (10 * log10(hBins.count().toDouble() / mBins.count())).toFloat()
    private val HN = 24
    private val hT = FloatArray(HN); private val hK = FloatArray(HN); private val hM = FloatArray(HN); private val hH = FloatArray(HN); private val hB = FloatArray(HN)
    private var hHead = -1; private var hCount = 0; private var clock = 0f; private var aPeak = -100f
    private var sinceKick = 1f; private var sinceDrop = 99f
    // energía: volumen real (dBFS) respecto a las partes fuertes de la canción, y graves para el drop
    private var loud = -60f; private var loudRef = -14f
    private var lowFast = -60f; private var lowLong = -60f
    private var energySlow = 0f; private var energyLong = 0f; private var primed = 0f

    fun histAt(d: Int): FloatArray = hist[((histHead - d) % HIST + HIST) % HIST]

    /** Un paso: [hasAudio] = hay muestras nuevas sonando en [samples]; [dt] en segundos; [smoothing] 0…1. */
    fun update(hasAudio: Boolean, dt: Float, smoothing: Float) {
        if (hasAudio) {
            fft()
            // la larga basta 60 veces por segundo: los graves no cambian más deprisa y es la FFT cara
            longAcc += dt
            if (longAcc >= 1f / 61) { longAcc = 0f; fftLong() }
            var fm = 0f
            for (i in 0 until NBAND) {
                val v = bandValue(bands[i]).toDouble().pow(2.2).toFloat()
                band[i] = v; if (v > fm) fm = v
            }
            agc = max(fm, agc * exp(-dt / 4f))
            for (i in 0 until NBAND) band[i] = min(1.15f, band[i] / max(agc, .12f))
        } else band.fill(0f)
        val ka = 1 - exp(-dt / .025f); val kr = 1 - exp(-dt / (.06f + .4f * smoothing))
        for (i in 0 until NBAND) {
            cur[i] += (band[i] - cur[i]) * (if (band[i] > cur[i]) ka else kr)
            if (cur[i] >= peak[i]) { peak[i] = cur[i]; peakV[i] = 0f } else { peakV[i] += dt * 1.8f; peak[i] = max(cur[i], peak[i] - peakV[i] * dt) }
        }
        smoothInto(spec, cur, 1 + (smoothing * 2).roundToInt())
        // en graves, solo con las vecinas: las notas son picos estrechos y el suavizado ancho los aplastaba
        for (i in 0 until longBands) {
            var s = 2 * cur[i]; var w = 2f
            if (i > 0) { s += cur[i - 1]; w++ }
            if (i + 1 < NBAND) { s += cur[i + 1]; w++ }
            spec[i] = s / w
        }
        histAcc += dt
        while (histAcc >= 1f / 60) { histAcc -= 1f / 60; histHead = (histHead + 1) % HIST; cur.copyInto(hist[histHead]) }
        var lb = 0f; for (i in 0 until BASS_BANDS) lb += cur[i]; lb /= BASS_BANDS
        bassAvg += (lb - bassAvg) * (1 - exp(-dt / 1.2f)); liveBass = lb
        punch = max(punch * exp(-dt / .12f), ((lb - bassAvg) * 3.2f).coerceIn(0f, 1f))
        instruments(dt, hasAudio)
    }


    private fun eDb(r: IntRange): Float { var s = 0.0; for (k in r) s += pw[k]; return (10 * log10(s + 1e-10)).toFloat() }

    /**
     * Bombo, caja y platos: cuánto ha SUBIDO la energía de su rango en los últimos ~30 ms (en dB), y solo si ese rango
     * pesa en la mezcla. Así un bajo que se desliza de nota (la energía de graves no sube) no es bombo, el ruido de una
     * caja (casi sin graves) tampoco, y la voz (que cambia de nota pero no sube de golpe en medios Y agudos a la vez)
     * no es caja. Bajo y voz: nivel. Energía: el volumen de verdad respecto a las partes fuertes de la canción.
     */
    private fun instruments(dt: Float, hasAudio: Boolean) {
        fun level(r: IntRange, a: FloatArray): Float { var s = 0f; for (i in r) s += a[i]; return s / max(1, r.count()) }
        clock += dt; sinceKick += dt; sinceDrop += dt
        val aDb = if (hasAudio) eDb(aBins) else -100f; val lDb = if (hasAudio) eDb(lBins) else -100f
        // lo más fuerte de lo que ha sonado hace poco: lo que está 30-40 dB por debajo es eco del filtro, no un instrumento
        aPeak = max(aDb, aPeak - dt * 8f)
        // con un suelo (50 dB por debajo de lo fuerte): salir del silencio digital no es «subir 80 dB»
        val floor = aPeak - 50f
        fun band(r: IntRange) = if (hasAudio) max(floor, eDb(r)) else floor
        val kDb = band(kBins); val bDb = band(bBins); val mDb = band(mBins); val hDb = band(hBins)
        // la media (en dB) de entre hace 20 y hace 120 ms (con cuadros lentos, el último que haya). Con la media y no
        // con el mínimo, los huecos de un bombo que «bate» con el bajo no parecen golpes nuevos al volver.
        var kRef = 0f; var bRef = 0f; var mRef = 0f; var hRef = 0f; var n = 0
        for (j in 0 until hCount) {
            val i = ((hHead - j) % HN + HN) % HN; val age = clock - hT[i]
            if (age < .02f) continue
            if (age > .12f && n > 0) break
            kRef += max(floor, hK[i]); bRef += max(floor, hB[i]); mRef += max(floor, hM[i]); hRef += max(floor, hH[i]); n++
        }
        if (n == 0) { kRef = kDb; bRef = bDb; mRef = mDb; hRef = hDb } else { kRef /= n; bRef /= n; mRef /= n; hRef /= n }
        hHead = (hHead + 1) % HN; hT[hHead] = clock; hK[hHead] = kDb; hM[hHead] = mDb; hH[hHead] = hDb; hB[hHead] = bDb; hCount = min(HN, hCount + 1)
        fun ramp(v: Float, a: Float, b: Float) = ((v - a) / (b - a)).coerceIn(0f, 1f)
        // ¿pesa el rango en la mezcla? (dB respecto al total)
        // bombo: suben los graves, que suenan más que el cuerpo de 150-400 Hz (el arranque seco de una caja también mancha
        // los graves, pero su cuerpo suena mucho más)
        val kStr = ramp(kDb - kRef, 3f, 8f) * ramp(kDb - bDb, -6f, 0f) * ramp(kDb - aDb, -20f, -9f) * ramp(kDb - aPeak, -24f, -14f)
        // caja: ruido de banda ancha. Suben los agudos (la voz casi no tiene) y también los medios aunque sea un poco (con una
        // voz sonando suben poco), y sus medios pesan (en los platos casi todo es agudo). Y se oye en la mezcla.
        val sStr = ramp(hDb - hRef, 3f, 9f) * ramp(mDb - mRef, .8f, 3f) * ramp(mDb - hDb + mhWidth, -20f, -12f) * ramp(mDb - aPeak, -40f, -30f)
        val hStr = ramp(hDb - hRef, 4f, 12f) * ramp(hDb - aDb, -42f, -30f) * ramp(hDb - aPeak, -44f, -34f) * (1 - sStr)
        // un bombo no se repite en menos de 0,12 s: lo que llega antes es el mismo golpe (o su cola), no otro
        val kDecay = kick * exp(-dt / .14f)
        kick = if (kStr > .55f && sinceKick < .12f) kDecay else max(kDecay, kStr)
        if (kStr > .55f && sinceKick >= .12f) sinceKick = 0f
        snare = max(snare * exp(-dt / .12f), sStr)
        hat = max(hat * exp(-dt / .07f), hStr)
        bass += ((level(0 until BASS_BANDS, cur) * 1.25f).coerceIn(0f, 1f) - bass) * (1 - exp(-dt / .06f))
        voice += ((level(voiceRange, cur) * 1.6f).coerceIn(0f, 1f) - voice) * (1 - exp(-dt / .08f))

        // volumen (dBFS de lo que suena) frente a lo más fuerte de la canción, que se olvida despacio (0,25 dB/s). La música
        // de ahora está muy comprimida: estrofa y estribillo se separan 2-4 dB (medido con pop y reguetón), de ahí la escala.
        var sq = 0.0; for (i in SHORT_END - 1024 until SHORT_END) sq += samples[i].toDouble() * samples[i]
        val db = if (hasAudio) (10 * log10(sq / 1024 + 1e-10)).toFloat() else -100f
        loud = if (hasAudio && primed < .6f) max(-60f, db) else loud + (max(-60f, db) - loud) * (1 - exp(-dt / .35f))
        loudRef = max(loud, loudRef - dt * .25f).coerceAtLeast(-30f)
        var all = 0f; for (i in 0 until NBAND) all += cur[i]; all /= NBAND
        val target = (.7f * ramp(loud - loudRef, -6f, -.5f) + .3f * (all * 1.8f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        energy += (target - energy) * (1 - exp(-dt / .4f))
        // al empezar a sonar, las medias parten de lo que suena (si no, el primer segundo parecía una subida)
        if (hasAudio && primed < .6f) { primed += dt; energy = target; energySlow = energy; energyLong = energy; lowFast = max(-60f, lDb); lowLong = lowFast }
        energySlow += (energy - energySlow) * (1 - exp(-dt / 2f)); energyLong += (energy - energyLong) * (1 - exp(-dt / 8f))
        lowFast += (max(-60f, lDb) - lowFast) * (1 - exp(-dt / .3f)); lowLong += (max(-60f, lDb) - lowLong) * (1 - exp(-dt / 4f))
        // drop: los graves entran de golpe (12 dB sobre lo de los últimos segundos) con la canción fuerte; uno cada 8 s como mucho
        if (lowFast - lowLong > 12f && energy > .5f && sinceDrop > 8f) { sinceDrop = 0f; drop = 1f } else drop *= exp(-dt / .6f)
        // subida: la energía crece durante segundos (y se olvida al llegar el drop)
        // (con la canción ya a tope no se está subiendo: se está arriba)
        val rise = if (sinceDrop < 8f) 0f else ramp(energySlow - energyLong, .04f, .2f) * (1 - ramp(energy, .75f, .92f))
        build += (rise - build) * (1 - exp(-dt / (if (rise < build) .3f else .6f)))
        // respiro: la canción cae mucho respecto a lo de los últimos segundos
        slow += ((ramp(energyLong - energy, .12f, .45f)) - slow) * (1 - exp(-dt / .8f))
    }

    /** La ventana corta: medios, agudos y la potencia que usan los golpes. */
    private fun fft() {
        val from = SHORT_END - N
        for (i in 0 until N) { re[i] = samples[from + i] * window[i]; im[i] = 0f }
        transform(N)
        for (k in 0 until N / 2) {
            val p2 = (re[k] * re[k] + im[k] * im[k]) / (N.toFloat() * N); pw[k] = p2
            val mag = sqrt(p2)
            smoothMag[k] = .5f * smoothMag[k] + .5f * mag
            val db = 20 * log10(max(1e-12f, smoothMag[k])) + tiltShort[k]
            bytes[k] = ((db + 90f) / 68f).coerceIn(0f, 1f)
        }
    }

    /** La ventana larga, solo para las bandas de graves. Misma escala que la corta: un seno da el mismo nivel en las dos. */
    private fun fftLong() {
        for (i in 0 until NL) { re[i] = samples[i] * windowLong[i]; im[i] = 0f }
        transform(NL)
        val gain = .42f / .5f   // ganancia de un seno con Blackman frente a Hann
        for (k in 0 until longBins) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / NL * gain
            smoothMagLong[k] = .5f * smoothMagLong[k] + .5f * mag
            val db = 20 * log10(max(1e-12f, smoothMagLong[k])) + tiltLong[k]
            bytesLong[k] = ((db + 90f) / 68f).coerceIn(0f, 1f)
        }
    }

    /** FFT radix 2 in situ sobre las [n] primeras posiciones de [re] e [im]. */
    private fun transform(n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len; val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var s = 0
            while (s < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = s + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                s += len
            }
            len = len shl 1
        }
    }
}

/** Media triangular con las vecinas; la cola de agudos baja para que la unión de arriba sea suave. */
internal fun smoothInto(out: FloatArray, src: FloatArray, margin: Int): FloatArray {
    val n = src.size
    for (i in 0 until n) {
        var s = 0f; var w = 0f
        for (j in -margin..margin) { val k = i + j; if (k < 0 || k >= n) continue; val wt = (margin - kotlin.math.abs(j) + 1).toFloat(); s += src[k] * wt; w += wt }
        out[i] = s / w
    }
    // solo las últimas barras (por encima de ~11 kHz) y poco: antes bajaba hasta la mitad todo lo de más de 7 kHz
    val from = (n * .92f).toInt()
    for (i in from until n) out[i] *= 1 - .3f * (i - n * .92f) / (n * .08f)
    return out
}
