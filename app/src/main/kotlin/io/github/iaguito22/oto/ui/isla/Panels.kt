package io.github.iaguito22.oto.ui.isla

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/** Mantener la gota: lo que viene después. Tocar una salta a ella. */
@Composable
fun QueueSheet(connection: PlayerConnection, onClose: () -> Unit) {
    val queue by connection.queue.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Grab()
        Header("A continuación", if (queue.isEmpty()) null else if (queue.size == 1) "1 canción" else "${queue.size} canciones")
        if (queue.isEmpty()) {
            Empty(R.drawable.ic_queue_music, "No hay nada detrás", "Pon una canción y la radio irá llenando esto sola.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
                items(queue, key = { "${it.index}:${it.id}" }) { e ->
                    SongRow(e.title, e.artist, e.artwork, current = false, onClick = { connection.skipTo(e.index); onClose() }, modifier = Modifier.animateItem())
                }
            }
        }
    }
}
