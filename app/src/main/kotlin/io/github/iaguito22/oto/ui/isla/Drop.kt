package io.github.iaguito22.oto.ui.isla

import android.graphics.RuntimeShader
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.iaguito22.oto.DropStyle
import io.github.iaguito22.oto.playback.AudioLevels
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/** Lo que se puede pedir a la gota. */
enum class Gesture { Toggle, Next, Previous, Menu, Queue, Lyrics }

/**
 * La física de la gota B1 del prototipo, a paso fijo para que se mueva igual a 60 que a 120 Hz.
 *
 * Un punto sigue al dedo con un muelle (más firme mientras se arrastra, blando y con rebote al soltar) y de él
 * cuelgan tres círculos: el cuerpo apenas se mueve, una gota intermedia hace de puente y el núcleo de luz va
 * con el dedo. Fundidos con smooth-min quedan como el filtro «goo» del prototipo.
 */
class DropState {
    /** Lo que se dibuja cambia en cada fotograma; leer esto invalida solo el dibujo, nunca la composición. */
    var frame by mutableLongStateOf(0L)
        private set

    internal var px = 0f; internal var py = 0f
    private var vx = 0f; private var vy = 0f
    internal var target: Offset? = null
    internal var pressTarget = 1f
    internal var press = 1f
    internal var wobble = 1f
    internal var phase = 0f
    internal var pulse = 0f
    internal var alpha = 1f
    private var speed = 1f
    private var leftover = 0f

    /** Cuánto está la gota llevada a un lado: -1 (izquierda) … 1 (derecha). Al soltar vuelve a 0 con su muelle. */
    val lean: Float get() = (px / leanPx).coerceIn(-1f, 1f)
    internal var leanPx = 1f

    /** El dedo está tocando la gota (para el tutorial). */
    val touching get() = target != null || pressTarget > 1f

    internal fun step(dtRaw: Float, playing: Boolean, level: Float) {
        val dt = dtRaw.coerceIn(0f, 0.05f)
        leftover += dt
        val following = target != null
        val k = if (following) FOLLOW_K else REST_K
        val c = if (following) FOLLOW_C else REST_C
        val gx = target?.x ?: 0f
        val gy = target?.y ?: 0f
        while (leftover >= STEP) {
            vx += (k * (gx - px) - c * vx) * STEP
            vy += (k * (gy - py) - c * vy) * STEP
            px += vx * STEP
            py += vy * STEP
            leftover -= STEP
        }
        press += (pressTarget - press) * (1 - exp(-dt / 0.07f))
        wobble += ((if (following) 0f else 1f) - wobble) * (1 - exp(-dt / 0.25f))
        speed += ((if (playing) 1f else 0.22f) - speed) * (1 - exp(-dt / 0.6f))
        phase += dt * speed
        // El golpe entra de golpe y se va despacio, como la luz de un vúmetro.
        val l = if (playing) level else 0f
        pulse = if (l > pulse) pulse + (l - pulse) * min(1f, dt / 0.025f) else pulse * exp(-dt / 0.16f)
        alpha += ((if (playing) 1f else 0.7f) - alpha) * (1 - exp(-dt / 0.2f))
        frame++
    }

    private companion object {
        // Las constantes del prototipo (por fotograma a 60 Hz) pasadas a muelle continuo.
        const val STEP = 1f / 240f
        const val FOLLOW_K = 792f
        const val FOLLOW_C = 28.7f
        const val REST_K = 252f
        const val REST_C = 13.4f
    }
}

/** Dirección de un arrastre, o null mientras no pasa del umbral. */
private fun directionOf(dx: Float, dy: Float, threshold: Float): Gesture? = when {
    hypot(dx, dy) < threshold -> null
    abs(dx) > abs(dy) -> if (dx > 0) Gesture.Next else Gesture.Previous
    dy < 0 -> Gesture.Menu
    else -> Gesture.Lyrics
}

/**
 * La gota: se dibuja con un shader AGSL y entiende los cinco gestos. [onCue] avisa de lo que pasará al soltar
 * (para la etiqueta de encima); [onGesture], de lo que se ha pedido.
 */
