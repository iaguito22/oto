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
 * Espectro en vivo para el fondo, igual que el del laboratorio (Web Audio AnalyserNode + procesado de bandas):
 * FFT de 2048 con ventana de Blackman, suavizado 0,5, escala de −90 a −22 dB, 96 bandas logarítmicas de 30 Hz a
 * 14 kHz, ganancia automática, subida rápida y bajada lenta, picos que caen, historial de 16 cuadros y «golpe» de bajo.
 *
 * Como en Oto no hay análisis previo de la canción, los instrumentos (bombo, caja, platos, bajo, voz) y la energía se
 * calculan aquí en vivo, a partir de las propias bandas.
 */
internal class Spectrum(private val sampleRate: Int = 48_000) {
    companion object {
        const val N = 2048
        const val NBAND = 96
        const val HIST = 16
        private const val F0 = 30.0
        private const val F1 = 14_000.0
        val BASS_BANDS = (NBAND * ln(140 / F0) / ln(F1 / F0)).roundToInt()
        fun bandOf(hz: Double) = (NBAND * ln(hz / F0) / ln(F1 / F0)).roundToInt().coerceIn(0, NBAND)
    }

    val samples = FloatArray(N)
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val window = FloatArray(N) { i -> val a = 2 * PI * i / (N - 1); (.42 - .5 * cos(a) + .08 * cos(2 * a)).toFloat() }
    private val smoothMag = FloatArray(N / 2)
    private val bytes = FloatArray(N / 2)               // 0…1, como getByteFrequencyData / 255
    private val bins: Array<IntArray> = Array(NBAND) { i ->
        val hz = sampleRate.toDouble() / N
        val f0 = F0 * (F1 / F0).pow(i.toDouble() / NBAND); val f1 = F0 * (F1 / F0).pow((i + 1).toDouble() / NBAND)
        val b0 = (f0 / hz).toInt(); intArrayOf(b0, min(N / 2, max(b0 + 1, kotlin.math.ceil(f1 / hz).toInt())))
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
            var fm = 0f
            for (i in 0 until NBAND) {
                var m = 0f
                for (j in bins[i][0] until bins[i][1]) m = max(m, bytes[j])
                val v = m.toDouble().pow(2.2).toFloat() * (1 + .9f * i / NBAND)
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
        var sq = 0.0; for (i in N - 1024 until N) sq += samples[i].toDouble() * samples[i]
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

    private fun fft() {
        for (i in 0 until N) { re[i] = samples[i] * window[i]; im[i] = 0f }
        // radix 2, in situ
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= N) {
            val ang = -2 * PI / len; val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var s = 0
            while (s < N) {
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
        for (k in 0 until N / 2) {
            val p2 = (re[k] * re[k] + im[k] * im[k]) / (N.toFloat() * N); pw[k] = p2
            val mag = sqrt(p2)
            smoothMag[k] = .5f * smoothMag[k] + .5f * mag
            val db = 20 * log10(max(1e-12f, smoothMag[k]))
            bytes[k] = ((db + 90f) / 68f).coerceIn(0f, 1f)
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
    val from = (n * .82f).toInt()
    for (i in from until n) out[i] *= 1 - .55f * (i - n * .82f) / (n * .18f)
    return out
}
