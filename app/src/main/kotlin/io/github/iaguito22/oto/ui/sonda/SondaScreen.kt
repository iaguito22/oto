package io.github.iaguito22.oto.ui.sonda

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Radius
import io.github.iaguito22.oto.ui.theme.Space

@Composable
fun SondaScreen(vm: SondaViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
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
            items(s.songs, key = { it.id }) { SongRow(it) }
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
    else -> "Fase 2 · busca algo para probar YouTube Music"
}

@Composable
private fun SongRow(song: SongItem) {
    val c = Oto.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
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
            Text(song.title, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
