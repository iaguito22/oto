package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** El DJ cambia canciones de la cola que no se podrán cuadrar (tempo o bombo) y recuerda tempos entre versiones. */
class DjTempoQueueTest {
    private val utc = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 6, 15, 16, 0, 0, 0, utc).toInstant().toEpochMilli()

    private val noCatalog = object : Catalog {
        override suspend fun artist(id: String): ArtistInfo? = null
        override suspend fun songs(info: ArtistInfo): List<Track> = emptyList()
        override suspend fun radio(id: String): List<Track> = emptyList()
        override suspend fun find(name: String): String? = null
    }

    private fun lists(): List<LocalPlaylist> = listOf(
        LocalPlaylist(
            "urbano",
            "Urbano",
            (1..10).map { i -> Heard("u-$i", "Urbano $i", "Duki", null, "duki") } +
                (1..10).map { i -> Heard("q-$i", "Quevedo $i", "Quevedo", null, "quevedo") } +
                (1..10).map { i -> Heard("b-$i", "Bizarrap $i", "Bizarrap", null, "bzrp") },
        ),
    )

    private fun metrics(
        id: String,
        bpm: Double,
        kick: Double = 0.6,
        confidence: Double = 0.8,
        analyzedMs: Long = 200_000L,
        durationMs: Long = 200_000L,
        bars: Int = 16,
        version: Long = now,
    ): AudioMetrics {
        val beat = 60_000.0 / bpm
        return AudioMetrics(
            trackId = id,
            bpm = bpm,
            bpmConfidence = confidence,
            beatStrength = 0.6,
            durationMs = durationMs,
            analyzedMs = analyzedMs,
            analyzedAt = version,
            beatGrid = (0 until 12).map { (it * 8_000L) },
            bars = (0 until bars).map { i ->
                val s = (i * 4 * beat).toLong()
                BarFeature(startMs = s, endMs = (s + 4 * beat).toLong(), kickOnBeat = kick)
            },
        )
    }

    private fun profile(id: String, bpm: Double, kick: Double = 0.6, whole: Boolean = true, confidence: Double = 0.8) =
        DjTempoMatch.profileOf(metrics(id, bpm, kick, confidence, analyzedMs = if (whole) 200_000L else 60_000L))!!

    @Test
    fun verdictUsesTheBridgeLimitsWithHalfAndDoubleTime() {
        val a = profile("a", 100.0)
        assertEquals(DjTempoMatch.TempoFit.Fits, DjTempoMatch.tempoVerdict(a, profile("b", 104.0)).fit)
        // 7,5 %: solo si B entra sin voz (8 %); cantando, el 6 %.
        assertEquals(DjTempoMatch.TempoFit.Fits, DjTempoMatch.tempoVerdict(a, profile("b", 93.0), maxStretchPct = 8.0).fit)
        assertEquals(DjTempoMatch.TempoFit.TempoFar, DjTempoMatch.tempoVerdict(a, profile("b", 93.0)).fit)
        // Mitad de tempo: 51 × 2 = 102.
        assertEquals(DjTempoMatch.TempoFit.Fits, DjTempoMatch.tempoVerdict(a, profile("b", 51.0)).fit)
        // Los del registro: 100 → 120 (20 %), 94 → 120.
        val far = DjTempoMatch.tempoVerdict(a, profile("b", 120.0))
        assertEquals(DjTempoMatch.TempoFit.TempoFar, far.fit)
        assertTrue(far.incomingBlocks)
        assertTrue(far.stretchPct > 15.0)
        assertEquals(DjTempoMatch.TempoFit.TempoFar, DjTempoMatch.tempoVerdict(profile("a", 94.0), profile("b", 120.0)).fit)
        // Tempo perfecto pero sin bombo (el 6,2 % con B 0,00 del registro).
        assertEquals(DjTempoMatch.TempoFit.NoKick, DjTempoMatch.tempoVerdict(a, profile("b", 100.0, kick = 0.0)).fit)
    }

    @Test
    fun verdictWaitsWhenItCannotJudgeAndSkipsOutgoingWithoutKick() {
        val a = profile("a", 100.0)
        assertEquals(DjTempoMatch.TempoFit.Unknown, DjTempoMatch.tempoVerdict(a, null).fit)
        assertEquals(DjTempoMatch.TempoFit.Unknown, DjTempoMatch.tempoVerdict(null, a).fit)
        // Principio con pulso dudoso: se espera a más audio.
        assertEquals(
            DjTempoMatch.TempoFit.Unknown,
            DjTempoMatch.tempoVerdict(a, profile("b", 120.0, whole = false, confidence = 0.1)).fit,
        )
        // Entera y sin pulso claro: no se cuadra.
        assertEquals(DjTempoMatch.TempoFit.NoPulse, DjTempoMatch.tempoVerdict(a, profile("b", 120.0, confidence = 0.1)).fit)
        // Saliente sin bombo: cambiar la entrante no ayuda.
        assertEquals(
            DjTempoMatch.TempoFit.NotApplicable,
            DjTempoMatch.tempoVerdict(profile("a", 100.0, kick = 0.0), profile("b", 130.0)).fit,
        )
        // Bombo sin medir (análisis viejo), entera: se juzga solo por tempo.
        val oldB = DjTempoMatch.profileOf(metrics("b", 101.0, kick = -1.0))!!
        assertFalse(oldB.kickKnown)
        assertEquals(DjTempoMatch.TempoFit.Fits, DjTempoMatch.tempoVerdict(a, oldB).fit)
    }

    @Test
    fun tempoIndexSurvivesAnAnalysisVersionBump() {
        val dir = Files.createTempDirectory("oto-tempo").toFile()
        try {
            val file = File(dir, "audio-analysis.json")
            // Un análisis guardado con una versión anterior (v4): AudioMetrics lo descarta, el índice no.
            val old = metrics("viejo", 97.0).toJson().put("v", 4)
            file.writeText(JSONObject().put("viejo", old).toString())
            val cache = AudioAnalysisCache(file)
            assertNull(cache.get("viejo"))
            val p = cache.tempoProfile("viejo")
            assertNotNull(p)
            assertEquals(97.0, p!!.bpm, 0.01)
            assertTrue(p.kickKnown)

            // Lo nuevo entra en el índice y se guarda aparte.
            cache.put(metrics("nuevo", 124.0))
            cache.save()
            assertTrue(File(dir, "tempo-index.json").exists())
            val reopened = AudioAnalysisCache(file)
            assertEquals(124.0, reopened.tempoProfile("nuevo")!!.bpm, 0.01)
            assertEquals(97.0, reopened.tempoProfile("viejo")!!.bpm, 0.01)

            // Una curva en vivo (sin audio decodificado) no pisa un análisis del audio.
            reopened.put(metrics("nuevo", 90.0, analyzedMs = 0L, version = now + 10_000L))
            assertEquals(124.0, reopened.tempoProfile("nuevo")!!.bpm, 0.01)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theLastQueuedSongIsSwappedWhenItsTempoCannotBeMatched() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(5), analysisCache = cache)
        assertNotNull(dj.start())
        val queue = dj.prepareNextUp(3)
        assertEquals(3, queue.size)
        val (first, second, third) = queue.map { it.track }
        // Sin análisis de la tercera no se toca nada.
        cache.put(metrics(first.id, 100.0))
        cache.put(metrics(second.id, 100.0))
        assertNull(dj.retuneUpcomingTempo(3))

        cache.put(metrics(third.id, 128.0))
        val swap = dj.retuneUpcomingTempo(3)
        assertNotNull(swap)
        swap!!
        assertEquals(third.id, swap.removed.id)
        assertEquals(second.id, swap.previous.id)
        assertEquals(DjTempoMatch.TempoFit.TempoFar, swap.verdict.fit)
        assertTrue(swap.describe(), swap.describe().contains("cambiada por tempo"))
        val after = dj.nextUp().map { it.track.id }
        assertEquals("la siguiente inmediata no se toca", first.id, after[0])
        assertEquals(second.id, after[1])
        assertNotEquals(third.id, after[2])
        assertEquals(swap.replacement?.id, after[2])
        assertEquals("la cola sigue llena", 3, after.size)

        // La nueva encaja (102): se queda y no hay más cambios.
        cache.put(metrics(after[2], 102.0))
        val notes = ArrayList<String>()
        assertNull(dj.retuneUpcomingTempo(3) { notes += it })
        assertEquals(after, dj.nextUp().map { it.track.id })
        assertTrue(notes.joinToString(), notes.any { it.contains("encaja") })
        // La apartada no es un veto: el artista no queda castigado y las elecciones siguen saliendo.
        assertNotNull(dj.next())
    }

    @Test
    fun androidProtectionKeepsThePenultimateEvenWhenItDoesNotFit() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(9), analysisCache = cache)
        dj.start()!!
        val q = dj.prepareNextUp(3).map { it.track }
        cache.put(metrics(q[0].id, 100.0))
        cache.put(metrics(q[1].id, 130.0))
        cache.put(metrics(q[2].id, 100.0))
        val protect = DjTempoMatch.protectedUpcomingIds(q.map { it.id })
        assertEquals(q.take(2).map { it.id }.toSet(), protect)
        val swap = dj.retuneUpcomingTempo(3, protect = protect)!!
        assertEquals(q[2].id, swap.removed.id)
        assertEquals(q.take(2).map { it.id }, dj.nextUp().take(2).map { it.track.id })
    }

    @Test
    fun theGlobalBudgetStillAllowsRestoringTheOriginal() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(9), analysisCache = cache)
        dj.start()!!
        val q = dj.prepareNextUp(3).map { it.track }
        cache.put(metrics(q[0].id, 100.0))
        cache.put(metrics(q[1].id, 100.0))
        cache.put(metrics(q[2].id, 130.0))
        repeat(Dj.MAX_TEMPO_SWAPS) {
            assertFalse(dj.retuneUpcomingTempo(3)!!.restoredOriginal)
            cache.put(metrics(dj.nextUp()[2].track.id, 130.0))
        }
        assertTrue(dj.retuneUpcomingTempo(3)!!.restoredOriginal)
        // Otro hueco consume la cuarta y última elección de esta canción.
        cache.put(metrics(q[1].id, 130.0))
        val last = dj.retuneUpcomingTempo(3)!!
        assertEquals(q[1].id, last.removed.id)
        assertFalse(last.restoredOriginal)
        cache.put(metrics(last.replacement!!.id, 130.0))
        val back = dj.retuneUpcomingTempo(3)!!
        assertTrue(back.restoredOriginal)
        assertEquals(q[1].id, dj.nextUp()[1].track.id)
        assertNull(dj.retuneUpcomingTempo(3))
    }

    @Test
    fun afterTheMaximumAttemptsTheOriginalComesBack() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(9), analysisCache = cache)
        assertNotNull(dj.start())
        val queue = dj.prepareNextUp(3).map { it.track }
        cache.put(metrics(queue[0].id, 100.0))
        cache.put(metrics(queue[1].id, 100.0))
        val original = queue[2]
        cache.put(metrics(original.id, 130.0))
        val removed = ArrayList<String>()
        repeat(Dj.MAX_TEMPO_SWAPS) { n ->
            val swap = dj.retuneUpcomingTempo(3)
            assertNotNull("intento ${n + 1}", swap)
            assertFalse(swap!!.restoredOriginal)
            assertEquals(n + 1, swap.attempt)
            removed += swap.removed.id
            assertEquals(queue[0].id, dj.nextUp()[0].track.id)
            // Ninguna encaja: todas a 130.
            cache.put(metrics(dj.nextUp()[2].track.id, 130.0))
        }
        assertEquals("nunca vuelve a probar una ya apartada", removed.size, removed.toSet().size)
        val back = dj.retuneUpcomingTempo(3)
        assertNotNull(back)
        assertTrue(back!!.restoredOriginal)
        assertEquals(original.id, dj.nextUp()[2].track.id)
        assertEquals(queue[1].id, dj.nextUp()[1].track.id)
        // Hueco cerrado: no se insiste.
        assertNull(dj.retuneUpcomingTempo(3))
        assertEquals(original.id, dj.nextUp()[2].track.id)
    }

    @Test
    fun theImmediateNextIsNeverSwapped() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(3), analysisCache = cache)
        val playing = dj.start()!!
        val queue = dj.prepareNextUp(3).map { it.track }
        // La que suena y la siguiente no encajan, pero la siguiente ya se está preparando: no se cambia.
        cache.put(metrics(playing.id, 100.0))
        cache.put(metrics(queue[0].id, 140.0))
        cache.put(metrics(queue[1].id, 140.0))
        cache.put(metrics(queue[2].id, 140.0))
        assertNull(dj.retuneUpcomingTempo(3))
        assertEquals(queue.map { it.id }, dj.nextUp().map { it.track.id })
    }

    @Test
    fun aWholeAnalysisCanUndoWhatThePartialOneAccepted() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(21), analysisCache = cache)
        assertNotNull(dj.start())
        val q = dj.prepareNextUp(3).map { it.track }
        cache.put(metrics(q[0].id, 100.0))
        cache.put(metrics(q[1].id, 100.0))
        // El principio dice 102 (encaja)…
        cache.put(metrics(q[2].id, 102.0, analyzedMs = 60_000L))
        assertNull(dj.retuneUpcomingTempo(3))
        // …y la canción entera, 126: se vuelve a mirar y se cambia.
        cache.put(metrics(q[2].id, 126.0, version = now + 1))
        val swap = dj.retuneUpcomingTempo(3)
        assertNotNull(swap)
        assertEquals(q[2].id, swap!!.removed.id)
        assertEquals(q[0].id, dj.nextUp()[0].track.id)
    }

    @Test
    fun aVetoAfterAcceptingStillGetsChecked() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(23), analysisCache = cache)
        assertNotNull(dj.start())
        val q = dj.prepareNextUp(3).map { it.track }
        cache.put(metrics(q[0].id, 100.0))
        cache.put(metrics(q[1].id, 100.0))
        cache.put(metrics(q[2].id, 101.0))
        assertNull(dj.retuneUpcomingTempo(3))
        // El usuario veta la tercera; la nueva no encaja: se mira aunque el hueco ya hubiera encajado.
        val after = dj.vetoNextUp(q[2].id, 3)
        val vetoReplacement = after[2].track
        assertEquals(q[1].id, after[1].track.id)
        cache.put(metrics(vetoReplacement.id, 140.0))
        val swap = dj.retuneUpcomingTempo(3)
        assertNotNull(swap)
        assertEquals(vetoReplacement.id, swap!!.removed.id)
    }

    @Test
    fun aWholeAnalysisWithoutPulseReplacesAnOldTempo() {
        val cache = AudioAnalysisCache(file = null)
        cache.put(metrics("x", 100.0, analyzedMs = 60_000L))
        assertEquals(100.0, cache.tempoProfile("x")!!.bpm, 0.01)
        // Entera y sin BPM: ya no hay pulso que cuadrar.
        cache.put(AudioMetrics(trackId = "x", bpm = null, durationMs = 200_000L, analyzedMs = 200_000L, analyzedAt = now + 5))
        val p = cache.tempoProfile("x")!!
        assertEquals(0.0, p.bpm, 0.0)
        assertEquals(DjTempoMatch.TempoFit.NoPulse, DjTempoMatch.tempoVerdict(profile("a", 100.0), p).fit)
    }

    @Test
    fun theRebuiltQueuePlaysInOrderWithItsTandas() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(31), analysisCache = cache)
        assertNotNull(dj.start())
        val q = dj.prepareNextUp(3).map { it.track }
        cache.put(metrics(q[0].id, 100.0))
        cache.put(metrics(q[1].id, 100.0))
        cache.put(metrics(q[2].id, 135.0))
        assertNotNull(dj.retuneUpcomingTempo(3))
        val queued = dj.nextUp()
        val played = listOfNotNull(dj.next(), dj.next(), dj.next()).map { it.id }
        assertEquals(queued.map { it.track.id }, played)
        queued.forEach { assertEquals(it.tanda, dj.tandaOf(it.track.id)) }
        assertEquals(played, dj.picks.takeLast(3).map { it.track.id })
        // Y la cola se sigue llenando con normalidad detrás.
        assertEquals(3, dj.prepareNextUp(3).size)
    }

    @Test
    fun aVetoedOriginalNeverComesBack() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(41), analysisCache = cache)
        assertNotNull(dj.start())
        val q = dj.prepareNextUp(3).map { it.track }
        cache.put(metrics(q[0].id, 100.0))
        cache.put(metrics(q[1].id, 100.0))
        cache.put(metrics(q[2].id, 130.0))
        val original = q[2]
        val first = dj.retuneUpcomingTempo(3)!!
        assertEquals(original.id, first.removed.id)
        // El usuario veta la original mientras está apartada (p. ej. desde otra vista de la cola).
        dj.vetoNextUp(original.id, 3)
        repeat(Dj.MAX_TEMPO_SWAPS) {
            cache.put(metrics(dj.nextUp()[2].track.id, 130.0))
            dj.retuneUpcomingTempo(3)
        }
        assertFalse(dj.nextUp().any { it.track.id == original.id })
    }

    @Test
    fun protectedSongsAndEverythingBeforeThemAreLeftAlone() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(43), analysisCache = cache)
        assertNotNull(dj.start())
        val q = dj.prepareNextUp(3).map { it.track }
        cache.put(metrics(q[0].id, 100.0))
        cache.put(metrics(q[1].id, 140.0))
        cache.put(metrics(q[2].id, 140.0))
        // La 2.ª (q[1]) se está preparando: no se toca, y solo se mira la 3.ª (que encaja con ella).
        assertNull(dj.retuneUpcomingTempo(3, protect = setOf(q[1].id)))
        assertEquals(q.map { it.id }, dj.nextUp().map { it.track.id })
    }

    @Test
    fun aPartialOutgoingWithoutKickIsNotClosedYet() {
        val introA = profile("a", 100.0, kick = 0.0, whole = false)
        assertEquals(DjTempoMatch.TempoFit.TempoFar, DjTempoMatch.tempoVerdict(introA, profile("b", 130.0)).fit)
        assertEquals(DjTempoMatch.TempoFit.Fits, DjTempoMatch.tempoVerdict(introA, profile("b", 101.0)).fit)
    }

    @Test
    fun theIndexKeepsTheKickFromANewPartialAnalysis() {
        val dir = Files.createTempDirectory("oto-tempo-merge").toFile()
        try {
            val file = File(dir, "audio-analysis.json")
            // v4 entera sin bombo medido…
            val old = metrics("m", 100.0, kick = -1.0).toJson().put("v", 4)
            file.writeText(JSONObject().put("m", old).toString())
            val cache = AudioAnalysisCache(file)
            assertFalse(cache.tempoProfile("m")!!.kickKnown)
            // …y la v5 del principio, que sí lo mide (y no tiene bombo).
            cache.put(metrics("m", 100.0, kick = 0.0, analyzedMs = 60_000L))
            val merged = cache.tempoProfile("m")!!
            assertTrue(merged.wholeSong)
            assertTrue(merged.kickKnown)
            assertEquals(0.0, merged.kickShare, 0.0)
            assertEquals(DjTempoMatch.TempoFit.NoKick, DjTempoMatch.tempoVerdict(profile("a", 100.0), merged).fit)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oldAnalysesAreReadOnlyFromVersionThree() {
        assertNull(DjTempoMatch.TempoProfile.fromAnalysisJson(metrics("v2", 100.0).toJson().put("v", 2)))
        val v3 = metrics("v3", 100.0).toJson().put("v", 3)
        v3.remove("bars")
        v3.remove("bs")
        val p = DjTempoMatch.TempoProfile.fromAnalysisJson(v3)!!
        assertFalse(p.kickKnown)
        assertEquals(0.5, p.beatStrength, 0.0)
        assertTrue(DjTempoMatch.isBeatMatchReady(p))
    }

    @Test
    fun knowingTempoMakesTheDjPickMatchablePairs() = runBlocking {
        // Tres artistas; cada uno con la mitad de canciones a 100 BPM y la otra mitad a 128.
        val songs = (1..10).flatMap { i ->
            listOf("duki", "quevedo", "bzrp").map { a -> Heard("$a-$i", "$a $i", a, null, a) }
        }
        val pl = listOf(LocalPlaylist("mix", "Mix", songs))
        val bpmOf = songs.associate { it.id to (if (it.id.substringAfter('-').toInt() % 2 == 0) 100.0 else 128.0) }
        suspend fun share(withIndex: Boolean): Double {
            var ok = 0
            var pairs = 0
            repeat(25) { seed ->
                val cache = AudioAnalysisCache(file = null)
                if (withIndex) bpmOf.forEach { (id, bpm) -> cache.put(metrics(id, bpm)) }
                val dj = Dj(Profile(pl, emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(seed), analysisCache = cache)
                val seq = ArrayList<String>()
                var t = dj.start()
                while (t != null && seq.size < 8) {
                    seq += t.id
                    t = dj.next()
                }
                seq.zipWithNext().forEach { (x, y) ->
                    pairs++
                    if (bpmOf[x] == bpmOf[y]) ok++
                }
            }
            return ok.toDouble() / pairs
        }
        val blind = share(withIndex = false)
        val known = share(withIndex = true)
        assertTrue("con índice $known, a ciegas $blind", known >= blind + 0.2)
        assertTrue("con índice $known", known >= 0.75)
    }

    @Test
    fun theKickKeepsImprovingAsMoreOfTheSongIsHeard() {
        val dir = Files.createTempDirectory("oto-tempo-kick").toFile()
        try {
            val file = File(dir, "audio-analysis.json")
            // v4 entera sin bombo medido.
            file.writeText(JSONObject().put("k", metrics("k", 100.0, kick = -1.0).toJson().put("v", 4)).toString())
            val cache = AudioAnalysisCache(file)
            // La intro (8 compases) sin bombo…
            cache.put(metrics("k", 100.0, kick = 0.0, analyzedMs = 20_000L, bars = 8))
            assertEquals(0.0, cache.tempoProfile("k")!!.kickShare, 0.0)
            // …y luego más canción, ya con bombo: manda el que oyó más compases, aunque el tempo siga siendo el entero.
            cache.put(metrics("k", 100.0, kick = 0.6, analyzedMs = 60_000L, bars = 24))
            val p = cache.tempoProfile("k")!!
            assertTrue(p.wholeSong)
            assertEquals(1.0, p.kickShare, 0.0)
            assertEquals(DjTempoMatch.TempoFit.Fits, DjTempoMatch.tempoVerdict(profile("a", 100.0), p).fit)
            // Persistido con su procedencia.
            cache.save()
            assertEquals(1.0, AudioAnalysisCache(file).tempoProfile("k")!!.kickShare, 0.0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun withoutCandidatesAndAVetoedOriginalTheQueueIsStillPublished() = runBlocking {
        // Biblioteca mínima: la semilla, tres en cola y una de repuesto.
        val songs = listOf("s", "q1", "q2", "q3", "spare").mapIndexed { i, id -> Heard(id, "Tema $id", "Art $i", null, "art$i") }
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(listOf(LocalPlaylist("p", "P", songs)), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(1), analysisCache = cache)
        assertNotNull(dj.start())
        val q = dj.prepareNextUp(3).map { it.track }
        assertEquals(3, q.size)
        cache.put(metrics(q[0].id, 100.0))
        cache.put(metrics(q[1].id, 100.0))
        val original = q[2]
        cache.put(metrics(original.id, 130.0))
        val first = dj.retuneUpcomingTempo(3)!!
        val spare = first.replacement!!
        // El usuario veta la original (apartada) y la de repuesto tampoco encaja: no queda nada.
        dj.vetoNextUp(original.id, 3)
        cache.put(metrics(spare.id, 130.0))
        val second = dj.retuneUpcomingTempo(3)
        assertNotNull("si la cola se recorta hay que devolverlo para publicarla", second)
        assertNull(second!!.replacement)
        val left = dj.nextUp().map { it.track.id }
        assertEquals(second.nextUp.map { it.track.id }, left)
        assertFalse(left.contains(spare.id))
        assertFalse(left.contains(original.id))
        assertEquals(listOf(q[0].id, q[1].id), left)
    }

    @Test
    fun aPartialIncomingIsOnlyDiscardedWithReliableEvidence() {
        val a = profile("a", 100.0)
        // Intro de 8 compases sin bombo: aún no.
        val intro = DjTempoMatch.profileOf(metrics("b", 100.0, kick = 0.0, analyzedMs = 20_000L, bars = 8))!!
        assertEquals(DjTempoMatch.TempoFit.Unknown, DjTempoMatch.tempoVerdict(a, intro).fit)
        // 16 compases sin bombo: ya sí.
        val longer = DjTempoMatch.profileOf(metrics("b", 100.0, kick = 0.0, analyzedMs = 40_000L, bars = 16))!!
        assertEquals(DjTempoMatch.TempoFit.NoKick, DjTempoMatch.tempoVerdict(a, longer).fit)
        // Tempo lejos con pulso flojo a medias: se espera.
        val shaky = DjTempoMatch.profileOf(metrics("b", 130.0, confidence = 0.36, analyzedMs = 60_000L))!!
        assertEquals(DjTempoMatch.TempoFit.Unknown, DjTempoMatch.tempoVerdict(a, shaky).fit)
        val sure = DjTempoMatch.profileOf(metrics("b", 130.0, confidence = 0.7, analyzedMs = 60_000L))!!
        assertEquals(DjTempoMatch.TempoFit.TempoFar, DjTempoMatch.tempoVerdict(a, sure).fit)
    }

    @Test
    fun anOldWholeTempoDoesNotMakeAShortKickIntroFinal() {
        val dir = Files.createTempDirectory("oto-tempo-kw").toFile()
        try {
            val file = File(dir, "audio-analysis.json")
            file.writeText(JSONObject().put("i", metrics("i", 100.0, kick = -1.0).toJson().put("v", 4)).toString())
            val cache = AudioAnalysisCache(file)
            // Intro nueva de 8 compases sin bombo, sobre un tempo entero viejo.
            cache.put(metrics("i", 100.0, kick = 0.0, analyzedMs = 20_000L, bars = 8))
            val p = cache.tempoProfile("i")!!
            assertTrue("el tempo es de la entera", p.wholeSong)
            assertFalse("el bombo no", p.kickWhole)
            // Como entrante: aún no se da por «sin bombo» (hacen falta 16 compases a medias).
            assertEquals(DjTempoMatch.TempoFit.Unknown, DjTempoMatch.tempoVerdict(profile("a", 100.0), p).fit)
            // Como saliente: no cierra el hueco.
            assertEquals(DjTempoMatch.TempoFit.TempoFar, DjTempoMatch.tempoVerdict(p, profile("b", 130.0)).fit)
            // Y se guarda así.
            cache.save()
            assertFalse(AudioAnalysisCache(file).tempoProfile("i")!!.kickWhole)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aReviewCanBeUndoneWhenWhatPlaysMoves() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(51), analysisCache = cache)
        val playing = dj.start()!!
        val q = dj.prepareNextUp(3).map { it.track }
        cache.put(metrics(playing.id, 100.0))
        cache.put(metrics(q[0].id, 100.0))
        cache.put(metrics(q[1].id, 100.0))
        cache.put(metrics(q[2].id, 130.0))
        val checkpoint = dj.tempoCheckpoint()
        // La revisión cambia la 3.ª (en el PC, D)…
        val swap = dj.retuneUpcomingTempo(3, protect = setOf(playing.id, q[0].id, q[1].id))
        assertEquals(q[2].id, swap!!.removed.id)
        // …y mientras tanto «Siguiente» dos veces: lo que suena se movió, se deshace todo.
        assertTrue(dj.rollbackTempo(checkpoint))
        assertEquals(q.map { it.id }, dj.nextUp().map { it.track.id })
        // La deshecha no cuenta como intento ni aparta a nadie: repetida, quita la misma y es el intento 1.
        val again = dj.retuneUpcomingTempo(3, protect = setOf(playing.id, q[0].id, q[1].id))
        assertEquals(q[2].id, again!!.removed.id)
        assertEquals(1, again.attempt)
        // Con algo ya confirmado entretanto, el punto de control ya no vale.
        val stale = dj.tempoCheckpoint()
        dj.next()
        assertFalse(dj.rollbackTempo(stale))
    }

    @Test
    fun theChoiceUsesTheSameKickAndPulseRulesAsTheReview() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        val dj = Dj(Profile(lists(), emptyList(), emptyList(), emptyList(), now, utc), noCatalog, Memory(null), this, Random(61), analysisCache = cache)
        val playing = dj.start()!!
        cache.put(metrics(playing.id, 100.0))
        fun t(id: String) = Track(id, id, listOf("x"), listOf("x"), seconds = 200)
        val good = dj.tempoFitOf(t("good").also { cache.put(metrics(it.id, 100.0)) })
        // Intro de 8 compases sin bombo: la revisión aún espera; la elección no la hunde.
        val intro = dj.tempoFitOf(t("intro").also { cache.put(metrics(it.id, 100.0, kick = 0.0, analyzedMs = 20_000L, bars = 8)) })
        // 16 compases o entera sin bombo: ya es «sin bombo» en las dos.
        val noKick = dj.tempoFitOf(t("nokick").also { cache.put(metrics(it.id, 100.0, kick = 0.0)) })
        // Entera sin pulso (bpm 0) frente a sin medir.
        val noPulse = dj.tempoFitOf(t("nopulse").also { cache.put(AudioMetrics(trackId = it.id, bpm = null, durationMs = 200_000L, analyzedMs = 200_000L)) })
        val unknown = dj.tempoFitOf(t("unknown"))
        assertTrue("intro $intro ≈ buena $good", intro >= good * 0.85)
        assertTrue("sin bombo $noKick < intro $intro", noKick < intro * 0.6)
        assertEquals(0.6, noPulse, 1e-9)
        assertEquals(0.78, unknown, 1e-9)
    }
}