@Composable
fun Drop(
    state: DropState,
    playing: Boolean,
    style: DropStyle,
    accent: () -> Color,
    visible: () -> Float,
    onCue: (Gesture?, hold: Boolean) -> Unit,
    onGesture: (Gesture) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val isPlaying by rememberUpdatedState(playing)
    val cue by rememberUpdatedState(onCue)
    val gesture by rememberUpdatedState(onGesture)

    // Solo corre mientras la app se ve: con la pantalla apagada no se gasta ni un fotograma.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    state.step((now - last) / 1e9f, isPlaying, AudioLevels.now())
                    last = now
                }
            }
        }
    }

    state.leanPx = with(density) { 96.dp.toPx() }
    // La huella es el contorno de la misma gota: se estira, se parte y late igual, pero solo con un trazo fino.
    val shader = remember(style) { RuntimeShader(if (style == DropStyle.Ring) RING_AGSL else DROP_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val px = remember(density) {
        with(density) { object {
            val body = 43.dp.toPx()
            val mid = 20.dp.toPx()
            val core = 21.dp.toPx()
            val bodyBeat = 1.8.dp.toPx()
            val coreBeat = 5.4.dp.toPx()
            val wobbleX = 12.dp.toPx()
            val wobbleY = 9.5.dp.toPx()
            val smooth = 23.dp.toPx()
            val soft = 10.dp.toPx()
            val shadow = 8.dp.toPx()
            val bleed = 90.dp.toPx()
            val slop = 7.dp.toPx()
            val reach = 52.dp.toPx()
            val threshold = 52.dp.toPx()
            val kxIn = 92.dp.toPx(); val kxOut = 108.dp.toPx()
            val kyIn = 58.dp.toPx(); val kyOut = 68.dp.toPx()
            val ringStroke = 3.dp.toPx()
        } }
    }

    val draw = modifier
        .drawBehind {
            state.frame
            val v = visible()
            if (v <= 0f) return@drawBehind
            val cx = size.width / 2
            val cy = size.height / 2
            val s = state.press

            val wx = sin(state.phase * 1.6f) * px.wobbleX * state.wobble
            val wy = cos(state.phase * 1.3f) * px.wobbleY * state.wobble
            shader.setFloatUniform("b0", cx + state.px * 0.16f, cy + state.py * 0.16f, (px.body + px.bodyBeat * state.pulse) * s)
            shader.setFloatUniform("b1", cx + state.px * 0.52f, cy + state.py * 0.52f, px.mid * s)
            shader.setFloatUniform("b2", cx + state.px + wx, cy + state.py + wy, (px.core + px.coreBeat * state.pulse) * s)
            shader.setFloatUniform("k", px.smooth)
            shader.setFloatUniform("alpha", state.alpha * v)
            val a = accent()
            if (style == DropStyle.Ring) {
                shader.setFloatUniform("width", px.ringStroke)
                shader.setColorUniform("color", a.toArgb())
                drawRect(brush, Offset(0f, -px.bleed), Size(size.width, size.height + 2 * px.bleed))
                return@drawBehind
            }
            shader.setFloatUniform("soft", px.soft)
            shader.setFloatUniform("shadowY", px.shadow)
            shader.setColorUniform("body", lerp(BodyBase, a, 0.26f).toArgb())
            shader.setColorUniform("bright", lerp(Color.White, a, 0.82f).toArgb())
            drawRect(brush, Offset(0f, -px.bleed), Size(size.width, size.height + 2 * px.bleed))
        }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Solo la gota (con un poco de margen), no toda la franja de abajo: tocar el borde de la pantalla
                // para que no se apague no puede pausar la música.
                if (hypot(down.position.x - size.width / 2f, down.position.y - size.height / 2f) > px.reach) return@awaitEachGesture
                down.consume()
                state.pressTarget = 1.1f
                var moved = false
                var held = false
                var dir: Gesture? = null
                var hold: Job? = scope.launch {
                    delay(HOLD_HINT_MS)
                    cue(Gesture.Queue, true)
                    delay(HOLD_MS - HOLD_HINT_MS)
                    held = true
                    state.pressTarget = 1f
                    cue(null, false)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    gesture(Gesture.Queue)
                }
                var finished = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            finished = true
                            break
                        }
                        change.consume()
                        if (held) continue
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!moved && hypot(dx, dy) > px.slop) {
                            moved = true
                            hold?.cancel(); hold = null
                            cue(null, false)
                            state.pressTarget = 1f
                        }
                        if (!moved) continue
                        val d = directionOf(dx, dy, px.threshold)
                        if (d != dir) {
                            dir = d
                            cue(d, false)
                            if (d != null) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                        state.target = Offset(px.kxOut * tanh(dx / px.kxIn), px.kyOut * tanh(dy / px.kyIn))
                    }
                } finally {
                    hold?.cancel()
                    state.target = null
                    state.pressTarget = 1f
                    if (!held) cue(null, false)
                }
                if (finished && !held) {
                    if (!moved) gesture(Gesture.Toggle) else dir?.let(gesture)
                }
            }
        }
    Box(draw)
}

