package io.github.iaguito22.oto.desktop.wallpaper

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TAU = (2 * PI).toFloat()

/**
 * Trazados reutilizados: cada cuadro necesitaba decenas de Path nuevos (memoria nativa que tarda en liberarse y hacía
 * crecer la RAM cientos de MB). Se dibujan al momento, así que basta un anillo grande que se va reciclando.
 */
private val pathRing = Array(128) { Path() }
private var pathIdx = 0
internal fun pooledPath(): Path {
    val p = pathRing[pathIdx]; pathIdx = (pathIdx + 1) % pathRing.size
    p.reset(); p.fillType = androidx.compose.ui.graphics.PathFillType.NonZero
    return p
}
private val PLUS = BlendMode.Plus

/** Una imagen recortada al cuadrado central y pintada en [dst]. */
internal fun DrawScope.drawSquare(img: ImageBitmap, dst: Rect, alpha: Float = 1f) {
    val s = min(img.width, img.height)
    drawImage(img, IntOffset((img.width - s) / 2, (img.height - s) / 2), IntSize(s, s),
        IntOffset(dst.left.roundToInt(), dst.top.roundToInt()), IntSize(dst.width.roundToInt(), dst.height.roundToInt()), alpha = alpha, filterQuality = FilterQuality.Medium)
}

/** Tu fondo de pantalla de siempre, colocado como lo coloca Windows. */
internal fun DrawScope.drawWallpaperImage(img: ImageBitmap, fit: String) {
    val W = size.width; val H = size.height; val iw = img.width.toFloat(); val ih = img.height.toFloat()
    if (fit == "mosaico") {
        var y = 0f; while (y < H) { var x = 0f; while (x < W) { drawImage(img, Offset(x, y)); x += iw }; y += ih }
        return
    }
    val k = when (fit) { "ajustar" -> min(W / iw, H / ih); "centrar" -> 1f; "expandir" -> -1f; else -> max(W / iw, H / ih) }
    if (k < 0) { drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(W.roundToInt(), H.roundToInt()), filterQuality = FilterQuality.Medium); return }
    val w = iw * k; val h = ih * k
    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(((W - w) / 2).roundToInt(), ((H - h) / 2).roundToInt()),
        IntSize(w.roundToInt(), h.roundToInt()), filterQuality = FilterQuality.Medium)
}

/** Lo de detrás: color de fondo, oscurecer, sombra, luz del bajo, viñeta, barras de abajo, láseres, partículas y ondas. */
internal fun DrawScope.drawBack(sc: WallpaperScene, lyricBox: Rect?) {
    val g = sc.G ?: return; val p = sc.p; val U = sc.U
    when (p.fondo) {
        "degradado" -> drawRect(Brush.radialGradient(listOf(hsl(sc.h1, sc.sat * .8f, .22f), hsl(sc.h2, sc.sat * .7f, .09f), hsl(sc.h2, sc.sat * .5f, .03f)),
            Offset(g.cx, g.cy), (size.width * size.width + size.height * size.height).pow(.5f) * .7f))
        "color" -> drawRect(parseColor(p.colorFondo))
        "negro" -> drawRect(Color.Black)
    }
    if (p.oscurecer > 0f && p.fondo != "negro") drawRect(Color.Black.copy(alpha = p.oscurecer))
    // sombra suave detrás del círculo y de la letra: se lee sobre cualquier fondo sin taparlo
    if (p.sombra > 0f) {
        val e = g.ex * 1.9f
        if (p.visualizador) drawCircle(Brush.radialGradient(0f to Color.Black.copy(alpha = .55f * p.sombra), .45f to Color.Black.copy(alpha = .41f * p.sombra),
            .75f to Color.Black.copy(alpha = .16f * p.sombra), 1f to Color.Transparent, center = Offset(g.cx, g.cy), radius = e), e, Offset(g.cx, g.cy))
        lyricBox?.let { b ->
            val rx = b.width / 2 + g.H * .06f; val ry = b.height / 2 + g.H * .05f
            translate(b.center.x, b.center.y) { scale(rx, ry, Offset.Zero) {
                drawCircle(Brush.radialGradient(0f to Color.Black.copy(alpha = .42f * p.sombra), .6f to Color.Black.copy(alpha = .22f * p.sombra), 1f to Color.Transparent,
                    center = Offset.Zero, radius = 1f), 1f, Offset.Zero)
            } }
        }
    }
    if (!p.visualizador) { drawLasers(sc, g); drawParticles(sc, g); return }
    // el bajo tiñe la luz alrededor del círculo
    val own = if (p.fondo == "actual") .55f else 1f; val rr = g.R * (if (p.fondo == "actual") 3.2f else 5f)
    val glowA = clamp(own * (.18f + .22f * U.bass + .2f * U.build), 0f, 1f)
    drawCircle(Brush.radialGradient(0f to Color.Transparent, (g.R / rr) to hsl(sc.h1, sc.sat, .5f, glowA), (g.R / rr + (1 - g.R / rr) * .4f) to hsl(sc.h1, sc.sat, .5f, glowA * .4f),
        1f to Color.Transparent, center = Offset(g.cx, g.cy), radius = rr), rr, Offset(g.cx, g.cy), blendMode = PLUS)
    if (p.vineta > 0f) drawRect(Brush.radialGradient(0f to Color.Transparent, .55f to Color.Transparent, 1f to Color.Black.copy(alpha = .75f * p.vineta),
        center = center, radius = (size.width * size.width + size.height * size.height).pow(.5f) * .6f))
    if (p.estilo == "barras") drawBottomBars(sc, g)
    drawLasers(sc, g)
    drawParticles(sc, g)
    // ondas del bombo
    if (p.anillosBombo) for (r in sc.kickRings) {
        val age = r[0]; val a = (1 - age / 1.1f).pow(2) * .7f * r[1] * p.kick
        if (a > .01f) drawCircle(hsl(sc.h1, sc.sat, .7f, a), g.Rout * sc.pulse * (1 + age * .9f), Offset(g.cx + sc.shakeX, g.cy + sc.shakeY), style = Stroke(1.6f * g.d))
    }
    // malla NCS sin carátula: el interior oscurece el fondo, como en los vídeos
    if (p.estilo == "malla" && p.dentro == "esfera") {
        val Rs = g.R * 1.28f * sc.pulse
        drawCircle(Brush.radialGradient(0f to Color.Black.copy(alpha = .5f), .85f to Color.Black.copy(alpha = .62f), 1f to Color.Black.copy(alpha = .25f),
            center = Offset(g.cx + sc.shakeX, g.cy + sc.shakeY), radius = Rs), Rs, Offset(g.cx + sc.shakeX, g.cy + sc.shakeY))
    }
}

