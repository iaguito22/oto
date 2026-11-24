package io.github.iaguito22.oto.desktop.wallpaper

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

internal fun clamp(v: Float, a: Float, b: Float) = if (v < a) a else if (v > b) b else v
internal fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k
internal fun hueLerp(a: Float, b: Float, k: Float): Float { val d = ((b - a + 1.5f) % 1f) - .5f; return ((a + d * k + 1f) % 1f + 1f) % 1f }
/** Como hsl() de CSS: tono 0…1 (da igual si se sale), saturación y luz 0…1. */
internal fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Color =
    Color.hsl((((h % 1f) + 1f) % 1f) * 360f, clamp(s, 0f, 1f), clamp(l, 0f, 1f), clamp(a, 0f, 1f))
/** Un número «al azar» fijo para cada (i, k): el mismo diseño cada vez que se repite una frase. */
internal fun h01(i: Int, k: Int = 0): Float { val x = sin(i * 127.1 + k * 311.7) * 43758.5453; return (x - floor(x)).toFloat() }
internal fun spring(age: Float, k: Float = 9f, wob: Float = 13f): Float = if (age <= 0f) 0f else 1f - exp(-age * k) * cos(age * wob)

/** Lo que mueve el dibujo en cada cuadro (0…1), ya con la sensibilidad de cada instrumento aplicada. */
internal class Drive {
    var kick = 0f; var snare = 0f; var hat = 0f; var bass = 0f; var voice = 0f
    var energy = .3f; var build = 0f; var drop = 0f; var slow = 0f; var lvl = 1
}

/** Colores de una carátula: tono principal, segundo tono, saturación; gris si casi no tiene color. */
internal data class CoverColors(val h1: Float, val h2: Float, val s: Float, val grey: Boolean)

internal fun coverColors(img: ImageBitmap): CoverColors? = runCatching {
    val px = img.toPixelMap(); val w = px.width; val h = px.height; val side = min(w, h)
    val bins = FloatArray(24); var sw = 0f; var ss = 0f
    val step = maxOf(1, side / 36)
    var y = (h - side) / 2
    while (y < (h + side) / 2) {
        var x = (w - side) / 2
        while (x < (w + side) / 2) {
            val c = px[x, y]; val r = c.red; val g = c.green; val b = c.blue
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            if (mx - mn >= .06f) {
                val s = (mx - mn) / mx
                var hh = when (mx) { r -> ((g - b) / (mx - mn) + 6) % 6; g -> (b - r) / (mx - mn) + 2; else -> (r - g) / (mx - mn) + 4 } / 6f
                hh = ((hh % 1f) + 1f) % 1f
                val wt = s * s * mx * mx; bins[(hh * 24).toInt() % 24] += wt; sw += wt; ss += s * wt
            }
            x += step
        }
        y += step
    }
    val scale = (36f * 36f) / ((side / step).toFloat().pow(2))
    if (sw * scale < 6f) return CoverColors(0f, 0f, 0f, true)
    val sm = FloatArray(24) { i -> bins[i] + .5f * (bins[(i + 1) % 24] + bins[(i + 23) % 24]) }
    var a = 0; for (i in 1 until 24) if (sm[i] > sm[a]) a = i
    var bb = -1; for (i in 0 until 24) { val dd = min(abs(i - a), 24 - abs(i - a)); if (dd >= 3 && (bb < 0 || sm[i] > sm[bb])) bb = i }
    val h1 = (a + .5f) / 24; val h2 = if (bb >= 0 && sm[bb] > sm[a] * .12f) (bb + .5f) / 24 else (h1 + .09f) % 1f
    CoverColors(h1, h2, clamp(ss / sw * 1.25f, .45f, 1f), false)
}.getOrNull()

/** Una zona libre para la letra (nunca encima del visualizador). */
internal data class Zone(val id: String, val x0: Float, val x1: Float, val y0: Float, val y1: Float) {
    val w get() = x1 - x0; val h get() = y1 - y0
    val side get() = id == "izquierda" || id == "derecha"
}

/**
 * Dónde van la hora y los controles con el fondo vivo apagado: sin visualizador (no se ve ningún círculo), así que lo que
 * va encima o debajo de él queda en el centro; y los dos a la vez, para que se apilen sin pisarse (Iago, 24/11).
 */
internal fun widgetOnlyGeometry(W: Float, H: Float, d: Float, p: WallpaperSettings): Geometry = geometry(W, H, d, p.copy(visualizador = false))

