package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** A short, restrained motion complements the native menu's own entrance and exit. */
@Composable
internal fun LibraryDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    shape: Shape = RoundedCornerShape(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val progress by animateFloatAsState(
        if (expanded) 1f else 0f,
        tween(if (expanded) 200 else 120, easing = FastOutSlowInEasing),
        label = "libraryMenu"
    )
    DropdownMenu(expanded, onDismissRequest, modifier = modifier.graphicsLayer {
        alpha = progress
        scaleX = .96f + .04f * progress
        scaleY = .96f + .04f * progress
        translationY = (1f - progress) * -4.dp.toPx()
    }, offset = offset, shape = shape, content = content)
}
