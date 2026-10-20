package io.github.iaguito22.oto.ui.isla

import android.icu.text.BreakIterator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.R
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import io.github.iaguito22.oto.lyrics.Line
import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.lyrics.LyricsQuery
import io.github.iaguito22.oto.lyrics.LyricsSource
import io.github.iaguito22.oto.lyrics.Word
import io.github.iaguito22.oto.playback.NowPlaying
import io.github.iaguito22.oto.playback.PlayerConnection
import io.github.iaguito22.oto.ui.menu.Skeleton
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Space
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** La letra de una canción en concreto: así nunca se pinta la de la anterior mientras llega la nueva. */
private data class Loaded(val id: String?, val lyrics: Lyrics?)

/**
 * La letra en el sitio de la carátula. Sincronizada, palabra a palabra cuando la fuente lo trae (como en el tema de
 * Spotify): cada sílaba se rellena letra a letra y da un pequeño salto, los coros van debajo en su fila, la
 * pronunciación debajo del japonés, chino, coreano y cirílico, y tres puntos en las pausas largas.
 */
@Composable
fun LyricsPanel(np: NowPlaying, connection: PlayerConnection, width: Dp, height: Dp, onFlash: (CueLabel) -> Unit) {
    // Casi siempre ya la ha pedido el servicio al empezar la canción ([LyricsStore]).
    val store = (LocalContext.current.applicationContext as OtoApp).lyrics
    val loaded by produceState(Loaded(np.id, np.id?.let(store::peek)), np.id) {
        val id = np.id ?: run { value = Loaded(null, Lyrics.Missing); return@produceState }
        store.peek(id)?.let { value = Loaded(id, it); return@produceState }
        value = Loaded(id, null)
        val q = LyricsQuery(id, np.title, np.artist, np.album, np.durationMs)
        var r = store.get(q)
        value = Loaded(id, r.lyrics ?: Lyrics.Missing)
        // Si alguna fuente no contestó (sin red, cupo agotado) se vuelve a preguntar un par de veces, y solo se
        // cambia si llega algo mejor: así no se queda sin sincronizar por un fallo pasajero.
        for (wait in RETRY_MS) {
            if (r.final) break
            delay(wait)
            val again = store.get(q)
            if (rank(again.lyrics) > rank(value.lyrics)) value = Loaded(id, again.lyrics)
            r = again
        }
        if (r.final) value.lyrics?.let { store.put(id, it) }
    }
    val current = if (loaded.id == np.id) loaded else Loaded(np.id, null)
    val c = Oto.colors
    // Más ancha que la carátula por los lados, con el texto igual de ancho que ella: la línea activa crece hacia la
    // derecha y cada palabra cantada se agranda, y lo pegado al borde no puede cortarse contra la capa del fundido.
    Box(
        Modifier
            .requiredSize(width + EDGE * 2, height)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = 40.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), endY = fade), size = Size(size.width, fade), blendMode = BlendMode.DstOut)
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = size.height - fade, endY = size.height),
                    Offset(0f, size.height - fade), Size(size.width, fade), blendMode = BlendMode.DstOut,
                )
            },
    ) {
        AnimatedContent(
            targetState = current,
            contentKey = { it.id to (it.lyrics != null) },
            transitionSpec = { fadeIn(tween(Motion.EFFECTS, delayMillis = 60)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) },
            label = "lyrics",
        ) { (id, lyrics) ->
            when (lyrics) {
                null -> Skeleton(rows = 5, modifier = Modifier.padding(start = EDGE, end = EDGE, top = Space.xl))
                Lyrics.Missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (id == null) "No suena nada" else "No hay letra\npara esta canción",
                        style = OtoType.body, color = c.textMuted, textAlign = TextAlign.Center,
                    )
                }
                is Lyrics.Plain -> PlainLyrics(lyrics)
                is Lyrics.Synced -> SyncedLyrics(id.orEmpty(), lyrics, np.playing, connection, height, onFlash)
            }
        }
    }
}

@Composable
private fun PlainLyrics(l: Lyrics.Plain) {
    val c = Oto.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = EDGE, vertical = 36.dp)) {
        Row(Modifier.padding(bottom = Space.md).clip(CircleShape).background(c.field).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text("Sin sincronizar", style = OtoType.label, color = c.textMuted)
        }
        l.lines.forEach { line ->
            if (line.isBlank()) Spacer(Modifier.height(Space.lg))
            else Text(line, style = LYRIC.copy(fontSize = LYRIC.fontSize * 0.86f, lineHeight = LYRIC.lineHeight * 0.86f), color = c.text, modifier = Modifier.padding(vertical = 3.dp))
        }
        Source(l.source, synced = false)
    }
}

/** El reloj de la letra: la posición del reproductor interpolada por fotograma, para que nada vaya a saltos. */
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

