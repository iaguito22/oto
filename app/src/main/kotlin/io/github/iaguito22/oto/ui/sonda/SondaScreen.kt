package io.github.iaguito22.oto.ui.sonda

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.playback.NowPlaying
import io.github.iaguito22.oto.playback.PlayerConnection
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Radius
import io.github.iaguito22.oto.ui.theme.Space
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.tanh

@Composable
fun SondaScreen(vm: SondaViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val connection = (LocalContext.current.applicationContext as OtoApp).player
    val np by connection.state.collectAsStateWithLifecycle()
    val c = Oto.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.base)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        Column(Modifier.padding(horizontal = Space.xl, vertical = Space.lg)) {
            Text("Oto", style = OtoType.display, color = c.text)
            Text(status(s), style = OtoType.rowSub, color = c.textMuted)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(s.songs, key = { it.id }) { song ->
                SongRow(song, current = song.id == np.id, onClick = { connection.play(song) })
            }
        }
        AnimatedVisibility(
            np.id != null,
            enter = fadeIn(tween(Motion.EFFECTS)) + expandVertically(tween(Motion.SPATIAL_FAST, easing = Motion.Standard)),
            exit = fadeOut(tween(Motion.EFFECTS)) + shrinkVertically(tween(Motion.SPATIAL_FAST, easing = Motion.Standard)),
        ) {
            NowPlayingBar(np, connection)
        }
        // El campo va abajo, al alcance del pulgar, como irá en el Menú.
        Row(
            Modifier
                .padding(Space.md)
                .fillMaxWidth()
                .height(50.dp)
                .clip(CircleShape)
                .background(c.field)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (s.query.isEmpty()) Text("Canciones, artistas…", style = OtoType.body.copy(fontSize = OtoType.body.fontSize * 1.07f), color = c.textFaint)
                BasicTextField(
                    value = s.query,
                    onValueChange = vm::onQuery,
                    singleLine = true,
                    textStyle = OtoType.body.copy(color = c.text, fontSize = OtoType.body.fontSize * 1.07f),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(s.loading, enter = fadeIn(tween(Motion.EFFECTS)), exit = fadeOut(tween(Motion.EFFECTS))) {
                Box(Modifier.padding(start = Space.sm).size(8.dp).clip(CircleShape).background(c.accent))
            }
        }
    }
}

private fun status(s: SondaState): String = when {
    s.error != null -> "Error: ${s.error}"
    s.loading -> "Buscando…"
    s.tookMs != null -> "${s.songs.size} canciones en ${s.tookMs} ms"
    else -> "Fase 3 · busca algo y tócalo para que suene"
}

@Composable
private fun SongRow(song: SongItem, current: Boolean, onClick: () -> Unit) {
    val c = Oto.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(Radius.sm)).background(c.field),
        )
        Spacer(Modifier.size(Space.md))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = OtoType.rowTitle, color = if (current) c.accent else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(song.artists.joinToString { it.name })
                    song.duration?.let { append(" · %d:%02d".format(it / 60, it % 60)) }
                },
                style = OtoType.rowSub, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Provisional hasta la gota de la fase 4, pero con sus mismos gestos: tocar = pausa, a los lados = otra canción.
 * Sigue al dedo con resistencia (k·tanh(d/k), como en el prototipo) y vibra al pasar el umbral.
 */
@Composable
private fun NowPlayingBar(np: NowPlaying, connection: PlayerConnection) {
    val c = Oto.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val threshold = with(density) { 52.dp.toPx() }
    val k = with(density) { 92.dp.toPx() }
    val drag = remember { Animatable(0f) }
    val armed = remember { booleanArrayOf(false) }

    // La posición se lee por fotograma y solo invalida el dibujo de la línea, no la composición.
    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(np.id, np.playing, np.durationMs) {
        do {
            withFrameNanos { }
            progress.floatValue = if (np.durationMs > 0) (connection.position().toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
        } while (np.playing)
    }
    val dim by animateFloatAsState(if (np.playing) 1f else 0.55f, tween(Motion.EFFECTS, easing = Motion.Standard), label = "dim")

    Box(
        Modifier
            .padding(horizontal = Space.md)
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer { translationX = k * tanh(drag.value / k) }
            .clip(RoundedCornerShape(Radius.lg))
            .background(c.field)
            .drawWithContent {
                drawContent()
                val y = size.height - 1.5.dp.toPx()
                drawLine(c.line, Offset(0f, y), Offset(size.width, y), 3.dp.toPx())
                drawLine(c.accent, Offset(0f, y), Offset(size.width * progress.floatValue, y), 3.dp.toPx())
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { connection.toggle() }) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val d = drag.value
                        if (abs(d) > threshold) if (d < 0) connection.next() else connection.previous()
                        armed[0] = false
                        scope.launch { drag.animateTo(0f, Motion.spatial()) }
                    },
                    onDragCancel = {
                        armed[0] = false
                        scope.launch { drag.animateTo(0f, Motion.spatial()) }
                    },
                ) { change, dx ->
                    change.consume()
                    val d = drag.value + dx
                    if ((abs(d) > threshold) != armed[0]) {
                        armed[0] = !armed[0]
                        if (armed[0]) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    }
                    scope.launch { drag.snapTo(d) }
                }
            },
    ) {
        AnimatedContent(
            targetState = np,
            contentKey = { it.id },
            transitionSpec = { fadeIn(tween(Motion.EFFECTS, delayMillis = 60)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) },
            label = "song",
        ) { song ->
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp).graphicsLayer { alpha = dim },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = song.artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(Radius.sm)).background(c.field),
                )
                Spacer(Modifier.size(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            song.error != null -> "Error: ${song.error}"
                            song.buffering -> "Cargando…"
                            else -> "${song.artist} · ${song.upNext} en cola"
                        },
                        style = OtoType.rowSub, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