/** Geometría de un monitor: dónde va el círculo, su radio, el círculo prohibido para la letra y las zonas libres. */
internal data class Geometry(val W: Float, val H: Float, val d: Float, val cx: Float, val cy: Float, val R: Float, val Rout: Float,
                             val ex: Float, val zones: List<Zone>, val barsTop: Float, val task: Float,
                             val clock: androidx.compose.ui.geometry.Rect? = null, val ctl: androidx.compose.ui.geometry.Rect? = null)

internal val EXT = mapOf("malla" to .75f, "ncs" to .95f, "eclipse" to 1.05f, "anillo" to .72f, "onda" to .62f, "pulso" to .5f, "instrumentos" to .78f, "barras" to .14f)

internal fun geometry(W: Float, H: Float, d: Float, p: WallpaperSettings): Geometry {
    val xs = mapOf('l' to .27f, 'c' to .5f, 'r' to .73f); val ys = mapOf('t' to .32f, 'm' to .5f, 'b' to .68f)
    // Sin visualizador no hay círculo: lo que va «encima» o «debajo» de él queda en el centro de la pantalla.
    val viz = p.visualizador
    val cx = if (!viz) W / 2 else W * ((xs[p.pos.getOrElse(1) { 'c' }] ?: .5f) + p.offX)
    val cy = if (!viz) H / 2 else H * ((ys[p.pos.getOrElse(0) { 'm' }] ?: .5f) + p.offY)
    val R = if (viz) H * p.tam else 0f
    val ext = EXT[p.estilo] ?: .75f
    val Rout = R * (1 + ext * (if (p.estilo == "barras") 1f else p.largo))
    val ex = if (viz) Rout * 1.1f + H * .03f else 0f
    val m = min(W, H) * .035f; val task = 48 * d; val icons = if (p.respetarIconos) 104 * d else 0f; val gap = H * .02f
    val top = m; val bottom = H - task - m * .6f
    val barsTop = if (viz && p.estilo == "barras") H - task - H * .2f * p.largo - gap else H
    val yMax = min(bottom, barsTop - gap)
    // la hora y los controles van primero; la letra se queda con lo que dejen libre
    val (clock, ctl) = layoutWidgets(W, H, cx, cy, ex, m, m + icons, W - m, top, yMax, gap, p)
    val taken = listOfNotNull(clock, ctl).map { it.inflate(gap) }
    val zones = listOf(
        Zone("izquierda", m + icons, cx - ex - gap, top, yMax), Zone("derecha", cx + ex + gap, W - m, top, yMax),
        Zone("arriba", m + icons, W - m, top, cy - ex - gap), Zone("abajo", m + icons, W - m, cy + ex + gap, yMax),
    ).map { z -> taken.fold(z) { acc, r -> acc.without(r) } }
        .filter { z -> if (z.side) z.w > W * .14f && z.h > H * .3f else z.w > W * .3f && z.h > H * .16f }
    return Geometry(W, H, d, cx, cy, R, Rout, ex, zones, barsTop, task, clock, ctl)
}

/** La zona sin el rectángulo [r]: el trozo más grande que queda (a un lado, al otro, encima o debajo). */
internal fun Zone.without(r: androidx.compose.ui.geometry.Rect): Zone {
    if (r.left >= x1 || r.right <= x0 || r.top >= y1 || r.bottom <= y0) return this
    return listOf(copy(x1 = r.left), copy(x0 = r.right), copy(y1 = r.top), copy(y0 = r.bottom))
        .filter { it.w > 0f && it.h > 0f }.maxByOrNull { it.w * it.h } ?: copy(x1 = x0)
}

/** Tamaño de la hora (ancho, alto) antes de colocarla; se mide de verdad al dibujar, esto reserva el sitio. */
internal fun clockSize(p: WallpaperSettings, H: Float): Pair<Float, Float> {
    if (p.relojEstilo == "dia") {   // «MIÉRCOLES» con letras muy separadas, la fecha y la hora en pequeño
        val fs = H * .062f * p.relojTam; val ss = H * .017f * p.relojTam
        return fs * 1.45f * 9 to fs * 1.25f + ss * 1.9f * (if (p.fecha) 2 else 1)
    }
    val fs = H * .085f * p.relojTam; val ds = fs * .26f
    val chars = (if (p.segundos) 8 else 5) + (if (p.hora24) 0f else 1.6f)
    val em = when (p.relojEstilo) { "grueso", "contorno" -> .7f; "digital" -> .64f; else -> .6f }
    val w = max(fs * em * chars, if (p.fecha) ds * 13f else 0f)
    return w to fs * 1.08f + (if (p.fecha) ds * 1.8f else 0f)
}

