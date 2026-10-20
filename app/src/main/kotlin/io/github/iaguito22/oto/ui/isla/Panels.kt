package io.github.iaguito22.oto.ui.isla

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import io.github.iaguito22.oto.playback.QueueEntry
import io.github.iaguito22.oto.ui.theme.Radius
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.playback.PlayerConnection
import io.github.iaguito22.oto.ui.menu.Empty
import io.github.iaguito22.oto.ui.menu.LoopButton
import io.github.iaguito22.oto.ui.menu.ShuffleButton
import io.github.iaguito22.oto.ui.menu.SongRow
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Space
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.abs

@Composable
private fun Grab() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(Oto.colors.textFaint))
    }
}

@Composable
private fun Header(title: String, sub: String?) {
    Column(Modifier.padding(start = Space.lg + 4.dp, end = Space.lg, top = Space.md, bottom = Space.sm)) {
        Text(title, style = OtoType.title, color = Oto.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = OtoType.rowSub, color = Oto.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Divider(text: String) {
    Text(
        text.uppercase(), style = OtoType.label, color = Oto.colors.textMuted,
        modifier = Modifier.padding(start = Space.md, top = Space.lg, bottom = Space.xs),
    )
}

/** Mantener la gota: lo que viene después. Tocar una salta a ella; mantenerla la mueve y deslizarla la quita. */
@Composable
fun QueueSheet(connection: PlayerConnection, onClose: () -> Unit) {
    val queue by connection.queue.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    // Mientras se arrastra, una copia que se reordena al momento; al soltar se manda al reproductor y, cuando este
    // devuelve la cola nueva, se vuelve a la suya.
    var local by remember { mutableStateOf<List<QueueEntry>?>(null) }
    var dragged by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(queue) { if (dragged == null) local = null }
    val shown = local ?: queue
    Column(Modifier.fillMaxSize()) {
        Grab()
        val source by connection.source.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Header("A continuación", if (queue.isEmpty()) null else if (queue.size == 1) "1 canción" else "${queue.size} canciones")
            }
            // Una lista se puede barajar o volver a su orden, y poner en bucle; la radio ya va a su aire.
            val list = source != null && source?.kind != "Radio"
            if (list) Row(Modifier.padding(end = Space.lg, top = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                if (source?.kind != "DJ") LoopButton()
                ShuffleButton()
            }
        }
        OutputChip(Modifier.padding(start = Space.lg, bottom = Space.sm))
        if (queue.isEmpty()) {
            Empty(R.drawable.ic_queue_music, "No hay nada detrás", "Pon una canción y la radio irá llenando esto sola.")
        } else {
            // Dónde empieza lo que no es de la lista: las parecidas del final o, en bucle, la vuelta al principio.
            val firstAuto = shown.indexOfFirst { it.auto }
            // De la cola del reproductor y no de [shown]: mientras se arrastra, sus índices ya no van en orden. Las
            // dos partes (hasta el final y la vuelta al principio) no se mezclan, así que la vuelta sigue en el mismo sitio.
            val wrap = (1 until queue.size).firstOrNull { queue[it].index < queue[it - 1].index } ?: -1
            val keys = remember(shown) { keysOf(shown) }
            val reorder = rememberReorderableLazyListState(listState) { from, to ->
                val list = local ?: queue
                val part = { i: Int -> wrap in 1..i }
                if (to.key !is String || from.index !in list.indices || to.index !in list.indices || part(from.index) != part(to.index)) return@rememberReorderableLazyListState
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                local = list.toMutableList().apply { add(to.index, removeAt(from.index)) }
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
                itemsIndexed(shown, key = { i, _ -> keys[i] }) { i, e ->
                    val key = keys[i]
                    ReorderableItem(reorder, key, animateItemModifier = Modifier.animateItem(placementSpec = spring(stiffness = Spring.StiffnessMediumLow))) { dragging ->
                        Column(
                            Modifier.graphicsLayer {
                                if (dragging) {
                                    scaleX = 1.03f; scaleY = 1.03f
                                    shadowElevation = 12.dp.toPx()
                                    shape = RoundedCornerShape(Radius.lg)
                                    clip = true
                                }
                            },
                        ) {
                            if (i == firstAuto) Divider("Al acabar la lista, parecidas")
                            if (i == wrap) Divider("Y vuelve a empezar")
                            Removable(onRemove = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); connection.remove(e.index) }) {
                                SongRow(
                                    e.title, e.artist, e.artwork, current = false, onClick = { connection.skipTo(e.index); onClose() },
                                    modifier = Modifier
                                        .background(if (dragging) Oto.colors.field else Color.Transparent, RoundedCornerShape(Radius.lg))
                                        .longPressDraggableHandle(
                                            onDragStarted = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                dragged = key
                                                local = local ?: queue
                                            },
                                            onDragStopped = {
                                                // La canción pasa, en el reproductor, al sitio de la que ocupaba ese hueco al empezar.
                                                val now = local
                                                dragged = null
                                                val from = keysOf(queue).indexOf(key)
                                                val to = now?.let { keysOf(it).indexOf(key) } ?: -1
                                                if (from >= 0 && to >= 0 && from != to) connection.move(queue[from].index, queue[to].index)
                                                else local = null
                                            },
                                        ),
                                )
                            }
                        }
                    }
                }
                item(key = "hint") {
                    Text(
                        "Mantén una canción para moverla; deslízala a un lado para quitarla.",
                        style = OtoType.rowSub, color = Oto.colors.textFaint,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.lg),
                    )
                }
            }
        }
    }
}

/** Claves que no cambian al mover una canción (su id, y cuántas veces salió antes si está repetida). */
private fun keysOf(list: List<QueueEntry>): List<String> {
    val seen = HashMap<String, Int>()
    return list.map { e -> val n = seen.merge(e.id, 1, Int::plus)!!; "${e.id}#$n" }
}

/** Deslizar a cualquier lado la quita, con el fondo en rojo suave y una papelera. */
@Composable
private fun Removable(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            // Del desplazamiento y no de state.progress: quieta, progress vale 1 y el rojo se veía en todas las filas.
            // Se lee al dibujar, así deslizar no recompone la fila.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val off = abs(runCatching { state.requireOffset() }.getOrDefault(0f))
                        alpha = (off / (size.width * 0.3f)).coerceIn(0f, 1f)
                    }
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(Color(0xFFFF8A80).copy(alpha = 0.18f)),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(painterResource(R.drawable.ic_delete), "Quitar", Modifier.padding(horizontal = Space.lg).size(22.dp), tint = Color(0xFFFF8A80))
            }
        },
        onDismiss = { onRemove() },
    ) { content() }
}

