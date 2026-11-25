package io.github.iaguito22.oto.desktop.wallpaper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Una zona que se puede pulsar en el escritorio: [id] es la acción (prev, play, next, like, shuffle, repeat, seek, vol). */
internal class Hit(val id: String, val rect: Rect)

/**
 * Lo que enseñan la hora y los controles. Son estados de Compose: la capa que los pinta solo se vuelve a dibujar cuando
 * cambia algo (cada segundo la hora, cada cuarto de segundo la barra, al pasar el ratón), no en cada cuadro.
 */
internal class WidgetState {
    var time by mutableStateOf(""); var suffix by mutableStateOf(""); var date by mutableStateOf("")
    var title by mutableStateOf(""); var artist by mutableStateOf(""); var hasSong by mutableStateOf(false)
    var playing by mutableStateOf(false); var posMs by mutableLongStateOf(0L); var durMs by mutableLongStateOf(0L)
    var liked by mutableStateOf(false); var shuffle by mutableStateOf(false); var loop by mutableIntStateOf(0); var volume by mutableFloatStateOf(1f)
    var hover by mutableStateOf<String?>(null); var pressed by mutableStateOf<String?>(null)
    var reveal by mutableFloatStateOf(1f)
    var cover by mutableStateOf<ImageBitmap?>(null)
    var day by mutableStateOf(""); var dateLine by mutableStateOf("")
    /** Vinilo: las siguientes de la cola y sus miniaturas (por dirección de la imagen). */
    var queue by mutableStateOf<List<QItem>>(emptyList())
    val thumbs = androidx.compose.runtime.mutableStateMapOf<String, ImageBitmap>()
    /** Vinilo: cuánto ha girado el disco (radianes); lo avanza el bucle de cuadros mientras suena. */
    var spin = 0f
    /** Zonas pulsables del último dibujo, en píxeles de la ventana. */
    @Volatile var hits: List<Hit> = emptyList()

    private val es = Locale.forLanguageTag("es-ES")
    // Los nombres van escritos aquí: el Java que va con Oto no lleva los datos de idiomas (jdk.localedata) y con
    // DateTimeFormatter salían abreviaturas genéricas en vez de «martes» o «noviembre».
    // Sin tildes (Iago, 25/11): la É y la Á en mayúsculas descuadran muchas fuentes del reloj.
    private val days = listOf("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo")
    private val months = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
    private val daysEn = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val monthsEn = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    /** Pone la hora de [now] con el formato elegido. */
    fun tick(p: WallpaperSettings, now: LocalDateTime = LocalDateTime.now()) {
        val h = if (p.hora24) now.hour else (now.hour + 11) % 12 + 1
        val hh = if (p.hora24) "%02d".format(h) else "$h"
        time = if (p.segundos) "$hh:%02d:%02d".format(now.minute, now.second) else "$hh:%02d".format(now.minute)
        val en = p.relojIdioma == "en"
        suffix = if (p.hora24) "" else if (now.hour < 12) (if (en) "AM" else "a. m.") else (if (en) "PM" else "p. m.")
        val d = (if (en) daysEn else days)[now.dayOfWeek.value - 1]; val m = (if (en) monthsEn else months)[now.monthValue - 1]
        date = if (en) "$d, $m ${now.dayOfMonth}" else "$d, ${now.dayOfMonth} de $m"
        day = d.uppercase(es)
        dateLine = "${now.dayOfMonth} $m, ${now.year}".uppercase(es)
    }
}

/**
 * Los clics sobre los controles, a partir del ratón leído cada ~16 ms: un botón se activa al soltar encima del mismo que
 * se pulsó (como un botón normal; si arrastras fuera, no pasa nada) y el volumen sigue al ratón mientras está pulsado.
 */
internal class WidgetInput {
    private var wasDown = false
    /** Lo pulsado ahora mismo (para pintarlo hundido). */
    var pressed: String? = null; private set

    /** Un paso: [at] = ratón sobre el escritorio (o null), [down] = botón pulsado. Devuelve la acción y la posición (0…1) en su zona. */
    fun step(at: Offset?, down: Boolean, hits: List<Hit>): Pair<String, Float>? {
        val hit = at?.let { pt -> hits.firstOrNull { it.rect.contains(pt) } }
        fun frac(id: String) = hits.firstOrNull { it.id == id }?.rect?.let { r -> at?.let { ((it.x - r.left) / r.width).coerceIn(0f, 1f) } }
        var out: Pair<String, Float>? = null
        if (down && !wasDown) pressed = hit?.id
        val id = pressed
        if (down && id == "vol") frac("vol")?.let { out = "vol" to it }
        if (!down && wasDown) { if (id != null && id != "vol" && id == hit?.id) out = id to (frac(id) ?: 0f); pressed = null }
        if (!down) pressed = null
        wasDown = down
        return out
    }
}

/** Una canción de las siguientes en la cola ([index] = su sitio en la cola, para saltar a ella). */
internal class QItem(val index: Int, val title: String, val artist: String, val art: String?)