/** Tamaño de los controles según su estilo y los botones que lleven. */
internal fun ctlSize(p: WallpaperSettings, H: Float): Pair<Float, Float> {
    val u = H * p.ctlTam
    val extras = listOf(p.ctlMeGusta, p.ctlAleatorio, p.ctlRepetir).count { it }
    return when (p.ctlEstilo) {
        "tarjeta" -> max(u * .46f, u * .17f + u * .052f * (3 + extras) + (if (p.ctlVolumen) u * .1f else 0f) + u * .04f) to u * .15f
        "vinilo" -> { val d = u * .25f; d * .6f + H * .014f + u * .2f to u * .28f }   // disco pequeño metido en la esquina y el panel al lado
        "compacta" -> ((if (p.ctlCaratula) u * .125f else 0f) + max(u * .2f, u * .036f * (3 + extras) + (if (p.ctlVolumen) u * .08f else 0f))) to u * .11f
        "botones" -> (u * .062f * (3 + extras) + (if (p.ctlVolumen) u * .1f else 0f)) to u * (if (p.ctlProgreso) .085f else .062f)
        else -> (u * .053f * (3 + extras) + (if (p.ctlTitulo) u * .22f else if (p.ctlProgreso) u * .11f else 0f) + (if (p.ctlCaratula) u * .08f else u * .02f) + (if (p.ctlVolumen) u * .11f else 0f) + u * .035f) to u * .083f
    }
}

/**
 * Dónde van la hora y los controles. «sobre»/«bajo»/«izquierda»/«derecha» son respecto al círculo y nunca lo tocan
 * (si no caben, se encogen hasta la mitad); las esquinas, en la pantalla. Si los dos van al mismo sitio, se apilan
 * (la hora más cerca del círculo).
 */
