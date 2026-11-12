package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.dj.DjMood

/** Selector desplegable compacto para cambiar el Mood del DJ en vivo o antes de iniciarlo. */
@Composable
internal fun DjMoodSelector(
    mood: DjMood,
    accent: Color,
    onSelect: (DjMood) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (hovered || open) accent.copy(alpha = 0.20f) else accent.copy(alpha = 0.12f),
        tween(150),
        label = "moodBg",
    )
    Box(modifier) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, accent.copy(alpha = 0.28f), CircleShape)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { open = true }
                .semantics { contentDescription = "Cambiar mood del DJ: ${mood.label}" }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(MobileIcons.graphicEq, null, Modifier.size(14.dp), tint = accent)
            Text("Mood: ${mood.label}", style = OtoType.rowSub, color = Color.White.copy(alpha = 0.92f), maxLines = 1)
        }
        LibraryDropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(18.dp)) {
            DjMood.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            style = OtoType.rowSub,
                            color = if (option == mood) accent else Color.White.copy(alpha = 0.85f),
                        )
                    },
                    trailingIcon = if (option == mood) {
                        { Icon(MobileIcons.check, null, Modifier.size(16.dp), tint = accent) }
                    } else null,
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Boton compacto para iniciar o alternar el modo DJ rapido (ADHD). */
@Composable
internal fun DjAdhdTogglePill(
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "DJ rápido (ADHD)",
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            active && hovered -> accent.copy(alpha = 0.30f)
            active -> accent.copy(alpha = 0.22f)
            hovered -> Color.White.copy(alpha = 0.12f)
            else -> Color.White.copy(alpha = 0.06f)
        },
        tween(150),
        label = "adhdBg",
    )
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (active) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f), CircleShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            MobileIcons.timer,
            null,
            Modifier.size(14.dp),
            tint = if (active) accent else Color.White.copy(alpha = 0.7f),
        )
        Text(
            label,
            style = OtoType.rowSub,
            color = if (active) Color.White else Color.White.copy(alpha = 0.78f),
            maxLines = 1,
        )
    }
}