/**
 * Las fuentes que se pueden elegir para la hora y los controles. Anurati, Blanka, Elianto y Ailerons no van dentro de
 * Oto (se publica y sus licencias no dejan repartirlas): se usan si están instaladas en Windows o copiadas en la carpeta
 * `fuentes` de los datos de Oto. Si no están, Michroma (libre, OFL, va con Oto y se parece a Anurati).
 */
internal object WidgetFonts {
    /** Fuentes de exposición: solo mayúsculas (el texto se pasa a mayúsculas con ellas). */
    val DISPLAY = setOf("anurati", "blanka", "elianto", "ailerons", "michroma")
    val michroma = FontFamily(androidx.compose.ui.text.platform.Font(resource = "fonts/michroma.ttf"))
    private val cache = HashMap<String, FontFamily?>()
    private val faces = HashMap<String, org.jetbrains.skia.Typeface?>()
    // lo que no estaba se vuelve a buscar cada 10 s: así una fuente recién copiada se usa sin reiniciar Oto
    private val missingSince = HashMap<String, Long>()
    private fun stale(name: String): Boolean {
        val t = missingSince[name] ?: return false
        if (System.currentTimeMillis() - t < 10_000) return false
        cache.remove(name); faces.remove(name); missingSince.remove(name); return true
    }
    private fun dirs() = listOfNotNull(runCatching { java.io.File(io.github.iaguito22.oto.OtoDirs.filesDir, "fuentes") }.getOrNull(),
        System.getenv("WINDIR")?.let { java.io.File(it, "Fonts") }, System.getenv("LOCALAPPDATA")?.let { java.io.File(it, "Microsoft/Windows/Fonts") })
    fun file(name: String): java.io.File? = dirs().asSequence().flatMap { it.listFiles()?.asSequence().orEmpty() }
        .firstOrNull { it.name.lowercase().startsWith(name) && it.extension.lowercase() in setOf("ttf", "otf") }
    fun installed(name: String) = name == "michroma" || name == "oto" || file(name) != null
    /** La familia de [name]; si es una de fuera y no está, Michroma. */
    fun family(name: String): FontFamily = when (name) {
        "oto" -> rubik
        "michroma" -> michroma
        else -> synchronized(cache) {
            stale(name)
            cache.getOrPut(name) { file(name)?.let { runCatching { FontFamily(androidx.compose.ui.text.platform.Font(it)) }.getOrNull() }.also { if (it == null) missingSince[name] = System.currentTimeMillis() } }
        } ?: michroma
    }
    private val michromaFace by lazy {
        runCatching { WidgetFonts::class.java.classLoader.getResourceAsStream("fonts/michroma.ttf")!!.use { org.jetbrains.skia.FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(it.readBytes())) } }.getOrNull()
    }
    /** La misma fuente que [family] para Skia (para medir el dibujo de cada letra); null si no se puede. */
    fun typeface(name: String): org.jetbrains.skia.Typeface? = when (name) {
        "michroma" -> michromaFace
        "oto" -> null
        else -> synchronized(cache) {
            stale(name)
            faces.getOrPut(name) { file(name)?.let { runCatching { org.jetbrains.skia.FontMgr.default.makeFromFile(it.path) }.getOrNull() } }
        } ?: michromaFace
    }
}

/**
 * Una palabra con el mismo hueco entre el dibujo de cada letra (no entre sus cajas): en Anurati la «M» trae mucho aire
 * a la derecha y salía «M ARTES» (Iago, 24/11). [x] = dónde va el origen de cada letra; [width] = de la primera tinta a la última.
 */
internal class EvenText(val text: String, val font: org.jetbrains.skia.Font, val x: FloatArray, val width: Float, val ascent: Float, val height: Float)

private var evenCache: EvenText? = null
private var evenKey = ""
internal fun evenText(text: String, face: org.jetbrains.skia.Typeface, size: Float, gap: Float): EvenText {
    val key = "$text|${face.hashCode()}|$size|$gap"
    evenCache?.takeIf { evenKey == key }?.let { return it }
    val font = org.jetbrains.skia.Font(face, size).apply { edging = org.jetbrains.skia.FontEdging.ANTI_ALIAS; isSubpixel = true }
    val xs = FloatArray(text.length); var cur = 0f
    text.forEachIndexed { i, ch ->
        val s = ch.toString(); val b = font.measureText(s)
        if (ch.isWhitespace() || b.width <= 0f) { xs[i] = cur; cur += font.measureTextWidth(s) + gap }
        else { xs[i] = cur - b.left; cur += b.width + gap }
    }
    val m = font.metrics
    return EvenText(text, font, xs, max(0f, cur - gap), -m.ascent, m.descent - m.ascent).also { evenCache = it; evenKey = key }
}
/** Los iconos de los botones (pintores de Material, creados una vez en la ventana). */
internal class WidgetIcons(val prev: Painter, val play: Painter, val pause: Painter, val next: Painter, val like: Painter, val liked: Painter,
                           val shuffle: Painter, val repeat: Painter, val repeatOne: Painter, val volume: Painter)

