package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.playback.QueueEntry

/**
 * La cola como panel inferior que se superpone al escenario (no una subvista del menú).
 *
 * Se abre desde el icono de cola del menú, se cierra con Escape o pulsando fuera, y deja ver el
 * escenario detrás con un velo. La animación es la misma escala del móvil: entra con
 * [Motion.EmphasizedDecelerate] y sale con [Motion.EmphasizedAccelerate].
 */
@Composable
fun DesktopQueuePanel(
    open: Boolean,
    queue: List<QueueEntry>,
    currentMediaId: String?,
    shuffle: Boolean,
    loop: Boolean,
    palette: ArtworkPalette,
    onToggleShuffle: () -> Unit,
    onToggleLoop: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onClearQueue: () -> Unit,
    onSkipTo: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (open) Motion.SPATIAL_FAST else Motion.EFFECTS,
            easing = if (open) Motion.EmphasizedDecelerate else Motion.EmphasizedAccelerate,
        ),
        label = "queuePanel",
    )
    if (progress <= 0.001f) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelW = (maxWidth - 48.dp).coerceAtMost(980.dp).coerceAtLeast(320.dp)
        val panelH = (maxHeight * 0.62f).coerceAtLeast(300.dp).coerceAtMost(maxHeight - 96.dp)

        // Velo: deja ver el escenario, pero apaga el fondo para que la cola se lea.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.46f * progress))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
                .width(panelW)
                .height(panelH)
                .graphicsLayer {
                    alpha = progress
                    translationY = (1f - progress) * 420f
                }
                .shadow(28.dp, RoundedCornerShape(Radius.xl), ambientColor = Color.Black.copy(alpha = 0.7f))
                .clip(RoundedCornerShape(Radius.xl))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFA1E1E1E), Color(0xFF0D0D0D)),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(Radius.xl))
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 18.dp),
        ) {
            // Asa de la hoja, como la del móvil.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.22f)),
            )
            QueuePane(
                queue = queue,
                currentMediaId = currentMediaId,
                shuffle = shuffle,
                loop = loop,
                palette = palette,
                onToggleShuffle = onToggleShuffle,
                onToggleLoop = onToggleLoop,
                onMove = onMove,
                onRemove = onRemove,
                onClearQueue = onClearQueue,
                onSkipTo = onSkipTo,
                onClose = onClose,
            )
        }
    }
}