internal fun layoutWidgets(W: Float, H: Float, cx: Float, cy: Float, ex: Float, m: Float, left: Float, right: Float, top: Float, bottom: Float,
                           gap: Float, p: WallpaperSettings): Pair<androidx.compose.ui.geometry.Rect?, androidx.compose.ui.geometry.Rect?> {
    // por cada sitio, dónde empieza el siguiente que se apile allí (hacia fuera del círculo o hacia dentro de la pantalla)
    val next = HashMap<String, Float>(); val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
    val centerStack = if (p.reloj && p.controles && p.ctlEstilo != "vinilo" && p.relojPos == "centro" && p.ctlPos == "centro")
        clockSize(p, H).second + gap + ctlSize(p, H).second else null
    fun k(avail: Float, need: Float) = clamp(avail / need, .5f, 1f)
    fun at(pos: String, w0: Float, h0: Float): androidx.compose.ui.geometry.Rect = when (pos) {
        "bajo" -> { val t = next[pos] ?: (cy + ex + gap); val k = k(bottom - t, h0); androidx.compose.ui.geometry.Rect(cx - w0 * k / 2, t, cx + w0 * k / 2, t + h0 * k) }
        "izquierda", "derecha" -> {
            val k = if (pos == "izquierda") k(cx - ex - gap - left, w0) else k(right - (cx + ex + gap), w0)
            val t = next[pos] ?: (cy - h0 * k / 2)
            if (pos == "izquierda") androidx.compose.ui.geometry.Rect(cx - ex - gap - w0 * k, t, cx - ex - gap, t + h0 * k)
            else androidx.compose.ui.geometry.Rect(cx + ex + gap, t, cx + ex + gap + w0 * k, t + h0 * k)
        }
        // en medio de la pantalla (no del círculo); si van los dos, la hora encima y el bloque entero centrado
        "centro" -> { val t = next[pos] ?: (H / 2 - (centerStack ?: h0) / 2); androidx.compose.ui.geometry.Rect(W / 2 - w0 / 2, t, W / 2 + w0 / 2, t + h0) }
        "ai" -> { val t = next[pos] ?: top; androidx.compose.ui.geometry.Rect(left, t, left + w0, t + h0) }
        "ad" -> { val t = next[pos] ?: top; androidx.compose.ui.geometry.Rect(right - w0, t, right, t + h0) }
        "bi" -> { val b = next[pos] ?: bottom; androidx.compose.ui.geometry.Rect(left, b - h0, left + w0, b) }
        "bd" -> { val b = next[pos] ?: bottom; androidx.compose.ui.geometry.Rect(right - w0, b - h0, right, b) }
        else -> { val b = next[pos] ?: (cy - ex - gap); val k = k(b - top, h0); androidx.compose.ui.geometry.Rect(cx - w0 * k / 2, b - h0 * k, cx + w0 * k / 2, b) }
    }
    fun mark(pos: String, r: androidx.compose.ui.geometry.Rect) { next[pos] = if (pos == "sobre" || pos == "bi" || pos == "bd") r.top - gap else r.bottom + gap }
    val opposite = mapOf("sobre" to "bajo", "bajo" to "sobre", "izquierda" to "derecha", "derecha" to "izquierda", "ai" to "bi", "ad" to "bd", "bi" to "ai", "bd" to "ad")
    /** Donde se pidió; si no cabe ni a la mitad de tamaño, al lado contrario; y si tampoco, a una esquina. */
    fun place(pos: String, size: Pair<Float, Float>, clock: Boolean): androidx.compose.ui.geometry.Rect {
        val (w0, h0) = size
        // «en el centro» con el círculo allí: la hora encima de él y los controles debajo (si no caben, al revés)
        val near = if (pos == "centro") (if (clock) listOf("sobre", "bajo") else listOf("bajo", "sobre")) else listOf(opposite[pos] ?: "bajo")
        for (cand in listOf(pos) + near + listOf("ad", "bd", "ai", "bi")) {
            val r = at(cand, w0, h0)
            val inside = r.left >= left - .5f && r.top >= top - .5f && r.right <= right + .5f && r.bottom <= bottom + .5f
            // ni encima de otro ni del círculo (una esquina con una hora muy ancha podía llegar a tocarlo)
            val clear = kotlin.math.hypot(cx.coerceIn(r.left, r.right) - cx, cy.coerceIn(r.top, r.bottom) - cy) >= ex
            if (inside && clear && placed.none { it.overlaps(r) }) { mark(cand, r); placed += r; return r }
        }
        val last = if (pos == "centro") (if (clock) "sobre" else "bajo") else pos
        return at(last, w0, h0).also { mark(last, it); placed += it }
    }
    // el vinilo va siempre abajo a la izquierda, pegado al borde (el disco se sale de la pantalla), y primero
    val vinyl = if (p.controles && p.ctlEstilo == "vinilo") {
        val (w0, h0) = ctlSize(p, H); val h = min(h0, bottom - top); val w = w0 * h / h0
        androidx.compose.ui.geometry.Rect(0f, bottom - h, w, bottom).also { next["bi"] = it.top - gap; placed += it.inflate(gap) }
    } else null
    // si los dos van encima del círculo, los controles quedan más cerca y la hora arriba del todo (como en los widgets)
    val ctlFirst = p.controles && vinyl == null && p.reloj && p.relojPos == p.ctlPos && p.ctlPos == "sobre"
    val ctl0 = if (ctlFirst) place(p.ctlPos, ctlSize(p, H), clock = false) else null
    val clock = if (p.reloj) place(p.relojPos, clockSize(p, H), clock = true) else null
    val ctl = vinyl ?: ctl0 ?: if (p.controles) place(p.ctlPos, ctlSize(p, H), clock = false) else null
    return clock to ctl
}

/**
 * El estado de un fondo vivo en un monitor: lo que se mueve de cuadro en cuadro (latido, temblor, colores,
 * partículas, láseres, carátulas, estela). [step] lo avanza; el dibujo solo lo lee.
 */
internal class WallpaperScene {
    var p = WallpaperSettings()
    var G: Geometry? = null
    private var geoKey = ""
    val U = Drive()
    lateinit var audio: Spectrum

    var vtime = 0f; private set
    var pulse = 1f; private set
    private var pulseV = 0f
    var shakeX = 0f; private set
    var shakeY = 0f; private set
    private var shakeA = 0f
    var playing = false
    var hasAudio = false

    // colores
    var h1 = .72f; private set
    var h2 = .85f; private set
    var sat = .85f; private set
    private var satMul = .85f
    /** La carátula casi no tiene color (blanca, negra o gris) y los colores salen de ella: todo en grises. */
    val greyCover: Boolean get() = (p.colores == "caratula" || p.colores == "mezcla") && coverCols?.grey == true
    /** Saturación de un acento que pide al menos [min]; en grises, 0. */
    fun accentSat(min: Float): Float = if (greyCover) 0f else max(min, sat)
    var sphereHue = -1f; private set

