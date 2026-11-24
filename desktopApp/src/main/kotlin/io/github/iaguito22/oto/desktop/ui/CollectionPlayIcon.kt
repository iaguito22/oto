package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.iaguito22.oto.playback.CollectionPlayback

internal fun CollectionPlayback.label(title: String) = when (this) {
    CollectionPlayback.Start -> "Reproducir $title"
    CollectionPlayback.Resume -> "Reanudar $title"
    CollectionPlayback.Pause -> "Pausar $title"
}

private enum class CollectionGlyph { Play, Pause, Bars }

/** Mientras suena, barras en movimiento; la pausa solo aparece al pasar el ratón ([hovered]). */
@Composable
internal fun CollectionPlayIcon(playing: Boolean, size: Dp, tint: Color = Color.White, hovered: Boolean = true) {
    val glyph = if (!playing) CollectionGlyph.Play else if (hovered) CollectionGlyph.Pause else CollectionGlyph.Bars
    AnimatedContent(glyph, transitionSpec = {
        (fadeIn(tween(140)) + scaleIn(Motion.hover(), initialScale = .6f)) togetherWith
            (fadeOut(tween(100)) + scaleOut(tween(120), targetScale = .6f))
    }, label = "iconoColección") {
        when (it) {
            CollectionGlyph.Bars -> SoundBars(size, tint)
            else -> Icon(if (it == CollectionGlyph.Pause) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(size), tint = tint)
        }
    }
}

private val barTimings = listOf(520, 680, 440, 600)

@Composable
private fun SoundBars(size: Dp, tint: Color) {
    val motion = rememberInfiniteTransition(label = "barrasSonido")
    val heights = barTimings.mapIndexed { i, duration ->
        motion.animateFloat(.25f + .1f * (i % 2), 1f,
            infiniteRepeatable(tween(duration, easing = FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(i * 130)),
            label = "barra$i")
    }
    Canvas(Modifier.size(size).semantics { contentDescription = "Sonando" }) {
        val bar = this.size.width * .13f
        val gap = this.size.width * .08f
        val total = barTimings.size * bar + (barTimings.size - 1) * gap
        val max = this.size.height * .66f
        var x = (this.size.width - total) / 2
        val bottom = (this.size.height + max) / 2
        for (h in heights) {
            val height = max * h.value
            drawRoundRect(tint, Offset(x, bottom - height), Size(bar, height), CornerRadius(bar / 2))
            x += bar + gap
        }
    }
}
