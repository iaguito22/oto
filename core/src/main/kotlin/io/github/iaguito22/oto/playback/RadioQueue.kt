package io.github.iaguito22.oto.playback

import com.metrolist.innertube.models.SongItem

data class RadioTrackKey(val id: String, val song: String)

/** Ventana de reproducción de una radio explícita. Conserva las páginas pendientes y recuerda todo lo que sonó. */
class RadioQueue<T>(
    seed: String,
    private val keyOf: (T) -> RadioTrackKey,
    private val fromSong: (SongItem) -> T,
    private val open: (String) -> Autoplay.Source = { Radio(it).asSource() },
) {
    private var source = open(seed)
    private var triedSeeds = hashSetOf(seed)
    private val heardIds = HashSet<String>()
    private val heardSongs = HashSet<String>()
    private val pending = ArrayDeque<T>()
    private val deferred = ArrayDeque<T>()

    data class Trim(val from: Int, val until: Int)

    fun withDeferred(queue: List<T>): List<T> = if (deferred.isEmpty()) queue else queue + deferred

    /** Al editar manualmente, la cola recibida incluye también lo que estaba fuera de la ventana. */
    fun trimEditedQueue(queue: List<T>, current: Int): Trim {
        deferred.clear()
        return trim(queue, current)
    }

    fun trim(queue: List<T>, current: Int): Trim {
        if (current !in queue.indices) return Trim(0, queue.size)
        val from = (current - HISTORY).coerceAtLeast(0)
        val until = minOf(queue.size, current + 1 + MAX_AHEAD)
        fun remember(item: T) {
            val key = keyOf(item)
            heardIds += key.id
            heardSongs += key.song
        }
        remember(queue[current])
        for (i in 0 until from) remember(queue[i])
        for (i in queue.lastIndex downTo until) deferred.addFirst(queue[i])
        return Trim(from, until)
    }

    fun needsRefill(size: Int, current: Int): Boolean {
        if (current !in 0 until size) return false
        val ahead = size - current - 1
        return ahead <= REFILL_AT || (ahead < MAX_AHEAD && deferred.isNotEmpty())
    }

    /** Primero lo que salió de la ventana al retroceder, luego lo que queda de la página. */
    fun take(queue: List<T>, current: Int, recent: Set<String> = emptySet()): List<T> {
        val room = MAX_AHEAD - (queue.size - current - 1)
        if (room <= 0) return emptyList()
        val queuedIds = HashSet<String>()
        val songs = HashSet<String>()
        for (item in queue) {
            val key = keyOf(item)
            queuedIds += key.id
            songs += key.song
        }
        return buildList {
            while (size < room && deferred.isNotEmpty()) {
                val item = deferred.removeFirst()
                val key = keyOf(item)
                if (queuedIds.add(key.id)) {
                    songs += key.song
                    add(item)
                }
            }
            while (size < room && pending.isNotEmpty()) {
                val item = pending.removeFirst()
                val key = keyOf(item)
                if (key.id !in recent && key.song !in recent && key.id !in heardIds && key.song !in heardSongs &&
                    key.id !in queuedIds && key.song !in songs
                ) {
                    queuedIds += key.id
                    songs += key.song
                    add(item)
                }
            }
        }
    }

    fun offer(page: List<SongItem>) {
        page.mapTo(pending, fromSong)
    }

    /** Una petición a la vez. Usa la continuación y, al agotarla, prueba desde la última canción disponible. */
    suspend fun fetch(seeds: List<String>): Result<List<SongItem>> {
        if (source.exhausted && seeds.isNotEmpty()) {
            val seed = seeds.firstOrNull { it !in triedSeeds } ?: seeds.first().also { triedSeeds.clear() }
            triedSeeds += seed
            source = open(seed)
        }
        return source.more()
    }

    private companion object {
        const val MAX_AHEAD = 50
        const val HISTORY = 15
        const val REFILL_AT = 8
    }
}