@Composable
private fun SyncedLyrics(id: String, l: Lyrics.Synced, playing: Boolean, connection: PlayerConnection, height: Dp, onFlash: (CueLabel) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val rows = remember(l) { rowsOf(l.lines) }
    val app = LocalContext.current.applicationContext as OtoApp
    val fetched by produceState(app.lyrics.peekRomanized(id), id, l) {
        if (value != null || !LyricsSource.needsRomanization(l.lines)) return@produceState
        app.lyrics.romanized(id, l)?.let { value = it }
    }
    // Se pide igual aunque esté quitada: así volver a ponerla es al momento.
    val prefs = app.prefs
    val romanize by prefs.romanize.collectAsStateWithLifecycle(initialValue = true)
    // Mantener una línea quita o pone la romanización (solo si esta letra la tiene).
    val toggleRom: (() -> Unit)? = if (fetched == null) null else {
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onFlash(CueLabel(R.drawable.ic_translate, if (romanize) "Sin romanización" else "Con romanización"))
            scope.launch { prefs.setRomanize(!romanize) }
        }
    }

    val clock = remember { LyricsClock() }
    LaunchedEffect(id, playing) {
        // Se ancla a la posición y avanza con el tiempo del fotograma; si el reproductor se separa mucho (un salto,
        // un corte), se vuelve a anclar, y si se separa poco se le acerca sin que se note.
        if (!playing) {
            while (true) { clock.ms = connection.position() + LEAD_MS; delay(200) }
        }
        var anchorPos = connection.position().toDouble()
        var anchorT = -1L
        while (true) withFrameNanos { now ->
            if (anchorT < 0) anchorT = now
            val est = anchorPos + (now - anchorT) / 1e6
            val real = connection.position().toDouble()
            val diff = real - est
            if (abs(diff) > 180) { anchorPos = real; anchorT = now } else anchorPos += diff * 0.08
            clock.ms = (anchorPos + (now - anchorT) / 1e6).toLong() + LEAD_MS
        }
    }
    val active by remember(rows) { derivedStateOf { val t = clock.ms; rows.indexOfLast { it.start <= t } } }

    val scroll = rememberScrollState()
    val tops = remember(rows) { FloatArray(rows.size) }
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    var userAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) { if (dragged) userAt = System.currentTimeMillis() }
    var placed by remember(rows) { mutableStateOf(false) }

    // Seguir a la línea activa: easeOutQuart de 620 ms (como en Spotify). El destino se recalcula en cada fotograma
    // porque una pausa que se abre o se cierra mueve las líneas mientras tanto. Tras arrastrar a mano, 2,5 s de calma.
    LaunchedEffect(active, dragged, userAt, placed) {
        if (dragged || !placed) return@LaunchedEffect
        val wait = userAt + USER_HOLD_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        val i = active.coerceAtLeast(0)
        // El relleno de arriba mide lo mismo que el ancla: con la línea en tops[i], scroll = tops[i] la deja en el ancla.
        fun target() = tops.getOrElse(i) { 0f }.coerceIn(0f, scroll.maxValue.toFloat())
        val from = scroll.value.toFloat()
        if (abs(target() - from) < 1f) return@LaunchedEffect
        runCatching {
            val p = Animatable(0f)
            p.animateTo(1f, tween(SCROLL_MS, easing = EaseOutQuart)) { scroll.dispatchRawDelta(lerp(from, target(), value) - scroll.value) }
        }
    }
    // Quitar o poner la romanización cambia el alto de todas mientras se despliega o se pliega: la que suena se queda
    // quieta en el ancla todo ese rato, y lo demás se mueve a su alrededor.
    LaunchedEffect(romanize) {
        if (!placed || dragged || System.currentTimeMillis() - userAt < USER_HOLD_MS) return@LaunchedEffect
        val end = System.nanoTime() + (ROM_MS + 80) * 1_000_000L
        while (System.nanoTime() < end) withFrameNanos {
            val i = active.coerceAtLeast(0)
            scroll.dispatchRawDelta(tops.getOrElse(i) { 0f }.coerceIn(0f, scroll.maxValue.toFloat()) - scroll.value)
        }
    }
    // Al abrirse, ya en su sitio.
    LaunchedEffect(placed) {
        if (!placed) return@LaunchedEffect
        val i = active.coerceAtLeast(0)
        scroll.scrollTo(tops.getOrElse(i) { 0f }.coerceIn(0f, scroll.maxValue.toFloat()).toInt())
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = EDGE, end = EDGE, top = height * ANCHOR, bottom = height * (1 - ANCHOR)),
    ) {
        val firstActive = remember(rows) { active }
        rows.forEachIndexed { i, row ->
            val dist = if (active < 0) i + 1 else i - active
            Box(
                Modifier
                    .fillMaxWidth()
                    .onPlaced {
                        tops[i] = it.positionInParent().y
                        if (i == rows.lastIndex) placed = true
                    },
            ) {
                when (row) {
                    is Row.Gap -> GapDots(row, dist == 0, clock)
                    is Row.Sung -> SungLine(row.line, dist, i - max(firstActive, 0), fetched?.get(row.line.text), romanize, clock, toggleRom) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        connection.seekTo((row.line.start - LEAD_MS).coerceAtLeast(0))
                        userAt = 0
                    }
                }
            }
        }
        Source(l.source, synced = l.wordByWord)
    }
}

