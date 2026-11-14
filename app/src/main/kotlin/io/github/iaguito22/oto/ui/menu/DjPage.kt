package io.github.iaguito22.oto.ui.menu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.dj.DjMode
import io.github.iaguito22.oto.playback.PlayerConnection
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Radius
import io.github.iaguito22.oto.ui.theme.Space

/**
 * La entrada al DJ: cabecera con el vinilo y el estado de la sesión, tarjeta de tanda actual si está pinchando,
 * selector de modo y parrilla de tus géneros más escuchados.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DjPage(
    connection: PlayerConnection,
    playing: Boolean,
    onBack: () -> Unit,
    onStart: (DjMode) -> Unit,
) {
    val c = Oto.colors
    val haptics = LocalHapticFeedback.current
    val mode by connection.djMode.collectAsStateWithLifecycle()
    val genre by connection.djGenre.collectAsStateWithLifecycle()
    val tanda by connection.djTanda.collectAsStateWithLifecycle()
    val fullOnce by connection.djFullOnce.collectAsStateWithLifecycle()
    val moodGenre = djGenreLabel(tanda)
    val genres = rememberDjGenres()
    val modeLabel = if (mode == DjMode.Adhd) "DJ rápido" else "Normal"

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.md, end = Space.md, top = Space.xs, bottom = Space.xl),
    ) {
        item(key = "topBar") {
            Row(
                Modifier.fillMaxWidth().padding(bottom = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RoundIcon(R.drawable.ic_arrow_back, description = "Volver", onClick = onBack)
                LiveBadge(playing = playing, label = if (playing) "En directo" else "$modeLabel · ${genre ?: "Todo"}")
            }
        }

        item(key = "hero") {
            val heroBorder by animateColorAsState(
                if (playing) c.accent.copy(alpha = 0.34f) else c.line,
                tween(Motion.EFFECTS),
                label = "djHeroBorder",
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.xl))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                c.accent.copy(alpha = if (playing) 0.20f else 0.11f),
                                c.field,
                            ),
                        ),
                    )
                    .border(1.dp, heroBorder, RoundedCornerShape(Radius.xl))
                    .padding(Space.lg),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(Radius.md + 2.dp))
                            .border(1.dp, c.accent.copy(alpha = if (playing) 0.35f else 0.14f), RoundedCornerShape(Radius.md + 2.dp)),
                    ) {
                        DjArt(112.dp, playing)
                    }
                    Spacer(Modifier.width(Space.lg))
                    Column(Modifier.weight(1f)) {
                        Text("DJ", style = OtoType.display.copy(fontSize = 30.sp, lineHeight = 34.sp), color = c.text)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (playing) {
                                buildString {
                                    append("Pinchando · ")
                                    append(moodGenre ?: modeLabel)
                                }
                            } else {
                                "Lo tuyo por tandas, con algo nuevo entre medias"
                            },
                            style = OtoType.rowSub,
                            color = c.textMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(Space.md))
                        Pill(
                            text = if (playing) "Empezar de nuevo" else "Empezar",
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onStart(mode)
                            },
                            icon = R.drawable.ic_play_arrow,
                        )
                    }
                }
            }
        }

        if (playing) {
            item(key = "now") {
                Spacer(Modifier.height(Space.md))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.lg))
                        .background(c.field)
                        .border(1.dp, c.line, RoundedCornerShape(Radius.lg))
                        .padding(Space.md),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(c.accent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(R.drawable.ic_graphic_eq), null, Modifier.size(18.dp), tint = c.accent)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("TANDA ACTUAL", style = OtoType.label.copy(fontSize = 10.5.sp), color = c.textMuted)
                            Text(
                                moodGenre ?: "Mezcla variada",
                                style = OtoType.rowTitle,
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Pill(
                            text = "Saltar mood",
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                connection.skipDjTanda()
                            },
                            filled = false,
                            icon = R.drawable.ic_skip_next,
                        )
                    }
                    if (mode == DjMode.Adhd) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.md))
                                .background(if (fullOnce) c.accent.copy(alpha = 0.14f) else c.base.copy(alpha = 0.35f))
                                .border(1.dp, if (fullOnce) c.accent.copy(alpha = 0.38f) else c.line, RoundedCornerShape(Radius.md))
                                .clickable(remember { MutableInteractionSource() }, ripple(color = c.text)) {
                                    if (!fullOnce) {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        connection.playDjFullOnce()
                                    }
                                }
                                .padding(horizontal = Space.md, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        ) {
                            Icon(
                                painterResource(if (fullOnce) R.drawable.ic_check else R.drawable.ic_timer),
                                null,
                                Modifier.size(16.dp),
                                tint = if (fullOnce) c.accent else c.textMuted,
                            )
                            Text(
                                if (fullOnce) "Esta canción sonará entera" else "Oír esta canción entera (solo esta vez)",
                                style = OtoType.rowSub,
                                color = if (fullOnce) c.accent else c.textMuted,
                            )
                        }
                    }
                }
            }
        }

        item(key = "modesTitle") {
            SectionTitle("Cómo pinchar", Modifier.padding(start = 0.dp, end = 0.dp))
        }
        item(key = "mode:normal") {
            ModeCard(
                icon = R.drawable.ic_play_arrow,
                title = "Normal",
                badge = "Temas enteros",
                body = "Canciones completas por tandas de artistas que encajan entre sí.",
                selected = mode == DjMode.Normal,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                if (playing) connection.setDjMode(DjMode.Normal) else onStart(DjMode.Normal)
            }
            Spacer(Modifier.height(Space.sm))
        }
        item(key = "mode:adhd") {
            ModeCard(
                icon = R.drawable.ic_timer,
                title = "DJ rápido",
                badge = "~1:30 · Al pulso",
                body = "Entra antes de cantar y mezcla al acabar el estribillo para mantener el ritmo.",
                selected = mode == DjMode.Adhd,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                if (playing) connection.setDjMode(DjMode.Adhd) else onStart(DjMode.Adhd)
            }
        }

        item(key = "genresTitle") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.lg, bottom = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Géneros", style = OtoType.section, color = c.text)
                Text(
                    genre ?: "Todo",
                    style = OtoType.label.copy(fontSize = 11.sp),
                    color = c.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item(key = "genres") {
            if (genres.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.lg))
                        .background(c.field.copy(alpha = 0.6f))
                        .border(1.dp, c.line, RoundedCornerShape(Radius.lg))
                        .padding(Space.md),
                ) {
                    Text(
                        "Buscando tus géneros entre lo que más escuchas…",
                        style = OtoType.rowTitle,
                        color = c.text,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "La primera vez tarda unos minutos. Mientras tanto puedes empezar con «Todo».",
                        style = OtoType.rowSub,
                        color = c.textMuted,
                    )
                }
            } else {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    DjGenreChip(
                        label = "Todo",
                        count = null,
                        selected = genre == null,
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        connection.setDjGenre(null)
                    }
                    genres.forEach { g ->
                        DjGenreChip(
                            label = g.name,
                            count = g.songs.size.takeIf { it > 0 },
                            selected = g.name == genre,
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            connection.setDjGenre(g.name)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBadge(playing: Boolean, label: String) {
    val c = Oto.colors
    val bg by animateColorAsState(if (playing) c.accent.copy(alpha = 0.16f) else c.field, tween(Motion.EFFECTS), label = "liveBg")
    val border by animateColorAsState(if (playing) c.accent.copy(alpha = 0.36f) else c.line, tween(Motion.EFFECTS), label = "liveBorder")
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (playing) {
            NowBars(playing = true, color = c.accent, modifier = Modifier.size(12.dp))
        }
        Text(
            label,
            style = OtoType.label.copy(fontSize = 11.sp),
            color = if (playing) c.accent else c.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Tarjeta selectora para un modo del DJ: mantiene el comportamiento de fila interactiva con más jerarquía visual. */
