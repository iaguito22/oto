package io.github.iaguito22.oto.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.iaguito22.oto.playback.QueueEntry

internal data class QueueRowItem(val key: String, val entry: QueueEntry)

/** Mantiene el orden visual del gesto y traduce la soltura a índices de la cola completa. */
internal class QueueReorder(private val source: List<QueueRowItem>) {
    var rows by mutableStateOf(source)
        private set
    var draggingKey by mutableStateOf<String?>(null)
        private set

    fun start(key: String) {
        if (draggingKey == null && rows == source && source.any { it.key == key }) draggingKey = key
    }

    fun move(from: Any, to: Any) {
        if (from != draggingKey) return
        val fromPosition = rows.indexOfFirst { it.key == from }
        val toPosition = rows.indexOfFirst { it.key == to }
        if (fromPosition < 0 || toPosition < 0 || fromPosition == toPosition) return
        rows = rows.toMutableList().apply { add(toPosition, removeAt(fromPosition)) }
    }

    fun release(key: String): Pair<Int, Int>? {
        if (draggingKey != key) return null
        draggingKey = null
        val origin = source.indexOfFirst { it.key == key }
        val destination = rows.indexOfFirst { it.key == key }
        val affected = minOf(origin, destination)..maxOf(origin, destination)
        // Mover dos entradas idénticas puede dejar el StateFlow igual y no reemplazar el borrador.
        if (affected.all { rows[it].entry.copy(index = source[it].entry.index) == source[it].entry }) {
            rows = source
        }
        return if (origin == destination) null else source[origin].entry.index to source[destination].entry.index
    }

    fun cancel(key: String) {
        if (draggingKey != key) return
        draggingKey = null
        rows = source
    }
}
