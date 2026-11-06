package io.github.iaguito22.oto.desktop

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.junit.Assert.*
import org.junit.Test

class WelcomeMediaTest {
    @Test fun packagedDemosDecodeEveryFrameAndRestartWithoutKeepingOldPixels() {
        val assets = welcomeSteps.map { it.asset }
        for (asset in assets) {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/welcome/$asset.webp")) { asset }.use { it.readBytes() }
            assertTrue("Cada demostración por debajo de 8 MB: $asset", bytes.size < 8_000_000)
            Data.makeFromBytes(bytes).use { data ->
                Codec.makeFromData(data).use { codec ->
                    assertEquals(1280, codec.width)
                    assertTrue(asset, codec.height in 670..730)
                    assertTrue(asset, codec.frameCount > 15)
                    assertTrue(asset, codec.framesInfo.sumOf { it.duration } in 3_000..30_000)
                    Bitmap().use { bitmap ->
                        assertTrue(bitmap.allocN32Pixels(codec.width, codec.height))
                        var first: ByteArray? = null
                        repeat(2) { loop ->
                            repeat(codec.frameCount) { index ->
                                codec.readPixels(bitmap, index, if (index == 0) -1 else index - 1)
                                if (index == 0) {
                                    val pixels = bitmap.readPixels(bitmap.imageInfo, bitmap.rowBytes, 0, 0)
                                    assertNotNull(asset, pixels)
                                    if (loop == 0) first = pixels else assertArrayEquals(asset, first, pixels)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
