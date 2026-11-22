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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import io.github.iaguito22.oto.desktop.LocalHoverEpoch
import io.github.iaguito22.oto.desktop.subtleHoverScale
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
import io.github.iaguito22.oto.dj.DjGenre
import io.github.iaguito22.oto.dj.DjMode

/**
 * Cabina del DJ: una portada grande con el vinilo (empieza, pausa o cambia de ambiente desde ahí),
 * el modo como dos tarjetas grandes y los géneros como baldosas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DjSpace(player: DesktopPlayerConnection, palette: ArtworkPalette, onBack: () -> Unit, onFail: (String) -> Unit, embedded: Boolean = false) {
    val accent = palette.primary
    val active by player.djActive.collectAsState()
    val mode by player.djMode.collectAsState()
    val genre by player.djGenre.collectAsState()
    val tanda by player.djTanda.collectAsState()
    val fullOnce by player.djFullOnce.collectAsState()
    val now by player.state.collectAsState()
    val shownTanda = djMoodPillLabel(tanda).takeIf { it != "Otro ambiente" }
    val genres by produceState(emptyList<DjGenre>()) { player.djGenres { value = it } }
    val start = { m: DjMode -> player.playDj(m) { onFail(if (m == DjMode.Adhd) "No se pudo iniciar el DJ rápido" else "No se pudo iniciar el DJ") } }
    val playing = active && now.playing

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp + LocalFloatingDockInset.current),
        verticalArrangement = Arrangement.spacedBy(if (embedded) 18.dp else 26.dp),
    ) {
        // Portada: degradado del acento, vinilo grande medio fuera a la derecha y la acción principal.
        item {
            val shape = RoundedCornerShape(Radius.xl)
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .then(if (embedded) Modifier.heightIn(min = 198.dp) else Modifier.height(260.dp))
                    .clip(shape)
                    .background(Brush.linearGradient(listOf(accent.copy(alpha = if (active) .42f else .30f), accent.copy(alpha = .06f), Color(0x33000000))))
                    .then(if (embedded) Modifier else Modifier.border(1.dp,
                        Brush.linearGradient(listOf(Color.White.copy(.22f), accent.copy(.10f), accent.copy(.30f))), shape)),
            ) {
                val narrow = embedded && maxWidth < 540.dp
                Box(Modifier.matchParentSize().drawBehind {
                    drawRect(Brush.radialGradient(
                        listOf(accent.copy(alpha = .24f), Color.Transparent),
                        center = Offset(size.width, size.height * .5f),
                        radius = size.height.coerceAtLeast(1f),
                    ))
                })
                DjVinylDesktop(
                    size = if (narrow) 132.dp else 230.dp, accent = accent, spinning = playing,
                    modifier = Modifier.align(Alignment.CenterEnd).graphicsLayer { translationX = (if (narrow) 28.dp else 70.dp).toPx() },
                )
                Column(
                    Modifier.align(Alignment.CenterStart).padding(start = if (embedded) 20.dp else 30.dp,
                        end = if (narrow) 106.dp else 200.dp, top = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DjStatusBadge(active, if (active) (if (playing) "En directo" else "En pausa") else if (mode == DjMode.Adhd) "DJ rápido" else "Mezcla para ti", accent, outlined = !embedded)
                    Text("DJ", style = OtoType.display.copy(fontSize = if (embedded) 46.sp else 64.sp, lineHeight = if (embedded) 48.sp else 66.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp), color = Color.White)
                    Text(
                        text = when {
                            active -> shownTanda?.let { "Pinchando · $it" } ?: "Pinchando una tanda para ti"
                            genre != null -> "Tu ${genre!!.lowercase()}, mezclado como en una cabina"
                            else -> "Tu música, por tandas, con algo nuevo de vez en cuando"
                        },
                        style = OtoType.body,
                        color = Color.White.copy(alpha = .78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DjBigButton(
                            label = when { !active -> "Empezar"; playing -> "Pausar"; else -> "Seguir" },
                            icon = if (playing) MobileIcons.pause else MobileIcons.play,
                            accent = accent,
                        ) { if (active) player.toggle() else start(mode) }
                        if (active) {
                            DjButton("Volver a empezar DJ", MobileIcons.play, accent, filled = false, outlined = !embedded) { start(mode) }
                            DjButton("Otro ambiente", MobileIcons.skipNext, accent, filled = false, outlined = !embedded) { player.skipDjTanda() }
                            if (mode == DjMode.Adhd) DjButton(if (fullOnce) "Suena entera" else "Oír esta entera", MobileIcons.timer, accent, filled = fullOnce, outlined = !embedded) {
                                if (!fullOnce) player.playDjFullOnce()
                            }
                        }
                    }
                }
            }
        }

        // Modo: dos tarjetas grandes una al lado de otra.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Cómo mezcla", style = OtoType.title, color = Color.White)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DjModeTile(
                        icon = MobileIcons.graphicEq, title = "Mezcla para ti", badge = "Temas enteros",
                        body = "Canciones completas, por tandas de artistas que se parecen.",
                        selected = mode == DjMode.Normal, accent = accent, modifier = Modifier.weight(1f), outlined = !embedded,
                    ) { if (active || embedded) player.setDjMode(DjMode.Normal) else start(DjMode.Normal) }
                    DjModeTile(
                        icon = MobileIcons.timer, title = "DJ rápido", badge = "~1:30 · al pulso",
                        body = "Entra antes de una parte cantada y mezcla al acabar el estribillo.",
                        selected = mode == DjMode.Adhd, accent = accent, modifier = Modifier.weight(1f), outlined = !embedded,
                    ) { if (active || embedded) player.setDjMode(DjMode.Adhd) else start(DjMode.Adhd) }
                }
            }
        }

        // Géneros: baldosas con el número de canciones.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text("Géneros", style = OtoType.title, color = Color.White, modifier = Modifier.weight(1f))
                    Text(genre ?: "Todo", style = OtoType.rowSub, color = if (genre != null) accent else Color.White.copy(alpha = .5f))
                }
                if (genres.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val pulse = rememberInfiniteTransition(label = "buscandoGeneros")
                        val a by pulse.animateFloat(.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "puntoGeneros")
                        Box(Modifier.size(8.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(accent))
                        Text("Buscando tus géneros entre lo que más escuchas… La primera vez tarda unos minutos.",
                            style = OtoType.rowSub, color = Color.White.copy(alpha = .55f))
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DjGenreTile("Todo", null, genre == null, accent, outlined = !embedded) { player.setDjGenre(null) }
                        genres.forEach { g -> DjGenreTile(g.name, g.songs.size, g.name == genre, accent, outlined = !embedded) { player.setDjGenre(g.name) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun DjBigButton(label: String, icon: ImageVector, accent: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) lerp(accent, Color.White, .18f) else accent, tween(150), label = "djGrande")
    val fg = djContrastOn(accent)
    Row(
        Modifier.clip(CircleShape).background(bg).hoverable(src).clickable(onClick = onClick)
            .padding(start = 18.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = fg)
        Text(label, style = OtoType.rowTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = fg, maxLines = 1)
    }
}

@Composable
private fun DjModeTile(icon: ImageVector, title: String, badge: String, body: String, selected: Boolean, accent: Color,
    modifier: Modifier, outlined: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Radius.lg + 4.dp)
    val glow by animateColorAsState(if (selected) accent.copy(alpha = .22f) else if (hovered) Color.White.copy(alpha = .07f) else Color.White.copy(alpha = .035f), tween(160), label = "djTile")
    val edge by animateColorAsState(if (selected) accent.copy(alpha = .55f) else Color.White.copy(alpha = if (hovered) .16f else .08f), tween(160), label = "djTileBorde")
    Column(
        modifier.subtleHoverScale(src, 1.025f).clip(shape)
            .background(Brush.verticalGradient(listOf(glow, glow.copy(alpha = glow.alpha * .35f))))
            .then(if (outlined) Modifier.border(1.dp, edge, shape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand).clickable(src, null, onClick = onClick).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) accent else Color.White.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = if (selected) djContrastOn(accent) else Color.White.copy(alpha = .8f))
            }
            Spacer(Modifier.weight(1f))
            if (selected) Icon(MobileIcons.check, "Elegido", Modifier.size(20.dp), tint = accent)
        }
        Text(title, style = OtoType.section.copy(fontWeight = FontWeight.Bold), color = Color.White)
        Text(badge, style = OtoType.label.copy(fontSize = 11.sp), color = if (selected) accent else Color.White.copy(alpha = .6f))
        Text(body, style = OtoType.rowSub, color = Color.White.copy(alpha = .62f), maxLines = 3)
    }
}

@Composable
private fun DjGenreTile(label: String, count: Int?, selected: Boolean, accent: Color, outlined: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    val bg by animateColorAsState(if (selected) accent.copy(alpha = .26f) else if (hovered) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .05f), tween(150), label = "djGenero")
    Column(
        Modifier.width(150.dp).subtleHoverScale(src).clip(shape).background(bg)
            .then(if (outlined) Modifier.border(1.dp, if (selected) accent.copy(alpha = .55f) else Color.White.copy(alpha = .08f), shape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand).clickable(src, null, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = OtoType.rowTitle.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold),
            color = if (selected) accent else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (count != null && count > 0) songCount(count) else "Todos tus géneros", style = OtoType.rowSub.copy(fontSize = 12.sp),
            color = Color.White.copy(alpha = .5f), maxLines = 1)
    }
}

@Composable
private fun DjVinylDesktop(size: Dp, accent: Color, spinning: Boolean, modifier: Modifier = Modifier) {
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
        modifier
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
private fun DjStatusBadge(active: Boolean, label: String, accent: Color, outlined: Boolean) {
    val bg = if (active) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f)
    val border = if (active) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.14f)
    val fg = if (active) accent else Color.White.copy(alpha = 0.72f)
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .then(if (outlined) Modifier.border(1.dp, border, CircleShape) else Modifier)
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
private fun DjButton(label: String, icon: ImageVector, accent: Color, filled: Boolean, outlined: Boolean = true, onClick: () -> Unit) {
    val src = remember(LocalHoverEpoch.current) { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            outlined && filled && hovered -> lerp(accent, Color.White, 0.14f)
            filled -> accent
            outlined && hovered -> Color.White.copy(alpha = 0.15f)
            else -> Color.White.copy(alpha = 0.08f)
        },
        tween(150),
        label = "djButtonBg",
    )
    val border = if (filled) accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.16f)
    val fg = if (filled) djContrastOn(accent) else Color.White
    Row(
        Modifier
            .subtleHoverScale(src)
            .clip(CircleShape)
            .background(bg)
            .then(if (outlined) Modifier.border(1.dp, border, CircleShape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = src, indication = null, onClick = onClick)
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
            .pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = null, indication = null, onClick = onClick)
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
