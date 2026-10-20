package io.github.iaguito22.oto.ui.isla

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

private class Step(val gestures: Set<Gesture>, val title: String, val body: String)

private val STEPS = listOf(
    Step(setOf(Gesture.Toggle), "Toca la gota", "Pausa la canción, o la sigue."),
    Step(setOf(Gesture.Next, Gesture.Previous), "Llévala a un lado", "A la derecha, pasa a la siguiente canción.\nA la izquierda, vuelve a la anterior."),
    Step(setOf(Gesture.Menu), "Súbela", "El Menú: buscar, explorar y tu música."),
    Step(setOf(Gesture.Queue), "Mantenla pulsada", "La cola: las canciones que vienen después."),
    Step(setOf(Gesture.Lyrics), "Bájala", "La letra de la canción."),
)

/**
 * Se aprende haciendo: cada paso pide un gesto, un dedo fantasma lo enseña sobre la gota y no se avanza
 * hasta hacerlo. Mientras dura, los gestos no tocan la música.
 */
@Stable
class TutorialState {
    var step by mutableIntStateOf(-1)
        private set
    /** Instante del último acierto: dispara el «eso es» y el paso siguiente. */
    var success by mutableStateOf(false)
        private set
    var miss by mutableIntStateOf(0)
        private set

    val active get() = step >= 0
    val finished get() = step == STEPS.size

    fun start() {
        step = 0
        success = false
    }

    fun skip() {
        step = -1
    }

    /** true si era el gesto que tocaba. */
    fun attempt(g: Gesture): Boolean {
        if (success || step !in STEPS.indices) return false
        if (g in STEPS[step].gestures) {
            success = true
            return true
        }
        miss++
        return false
    }

    internal fun advance() {
        success = false
        step++
    }
}