private val BodyBase = Color(0xFF151515)
private const val HOLD_HINT_MS = 150L
private const val HOLD_MS = 480L

/**
 * Tres círculos fundidos con smooth-min (el cuerpo, el puente y el núcleo). El núcleo se ve como luz con el
 * borde difuso, igual que en el filtro «goo», donde el color también se desenfoca. Sombra suave debajo y un
 * reflejo finito arriba para que parezca líquido y no un círculo plano.
 */
private const val DROP_AGSL = """
uniform float3 b0;
uniform float3 b1;
uniform float3 b2;
uniform float k;
uniform float soft;
uniform float shadowY;
uniform float alpha;
layout(color) uniform half4 body;
layout(color) uniform half4 bright;

float smin(float a, float b, float r) {
    float h = max(r - abs(a - b), 0.0) / r;
    return min(a, b) - h * h * r * 0.25;
}
float circle(float2 p, float3 c) { return length(p - c.xy) - c.z; }
float field(float2 p) { return smin(smin(circle(p, b0), circle(p, b1), k), circle(p, b2), k); }

half4 main(float2 p) {
    float d = field(p);
    float shade = (1.0 - smoothstep(-soft, soft * 2.8, field(p - float2(0.0, shadowY)))) * 0.34;
    if (d > 1.0) return half4(0.0, 0.0, 0.0, shade * alpha);

    float light = 1.0 - smoothstep(-soft * 0.55, soft * 0.75, smin(circle(p, b1), circle(p, b2), k * 0.6));
    half3 col = mix(body.rgb, bright.rgb, light);
    float2 n = normalize(float2(field(p + float2(1.0, 0.0)) - field(p - float2(1.0, 0.0)),
                                field(p + float2(0.0, 1.0)) - field(p - float2(0.0, 1.0))) + 1e-5);
    float rim = smoothstep(-soft * 0.6, 0.0, d) * max(0.0, dot(n, normalize(float2(-0.35, -1.0))));
    col += half3(rim * 0.16 * (1.0 - light));

    float a = clamp(0.5 - d, 0.0, 1.0);
    half4 drop = half4(col * a, a);
    return (drop + half4(0.0, 0.0, 0.0, shade) * (1.0 - a)) * alpha;
}
"""

/** El aro: el borde de la misma figura que [DROP_AGSL], con un trazo de [width] y sin relleno ni sombra. */
private const val RING_AGSL = """
uniform float3 b0;
uniform float3 b1;
uniform float3 b2;
uniform float k;
uniform float width;
uniform float alpha;
layout(color) uniform half4 color;

float smin(float a, float b, float r) {
    float h = max(r - abs(a - b), 0.0) / r;
    return min(a, b) - h * h * r * 0.25;
}
float circle(float2 p, float3 c) { return length(p - c.xy) - c.z; }
float field(float2 p) { return smin(smin(circle(p, b0), circle(p, b1), k), circle(p, b2), k); }

half4 main(float2 p) {
    float a = clamp(width * 0.5 - abs(field(p)) + 0.5, 0.0, 1.0) * alpha;
    return color * a;
}
"""
