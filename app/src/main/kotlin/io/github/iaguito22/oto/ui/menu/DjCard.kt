package io.github.iaguito22.oto.ui.menu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Radius
import io.github.iaguito22.oto.ui.theme.Space

/** El DJ en Tu música, junto a Me gusta: un disco que gira mientras pincha. */
@Composable
fun DjCard(spinning: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Oto.colors
    Column(
        modifier
            .width(140.dp + Space.md)
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(Space.xs + 2.dp),
    ) {
        DjArt(140.dp, spinning)
        Spacer(Modifier.height(Space.sm))
        Text("DJ", style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = c.text, maxLines = 1)
        Text(if (spinning) "Pinchando" else "Mezcla para ti", style = OtoType.rowSub, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Un vinilo sobre el color de la app. Los surcos y el brillo no se mueven (en un disco de verdad tampoco); lo que
 * delata el giro es la marca de la galleta. Al parar se queda donde estaba, sin volver a cero.
 */
@Composable
fun DjArt(size: Dp, spinning: Boolean) {
    val c = Oto.colors
    var angle by remember { mutableFloatStateOf(-35f) }
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                angle = (angle + (now - last) / 1e9f * 360f / SECONDS_PER_TURN) % 360f
                last = now
            }
        }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(if (size < 64.dp) Radius.sm else Radius.md))
            .background(Brush.radialGradient(listOf(lerp(c.accent, c.base, 0.25f), lerp(c.accent, c.base, 0.8f)))),
    ) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension * 0.39f
            val center = Offset(this.size.width / 2, this.size.height / 2)
            drawCircle(Color.Black.copy(alpha = 0.35f), r * 1.03f, center + Offset(0f, r * 0.04f))
            drawCircle(Color(0xFF121212), r, center)
            // Surcos: anillos tenues, más juntos hacia fuera.
            var g = 0.45f
            while (g < 0.97f) {
                drawCircle(Color.White.copy(alpha = 0.05f), r * g, center, style = Stroke(width = r * 0.008f))
                g += 0.045f * (1.2f - g)
            }
            // El brillo de la luz sobre el vinilo: dos cuñas opuestas.
            for (start in listOf(-70f, 110f)) {
                drawArc(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), center, r),
                    start, 34f, useCenter = true, topLeft = center - Offset(r, r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                )
            }
            drawCircle(c.accent, r * 0.36f, center)
            rotate(angle, center) {
                drawCircle(c.onAccent.copy(alpha = 0.85f), r * 0.045f, center + Offset(r * 0.22f, 0f))
                drawArc(
                    c.onAccent.copy(alpha = 0.35f), -40f, 80f, useCenter = false,
                    topLeft = center - Offset(r * 0.28f, r * 0.28f), size = androidx.compose.ui.geometry.Size(r * 0.56f, r * 0.56f),
                    style = Stroke(width = r * 0.02f),
                )
            }
            drawCircle(lerp(c.accent, c.base, 0.8f), r * 0.035f, center)
        }
    }
}

// Más despacio que un 33 de verdad: en pequeño, a esa velocidad marea.
private const val SECONDS_PER_TURN = 4f