/** Con una fuente de exposición (solo mayúsculas) el texto de los controles va en mayúsculas. */
internal fun ctlText(p: WallpaperSettings, s: String) = if (p.ctlFuente in WidgetFonts.DISPLAY) s.uppercase() else s

internal fun fmtTime(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "${s / 60}:%02d".format(s % 60) }

// ── la hora ──

internal fun DrawScope.drawClock(sc: WallpaperScene, g: Geometry, tm: TextMeasurer, st: WidgetState) {
    val r = g.clock ?: return; val p = sc.p
    if (st.time.isEmpty()) return
    if (p.relojEstilo == "dia") { drawDayClock(sc, g, r, tm, st); return }
    val k = r.height / clockSize(p, g.H).second
    val fs = g.H * .085f * p.relojTam * k; val ds = fs * .26f
    val (fam, wt) = when (p.relojEstilo) {
        "grueso", "contorno" -> jakarta to FontWeight.ExtraBold
        "digital" -> FontFamily.Monospace to FontWeight.Bold
        else -> rubik to FontWeight.Normal
    }
    val own = p.relojFuente.takeIf { it != "estilo" }?.let(WidgetFonts::family)
    val tStyle = TextStyle(fontFamily = own ?: fam, fontWeight = if (own != null) FontWeight.Normal else wt, fontSize = (fs / density).sp, letterSpacing = (if (p.relojEstilo == "fino") -.02f else 0f).em)
    val time = tm.measure(st.time, tStyle)
    val suf = st.suffix.takeIf { it.isNotEmpty() }?.let { tm.measure(it, TextStyle(fontFamily = rubik, fontWeight = FontWeight.Bold, fontSize = (fs * .26f / density).sp)) }
    val date = if (p.fecha && st.date.isNotEmpty()) tm.measure(st.date, TextStyle(fontFamily = rubik, fontWeight = FontWeight.Normal, fontSize = (ds / density).sp)) else null
    val color = if (p.relojColor == "caratula") hsl(sc.h1, if (sc.greyCover) 0f else min(.9f, sc.sat + .1f), .76f) else Color.White
    val a = clamp(p.relojOpacidad, 0f, 1f); val shadowA = a * (.18f + .3f * p.sombra)
    // hacia dónde se alinea: hacia el círculo en los lados, al borde en las esquinas, centrada arriba y abajo
    val align = when (p.relojPos) { "ai", "bi", "derecha" -> 0; "ad", "bd", "izquierda" -> 2; else -> 1 }
    val tw = time.size.width + (suf?.let { it.size.width + fs * .08f } ?: 0f)
    fun x(w: Float) = when (align) { 0 -> r.left; 2 -> r.right - w; else -> r.center.x - w / 2 }
    val blockH = time.size.height * .92f + (date?.let { it.size.height * 1.1f } ?: 0f)
    val y0 = r.top + (r.height - blockH) / 2
    val beat = if (p.relojLatido && sc.playing) 1 + .045f * clamp(sc.U.kick, 0f, 1f) else 1f
    scale(beat, beat, Offset(x(tw) + tw / 2, y0 + blockH / 2)) {
        val tp = Offset(x(tw), y0)
        drawText(time, Color.Black, tp, alpha = shadowA, drawStyle = Stroke(fs * .09f, join = StrokeJoin.Round))
        if (p.relojEstilo == "contorno") drawText(time, color, tp, alpha = a, drawStyle = Stroke(max(1.5f, fs * .035f), join = StrokeJoin.Round))
        else drawText(time, color, tp, alpha = a)
        suf?.let { s -> drawText(s, color, Offset(tp.x + time.size.width + fs * .08f, y0 + time.size.height * .2f), alpha = a * .8f) }
        date?.let { dl ->
            val dp = Offset(x(dl.size.width.toFloat()), y0 + time.size.height * .92f)
            drawText(dl, Color.Black, dp, alpha = shadowA, drawStyle = Stroke(ds * .14f, join = StrokeJoin.Round))
            drawText(dl, color, dp, alpha = a * .78f)
        }
    }
}

