package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class DjWorkflowAndBridgesTest {
    private val utc = ZoneId.of("UTC")
    private val fixedNow = ZonedDateTime.of(2026, 6, 15, 16, 0, 0, 0, utc).toInstant().toEpochMilli()

    private class BridgeCatalogStub(
        private val artists: Map<String, ArtistInfo> = emptyMap(),
        private val radios: Map<String, List<Track>> = emptyMap(),
    ) : Catalog {
        override suspend fun artist(id: String): ArtistInfo? = artists[id]
        override suspend fun songs(info: ArtistInfo): List<Track> = info.top
        override suspend fun radio(id: String): List<Track> = radios[id].orEmpty()
        override suspend fun find(name: String): String? = artists.values.firstOrNull { it.name.equals(name, true) }?.id ?: name
    }

    private fun twoWorldsLists(): List<LocalPlaylist> = listOf(
        LocalPlaylist(
            "pl-urbano",
            "Urbano Workout Hype",
            (1..8).map { i -> Heard("u-$i", "Urbano Hit $i", "Duki", null, "duki") } +
                (1..6).map { i -> Heard("q-$i", "Quevedo Flow $i", "Quevedo", null, "quevedo") },
        ),
        LocalPlaylist(
            "pl-rock",
            "Rock Indie Classic",
            (1..8).map { i -> Heard("r-$i", "Guitar Anthem $i", "Vetusta Morla", null, "vetusta") } +
                (1..6).map { i -> Heard("i-$i", "Indie Night $i", "Izal", null, "izal") },
        ),
    )

    @Test
    fun startFromSeedAndPlayFullOnceWorkCleanly() = runBlocking {
        val profile = Profile(twoWorldsLists(), emptyList(), emptyList(), emptyList(), fixedNow, utc)
        val memory = Memory(file = null)
        val dj = Dj(profile, BridgeCatalogStub(), memory, this, Random(7), mode = DjMode.Adhd)

        val unknownSeed = Track(
            id = "seed-99",
            title = "Tema Semilla",
            artists = listOf("Artista Invitado"),
            artistIds = listOf("guest-1"),
            seconds = 205,
        )
        val first = dj.startFrom(unknownSeed, playSeedFirst = true)
        assertNotNull(first)
        assertEquals("seed-99", first!!.id)
        assertEquals("Semilla · Artista Invitado", dj.reasonOf("seed-99"))
        assertNotNull("ADHD mode should compute segment initially", dj.picks.first().adhdSegment)

        // «Oír esta entera» anula el recorte ADHD solo para esa pista sin apagar el modo ADHD.
        assertTrue(dj.playFullOnce("seed-99"))
        assertTrue(dj.isFullOnce("seed-99"))
        assertNull(dj.adhdSegment(unknownSeed, durationMs = 205_000L))
        assertNull(dj.picks.first().adhdSegment)
        assertEquals(DjMode.Adhd, dj.mode)

        val second = dj.next()
        assertNotNull(second)
        assertNotEquals("seed-99", second!!.id)
        assertFalse(dj.isFullOnce(second.id))
        assertNotNull("Following track in ADHD mode still gets a segment", dj.adhdSegment(second, durationMs = 200_000L))
    }

    @Test
    fun nextUpPreviewsAndVetoReplacesWithoutPollutingPicks() = runBlocking {
        val profile = Profile(twoWorldsLists(), emptyList(), emptyList(), emptyList(), fixedNow, utc)
        val memory = Memory(file = null)
        val dj = Dj(profile, BridgeCatalogStub(), memory, this, Random(11))

        val first = dj.start()
        assertNotNull(first)
        val picksBefore = dj.picks.size

        val queue = dj.prepareNextUp(3)
        assertEquals(3, queue.size)
        // Preparar NextUp no compromete picks hasta llamar a next().
        assertEquals(picksBefore, dj.picks.size)
        val queuedIds = queue.map { it.track.id }
        assertEquals(queuedIds.distinct().size, queuedIds.size)

        val vetoTarget = queue[1].track.id
        val updatedQueue = dj.vetoNextUp(vetoTarget, refillCount = 3)
        assertEquals(3, updatedQueue.size)
        assertEquals("Prefix before vetoed item should stay intact", queue[0].track.id, updatedQueue[0].track.id)
        assertFalse("Vetoed track must not appear in refreshed queue", updatedQueue.any { it.track.id == vetoTarget })
        assertEquals(picksBefore, dj.picks.size)

        val next1 = dj.next()
        assertEquals(updatedQueue[0].track.id, next1?.id)
        val next2 = dj.next()
        assertEquals(updatedQueue[1].track.id, next2?.id)
        assertNotEquals(vetoTarget, next2?.id)
    }

    private fun twoWorldsGenre(track: Track): String? = when {
        track.id.startsWith("u-") || track.id.startsWith("q-") -> "Urbano"
        track.id.startsWith("r-") || track.id.startsWith("i-") -> "Rock"
        else -> null
    }

    @Test
    fun skipTandaInvalidatesStagedQueueAndSwitchesTandaCleanly() = runBlocking {
        val profile = Profile(twoWorldsLists(), emptyList(), emptyList(), emptyList(), fixedNow, utc)
        val memory = Memory(file = null)
        val dj = Dj(profile, BridgeCatalogStub(), memory, this, Random(19), genreOf = ::twoWorldsGenre)

        val first = dj.start()
        assertNotNull(first)
        val initialTanda = dj.tanda
        assertNotNull(initialTanda)
        assertEquals(initialTanda, dj.currentGenre)
        assertFalse("tanda must only contain genre name, never 'Como ...'", initialTanda!!.contains("como", ignoreCase = true))

        val staged = dj.prepareNextUp(2)
        assertTrue(staged.isNotEmpty())

        dj.skipTanda()
        assertTrue("skipTanda should clear staged NextUp items", dj.nextUp().isEmpty())

        val jumped = dj.next()
        assertNotNull(jumped)
        val newTanda = dj.tandaOf(jumped!!.id)
        assertNotNull(newTanda)
        assertNotEquals("New track after skipTanda should belong to a different tanda", initialTanda, newTanda)
        assertEquals(newTanda, dj.tanda)
        assertEquals(newTanda, dj.currentGenre)
        assertFalse("tanda after skip must only contain genre name", newTanda!!.contains("como", ignoreCase = true))
    }

    @Test
    fun collaborativeAndGenreBpmBridgesConnectBothTandasAndTriggerRemate() = runBlocking {
        val cache = AudioAnalysisCache(file = null)
        // Tanda saliente (Duki) y tanda destino (Vetusta Morla) conectadas por colaboración y BPM cercano.
        cache.put(AudioMetrics("u-1", bpm = 118.0, bpmConfidence = 0.9, rmsDb = -10.0))
        cache.put(AudioMetrics("r-1", bpm = 120.0, bpmConfidence = 0.9, rmsDb = -10.5))
        cache.put(AudioMetrics("bridge-collab", bpm = 119.0, bpmConfidence = 0.9, rmsDb = -10.2))

        val genres = buildMap {
            (1..8).forEach { put("u-$it", "Fusion"); put("r-$it", "Fusion") }
            put("bridge-collab", "Fusion")
        }
        val artistsInfo = mapOf(
            "duki" to ArtistInfo(
                id = "duki",
                name = "Duki",
                top = listOf(
                    Track(
                        id = "bridge-collab",
                        title = "Cruce Nocturno (feat. Vetusta Morla)",
                        artists = listOf("Duki", "Vetusta Morla"),
                        artistIds = listOf("duki", "vetusta"),
                        seconds = 200,
                    ),
                ),
                related = listOf("rel-bridge" to "RelBridge"),
                more = null,
            ),
            "rel-bridge" to ArtistInfo(
                id = "rel-bridge",
                name = "RelBridge",
                top = listOf(
                    Track("new-bridge-1", "Puente Nuevo (feat. Vetusta Morla)", listOf("RelBridge", "Vetusta Morla"), listOf("rel-bridge", "vetusta"), seconds = 195),
                ),
                related = listOf("vetusta" to "Vetusta Morla"),
                more = null,
            ),
        )

        val profile = Profile(twoWorldsLists(), emptyList(), emptyList(), emptyList(), fixedNow, utc, analysisCache = cache)
        val memory = Memory(file = null)
        val dj = Dj(
            profile = profile,
            catalog = BridgeCatalogStub(artistsInfo),
            memory = memory,
            scope = this,
            random = Random(3),
            genreOf = { t -> genres[t.id] },
            analysisCache = cache,
        )

        // Sembrar desde Duki y explorar su catálogo para tener el puente colaborativo en el pool.
        val first = dj.startFrom(Track("u-1", "Urbano Hit 1", listOf("Duki"), listOf("duki"), seconds = 190), playSeedFirst = true)
        assertNotNull(first)
        dj.settle()

        // Avanzar hasta forzar una transición de tanda (blockLen <= 6) y verificar puente + razón.
        var foundBridgePick: Dj.Pick? = null
        repeat(10) {
            dj.next() ?: return@repeat
            val last = dj.picks.last()
            if (last.isBridge || (last.reason?.startsWith("Puente de ") == true)) {
                foundBridgePick = last
                return@repeat
            }
        }

        val bridgePick = foundBridgePick
        assertNotNull("Expected a cross-tanda bridge pick", bridgePick)
        assertTrue(bridgePick!!.reason?.startsWith("Puente de ") == true)
        assertTrue(bridgePick.reason?.contains("→") == true)

        // Reaccionar positivamente (Save/Like) a un descubrimiento/puente no familiar debe activar un «Remate» familiar.
        var nonFamiliar = dj.picks.lastOrNull { it.kind != Kind.FAMILIAR }
        var attempts = 0
        while (nonFamiliar == null && attempts < 35) {
            dj.next() ?: break
            dj.settle()
            nonFamiliar = dj.picks.lastOrNull { it.kind != Kind.FAMILIAR }
            attempts++
        }
        assertNotNull("Expected at least one discovery/bridge pick", nonFamiliar)
        dj.react(nonFamiliar!!.track.id, DjReaction.Save)
        val rematePick = dj.next()
        assertNotNull(rematePick)
        val remateMeta = dj.picks.last()
        assertEquals(Kind.FAMILIAR, remateMeta.kind)
        assertTrue("Expected Remate reason, got ${remateMeta.reason}", remateMeta.reason?.startsWith("Remate tras ") == true)
        assertEquals("collaboratorKeys should parse feat. artists", setOf("duki", "vetusta morla"), collaboratorKeys(artistsInfo.getValue("duki").top.first()))
    }

    @Test
    fun prepareNextUpRefilledEveryNextAdvancesBlockAndChangesTandaNaturally() = runBlocking {
        val profile = Profile(twoWorldsLists(), emptyList(), emptyList(), emptyList(), fixedNow, utc)
        val memory = Memory(file = null)
        val dj = Dj(profile, BridgeCatalogStub(), memory, this, Random(23), genreOf = ::twoWorldsGenre)

        val first = dj.start()
        assertNotNull(first)
        val initialTanda = dj.tanda
        assertNotNull(initialTanda)

        val seenTandas = LinkedHashSet<String>()
        seenTandas += initialTanda!!
        repeat(10) {
            val queue = dj.prepareNextUp(3)
            assertTrue("Expected staged NextUp items", queue.isNotEmpty())
            val played = dj.next()
            assertNotNull(played)
            dj.tanda?.let { seenTandas += it }
        }
        assertTrue("Refilling prepareNextUp(3) on every next() must advance block and switch tanda naturally (seen=$seenTandas)", seenTandas.size >= 2)
    }

    @Test
    fun tandaAndCurrentGenreReturnNullWhenGenreUnknownInsteadOfArtistFallback() = runBlocking {
        val profile = Profile(twoWorldsLists(), emptyList(), emptyList(), emptyList(), fixedNow, utc)
        val dj = Dj(profile, BridgeCatalogStub(), Memory(null), this, Random(5))

        val first = dj.start()
        assertNotNull(first)
        assertNull("tanda must be null when no genre is known", dj.tanda)
        assertNull("currentGenre must be null when no genre is known", dj.currentGenre)
        assertNull("tandaOf must be null when no genre is known", dj.tandaOf(first!!.id))
    }

    @Test
    fun discoveryPrioritizesConsensusAndTopRankOverObscureRadioFillers() = runBlocking {
        // Dos artistas tuyos cercanos citan al mismo artista recomendado (consenso >= 2) y la radio confirma su hit #1.
        val lists = listOf(
            LocalPlaylist(
                "pl-main",
                "Urbano",
                (1..8).map { i -> Heard("u-$i", "Urbano $i", "Duki", "duki", "duki") } +
                    (1..8).map { i -> Heard("q-$i", "Flow $i", "Quevedo", "quevedo", "quevedo") },
            ),
        )
        val strongHit = Track("strong-hit", "Tema Estrella", listOf("Artista Top"), listOf("top-new"), seconds = 195)
        val weakLate = Track("weak-late", "Relleno Oscuro 9", listOf("Artista Flojo"), listOf("weak-new"), seconds = 190)
        val weakRadioTracks = (1..12).map { idx ->
            Track("radio-filler-$idx", "Radio Random $idx", listOf("Random $idx"), listOf("rand-$idx"), seconds = 200)
        }
        val artists = mapOf(
            "duki" to ArtistInfo(
                id = "duki",
                name = "Duki",
                top = emptyList(),
                related = listOf("quevedo" to "Quevedo", "top-new" to "Artista Top", "weak-new" to "Artista Flojo"),
                more = null,
            ),
            "quevedo" to ArtistInfo(
                id = "quevedo",
                name = "Quevedo",
                top = emptyList(),
                related = listOf("duki" to "Duki", "top-new" to "Artista Top"),
                more = null,
            ),
            "top-new" to ArtistInfo(
                id = "top-new",
                name = "Artista Top",
                top = listOf(
                    strongHit,
                    Track("top-2", "Segundo Corte", listOf("Artista Top"), listOf("top-new"), seconds = 192),
                ),
                related = listOf("duki" to "Duki", "quevedo" to "Quevedo"),
                more = null,
            ),
            "weak-new" to ArtistInfo(
                id = "weak-new",
                name = "Artista Flojo",
                top = (0..7).map { r ->
                    if (r < 7) Track("weak-$r", "Corte $r", listOf("Artista Flojo"), listOf("weak-new"), seconds = 185) else weakLate
                },
                related = emptyList(),
                more = null,
            ),
        )
        val radios = mapOf(
            "u-1" to listOf(Track("u-1", "Urbano 1", listOf("Duki"), listOf("duki"), seconds = 190), strongHit) + weakRadioTracks,
        )

        var strongTopWins = 0
        var firstNewTracks = 0
        repeat(8) { seed ->
            val dj = Dj(
                profile = Profile(lists, emptyList(), emptyList(), emptyList(), fixedNow, utc),
                catalog = BridgeCatalogStub(artists, radios),
                memory = Memory(null),
                scope = this,
                random = Random(seed + 100),
                mood = DjMood.Descubrir,
            )
            dj.startFrom(Track("u-1", "Urbano 1", listOf("Duki"), listOf("duki"), seconds = 190), playSeedFirst = true)
            dj.settle()
            repeat(6) {
                val next = dj.next() ?: return@repeat
                val meta = dj.picks.last()
                if (meta.kind == Kind.NEW || meta.kind == Kind.BRIDGE) {
                    firstNewTracks++
                    if (next.id == "strong-hit" || meta.track.artistId == "top-new") strongTopWins++
                    return@repeat
                }
            }
        }
        assertTrue("Expected at least some discovery picks in Descubrir mood", firstNewTracks >= 4)
        assertTrue("Consensus + top-rank + radio hit should dominate discovery ($strongTopWins/$firstNewTracks)", strongTopWins * 2 >= firstNewTracks)
    }

    @Test
    fun prepareNextUpPreservesTrailingQueueAcrossFullListenConsumeAndFastSkip() = runBlocking {
        val profile = Profile(twoWorldsLists(), emptyList(), emptyList(), emptyList(), fixedNow, utc)
        val memory = Memory(file = null)
        val dj = Dj(profile, BridgeCatalogStub(), memory, this, Random(21), mood = DjMood.Auto)
        val seed = Track("u-1", "Urbano Hit 1", listOf("Duki"), listOf("duki"), seconds = 190)
        dj.startFrom(seed, playSeedFirst = true)
        dj.settle()

        val initialQueue = dj.prepareNextUp(3)
        assertEquals(3, initialQueue.size)
        val expectedSecondId = initialQueue[1].track.id
        val expectedThirdId = initialQueue[2].track.id

        // Simulamos exactamente el orden real de callbacks al completar una canción en PlaybackService:
        // onPositionDiscontinuity -> logPlay -> dj.heard(FullListen) BEFORE onMediaItemTransition -> consumeUpcomingThrough.
        dj.heard(seed.id, 185_000L, 190_000L)
        val consumed = dj.consumeUpcomingThrough(initialQueue[0].track.id)
        assertNotNull(consumed)
        assertEquals(initialQueue[0].track.id, consumed!!.id)

        val afterAdvance = dj.prepareNextUp(3)
        assertEquals(3, afterAdvance.size)
        assertEquals("Slot #2 must shift cleanly into #1 without changing", expectedSecondId, afterAdvance[0].track.id)
        assertEquals("Slot #3 must shift cleanly into #2 without changing", expectedThirdId, afterAdvance[1].track.id)
        assertFalse("Already consumed track must not reappear immediately", afterAdvance.any { it.track.id == consumed.id })

        // Un salto rápido o una escucha completa adicional NO deben regenerar los huecos ya mostrados en cola.
        dj.heard(consumed.id, 5_000L, 190_000L)
        val afterFastSkip = dj.prepareNextUp(3)
        assertEquals(afterAdvance.map { it.track.id }, afterFastSkip.map { it.track.id })
    }

    @Test
    fun familiarSelectionPrefersBeatCompatibleBpmCandidates() = runBlocking {
        val compSongs = (1..4).map { i -> Heard("comp-$i", "Comp $i", "Duki", null, "duki") }
        val farSongs = (1..4).map { i -> Heard("far-$i", "Far $i", "Duki", null, "duki") }
        val seedTrack = Heard("seed-bpm", "Seed 120", "Quevedo", null, "quevedo")
        val lists = listOf(LocalPlaylist("pl-bpm", "BPM Test", listOf(seedTrack) + compSongs + farSongs))

        fun fakeMetrics(id: String, bpm: Double) = AudioMetrics(
            trackId = id,
            bpm = bpm,
            bpmConfidence = 0.85,
            beatPhaseMs = 0L,
            downbeatPhaseMs = 0L,
            rmsDb = -10.0,
            integratedLufs = -10.0,
            keyName = "A min",
            camelot = "8A",
            keyConfidence = 0.8,
            beatStrength = 0.72,
            firstSoundMs = 200L,
            lastSoundMs = 179_000L,
            outroStartMs = 179_000L,
            durationMs = 180_000L,
            beatGrid = (0L..20_000L step 500L).toList(),
            analyzedMs = 180_000L,
        )

        val cache = AudioAnalysisCache(file = null).apply {
            put(fakeMetrics("seed-bpm", 120.0))
            compSongs.forEach { put(fakeMetrics(it.id, 121.0)) }
            farSongs.forEach { put(fakeMetrics(it.id, 165.0)) }
        }

        var compWins = 0
        var farWins = 0
        repeat(16) { runSeed ->
            val dj = Dj(
                profile = Profile(lists, emptyList(), emptyList(), emptyList(), fixedNow, utc),
                catalog = BridgeCatalogStub(),
                memory = Memory(null),
                scope = this,
                random = Random(300 + runSeed),
                mood = DjMood.Energia,
                analysisCache = cache,
            )
            dj.startFrom(Track("seed-bpm", "Seed 120", listOf("Quevedo"), listOf("quevedo"), seconds = 180), playSeedFirst = true)
            dj.settle()
            val pick = dj.next()
            assertNotNull("Expected DJ to pick a track from shared playlist", pick)
            if (pick != null) {
                if (pick.id.startsWith("comp-")) compWins++
                if (pick.id.startsWith("far-")) farWins++
            }
        }
        assertTrue("Expected beat-compatible 121 BPM tracks to strongly beat 165 BPM tracks ($compWins vs $farWins)", compWins > farWins)
    }
}
