package io.github.iaguito22.oto.importer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * No es una prueba: empareja de verdad contra YouTube Music una muestra de canciones (título TAB artistas|… TAB ms)
 * y escribe qué eligió, para revisarlo a ojo. Solo corre con OTO_HARNESS=<muestra.tsv>.
 */
class MatchHarness {
    @Test
    fun run() = runBlocking(Dispatchers.IO) {
        val input = System.getenv("OTO_HARNESS")
        assumeTrue(input != null)
        val tracks = File(input!!).readLines().filter { it.isNotBlank() }.map { l ->
            val (t, a, d) = l.split('\t')
            SourceTrack(t, a.split('|').filter { it.isNotEmpty() }, null, d.toLongOrNull())
        }
        val gate = Semaphore(6)
        val start = System.currentTimeMillis()
        val out = tracks.map { t ->
            async {
                gate.withPermit {
                    val r = Matcher.find(t)
                    val m = r.getOrNull()
                    val line = when {
                        r.isFailure -> "ERR\t${t.title}\t${t.artists.joinToString()}\t${r.exceptionOrNull()}"
                        m == null -> "NO\t${t.title}\t${t.artists.joinToString()}\t${t.durationMs?.div(1000)}"
                        else -> "%.2f\t%s\t%s\t%s\t→\t%s\t%s\t%s\t%s".format(
                            m.score, t.title, t.artists.joinToString(), t.durationMs?.div(1000),
                            m.song.title, m.song.artists.joinToString { it.name }, m.song.duration, m.song.musicVideoType,
                        )
                    }
                    line
                }
            }
        }.awaitAll()
        File(File(input).parentFile, "harness-out.tsv").writeText(out.joinToString("\n"))
        println("${tracks.size} en ${(System.currentTimeMillis() - start) / 1000} s")
    }
}
