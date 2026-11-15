package io.github.iaguito22.oto.ui.isla

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ripple
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import io.github.iaguito22.oto.dj.DjBridgeStyle
import io.github.iaguito22.oto.dj.DjMode
import io.github.iaguito22.oto.dj.DjPickInfo
import io.github.iaguito22.oto.dj.Kind
import io.github.iaguito22.oto.playback.NextMix
import io.github.iaguito22.oto.playback.QueueEntry
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Radius
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.playback.PlayerConnection
import io.github.iaguito22.oto.ui.menu.Art
import io.github.iaguito22.oto.ui.menu.Empty
import io.github.iaguito22.oto.ui.menu.djGenreLabel
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
private fun Divider(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = OtoType.label, color = Oto.colors.textMuted,
        modifier = modifier.padding(start = Space.md, top = Space.lg, bottom = Space.xs),
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
        val nextUp by connection.djNextUp.collectAsStateWithLifecycle()
        val djTanda by connection.djTanda.collectAsStateWithLifecycle()
        val nextTransitionStyle by connection.djNextTransitionStyle.collectAsStateWithLifecycle()
        val nextMix by connection.djMix.collectAsStateWithLifecycle()
        val isDj = source?.kind == "DJ" && !connection.mirrored
        val nextUpById = remember(nextUp) { nextUp.associateBy { it.track.id } }
        val nextUpOrderById = remember(nextUp) { nextUp.withIndex().associate { (idx, it) -> it.track.id to (idx + 1) } }
        val countLabel = if (queue.isEmpty()) null else if (queue.size == 1) "1 canción" else "${queue.size} canciones"
        val djGenre = djGenreLabel(djTanda)
        val headerSub = if (isDj) listOfNotNull(djGenre, countLabel).joinToString(" · ").ifEmpty { "Sesión inteligente" } else countLabel
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Header(if (isDj) "Cola del DJ" else "A continuación", headerSub)
            }
            // Una lista se puede barajar o volver a su orden, y poner en bucle. La radio va a su aire, pero la canción
            // que suena se puede repetir.
            val radio = source?.kind == "Radio"
            if (isDj) {
                DjSkipMoodPill(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        connection.skipDjTanda()
                    },
                    modifier = Modifier.padding(end = Space.lg, top = Space.sm),
                )
            } else if (source != null) {
                Row(Modifier.padding(end = Space.lg, top = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    LoopButton(radio)
                    if (!radio) ShuffleButton()
                }
            }
        }
        Row(Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            OutputChip()
            Spacer(Modifier.weight(1f))
            if (queue.isNotEmpty() || (isDj && nextUp.isNotEmpty())) {
                Text(
                    if (isDj && nextUp.isNotEmpty()) "Desliza o toca «Otra»" else "Desliza para quitar",
                    style = OtoType.label.copy(fontSize = 11.sp), color = Oto.colors.textFaint, maxLines = 1,
                )
            }
        }
        if (isDj) {
            DjQueueControls(connection)
        }
        if (queue.isEmpty()) {
            if (isDj && nextUp.isNotEmpty()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item(key = NEXT_UP_DIVIDER) { DjSectionDivider(Modifier.animateItem()) }
                    itemsIndexed(nextUp, key = { _, it -> "nextup:" + it.track.id }) { idx, pick ->
                        val swap = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            connection.vetoDjNextUp(pick.track.id)
                        }
                        Removable(onRemove = swap) {
                            DjNextUpRow(
                                order = idx + 1,
                                title = pick.track.title,
                                artist = pick.track.artist,
                                artwork = pick.track.artwork,
                                pick = pick,
                                transitionStyle = if (idx == 0 && !connection.mirrored) nextTransitionStyle else null,
                                mix = nextMix.takeIf { idx == 0 && !connection.mirrored },
                                onClick = {},
                                onSwap = swap,
                            )
                        }
                    }
                }
            } else if (isDj) {
                DjQueueEmptyState()
            } else {
                Empty(R.drawable.ic_queue_music, "No hay nada detrás", "Pon una canción y la radio irá llenando esto sola.")
            }
        } else {
            // Dónde empieza lo que no es de la lista: las parecidas del final o, en bucle, la vuelta al principio.
            val firstAuto = shown.indexOfFirst { it.auto }
            val firstNextUp = if (isDj) shown.indexOfFirst { it.id in nextUpById } else -1
            // Dónde empieza, en bucle, la vuelta al principio (de la cola del reproductor: mientras se arrastra, sus
            // índices ya no van en orden). Al arrastrar, el aviso hace de frontera: cruzarlo pasa la canción a la
            // otra vuelta y el aviso se corre un sitio, en vez de que la cruce otra.
            val wrap = (1 until queue.size).firstOrNull { queue[it].index < queue[it - 1].index } ?: -1
            var localWrap by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(queue) { if (dragged == null) localWrap = null }
            val border = localWrap ?: wrap
            val keys = remember(shown) { keysOf(shown) }
            val reorder = rememberReorderableLazyListState(listState) { fromItem, toItem ->
                val list = local ?: queue
                // Por la clave y no por el índice del LazyColumn, que cuenta también los avisos.
                val listKeys = keysOf(list)
                val from = listKeys.indexOf(fromItem.key)
                val to = listKeys.indexOf(toItem.key)
                if (from < 0 || to < 0) return@rememberReorderableLazyListState
                val w = localWrap ?: wrap
                val at = when {
                    w < 1 -> to
                    from < w && to >= w -> { localWrap = w - 1; w - 1 } // baja a la vuelta siguiente
                    from >= w && to < w -> { localWrap = w + 1; w } // sube a esta vuelta
                    else -> to
                }
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                if (at != from) local = list.toMutableList().apply { add(at, removeAt(from)) }
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
                shown.forEachIndexed { i, e ->
                    // Los avisos van en su propia fila, fuera de las canciones: pegados a una, al subir otra a su
                    // sitio el aviso saltaba de una a otra, cambiaban de alto a mitad del arrastre y se intercambiaban
                    // sin parar hasta llegar arriba del todo.
                    if (i == firstNextUp) item(key = NEXT_UP_DIVIDER) { DjSectionDivider(Modifier.animateItem()) }
                    if (i == firstAuto) item(key = AUTO_DIVIDER) { Divider("Al acabar la lista, parecidas", Modifier.animateItem()) }
                    if (i == border) item(key = WRAP_DIVIDER) { Divider("Y vuelve a empezar", Modifier.animateItem()) }
                    val key = keys[i]
                    val pick = if (isDj) nextUpById[e.id] else null
                    val isNextTrack = i == 0 && !connection.mirrored
                    item(key = key) {
                        if (pick != null) {
                            val swap = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                connection.vetoDjNextUp(e.id)
                            }
                            Removable(onRemove = swap) {
                                DjNextUpRow(
                                    order = nextUpOrderById[e.id],
                                    title = e.title,
                                    artist = e.artist,
                                    artwork = e.artwork,
                                    pick = pick,
                                    transitionStyle = if (isNextTrack) nextTransitionStyle else null,
                                    mix = nextMix.takeIf { isNextTrack },
                                    onClick = { connection.skipTo(e.index); onClose() },
                                    onSwap = swap,
                                    modifier = Modifier.animateItem().padding(vertical = 3.dp),
                                )
                            }
                        } else {
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
                                                        val w = (localWrap ?: wrap).let { if (it < 0) Int.MAX_VALUE else it }
                                                        dragged = null
                                                        val from = keysOf(queue).indexOf(key)
                                                        val to = now?.let { keysOf(it).indexOf(key) } ?: -1
                                                        if (now != null && from >= 0 && to >= 0 && (from != to || localWrap != null && localWrap != wrap)) {
                                                            // Se coloca junto a sus vecinas de la misma vuelta: detrás de la de
                                                            // arriba (o de la que suena) en esta; delante de la de abajo en la siguiente.
                                                            val real = queue[from].index
                                                            when {
                                                                to < w -> connection.place(real, after = now.getOrNull(to - 1)?.index)
                                                                to + 1 < now.size -> connection.place(real, before = now[to + 1].index)
                                                                to > w -> connection.place(real, after = now[to - 1].index)
                                                                else -> connection.place(real, before = 0)
                                                            }
                                                        } else { local = null; localWrap = null }
                                                    },
                                                ),
                                        )
                                    }
                                    if (isNextTrack && (nextTransitionStyle != null || nextMix != null)) {
                                        MixTransitionBadge(
                                            style = nextTransitionStyle,
                                            mix = nextMix,
                                            modifier = Modifier.padding(start = Space.md, top = 2.dp, bottom = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (!isDj || shown.any { it.id !in nextUpById }) {
                    item(key = "hint") {
                        Text(
                            "Mantén una canción para moverla.",
                            style = OtoType.rowSub, color = Oto.colors.textFaint,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.lg),
                        )
                    }
                }
            }
        }
    }
}

