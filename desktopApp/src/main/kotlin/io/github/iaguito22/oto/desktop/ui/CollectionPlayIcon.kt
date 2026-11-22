package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import io.github.iaguito22.oto.playback.CollectionPlayback

internal fun CollectionPlayback.label(title: String) = when (this) {
    CollectionPlayback.Start -> "Reproducir $title"
    CollectionPlayback.Resume -> "Reanudar $title"
    CollectionPlayback.Pause -> "Pausar $title"
}

@Composable
internal fun CollectionPlayIcon(playing: Boolean, size: Dp, tint: Color = Color.White) {
    AnimatedContent(playing, transitionSpec = {
        (fadeIn(tween(140)) + scaleIn(Motion.hover(), initialScale = .6f)) togetherWith
            (fadeOut(tween(100)) + scaleOut(tween(120), targetScale = .6f))
    }, label = "iconoColección") {
        Icon(if (it) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(size), tint = tint)
    }
}
