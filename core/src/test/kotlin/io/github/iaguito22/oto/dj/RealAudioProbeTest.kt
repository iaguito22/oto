package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.playback.InnerTubeXPlayer
import io.github.iaguito22.oto.playback.Tier
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Banco de pruebas con canciones reales (no corre sin variables de entorno).
 *
 * - `OTO_AUDIO_FETCH=id1,id2` + `OTO_AUDIO_DIR=carpeta`: baja el audio de esas canciones (`id.audio`).
 * - `OTO_AUDIO_DIR=carpeta`: analiza cada `id.pcm` (s16le mono 22050 Hz, de ffmpeg) y escribe un resumen.
 */
class RealAudioProbeTest {
    private val dir = System.getenv("OTO_AUDIO_DIR")?.let(::File)

    @Test
    fun fetch() {
        val ids = System.getenv("OTO_AUDIO_FETCH")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        assumeTrue(dir != null && !ids.isNullOrEmpty())
        dir!!.mkdirs()
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        for (id in ids!!) {
            val out = File(dir, "$id.audio")
            if (out.exists() && out.length() > 0) continue
            val stream = runBlocking { InnerTubeXPlayer.resolve(id, Tier.High) }.getOrElse {
                println("$id: sin stream (${it.message})")
                continue
            }
            val tmp = File(dir, "$id.part")
            tmp.outputStream().use { o ->
                var pos = 0L
                val chunk = 1L shl 20
                while (true) {
                    val req = HttpRequest.newBuilder(URI(stream.url)).apply {
                        stream.headers.forEach { (k, v) -> if (k.lowercase() !in setOf("host", "connection", "content-length")) header(k, v) }
                        header("Range", "bytes=$pos-${pos + chunk - 1}")
                    }.GET().build()
                    val res = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
                    if (res.statusCode() !in 200..299) { println("$id: HTTP ${res.statusCode()} en $pos"); break }
                    val body = res.body()
                    o.write(body)
                    pos += body.size
                    if (body.size < chunk || res.statusCode() == 200) break
                }
            }
            tmp.renameTo(out)
            println("$id: ${out.length()} bytes (${stream.client})")
        }
    }

    @Test
    fun analyze() {
        assumeTrue(dir != null && System.getenv("OTO_AUDIO_FETCH") == null)
        val report = StringBuilder()
        dir!!.listFiles { f -> f.name.endsWith(".pcm") }!!.sortedBy { it.name }.forEach { f ->
            val bytes = f.readBytes()
            val notes = StringBuilder()
            AudioAnalyzer.PROBE = { notes.append(' ').append(it) }
            val t0 = System.nanoTime()
            val m = AudioAnalyzer.analyzePcm16Bytes(f.nameWithoutExtension, bytes, 22_050, 1)
            val ms = (System.nanoTime() - t0) / 1_000_000
            // Lo mismo con solo el primer minuto (lo que había antes de bajarla entera).
            AudioAnalyzer.PROBE = null
            val head = AudioAnalyzer.analyzePcm16Bytes(f.nameWithoutExtension, bytes.copyOf(minOf(bytes.size, 22_050 * 2 * 60)), 22_050, 1)
            report.append(
                "%-12s %5.0f s  BPM %-7s fiab %.2f  rejilla %3d  intro %5d  drop %6d  outro %6d  (%d ms) | 60 s: %-7s %.2f |%s\n".format(
                    f.nameWithoutExtension, m.durationMs / 1000.0, m.bpm?.let { "%.2f".format(it) } ?: "?",
                    m.bpmConfidence, m.beatGrid.size, m.introEndMs, m.dropMs, m.outroStartMs, ms,
                    head.bpm?.let { "%.2f".format(it) } ?: "?", head.bpmConfidence, notes,
                ),
            )
        }
        File(dir, "report.txt").writeText(report.toString())
        print(report)
    }
}
