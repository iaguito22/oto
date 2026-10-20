package io.github.iaguito22.oto.ui.isla

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType

/** La etiqueta que sale encima de la gota: dice qué pasará al soltar. [hold] dibuja la barra de «mantén». */
data class CueLabel(@param:DrawableRes val icon: Int, val text: String, val hold: Boolean = false)

fun Gesture.label(playing: Boolean, hold: Boolean = false) = when (this) {
    Gesture.Toggle -> if (playing) CueLabel(R.drawable.ic_pause, "Pausa") else CueLabel(R.drawable.ic_play_arrow, "Seguir")
    Gesture.Next -> CueLabel(R.drawable.ic_skip_next, "Siguiente")
    Gesture.Previous -> CueLabel(R.drawable.ic_skip_previous, "Anterior")
    Gesture.Menu -> CueLabel(R.drawable.ic_library_music, "Menú")
    Gesture.Queue -> CueLabel(R.drawable.ic_queue_music, "Cola", hold)
    Gesture.Lyrics -> CueLabel(R.drawable.ic_lyrics, "Letra")
}

@Composable
fun Cue(label: CueLabel?, modifier: Modifier = Modifier) {
    val c = Oto.colors
    // Al irse sigue enseñando la última etiqueta mientras se desvanece.
    var shown by remember { mutableStateOf(label) }
    if (label != null) shown = label
    val on = label != null
    val alpha by animateFloatAsState(if (on) 1f else 0f, tween(if (on) 180 else 150, easing = Motion.Standard), label = "cue")
    val lift by animateFloatAsState(if (on) 0f else 1f, spring(dampingRatio = 0.6f, stiffness = 520f), label = "cueLift")
    val fill = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        fill.snapTo(0f)
        if (shown?.hold == true) fill.animateTo(1f, tween(330, easing = LinearEasing))
    }

    AnimatedContent(
        targetState = shown,
        contentKey = { it?.text },
        transitionSpec = {
            fadeIn(tween(Motion.EFFECTS_FAST)) togetherWith fadeOut(tween(90)) using
                SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.8f, stiffness = 900f) }
        },
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha
                translationY = lift * 8.dp.toPx()
            }
            .height(36.dp)
            .clip(CircleShape)
            .background(c.glass)
            .border(1.dp, c.line, CircleShape)
            .drawWithContent {
                drawContent()
                if (shown?.hold == true) drawRect(c.accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width * fill.value, 2.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
        label = "cueText",
    ) { l ->
        if (l == null) return@AnimatedContent
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(l.icon), null, Modifier.size(20.dp), tint = c.accent)
            Spacer(Modifier.width(8.dp))
            Text(l.text, style = OtoType.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = c.text, maxLines = 1)
        }
    }
}
