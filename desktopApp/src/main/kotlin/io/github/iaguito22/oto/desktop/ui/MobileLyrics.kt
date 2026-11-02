package io.github.iaguito22.oto.desktop.ui
import java.text.BreakIterator
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.util.lerp
import io.github.iaguito22.oto.lyrics.*
import io.github.iaguito22.oto.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun MobileLyrics(id: String?, lyrics: Lyrics?, loading: Boolean, playing: Boolean, position: () -> Long, seek: (Long) -> Unit, store: LyricsStore, prefs: Prefs, fontScale:Float = 1f) {
    val clock = remember(id) { LyricsClock() }
    val romanize by prefs.romanize.collectAsState(initial = true)
    var rom by remember(id) { mutableStateOf<Map<String,String>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id, lyrics) { rom = if (id != null && lyrics is Lyrics.Synced) store.romanized(id, lyrics) else null }
    LaunchedEffect(id, playing) {
        if (!playing) {
            while (true) { clock.ms = position() + LEAD_MS; delay(150) }
        }
        var last = position()
        var anchorTime = 0L
        var lastFrame = last
        while (true) withFrameNanos { now ->
            val current = position()
            if (anchorTime == 0L || current != last) { last = current; anchorTime = now }
            val estimate = last + ((now - anchorTime) / 1_000_000).coerceIn(0, 70) + LEAD_MS
            lastFrame = if (estimate < lastFrame && lastFrame - estimate < 180) lastFrame else estimate
            clock.ms = lastFrame
        }
    }
    when {
        loading -> LyricsSkeleton()
        lyrics is Lyrics.Synced -> {
            val rows = remember(lyrics) { rowsOf(lyrics.lines) }
            val active by remember(rows) { derivedStateOf { rows.indexOfLast { it.start <= clock.ms } } }
            val list = remember(rows) { LazyListState(active.coerceAtLeast(0)) }
            val dragged by list.interactionSource.collectIsDraggedAsState()
            var following by remember(rows) { mutableStateOf(true) }
            var syncRequest by remember(rows) { mutableIntStateOf(0) }
            LaunchedEffect(dragged) { if (dragged) following = false }
            fun toAnchor(i:Int):Float? = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }?.offset?.toFloat()
            val firstActive = remember(rows) { active }
            val openedAt = remember(rows) { System.nanoTime() }
            BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                drawContent()
                val fade = 40.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), endY = fade), size = androidx.compose.ui.geometry.Size(size.width, fade), blendMode = BlendMode.DstOut)
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = size.height-fade, endY = size.height), Offset(0f,size.height-fade), androidx.compose.ui.geometry.Size(size.width,fade), blendMode = BlendMode.DstOut)
            }) {
                LaunchedEffect(active, dragged, following, syncRequest) {
                    if (!following || dragged || rows.isEmpty()) return@LaunchedEffect
                    val i = active.coerceAtLeast(0)
                    if (toAnchor(i) == null) { list.scrollToItem(i); return@LaunchedEffect }
                    if (abs(toAnchor(i)!!) < 1f) return@LaunchedEffect
                    var moved = 0f
                    Animatable(0f).animateTo(1f, tween(400, easing = EaseOutQuart)) {
                        val left = toAnchor(i) ?: return@animateTo
                        val step = left - (1 - value) * (moved + left)
                        moved += list.dispatchRawDelta(step)
                    }
                }
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { following = false }, contentPadding = PaddingValues(top = maxHeight * .3f, bottom = maxHeight * .7f)) {
                    itemsIndexed(rows) { i, row -> when(row) {
                        is Row.Sung -> {
                            val cascade = remember { System.nanoTime() - openedAt < 400_000_000L }
                            SungLine(row.line, if(active < 0) i+1 else i-active, if(cascade) i-max(firstActive,0) else null, rom?.get(row.line.text), romanize, clock, !following, fontScale, { scope.launch { prefs.setRomanize(!romanize) } }, { following=true; syncRequest++; seek((row.start - LEAD_MS).coerceAtLeast(0)) })
                        }
                        is Row.Gap -> GapDots(row, i == active, clock)
                    } }
                }
                if (!following) {
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom=46.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(Color(0xEE343936)).clickable { following=true; syncRequest++ }.padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Sync,null,Modifier.size(16.dp),tint=Color.White)
                        Text("Sincronizar",style=OtoType.rowSub,color=Color.White)
                    }
                }
            }
        }
        lyrics is Lyrics.Plain -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { items(lyrics.lines) { Text(it, style = LYRIC.copy(fontSize=LYRIC.fontSize*fontScale,lineHeight=LYRIC.lineHeight*fontScale), color = Color.White.copy(alpha = .62f)) } }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Instrumental o sin letra disponible", style = OtoType.rowSub, color = Color.White.copy(alpha = .36f)) }
    }
}

