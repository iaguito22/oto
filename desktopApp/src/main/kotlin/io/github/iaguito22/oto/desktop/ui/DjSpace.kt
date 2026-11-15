package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
import io.github.iaguito22.oto.dj.DjGenre
import io.github.iaguito22.oto.dj.DjMode

/**
 * Cabina del DJ en escritorio: tarjeta principal con vinilo, estado de la tanda en vivo,
 * modos de mezcla y filtros por género.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DjSpace(player: DesktopPlayerConnection, palette: ArtworkPalette, onBack: () -> Unit, onFail: (String) -> Unit) {
    val accent = palette.primary
    val active by player.djActive.collectAsState()
    val mode by player.djMode.collectAsState()
    val genre by player.djGenre.collectAsState()
    val tanda by player.djTanda.collectAsState()
    val fullOnce by player.djFullOnce.collectAsState()
    val shownTanda = djMoodPillLabel(tanda).takeIf { it != "Otro ambiente" }
    val genres by produceState(emptyList<DjGenre>()) { player.djGenres { value = it } }
    val start = { m: DjMode -> player.playDj(m) { onFail(if (m == DjMode.Adhd) "No se pudo iniciar el DJ rápido" else "No se pudo iniciar el DJ") } }
    val modeLabel = if (mode == DjMode.Adhd) "DJ rápido" else "Normal"
    val heroBorder by animateColorAsState(
        if (active) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.14f),
        tween(Motion.EFFECTS),
        label = "djHeroBorder",
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HoverAction(MobileIcons.arrowBack, "Volver", onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    DjStatusBadge(
                        active = active,
                        label = if (active) "En directo" else "$modeLabel · ${genre ?: "Todo"}",
                        accent = accent,
                    )
                }

                val heroShape = RoundedCornerShape(Radius.xl)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(heroShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    lerp(Color(0xFF141820), accent, if (active) 0.34f else 0.22f),
                                    Color.White.copy(alpha = 0.05f),
                                ),
                            ),
                        )
                        .border(1.dp, heroBorder, heroShape)
                        .padding(22.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        DjVinylDesktop(size = 112.dp, accent = accent, spinning = active)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (active) "SESIÓN EN VIVO" else "CABINA PERSONAL",
                                style = OtoType.label.copy(fontSize = 11.sp, letterSpacing = 1.sp),
                                color = if (active) accent else Color.White.copy(alpha = 0.58f),
                            )
                            Text("DJ", style = OtoType.display, color = Color.White)
                            Text(
                                text = if (active) {
                                    shownTanda?.let { "Pinchando · $it" } ?: "Pinchando una tanda pensada para ti"
                                } else {
                                    "Lo tuyo, por tandas, con algún descubrimiento entre medias"
                                },
                                style = OtoType.rowSub,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                DjButton(
                                    label = if (active) "Empezar de nuevo" else "Empezar",
                                    icon = MobileIcons.play,
                                    accent = accent,
                                    filled = true,
                                ) { start(mode) }
                                if (genre != null) {
                                    Text(
                                        text = genre!!,
                                        style = OtoType.label.copy(fontSize = 11.5.sp),
                                        color = accent,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(accent.copy(alpha = 0.14f))
                                            .border(1.dp, accent.copy(alpha = 0.35f), CircleShape)
                                            .padding(horizontal = 12.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (active) {
            item {
                val liveShape = RoundedCornerShape(Radius.lg)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ahora en cabina", style = OtoType.section, color = Color.White)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(liveShape)
                            .background(accent.copy(alpha = 0.12f))
                            .border(1.dp, accent.copy(alpha = 0.36f), liveShape)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(MobileIcons.graphicEq, null, Modifier.size(20.dp), tint = accent)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "TANDA ACTUAL",
                                style = OtoType.label.copy(fontSize = 10.5.sp, letterSpacing = 0.8.sp),
                                color = accent,
                            )
                            Text(
                                text = shownTanda ?: "Ambiente actual",
                                style = OtoType.rowTitle,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (mode == DjMode.Adhd) {
                            DjButton(
                                label = if (fullOnce) "Suena entera" else "Oír esta entera",
                                icon = MobileIcons.timer,
                                accent = accent,
                                filled = fullOnce,
                            ) {
                                if (!fullOnce) player.playDjFullOnce()
                            }
                        }
                        DjButton("Otro ambiente", MobileIcons.skipNext, accent, filled = false) { player.skipDjTanda() }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cómo pinchar", style = OtoType.section, color = Color.White, modifier = Modifier.padding(bottom = 2.dp))
                DjModeRow(
                    icon = MobileIcons.play,
                    title = "Normal",
                    badge = "Temas enteros",
                    body = "Canciones completas, enlazadas por tandas de artistas que se parecen.",
                    selected = mode == DjMode.Normal,
                    accent = accent,
                ) {
                    if (active) player.setDjMode(DjMode.Normal) else start(DjMode.Normal)
                }
                DjModeRow(
                    icon = MobileIcons.timer,
                    title = "DJ rápido",
                    badge = "~1:30 · Al pulso",
                    body = "Entra justo antes de una parte cantada y mezcla al acabar un estribillo.",
                    selected = mode == DjMode.Adhd,
                    accent = accent,
                ) {
                    if (active) player.setDjMode(DjMode.Adhd) else start(DjMode.Adhd)
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Géneros", style = OtoType.section, color = Color.White, modifier = Modifier.weight(1f))
                    Text(
                        text = genre ?: "Todos",
                        style = OtoType.label.copy(fontSize = 11.5.sp),
                        color = if (genre != null) accent else Color.White.copy(alpha = 0.5f),
                    )
                }
                if (genres.isEmpty()) {
                    Text(
                        text = "Buscando tus géneros entre lo que más escuchas… La primera vez tarda unos minutos.",
                        style = OtoType.rowSub,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DjChip("Todo", count = null, selected = genre == null, accent = accent) { player.setDjGenre(null) }
                        genres.forEach { g ->
                            DjChip(g.name, count = g.songs.size, selected = g.name == genre, accent = accent) {
                                player.setDjGenre(g.name)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DjVinylDesktop(size: Dp, accent: Color, spinning: Boolean) {
    val angle = if (spinning) {
        val transition = rememberInfiniteTransition(label = "djVinyl")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
            label = "djVinylAngle",
        ).value
    } else {
        0f
    }

    Box(
        Modifier
            .size(size)
            .graphicsLayer { rotationZ = angle }
            .clip(CircleShape)
            .border(1.5.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            drawCircle(Color(0xFF101218), radius = r)
            // Surcos del vinilo.
            for (frac in listOf(0.86f, 0.72f, 0.58f)) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.08f),
                    radius = r * frac,
                    style = Stroke(width = 1.2f),
                )
            }
            // Reflejo diagonal sutil.
            drawLine(
                color = Color.White.copy(alpha = 0.08f),
                start = Offset(r * 0.35f, r * 0.25f),
                end = Offset(r * 1.65f, r * 1.75f),
                strokeWidth = r * 0.18f,
            )
            // Galleta central con el color de acento.
            drawCircle(accent.copy(alpha = 0.9f), radius = r * 0.35f)
            drawCircle(Color(0xFF101218), radius = r * 0.11f)
        }
    }
}

@Composable
private fun DjStatusBadge(active: Boolean, label: String, accent: Color) {
    val bg = if (active) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f)
    val border = if (active) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.14f)
    val fg = if (active) accent else Color.White.copy(alpha = 0.72f)
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (active) {
            Icon(MobileIcons.graphicEq, null, Modifier.size(14.dp), tint = fg)
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)))
        }
        Text(
            label,
            style = OtoType.label.copy(fontSize = 11.5.sp),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DjButton(label: String, icon: ImageVector, accent: Color, filled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            filled && hovered -> lerp(accent, Color.White, 0.14f)
            filled -> accent
            hovered -> Color.White.copy(alpha = 0.15f)
            else -> Color.White.copy(alpha = 0.08f)
        },
        tween(150),
        label = "djButtonBg",
    )
    val border = if (filled) accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.16f)
    val fg = if (filled) djContrastOn(accent) else Color.White
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .hoverable(src)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(label, style = OtoType.rowTitle.copy(fontSize = 13.5.sp), color = fg, maxLines = 1)
    }
}

@Composable
private fun DjModeRow(
    icon: ImageVector,
    title: String,
    badge: String,
    body: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> accent.copy(alpha = 0.14f)
            hovered -> Color.White.copy(alpha = 0.08f)
            else -> Color.White.copy(alpha = 0.04f)
        },
        tween(150),
        label = "djModeBg",
    )
    val border by animateColorAsState(
        when {
            selected -> accent.copy(alpha = 0.45f)
            hovered -> Color.White.copy(alpha = 0.18f)
            else -> Color.White.copy(alpha = 0.10f)
        },
        tween(150),
        label = "djModeBorder",
    )
    val shape = RoundedCornerShape(Radius.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .hoverable(src)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (selected) accent else Color.White.copy(alpha = 0.78f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = OtoType.rowTitle, color = if (selected) accent else Color.White)
                Text(
                    text = badge,
                    style = OtoType.label.copy(fontSize = 10.5.sp),
                    color = if (selected) accent else Color.White.copy(alpha = 0.65f),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text(body, style = OtoType.rowSub, color = Color.White.copy(alpha = 0.62f))
        }
        if (selected) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MobileIcons.check, "Elegido", Modifier.size(15.dp), tint = djContrastOn(accent))
            }
        }
    }
}

private fun djContrastOn(color: Color): Color =
    if (color.luminance() > 0.55f) Color(0xFF101218) else Color.White

@Composable
private fun DjChip(label: String, count: Int?, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> accent.copy(alpha = 0.22f)
            hovered -> Color.White.copy(alpha = 0.12f)
            else -> Color.White.copy(alpha = 0.06f)
        },
        tween(150),
        label = "djChipBg",
    )
    val border by animateColorAsState(
        if (selected) accent.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.12f),
        tween(150),
        label = "djChipBorder",
    )
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .hoverable(src)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = OtoType.rowSub.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) accent else Color.White.copy(alpha = 0.88f),
            maxLines = 1,
        )
        if (count != null && count > 0) {
            Text(
                text = count.toString(),
                style = OtoType.label.copy(fontSize = 10.5.sp),
                color = if (selected) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.45f),
            )
        }
    }
}