    // carátula (actual y anterior, para la transición)
    var cover: ImageBitmap? = null; private set
    var prevCover: ImageBitmap? = null; private set
    private var coverCols: CoverColors? = null
    var coverT = 99f; private set
    var vinyl = 0f; private set

    // ondas del bombo
    val kickRings = ArrayDeque<FloatArray>()   // [edad, fuerza]
    private var lastKick = 0f

    // láseres
    var laserOn = 0f; private set
    var laserRot = 0f; private set
    private var laserVel = 0f; private var laserTarget = 0f; private var laserBeatT = 0f; private var laserBeat = 0

    // partículas
    companion object { const val PM = 900 }
    val px = FloatArray(PM); val py = FloatArray(PM); val pz = FloatArray(PM); val pvx = FloatArray(PM); val pvy = FloatArray(PM)
    val life = FloatArray(PM); val maxLife = FloatArray(PM); val psz = FloatArray(PM); val pc = ByteArray(PM)
    private var pHead = 0; private var pAcc = 0f; private var lastHit = 0f; private var pType = ""
    private val rnd = Random(7)

    // malla NCS: banda (borde de fuera y de dentro) y estela de bordes anteriores
    val MB = 192
    val bOut = FloatArray(MB); val bIn = FloatArray(MB)
    val ghosts = ArrayDeque<FloatArray>()
    private val bRaw = FloatArray(Spectrum.NBAND); private val bTmp = FloatArray(Spectrum.NBAND)
    val tmpA = FloatArray(Spectrum.NBAND)

    // onda: historial de formas
    val waveHist = ArrayDeque<FloatArray>()
    val td = FloatArray(1024)

    // destellos / drop
    var flash = 0f; private set
    var dropAge = 9f; private set
    private var prevDrop = 0f

    /** [colors]: calculados fuera del hilo de la interfaz (leer los píxeles de la carátula tarda). */
    fun setCover(img: ImageBitmap?, colors: CoverColors? = null) {
        if (img === cover) return
        prevCover = cover; cover = img; coverCols = colors; coverT = 0f
    }

    private var lastP: WallpaperSettings? = null; private var lastW = 0f; private var lastH = 0f; private var lastD = 0f
    fun ensureGeometry(W: Float, H: Float, d: Float) {
        // lo normal: nada ha cambiado (antes se montaba una cadena larga en cada cuadro)
        if (p === lastP && W == lastW && H == lastH && d == lastD) return
        lastP = p; lastW = W; lastH = H; lastD = d
        val key = "$W|$H|$d|${p.visualizador}|${p.estilo}|${p.tam}|${p.pos}|${p.offX}|${p.offY}|${p.largo}|${p.respetarIconos}|" +
            "${p.reloj}|${p.relojPos}|${p.relojTam}|${p.relojEstilo}|${p.segundos}|${p.hora24}|${p.relojIdioma}|${p.fecha}|${p.controles}|${p.ctlPos}|${p.ctlEstilo}|${p.ctlTam}|" +
            "${p.ctlCaratula}|${p.ctlTitulo}|${p.ctlProgreso}|${p.ctlMeGusta}|${p.ctlAleatorio}|${p.ctlRepetir}|${p.ctlVolumen}"
        if (key != geoKey) { geoKey = key; G = geometry(W, H, d, p); waveHist.clear(); ghosts.clear() }
    }