// Las claves de los avisos de la cola: no son de canciones, así que no se mueven ni se intercambian.
private const val AUTO_DIVIDER = 1
private const val WRAP_DIVIDER = 2
private const val NEXT_UP_DIVIDER = 3

@Composable
private fun DjSkipMoodPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Oto.colors
    Row(
        modifier
            .clip(CircleShape)
            .background(c.accent.copy(alpha = 0.16f))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.accent), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Otro ambiente", modifier = Modifier.size(15.dp), tint = c.accent)
        Text("Otro ambiente", style = OtoType.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), color = c.accent, maxLines = 1)
    }
}

@Composable
private fun DjSectionDivider(modifier: Modifier = Modifier) {
    val c = Oto.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null, modifier = Modifier.size(14.dp), tint = c.accent)
        Text(
            "SIGUIENTES DEL DJ",
            style = OtoType.label.copy(fontSize = 10.5.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold),
            color = c.textMuted,
        )
        HorizontalDivider(Modifier.weight(1f), color = c.line)
    }
}

@Composable
private fun DjQueueEmptyState() {
    val c = Oto.colors
    Box(Modifier.fillMaxSize().padding(horizontal = Space.lg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(c.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null, modifier = Modifier.size(26.dp), tint = c.accent)
            }
            Text("El DJ está preparando lo siguiente", style = OtoType.section, color = c.text)
            Text("Enseguida verás aquí los próximos temas.", style = OtoType.rowSub, color = c.textMuted)
        }
    }
}

