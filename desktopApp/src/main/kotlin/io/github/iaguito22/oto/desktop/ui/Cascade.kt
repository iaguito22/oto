package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Entrada escalonada de una vista: cada elemento que se compone por primera vez
 * mientras la vista acaba de aparecer entra con un pequeño retraso respecto al
 * anterior. Lo que aparece después (al desplazarse) se muestra sin animación.
 */
internal class Cascade(val startedAt: Long) { var next = 0 }

internal val LocalCascade = staticCompositionLocalOf<Cascade?> { null }

private const val STEP_MS = 22L
private const val MAX_STEPS = 14
private const val WINDOW_MS = STEP_MS * MAX_STEPS + 120

@Composable
internal fun CascadeScope(key: Any?, content: @Composable () -> Unit) {
    val cascade = remember(key) { Cascade(System.nanoTime()) }
    CompositionLocalProvider(LocalCascade provides cascade, content = content)
}

@Composable
internal fun Modifier.cascadeIn(): Modifier {
    val cascade = LocalCascade.current
    val delayMs = remember {
        if (cascade == null) -1L else {
            val elapsed = (System.nanoTime() - cascade.startedAt) / 1_000_000
            if (elapsed > WINDOW_MS) -1L
            else (cascade.next++.coerceAtMost(MAX_STEPS) * STEP_MS - elapsed).coerceAtLeast(0L)
        }
    }
    val progress = remember { Animatable(if (delayMs < 0) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            if (delayMs > 0) delay(delayMs)
            progress.animateTo(1f, Motion.slide())
        }
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 16.dp.toPx()
    }
}