@Composable
private fun ModeCard(icon: Int, title: String, badge: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val c = Oto.colors
    val bg by animateColorAsState(if (selected) c.accent.copy(alpha = 0.15f) else c.field.copy(alpha = 0.55f), tween(Motion.EFFECTS), label = "modeBg")
    val border by animateColorAsState(if (selected) c.accent.copy(alpha = 0.48f) else c.line, tween(Motion.EFFECTS), label = "modeBorder")
    val iconBg by animateColorAsState(if (selected) c.accent.copy(alpha = 0.22f) else c.base.copy(alpha = 0.35f), tween(Motion.EFFECTS), label = "modeIconBg")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(Radius.lg))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(horizontal = Space.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = if (selected) c.accent else c.textMuted)
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(title, style = OtoType.rowTitle, color = if (selected) c.accent else c.text)
                Text(
                    badge,
                    style = OtoType.label.copy(fontSize = 10.sp),
                    color = if (selected) c.accent else c.textMuted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) c.accent.copy(alpha = 0.16f) else c.base.copy(alpha = 0.35f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text(body, style = OtoType.rowSub, color = c.textMuted)
        }
        Spacer(Modifier.width(Space.sm))
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) c.accent else Color.Transparent)
                .border(1.5.dp, if (selected) c.accent else c.textFaint.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(painterResource(R.drawable.ic_check), "Elegido", Modifier.size(14.dp), tint = c.onAccent)
        }
    }
}

@Composable
private fun DjGenreChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val c = Oto.colors
    val bg by animateColorAsState(if (selected) c.accent.copy(alpha = 0.22f) else c.field.copy(alpha = 0.72f), tween(Motion.EFFECTS), label = "djGenreBg")
    val border by animateColorAsState(if (selected) c.accent.copy(alpha = 0.50f) else c.line, tween(Motion.EFFECTS), label = "djGenreBorder")
    val fg by animateColorAsState(if (selected) c.accent else c.text, tween(Motion.EFFECTS), label = "djGenreFg")
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.04f),
            color = fg,
            maxLines = 1,
        )
        if (count != null) {
            Text(
                "$count",
                style = OtoType.label.copy(fontSize = 10.5.sp),
                color = if (selected) c.accent.copy(alpha = 0.85f) else c.textFaint,
                maxLines = 1,
            )
        }
    }
}

/** Extrae solo el género de la tanda activa (oculta cualquier sufijo o texto tipo «Como <artista>»). */
internal fun djGenreLabel(tanda: String?): String? {
    val raw = tanda?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (raw.startsWith("Como ", ignoreCase = true)) return null
    val cleaned = raw.substringBefore(" · como ", raw).substringBefore("· como ").trim()
    return cleaned.takeIf { it.isNotEmpty() && !it.startsWith("Como ", ignoreCase = true) }
}
