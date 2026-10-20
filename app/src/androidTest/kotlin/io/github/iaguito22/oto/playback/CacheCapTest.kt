package io.github.iaguito22.oto.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * El audio nunca pasa de su tope: al entrar una canción nueva se van las menos usadas. La misma caché que en la app
 * (SimpleCache + LRU + base de datos), con un tope de 1 MB y canciones de 400 KB.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class CacheCapTest {
    @Test
    fun audioNeverExceedsItsCap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "cap-test").apply { deleteRecursively() }
        val cap = 1L shl 20
        val db = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(cap), db)
        val song = ByteArray(400 * 1024) { it.toByte() }
        fun onDisk() = dir.walk().filter { it.isFile && it.name.endsWith(".exo") }.sumOf { it.length() }
        for (i in 1..6) {
            // Como CacheDataSource al bajarla: primero el cerrojo de ese tramo, luego se escribe.
            val hole = cache.startReadWrite("song$i", 0, song.size.toLong())
            val sink = CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            sink.open(DataSpec.Builder().setUri(Uri.parse("oto://$i")).setKey("song$i").setLength(song.size.toLong()).build())
            sink.write(song, 0, song.size)
            sink.close()
            cache.releaseHoleSpan(hole)
            assertTrue("tras la canción $i: ${cache.cacheSpace} bytes", cache.cacheSpace <= cap)
            assertTrue("tras la canción $i, en disco: ${onDisk()} bytes", onDisk() <= cap)
        }
        // Quedan las dos últimas: las primeras se fueron.
        assertEquals(setOf("song5", "song6"), cache.keys)
        cache.release()
        SimpleCache.delete(dir, db)
    }
}
