package io.github.iaguito22.oto.desktop.playback

import io.github.iaguito22.oto.dj.DjPickInfo
import io.github.iaguito22.oto.dj.Kind
import io.github.iaguito22.oto.dj.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class DjTailAfterTest {
    private fun pick(id: String) = DjPickInfo(Track(id, id, listOf("a"), listOf("a"), seconds = 200), Kind.FAMILIAR, null, "")

    @Test
    fun theSongThatJustStartedIsNotQueuedAgain() {
        // «Siguiente» ya puso B a sonar (índice movido) y el DJ aún no la consumió: su cola es B, X, Y.
        val tail = djTailAfter(listOf("A", "B"), listOf(pick("B"), pick("X"), pick("Y")))
        assertEquals(listOf("X", "Y"), tail.map { it.track.id })
    }

    @Test
    fun aConsumedQueueIsLeftAsIs() {
        val tail = djTailAfter(listOf("A", "B"), listOf(pick("X"), pick("Y"), pick("Z")))
        assertEquals(listOf("X", "Y", "Z"), tail.map { it.track.id })
    }

    @Test
    fun anAdvanceAndAManualSongCannotExposeThePenultimate() = kotlinx.coroutines.runBlocking {
        // A suena; cola del DJ B, C, D. La revisión calcula lo protegido y, mientras tanto, «Siguiente» pasa a B.
        val queue = listOf("A", "B", "C", "D")
        val protect = djTempoProtected(queue, currentIndex = 0, preparedId = null, preparingId = null, upcomingIds = queue.drop(1))
        val advancedNext = queue[1 + 1] // tras avanzar, la siguiente es C
        org.junit.Assert.assertTrue("C ya estaba protegida antes de avanzar", advancedNext in protect)
        // En el DJ real: con B y C protegidas solo puede cambiar D.
        val cache = io.github.iaguito22.oto.dj.AudioAnalysisCache(file = null)
        val songs = (1..30).map { i -> io.github.iaguito22.oto.Heard("t-$i", "T $i", "Art ${i % 3}", null, "a${i % 3}") }
        val dj = io.github.iaguito22.oto.dj.Dj(
            io.github.iaguito22.oto.dj.Profile(listOf(io.github.iaguito22.oto.LocalPlaylist("l", "L", songs)), emptyList(), emptyList(), emptyList(), 1_781_000_000_000L),
            object : io.github.iaguito22.oto.dj.Catalog {
                override suspend fun artist(id: String) = null
                override suspend fun songs(info: io.github.iaguito22.oto.dj.ArtistInfo) = emptyList<Track>()
                override suspend fun radio(id: String) = emptyList<Track>()
                override suspend fun find(name: String) = null
            },
            io.github.iaguito22.oto.dj.Memory(null), this, kotlin.random.Random(3), analysisCache = cache,
        )
        val playing = dj.start()!!
        val up = dj.prepareNextUp(3).map { it.track.id }
        // Todas a tempos imposibles: sin protección cambiaría la 2.ª.
        (listOf(playing.id) + up).forEachIndexed { i, id -> cache.put(metricsAt(id, if (i % 2 == 0) 100.0 else 140.0)) }
        val ids = listOf(playing.id) + up
        val withManual = listOf(playing.id, "manual") + up
        val protectedWithManual = djTempoProtected(withManual, 0, null, null, upcomingIds = up)
        org.junit.Assert.assertTrue(up[1] in protectedWithManual)
        org.junit.Assert.assertFalse(up[2] in protectedWithManual)
        val swap = dj.retuneUpcomingTempo(3, protect = protectedWithManual)
        // La 3.ª (D) no encaja con la 2.ª (C): se cambia, y B y C siguen.
        org.junit.Assert.assertNotNull("D se debía cambiar", swap)
        assertEquals(up[2], swap!!.removed.id)
        assertEquals(up.take(2), dj.nextUp().take(2).map { it.track.id })
    }

    private fun metricsAt(id: String, bpm: Double) = io.github.iaguito22.oto.dj.AudioMetrics(
        trackId = id,
        bpm = bpm,
        bpmConfidence = 0.8,
        beatStrength = 0.6,
        durationMs = 200_000L,
        analyzedMs = 200_000L,
        beatGrid = (0 until 12).map { it * 8_000L },
        bars = (0 until 16).map { i ->
            val beat = 60_000.0 / bpm
            val st = (i * 4 * beat).toLong()
            io.github.iaguito22.oto.dj.BarFeature(startMs = st, endMs = (st + 4 * beat).toLong(), kickOnBeat = 0.6)
        },
    )

    @Test
    fun twoAdvancesBeforePublishingUndoTheReview() {
        val gate = IndexGate()
        var index = 0
        val before = gate.epoch
        // Mientras la revisión trabaja, dos «Siguiente».
        gate.moving { index++ }
        gate.moving { index++ }
        var published = false
        org.junit.Assert.assertFalse(gate.publishIfStill(before) { published = true })
        org.junit.Assert.assertFalse("no se publica la sustitución de la nueva siguiente", published)
        assertEquals(2, index)
    }

    @Test
    fun anAdvanceCannotSlipBetweenCheckingAndPublishing() {
        val gate = IndexGate()
        var index = 0
        val before = gate.epoch
        val insidePublish = java.util.concurrent.CountDownLatch(1)
        val releasePublish = java.util.concurrent.CountDownLatch(1)
        val advanced = java.util.concurrent.CountDownLatch(1)
        var indexSeenByPublish = -1
        // La revisión ya comprobó la época y está publicando…
        val reviewer = Thread {
            gate.publishIfStill(before) {
                insidePublish.countDown()
                releasePublish.await()
                indexSeenByPublish = index
            }
        }
        reviewer.start()
        org.junit.Assert.assertTrue(insidePublish.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // …y llegan dos «Siguiente»: esperan a que acabe la publicación.
        val skipper = Thread {
            gate.moving { index++ }
            gate.moving { index++ }
            advanced.countDown()
        }
        skipper.start()
        org.junit.Assert.assertFalse("el avance no se cuela en la publicación", advanced.await(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        releasePublish.countDown()
        reviewer.join(5_000)
        org.junit.Assert.assertTrue(advanced.await(5, java.util.concurrent.TimeUnit.SECONDS))
        skipper.join(5_000)
        assertEquals("la publicación vio el índice de antes de avanzar", 0, indexSeenByPublish)
        assertEquals(2, index)
        org.junit.Assert.assertEquals(before + 2, gate.epoch)
    }
}