/** «MARTES» en grande y muy espaciado, la fecha debajo y «— 21:47 —» en pequeño, como los widgets de Android. */
private fun DrawScope.drawDayClock(sc: WallpaperScene, g: Geometry, r: Rect, tm: TextMeasurer, st: WidgetState) {
    val p = sc.p; val k = r.height / clockSize(p, g.H).second
    val fs = g.H * .062f * p.relojTam * k; val ss = g.H * .017f * p.relojTam * k
    val color = if (p.relojColor == "caratula") hsl(sc.h1, if (sc.greyCover) 0f else min(.9f, sc.sat + .1f), .78f) else Color.White
    val a = clamp(p.relojOpacidad, 0f, 1f); val shadowA = a * (.18f + .3f * p.sombra)
    val sp = .3f
    val dayFont = if (p.relojFuente == "estilo") "anurati" else p.relojFuente
    // el día con el mismo hueco entre letras (medido sobre su dibujo); con la letra de Oto, como siempre
    val even = WidgetFonts.typeface(dayFont)?.let { evenText(st.day, it, fs, fs * .34f) }
    val dayL = tm.measure(st.day, TextStyle(fontFamily = WidgetFonts.family(dayFont), fontSize = (fs / density).sp, letterSpacing = sp.em))
    val dateL = if (p.fecha && st.dateLine.isNotEmpty()) tm.measure(st.dateLine, TextStyle(fontFamily = rubik, fontWeight = FontWeight.Normal, fontSize = (ss / density).sp, letterSpacing = .3f.em)) else null
    val timeL = tm.measure("—  ${st.time}${if (st.suffix.isEmpty()) "" else " " + st.suffix}  —", TextStyle(fontFamily = rubik, fontWeight = FontWeight.Normal, fontSize = (ss * 1.05f / density).sp, letterSpacing = .2f.em))
    val align = when (p.relojPos) { "ai", "bi", "derecha" -> 0; "ad", "bd", "izquierda" -> 2; else -> 1 }
    // el espaciado también se añade detrás de la última letra: se descuenta para que quede bien centrado
    fun x(l: TextLayoutResult, trail: Float) = (l.size.width - trail).let { w -> when (align) { 0 -> r.left; 2 -> r.right - w; else -> r.center.x - w / 2 } }
    val beat = if (p.relojLatido && sc.playing) 1 + .035f * clamp(sc.U.kick, 0f, 1f) else 1f
    val dayH = even?.height ?: dayL.size.height.toFloat()
    val blockH = dayH + ss * .5f + (dateL?.size?.height ?: 0) + timeL.size.height
    var y = r.top + (r.height - blockH) / 2
    scale(beat, beat, r.center) {
        fun line(l: TextLayoutResult, trail: Float, alpha: Float, strokeW: Float) {
            val at = Offset(x(l, trail), y)
            drawText(l, Color.Black, at, alpha = shadowA, drawStyle = Stroke(strokeW, join = StrokeJoin.Round))
            drawText(l, color, at, alpha = a * alpha); y += l.size.height
        }
        if (even != null) {
            val x0 = when (align) { 0 -> r.left; 2 -> r.right - even.width; else -> r.center.x - even.width / 2 }
            val base = y + even.ascent
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                val shadow = org.jetbrains.skia.Paint().apply { isAntiAlias = true; mode = org.jetbrains.skia.PaintMode.STROKE; strokeWidth = fs * .08f
                    strokeJoin = org.jetbrains.skia.PaintStrokeJoin.ROUND; this.color = Color.Black.copy(alpha = shadowA).toArgb() }
                val fill = org.jetbrains.skia.Paint().apply { isAntiAlias = true; this.color = color.copy(alpha = a).toArgb() }
                for (paint in listOf(shadow, fill)) even.text.forEachIndexed { i, ch -> nc.drawString(ch.toString(), x0 + even.x[i], base, even.font, paint) }
            }
            y += even.height
        } else line(dayL, fs * sp, 1f, fs * .08f)
        y += ss * .5f
        dateL?.let { line(it, ss * .3f, .85f, ss * .14f) }
        line(timeL, ss * 1.05f * .2f, .7f, ss * .14f)
    }
}

// ── los controles ──

