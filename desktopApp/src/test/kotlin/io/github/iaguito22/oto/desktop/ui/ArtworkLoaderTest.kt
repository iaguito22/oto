package io.github.iaguito22.oto.desktop.ui

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class ArtworkLoaderTest {
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
