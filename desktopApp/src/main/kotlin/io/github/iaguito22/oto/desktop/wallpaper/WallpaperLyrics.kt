package io.github.iaguito22.oto.desktop.wallpaper

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp
import io.github.iaguito22.oto.lyrics.Lyrics
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

internal class LWord(val w: String, val t: Float)
internal class LLine(val t: Float, val end: Float, val words: List<LWord>)

/** De la letra sincronizada de Oto (milisegundos) a frases con sus palabras (segundos). */
internal fun lyricLines(l: Lyrics?): List<LLine> = (l as? Lyrics.Synced)?.lines.orEmpty().mapNotNull { line ->
    val words = line.words.filter { it.text.isNotBlank() }.map { LWord(it.text.trim(), it.start / 1000f) }
    if (words.isEmpty()) null else LLine(line.start / 1000f, line.end / 1000f, words)
}

internal val rubik = FontFamily(Font(resource = "fonts/rubik.ttf", weight = FontWeight.Normal), Font(resource = "fonts/rubik.ttf", weight = FontWeight.Bold))
internal val jakarta = FontFamily(Font(resource = "fonts/PlusJakartaSans-800.ttf", weight = FontWeight.ExtraBold))
/** Familias de la letra: «anton» y «archivo» → Plus Jakarta muy gruesa; «grotesk» → Rubik; «serif» → la serif del sistema. */
private fun fontOf(f: String): Triple<FontFamily, FontWeight, FontStyle> = when (f) {
    "anton", "archivo" -> Triple(jakarta, FontWeight.ExtraBold, FontStyle.Normal)
    "serif" -> Triple(FontFamily.Serif, FontWeight.SemiBold, FontStyle.Italic)
    "light" -> Triple(rubik, FontWeight.Normal, FontStyle.Normal)
    else -> Triple(rubik, FontWeight.Bold, FontStyle.Normal)
}

private class Role(val f: String, val sc: Float, val upper: Boolean, val sp: Float)
private class LStyle(val hero: Role, val normal: Role, val small: Role, val treats: List<String>, val enter: String, val tilt: Float)
private val STYLES = mapOf(
    "editorial" to LStyle(Role("anton", 1.9f, true, .01f), Role("grotesk", 1f, false, 0f), Role("serif", .8f, false, 0f), listOf("fill", "outline", "box"), "rise", .02f),
    "electronica" to LStyle(Role("grotesk", 1.7f, true, .12f), Role("grotesk", .95f, true, .06f), Role("light", .62f, false, .02f), listOf("fill", "outline", "glitch"), "zoom", 0f),
    "rock" to LStyle(Role("anton", 2.1f, true, .01f), Role("anton", 1.05f, true, .01f), Role("serif", .75f, false, 0f), listOf("fill", "box", "outline"), "slam", .05f),
    "hiphop" to LStyle(Role("archivo", 1.75f, true, 0f), Role("archivo", .95f, true, 0f), Role("grotesk", .7f, false, 0f), listOf("box", "fill", "shadow"), "slam", .04f),
    "indie" to LStyle(Role("serif", 1.85f, false, 0f), Role("serif", 1f, false, 0f), Role("light", .7f, false, .02f), listOf("fill", "outline"), "float", 0f),
)
private val STOP = ("a al de del el la las los lo y o u e en un una unos unas que se me te le les mi mis tu tus su sus por con sin para es no ya si ni nos os muy mas más " +
    "the an of to in on and or i you me my it is be at for so oh ah im i'm your we our").split(' ').toSet()
private val COMPOS = listOf("izquierda", "derecha", "escalera", "centro")

private class PWord(val w: LWord, val k: Int, val role: String, val f: String, var size: Float, val txt: String, val sp: Float, var wpx: Float,
                    val treat: String, val seed: Float, var x: Float = 0f, var y: Float = 0f, var layout: TextLayoutResult? = null)
private class PLine(val words: List<PWord>, val key: String, val zone: Zone, val tilt: Float, val cx: Float, val cy: Float)