internal fun parseColor(hex: String): Color = runCatching { Color(0xFF000000 or hex.removePrefix("#").toLong(16)) }.getOrDefault(Color(0xFF120F1C))

private fun DrawScope.drawLasers(sc: WallpaperScene, g: Geometry) {
    if (sc.laserOn < .01f) return
    val c = hsl(sc.h2 + .05f, 1f, .6f)
    for (k in 0..1) {
        val path = pooledPath()
        for (i in 0 until 8) {
            val a = -PI.toFloat() / 2 + (i - 3.5f) * .16f + sc.laserRot + (if (i % 2 == 1) 0f else PI.toFloat()); val r0 = g.Rout * 1.02f
            path.moveTo(g.cx + cos(a) * r0, g.cy + sin(a) * r0); path.lineTo(g.cx + cos(a) * g.W * 1.5f, g.cy + sin(a) * g.W * 1.5f)
        }
        drawPath(path, c, alpha = clamp(sc.laserOn * (if (k == 1) .9f else .12f) * (.45f + sc.U.hat * .8f), 0f, 1f),
            style = Stroke((if (k == 1) 1.4f else 7f) * g.d), blendMode = PLUS)
    }
}

private fun DrawScope.drawParticles(sc: WallpaperScene, g: Geometry) {
    val p = sc.p; if (!p.particulas) return
    val ty = p.tipoPart
    val cols = arrayOf(hsl(sc.h1, sc.sat * .7f, .75f), hsl(sc.h2, sc.sat * .7f, .8f))
    val alphas = floatArrayOf(.25f, .55f, .9f)
    for (ci in 0..1) for (ab in 0..2) {
        val path = pooledPath(); var any = false
        for (i in 0 until WallpaperScene.PM) {
            if (sc.life[i] <= 0f || sc.pc[i].toInt() != ci) continue
            if (ty == "estrellas") {
                val z = sc.pz[i]; val x = g.cx + sc.px[i] / z; val y = g.cy + sc.py[i] / z
                if (x < -50 || y < -50 || x > g.W + 50 || y > g.H + 50) { sc.life[i] = 0f; continue }
                val fin = clamp((4 - z) / 1.2f, 0f, 1f) * clamp((z - .3f) / .4f, 0f, 1f)
                if (min(2, floor(fin * 3).toInt()) != ab) continue; any = true
                val zp = z + .05f + .25f * sc.audio.punch
                path.moveTo(g.cx + sc.px[i] / zp, g.cy + sc.py[i] / zp); path.lineTo(x, y)
                continue
            }
            val fin = sc.life[i] / sc.maxLife[i]
            if (min(2, floor(fin * 3).toInt()) != ab) continue; any = true
            if (ty == "polvo") path.addOval(Rect(Offset(sc.px[i], sc.py[i]), sc.psz[i]))
            else { path.moveTo(sc.px[i], sc.py[i]); path.lineTo(sc.px[i] - sc.pvx[i] * .035f, sc.py[i] - sc.pvy[i] * .035f) }
        }
        if (!any) continue
        val a = alphas[ab] * (if (ty == "polvo") .7f else 1f)
        if (ty == "polvo") drawPath(path, cols[ci], alpha = a, blendMode = PLUS)
        else drawPath(path, cols[ci], alpha = a, style = Stroke((if (ty == "estrellas") floatArrayOf(1f, 1.6f, 2.4f)[ab] else 1.3f) * g.d * p.tamPart, cap = StrokeCap.Round), blendMode = PLUS)
    }
}

