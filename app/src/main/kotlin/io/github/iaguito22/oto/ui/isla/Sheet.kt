package io.github.iaguito22.oto.ui.isla

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.iaguito22.oto.ui.theme.Oto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

enum class SheetKind { Menu, Queue }

/**
 * La hoja que sale de la gota: no aparece encima, es la gota que crece hasta ser hoja (y al cerrarse vuelve a
 * encogerse en ella). [morph] va de 0 (la gota) a 1 (abierta) con muelle, y lo mismo sirve para el gesto atrás
 * predictivo, que la va encogiendo hacia la gota mientras se arrastra.
 */
@Stable
class SheetState(private val scope: CoroutineScope) {
    var kind by mutableStateOf<SheetKind?>(null)
        private set
    val morph = Animatable(0f)
    internal var drag by mutableFloatStateOf(0f)
    private var settle: Job? = null

    val isOpen get() = kind != null

    /** De dónde sale la hoja si no es de la gota (por ejemplo, del nombre del álbum de arriba). */
    var origin by mutableStateOf<Offset?>(null)
        private set

    fun open(k: SheetKind, from: Offset? = null) {
        kind = k
        origin = from
        settle?.cancel()
        drag = 0f
        scope.launch { morph.animateTo(1f, OPEN) }
    }

    fun close() {
        if (kind == null) return
        toDrop()
        settle?.cancel()
        val from = drag
        settle = scope.launch { animate(from, 0f, animationSpec = CLOSE) { v, _ -> drag = v } }
        scope.launch {
            morph.animateTo(0f, CLOSE)
            kind = null
        }
    }

    /**
     * Abierta desde arriba (el origen), se cierra igual que todas: encogiéndose en la gota. Se cambia con la hoja
     * aún (casi) abierta del todo, donde las dos formas coinciden, así no salta; cerrada a medio abrir, vuelve por
     * donde vino.
     */
    internal fun toDrop() {
        if (morph.value > 0.85f) origin = null
    }

    internal fun dragBy(dy: Float) {
        settle?.cancel()
        drag = (drag + dy).coerceAtLeast(0f)
    }

    /** Al soltar: cierra si se bajó bastante o con fuerza; si no, vuelve a su sitio. */
    internal fun release(velocity: Float, threshold: Float, flingThreshold: Float) {
        if (drag > threshold || (drag > 0f && velocity > flingThreshold)) return close()
        val from = drag
        settle = scope.launch { animate(from, 0f, initialVelocity = velocity, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)) { v, _ -> drag = v } }
    }

    internal companion object {
        // El prototipo usa cubic-bezier(.38,1.21,.22,1) en 500 ms: un pelo de rebote al llegar.
        val OPEN = spring<Float>(dampingRatio = 0.78f, stiffness = 230f)
        val CLOSE = spring<Float>(dampingRatio = 0.9f, stiffness = 340f)
    }
}

@Composable
fun rememberSheetState(): SheetState {
    val scope = rememberCoroutineScope()
    return remember { SheetState(scope) }
}

/**
 * [dropCenter] es el centro de la gota en coordenadas de la pantalla y [dropRadius] su radio: de ahí sale la hoja.
 * El contenido se mide ya al tamaño final y se recorta con la forma que crece, así no se rehace en cada fotograma.
 */