    /** Avanza [dt] segundos. */
    fun step(dt: Float) {
        val g = G ?: return
        val a = audio
        val s = p.sens
        U.kick = a.kick * p.kick * s; U.snare = a.snare * p.snare * s; U.hat = a.hat * p.hat * s
        U.bass = a.bass * p.bass * s; U.voice = a.voice * p.voice * s
        U.energy = a.energy; U.build = a.build; U.drop = a.drop; U.slow = a.slow * p.lenta
        if (!playing) { U.kick = 0f; U.snare = 0f; U.hat = 0f; U.bass = 0f; U.voice = 0f; U.build = 0f; U.drop = 0f }
        U.lvl = if (U.energy > .62f) 2 else if (U.energy > .3f) 1 else 0
        vtime += dt * (if (playing) 1f else .15f) * (1 - .8f * U.slow) * (1 + U.energy * .4f)
        coverT += dt
        if (p.forma == "vinilo" && playing) vinyl += dt * 1.75f * (1 - .7f * U.slow)
        updateColors(dt)
        // latido: muelle en pasos de 1/240 s (con cuadros lentos un paso grande lo desbocaba)
        val hit = max(U.kick, a.punch)
        val target = 1 + .07f * p.latido * hit + .03f * p.latido * U.bass + .05f * U.drop
        var left = dt
        while (left > 1e-6f) { val h = min(left, 1f / 240); pulseV += ((target - pulse) * 420 - pulseV * 20) * h; pulse += pulseV * h; left -= 1f / 240 }
        pulse = clamp(pulse, .85f, 1.35f); if (!pulseV.isFinite()) pulseV = 0f
        shakeA = max(shakeA * exp(-dt / .12f), p.temblor * (if (U.drop > .6f) U.drop else if (U.lvl >= 2 && hit > .85f) .35f else 0f))
        shakeX = (rnd.nextFloat() - .5f) * shakeA * g.R * .06f; shakeY = (rnd.nextFloat() - .5f) * shakeA * g.R * .06f
        // ondas del bombo
        if (p.anillosBombo && playing && U.kick > .55f && lastKick < .35f) kickRings.addLast(floatArrayOf(0f, U.kick))
        lastKick = U.kick
        val it = kickRings.iterator(); while (it.hasNext()) { val r = it.next(); r[0] += dt; if (r[0] > 1.1f) it.remove() }
        // destellos y anillo del drop
        flash = if (p.destellos && playing) U.snare.coerceIn(0f, 1f).pow(4) * .07f + U.drop.pow(3) * .25f else 0f
        if (U.drop > .85f && prevDrop <= .85f) dropAge = 0f else dropAge += dt
        prevDrop = U.drop
        updateLasers(dt, hit)
        updateParticles(dt, g, hit)
        if (p.estilo == "malla") bandFrame(g, hit)
        if (p.estilo == "onda") waveFrame(g)
    }

    private fun hexHue(hex: String): Pair<Float, Float> = runCatching {
        val n = hex.removePrefix("#").toInt(16); val r = (n shr 16 and 255) / 255f; val gg = (n shr 8 and 255) / 255f; val b = (n and 255) / 255f
        val mx = maxOf(r, gg, b); val mn = minOf(r, gg, b); if (mx == mn) return 0f to 0f
        val h = when (mx) { r -> ((gg - b) / (mx - mn) + 6) % 6; gg -> (b - r) / (mx - mn) + 2; else -> (r - gg) / (mx - mn) + 4 }
        (h / 6f) to ((mx - mn) / mx)
    }.getOrDefault(0f to 0f)

    private fun updateColors(dt: Float) {
        var t1 = h1; var t2 = h2; var s = p.saturacion
        val cc = coverCols
        when {
            p.colores == "fijo" -> { val (a, sa) = hexHue(p.color1); t1 = a; t2 = hexHue(p.color2).first; s *= max(.15f, sa) }
            // sin color en la carátula: grises (el tono se queda quieto; antes derivaba con la música a todo color)
            greyCover -> s = 0f
            (p.colores == "caratula" || p.colores == "mezcla") && cc != null && !cc.grey -> {
                t1 = cc.h1; t2 = cc.h2; s *= cc.s
                if (p.colores == "mezcla") { t1 += .1f * U.build + .06f * (U.lvl - 1); t2 = cc.h2 + (if (U.lvl >= 2) .25f else 0f) }
            }
            else -> { t1 = h1 + dt * (.01f + .03f * U.energy); t2 = t1 + .15f + .3f * U.lvl / 2 }   // por la música: deriva con la energía
        }
        h1 = hueLerp(h1, t1, 1 - exp(-dt / .4f)); h2 = hueLerp(h2, t2, 1 - exp(-dt / .15f))
        satMul = lerp(satMul, if (p.colores != "fijo") .7f + .15f * U.lvl else 1f, 1 - exp(-dt / .5f))
        // al cambiar de carátula el color entra y sale poco a poco, como el tono
        sat = lerp(sat, clamp(s * (.55f + .45f * satMul), 0f, 1f), 1 - exp(-dt / .4f))
        // la malla NCS tiene un color fijo: sigue al de la carátula muy despacio
        sphereHue = if (sphereHue < 0f) h1 else hueLerp(sphereHue, h1, 1 - exp(-dt / 1.4f))
    }