private fun DrawScope.drawBottomBars(sc: WallpaperScene, g: Geometry) {
    val p = sc.p; val n = p.barras; val W = g.W * .92f; val x0 = (g.W - W) / 2; val base = g.H - g.task - g.H * .012f; val Hm = g.H * .2f * p.largo; val bw = W / n
    val brush = Brush.verticalGradient(listOf(hsl(sc.h2, sc.sat, .66f), hsl(sc.h1, sc.sat, .5f)), startY = base - Hm, endY = base)
    val bars = pooledPath(); val caps = pooledPath()
    for (i in 0 until n) {
        val q = (i + .5f) / n; val u = if (p.espejo) abs(q * 2 - 1) else q
        val h = max(g.d * 2, min(1.3f, sc.specAt(sc.audio.spec, u)) * Hm); val pk = min(1.35f, sc.specAt(sc.audio.peak, u)) * Hm
        bars.addRoundRect(RoundRect(x0 + i * bw + bw * .18f, base - h, x0 + i * bw + bw * .82f, base, CornerRadius(bw * .32f)))
        caps.addRoundRect(RoundRect(x0 + i * bw + bw * .18f, base - pk - bw * .9f, x0 + i * bw + bw * .82f, base - pk - bw * .5f, CornerRadius(bw * .2f)))
    }
    if (p.resplandor > 0f) drawPath(bars, brush, alpha = .25f * p.resplandor, style = Stroke(bw * .55f, join = StrokeJoin.Round))
    drawPath(bars, brush); drawPath(caps, Color.White.copy(alpha = .8f))
}

/** Curva suave que pasa por los puntos medios (pts = x0,y0,x1,y1…). */
private fun closedPath(pts: FloatArray, k: Float = 1f): Path {
    val n = pts.size / 2; val path = pooledPath()
    path.moveTo((pts[(n - 1) * 2] + pts[0]) / 2 * k, (pts[(n - 1) * 2 + 1] + pts[1]) / 2 * k)
    for (i in 0 until n) { val j = (i + 1) % n; path.quadraticTo(pts[i * 2] * k, pts[i * 2 + 1] * k, (pts[i * 2] + pts[j * 2]) / 2 * k, (pts[i * 2 + 1] + pts[j * 2 + 1]) / 2 * k) }
    path.close(); return path
}

private fun ringPts(sc: WallpaperScene, n: Int, rot: Float, rf: (u: Float, q: Float) -> Float): FloatArray {
    val pts = FloatArray(n * 2)
    for (i in 0 until n) { val q = i.toFloat() / n; val a = PI.toFloat() / 2 + q * TAU + rot; val r = rf(sc.uOf(q), q); pts[i * 2] = cos(a) * r; pts[i * 2 + 1] = sin(a) * r }
    return pts
}

private fun conic(sc: WallpaperScene, l: Float = .62f): Brush =
    if (sc.p.arcoiris) Brush.sweepGradient(List(7) { hsl(sc.h1 + it / 6f, sc.sat, l) }, Offset.Zero)
    else Brush.sweepGradient(listOf(hsl(sc.h1, sc.sat, l), hsl(sc.h2, sc.sat, l + .04f), hsl(sc.h1, sc.sat, l)), Offset.Zero)

/**
 * El visualizador, con el origen en el centro del círculo. [bloom] = la pasada que se difumina y se suma (el brillo):
 * se pinta más gruesa y sin detalles.
 */
