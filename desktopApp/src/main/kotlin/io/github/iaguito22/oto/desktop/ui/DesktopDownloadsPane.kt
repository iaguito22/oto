package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.desktop.playback.DesktopDownloads
import io.github.iaguito22.oto.desktop.playback.DOWNLOADS_ID
import io.github.iaguito22.oto.toSong
import io.github.iaguito22.oto.desktop.ui.MobileIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Descargas» de escritorio, con la misma idea que `DownloadsView.kt` del móvil: las listas enteras (lo que entre
 * después también) y las canciones sueltas, con su estado, su tamaño y una × para quitarlas. Todo suena sin red.
 */
@Composable
internal fun DownloadsPane(
    downloads: DesktopDownloads,
    prefs: Prefs,
    palette: ArtworkPalette,
    currentMediaId: String?,
    onBack: () -> Unit,
    onOpenLocal: (String) -> Unit,
    onPlayLocal: (LocalPlaylist) -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onRemoveList: ((LocalPlaylist) -> Unit)? = null,
    onRemoveSong: ((Heard) -> Unit)? = null,
    onClearAll: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val entries by downloads.entries.collectAsState()
    val listIds by prefs.downloadLists.collectAsState(initial = emptySet())
    val songs by prefs.downloadSongs.collectAsState(initial = emptyList())
    val playlists by prefs.playlists.collectAsState(initial = emptyList())
    val liked by prefs.likedList.collectAsState(initial = LocalPlaylist(LIKED_ID, "Me gusta", emptyList()))
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            kotlinx.coroutines.delay(3000)
            confirmClear = false
        }
    }

    val lists = listIds.mapNotNull { id -> if (id == LIKED_ID) liked else playlists.firstOrNull { it.id == id } }
    val done = entries.values.count { it.state == DesktopDownloads.State.Done }
    val pending = entries.values.count { it.state != DesktopDownloads.State.Done }
    val size by produceState(0L, done, entries.size) {
        value = withContext(Dispatchers.IO) { downloads.bytes() }
    }
    val all = remember(lists, songs, entries) {
        (lists.flatMap { it.songs } + songs)
            .distinctBy { it.id }
            .filter { downloads.has(it.id) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(MobileIcons.arrowBack, "Volver", onBack)
                    Spacer(Modifier.width(10.dp))
                    Text("Descargas", style = OtoType.title, color = Color(0xFFFFFFFF))
                }
                val parts = buildList {
                    if (done > 0 || size > 0L) add("$done ${if (done == 1) "canción" else "canciones"} · ${formatBytes(size)}")
                    if (pending > 0) add("bajando $pending")
                }
                if (entries.isNotEmpty()) {
                    Text(
                        "Suenan sin conexión. ${parts.joinToString(" · ")}",
                        style = OtoType.rowSub,
                        color = Color.White.copy(alpha = 0.58f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (all.isNotEmpty()) {
                    Row(
                        Modifier.padding(top = 12.dp, start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MenuTabPill("Reproducir todo", selected = true, accent = palette.primary) {
                            onPlayLocal(LocalPlaylist(DOWNLOADS_ID, "Tus descargas", all))
                        }
                    }
                }
            }
        }

        if (listIds.isEmpty() && songs.isEmpty()) {
            item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().height(300.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(MobileIcons.downloadOffline, null, Modifier.size(44.dp), tint = Color.White.copy(alpha = 0.35f))
                    Spacer(Modifier.height(10.dp))
                    Text("Nada descargado", style = OtoType.rowTitle, color = Color.White.copy(alpha = 0.8f))
                    Text(
                        "En una lista, abre ··· y elige «Descargar». Una canción suelta: su ··· → «Descargar».",
                        style = OtoType.rowSub,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 40.dp, vertical = 6.dp),
                    )
                }
            }
        }

        if (lists.isNotEmpty()) {
            item(key = "listsTitle") { SectionLabel("Listas") }
            items(lists, key = { "l:" + it.id }) { list ->
                val got = list.songs.count { downloads.has(it.id) }
                val status = when {
                    list.songs.isEmpty() -> "Vacía"
                    got == list.songs.size -> "Descargada · $got canciones"
                    else -> "Bajando · $got de ${list.songs.size}"
                }
                ListRow(
                    name = list.name,
                    status = status,
                    done = got,
                    total = list.songs.size,
                    artwork = list.songs.firstOrNull { !it.artwork.isNullOrBlank() }?.artwork,
                    accent = palette.primary,
                    onClick = { onOpenLocal(list.id) },
                    onRemove = {
                        if (onRemoveList != null) onRemoveList(list)
                        else scope.launch { prefs.setDownloadList(list.id, false) }
                    },
                )
            }
        }

        if (songs.isNotEmpty()) {
            item(key = "songsTitle") { SectionLabel("Canciones") }
            items(songs, key = { "s:" + it.id }) { heard ->
                val entry = entries[heard.id]
                SongDownloadRow(
                    heard = heard,
                    entry = entry,
                    isCurrent = heard.id == currentMediaId,
                    accent = palette.primary,
                    onClick = { onPlaySong(heard.toSong()) },
                    onRemove = {
                        if (onRemoveSong != null) onRemoveSong(heard)
                        else scope.launch { prefs.setDownloadSong(heard, false) }
                    },
                )
            }
        }

        if (listIds.isNotEmpty() || songs.isNotEmpty()) {
            item(key = "clear") {
                Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                    MenuTabPill(
                        label = if (confirmClear) "Toca otra vez para borrarlo todo" else "Borrar todas las descargas",
                        selected = false,
                        accent = palette.primary,
                    ) {
                        if (confirmClear) {
                            confirmClear = false
                            if (onClearAll != null) onClearAll() else scope.launch { downloads.clear() }
                        } else {
                            confirmClear = true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = OtoType.label,
        color = Color.White.copy(alpha = 0.45f),
        modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(22.dp), tint = Color.White.copy(alpha = 0.85f))
    }
}

@Composable
private fun ListRow(
    name: String,
    status: String,
    done: Int,
    total: Int,
    artwork: String?,
    accent: Color,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    var bmp by remember(artwork) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artwork) {
        if (!artwork.isNullOrBlank()) bmp = ArtworkLoader.load(artwork).first
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(bmp!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(MobileIcons.libraryMusic, null, Modifier.size(20.dp), tint = accent)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = OtoType.rowTitle, color = Color(0xFFFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = OtoType.rowSub, color = Color.White.copy(alpha = 0.58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (total > 0 && done < total) {
            Icon(MobileIcons.download, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.5f))
        } else if (total > 0) {
            Icon(MobileIcons.downloadDone, null, Modifier.size(18.dp), tint = accent)
        }
        RemoveButton(onRemove)
    }
}

@Composable
private fun SongDownloadRow(
    heard: Heard,
    entry: DesktopDownloads.Entry?,
    isCurrent: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    var bmp by remember(heard.artwork) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(heard.artwork) {
        if (!heard.artwork.isNullOrBlank()) bmp = ArtworkLoader.load(heard.artwork).first
    }
    val status = when (entry?.state) {
        DesktopDownloads.State.Done -> "Descargada${entry.quality?.let { " · ${it.substringBefore('|')}" }.orEmpty()}"
        DesktopDownloads.State.Downloading -> "Bajando · ${(entry.progress * 100).toInt()} %"
        DesktopDownloads.State.Queued -> "En cola"
        DesktopDownloads.State.Failed -> "Error · se reintentará"
        null -> "Pendiente"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrent) accent.copy(alpha = 0.13f) else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(bmp!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(MobileIcons.play, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.45f))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                heard.title,
                style = OtoType.rowTitle,
                color = if (isCurrent) accent else Color(0xFFFFFFFF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${heard.artist} · $status", style = OtoType.rowSub, color = Color.White.copy(alpha = 0.58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RemoveButton(onRemove)
    }
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MobileIcons.close, "Quitar la descarga", Modifier.size(17.dp), tint = Color.White.copy(alpha = 0.5f))
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb < 1000.0) String.format("%.0f MB", mb) else String.format("%.1f GB", mb / 1024.0)
}
