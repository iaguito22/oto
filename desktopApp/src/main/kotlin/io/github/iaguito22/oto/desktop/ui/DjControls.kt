package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Limpia el texto de tanda para la píldora de ambiente: muestra solo el género si se conoce,
 * o «Otro ambiente» si no hay género definido (sin mostrar explicaciones tipo «Como <artista>»).
 */
internal fun djMoodPillLabel(tanda: String?): String {
    val raw = tanda?.trim()?.takeIf { it.isNotBlank() } ?: return "Otro ambiente"
    if (raw.startsWith("como ", ignoreCase = true)) return "Otro ambiente"
    val idx = raw.indexOf(" · como ", ignoreCase = true)
    val genre = (if (idx >= 0) raw.substring(0, idx) else raw).trim()
    return genre.ifBlank { "Otro ambiente" }
}

/**
 * El ambiente que pincha el DJ (solo el género si se conoce, o «Otro ambiente»): pulsarlo lo salta y pasa ya a
 * otra tanda, lejos de esta.
 */
@Composable
internal fun DjTandaPill(
    tanda: String?,
    accent: Color,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (hovered) accent.copy(alpha = 0.20f) else accent.copy(alpha = 0.12f),
        tween(150),
        label = "tandaBg",
    )
    val pillLabel = djMoodPillLabel(tanda)
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, accent.copy(alpha = 0.28f), CircleShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onSkip)
            .semantics {
                contentDescription = if (pillLabel == "Otro ambiente") "Otro ambiente" else "Otro ambiente (ahora $pillLabel)"
            }
            .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            pillLabel, style = OtoType.rowSub, color = Color.White.copy(alpha = 0.92f), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Icon(MobileIcons.skipNext, null, Modifier.size(14.dp), tint = accent)
    }
}

/**
 * Botón compacto para iniciar o alternar el modo DJ rápido (ADHD).
 * Con el modo rápido activo, clic derecho o pulsación larga abre opciones para oír la canción actual entera
 * sin añadir más botones en la pantalla del reproductor.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun DjAdhdTogglePill(
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "DJ rápido",
    fullOnce: Boolean = false,
    onPlayFullOnce: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val shownLabel = if (active && fullOnce) "Entera esta" else label
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
    Box(modifier) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, if (active) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f), CircleShape)
                .hoverable(interaction)
                .onPointerEvent(PointerEventType.Press) { ev ->
                    if (active && onPlayFullOnce != null && ev.buttons.isSecondaryPressed) {
                        menuOpen = true
                        ev.changes.forEach { it.consume() }
                    }
                }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = if (active && onPlayFullOnce != null) ({ menuOpen = true }) else null,
                )
                .semantics {
                    contentDescription = if (active && onPlayFullOnce != null && !fullOnce) {
                        "$shownLabel · clic derecho para oír esta canción entera"
                    } else {
                        shownLabel
                    }
                }
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
                shownLabel,
                style = OtoType.rowSub,
                color = if (active) Color.White else Color.White.copy(alpha = 0.78f),
                maxLines = 1,
            )
        }
        if (active && onPlayFullOnce != null) {
            LibraryDropdownMenu(menuOpen, { menuOpen = false }, shape = RoundedCornerShape(16.dp), modifier = Modifier.width(220.dp)) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (fullOnce) "Esta canción suena entera" else "Oír esta canción entera",
                            style = OtoType.rowSub,
                            color = if (fullOnce) accent else Color.White,
                        )
                    },
                    enabled = !fullOnce,
                    onClick = {
                        menuOpen = false
                        onPlayFullOnce()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Pasar a DJ normal", style = OtoType.rowSub, color = Color.White.copy(alpha = 0.8f)) },
                    onClick = {
                        menuOpen = false
                        onClick()
                    },
                )
            }
        }
    }
}
