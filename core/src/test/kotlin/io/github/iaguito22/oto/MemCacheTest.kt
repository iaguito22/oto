package io.github.iaguito22.oto

import org.junit.Assert.*
import org.junit.Test

class MemCacheTest {
    @Test fun shrinkingImmediatelyReleasesLeastRecentlyUsedEntries() {
        val cache = MemCache<String, String>(3)
        cache.put("a", "A"); cache.put("b", "B"); cache.put("c", "C")
        assertEquals("A", cache.get("a"))
        cache.resize(1)
        assertNull(cache.get("b")); assertNull(cache.get("c"))
        assertEquals("A", cache.get("a"))
        cache.put("d", "D")
        assertNull(cache.get("a")); assertEquals("D", cache.get("d"))
        cache.resize(3)
        cache.put("e", "E"); cache.put("f", "F")
        assertEquals("D", cache.get("d"))
    }
}