/** Pinta los controles y deja en [WidgetState.hits] dónde está cada botón. */
internal fun DrawScope.drawControls(sc: WallpaperScene, g: Geometry, tm: TextMeasurer, st: WidgetState, ic: WidgetIcons) {
    val r = g.ctl ?: run { st.hits = emptyList(); return }; val p = sc.p
    val alpha = clamp(st.reveal * p.ctlOpacidad, 0f, 1f)
    if (!st.hasSong || alpha < .01f) { st.hits = emptyList(); return }
    val hits = ArrayList<Hit>()
    val accent = if (p.ctlColor == "caratula") hsl(sc.h1, sc.accentSat(.55f), .62f) else Color.White
    val onAccent = if (p.ctlColor == "caratula") Color.White else Color(0xFF111114)
    val k = r.height / ctlSize(p, g.H).second; val u = g.H * p.ctlTam * k

    fun button(id: String, pt: Painter, cx: Float, cy: Float, s: Float, primary: Boolean = false, on: Boolean? = null) {
        val hov = st.hover == id; val down = st.pressed == id; val sz = s * (if (down) .88f else 1f)
        val hr = s * .62f
        // compacta: minimalista, solo los iconos (con una sombra suave para leerse sobre cualquier fondo)
        val minimal = p.ctlEstilo == "compacta"
        if (primary && !minimal) drawCircle(accent, hr * (if (hov) 1.06f else 1f) * (if (down) .92f else 1f), Offset(cx, cy), alpha = alpha)
        else {
            if (p.ctlEstilo == "botones") drawCircle(Color.Black, hr, Offset(cx, cy), alpha = alpha * .35f)
            if (hov) drawCircle(Color.White, hr, Offset(cx, cy), alpha = alpha * .14f)
        }
        val c = when { primary && !minimal -> onAccent; on == true -> accent; else -> Color.White }
        val a = alpha * (if (on == false) .55f else if (minimal && !primary && !hov) .8f else .95f)
        if (minimal) translate(cx - sz / 2 + g.d, cy - sz / 2 + g.d) { with(pt) { draw(Size(sz, sz), alpha = alpha * .45f, colorFilter = ColorFilter.tint(Color.Black)) } }
        translate(cx - sz / 2, cy - sz / 2) { with(pt) { draw(Size(sz, sz), alpha = a, colorFilter = ColorFilter.tint(c)) } }
        hits += Hit(id, Rect(cx - hr, cy - hr, cx + hr, cy + hr))
    }
    fun bar(id: String, x0: Float, x1: Float, y: Float, th: Float, frac: Float) {
        if (x1 - x0 < 8f) return
        val f = clamp(frac, 0f, 1f); val hov = st.hover == id || st.pressed == id; val t = th * (if (hov) 1.5f else 1f)
        drawRoundRect(Color.White, Offset(x0, y - t / 2), Size(x1 - x0, t), CornerRadius(t / 2), alpha = alpha * .2f)
        drawRoundRect(accent, Offset(x0, y - t / 2), Size((x1 - x0) * f, t), CornerRadius(t / 2), alpha = alpha)
        if (hov) drawCircle(Color.White, t * 1.6f, Offset(x0 + (x1 - x0) * f, y), alpha = alpha)
        hits += Hit(id, Rect(x0, y - max(th * 4f, 7f * g.d), x1, y + max(th * 4f, 7f * g.d)))
    }
    fun text(s: String, size: Float, bold: Boolean, maxW: Float): TextLayoutResult? = if (s.isEmpty() || maxW < size) null else
        tm.measure(ctlText(sc.p, s), TextStyle(fontFamily = WidgetFonts.family(sc.p.ctlFuente), fontWeight = if (bold && sc.p.ctlFuente == "oto") FontWeight.Bold else FontWeight.Normal, fontSize = (size / density).sp),
            overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW.toInt().coerceAtLeast(1)))
    fun shadowed(l: TextLayoutResult, at: Offset, a: Float, c: Color = Color.White) {
        if (p.ctlEstilo == "botones" || p.ctlEstilo == "compacta" || p.ctlFondo < .2f) drawText(l, Color.Black, at, alpha = alpha * .45f, drawStyle = Stroke(l.layoutInput.style.fontSize.value * density * .16f, join = StrokeJoin.Round))
        drawText(l, c, at, alpha = alpha * a)
    }
    fun cover(x: Float, y: Float, s: Float, round: Float) {
        val img = st.cover ?: sc.cover
        val shape = pooledPath().apply { addRoundRect(RoundRect(x, y, x + s, y + s, CornerRadius(round))) }
        if (img == null) { drawPath(shape, hsl(sc.h1, .4f, .25f), alpha = alpha); return }
        clipPath(shape) { drawSquare(img, Rect(x, y, x + s, y + s), alpha) }
    }
    /** La fila de botones a partir de [x0] centrada en [cy]; devuelve dónde acaba. */
    fun buttons(x0: Float, cy: Float, slot: Float): Float {
        var x = x0 + slot / 2; val s = slot * .62f
        button("prev", ic.prev, x, cy, s); x += slot
        button("play", if (st.playing) ic.pause else ic.play, x, cy, s * 1.05f, primary = true); x += slot
        button("next", ic.next, x, cy, s); x += slot
        if (p.ctlMeGusta) { button("like", if (st.liked) ic.liked else ic.like, x, cy, s * .9f, on = if (st.liked) true else null); x += slot }
        if (p.ctlAleatorio) { button("shuffle", ic.shuffle, x, cy, s * .85f, on = st.shuffle); x += slot }
        if (p.ctlRepetir) { button("repeat", if (st.loop == 2) ic.repeatOne else ic.repeat, x, cy, s * .85f, on = st.loop != 0); x += slot }
        return x - slot / 2
    }
    fun volume(x0: Float, cy: Float, w: Float, th: Float) {
        val s = th * 6f
        translate(x0, cy - s / 2) { with(ic.volume) { draw(Size(s, s), alpha = alpha * .8f, colorFilter = ColorFilter.tint(Color.White)) } }
        bar("vol", x0 + s * 1.2f, x0 + w, cy, th, st.volume)
    }
    val frac = if (st.durMs > 0) st.posMs.toFloat() / st.durMs else 0f
    val times = if (p.ctlTiempos && st.durMs > 0) "${fmtTime(st.posMs)} / ${fmtTime(st.durMs)}" else ""

    when (p.ctlEstilo) {
        "tarjeta" -> {
            val pad = r.height * .1f
            drawRoundRect(Color.Black, r.topLeft, r.size, CornerRadius(r.height * .14f), alpha = alpha * p.ctlFondo)
            drawRoundRect(Color.White, r.topLeft, r.size, CornerRadius(r.height * .14f), alpha = alpha * .08f, style = Stroke(g.d))
            var x = r.left + pad
            if (p.ctlCaratula) { val s = r.height - pad * 2; cover(x, r.top + pad, s, s * .12f); x += s + pad }
            val right = r.right - pad; val w = right - x
            var y = r.top + pad * .9f
            if (p.ctlTitulo) {
                text(st.title, r.height * .14f, true, w)?.let { shadowed(it, Offset(x, y), 1f); y += it.size.height }
                text(st.artist, r.height * .105f, false, w)?.let { shadowed(it, Offset(x, y), .62f); y += it.size.height }
            }
            val slot = u * .052f; val by = r.bottom - pad - slot * .5f
            if (p.ctlProgreso) {
                val py = (y + by - slot * .5f) / 2 + (if (times.isEmpty()) 0f else -r.height * .03f)
                bar("seek", x, right, py, max(2f, r.height * .022f), frac)
                text(times, r.height * .08f, false, w)?.let { shadowed(it, Offset(x, py + r.height * .035f), .55f) }
            }
            val end = buttons(x - slot * .18f, by, slot)
            if (p.ctlVolumen) volume(end + slot * .2f, by, min(u * .1f, right - end - slot * .2f), max(2f, r.height * .02f))
        }
        "botones" -> {
            val slot = u * .062f; val cy = r.top + slot / 2
            val rowW = slot * (3 + listOf(p.ctlMeGusta, p.ctlAleatorio, p.ctlRepetir).count { it }) + (if (p.ctlVolumen) u * .1f else 0f)
            val x0 = r.center.x - rowW / 2
            val end = buttons(x0, cy, slot)
            if (p.ctlVolumen) volume(end + slot * .1f, cy, u * .1f - slot * .1f, max(2f, slot * .05f))
            if (p.ctlProgreso) {
                val py = r.bottom - u * .01f
                bar("seek", x0 + slot * .15f, x0 + rowW - slot * .15f, py, max(2f, u * .0032f), frac)
            }
        }
        "vinilo" -> drawVinyl(sc, g, r, tm, st, ic, accent, onAccent, alpha, u, hits)
        "compacta" -> {
            // como los widgets minimalistas: sin fondo, la carátula a la izquierda y a su derecha, de arriba abajo,
            // título, artista, la barra con los tiempos y los controles
            val h = r.height; var x = r.left
            if (p.ctlCaratula) {
                drawRoundRect(Color.Black, Offset(x + g.d * 2, r.top + g.d * 3), Size(h, h), CornerRadius(h * .12f), alpha = alpha * .35f)
                cover(x, r.top, h, h * .12f); x += h + u * .014f
            }
            val w = r.right - x; var y = r.top - h * .02f
            if (p.ctlTitulo) {
                text(st.title, h * .16f, true, w)?.let { shadowed(it, Offset(x, y), 1f); y += it.size.height }
                text(st.artist, h * .125f, false, w)?.let { shadowed(it, Offset(x, y), .62f); y += it.size.height }
            }
            val slot = u * .036f; val by = r.bottom - slot / 2
            if (p.ctlProgreso) {
                val tl = if (p.ctlTiempos && st.durMs > 0) text(fmtTime(st.posMs), h * .095f, false, w) else null
                val tr = if (p.ctlTiempos && st.durMs > 0) text(fmtTime(st.durMs), h * .095f, false, w) else null
                val py = (y + by - slot / 2) / 2
                val bx0 = x + (tl?.let { it.size.width + h * .06f } ?: 0f); val bx1 = r.right - (tr?.let { it.size.width + h * .06f } ?: 0f)
                tl?.let { shadowed(it, Offset(x, py - it.size.height / 2), .6f) }
                tr?.let { shadowed(it, Offset(r.right - it.size.width, py - it.size.height / 2), .6f) }
                bar("seek", bx0, bx1, py, max(2f, h * .022f), frac)
            }
            val end = buttons(x - slot * .2f, by, slot)
            if (p.ctlVolumen) volume(end + slot * .15f, by, r.right - end - slot * .15f, max(2f, h * .02f))
        }
        else -> {   // píldora y compacta (una tarjeta pequeña con las esquinas menos redondas)
            val compact = p.ctlEstilo == "compacta"
            val h = r.height; val pad = h * .13f; val corner = if (compact) h * .24f else h / 2
            // compacta: sin fondo, como los widgets minimalistas (la carátula, el título y el artista sueltos)
            if (!compact) {
                drawRoundRect(Color.Black, r.topLeft, r.size, CornerRadius(corner), alpha = alpha * p.ctlFondo)
                drawRoundRect(Color.White, r.topLeft, r.size, CornerRadius(corner), alpha = alpha * .08f, style = Stroke(g.d))
            }
            var x = r.left + (if (compact) 0f else pad)
            if (p.ctlCaratula) {
                val s = h - pad * 2
                if (compact) drawRoundRect(Color.Black, Offset(x + g.d * 2, r.top + pad + g.d * 3), Size(s, s), CornerRadius(s * .18f), alpha = alpha * .35f)
                cover(x, r.top + pad, s, if (compact) s * .18f else s / 2); x += s + pad * .9f
            } else x += pad
            val slot = u * (if (compact) .044f else .053f)
            val btnW = slot * (3 + listOf(p.ctlMeGusta, p.ctlAleatorio, p.ctlRepetir).count { it })
            val volW = if (p.ctlVolumen) u * (if (compact) .09f else .11f) else 0f
            val bx = r.right - pad - volW - btnW - (if (p.ctlVolumen) 0f else h * .12f)
            val tw = bx - x - pad * .6f
            if (p.ctlTitulo && tw > h) {
                val tl = text(st.title, h * .21f, true, tw); val al = text(if (times.isEmpty()) st.artist else "${st.artist}  ·  $times", h * .165f, false, tw)
                val th = (tl?.size?.height ?: 0) + (al?.size?.height ?: 0) + (if (p.ctlProgreso) h * .14f else 0f)
                var y = r.center.y - th / 2
                tl?.let { shadowed(it, Offset(x, y), 1f); y += it.size.height }
                al?.let { shadowed(it, Offset(x, y), .6f); y += it.size.height }
                if (p.ctlProgreso) bar("seek", x, x + tw, y + h * .08f, max(2f, h * .035f), frac)
            } else if (p.ctlProgreso && tw > h * .5f) bar("seek", x, x + tw, r.center.y, max(2f, h * .04f), frac)
            val end = buttons(bx, r.center.y, slot)
            if (p.ctlVolumen) volume(end + slot * .15f, r.center.y, volW - slot * .15f, max(2f, h * .035f))
        }
    }
    st.hits = hits
}

