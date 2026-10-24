package io.github.iaguito22.oto.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assume.assumeTrue
import org.junit.Test
import timber.log.Timber
import java.io.File

/**
 * No es una prueba: pide la letra de una muestra de canciones (título TAB artistas TAB segundos, como las da
 * YouTube Music) y apunta qué salió de cada una. Solo corre con OTO_LYRICS=<muestra.tsv>; OTO_LYRICS_N limita cuántas.
 */
class LyricsHarness {
    @Test
    fun run() = runBlocking(Dispatchers.IO) {
        val input = System.getenv("OTO_LYRICS")
        assumeTrue(input != null)
        val n = System.getenv("OTO_LYRICS_N")?.toIntOrNull() ?: Int.MAX_VALUE
        val qs = File(input!!).readLines().filter { it.isNotBlank() }.take(n).mapIndexed { i, l ->
            val (t, a, d) = l.split('\t')
            LyricsQuery("x$i", t, a, null, (d.toLongOrNull() ?: 0) * 1000)
        }
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                System.err.println("LOG $message ${t?.let { it::class.simpleName + ": " + it.message }}")
            }
        })
        val gate = Semaphore(1) // de una en una, como en el móvil: lyricsplus corta con 429 si se le pide en paralelo
        val out = qs.map { q ->
            async {
                gate.withPermit {
                    delay(System.getenv("OTO_LYRICS_PACE")?.toLongOrNull() ?: 0L)
                    val f = LyricsSource.fetch(q)
                    val r = f.lyrics
                    val kind = when (r) {
                        is Lyrics.Synced -> if (r.wordByWord) "WORD" else "LINE"
                        is Lyrics.Plain -> "PLAIN"
                        Lyrics.Missing -> "NONE"
                        null -> "OFFLINE"
                    }
                    val src = (r as? Lyrics.Synced)?.source ?: (r as? Lyrics.Plain)?.source ?: ""
                    "$kind${if (f.final) "" else "?"}\t$src\t${q.title}\t${q.artist}"
                }
            }
        }.awaitAll()
        File(File(input).parentFile, "lyrics-out.tsv").writeText(out.joinToString("\n"))
    }
}
