package io.github.iaguito22.oto.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class ArtworkLoaderTest {
    @Test fun monochromeCoverUsesWhiteEvenWithSmallColoredDetails() {
        for (redColumns in listOf(0, 3)) {
            val palette = coverPalette(redColumns)
            assertEquals(Color.White, palette.primary)
            assertEquals(Color.White, palette.secondary)
            assertEquals(Color.White, palette.tertiary)
        }
    }

    @Test fun coverWithSubstantialColorKeepsItsHue() {
        val accent = coverPalette(redColumns = 40).primary
        assertTrue(accent.red > accent.green + 0.25f)
        assertEquals(accent.green, accent.blue, 0.001f)
    }

    private fun coverPalette(redColumns: Int): ArtworkPalette {
        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val gray = y * 255 / (image.height - 1)
                image.setRGB(x, y, if (x < redColumns) 0xD12C2C else gray * 0x010101)
            }
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return Image.makeFromEncoded(png).use { ArtworkLoader.extractPalette(it.toComposeImageBitmap()) }
    }

    @Test fun concurrentViewsShareOneDownloadAndFailedLoadsCanRetry():Unit=runBlocking {
        val requests=AtomicInteger()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLttAAAAABJRU5ErkJggg==")
        server.createContext("/cover") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200,png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        server.createContext("/failed") { exchange ->
            requests.incrementAndGet();exchange.sendResponseHeaders(503,-1);exchange.close()
        }
        server.start()
        try {
            val url="http://127.0.0.1:${server.address.port}/cover=w544-h544"
            val images=(1..12).map { async(Dispatchers.Default) { ArtworkLoader.load(url,64).first } }.awaitAll()
            assertNotNull(images.first());assertEquals(1,requests.get())
            images.forEach { assertSame(images.first(),it) }
            assertNotNull(ArtworkLoader.load(url).first)
            assertEquals(2,requests.get())
            val failed="http://127.0.0.1:${server.address.port}/failed"
            repeat(2) { assertNull(ArtworkLoader.load(failed,64).first) }
            assertEquals(4,requests.get())
        } finally { server.stop(0) }
    }
}