private fun djKindCategory(pick: DjPickInfo): String = when {
    pick.isBridge || pick.kind == Kind.BRIDGE -> "Puente"
    pick.kind == Kind.FAMILIAR -> "Clásico"
    pick.kind == Kind.DEEP -> "De tus artistas"
    pick.kind == Kind.NEW -> "Descubrimiento"
    else -> ""
}

private fun djReasonText(pick: DjPickInfo, category: String): String {
    val reason = pick.reason.trim()
    if (reason.isEmpty()) return ""
    if (reason.equals(category, ignoreCase = true)) return ""
    return reason
}

@Composable
private fun DjNextUpRow(
    order: Int?,
    title: String,
    artist: String,
    artwork: String?,
    pick: DjPickInfo,
    transitionStyle: DjBridgeStyle? = null,
    mix: NextMix? = null,
    onClick: () -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Oto.colors
    val category = remember(pick) { djKindCategory(pick) }
    val reason = remember(pick, category) { djReasonText(pick, category) }
    val highlight = pick.isBridge || pick.kind == Kind.NEW
    val shape = RoundedCornerShape(Radius.lg)
    val cardBg = if (highlight) c.accent.copy(alpha = 0.11f) else c.field
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xs)
            .clip(shape)
            .background(cardBg)
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(horizontal = Space.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (order != null) {
            Text(
                "+$order",
                style = OtoType.label.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = if (highlight) c.accent else c.textFaint,
                modifier = Modifier.width(24.dp),
            )
        }
        Art(artwork, 46.dp, Radius.sm)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (artist.isNotEmpty()) {
                Text(artist, style = OtoType.rowSub, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (category.isNotEmpty() || reason.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (category.isNotEmpty()) {
                        Text(
                            category,
                            style = OtoType.label.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            color = if (highlight) c.accent else c.textMuted,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (highlight) c.accent.copy(alpha = 0.18f) else c.field)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                    if (reason.isNotEmpty()) {
                        Text(
                            reason,
                            style = OtoType.label.copy(fontSize = 11.sp),
                            color = c.textFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
            if (transitionStyle != null || mix != null) {
                MixTransitionBadge(
                    style = transitionStyle,
                    mix = mix,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
        Spacer(Modifier.width(Space.sm))
        Row(
            Modifier
                .clip(CircleShape)
                .background(c.field)
                .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onSwap)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Cambiar por otra", modifier = Modifier.size(13.dp), tint = c.textMuted)
            Text("Otra", style = OtoType.label.copy(fontSize = 11.sp), color = c.textMuted)
        }
    }
}

/**
 * Cómo entra la siguiente: mientras el puente se prepara, «Preparando la mezcla» con un punto que late; lista, su
 * estilo y «lista»; sin puente (en vivo), solo el estilo.
 */
@Composable
private fun MixTransitionBadge(
    style: DjBridgeStyle?,
    mix: NextMix?,
    modifier: Modifier = Modifier,
) {
    val c = Oto.colors
    val preparing = mix?.preparing == true
    val ready = mix?.ready == true
    val label = when {
        preparing -> "Preparando la mezcla"
        ready && style != null -> "${style.label} · lista"
        ready -> "Mezcla lista"
        style != null -> style.label
        else -> return
    }
    val fg by animateColorAsState(if (ready) c.accent else c.textMuted, tween(Motion.EFFECTS), label = "mixBadgeFg")
    val bg by animateColorAsState(if (ready) c.accent.copy(alpha = 0.16f) else c.field, tween(Motion.EFFECTS), label = "mixBadgeBg")
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 500f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (preparing) {
            val pulse = rememberInfiniteTransition(label = "mixPrep")
            val a by pulse.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(700, easing = Motion.Standard), RepeatMode.Reverse),
                label = "mixPrepDot",
            )
            Box(Modifier.size(6.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(c.accent))
        } else {
            Icon(painterResource(R.drawable.ic_transition_fade), contentDescription = null, modifier = Modifier.size(11.dp), tint = fg)
        }
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(Motion.EFFECTS, delayMillis = 60)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) using SizeTransform(clip = false) },
            label = "mixBadgeText",
        ) { text ->
            Text(
                text,
                style = OtoType.label.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

/**
 * Controles en vivo del DJ dentro de la cola: modo rápido, oír entera esta y tus géneros. En varias líneas, todo a la
 * vista: antes era una fila con scroll lateral y los géneros quedaban fuera de la pantalla.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DjQueueControls(connection: PlayerConnection) {
    val haptics = LocalHapticFeedback.current
    val genre by connection.djGenre.collectAsStateWithLifecycle()
    val genres = io.github.iaguito22.oto.ui.menu.rememberDjGenres()
    val mode by connection.djMode.collectAsStateWithLifecycle()
    val fullOnce by connection.djFullOnce.collectAsStateWithLifecycle()
    val adhd = mode == DjMode.Adhd
    Column(
        Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.md).animateContentSize(spring(dampingRatio = 0.85f, stiffness = 400f)),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            QueueChip(
                label = "DJ rápido",
                selected = adhd,
                icon = R.drawable.ic_timer,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    connection.setDjMode(if (adhd) DjMode.Normal else DjMode.Adhd)
                },
            )
            if (adhd) {
                QueueChip(
                    label = if (fullOnce) "Esta suena entera" else "Oír esta entera",
                    selected = fullOnce,
                    icon = if (fullOnce) R.drawable.ic_check else null,
                    onClick = {
                        if (!fullOnce) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            connection.playDjFullOnce()
                        }
                    },
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            QueueChip(
                label = "Todo",
                selected = genre == null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    connection.setDjGenre(null)
                },
            )
            genres.forEach { g ->
                QueueChip(
                    label = g.name,
                    selected = g.name == genre,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        connection.setDjGenre(g.name)
                    },
                )
            }
        }
    }
}

@Composable
private fun QueueChip(label: String, selected: Boolean, onClick: () -> Unit, icon: Int? = null) {
    val c = Oto.colors
    val bg by animateColorAsState(if (selected) c.accent.copy(alpha = 0.20f) else c.field, tween(Motion.EFFECTS), label = "qChipBg")
    val fg by animateColorAsState(if (selected) c.accent else c.textMuted, tween(Motion.EFFECTS), label = "qChipFg")
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(13.dp), tint = fg)
        }
        Text(
            label,
            style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 0.96f),
            color = fg,
            maxLines = 1,
        )
    }
}

