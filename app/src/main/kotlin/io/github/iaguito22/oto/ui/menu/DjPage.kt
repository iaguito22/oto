package io.github.iaguito22.oto.ui.menu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
 * La entrada al DJ: cabecera con el vinilo sin cajas ni bordes recargados, selector de modo y géneros.
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
                Modifier.fillMaxWidth().padding(bottom = Space.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RoundIcon(R.drawable.ic_arrow_back, description = "Volver", onClick = onBack)
                LiveBadge(playing = playing, label = if (playing) "En directo" else "$modeLabel · ${genre ?: "Todo"}")
            }
        }

        item(key = "hero") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.sm, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DjArt(112.dp, playing)
                Spacer(Modifier.width(Space.lg))
                Column(Modifier.weight(1f)) {
                    Text("DJ", style = OtoType.title, color = c.text)
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

        if (playing && mode == DjMode.Adhd) {
            item(key = "fullOnce") {
                Spacer(Modifier.height(Space.md))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.lg))
                        .background(if (fullOnce) c.accent.copy(alpha = 0.16f) else c.field)
                        .clickable(remember { MutableInteractionSource() }, ripple(color = c.text)) {
                            if (!fullOnce) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                connection.playDjFullOnce()
                            }
                        }
                        .padding(horizontal = Space.md, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Icon(
                        painterResource(if (fullOnce) R.drawable.ic_check else R.drawable.ic_timer),
                        null,
                        Modifier.size(18.dp),
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

        item(key = "modesTitle") {
            SectionTitle("Cómo pinchar", Modifier.padding(start = 0.dp, end = 0.dp))
        }
        item(key = "mode:normal") {
            ModeCard(
                icon = R.drawable.ic_play_arrow,
                title = "Normal",
                badge = "Temas enteros",
                body = "Canciones enteras, por tandas de artistas que encajan entre sí.",
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
                badge = "Trozos de ~1:30",
                body = "Lo mejor de cada canción: entra justo antes de la voz y mezcla al acabar el estribillo.",
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
                        .background(c.field)
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
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
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

/** Tarjeta selectora sin bordes para un modo del DJ: superficie tonal limpia. */
@Composable
private fun ModeCard(icon: Int, title: String, badge: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val c = Oto.colors
    val bg by animateColorAsState(if (selected) c.accent.copy(alpha = 0.15f) else c.field, tween(Motion.EFFECTS), label = "modeBg")
    val iconBg by animateColorAsState(if (selected) c.accent.copy(alpha = 0.22f) else c.field, tween(Motion.EFFECTS), label = "modeIconBg")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(bg)
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
                        .background(if (selected) c.accent.copy(alpha = 0.16f) else c.field)
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
                .background(if (selected) c.accent else c.field),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(painterResource(R.drawable.ic_check), "Elegido", Modifier.size(14.dp), tint = c.onAccent)
        }
    }
}

@Composable
private fun DjGenreChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val c = Oto.colors
    val bg by animateColorAsState(if (selected) c.accent.copy(alpha = 0.22f) else c.field, tween(Motion.EFFECTS), label = "djGenreBg")
    val fg by animateColorAsState(if (selected) c.accent else c.text, tween(Motion.EFFECTS), label = "djGenreFg")
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
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