@Composable
private fun Source(source: String, synced: Boolean) {
    Text(
        "Letra de $source" + if (synced) "" else " · por líneas",
        style = OtoType.label.copy(fontSize = 11.sp), color = Oto.colors.textFaint,
        modifier = Modifier.padding(top = 36.dp),
    )
}

/**
 * Una línea. Su brillo depende de la distancia a la que suena ([dist]: 0 la actual, −1 la que acaba de terminar).
 * El relevo entre frases dura un 30 % de la frase (420–1000 ms): la nueva se enciende en la mitad y la que se va
 * se apaga algo más despacio, así nunca queda la letra sin frase marcada.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SungLine(line: Line, dist: Int, order: Int, rom: String?, showRom: Boolean, clock: LyricsClock, onHold: (() -> Unit)?, onTap: () -> Unit) {
    val c = Oto.colors
    val dur = ((line.end - line.start) * 0.3).toInt().coerceIn(420, 1000)
    val alphaTarget = when {
        dist == 0 -> 1f
        dist < 0 -> 0.34f
        dist == 1 -> 0.62f
        dist == 2 -> 0.42f
        dist == 3 -> 0.3f
        else -> 0.24f
    }
    val alpha by animateFloatAsState(alphaTarget, tween(if (dist == 0) dur / 2 else if (dist < 0) (dur * 1.4f).toInt() else dur, easing = EaseOutQuart), label = "lineAlpha")
    val scale by animateFloatAsState(if (dist == 0) 1.03f else 1f, tween(if (dist == 0) dur else (dur * 1.2f).toInt(), easing = EaseOutQuart), label = "lineScale")
    // Al abrir la letra las líneas suben en cascada desde la activa.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
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
            .graphicsLayer {
                val e = enter.value
                this.alpha = alpha * e
                translationY = (1 - e) * 46.dp.toPx()
                val s = scale * lerp(0.965f, 1f, e)
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(if (line.duet) 1f else 0f, 0.5f)
            }
            .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onHold, onClick = onTap)
            .padding(vertical = 9.dp),
        horizontalAlignment = align,
    ) {
        if (!bgOnly) Words(line.words, LYRIC, lit, clock, arrangement, c.text, letters = line.real)
        if (rom != null && !bgOnly) {
            // Se despliega (o se pliega) bajo su línea, en vez de aparecer de golpe.
            AnimatedVisibility(
                showRom,
                enter = expandVertically(tween(ROM_MS, easing = EaseOutQuart), expandFrom = Alignment.Top) + fadeIn(tween(ROM_MS, delayMillis = ROM_MS / 4)),
                exit = shrinkVertically(tween(ROM_MS, easing = EaseOutQuart), shrinkTowards = Alignment.Top) + fadeOut(tween(ROM_MS / 2)),
            ) {
                val romWords = remember(rom, line) { spread(rom, line.words.first().start, line.words.last().end) }
                Words(romWords, ROM, lit, clock, arrangement, c.text.copy(alpha = 0.72f), rise = false, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (line.bg.isNotEmpty()) {
            Words(line.bg, if (bgOnly) LYRIC.copy(fontSize = LYRIC.fontSize * 0.8f, lineHeight = LYRIC.lineHeight * 0.8f) else BG, lit, clock, arrangement,
                c.text.copy(alpha = if (bgOnly) 0.9f else 0.78f), letters = line.real, modifier = Modifier.padding(top = if (bgOnly) 0.dp else 4.dp))
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
            val t = clock.ms
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
                    up * if (t > hold) 1f - EaseOutExpo.transform(((t - hold) / 900f).coerceIn(0f, 1f)) else 1f
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
    val c = Oto.colors
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
                    drawCircle(c.text.copy(alpha = lerp(0.28f, 1f, fk)), r, Offset(r + 4.dp.toPx() + k * step, size.height / 2))
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

// La letra exacta parece ir atrasada al leerla: va un poco por delante (en el tema de Spotify, 250 ms).
// Como en el tema de Spotify del PC (LYR_LEAD): la letra exacta, al leerla, parece ir atrasada.
private const val LEAD_MS = 250L
private val RETRY_MS = listOf(20_000L, 90_000L)

/** Sin letra < sin sincronizar < por líneas < palabra a palabra. */
private fun rank(l: Lyrics?) = when (l) {
    is Lyrics.Synced -> if (l.wordByWord) 3 else 2
    is Lyrics.Plain -> 1
    else -> 0
}
// La línea activa queda a esta altura del panel.
// Margen a cada lado del texto para lo que crece al cantarse (ver LyricsPanel).
private val EDGE = 20.dp
private const val ANCHOR = 0.3f
private const val SCROLL_MS = 620
private const val USER_HOLD_MS = 2500L
private const val ROM_MS = 420
private const val GAP_MS = 6000L
private const val GAP_INTRO_MS = 3500L