/**
 * La letra del fondo: palabras cinéticas en los huecos libres alrededor del visualizador (nunca encima: se coloca en
 * zonas libres y además se recorta con el círculo), o la letra «sobria» tipo karaoke.
 */
internal class WallpaperLyrics {
    var lines: List<LLine> = emptyList(); private set
    private var cache = HashMap<Int, PLine?>()
    private var prevCompo = ""; private var prevZone = ""
    private var soberLine = -1; private var soberSince = 0f; private var soberZone: Zone? = null
    /** Lo medido de las frases del modo sobrio (antes se medía cada palabra en cada cuadro). */
    private val soberCache = HashMap<Triple<List<String>, androidx.compose.ui.unit.TextUnit, Float>, Pair<List<List<Pair<String, TextLayoutResult>>>, Float>>()
    private var layoutG: Geometry? = null; private var layoutTam = 0f; private var layoutStyle = ""; private var layoutPos = ""
    private val rnd = Random(3)
    /** Caja de todo lo que se ha pintado en el último cuadro (para la sombra de detrás). */
    var box: Rect? = null; private set
    private var boxSmooth: Rect? = null

    fun set(newLines: List<LLine>) { if (newLines !== lines) { lines = newLines; reset() } }
    fun reset() { cache = HashMap(); prevCompo = ""; prevZone = ""; soberLine = -1; soberCache.clear() }

    private fun styleKey(p: WallpaperSettings, energy: Float) =
        if (p.estiloLetra == "auto") (if (energy > .55f) "electronica" else "editorial") else p.estiloLetra

    private fun pickZone(i: Int, g: Geometry, p: WallpaperSettings): Zone? {
        val zs = g.zones; if (zs.isEmpty()) return null
        if (p.letraPos != "auto") return zs.find { it.id == p.letraPos } ?: zs.maxBy { it.w * it.h }
        var cand = lyricCandidates(zs)
        if (cand.size > 1) cand = cand.filter { it.id != prevZone }.ifEmpty { cand }
        return cand[(h01(i, 11) * cand.size).toInt().coerceIn(0, cand.size - 1)]
    }

    private fun style(f: String, sizePx: Float, density: Float, sp: Float): TextStyle {
        val (fam, wt, st) = fontOf(f)
        return TextStyle(fontFamily = fam, fontWeight = wt, fontStyle = st, fontSize = (sizePx / density).sp, letterSpacing = (sizePx * sp / density).sp)
    }

