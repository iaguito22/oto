package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.LocalPlaylist

/** El id con el que una carpeta suena como una lista (el mismo que en el móvil). */
internal const val FOLDER_PREFIX = "folder:"

/**
 * Lo que va en Tus listas, igual que en el móvil: una lista suelta, una carpeta ([folder]) con las suyas, o el DJ o
 * Me gusta ([special]). La clave de una carpeta es «d:» + nombre, la misma con la que se fija.
 */
internal data class Shelved(val folder: String?, val lists: List<LocalPlaylist>, val special: String? = null) {
    val key get() = special ?: folder?.let { "d:$it" } ?: lists.first().id

    /** Las carpetas vacías no se mueven: no tienen sitio entre las listas, van delante. */
    val movable get() = special != null || lists.isNotEmpty()

    val name get() = folder ?: lists.firstOrNull()?.name.orEmpty()
}

/**
 * Tus listas en el orden compartido con el móvil: las carpetas aún vacías delante y después cada lista suelta, o
 * cada carpeta en el sitio de la primera de sus listas.
 */
internal fun shelfOf(playlists: List<LocalPlaylist>, folders: Set<String>): List<Shelved> {
    val used = playlists.mapNotNullTo(HashSet()) { it.folder }
    return folders.filter { it !in used }.sorted().map { Shelved(it, emptyList()) } +
        playlists.groupBy { it.folder ?: "\u0000" + it.id }.values.map { g -> Shelved(g.first().folder, g) }
}

/**
 * Tras recolocar [row] (una parte de [shelf], en su nuevo orden), el orden de todas las listas: las movidas vuelven
 * a los mismos huecos entre todas y cada carpeta lleva sus listas consigo. Es lo que hace el móvil.
 */
internal fun reorderedIds(shelf: List<Shelved>, row: List<Shelved>): List<String> {
    val moved = row.filter { it.special == null && it.lists.isNotEmpty() }.iterator()
    val keys = row.mapTo(HashSet()) { it.key }
    return shelf.filter { it.lists.isNotEmpty() }
        .map { if (it.key in keys && moved.hasNext()) moved.next() else it }
        .flatMap { e -> e.lists.map { it.id } }
}

/** Dónde quedan el DJ y Me gusta en la fila de arriba tras recolocarla. */
internal fun spotsOf(row: List<Shelved>): Map<String, Int> =
    row.withIndex().filter { it.value.special != null }.associate { (i, e) -> e.special!! to i }

/** Cuatro listas en cuadrícula (cada una, su primera carátula) y la carpeta en una esquina, como en el móvil. */
@Composable
internal fun FolderArt(lists: List<LocalPlaylist>, modifier: Modifier = Modifier) {
    val arts = lists.mapNotNull { l -> l.songs.firstOrNull { it.artwork != null }?.artwork }.take(4)
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .045f)), contentAlignment = Alignment.Center) {
        if (arts.isEmpty()) {
            Icon(MobileIcons.folder, null, Modifier.fillMaxSize(.3f), tint = Color.White.copy(alpha = .3f))
            return@Box
        }
        if (arts.size == 1) LibraryArt(arts.first(), Modifier.fillMaxSize())
        else Column(Modifier.fillMaxSize()) {
            for (row in 0..1) Row(Modifier.weight(1f)) {
                for (col in 0..1) LibraryArt(arts.getOrNull(row * 2 + col), Modifier.weight(1f).fillMaxHeight(), rounded = false)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = minOf(maxWidth, maxHeight)
            Box(
                Modifier.align(Alignment.BottomStart).padding(side * .06f).size(side * .24f).clip(CircleShape).background(Color(0xFF0B0B0B).copy(alpha = .78f)),
                contentAlignment = Alignment.Center,
            ) { Icon(MobileIcons.folder, null, Modifier.fillMaxSize(.55f), tint = Color.White) }
        }
    }
}