internal fun DrawScope.drawViz(sc: WallpaperScene, bloom: Boolean) {
    val g = sc.G ?: return; val p = sc.p; val U = sc.U; val a = sc.audio
    if (!p.visualizador) return
    val rot = sc.vtime * p.giro * .25f
    translate(g.cx + sc.shakeX, g.cy + sc.shakeY) { scale(sc.pulse, sc.pulse, Offset.Zero) { rotate(0f, Offset.Zero) {
        when (p.estilo) {
            "malla" -> drawMallaBand(sc, g, bloom)
            "ncs" -> {
                val R = g.R * 1.01f; val L = g.R * 1.15f * p.largo
                val cols = listOf(Color.White.copy(alpha = .93f), hsl(sc.h1, sc.sat * .5f, .8f), hsl(sc.h1, sc.sat, .66f), hsl(hueLerp(sc.h1, sc.h2, .5f), sc.sat, .6f), hsl(sc.h2, sc.sat, .56f), hsl(sc.h2 + .04f, sc.sat, .45f))
                val layers = arrayOf(intArrayOf(0, 0), intArrayOf(2, 1), intArrayOf(4, 1), intArrayOf(6, 2), intArrayOf(9, 2), intArrayOf(12, 3)); val ex = floatArrayOf(1.5f, 1.4f, 1.3f, 1.2f, 1.1f, 1f)
                for (s in layers.indices.reversed()) {
                    val arr = smoothInto(sc.tmpA, a.histAt(layers[s][0]), layers[s][1])
                    val path = closedPath(ringPts(sc, if (bloom) 96 else 192, rot) { u, _ -> R + g.d * 2 * s + min(1.2f, sc.specAt(arr, u)).pow(ex[s]) * L * (.72f + s * .07f) })
                    drawPath(path, cols[s])
                }
            }
            "anillo" -> {
                val R = g.R * 1.08f; val L = g.R * .62f * p.largo; val n = p.barras; val w = max(1.5f * g.d, TAU * R / n * .5f)
                val bars = pooledPath(); val caps = pooledPath()
                for (i in 0 until n) {
                    // sin el giro aquí: se gira el lienzo entero abajo (barras, tapas y degradado a la vez). Antes las barras
                    // giraban dos veces y las tapas una, y con el tiempo se separaban.
                    val q = (i + .5f) / n; val ang = PI.toFloat() / 2 + q * TAU; val u = sc.uOf(q)
                    val v = min(1.25f, sc.specAt(a.spec, u)); val pk = min(1.3f, sc.specAt(a.peak, u)); val c = cos(ang); val s = sin(ang)
                    val r1 = R + g.d * 2 + v * L; val r2 = R + g.d * 2 + pk * L + w * 1.6f
                    bars.moveTo(c * R, s * R); bars.lineTo(c * r1, s * r1); caps.moveTo(c * r2, s * r2); caps.lineTo(c * (r2 + .1f), s * (r2 + .1f))
                }
                rotate(rot * 180 / PI.toFloat(), Offset.Zero) {
                    drawPath(bars, conic(sc), style = Stroke(w * (if (bloom) 1.4f else 1f), cap = StrokeCap.Round))
                    if (!bloom) drawPath(caps, Color.White.copy(alpha = .85f), style = Stroke(w * .9f, cap = StrokeCap.Round))
                }
            }
            "onda" -> {
                val R = g.R * 1.3f; val L = g.R * .5f * p.largo
                val first = sc.waveHist.firstOrNull()
                if (first != null) {
                    if (!bloom) drawPath(closedPath(first), Brush.radialGradient(0f to Color.Transparent, (g.R / (R + L)) to hsl(sc.h1, sc.sat, .5f, .3f + .35f * U.bass), 1f to hsl(sc.h1, sc.sat, .5f, 0f), center = Offset.Zero, radius = R + L))
                    val specs = listOf(Triple(8, hsl(sc.h2, sc.sat, .55f, .55f), 1.04f), Triple(4, hsl(hueLerp(sc.h1, sc.h2, .5f), sc.sat, .6f, .75f), 1.02f), Triple(0, Color.White.copy(alpha = .95f), 1f))
                    for ((h, c, k) in specs) {
                        val pts = sc.waveHist.elementAtOrElse(min(h, sc.waveHist.size - 1)) { first }
                        drawPath(closedPath(pts, k), c, style = Stroke((if (h != 0) 2f else 2.6f) * g.d * (if (bloom) 2f else 1f), join = StrokeJoin.Round))
                    }
                }
            }
            "pulso" -> {
                val r0 = g.R * 1.2f; val t = sc.vtime; val hit = max(U.kick, a.punch)
                val pts = ringPts(sc, 180, rot) { u, q -> val an = q * TAU
                    r0 * (1 + .09f * U.bass * p.largo * sin(3 * an + t * 1.3f) + .06f * hit * p.largo * sin(5 * an - t * 2.1f) + .03f * U.energy * sin(9 * an + t * 3)) + sc.specAt(a.spec, u) * g.R * .14f * p.largo }
                val w = g.R * (.06f + .05f * hit); val dx = g.R * .05f * hit; val path = closedPath(pts)
                drawPath(path, hsl(sc.h1, sc.sat, .35f), style = Stroke(w * .6f, join = StrokeJoin.Round))
                if (!bloom) for ((o, c) in listOf(-dx to hsl(sc.h2, sc.sat, .5f), dx to hsl(sc.h1, sc.sat, .5f), 0f to Color.White.copy(alpha = .55f)))
                    translate(o, -o * .3f) { drawPath(path, c, style = Stroke(w, join = StrokeJoin.Round), blendMode = PLUS) }
            }
            "eclipse" -> {
                val R = g.R * 1.02f; val L = g.R * p.largo; val n = 300; val t = floor(sc.vtime * 10).toInt()
                drawCircle(Brush.radialGradient(0f to Color.Transparent, (R * .98f / (R + L * .7f)) to hsl(sc.h1, sc.sat, .7f, .55f + .35f * max(U.kick, a.punch)),
                    ((R * .98f / (R + L * .7f)) + .2f).coerceAtMost(.99f) to hsl(sc.h1, sc.sat, .5f, .25f), 1f to hsl(sc.h2, sc.sat, .4f, 0f), center = Offset.Zero, radius = R + L * .7f), R + L * .7f, Offset.Zero)
                if (!bloom) for (k in 0..2) {
                    val path = pooledPath(); var i = k
                    while (i < n) {
                        val q = i.toFloat() / n; val an = PI.toFloat() / 2 + q * TAU + rot * .5f; val v = min(1.2f, sc.specAt(a.spec, sc.uOf(q)))
                        val len = v * L * (.35f + .65f * h01(i, t + k)); val c = cos(an); val s = sin(an)
                        path.moveTo(c * R, s * R); path.lineTo(c * (R + g.d * 2 + len), s * (R + g.d * 2 + len)); i += 3
                    }
                    drawPath(path, listOf(hsl(sc.h1, sc.sat, .7f, .5f), hsl(sc.h2, sc.sat, .7f, .45f), Color.White.copy(alpha = .5f))[k], style = Stroke((if (k == 2) 1f else 1.6f) * g.d, cap = StrokeCap.Round), blendMode = PLUS)
                }
                drawCircle(Color.White.copy(alpha = .9f), R, Offset.Zero, style = Stroke(2 * g.d * (if (bloom) 2f else 1f)))
            }
            "instrumentos" -> {
                val R = g.R; val t = sc.vtime
                if (!bloom) drawCircle(Brush.radialGradient(0f to Color.Transparent, (1 / 1.5f) to hsl(sc.h1, sc.sat, .5f, .5f * U.bass), 1f to hsl(sc.h1, sc.sat, .5f, 0f), center = Offset.Zero, radius = R * 1.5f), R * 1.5f, Offset.Zero)
                fun dots(n: Int, rr: Float, size: Float, rotK: Float, keep: (Int) -> Boolean = { true }): Path { val pth = pooledPath()
                    for (i in 0 until n) if (keep(i)) { val an = i.toFloat() / n * TAU + rot * rotK; pth.addOval(Rect(Offset(cos(an) * rr, sin(an) * rr), size)) }; return pth }
                drawPath(dots(24, R * 1.16f, g.d * (2 + 5 * U.kick), 1f), hsl(sc.h1, sc.sat, .62f), blendMode = PLUS)
                drawPath(dots(40, R * 1.34f, g.d * (1.4f + 2.5f * U.snare), -1.4f), Color.White.copy(alpha = clamp(.2f + .8f * U.snare, 0f, 1f)), blendMode = PLUS)
                drawPath(dots(90, R * 1.5f, g.d * 1.3f, 2f) { h01(it, floor(t * 12).toInt()) <= .25f + .7f * U.hat }, hsl(sc.h2, sc.sat * .6f, .8f), blendMode = PLUS)
                val sw = 180f * clamp(U.voice, 0f, 1f) * .95f
                drawArc(hsl(sc.h2, sc.sat, .65f), -90f - sw, sw * 2, false, Offset(-R * 1.68f, -R * 1.68f), Size(R * 3.36f, R * 3.36f), style = Stroke(3.5f * g.d, cap = StrokeCap.Round))
            }
            "barras" -> drawCircle(hsl(sc.h1, sc.sat, .65f, .6f + .4f * max(U.kick, a.punch)), g.R * (1.08f + .04f * U.kick), Offset.Zero, style = Stroke(2.5f * g.d * (if (bloom) 2f else 1f)))
        }
    } } }
}