@Composable
fun SheetHost(
    state: SheetState,
    dropCenter: () -> Offset,
    dropRadius: Float,
    content: @Composable (SheetKind) -> Unit,
) {
    val kind = state.kind ?: return
    val c = Oto.colors
    val density = LocalDensity.current
    val dropBody = lerp(Color(0xFF151515), c.accent, 0.26f)
    val bright = lerp(Color.White, c.accent, 0.82f)

    PredictiveBackHandler(enabled = true) { progress ->
        state.toDrop()
        try {
            progress.collect { e -> state.morph.snapTo(1f - 0.16f * (1f - (1f - e.progress) * (1f - e.progress))) }
            state.close()
        } catch (e: CancellationException) {
            state.morph.animateTo(1f, SheetState.OPEN)
            throw e
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val top = WindowInsets.statusBars.getTop(density)
        val bottom = maxOf(WindowInsets.navigationBars.getBottom(density), WindowInsets.ime.getBottom(density))
        val margin = with(density) { 10.dp.toPx() }
        val target = Rect(
            left = margin,
            top = if (kind == SheetKind.Queue) h * 0.34f else top + with(density) { 6.dp.toPx() },
            right = w - margin,
            bottom = h - bottom - margin,
        )
        val radius = with(density) { 30.dp.toPx() }
        val closeAt = with(density) { 110.dp.toPx() }
        val flingAt = with(density) { 1200.dp.toPx() }

        // Fuera de la hoja: oscurece lo de detrás y un toque cierra.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.morph.value.coerceIn(0f, 1f) }
                .drawBehind { drawRect(Color.Black.copy(alpha = 0.32f)) }
                .pointerInput(Unit) { detectTapGestures { state.close() } },
        )

        val shape = remember(target) { MorphShape(state, target, dropCenter, dropRadius, radius) }
        val nested = remember(state) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (state.drag > 0f && available.y < 0f) {
                        val used = maxOf(available.y, -state.drag)
                        state.dragBy(used)
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0f && source == NestedScrollSource.UserInput) {
                        state.dragBy(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (state.drag <= 0f) return Velocity.Zero
                    state.release(available.y, closeAt, flingAt)
                    return available
                }
            }
        }

        Box(
            Modifier
                .layout { measurable, _ ->
                    val p = measurable.measure(Constraints.fixed(target.width.roundToInt(), target.height.roundToInt()))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(target.left.roundToInt(), target.top.roundToInt()) }
                }
                .graphicsLayer {
                    translationY = state.drag * state.morph.value.coerceIn(0f, 1f)
                    this.shape = shape.copyForFrame()
                    clip = true
                }
                .drawBehind {
                    val m = state.morph.value
                    val r = shape.localRect(m)
                    val from = if (state.origin == null) dropBody else Color(0xFF161616).copy(alpha = 0.78f)
                    val glass = lerp(from, Color(0xFF161616).copy(alpha = 0.78f), (m / 0.4f).coerceIn(0f, 1f))
                    drawRect(glass)
                    // El resplandor del acento abajo, como en el tema de Spotify.
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, c.accent.copy(alpha = 0.1f)), startY = r.bottom - 90.dp.toPx(), endY = r.bottom),
                        Offset(r.left, r.bottom - 90.dp.toPx()), Size(r.width, 90.dp.toPx()),
                    )
                    // Mientras aún es gota, el núcleo de luz se apaga en vez de desaparecer de golpe.
                    val core = 1f - (m / 0.3f).coerceIn(0f, 1f)
                    if (core > 0f && state.origin == null) {
                        val o = dropCenter() - Offset(target.left, target.top)
                        drawCircle(
                            Brush.radialGradient(listOf(bright.copy(alpha = core), bright.copy(alpha = 0f)), o, 30.dp.toPx()),
                            30.dp.toPx(), o,
                        )
                    }
                    drawRoundRect(
                        Color.White.copy(alpha = 0.1f * (m / 0.5f).coerceIn(0f, 1f)),
                        r.topLeft, r.size, CornerRadius(shape.cornerFor(m)), style = Stroke(1.dp.toPx()),
                    )
                }
                .nestedScroll(nested)
                .draggable(
                    rememberDraggableState { state.dragBy(it) },
                    Orientation.Vertical,
                    onDragStopped = { v -> state.release(v, closeAt, flingAt) },
                ),
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((state.morph.value - 0.55f) / 0.45f).coerceIn(0f, 1f) }) {
                content(kind)
            }
        }
    }
}

/** El contorno de la hoja en cada momento: de círculo (la gota) a rectángulo redondeado (la hoja). */
private class MorphShape(
    private val state: SheetState,
    private val target: Rect,
    private val dropCenter: () -> Offset,
    private val dropRadius: Float,
    private val radius: Float,
) : Shape {
    private var frame = 0

    fun localRect(m: Float): Rect {
        val c = (state.origin ?: dropCenter()) - Offset(target.left, target.top)
        val r = if (state.origin == null) dropRadius else dropRadius * 0.5f
        val drop = Rect(c.x - r, c.y - r, c.x + r, c.y + r)
        // Durante el arrastre la hoja ya está desplazada por translationY: la gota se compensa.
        val shift = state.drag * m.coerceIn(0f, 1f)
        return lerp(drop.translate(0f, -shift), Rect(0f, 0f, target.width, target.height), m)
    }

    fun cornerFor(m: Float) = lerp(if (state.origin == null) dropRadius else dropRadius * 0.5f, radius, m.coerceIn(0f, 1f))

    /** graphicsLayer solo vuelve a pedir el contorno si cambia la forma: un objeto nuevo por fotograma. */
    fun copyForFrame(): Shape {
        frame++
        val m = state.morph.value
        val r = localRect(m)
        val corner = cornerFor(m)
        return object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
                Outline.Rounded(RoundRect(r, CornerRadius(corner)))
        }
    }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(localRect(state.morph.value), CornerRadius(cornerFor(state.morph.value))))
}