@Composable private fun LyricsSkeleton() {
    val pulse = rememberInfiniteTransition(label = "lyricsLoading")
    val alpha by pulse.animateFloat(.07f,.19f,infiniteRepeatable(tween(1050,easing=EaseInOut),RepeatMode.Reverse),label="lyricsLoadingAlpha")
    Column(Modifier.fillMaxSize().padding(start=20.dp,top=48.dp,end=20.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        listOf(.72f,.94f,.58f,.84f,.65f).forEach { width ->
            Box(Modifier.fillMaxWidth(width).height(23.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color.White.copy(alpha=alpha)))
        }
    }
}
private const val LEAD_MS = 0L
private const val ROM_MS = 420
private const val GAP_MS = 6000L
private const val GAP_INTRO_MS = 3500L
private val EDGE = 20.dp
@Stable
private class LyricsClock {
    var ms by mutableLongStateOf(0L)
}

/** Una fila de la letra: una línea cantada o una pausa larga (tres puntos). */
private sealed interface Row {
    val start: Long
    val end: Long

    data class Sung(val line: Line) : Row {
        override val start get() = line.start
        override val end get() = line.end
    }

    data class Gap(override val start: Long, override val end: Long) : Row
}

private fun rowsOf(lines: List<Line>): List<Row> = buildList {
    val first = lines.firstOrNull() ?: return@buildList
    if (first.start > GAP_INTRO_MS) add(Row.Gap(0, first.start))
    lines.forEachIndexed { i, l ->
        add(Row.Sung(l))
        val next = lines.getOrNull(i + 1) ?: return@forEachIndexed
        val sungEnd = max(l.words.lastOrNull()?.end ?: l.end, l.bg.lastOrNull()?.end ?: 0)
        if (next.start - sungEnd > GAP_MS) add(Row.Gap(sungEnd + 400, next.start))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SungLine(line: Line, dist: Int, order: Int?, rom: String?, showRom: Boolean, clock: LyricsClock, browsing: Boolean, fontScale:Float, onHold: (() -> Unit)?, onTap: () -> Unit) {
    val dur = ((line.end - line.start) * 0.3).toInt().coerceIn(420, 1000)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val alphaTarget = when {
        browsing -> if(hovered) 1f else .4f
        dist == 0 -> 1f
        dist < 0 -> 0.34f
        dist == 1 -> 0.62f
        dist == 2 -> 0.42f
        dist == 3 -> 0.3f
        else -> 0.24f
    }
    val alpha by animateFloatAsState(alphaTarget, tween(if (dist == 0) dur / 2 else if (dist < 0) (dur * 1.4f).toInt() else dur, easing = EaseOutQuart), label = "lineAlpha")
    val scale by animateFloatAsState(if (dist == 0) 1.03f else 1f, tween(if (dist == 0) dur else (dur * 1.2f).toInt(), easing = EaseOutQuart), label = "lineScale")
    val blur = if (!browsing && abs(dist) >= 3) 1.dp else 0.dp
    val lyricStyle = LYRIC.copy(fontSize=LYRIC.fontSize*fontScale,lineHeight=LYRIC.lineHeight*fontScale)
    val backgroundStyle = BG.copy(fontSize=BG.fontSize*fontScale,lineHeight=BG.lineHeight*fontScale)
    val romanStyle = ROM.copy(fontSize=ROM.fontSize*fontScale,lineHeight=ROM.lineHeight*fontScale)
    // Al abrir la letra las líneas suben en cascada desde la activa ([order]: su turno; null, ya en su sitio).
    val enter = remember { Animatable(if (order == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (order == null) return@LaunchedEffect
        delay(30L + 70L * order.coerceIn(0, 6))
        enter.animateTo(1f, tween(820, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))
    }
    val lit = dist == 0 || dist == -1
    val align = if (line.duet) Alignment.End else Alignment.Start
    val arrangement = if (line.duet) Arrangement.End else Arrangement.Start
    val bgOnly = line.words.isEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .blur(blur)
            .graphicsLayer {
                val e = enter.value
                this.alpha = alpha * e
                translationY = (1 - e) * 46.dp.toPx()
                val s = scale * lerp(0.965f, 1f, e)
                scaleX = s; scaleY = s
                // La capa va de borde a borde, con el margen dentro: mientras su opacidad no es 1 se pinta aparte y
                // recortada a sus límites, y la primera letra, que crece desde su centro, perdía su lado de fuera
                // (la H de «Heartbreaker»). Crece desde donde empieza el texto, no desde el borde de la capa.
                val edge = if (size.width > 0f) EDGE.toPx() / size.width else 0f
                transformOrigin = TransformOrigin(if (line.duet) 1f - edge else edge, 0.5f)
            }
            .combinedClickable(interaction, null, onLongClick = onHold, onClick = onTap)
            .padding(horizontal = EDGE, vertical = 9.dp),
        horizontalAlignment = align,
    ) {
        if (!bgOnly) Words(line.words, lyricStyle, lit, clock, arrangement, Color.White, letters = line.real)
        if (rom != null && !bgOnly) {
            // Se despliega (o se pliega) bajo su línea, en vez de aparecer de golpe.
            AnimatedVisibility(
                showRom,
                enter = expandVertically(tween(ROM_MS, easing = EaseOutQuart), expandFrom = Alignment.Top) + fadeIn(tween(ROM_MS, delayMillis = ROM_MS / 4)),
                exit = shrinkVertically(tween(ROM_MS, easing = EaseOutQuart), shrinkTowards = Alignment.Top) + fadeOut(tween(ROM_MS / 2)),
            ) {
                val romWords = remember(rom, line) { spread(rom, line.words.first().start, line.words.last().end) }
                Words(romWords, romanStyle, lit, clock, arrangement, Color.White.copy(alpha = 0.72f), rise = false, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (line.bg.isNotEmpty()) {
            Words(line.bg, if (bgOnly) lyricStyle.copy(fontSize = lyricStyle.fontSize * 0.8f, lineHeight = lyricStyle.lineHeight * 0.8f) else backgroundStyle, lit, clock, arrangement,
                Color.White.copy(alpha = if (bgOnly) 0.9f else 0.78f), letters = line.real, modifier = Modifier.padding(top = if (bgOnly) 0.dp else 4.dp))
        }
    }
}

/**
 * Las palabras de una fila, que se parten entre renglones por los espacios (el japonés, letra a letra). Mientras la
 * línea está encendida cada trozo (una letra si la fuente da tiempos por palabra, como en el tema del PC; si no, la
 * sílaba) tiene su relleno y su salto; apagada, cada palabra es un texto.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Words(
    words: List<Word>,
    style: TextStyle,
    lit: Boolean,
    clock: LyricsClock,
    arrangement: Arrangement.Horizontal,
    color: Color,
    rise: Boolean = true,
    letters: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val groups = remember(words) { groupsOf(words) }
    val space = with(LocalDensity.current) { (style.fontSize * 0.27f).toDp() }
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
        groups.forEach { g ->
            val gap = if (g.last().space) Modifier.padding(end = space) else Modifier
            if (!lit) BasicText(g.joinToString("") { it.text }, gap, style = style.copy(color = color))
            else LitWord(g, style, color, clock, rise, letters, gap)
        }
    }
}

/** La pronunciación se rellena a la vez que la voz: de su primera a su última sílaba, repartida por letras. */
private fun spread(text: String, start: Long, end: Long): List<Word> {
    val parts = text.split(' ').filter { it.isNotEmpty() }
    val total = parts.sumOf { it.length }.coerceAtLeast(1)
    var acc = 0
    return parts.mapIndexed { i, p ->
        val s = start + (end - start) * acc / total
        acc += p.length
        Word(s, start + (end - start) * acc / total, p, i < parts.lastIndex)
    }
}

// Una palabra puede venir en varias sílabas: van juntas (no se parten) hasta el espacio. El japonés y el chino
// se pueden partir en cualquier sitio.
// Una palabra en katakana partida en sílabas («ミス» «テリ» «アス») tampoco se parte.
private val CJK_CHAR = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff\\uac00-\\ud7af]")
private val KATAKANA = Regex("^[\\u30a0-\\u30ff]+$")

private fun groupsOf(words: List<Word>): List<List<Word>> {
    val out = ArrayList<List<Word>>()
    var cur = ArrayList<Word>()
    words.forEachIndexed { i, w ->
        cur += w
        val next = words.getOrNull(i + 1)
        val katakanaWord = next != null && KATAKANA.matches(w.text) && KATAKANA.matches(next.text)
        if (w.space || (CJK_CHAR.containsMatchIn(w.text) && !katakanaWord)) { out += cur; cur = ArrayList() }
    }
    if (cur.isNotEmpty()) out += cur
    return out
}

/**
 * Un trozo de una palabra encendida: una letra (con [letter]) o una sílaba entera. [from] y [to] son su sitio en el
 * texto de la palabra; [weights], lo que pesa cada carácter al rellenarse (los signos poco).
 */
private class Piece(val from: Int, val to: Int, val start: Long, val end: Long, val weights: FloatArray, val letter: Boolean)

/**
 * Los trozos de una palabra. Con [letters], cada sílaba se reparte en letras como en el tema de Spotify del PC: los
 * signos pesan poco y, si la sílaba se alarga, la última letra se queda con el resto. El japonés, el chino y el
 * coreano, y lo de un solo carácter, no se parten.
 */
private fun piecesOf(g: List<Word>, letters: Boolean): List<Piece> {
    val out = ArrayList<Piece>()
    var offset = 0
    for (w in g) {
        val text = w.text
        val d = max(1L, w.end - w.start)
        val ws = FloatArray(text.length) { if (text[it].isLetterOrDigit()) 1f else 0.3f }
        if (d > 800 && ws.isNotEmpty()) ws[ws.lastIndex] *= 1 + min(2.5f, (d - 800) / 600f)
        val marks = graphemes(text)
        if (!letters || marks.size < 3 || CJK_CHAR.containsMatchIn(text)) {
            if (text.isNotEmpty()) out += Piece(offset, offset + text.length, w.start, w.end, ws, letter = false)
        } else {
            // Una letra con su tilde aparte (dos caracteres) pesa lo de su base, y el estirón de la última va con ella.
            val pw = FloatArray(marks.size - 1) { k -> (marks[k] until marks[k + 1]).maxOf { ws[it] } }
            val sum = pw.sum()
            var acc = 0f
            for (k in pw.indices) {
                val s = w.start + (d * acc / sum).toLong()
                acc += pw[k]
                out += Piece(offset + marks[k], offset + marks[k + 1], s, w.start + (d * acc / sum).toLong(), floatArrayOf(1f), letter = true)
            }
        }
        offset += text.length
    }
    return out
}

/** Dónde empieza cada letra tal como se ve (una «é» escrita como «e» + tilde es una), y el final. */
private fun graphemes(text: String): IntArray {
    val bi = BreakIterator.getCharacterInstance().apply { setText(text) }
    return generateSequence(bi.first()) { bi.next().takeIf { n -> n != BreakIterator.DONE } }.toList().toIntArray()
}

/**
 * Una palabra encendida. Ocupa lo mismo que apagada (el mismo texto, sin pintar) y cada trozo se dibuja en el sitio
 * que tiene dentro de la palabra: separar las letras en textos sueltos perdía el kerning y la línea se movía al
 * encenderse. Cada trozo se rellena a su tiempo, sube con un rebote al empezar a cantarse y se asienta despacio
 * cuando acaba la palabra entera, más alto cuanto más se alarga: con letras, una ola que la recorre. Todo se lee del
 * reloj al dibujar.
 */
@Composable
private fun LitWord(g: List<Word>, style: TextStyle, color: Color, clock: LyricsClock, rise: Boolean, letters: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val text = remember(g) { g.joinToString("") { it.text } }
    val pieces = remember(g, letters) { piecesOf(g, letters) }
    val shapes = remember(pieces, style) { pieces.map { measurer.measure(text.substring(it.from, it.to), style, softWrap = false, maxLines = 1) } }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val hold = g.last().end
    val sw = min(0.085f, 0.032f + (hold - g.first().start) / 1000f * 0.03f)
    val full = remember(color) { SolidColor(color) }
    val dim = remember(color) { SolidColor(color.copy(alpha = color.alpha * 0.62f)) }
    BasicText(
        text,
        modifier.drawWithContent {
            val l = layout ?: return@drawWithContent
            val t = clock.ms + if (letters) 100L else 0L
            val lift = sw * style.fontSize.toPx() * 1.05f
            pieces.forEachIndexed { k, p ->
                val box = l.getBoundingBox(p.from)
                val w = l.getBoundingBox(p.to - 1).right - box.left
                val r = if (!rise) 0f else {
                    val ud = (hold - p.start).coerceIn(120, 420)
                    val up = when {
                        t < p.start -> 0f
                        t < p.start + ud -> Overshoot.transform((t - p.start) / ud.toFloat())
                        else -> 1f
                    }
                    val settle = up * if (t > hold) 1f - EaseOutExpo.transform(((t - hold) / 320f).coerceIn(0f, 1f)) else 1f
                    if (settle < .015f) 0f else settle
                }
                val f = ((t - p.start).toFloat() / max(1L, p.end - p.start)).coerceIn(0f, 1f)
                val brush = when {
                    f <= 0f -> dim
                    f >= 1f -> full
                    else -> {
                        // Como el CSS del PC: el borde del relleno es un degradado del 12 % del trozo.
                        val soft = if (p.letter) w * 0.12f else max(6.dp.toPx(), w * 0.12f)
                        val x = fillX(f, p.weights, shapes[k], w)
                        Brush.horizontalGradient(
                            0f to color, ((x - soft) / w).coerceIn(0f, 1f) to color, (x / w).coerceIn(0.001f, 1f) to dim.value, 1f to dim.value,
                            startX = 0f, endX = w,
                        )
                    }
                }
                withTransform({
                    if (r != 0f) {
                        translate(0f, -lift * r)
                        scale(1f + sw * r, 1f + sw * r, Offset(box.left + w / 2, box.top + box.height * 0.88f))
                    }
                    // alpha = 1 explícito: sin él, con un degradado se queda la opacidad del pintado anterior (la de la
                    // letra apagada) y la que se está rellenando salía más oscura que las demás.
                }) { drawText(shapes[k], brush, Offset(box.left, box.top), alpha = 1f) }
            }
        },
        style = style,
        onTextLayout = { layout = it },
    )
}

/** Hasta dónde llega el relleno dentro del trozo, en px: el progreso repartido por letras según su peso. */
private fun fillX(f: Float, weights: FloatArray, layout: TextLayoutResult, width: Float): Float {
    val target = f * weights.sum()
    var acc = 0f
    for (i in weights.indices) {
        if (acc + weights[i] >= target) {
            val box = layout.getBoundingBox(i)
            val frac = if (weights[i] > 0) (target - acc) / weights[i] else 1f
            return lerp(box.left, box.right, frac) + 0.12f * width * f
        }
        acc += weights[i]
    }
    return width * 1.12f
}

/** Una pausa larga: tres puntos que se llenan poco a poco y respiran; solo ocupan sitio mientras suena la pausa. */
@Composable
private fun GapDots(gap: Row.Gap, active: Boolean, clock: LyricsClock) {
    val h by animateDpAsState(if (active) 46.dp else 0.dp, tween(Motion.SPATIAL_FAST, easing = Motion.Standard), label = "gap")
    val shown by animateFloatAsState(if (active) 1f else 0f, tween(Motion.SPATIAL_FAST), label = "gapAlpha")
    Box(
        Modifier
            .size(64.dp, h)
            .graphicsLayer { alpha = shown }
            .drawBehind {
                if (!active && shown == 0f) return@drawBehind
                val t = clock.ms
                val p = ((t - gap.start).toFloat() / max(1L, gap.end - gap.start)).coerceIn(0f, 1f)
                // Al final se encogen de golpe, como quien toma aire antes de la frase.
                val out = ((gap.end - t) / 420f).coerceIn(0f, 1f)
                val breathe = 1f + 0.08f * sin(t / 1000f * 2f * PI.toFloat() * 0.8f)
                val r = 5.dp.toPx() * breathe * lerp(0.4f, 1f, out)
                val step = 18.dp.toPx()
                for (k in 0..2) {
                    val fk = ((p * 3f) - k).coerceIn(0f, 1f)
                    drawCircle(Color.White.copy(alpha = lerp(0.28f, 1f, fk)), r, Offset(r + 4.dp.toPx() + k * step, size.height / 2))
                }
            },
    )
}

private val LYRIC = TextStyle(fontFamily = OtoType.lyric.fontFamily, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 31.sp, letterSpacing = (-0.3).sp)
private val BG = LYRIC.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
private val ROM = LYRIC.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)

private val EaseOutQuart = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
private val EaseOutExpo = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val Overshoot = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