/** Banda de la malla NCS: relleno translúcido, hilos paralelos dentro y los dos bordes (el de fuera, lo que más brilla). */
private fun DrawScope.drawMallaBand(sc: WallpaperScene, g: Geometry, bloom: Boolean) {
    val p = sc.p; val U = sc.U; val n = sc.MB
    val hue = sc.sphereHue.takeIf { it >= 0f } ?: sc.h1
    val col = hsl(hue, 1f, .5f); val hi = hsl(hue, 1f, .66f)
    fun contour(arr: FloatArray): Path { val path = pooledPath(); for (k in 0..n) { val i = k % n; val a = i.toFloat() / n * TAU; val x = cos(a) * arr[i]; val y = sin(a) * arr[i]; if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }; path.close(); return path }
    val band = pooledPath().apply { for (k in 0..n) { val i = k % n; val a = i.toFloat() / n * TAU; if (k == 0) moveTo(cos(a) * sc.bOut[i], sin(a) * sc.bOut[i]) else lineTo(cos(a) * sc.bOut[i], sin(a) * sc.bOut[i]) }
        for (k in 0..n) { val i = (n - k) % n; val a = i.toFloat() / n * TAU; lineTo(cos(a) * sc.bIn[i], sin(a) * sc.bIn[i]) }; close() }
    val flash = max(U.kick, sc.audio.punch); val ka = p.aro
    if (bloom) {
        drawPath(band, col, alpha = clamp((.3f + .3f * flash) * ka, 0f, 1f), blendMode = PLUS)
        drawPath(contour(sc.bOut), col, alpha = clamp(.6f * ka, 0f, 1f), style = Stroke(4 * g.d), blendMode = PLUS)
        return
    }
    // estela: los bordes de fuera de los cuadros anteriores, cada vez más flojos
    sc.ghosts.forEachIndexed { k, arr -> drawPath(contour(arr), col, alpha = clamp(.22f * p.estela * ka * (1 - k / 3f), 0f, 1f), style = Stroke(1.4f * g.d), blendMode = PLUS) }
    drawPath(band, col, alpha = clamp((.1f + .16f * flash) * ka, 0f, 1f), blendMode = PLUS)
    val ribbon = pooledPath(); val NR = 9
    for (r in 1 until NR) { val f = r.toFloat() / NR; for (k in 0..n) { val i = k % n; val a = i.toFloat() / n * TAU; val R = sc.bOut[i] + (sc.bIn[i] - sc.bOut[i]) * f; if (k == 0) ribbon.moveTo(cos(a) * R, sin(a) * R) else ribbon.lineTo(cos(a) * R, sin(a) * R) } }
    drawPath(ribbon, hi, alpha = clamp((.32f + .2f * flash) * ka, 0f, 1f), style = Stroke(.7f * g.d), blendMode = PLUS)
    drawPath(contour(sc.bIn), hi, alpha = clamp(.3f * ka, 0f, 1f), style = Stroke(g.d), blendMode = PLUS)
    drawPath(contour(sc.bOut), hi, alpha = clamp((.55f + .25f * flash) * ka, 0f, 1f), style = Stroke(1.2f * g.d), blendMode = PLUS)
}

