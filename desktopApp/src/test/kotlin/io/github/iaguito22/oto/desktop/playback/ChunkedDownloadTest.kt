package io.github.iaguito22.oto.desktop.playback

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ChunkedDownloadTest {
    @Test fun serverIgnoringRangeRestartsPartialAndFinishesLargeUnknownLength(): Unit = runBlocking {
        val bytes = ByteArray(DOWNLOAD_CHUNK_BYTES.toInt() + 50) { (it % 251).toByte() }
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio") { ex ->
            calls.incrementAndGet()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        val root = Files.createTempDirectory("oto-download ñ ").toFile()
        server.start()
        try {
            val file = root.resolve("audio.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            assertEquals(bytes.size.toLong(), downloadChunked("http://127.0.0.1:${server.address.port}/audio", emptyMap(), null, file))
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(1, calls.get())
        } finally { server.stop(0); root.deleteRecursively() }
    }

    @Test fun shortRangeCannotBeMarkedComplete(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio") { ex ->
            ex.responseHeaders.add("Content-Range", "bytes 0-99/100")
            ex.sendResponseHeaders(206, 40)
            ex.responseBody.use { it.write(ByteArray(40)) }
        }
        val root = Files.createTempDirectory("oto-short").toFile()
        server.start()
        try {
            val result = runCatching { downloadChunked("http://127.0.0.1:${server.address.port}/audio", emptyMap(), 100, root.resolve("audio.part")) }
            assertTrue("No debe aceptar 40 bytes de los 100 anunciados", result.exceptionOrNull() is EOFException)
        } finally { server.stop(0); root.deleteRecursively() }
    }
}