@Composable
fun Tutorial(state: TutorialState, dropCenter: () -> Offset, drop: DropState, onDone: () -> Unit) {
    val c = Oto.colors
    val visible = state.active
    val shown by animateFloatAsState(if (visible) 1f else 0f, tween(Motion.SPATIAL_FAST, easing = Motion.Standard), label = "tutorial")
    if (shown == 0f && !visible) return

    LaunchedEffect(state.success) {
        if (!state.success) return@LaunchedEffect
        delay(SUCCESS_MS)
        state.advance()
    }
    LaunchedEffect(state.finished) {
        if (!state.finished) return@LaunchedEffect
        delay(DONE_MS)
        onDone()
        state.skip()
    }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state.miss) {
        if (state.miss == 0) return@LaunchedEffect
        shake.snapTo(1f)
        shake.animateTo(0f, tween(420, easing = LinearEasing))
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown }
            .drawBehind {
                // Todo en penumbra menos la gota, que es lo que hay que tocar.
                val o = dropCenter()
                drawRect(
                    Brush.radialGradient(
                        0f to Color.Black.copy(alpha = 0f), 0.18f to Color.Black.copy(alpha = 0f), 0.5f to Color.Black.copy(alpha = 0.72f),
                        center = o, radius = size.height * 0.9f,
                    ),
                )
            },
    ) {
        Text(
            "Saltar",
            style = OtoType.rowTitle, color = c.textMuted,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(Space.md)
                .clip(CircleShape)
                .clickable(remember { MutableInteractionSource() }, null) { onDone(); state.skip() }
                .padding(horizontal = Space.lg, vertical = Space.sm),
        )

        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = Space.xl)
                .graphicsLayer { translationX = sin(shake.value * PI.toFloat() * 6) * shake.value * 14.dp.toPx() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val step = state.step.coerceIn(0, STEPS.size)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                STEPS.indices.forEach { i ->
                    val on by animateFloatAsState(if (i < step || (i == step && state.success)) 1f else if (i == step) 0.5f else 0f, spring(stiffness = 500f), label = "dot")
                    Box(Modifier.height(6.dp).width(6.dp + 12.dp * on).clip(CircleShape).background(lerpColor(c.textFaint, c.accent, on)))
                }
            }
            Spacer(Modifier.height(Space.xl))
            AnimatedContent(
                targetState = Triple(step, state.success, state.finished),
                transitionSpec = {
                    (fadeIn(tween(Motion.EFFECTS, delayMillis = 60)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 5 }) togetherWith
                        (fadeOut(tween(Motion.EFFECTS_FAST)) + slideOutVertically(tween(Motion.EFFECTS_FAST)) { -it / 8 })
                },
                contentAlignment = Alignment.Center,
                label = "step",
            ) { (i, ok, finished) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        finished -> {
                            Text("Ya está", style = OtoType.display, color = c.text, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(Space.sm))
                            Text("Sube la gota y busca algo.", style = OtoType.body, color = c.textMuted, textAlign = TextAlign.Center)
                        }
                        ok -> {
                            Icon(painterResource(R.drawable.ic_check), null, Modifier.size(44.dp), tint = c.accent)
                            Spacer(Modifier.height(Space.sm))
                            Text("Eso es", style = OtoType.display, color = c.text, textAlign = TextAlign.Center)
                        }
                        else -> {
                            Text(STEPS[i].title, style = OtoType.display, color = c.text, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(Space.sm))
                            Text(STEPS[i].body, style = OtoType.body, color = c.textMuted, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        if (visible && !state.success && !state.finished) Ghost(STEPS[state.step.coerceIn(STEPS.indices)].gestures.first(), dropCenter, drop)
    }
}

/** Un dedo fantasma que repite el gesto encima de la gota; se aparta en cuanto la tocas. */
@Composable
private fun Ghost(g: Gesture, dropCenter: () -> Offset, drop: DropState) {
    val t = remember(g) { Animatable(0f) }
    LaunchedEffect(g) {
        delay(600)
        while (isActive) {
            t.snapTo(0f)
            t.animateTo(1f, tween(GHOST_MS, easing = LinearEasing))
            delay(500)
        }
    }
    val away by animateFloatAsState(if (drop.touching) 0f else 1f, tween(Motion.EFFECTS), label = "ghost")
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val p = t.value
                // Baja, hace el gesto y se levanta: aparece y desaparece en los extremos.
                val fade = (p / 0.12f).coerceIn(0f, 1f) * ((1f - p) / 0.15f).coerceIn(0f, 1f) * away
                if (fade <= 0f) return@drawBehind
                val move = FastOutSlowInEasing.transform(((p - 0.18f) / 0.5f).coerceIn(0f, 1f))
                val reach = 78.dp.toPx()
                val o = dropCenter() + when (g) {
                    Gesture.Next, Gesture.Previous -> Offset(reach * move, 0f)
                    Gesture.Menu -> Offset(0f, -reach * move)
                    Gesture.Lyrics -> Offset(0f, reach * 0.8f * move)
                    else -> Offset.Zero
                }
                val press = when (g) {
                    Gesture.Toggle -> 1f - 0.25f * sin((p.coerceIn(0.2f, 0.45f) - 0.2f) / 0.25f * PI.toFloat())
                    else -> 1f
                }
                val r = 22.dp.toPx() * press
                drawCircle(Color.White.copy(alpha = 0.28f * fade), r, o)
                drawCircle(Color.White.copy(alpha = 0.6f * fade), r, o, style = Stroke(1.5.dp.toPx()))
                if (g == Gesture.Queue) {
                    val hold = ((p - 0.15f) / 0.45f).coerceIn(0f, 1f)
                    drawArc(Color.White.copy(alpha = 0.85f * fade), -90f, 360f * hold, false, o - Offset(r + 6.dp.toPx(), r + 6.dp.toPx()),
                        androidx.compose.ui.geometry.Size((r + 6.dp.toPx()) * 2, (r + 6.dp.toPx()) * 2), style = Stroke(2.dp.toPx()))
                }
            },
    )
}

private fun lerpColor(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f.coerceIn(0f, 1f))

private const val SUCCESS_MS = 750L
private const val DONE_MS = 1700L
private const val GHOST_MS = 1500