    private fun layoutLine(i: Int, g: Geometry, p: WallpaperSettings, energy: Float, tm: TextMeasurer, density: Float): PLine? {
        val line = lines[i]; val key = styleKey(p, energy); val st = STYLES[key] ?: STYLES.getValue("editorial")
        val base = min(g.W, g.H) * .05f * p.tamLetra
        val plain = line.words.map { it.w.lowercase().filter { c -> c.isLetterOrDigit() || c == '\'' } }
        val score = plain.mapIndexed { k, w -> if (w in STOP) -1f else w.length + (if (k == plain.size - 1) 2 else 0) + h01(i, k) * 1.5f }
        val heroIdx = score.indices.sortedByDescending { score[it] }.take(if (plain.size >= 7) 2 else 1).filter { score[it] >= 0 }
        val treatHero = st.treats[(h01(i, 9) * st.treats.size).toInt().coerceIn(0, st.treats.size - 1)]
        val words = line.words.mapIndexed { k, w ->
            val role = if (k in heroIdx) "hero" else if ((plain[k] in STOP || plain[k].length <= 2) && plain.size > 2) "small" else "normal"
            val r = when (role) { "hero" -> st.hero; "small" -> st.small; else -> st.normal }
            val size = base * r.sc * (if (role == "hero" && plain[k].length > 9) .8f else 1f); val txt = if (r.upper) w.w.uppercase() else w.w
            val lay = tm.measure(txt, style(r.f, size, density, r.sp))
            PWord(w, k, role, r.f, size, txt, r.sp, lay.size.width.toFloat(), if (role == "hero") treatHero else "fill", h01(i, k + 20), layout = lay)
        }
        val zone = pickZone(i, g, p) ?: return null
        prevZone = zone.id
        val maxW = zone.w * .94f; var gap = base * .3f
        val rows = ArrayList<MutableList<PWord>>(); var row = ArrayList<PWord>(); var rw = 0f
        for (w in words) {
            if (w.role == "hero") { if (row.isNotEmpty()) rows += row; rows += mutableListOf(w); row = ArrayList(); rw = 0f; continue }
            if (row.isNotEmpty() && rw + w.wpx > maxW) { rows += row; row = ArrayList(); rw = 0f }
            row += w; rw += w.wpx + gap
        }
        if (row.isNotEmpty()) rows += row
        fun rowW(r: List<PWord>) = r.sumOf { it.wpx.toDouble() }.toFloat() + gap * (r.size - 1)
        fun rowH(r: List<PWord>) = r.maxOf { it.size } * (if (r[0].role == "hero") 1f else 1.2f)
        val k = min(1f, min(maxW / rows.maxOf { rowW(it) }, zone.h * .9f / rows.sumOf { rowH(it).toDouble() }.toFloat()))
        if (k < 1f) {
            for (w in words) { w.size *= k; w.layout = tm.measure(w.txt, style(w.f, w.size, density, w.sp)); w.wpx = w.layout!!.size.width.toFloat() }
            gap *= k
        }
        val blockH = rows.sumOf { rowH(it).toDouble() }.toFloat()
        var c = (h01(i, 3) * COMPOS.size).toInt().coerceIn(0, COMPOS.size - 1); if (COMPOS[c] == prevCompo) c = (c + 1) % COMPOS.size
        val compo = COMPOS[c]; prevCompo = compo
        val yc = if (zone.side) g.cy + (h01(i, 5) - .5f) * min(zone.h, g.H * .55f) else zone.y0 + zone.h / 2 + (h01(i, 5) - .5f) * (zone.h - blockH)
        val y0 = clamp(yc - blockH / 2, zone.y0, max(zone.y0, zone.y1 - blockH))
        var y = y0; val pad = zone.w * .03f
        rows.forEachIndexed { ri, r ->
            val h = rowH(r); val tot = rowW(r)
            var x = when (compo) { "izquierda" -> zone.x0 + pad; "derecha" -> zone.x1 - pad - tot; "escalera" -> zone.x0 + pad + ri * zone.w * .06f; else -> zone.x0 + zone.w / 2 - tot / 2 }
            x = clamp(x, zone.x0 + pad, max(zone.x0 + pad, zone.x1 - pad - tot))
            for (w in r) { w.x = x + w.wpx / 2; w.y = y + h / 2; x += w.wpx + gap }
            y += h
        }
        return PLine(words, key, zone, (h01(i, 7) - .5f) * 2 * st.tilt, zone.x0 + zone.w / 2, y0 + blockH / 2)
    }