/**
 * Vinilo en la esquina de abajo a la izquierda: un disco con la carátula impresa que gira mientras suena (se sale un
 * poco de la pantalla) y, a su lado, los botones, lo que suena y las siguientes de la cola (se pulsan para saltar).
 */
private fun DrawScope.drawVinyl(sc: WallpaperScene, g: Geometry, r: Rect, tm: TextMeasurer, st: WidgetState, ic: WidgetIcons,
                                accent: Color, onAccent: Color, alpha: Float, u: Float, hits: MutableList<Hit>) {
    // el disco: pequeño y metido en la esquina (se sale por la izquierda y un poco por abajo, detrás de la barra de tareas)
    val p = sc.p; val D = min(r.height, u * .25f); val R = D / 2
    val c = Offset(r.left + D * .1f, r.bottom - R * .82f)
    val img = st.cover ?: sc.cover
    // el disco
    val beat = 1 + .012f * clamp(sc.U.kick, 0f, 1f) * (if (sc.playing) 1f else 0f)
    scale(beat, beat, c) {
        drawCircle(Color.Black, R * 1.03f, c + Offset(R * .02f, R * .03f), alpha = alpha * .45f)
        drawCircle(Color(0xFF0B0B0E), R, c, alpha = alpha)
        if (img != null) {
            val clip = pooledPath().apply { addOval(Rect(c, R * .965f)) }
            clipPath(clip) {
                rotate(st.spin * 57.29578f, c) { drawSquare(img, Rect(c, R * .965f), alpha * .92f) }
            }
        }
        // surcos y el brillo (el brillo no gira: es la luz de la habitación)
        var rr = R * .3f
        while (rr < R * .96f) { drawCircle(Color.Black, rr, c, alpha = alpha * .16f, style = Stroke(max(1f, R * .004f))); rr += R * .032f }
        drawCircle(androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = .13f), Color.Transparent, Color.Transparent,
            Color.White.copy(alpha = .1f), Color.Transparent, Color.Transparent), c), R * .97f, c, alpha = alpha)
        drawCircle(Color.White, R * .965f, c, alpha = alpha * .1f, style = Stroke(max(1f, R * .006f)))
        // centro: etiqueta oscura y el agujero
        drawCircle(Color(0xFF15151A), R * .13f, c, alpha = alpha * .9f)
        drawCircle(accent, R * .13f, c, alpha = alpha * .8f, style = Stroke(R * .008f))
        drawCircle(Color.Black, R * .028f, c, alpha = alpha)
    }
    // al lado: botones, lo que suena y la cola
    val x0 = c.x + R + g.H * .014f; val w = r.right - x0
    if (w < u * .08f) return
    val bs = u * .028f; val gapB = bs * .3f
    var y = r.top
    fun shadowText(l: TextLayoutResult, at: Offset, a: Float, col: Color = Color.White) {
        drawText(l, Color.Black, at, alpha = alpha * .45f, drawStyle = Stroke(l.layoutInput.style.fontSize.value * density * .16f, join = StrokeJoin.Round))
        drawText(l, col, at, alpha = alpha * a)
    }
    fun text(s: String, size: Float, bold: Boolean, maxW: Float, spacing: Float = 0f): TextLayoutResult? = if (s.isEmpty() || maxW < size) null else
        tm.measure(ctlText(sc.p, s), TextStyle(fontFamily = WidgetFonts.family(sc.p.ctlFuente), fontWeight = if (bold && sc.p.ctlFuente == "oto") FontWeight.Bold else FontWeight.Normal, fontSize = (size / density).sp, letterSpacing = spacing.em),
            overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW.toInt().coerceAtLeast(1)))
    fun thumb(art: ImageBitmap?, x: Float, yy: Float, s: Float) {
        val shape = pooledPath().apply { addRoundRect(RoundRect(x, yy, x + s, yy + s, CornerRadius(s * .18f))) }
        if (art == null) drawPath(shape, hsl(sc.h1, .35f, .22f), alpha = alpha) else clipPath(shape) { drawSquare(art, Rect(x, yy, x + s, yy + s), alpha) }
    }
    // botones: cuadraditos de cristal
    var bx = x0
    fun btn(id: String, pt: Painter, primary: Boolean = false, on: Boolean? = null) {
        val hov = st.hover == id; val down = st.pressed == id
        val rect = Rect(bx, y, bx + bs, y + bs)
        drawRoundRect(if (primary) accent else Color.Black, rect.topLeft, rect.size, CornerRadius(bs * .28f), alpha = alpha * (if (primary) 1f else .5f))
        if (hov && !primary) drawRoundRect(Color.White, rect.topLeft, rect.size, CornerRadius(bs * .28f), alpha = alpha * .16f)
        val s = bs * .62f * (if (down) .86f else 1f)
        val col = when { primary -> onAccent; on == true -> accent; else -> Color.White }
        translate(rect.center.x - s / 2, rect.center.y - s / 2) { with(pt) { draw(Size(s, s), alpha = alpha * (if (on == false) .55f else .95f), colorFilter = ColorFilter.tint(col)) } }
        hits += Hit(id, rect); bx += bs + gapB
    }
    btn("prev", ic.prev); btn("play", if (st.playing) ic.pause else ic.play, primary = true); btn("next", ic.next)
    if (p.ctlMeGusta) btn("like", if (st.liked) ic.liked else ic.like, on = if (st.liked) true else null)
    if (p.ctlAleatorio) btn("shuffle", ic.shuffle, on = st.shuffle)
    if (p.ctlRepetir) btn("repeat", if (st.loop == 2) ic.repeatOne else ic.repeat, on = st.loop != 0)
    y += bs + u * .016f
    // lo que suena
    text("SONANDO", u * .011f, true, w, .18f)?.let { shadowText(it, Offset(x0, y), 1f, accent); y += it.size.height + u * .006f }
    val ts = u * .042f
    thumb(img, x0, y, ts)
    val tx = x0 + ts + u * .012f; val tw = r.right - tx
    var ty = y + ts * .08f
    text(st.title, u * .017f, true, tw)?.let { shadowText(it, Offset(tx, ty), 1f); ty += it.size.height }
    text(st.artist, u * .0135f, false, tw)?.let { shadowText(it, Offset(tx, ty), .65f) }
    y += ts + u * .012f
    if (p.ctlProgreso) {
        val f = if (st.durMs > 0) clamp(st.posMs.toFloat() / st.durMs, 0f, 1f) else 0f; val th = max(2f, u * .003f)
        val hov = st.hover == "seek" || st.pressed == "seek"; val t = th * (if (hov) 1.5f else 1f)
        drawRoundRect(Color.White, Offset(x0, y - t / 2), Size(w, t), CornerRadius(t / 2), alpha = alpha * .2f)
        drawRoundRect(accent, Offset(x0, y - t / 2), Size(w * f, t), CornerRadius(t / 2), alpha = alpha)
        hits += Hit("seek", Rect(x0, y - max(th * 4f, 7f * g.d), x0 + w, y + max(th * 4f, 7f * g.d)))
        y += u * .008f
        if (p.ctlTiempos && st.durMs > 0) text("${fmtTime(st.posMs)} / ${fmtTime(st.durMs)}", u * .012f, false, w)?.let { shadowText(it, Offset(x0, y), .55f); y += it.size.height }
    }
    // la cola
    if (p.ctlCola) {
        y += u * .012f; val qs = u * .03f
        st.queue.take(p.ctlColaN.coerceIn(1, 5)).forEachIndexed { i, q ->
            if (y + qs > r.bottom) return@forEachIndexed
            val id = "q:${q.index}"; val a = (1f - i * .15f) * (if (st.hover == id) 1f else .8f)
            if (st.hover == id) drawRoundRect(Color.White, Offset(x0 - u * .006f, y - u * .004f), Size(w + u * .006f, qs + u * .008f), CornerRadius(qs * .2f), alpha = alpha * .08f)
            thumb(q.art?.let { st.thumbs[it] }, x0, y, qs)
            val qx = x0 + qs + u * .01f; var qy = y + qs * .06f
            text(q.title, u * .0135f, true, r.right - qx)?.let { shadowText(it, Offset(qx, qy), a); qy += it.size.height }
            text(q.artist, u * .011f, false, r.right - qx)?.let { shadowText(it, Offset(qx, qy), a * .6f) }
            hits += Hit(id, Rect(x0, y, r.right, y + qs)); y += qs + u * .009f
        }
    }
}