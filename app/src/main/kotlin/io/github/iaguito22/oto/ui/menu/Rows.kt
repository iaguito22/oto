package io.github.iaguito22.oto.ui.menu

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.playback.resize
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Radius
import io.github.iaguito22.oto.ui.theme.Space

fun SongItem.subtitle() = buildString {
    append(artists.joinToString { it.name })
    duration?.let { append(" · %d:%02d".format(it / 60, it % 60)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    title: String,
    subtitle: String,
    artwork: String?,
    current: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    downloaded: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    /** En lugar de la carátula (una búsqueda reciente lleva su reloj). */
    lead: (@Composable () -> Unit)? = null,
) {
    val c = Oto.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = c.text),
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = Space.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lead != null) lead() else Art(artwork, 44.dp, if (round) 44.dp else Radius.sm)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = OtoType.rowTitle, color = if (current) c.accent else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty() || downloaded) Row(verticalAlignment = Alignment.CenterVertically) {
                // Descargada: una flecha pequeña delante, como en Spotify.
                if (downloaded) {
                    Icon(painterResource(io.github.iaguito22.oto.R.drawable.ic_download_for_offline), "Descargada", Modifier.size(14.dp), tint = c.accent)
                    Spacer(Modifier.width(4.dp))
                }
                Text(subtitle, style = OtoType.rowSub, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (current) {
            Spacer(Modifier.width(Space.sm))
            NowBars(LocalPlaying.current, c.accent)
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.xs))
            trailing()
        }
    }
}

/** Si lo que suena está sonando (y no en pausa): las barras de la que suena se mueven solo entonces. */
val LocalPlaying = compositionLocalOf { false }

/**
 * Las barras de «esta es la que suena»: botan mientras suena, cada una a su ritmo, y en pausa se quedan bajas y
 * quietas. Se mueven en el dibujo, sin recomponer la fila.
 */
@Composable
fun NowBars(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val bars = remember { List(3) { Animatable(0.3f) } }
    LaunchedEffect(playing) {
        bars.forEachIndexed { i, bar ->
            launch {
                if (!playing) { bar.animateTo(0.3f, tween(260, easing = Motion.Standard)); return@launch }
                delay(i * 70L)
                while (true) {
                    // Entre un 25 % y el alto entero, cada vez distinto, como un vúmetro.
                    bar.animateTo(0.25f + Random.nextFloat() * 0.75f, tween(170 + Random.nextInt(170), easing = Motion.Standard))
                }
            }
        }
    }
    Box(
        modifier.size(16.dp).drawBehind {
            val w = 3.dp.toPx()
            val gap = (size.width - 3 * w) / 2
            bars.forEachIndexed { i, bar ->
                val h = size.height * bar.value
                drawRoundRect(color, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 2))
            }
        },
    )
}

/** Carátula con el hueco ya pintado mientras carga, para que nada salte. */
@Composable
fun Art(url: String?, size: Dp, radius: Dp, modifier: Modifier = Modifier) {
    val c = Oto.colors
    AsyncImage(
        model = url?.resize((size.value * 3).toInt()),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(if (radius >= size / 2) CircleShape else RoundedCornerShape(radius)).background(c.field),
    )
}

/** Lo que ocupa el sitio de una lista mientras llega: bloques que respiran, sin girar nada. */
@Composable
fun Skeleton(rows: Int = 6, modifier: Modifier = Modifier) {
    val c = Oto.colors
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.5f, 1f, infiniteRepeatable(tween(900, easing = Motion.Standard), RepeatMode.Reverse), label = "breath")
    Column(modifier.graphicsLayer { alpha = a }, verticalArrangement = Arrangement.spacedBy(Space.md)) {
        repeat(rows) { i ->
            Row(Modifier.padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(Radius.sm)).background(c.field))
                Spacer(Modifier.width(Space.md))
                Column {
                    Box(Modifier.height(12.dp).fillMaxWidth(0.55f + 0.08f * (i % 3)).clip(CircleShape).background(c.field))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.height(10.dp).fillMaxWidth(0.32f + 0.06f * (i % 2)).clip(CircleShape).background(c.field))
                }
            }
        }
    }
}

