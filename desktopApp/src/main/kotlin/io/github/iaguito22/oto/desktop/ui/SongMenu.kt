package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
import io.github.iaguito22.oto.toSong
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Las opciones de una canción, las mismas en todas partes (como en el móvil): reproducir a continuación, guardar,
 * descargar o quitar la descarga, ir al artista, copiar el enlace y, dentro de una lista tuya, quitarla de ella.
 * [here]: el artista cuya página se ve, para no ofrecer ir a él.
 */
@Composable
internal fun rememberSongActions(
    player: DesktopPlayerConnection,
    prefs: Prefs,
    onSave: (Heard) -> Unit,
    onArtist: ((String, String) -> Unit)? = null,
    here: String? = null,
): (Heard, String?) -> List<ContextMenuItem> {
    val scope = rememberCoroutineScope()
    val requested by prefs.downloadSongs.collectAsState(initial = emptyList())
    val requestedIds = remember(requested) { requested.mapTo(HashSet()) { it.id } }
    val save by rememberUpdatedState(onSave)
    val artist by rememberUpdatedState(onArtist)
    return remember(requestedIds, here) {
        { h: Heard, listId: String? ->
            buildList {
                add(ContextMenuItem("Reproducir a continuación") { player.playNext(h.toSong()) })
                add(ContextMenuItem("Guardar en…") { save(h) })
                val downloaded = h.id in requestedIds
                add(ContextMenuItem(if (downloaded) "Quitar la descarga" else "Descargar") { scope.launch { prefs.setDownloadSong(h, !downloaded) } })
                val go = artist
                val id = h.artistId
                if (go != null && id != null && id != here) add(ContextMenuItem("Ir a ${h.artist}") { go(id, h.artist) })
                add(ContextMenuItem("Copiar enlace") {
                    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("https://music.youtube.com/watch?v=${h.id}"), null) }
                })
                if (listId != null) add(ContextMenuItem(if (listId == LIKED_ID) "Quitar de Me gusta" else "Quitar de esta lista") {
                    scope.launch { if (listId == LIKED_ID) prefs.setLiked(h, false) else prefs.removeFromPlaylist(listId, h.id) }
                })
            }
        }
    }
}

/** El botón ··· de una canción con sus opciones. */
@Composable
internal fun SongMenuButton(title: String, actions: List<ContextMenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        HoverAction(MobileIcons.moreHoriz, "Opciones de $title", onClick = { open = true })
        LibraryDropdownMenu(open, { open = false }, shape = RoundedCornerShape(18.dp), modifier = Modifier.width(240.dp)) {
            actions.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, style = OtoType.rowSub, color = if (item.label.startsWith("Quitar de")) Danger else Color.White) },
                    onClick = { open = false; item.onClick() },
                )
            }
        }
    }
}
