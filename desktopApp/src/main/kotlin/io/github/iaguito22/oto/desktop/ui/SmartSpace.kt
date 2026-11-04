package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
import io.github.iaguito22.oto.ui.menu.Recent
import kotlinx.coroutines.launch

/** Una lista hecha con lo tuyo, como en el móvil: se pone o se guarda como lista tuya, pero no se edita. */
@Composable
internal fun SmartSpace(list: LocalPlaylist, currentId: String?, player: DesktopPlayerConnection, prefs: Prefs, palette: ArtworkPalette, onSave: (Heard) -> Unit, onArtist: (String, String) -> Unit, onBack: () -> Unit) {
    val shuffle = rememberListShuffle(player, list.id)
    val songActions = rememberSongActions(player, prefs, onSave, onArtist)
    var saving by remember(list.id) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                HoverAction(MobileIcons.arrowBack, "Volver", onClick = onBack)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PlaylistMosaic(list, palette.primary, Modifier.size(140.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(list.name, style = OtoType.display, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("${list.songs.size} canciones · hecha con lo tuyo", style = OtoType.rowSub, color = Color.White.copy(alpha = .5f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { player.playLocal(list, null, false) }, colors = ButtonDefaults.buttonColors(containerColor = palette.primary, contentColor = Color(0xFF101010))) { Text("Reproducir", fontWeight = FontWeight.SemiBold) }
                            HoverAction(MobileIcons.shuffle, if (shuffle) "Desactivar aleatorio" else "Activar aleatorio", onClick = { player.setShuffle(!shuffle, list.id) }, tint = if (shuffle) palette.primary else Color.White.copy(alpha = .6f))
                            HoverAction(MobileIcons.add, "Guardar como lista", onClick = { saving = true })
                        }
                    }
                }
            }
        }
        itemsIndexed(list.songs, key = { i, h -> "${h.id}:$i" }) { i, h ->
            val actions = songActions(h, null)
            PlaylistContextMenu(actions) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { player.playLocal(list, i, false) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LibraryArt(h.artwork, Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title, style = OtoType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.artist, style = OtoType.rowSub, color = Color.White.copy(alpha = .45f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (h.id == currentId) Icon(MobileIcons.graphicEq, "Sonando", Modifier.size(17.dp), tint = palette.primary)
                    SongMenuButton(h.title, actions)
                }
            }
        }
    }
    if (saving) SaveSongsDialog(list.name, list.songs, list.songs.firstOrNull()?.artwork, prefs, palette) { saving = false }
}

/** Guardar varias canciones de golpe: en Me gusta, en una lista tuya o en una nueva con [name]. */
@Composable
internal fun SaveSongsDialog(name: String, tracks: List<Heard>, artwork: String?, prefs: Prefs, palette: ArtworkPalette, onDismiss: () -> Unit) {
    val lists by prefs.playlists.collectAsState(initial = emptyList())
    val liked by prefs.likedIds.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(390.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF242826)).border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(24.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LibraryArt(artwork, Modifier.size(62.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Guardar canciones", style = OtoType.section)
                    Text(name, style = OtoType.rowSub, color = Color.White.copy(alpha = .55f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${tracks.size} canciones", style = OtoType.rowSub, color = palette.primary)
                }
            }
            LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (lists.none { it.name.equals(name, true) }) item(key = "new") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { scope.launch { prefs.createPlaylist(name, tracks) }; onDismiss() }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(palette.primary.copy(alpha = .18f)), contentAlignment = Alignment.Center) { Icon(MobileIcons.add, null, Modifier.size(19.dp), tint = palette.primary) }
                        Text("Nueva lista «$name»", Modifier.weight(1f), style = OtoType.body, color = palette.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item(key = "liked") {
                    val saved = tracks.all { it.id in liked }
                    SaveDestination("Me gusta", null, saved, palette, true) { if (!saved) scope.launch { tracks.forEach { prefs.setLiked(it, true) } } }
                }
                items(lists, key = { it.id }) { list ->
                    val saved = tracks.all { h -> list.songs.any { it.id == h.id } }
                    SaveDestination(list.name, list.songs.firstOrNull()?.artwork, saved, palette, false) { if (!saved) scope.launch { prefs.addAllToPlaylist(list.id, tracks) } }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Button(onClick = onDismiss) { Text("Listo") } }
        }
    }
}

/** «Hecho con lo tuyo»: las listas que se hacen solas, arriba de Explorar (como en el móvil). */
@Composable
internal fun SmartRow(smart: List<LocalPlaylist>, palette: ArtworkPalette, onOpen: (LocalPlaylist) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Hecho con lo tuyo", style = OtoType.section)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            items(smart, key = { it.id }) { l ->
                Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(l) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlaylistMosaic(l, palette.primary, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(l.name, style = OtoType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${l.songs.size} canciones", style = OtoType.rowSub, color = Color.White.copy(alpha = .38f), maxLines = 1)
                }
            }
        }
    }
}

/** Lo buscado hace poco, con el buscador vacío: búsquedas y lo que abriste desde ellas, cada cosa con su ×. */
@Composable
internal fun RecentSearches(list: List<Recent>?, onPick: (Recent) -> Unit, onForget: (Recent) -> Unit) {
    when {
        list == null -> Unit
        list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(MobileIcons.search, null, Modifier.size(40.dp), tint = Color.White.copy(alpha = .25f))
            Text("Busca lo que quieras", style = OtoType.section)
            Text("Canciones, artistas, álbumes o listas. Lo que busques se queda aquí.", style = OtoType.rowSub, color = Color.White.copy(alpha = .5f))
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "title") { Text("RECIENTES", style = OtoType.label, color = Color.White.copy(alpha = .5f), modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) }
            items(list, key = { it.key }) { r ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(r) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (r.kind == Recent.Kind.Query) Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
                        Icon(MobileIcons.history, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = .5f))
                    } else Box(Modifier.size(44.dp).clip(if (r.kind == Recent.Kind.Artist) CircleShape else RoundedCornerShape(10.dp))) { LibraryArt(r.art, Modifier.fillMaxSize()) }
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = OtoType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = when (r.kind) {
                            Recent.Kind.Query -> null
                            Recent.Kind.Artist -> "Artista"
                            Recent.Kind.Album -> listOfNotNull("Álbum", r.sub).joinToString(" · ")
                            Recent.Kind.Playlist -> listOfNotNull("Lista", r.sub).joinToString(" · ")
                        }
                        if (sub != null) Text(sub, style = OtoType.rowSub, color = Color.White.copy(alpha = .45f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HoverAction(MobileIcons.close, "Quitar", onClick = { onForget(r) })
                }
            }
        }
    }
}