// rejilla de la esfera NCS (polo en el eje X, de lado)
private const val GLAT = 40; private const val GLON = 84
private val gX = FloatArray(GLAT * GLON); private val gY = FloatArray(GLAT * GLON); private val gZ = FloatArray(GLAT * GLON)
private val gridReady = run {
    for (j in 0 until GLAT) { val ph = (-.5f + (j + .5f) / GLAT) * PI.toFloat() * .94f; val cph = cos(ph); val sph = sin(ph)
        for (i in 0 until GLON) { val th = i.toFloat() / GLON * TAU; val k = j * GLON + i; gX[k] = sph; gY[k] = cph * cos(th); gZ[k] = cph * sin(th) } }
    true
}

/** Lo de delante: carátula, rejilla de puntos de la esfera, anillo del drop, destellos, tono de los respiros y zonas. */
internal fun DrawScope.drawFront(sc: WallpaperScene) {
    val g = sc.G ?: return; val p = sc.p; val U = sc.U
    if (p.visualizador) translate(g.cx + sc.shakeX, g.cy + sc.shakeY) { scale(sc.pulse, sc.pulse, Offset.Zero) {
        drawCover(sc, g)
        if (p.estilo == "malla") drawSphereGrid(sc, g) else if (p.dentro != "caratula") drawDotSphere(sc, g)
    } }
    if (p.visualizador && sc.dropAge < 1.4f && sc.playing) drawCircle(Color.White.copy(alpha = (1 - sc.dropAge / 1.4f).pow(2)), g.Rout + sc.dropAge * g.W * .45f, Offset(g.cx, g.cy), style = Stroke(4 * g.d))
    if (sc.flash > .005f) drawRect(Color.White.copy(alpha = clamp(sc.flash, 0f, 1f)))
    if (U.slow > .02f) drawRect(Color(20, 30, 60).copy(alpha = clamp(U.slow * .25f, 0f, 1f)))   // respiro: más frío y apagado
    if (p.verZonas) {
        for (z in g.zones) drawRect(Color(120, 255, 200, 200), Offset(z.x0, z.y0), Size(z.w, z.h), style = Stroke(1.5f * g.d))
        if (p.visualizador) drawCircle(Color(255, 120, 120, 200), g.ex, Offset(g.cx, g.cy), style = Stroke(1.5f * g.d))
    }
}