    /** Pinta la letra en el instante [t] (segundos). */
    fun DrawScope.draw(sc: WallpaperScene, t: Float, tm: TextMeasurer) {
        val g = sc.G ?: return; val p = sc.p
        if (!p.letra || lines.isEmpty()) { box = null; return }
        // otra geometría (tamaño, círculo, huecos) u otro estilo de letra: se vuelve a colocar todo
        if (g !== layoutG || p.tamLetra != layoutTam || p.estiloLetra != layoutStyle || p.letraPos != layoutPos) {
            layoutG = g; layoutTam = p.tamLetra; layoutStyle = p.estiloLetra; layoutPos = p.letraPos; reset()
        }
        var bx0 = Float.MAX_VALUE; var by0 = Float.MAX_VALUE; var bx1 = -Float.MAX_VALUE; var by1 = -Float.MAX_VALUE
        val addBox: (Float, Float, Float, Float) -> Unit = { x, y, hw, hh -> bx0 = min(bx0, x - hw); bx1 = max(bx1, x + hw); by0 = min(by0, y - hh); by1 = max(by1, y + hh) }
        // recorte: la letra no puede pintarse nunca sobre el visualizador
        val clip = pooledPath().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, g.W, g.H)); addOval(Rect(Offset(g.cx, g.cy), g.ex))
            if (g.barsTop < g.H) addRect(Rect(0f, g.barsTop, g.W, g.H))
        }
        clipPath(clip) {
            if (p.estiloLetra == "sobria") drawSober(sc, g, p, t, tm, addBox)
            else for (i in lines.indices) {
                val l = lines[i]
                // la frase que viene se mide antes de tiempo: medir todas sus palabras de golpe al aparecer costaba un cuadro
                if (t < l.t - .05f) { if (t > l.t - .8f) cache.getOrPut(i) { layoutLine(i, g, p, sc.U.energy, tm, density) }; continue }
                if (t > l.end + 1.2f) continue
                val L = cache.getOrPut(i) { layoutLine(i, g, p, sc.U.energy, tm, density) } ?: continue
                for (w in L.words) drawWord(sc, g, p, w, L, t, l.end, false, addBox)
            }
        }
        val raw = if (bx0 < bx1) Rect(bx0, by0, bx1, by1) else null
        // la sombra sigue a la letra con suavidad
        boxSmooth = when {
            raw == null -> null
            boxSmooth == null -> raw
            else -> { val b = boxSmooth!!; val k = .15f; Rect(lerp(b.left, raw.left, k), lerp(b.top, raw.top, k), lerp(b.right, raw.right, k), lerp(b.bottom, raw.bottom, k)) }
        }
        box = boxSmooth
    }

    private fun DrawScope.drawWord(sc: WallpaperScene, g: Geometry, p: WallpaperSettings, w: PWord, L: PLine, t: Float, exitAt: Float, explode: Boolean,
                                   addBox: (Float, Float, Float, Float) -> Unit) {
        val st = STYLES[L.key] ?: STYLES.getValue("editorial"); val a = t - w.w.t; if (a < 0f) return
        val lay = w.layout ?: return; val U = sc.U
        val hero = w.role == "hero"
        var sx = 1f; var dx = 0f; var dy = 0f; var rot = 0f; var alpha: Float; var shake = 0f
        when (st.enter) {
            "rise" -> { val s = spring(a, 10f, 14f); dy = (1 - s) * w.size * .6f; alpha = clamp(a * 7, 0f, 1f); if (hero) sx = lerp(.55f, 1f, s) }
            "zoom" -> { val s = spring(a, 14f, 7f); sx = lerp(if (hero) 1.7f else 1.3f, 1f, s); alpha = clamp(a * 6, 0f, 1f) }
            "slam" -> { val s = clamp(a * (if (hero) 8 else 12), 0f, 1f); sx = lerp(if (hero) 1.9f else 1.4f, 1f, 1 - (1 - s).pow(3)); alpha = s; shake = if (a < .15f && hero) 8f else 0f }
            else -> { val s = 1 - exp(-a * 4); dy = (1 - s) * w.size * .4f; alpha = s }
        }
        if (w.role == "small") sx = min(sx, 1.15f)
        if (hero) sx *= 1 + .05f * U.kick
        if (st.enter == "slam") shake += U.kick * 2
        val e = t - exitAt - w.k * .03f
        if (e > 0f) {
            val q = clamp(e / (if (explode) .9f else .5f), 0f, 1f); if (q >= 1f) return
            if (explode) { val ox = w.x - g.cx; val oy = w.y - g.cy; val l = hypot(ox, oy) + 1; dx += ox / l * q * q * g.W * .4f; dy += oy / l * q * q * g.W * .4f; sx *= 1 + q * 1.2f; rot += (w.seed - .5f) * q * 2 }
            else { dy -= q * w.size * .45f; sx *= 1 + q * .12f }
            alpha *= (1 - q) * (1 - q)
        } else addBox(w.x, w.y, w.wpx * sx / 2, w.size * sx * .55f)
        if (alpha <= .002f) return
        val cs = cos(L.tilt); val sn = sin(L.tilt); val rx = w.x - L.cx; val ry = w.y - L.cy
        val px = L.cx + rx * cs - ry * sn; val py = L.cy + rx * sn + ry * cs
        val accent = hsl(sc.h1, if (sc.greyCover) 0f else min(.9f, sc.sat + .1f), .68f); val glowC = hsl(sc.h1, if (sc.greyCover) 0f else .9f, .6f)
        val tl = Offset(-lay.size.width / 2f, -lay.size.height / 2f)
        translate(px + dx + (rnd.nextFloat() - .5f) * shake, py + dy + (rnd.nextFloat() - .5f) * shake) {
            rotate((L.tilt + rot) * 57.2958f, Offset.Zero) { scale(sx, sx, Offset.Zero) {
                when (w.treat) {
                    "box" -> { val pad = w.size * .14f
                        drawRect(accent, Offset(-w.wpx / 2 - pad, -w.size * .52f), Size(w.wpx + pad * 2, w.size * 1.04f), alpha = alpha)
                        drawText(lay, Color(0xFF0B0A12), tl, alpha = alpha) }
                    "outline" -> {
                        drawText(lay, glowC, tl, alpha = alpha * .3f, drawStyle = Stroke(max(4f, w.size * .13f), join = StrokeJoin.Round))
                        drawText(lay, Color.White, tl, alpha = alpha, drawStyle = Stroke(max(1.5f, w.size * .03f), join = StrokeJoin.Round)) }
                    "shadow" -> { drawText(lay, accent, tl + Offset(w.size * .06f, w.size * .06f), alpha = alpha); drawText(lay, Color.White, tl, alpha = alpha) }
                    else -> {
                        if (w.treat == "glitch" && U.snare > .5f) {
                            drawText(lay, Color(255, 40, 90), tl + Offset(-5 * U.snare, 0f), alpha = alpha * .85f, blendMode = BlendMode.Plus)
                            drawText(lay, Color(40, 220, 255), tl + Offset(5 * U.snare, 0f), alpha = alpha * .85f, blendMode = BlendMode.Plus)
                        }
                        // contorno oscuro suave para que se lea sobre cualquier fondo de pantalla
                        drawText(lay, Color.Black, tl, alpha = alpha * (.18f + .3f * p.sombra), drawStyle = Stroke(w.size * (if (hero) .16f else .13f), join = StrokeJoin.Round))
                        drawText(lay, if (hero && w.treat == "fill") accent else if (w.role == "small") Color.White.copy(alpha = .8f) else Color.White, tl, alpha = alpha)
                    }
                }
            } }
        }
    }

    /** Estilo sobrio: la frase actual con las palabras encendiéndose y la siguiente en pequeño. */
    private fun DrawScope.drawSober(sc: WallpaperScene, g: Geometry, p: WallpaperSettings, t: Float, tm: TextMeasurer, addBox: (Float, Float, Float, Float) -> Unit) {
        var cur = -1
        for (i in lines.indices) if (lines[i].t <= t + .05f) cur = i else break
        if (cur < 0) return
        if (cur != soberLine) { soberLine = cur; soberSince = t; soberZone = pickZone(cur, g, p); soberZone?.let { prevZone = it.id } }
        val z = soberZone ?: return
        val L = lines[cur]; val base = min(g.W, g.H) * .036f * p.tamLetra; val maxW = z.w * .9f
        val age = t - soberSince; val inA = spring(age, 9f, 10f); val ended = t > L.end + .6f
        val s1 = TextStyle(fontFamily = rubik, fontWeight = FontWeight.Bold, fontSize = (base / density).sp)
        val s2 = TextStyle(fontFamily = rubik, fontWeight = FontWeight.Normal, fontSize = (base * .62f / density).sp)
        fun wrap(words: List<String>, style: TextStyle): Pair<List<List<Pair<String, TextLayoutResult>>>, Float> = soberCache.getOrPut(Triple(words, style.fontSize, maxW)) {
            val sp = tm.measure(" ", style).size.width.toFloat()
            val rows = ArrayList<List<Pair<String, TextLayoutResult>>>(); var row = ArrayList<Pair<String, TextLayoutResult>>(); var rw = 0f
            for (w in words) { val lay = tm.measure(w, style); val wd = lay.size.width.toFloat(); if (row.isNotEmpty() && rw + wd > maxW) { rows += row; row = ArrayList(); rw = 0f }; row += w to lay; rw += wd + sp }
            if (row.isNotEmpty()) rows += row
            rows to sp
        }
        val (rows1, sp1) = wrap(L.words.map { it.w }, s1)
        val nx = lines.getOrNull(cur + 1); val rows2 = nx?.let { wrap(listOf(it.words.joinToString(" ") { w -> w.w }), s2).first }.orEmpty()
        val h1 = rows1.size * base * 1.25f; val h2 = rows2.size * base * .62f * 1.3f; val tot = h1 + base * .5f + h2
        var y = clamp((if (z.side) g.cy else z.y0 + z.h / 2) - tot / 2, z.y0, max(z.y0, z.y1 - tot)) + (1 - inA) * base * .8f
        fun alignX(rw: Float) = when (z.id) { "izquierda" -> z.x1 - z.w * .05f - rw; "derecha" -> z.x0 + z.w * .05f; else -> z.x0 + z.w / 2 - rw / 2 }
        val fade = if (ended) clamp(1 - (t - L.end - .6f) / .5f, 0f, 1f) else 1f
        val shadowA = .18f + .3f * p.sombra
        var wi = 0
        for (r in rows1) {
            val rw = r.sumOf { it.second.size.width.toDouble() }.toFloat() + sp1 * (r.size - 1); var x = alignX(rw)
            for ((_, lay) in r) {
                val wt = L.words[wi++].t; val lit = clamp((t - wt) / .18f, 0f, 1f)
                val a = clamp(inA, 0f, 1f) * fade * (.42f + .58f * lit); val yy = y + base * .62f - lit * base * .05f - lay.size.height / 2f
                drawText(lay, Color.Black, Offset(x, yy), alpha = a * shadowA, drawStyle = Stroke(base * .14f, join = StrokeJoin.Round))
                drawText(lay, if (lit > .5f) Color.White else hsl(sc.h1, .25f, .85f), Offset(x, yy), alpha = a)
                addBox(x + lay.size.width / 2f, y + base * .62f, lay.size.width / 2f, base * .6f)
                x += lay.size.width + sp1
            }
            y += base * 1.25f
        }
        y += base * .5f
        for (r in rows2) {
            val lay = r[0].second; val x = alignX(lay.size.width.toFloat()); val a = .55f * clamp(inA, 0f, 1f)
            drawText(lay, Color.Black, Offset(x, y), alpha = a * shadowA, drawStyle = Stroke(base * .1f, join = StrokeJoin.Round))
            drawText(lay, Color.White, Offset(x, y), alpha = a)
            addBox(x + lay.size.width / 2f, y + base * .4f, lay.size.width / 2f, base * .4f)
            y += base * .62f * 1.3f
        }
    }
}

/**
 * Las zonas donde puede ir una línea (en «automático»): las que no son mucho más pequeñas que la mayor (45 %). Los lados
 * (izquierda y derecha) bastan con el 30 %: con el vinilo y la hora la izquierda se quedaba en el 44 % y nunca salía.
 * Bajarlo para todas metía la franja estrecha de debajo del círculo y Iago prefería las posiciones de antes (24/11).
 */
internal fun lyricCandidates(zs: List<Zone>): List<Zone> {
    fun area(z: Zone) = z.w * z.h * (if (z.side) 1.6f else 1f)
    val best = zs.maxOfOrNull(::area) ?: return zs
    return zs.filter { area(it) > best * (if (it.side) .3f else .45f) }.ifEmpty { zs }
}
