package io.github.iaguito22.oto.playback

import com.metrolist.innertube.models.YTItem

enum class CollectionPlayback { Start, Resume, Pause }

fun collectionPlayback(source: Source?, trackId: String?, playing: Boolean, localId: String? = null, item: YTItem? = null): CollectionPlayback {
    if (trackId == null || source == null) return CollectionPlayback.Start
    val from = source.item
    val sameCollection = when {
        localId != null -> source.local == localId
        item != null && from != null -> item::class == from::class && item.id == from.id
        else -> false
    }
    return when {
        !sameCollection -> CollectionPlayback.Start
        playing -> CollectionPlayback.Pause
        else -> CollectionPlayback.Resume
    }
}

/** Devuelve true si se ha actuado sobre la cola actual de la colección. */
fun OtoPlayer.toggleCollection(localId: String? = null, item: YTItem? = null): Boolean {
    val now = state.value
    if (collectionPlayback(source.value, now.id, now.playing, localId, item) == CollectionPlayback.Start) return false
    toggle()
    return true
}
