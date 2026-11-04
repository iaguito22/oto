package io.github.iaguito22.oto.desktop.ui

/** LRU limitada por memoria estimada y por entradas; las vistas mantienen sus propias referencias. */
internal class ArtworkCache<V>(private var maxBytes:Long,private val sizeOf:(V)->Long) {
    private val entries=LinkedHashMap<String,V>(16,.75f,true)
    private var bytes=0L

    @Synchronized fun get(key:String):V?=entries[key]
    @Synchronized fun put(key:String,value:V) {
        entries.remove(key)?.let { bytes-=sizeOf(it) }
        val weight=sizeOf(value)
        if(weight<=maxBytes) { entries[key]=value;bytes+=weight }
        trim()
    }
    private var maxEntries=96
    @Synchronized fun resize(limit:Long,entryLimit:Int=96) { require(limit>0 && entryLimit>0);maxBytes=limit;maxEntries=entryLimit;trim() }
    @Synchronized fun clear() { entries.clear();bytes=0L }
    private fun trim() {
        val iterator=entries.entries.iterator()
        while((bytes>maxBytes || entries.size>maxEntries) && iterator.hasNext()) {
            bytes-=sizeOf(iterator.next().value);iterator.remove()
        }
    }
}

internal fun artworkSize(pixels:Int):Int = when {
    pixels<=64 -> 64
    pixels<=128 -> 128
    pixels<=256 -> 256
    else -> 544
}

internal fun artworkUrl(url:String,size:Int):String =
    url.replace(Regex("=w\\d+-h\\d+"),"=w$size-h$size")