private fun DrawScope.drawSphereGrid(sc: WallpaperScene, g: Geometry) {
    check(gridReady)
    val p = sc.p; val t = sc.vtime; val Rs = g.R * 1.28f
    val hue = sc.sphereHue.takeIf { it >= 0f } ?: sc.h1; val hi = hsl(hue, 1f, .66f)
    val bLive = clamp((sc.audio.liveBass - .12f) / .65f, 0f, 1.25f) * (if (sc.playing) 1f else 0f)
    val over = if (p.dentro == "esfera") 1f else .35f
    val yaw = PI.toFloat() - .3f + .1f * sin(t * .11f); val roll = t * .12f
    val cy = cos(yaw); val sy = sin(yaw); val cr = cos(roll); val sr = sin(roll); val F = Rs * 3.2f; val rr = Rs * .965f
    val near = pooledPath(); val mid = pooledPath(); val s1 = 1.5f * g.d; val s2 = 1.9f * g.d
    for (k in 0 until GLAT * GLON) {
        val x0 = gX[k]; val y1 = gY[k] * cr - gZ[k] * sr; val z1 = gY[k] * sr + gZ[k] * cr
        val x2 = x0 * cy + z1 * sy; val z2 = -x0 * sy + z1 * cy
        if (z2 > .05f) continue
        val w = 1 - .12f * bLive * p.largo * max(0f, sin(2.2f * x0 + 1.7f * gY[k] + t * .9f))
        val pz = z2 * rr * w; val pp = F / (F + pz); val x = x2 * rr * w * pp; val y = y1 * rr * w * pp
        if (z2 > -.35f) mid.addRect(Rect(x - s1 / 2, y - s1 / 2, x + s1 / 2, y + s1 / 2)) else near.addRect(Rect(x - s2 / 2, y - s2 / 2, x + s2 / 2, y + s2 / 2))
    }
    drawPath(near, hi, alpha = .75f * over, blendMode = PLUS); drawPath(mid, hi, alpha = .6f * over, blendMode = PLUS)
}

/** Esfera de puntos girando dentro del círculo (para los demás estilos); con el bajo se ondula. */
private fun DrawScope.drawDotSphere(sc: WallpaperScene, g: Geometry) {
    val p = sc.p; val U = sc.U; val rs = g.R * .96f; val t = sc.vtime; val ry = t * .22f; val tx = .38f + .1f * sin(t * .13f)
    val ccy = cos(tx); val ssy = sin(tx); val cr = cos(ry); val sr = sin(ry); val s = 1.6f * g.d; val alpha = if (p.dentro == "ambas") .6f else 1f
    val paths = arrayOf(pooledPath(), pooledPath(), pooledPath())
    for (la in 1 until 15) { val ph = -PI.toFloat() / 2 + la / 15f * PI.toFloat(); val cp = cos(ph); val spp = sin(ph)
        for (lo in 0 until 34) { val th = lo / 34f * TAU; val w = 1 + (.05f + .08f * U.bass) * sin(th * 2 + ph * 3 - t * 1.6f)
            val x = cp * cos(th) * w; val y = spp * w; val z = cp * sin(th) * w
            val x1 = x * cr + z * sr; val z1 = -x * sr + z * cr; val y2 = y * ccy - z1 * ssy; val z2 = y * ssy + z1 * ccy
            val b = if (z2 > .35f) 2 else if (z2 > -.2f) 1 else 0; val ss = s * floatArrayOf(.8f, 1f, 1.2f)[b]
            paths[b].addRect(Rect(x1 * rs - ss / 2, y2 * rs - ss / 2, x1 * rs + ss / 2, y2 * rs + ss / 2)) } }
    val c = hsl(sc.h1, sc.accentSat(.85f), .7f)
    floatArrayOf(.18f, .45f, .9f).forEachIndexed { i, a -> drawPath(paths[i], c, alpha = a * alpha, blendMode = PLUS) }
}