    private fun updateLasers(dt: Float, hit: Float) {
        val want = if (p.laseres && (U.lvl >= 2 || U.drop > .1f) && U.slow < .3f) 1f else 0f
        laserOn += (want - laserOn) * (1 - exp(-dt / .25f))
        if (laserOn < .01f) return
        laserBeatT += dt
        if (hit > .7f && laserBeatT > .25f) { laserBeatT = 0f; laserBeat++; laserTarget = (h01(laserBeat, 4) - .5f) * 1.6f }
        var left = dt
        while (left > 1e-6f) { val h = min(left, 1f / 240); laserVel += ((laserTarget - laserRot) * 90 - laserVel * 11) * h; laserRot += laserVel * h; left -= 1f / 240 }
    }

    // ── partículas: chispas (salen del borde), polvo (flota despacio) y estrellas (vienen hacia ti en 3D) ──
    private fun spawn(n: Int, g: Geometry) {
        val ty = p.tipoPart
        repeat(n) {
            val i = pHead; pHead = (pHead + 1) % PM
            val a = rnd.nextFloat() * 2 * PI.toFloat()
            if (ty == "estrellas") {
                val z = 3 + rnd.nextFloat(); val rr = g.R * (1.15f + rnd.nextFloat() * 3.5f) * z
                px[i] = cos(a) * rr; py[i] = sin(a) * rr; pz[i] = z; pvx[i] = z
                maxLife[i] = 9f; life[i] = 9f; psz[i] = (1.2f + rnd.nextFloat() * 1.8f) * g.d * p.tamPart; pc[i] = if (rnd.nextFloat() < .6f) 0 else 1
                return@repeat
            }
            val r = g.Rout * (.92f + rnd.nextFloat() * .15f)
            val sp = g.R * (if (ty == "polvo") .25f + rnd.nextFloat() * .35f else 1.2f + rnd.nextFloat() * 1.6f + U.bass) * p.velPart
            px[i] = g.cx + cos(a) * r; py[i] = g.cy + sin(a) * r; pvx[i] = cos(a) * sp; pvy[i] = sin(a) * sp
            val l = if (ty == "polvo") 3 + rnd.nextFloat() * 3 else .9f + rnd.nextFloat() * 1.1f
            maxLife[i] = l; life[i] = l
            psz[i] = (if (ty == "polvo") 1.2f + rnd.nextFloat() * 2.2f else 1 + rnd.nextFloat() * 1.2f) * g.d * p.tamPart; pc[i] = if (rnd.nextFloat() < .55f) 0 else 1
        }
    }

    private fun updateParticles(dt: Float, g: Geometry, hit: Float) {
        if (!p.particulas) return
        val ty = p.tipoPart; if (ty != pType) { life.fill(0f); pType = ty }
        val slow = 1 - .75f * U.slow
        val base = when (ty) { "polvo" -> 40f; "estrellas" -> 120f; else -> 25f }
        pAcc += dt * p.cantidad * slow * (base * (.35f + U.energy) + (if (ty == "chispas") 260f else 60f) * hit * hit + 200f * U.build) * (if (playing) 1f else .25f)
        val n = pAcc.toInt(); pAcc -= n; spawn(n, g)
        if (hit > .7f && lastHit < .45f) spawn(((if (ty == "polvo") 8 else 30) * p.cantidad).toInt(), g)
        if (U.drop > .85f) spawn((20 * p.cantidad).toInt(), g)
        lastHit = hit
        if (ty == "estrellas") {
            val vz = (.35f + 2.6f * hit * hit + 1.2f * U.drop + .5f * U.energy) * p.velPart * slow
            for (i in 0 until PM) { if (life[i] <= 0f) continue; pvx[i] = pz[i]; pz[i] -= vz * dt * max(.4f, pz[i] * .6f); if (pz[i] < .3f) life[i] = 0f }
            return
        }
        val acc = if (ty == "chispas") 1 - dt * .6f else 1f; val boost = (1 + U.bass * .8f + U.drop) * slow
        for (i in 0 until PM) {
            if (life[i] <= 0f) continue
            life[i] -= dt * (if (ty == "polvo") 1f else slow)
            pvx[i] *= acc; pvy[i] *= acc
            if (ty == "polvo") { val dx = px[i] - g.cx; val dy = py[i] - g.cy; val l = hypot(dx, dy) + 1; pvx[i] += -dy / l * g.R * .05f * dt; pvy[i] += dx / l * g.R * .05f * dt }
            px[i] += pvx[i] * dt * boost; py[i] += pvy[i] * dt * boost
        }
    }

