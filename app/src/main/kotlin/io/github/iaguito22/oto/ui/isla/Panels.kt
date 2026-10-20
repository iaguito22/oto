package io.github.iaguito22.oto.ui.isla

import androidx.compose.foundation.background
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

/** Mantener la gota: lo que viene después. Tocar una salta a ella. */
@Composable
fun QueueSheet(connection: PlayerConnection, onClose: () -> Unit) {
    val queue by connection.queue.collectAsStateWithLifecycle()
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
        if (queue.isEmpty()) {
            Empty(R.drawable.ic_queue_music, "No hay nada detrás", "Pon una canción y la radio irá llenando esto sola.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
                // Dónde empieza lo que no es de la lista: las parecidas del final o, en bucle, la vuelta al principio.
                val firstAuto = queue.indexOfFirst { it.auto }
                val wrap = (1 until queue.size).firstOrNull { queue[it].index < queue[it - 1].index } ?: -1
                itemsIndexed(queue, key = { _, e -> "${e.index}:${e.id}" }) { i, e ->
                    Column(Modifier.animateItem()) {
                        if (i == firstAuto) Divider("Al acabar la lista, parecidas")
                        if (i == wrap) Divider("Y vuelve a empezar")
                        SongRow(e.title, e.artist, e.artwork, current = false, onClick = { connection.skipTo(e.index); onClose() })
                    }
                }
            }
        }
    }
}