private fun coverShape(p: WallpaperSettings, r: Float): Path = pooledPath().apply {
    when (p.forma) {
        "redondeada" -> addRoundRect(RoundRect(-r, -r, r, r, CornerRadius(r * .28f)))
        "cuadrada" -> addRect(Rect(-r * .9f, -r * .9f, r * .9f, r * .9f))
        else -> addOval(Rect(Offset.Zero, r))
    }
}

private fun DrawScope.drawCoverAt(sc: WallpaperScene, img: ImageBitmap?, r: Float, sx: Float, sy: Float, rot: Float, alpha: Float) {
    if (img == null || alpha <= 0f || r <= 0f) return
    val p = sc.p; val g = sc.G ?: return
    rotate(rot * 180 / PI.toFloat(), Offset.Zero) { scale(sx, sy, Offset.Zero) {
        if (p.forma == "vinilo") {
            drawCircle(Color(0xFF0C0C10), r, Offset.Zero, alpha = alpha)
            for (k in 0 until 9) drawCircle(Color.White.copy(alpha = .06f * alpha), r * (.62f + k * .04f), Offset.Zero, style = Stroke(max(1f, r * .006f)))
            val inner = pooledPath().apply { addOval(Rect(Offset.Zero, r * .56f)) }
            clipPath(inner) { drawSquare(img, Rect(-r * .56f, -r * .56f, r * .56f, r * .56f), alpha) }
            drawCircle(Color(0xFF0C0C10), r * .045f, Offset.Zero, alpha = alpha)
        } else {
            val shape = coverShape(p, r)
            clipPath(shape) { drawSquare(img, Rect(-r, -r, r, r), alpha) }
            if (p.borde > 0f) drawPath(shape, hsl(sc.h1, sc.sat * .6f, .8f, (.25f + .5f * p.borde) * alpha), style = Stroke(g.d * (1 + 3 * p.borde)))
        }
    } }
}


/** La carátula en el círculo, con la transición entre canciones (rebote, giro o fundido). */
private fun DrawScope.drawCover(sc: WallpaperScene, g: Geometry) {
    val p = sc.p; if (p.dentro == "esfera") return
    val r = g.R * p.tamCaratula; val k = sc.coverT; val rot = if (p.forma == "vinilo") sc.vinyl else 0f
    val cur = sc.cover
    if (cur == null) { drawCircle(hsl(sc.h1, .3f, .12f), r, Offset.Zero); return }
    val prev = sc.prevCover
    when {
        p.transicion == "fundido" || prev == null -> {
            val a = if (prev != null) clamp(k / .8f, 0f, 1f) else clamp(k / .5f, 0f, 1f)
            if (prev != null && a < 1f) drawCoverAt(sc, prev, r, 1f, 1f, rot, 1 - a)
            val s = if (prev != null) 1f else lerp(.85f, 1f, spring(k, 8f, 12f))
            drawCoverAt(sc, cur, r, s, s, rot, a)
        }
        p.transicion == "giro" -> {
            if (k < .28f) drawCoverAt(sc, prev, r, cos(k / .28f * PI.toFloat() / 2), 1f, rot, 1f)
            else drawCoverAt(sc, cur, r, clamp(spring(k - .28f, 11f, 16f), 0f, 1.3f), 1f, rot, 1f)
        }
        else -> {
            if (k < .32f) { val q = k / .32f; drawCoverAt(sc, prev, r, 1 - q * q, 1 - q * q, rot - q * 1.2f, 1f) }
            val s = spring(k - .22f, 9f, 15f); if (k > .22f) drawCoverAt(sc, cur, r, s, s, rot, clamp((k - .22f) * 6, 0f, 1f))
        }
    }
}