    // ── malla NCS: banda con borde exterior casi redondo y gotas hacia dentro, que reacciona al audio en vivo ──
    private fun bandFrame(g: Geometry, hit: Float) {
        val a = audio; val t = vtime; val Rs = g.R * 1.28f; val amp = p.largo * p.sens
        for (i in 0 until Spectrum.NBAND) bRaw[i] = .7f * a.band[i] + .3f * a.cur[i]
        var rb = 0f; for (i in 0 until Spectrum.BASS_BANDS) rb += bRaw[i]; rb /= Spectrum.BASS_BANDS
        val lb = clamp((rb - .12f) / .65f, 0f, 1.25f) * p.bass * (if (playing) 1f else 0f)
        val sm = smoothInto(bTmp, bRaw, 3)
        val spin = t * .05f; val scale = 1 + amp * (.085f * lb + .1f * hit)
        if (p.estela > 0f) { ghosts.addFirst(bOut.copyOf()); while (ghosts.size > 3) ghosts.removeLast() } else ghosts.clear()
        for (i in 0 until MB) {
            val q = i.toFloat() / MB; val ang = q * 2 * PI.toFloat(); val u = .5f - .5f * cos(ang + spin)
            val v = if (playing) min(1.25f, specAt(sm, u)) else 0f
            val l1 = max(0f, sin(2 * ang + t * .45f) * sin(3 * ang - t * .31f + 1)); val l2 = max(0f, sin(5 * ang - t * .8f + 2)).pow(3)
            bOut[i] = Rs * (scale + .025f * v)
            bIn[i] = bOut[i] - Rs * (.025f + amp * (.03f * lb + .045f * hit + .085f * v * v + .2f * l1 * l1 * (.25f + lb) + .07f * l2 * (U.voice + .5f * hit)))
        }
    }

    /** Onda: la forma de onda de lo que suena dando la vuelta al círculo, en capas que se persiguen. */
    private fun waveFrame(g: Geometry) {
        val R = g.R * 1.3f; val L = g.R * .5f * p.largo; val n = 160; val half = n / 2
        val s = FloatArray(half + 1)
        for (i in 0..half) { var v = 0f; val j = (i.toFloat() / half * 1000).toInt(); for (k in 0 until 8) v += td.getOrElse(j + k) { 0f }; s[i] = v / 8 }
        repeat(2) { for (i in 1 until half) s[i] = (s[i - 1] + 2 * s[i] + s[i + 1]) / 4 }
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            val q = i.toFloat() / n; val idx = if (i <= half) i else n - i; val a = PI.toFloat() / 2 + q * 2 * PI.toFloat()
            val r = R + clamp(s[idx] * 3 * p.sens * (.6f + .4f * p.voice + .3f * U.voice), -1f, 1f) * L * (sin(q * PI.toFloat()) * .7f + .3f)
            pts[i * 2] = cos(a) * r; pts[i * 2 + 1] = sin(a) * r
        }
        waveHist.addFirst(pts); while (waveHist.size > 9) waveHist.removeLast()
    }

    /** Cuánto pesa cada instrumento en cada zona del espectro (graves → bajo; medios → voz; agudos → caja y platos). */
    fun bandW(u: Float): Float {   // sin crear objetos: se llama miles de veces por cuadro
        val w = when {
            u <= .3f -> lerp(p.bass, p.voice, u / .3f)
            u <= .6f -> lerp(p.voice, p.snare, (u - .3f) / .3f)
            u <= .9f -> lerp(p.snare, p.hat, (u - .6f) / .3f)
            else -> p.hat
        }
        return w * p.sens
    }
    fun specAt(arr: FloatArray, u: Float): Float {
        val x = clamp(u, 0f, 1f) * (Spectrum.NBAND - 1); val i = x.toInt(); val f = x - i
        return (arr[i] * (1 - f) + arr[min(Spectrum.NBAND - 1, i + 1)] * f) * bandW(u)
    }
    /** Ángulo → posición en el espectro: con simetría, graves abajo y agudos arriba a los dos lados. */
    fun uOf(q: Float) = if (p.espejo) abs(q * 2 - 1) else q
}
