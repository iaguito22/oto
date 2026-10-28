package io.github.iaguito22.oto.ui.menu

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import io.github.iaguito22.oto.ui.theme.Motion
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.toSong
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Radius
import io.github.iaguito22.oto.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** El id con el que suena «todo lo descargado» (y se vuelve a abrir desde lo que suena). */
const val DOWNLOADS_ID = "downloads"

/**
 * Lo descargado: las listas (con lo que entre en ellas después) y las canciones sueltas, cada cosa con su ×. Todo
 * suena sin conexión. La × quita al momento ([onRemoveList], [onRemoveSong]) y el aviso deja deshacerlo: lo quitado
 * no se borra hasta que el aviso se va ([gone]: lo que está esperando). Borrarlo todo sí pide un segundo toque.
 */
@Composable
internal fun DownloadsView(
    currentId: String?,
    gone: Set<String>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    play: (SongItem) -> Unit,
    onPlayAll: (LocalPlaylist) -> Unit,
    onHold: (SongItem) -> Unit,
    onRemoveList: (LocalPlaylist) -> Unit,
    onRemoveSong: (Heard) -> Unit,
) {
    val c = Oto.colors
    val app = LocalContext.current.applicationContext as OtoApp
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val states by app.downloads.states.collectAsStateWithLifecycle()
    val waiting by app.downloads.waiting.collectAsStateWithLifecycle()
    val saver by prefs.dataSaver.collectAsStateWithLifecycle(initialValue = false)
    val listIds by prefs.downloadLists.collectAsStateWithLifecycle(initialValue = null)
    val singles by prefs.downloadSongs.collectAsStateWithLifecycle(initialValue = null)
    val playlists by prefs.playlists.collectAsStateWithLifecycle(initialValue = null)
    val liked by prefs.likedList.collectAsStateWithLifecycle(initialValue = null)
    // Lo que ocupan: se vuelve a medir cada vez que acaba (o se quita) alguna.
    val done = states.count { it.value == Download.STATE_COMPLETED }
    val size by produceState(0L, done) { value = withContext(Dispatchers.IO) { app.downloads.bytes() } }
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(confirm) { if (confirm != null) { delay(3000); confirm = null } }

    val lists = listIds?.filter { "dl:$it" !in gone }?.mapNotNull { id -> if (id == LIKED_ID) liked else playlists?.firstOrNull { it.id == id } }.orEmpty()
    val songs = singles.orEmpty().filter { "ds:${it.id}" !in gone }
    val isDone = { id: String -> states[id] == Download.STATE_COMPLETED }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(bottom = Space.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(R.drawable.ic_arrow_back, onClick = onBack)
                    Text("Descargas", style = OtoType.title, color = c.text)
                }
                val pending = states.count { it.value != Download.STATE_COMPLETED }
                val line = buildList {
                    // Lo que se acaba de quitar (y aún se puede deshacer) ya no cuenta.
                    add(count((lists.flatMap { it.songs } + songs).distinctBy { it.id }.count { isDone(it.id) }) + if (size > 0) " · ${bytes(size)}" else "")
                    if (pending > 0) add(
                        when {
                            waiting && saver -> "$pending esperando al wifi"
                            waiting -> "$pending esperando conexión"
                            else -> "bajando $pending"
                        },
                    )
                }.joinToString(" · ")
                if (states.isNotEmpty()) Text("Suenan sin conexión. $line", style = OtoType.rowSub, color = c.textMuted, modifier = Modifier.padding(horizontal = Space.md))
                val all = remember(lists, songs, states) {
                    (lists.flatMap { it.songs } + songs).distinctBy { it.id }.filter { isDone(it.id) }
                }
                if (all.isNotEmpty()) Row(
                    Modifier.padding(start = Space.md, top = Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Pill("Reproducir todo", { onPlayAll(LocalPlaylist(DOWNLOADS_ID, "Tus descargas", all)) }, icon = R.drawable.ic_play_arrow)
                    ShuffleButton(DOWNLOADS_ID)
                }
            }
        }
        if (listIds != null && singles != null && lists.isEmpty() && songs.isEmpty()) item(key = "empty") {
            Empty(
                R.drawable.ic_download_for_offline, "Nada descargado",
                "En una lista tuya, toca ··· y elige «Descargar»: se descarga entera, con lo que añadas después. Una canción suelta: mantenla pulsada y elige «Descargar».",
                Modifier.height(320.dp),
            )
        }
        if (lists.isNotEmpty()) item(key = "listsTitle") { Label("Listas") }
        items(lists, key = { "l:" + it.id }) { l ->
            val got = l.songs.count { isDone(it.id) }
            ListRow(
                l,
                if (got == l.songs.size) "Descargada · ${count(got)}" else "Bajando · $got de ${l.songs.size}",
                got,
                onClick = { onOpen(l.id) },
                onRemove = { onRemoveList(l) },
                modifier = Modifier.animateItem(),
            )
        }
        if (songs.isNotEmpty()) item(key = "songsTitle") { Label("Canciones") }
        items(songs, key = { "s:" + it.id }) { h ->
            SongRow(
                h.title, h.artist, h.artwork, h.id == currentId,
                onClick = { play(h.toSong()) }, onLongClick = { onHold(h.toSong()) }, downloaded = isDone(h.id),
                modifier = Modifier.animateItem(),
                trailing = { RemoveButton { onRemoveSong(h) } },
            )
        }
        if (lists.isNotEmpty() || songs.isNotEmpty()) item(key = "clear") {
            Box(Modifier.fillMaxWidth().padding(top = Space.lg), contentAlignment = Alignment.Center) {
                Pill(if (confirm == "all") "Toca otra vez para borrarlo todo" else "Borrar todas las descargas", {
                    if (confirm == "all") { confirm = null; scope.launch { app.downloads.clear() } } else confirm = "all"
                }, filled = false, icon = R.drawable.ic_delete)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = OtoType.label, color = Oto.colors.textMuted, modifier = Modifier.padding(start = Space.md, top = Space.lg, bottom = Space.xs))
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    val c = Oto.colors
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_close), "Quitar la descarga", Modifier.size(20.dp), tint = c.textMuted)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(l: LocalPlaylist, status: String, got: Int, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier) {
    val c = Oto.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .combinedClickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(horizontal = Space.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Lo que baja lleva su aro en la esquina; lo bajado, la marca.
        Box {
            if (l.id == LIKED_ID) LikedArt(52.dp) else Mosaic(l.songs, 52.dp)
            DownloadMark(got, l.songs.size, Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp), size = 22.dp)
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(l.name, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = OtoType.rowSub, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RemoveButton(onRemove)
    }
}