/**
 * Lo que ocupa el sitio de unos carruseles mientras llegan: título y tres tarjetas que respiran, con la forma de lo
 * que va a llegar (antes eran filas de lista, y al llegar todo saltaba).
 */
@Composable
fun ShelfSkeleton(shelves: Int, modifier: Modifier = Modifier) {
    val c = Oto.colors
    val t = rememberInfiniteTransition(label = "shelfSkeleton")
    val a by t.animateFloat(0.5f, 1f, infiniteRepeatable(tween(900, easing = Motion.Standard), RepeatMode.Reverse), label = "breath")
    Column(modifier.graphicsLayer { alpha = a }) {
        repeat(shelves) {
            // Como SectionTitle: 16 arriba, 8 abajo y una línea de 23.
            Box(Modifier.padding(start = Space.lg, top = Space.lg + 4.dp, bottom = Space.sm + 4.dp).height(15.dp).fillMaxWidth(0.42f).clip(CircleShape).background(c.field))
            // Como un carrusel de Card: más ancho que la pantalla, la tercera asoma.
            Row(Modifier.horizontalScroll(rememberScrollState(), enabled = false).padding(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                repeat(3) {
                    Column(Modifier.width(140.dp + Space.md).padding(Space.xs + 2.dp)) {
                        Box(Modifier.size(140.dp).clip(RoundedCornerShape(Radius.md)).background(c.field))
                        Spacer(Modifier.height(Space.sm + 2.dp))
                        Box(Modifier.height(12.dp).fillMaxWidth(0.8f).clip(CircleShape).background(c.field))
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.height(10.dp).fillMaxWidth(0.55f).clip(CircleShape).background(c.field))
                    }
                }
            }
        }
    }
}

/**
 * En la esquina de la portada de una lista descargada: un aro que se llena mientras baja ([done] de [total]) y, al
 * acabar, la marca de descargada.
 */
@Composable
fun DownloadMark(done: Int, total: Int, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val c = Oto.colors
    val p by animateFloatAsState(if (total == 0) 1f else (done.toFloat() / total).coerceIn(0f, 1f), tween(Motion.SPATIAL, easing = Motion.Standard), label = "downloadMark")
    Box(modifier.size(size).clip(CircleShape).background(c.base.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
        if (done >= total) Icon(painterResource(io.github.iaguito22.oto.R.drawable.ic_download_for_offline), "Descargada", Modifier.size(size - 4.dp), tint = c.accent)
        else Canvas(Modifier.size(size * 0.62f)) {
            val w = 2.5.dp.toPx()
            val inset = Offset(w / 2, w / 2)
            val arc = Size(this.size.width - w, this.size.height - w)
            drawArc(c.text.copy(alpha = 0.18f), 0f, 360f, false, inset, arc, style = Stroke(w))
            drawArc(c.accent, -90f, 360f * p, false, inset, arc, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

/** Un hueco vacío explicado: icono, una frase y, si hace falta, qué hacer. */
@Composable
fun Empty(icon: Int, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = Oto.colors
    Column(modifier.fillMaxSize().padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(icon), null, Modifier.size(36.dp), tint = c.textFaint)
        Spacer(Modifier.height(Space.md))
        Text(title, style = OtoType.rowTitle, color = c.text, textAlign = TextAlign.Center)
        if (body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = OtoType.rowSub, color = c.textMuted, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(Space.lg))
            action()
        }
    }
}

@Composable
fun Pill(text: String, onClick: () -> Unit, filled: Boolean = true, icon: Int? = null) {
    val c = Oto.colors
    Row(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (filled) c.accent else c.field)
            .combinedClickable(remember { MutableInteractionSource() }, ripple(color = if (filled) c.onAccent else c.text), onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = if (filled) c.onAccent else c.text)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.08f), color = if (filled) c.onAccent else c.text)
    }
}
