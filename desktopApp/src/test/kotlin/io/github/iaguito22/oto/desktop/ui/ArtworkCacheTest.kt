package io.github.iaguito22.oto.desktop.ui

import org.junit.Assert.*
import org.junit.Test

class ArtworkCacheTest {
    @Test fun evictsByMemoryAndRecencyAndShrinksImmediately() {
        val cache=ArtworkCache<String>(10) { it.length.toLong() }
        cache.put("a","aaaa");cache.put("b","bbbb")
        assertEquals("aaaa",cache.get("a"))
        cache.put("c","cccc")
        assertNull(cache.get("b"));assertNotNull(cache.get("a"))
        cache.resize(4)
        assertNull(cache.get("c"));assertEquals("aaaa",cache.get("a"))
        cache.put("huge","0123456789")
        assertNull(cache.get("huge"));assertNotNull(cache.get("a"))
        cache.put("a","a");cache.put("d","ddd")
        assertEquals("a",cache.get("a"));assertEquals("ddd",cache.get("d"))
    }
    @Test fun thumbnailsRespectPhysicalPixelsAndPreserveUrlOptions() {
        assertEquals(64,artworkSize(55));assertEquals(128,artworkSize(114))
        assertEquals(256,artworkSize(228));assertEquals(544,artworkSize(480))
        assertEquals("https://example.test/cover=w128-h128-l90-rj",artworkUrl("https://example.test/cover=w544-h544-l90-rj",128))
        assertEquals("https://example.test/cover.jpg",artworkUrl("https://example.test/cover.jpg",128))
    }
}
