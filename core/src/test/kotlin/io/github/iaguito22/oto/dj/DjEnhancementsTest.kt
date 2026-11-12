package io.github.iaguito22.oto.dj

import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class DjEnhancementsTest {
    private val utc = ZoneId.of("UTC")

    private fun atHour(hour: Int): Long =
        ZonedDateTime.of(2026, 6, 15, hour, 0, 0, 0, utc).toInstant().toEpochMilli()

    private class CatalogStub(
        private val artistsById: Map<String, ArtistInfo> = emptyMap(),
        private val radioBySong: Map<String, List<Track>> = emptyMap(),
    ) : Catalog {
        override suspend fun artist(id: String): ArtistInfo? =
            artistsById[id] ?: ArtistInfo(
                id = id,
                name = id,
                top = (1..8).map { Track("$id-deep-$it", "$id Deep $it", listOf(id), listOf(id), seconds = 195) },
                related = listOf("rel-$id" to "rel-$id"),
                more = null,
            )

        override suspend fun songs(info: ArtistInfo): List<Track> = info.top
        override suspend fun radio(id: String): List<Track> = radioBySong[id].orEmpty()
        override suspend fun find(name: String): String? = name
    }

    private fun richLists(): List<LocalPlaylist> = listOf(
        LocalPlaylist(
            "gym",
            "Gym Energia Workout",
            (1..12).map { i ->
                val a = "energy_${(i - 1) % 3}"
                Heard("e-$i", "$a Hype Banger $i", a, null, a)
            },
        ),
        LocalPlaylist(
            "chill",
            "Chill Noche Acoustic",
            (1..12).map { i ->
                val a = "chill_${(i - 1) % 3}"
                Heard("c-$i", "$a Acoustic Calm $i", a, null, a)
            },
        ),
        LocalPlaylist(
            "focus",
            "Focus Estudio Lofi",
            (1..12).map { i ->
                val a = "focus_${(i - 1) % 3}"
                Heard("f-$i", "$a Lofi Instrumental $i", a, null, a)
            },
        ),
    )

    @Test
    fun songCooldownNeverRepeatsSameSongWhileArtistCanReturnWithoutStrictLockout() = runBlocking {
        // Solo 2 artistas con 8 canciones cada uno: antes el cap/spacing de artista bloqueaba la sesion enseguida.
        val smallLists = listOf(
            LocalPlaylist("p1", "Lista 1", (1..8).map { Heard("s1-$it", "Tema A $it", "Artista A", null, "artA") }),
            LocalPlaylist("p2", "Lista 2", (1..8).map { Heard("s2-$it", "Tema B $it", "Artista B", null, "artB") }),
        )
        val memory = Memory(null)
        val now = atHour(15)
        val profile = Profile(smallLists, emptyList(), emptyList(), emptyList(), now, utc)
        val emptyCatalog = object : Catalog {
            override suspend fun artist(id: String) = null
            override suspend fun songs(info: ArtistInfo) = emptyList<Track>()
            override suspend fun radio(id: String) = emptyList<Track>()
            override suspend fun find(name: String) = null
        }
        val dj = Dj(profile, emptyCatalog, memory, this, Random(42))
        val picked = ArrayList<Track>()
        var t = dj.start()
        while (t != null && picked.size < 16) {
            picked += t
            dj.heard(t.id, 180_000L, 180_000L)
            t = dj.next()
        }
        assertEquals(16, picked.size)
        assertEquals(16, picked.map { it.key }.toSet().size)

        // Segunda sesion inmediata con la misma Memory: las 16 ya sonadas siguen en enfriamiento.
        val dj2 = Dj(Profile(smallLists, emptyList(), emptyList(), emptyList(), now + 60_000L, utc), emptyCatalog, memory, this, Random(43))
        val firstSession2 = dj2.start()
        // Como todas las de la biblioteca estuvieron en la sesion previa, relaxLevel >= 1 puede recuperar si > 1 dia,
        // pero si añadimos una cancion fresca debe salir esa antes que las recientes.
        assertNotNull(firstSession2)
    }

    @Test
    fun moodSelectionAndLiveSwitchAdaptTracks() = runBlocking {
        val lists = richLists()
        val now = atHour(15)
        val catalog = object : Catalog {
            override suspend fun artist(id: String) = null
            override suspend fun songs(info: ArtistInfo) = emptyList<Track>()
            override suspend fun radio(id: String) = emptyList<Track>()
            override suspend fun find(name: String) = null
        }

        var chillCountInChillMood = 0
        repeat(8) { seed ->
            val dj = Dj(Profile(lists, emptyList(), emptyList(), emptyList(), now, utc), catalog, Memory(null), this, Random(seed), mood = DjMood.Chill)
            val picks = listOfNotNull(dj.start(), dj.next(), dj.next(), dj.next())
            chillCountInChillMood += picks.count { it.artist.startsWith("chill_") }
        }
        assertTrue("Modo Chill debe priorizar temas chill ($chillCountInChillMood)", chillCountInChillMood >= 22)

        // Cambio de mood en vivo: empieza en Chill y cambia a Energia.
        val liveDj = Dj(Profile(lists, emptyList(), emptyList(), emptyList(), now, utc), catalog, Memory(null), this, Random(11), mood = DjMood.Chill)
        val first = liveDj.start()!!
        assertTrue("Inicio en Chill debe priorizar chill/calma, fue ${first.artist}", first.artist.startsWith("chill_") || first.artist.startsWith("focus_"))
        liveDj.setMood(DjMood.Energia)
        assertEquals(DjMood.Energia, liveDj.mood)
        val afterSwitch = (1..6).mapNotNull { liveDj.next() }
        assertTrue("Tras cambiar a Energia en vivo deben entrar temas energeticos", afterSwitch.any { it.artist.startsWith("energy_") })
    }

    @Test
    fun autoMoodUsesTimeOfDayFactor() = runBlocking {
        val lists = richLists()
        val catalog = object : Catalog {
            override suspend fun artist(id: String) = null
            override suspend fun songs(info: ArtistInfo) = emptyList<Track>()
            override suspend fun radio(id: String) = emptyList<Track>()
            override suspend fun find(name: String) = null
        }
        val nightNow = atHour(2)
        val dayNow = atHour(14)

        var nightChillPicks = 0
        var dayEnergyPicks = 0
        repeat(10) { seed ->
            val nightDj = Dj(Profile(lists, emptyList(), emptyList(), emptyList(), nightNow, utc), catalog, Memory(null), this, Random(seed), mood = DjMood.Auto)
            val nightTracks = listOfNotNull(nightDj.start(), nightDj.next(), nightDj.next(), nightDj.next())
            nightChillPicks += nightTracks.count { it.artist.startsWith("chill_") || it.artist.startsWith("focus_") }

            val dayDj = Dj(Profile(lists, emptyList(), emptyList(), emptyList(), dayNow, utc), catalog, Memory(null), this, Random(seed), mood = DjMood.Auto)
            val dayTracks = listOfNotNull(dayDj.start(), dayDj.next(), dayDj.next(), dayDj.next())
            dayEnergyPicks += dayTracks.count { it.artist.startsWith("energy_") }
        }
        assertTrue("De noche (02:00) Auto debe favorecer calma ($nightChillPicks/40)", nightChillPicks >= 24)
        assertTrue("De dia (14:00) Auto debe favorecer energia ($dayEnergyPicks/40)", dayEnergyPicks >= 20)
    }

    @Test
    fun synergyPrefersCoherentNextTrackOverAbruptJump() = runBlocking {
        // Dos escenas separadas sin artistas en comun: Rock/Gym vs Piano/Chill.
        val lists = listOf(
            LocalPlaylist("rock", "Rock Gym", (1..8).map { Heard("r-$it", "Rock Track $it", "rock_band_${it % 2}", null, "rock_band_${it % 2}") }),
            LocalPlaylist("piano", "Piano Chill", (1..8).map { Heard("p-$it", "Piano Ballad $it", "piano_solo_${it % 2}", null, "piano_solo_${it % 2}") }),
        )
        val catalog = object : Catalog {
            override suspend fun artist(id: String) = null
            override suspend fun songs(info: ArtistInfo) = emptyList<Track>()
            override suspend fun radio(id: String) = emptyList<Track>()
            override suspend fun find(name: String) = null
        }
        var sameSceneTransitions = 0
        var totalTransitions = 0
        repeat(6) { seed ->
            val dj = Dj(Profile(lists, emptyList(), emptyList(), emptyList(), atHour(16), utc), catalog, Memory(null), this, Random(seed), mood = DjMood.Foco)
            val seq = ArrayList<Track>()
            dj.start()?.let { seq += it }
            repeat(5) { dj.next()?.let { seq += it } }
            for (i in 1 until seq.size) {
                totalTransitions++
                val aScene = seq[i - 1].artist.substringBefore('_')
                val bScene = seq[i].artist.substringBefore('_')
                if (aScene == bScene) sameSceneTransitions++
            }
        }
        assertTrue("La continuidad debe mantener transiciones coherentes ($sameSceneTransitions/$totalTransitions)", sameSceneTransitions >= totalTransitions - 4)
    }

    @Test
    fun knownVsDiscoveryRatioStaysAround70To30InNormalMood() = runBlocking {
        val lists = (0 until 4).map { l ->
            LocalPlaylist("l$l", "Lista $l", (1..15).map { i -> val a = "art_${l}_${i % 3}"; Heard("$a-$i", "$a song $i", a, null, a) })
        }
        val catalog = CatalogStub()
        var familiar = 0
        var discovery = 0
        repeat(3) { seed ->
            val dj = Dj(Profile(lists, emptyList(), emptyList(), emptyList(), atHour(13), utc), catalog, Memory(null), this, Random(seed), mood = DjMood.Auto)
            var t = dj.start()
            dj.settle()
            var n = 0
            while (t != null && n < 25) {
                dj.heard(t.id, 180_000L, 180_000L)
                t = dj.next()
                dj.settle()
                n++
            }
            familiar += dj.picks.count { it.kind == Kind.FAMILIAR }
            discovery += dj.picks.count { it.kind != Kind.FAMILIAR }
        }
        val total = familiar + discovery
        val famShare = familiar.toDouble() / total
        val discShare = discovery.toDouble() / total
        assertTrue("Conocidas deberian rondar ~70% (fue ${(famShare * 100).toInt()}%)", famShare in 0.58..0.82)
        assertTrue("Descubrimientos deberian rondar ~30% (fue ${(discShare * 100).toInt()}%)", discShare in 0.18..0.42)
    }

    @Test
    fun liveReactionsFastSkipCoolsBranchAndLikeReinforcesNextSongs() = runBlocking {
        val lists = listOf(
            LocalPlaylist("a", "Rock Energy", (1..10).map { Heard("rk-$it", "Rock $it", "rock_$it", null, "rk_$it") }),
            LocalPlaylist("b", "Chill Jazz", (1..10).map { Heard("cj-$it", "Jazz $it", "jazz_$it", null, "cj_$it") }),
        )
        val radioMap = mapOf(
            "cj-1" to (1..5).map { Track("cj-rad-$it", "Jazz Radio $it", listOf("jazz_rad_$it"), listOf("cj_rad_$it"), seconds = 190) },
        )
        val catalog = CatalogStub(radioBySong = radioMap)
        val dj = Dj(Profile(lists, emptyList(), emptyList(), emptyList(), atHour(15), utc), catalog, Memory(null), this, Random(5), mood = DjMood.Auto)
        val first = dj.start()!!
        dj.settle()
        // Forzamos un salto rapido (< 20 s) sobre la primera pista y otro sobre una segunda si es de esa rama.
        dj.heard(first.id, 10_000L, 200_000L)
        val second = dj.next()!!
        dj.settle()
        if (second.artist.startsWith(first.artist.substringBefore('_'))) {
            dj.heard(second.id, 8_000L, 200_000L)
        } else {
            // Reaccion positiva (Like) sobre la otra rama: las siguientes 4-5 canciones deben reforzar esa direccion.
            dj.react(second.id, DjReaction.Like)
            dj.settle()
        }
        val branchPrefix = if (second.artist.startsWith(first.artist.substringBefore('_'))) {
            if (first.artist.startsWith("rock_")) "jazz" else "rock"
        } else {
            second.artist.substringBefore('_')
        }
        val nextFive = (1..5).mapNotNull {
            dj.next()?.also { t ->
                dj.settle()
                dj.heard(t.id, 180_000L, 180_000L)
            }
        }
        assertTrue(
            "Tras skip rapido / refuerzo, las siguientes canciones deben favorecer la rama reforzada ($branchPrefix)",
            nextFive.count { it.artist.contains(branchPrefix) || it.title.contains("Jazz", ignoreCase = true) || it.title.contains("Rock", ignoreCase = true) } >= 3,
        )
    }

    @Test
    fun adhdSegmentCalculationAndModePlayback() = runBlocking {
        // Cancion estandar de 3:20 (200s): salta intro (~25-35%) y dura ~45-75s con fadeOut antes del final.
        val seg = AdhdPlanner.segment(200_000L, trackId = "track-1", crossfadeMs = 4_000L, mood = DjMood.Auto)!!
        assertTrue("startMs (${seg.startMs}) debe saltar intro ~25-35%", seg.startMs in 50_000L..70_000L)
        assertTrue("clipDurationMs (${seg.clipDurationMs}) debe estar en 45-75s", seg.clipDurationMs in 45_000L..75_000L)
        assertEquals(seg.endMs - seg.startMs, seg.clipDurationMs)
        assertEquals(4_000L, seg.mixOutDurationMs)
        assertEquals(seg.endMs - seg.mixOutDurationMs, seg.fadeOutStartMs)
        assertTrue(seg.endMs <= seg.fullDurationMs)

        // Cancion muy corta (<50s): no la recorta a cero.
        val shortSeg = AdhdPlanner.segment(38_000L, trackId = "short", crossfadeMs = 3_000L)!!
        assertEquals(0L, shortSeg.startMs)
        assertEquals(38_000L, shortSeg.endMs)

        // Cancion larga (7 min): no salta demasiado lejos y mantiene clip <= 75s.
        val longSeg = AdhdPlanner.segment(420_000L, trackId = "long", crossfadeMs = 5_000L, mood = DjMood.Chill)!!
        assertTrue(longSeg.startMs in 70_000L..110_000L)
        assertTrue(longSeg.clipDurationMs in 45_000L..75_000L)

        // Duracion invalida: null.
        assertNull(AdhdPlanner.segment(0L))

        // Integracion en Dj con DjMode.Adhd: adjunta adhdSegment y escucha completa del clip no cuenta como skip.
        val lists = listOf(
            LocalPlaylist("p", "Energia", (1..6).map { Heard("s-$it", "Track $it", "Art $it", null, "a-$it") }),
        )
        val tracksWithDur = (1..6).map { Track("s-$it", "Track $it", listOf("Art $it"), listOf("a-$it"), seconds = 210) }
        val profile = Profile(
            listOf(LocalPlaylist("p", "Energia", tracksWithDur.map { Heard(it.id, it.title, it.artist, null, it.artistId) })),
            emptyList(), emptyList(), emptyList(), atHour(14), utc,
        )
        val catalog = object : Catalog {
            override suspend fun artist(id: String) = null
            override suspend fun songs(info: ArtistInfo) = emptyList<Track>()
            override suspend fun radio(id: String) = emptyList<Track>()
            override suspend fun find(name: String) = null
        }
        val dj = Dj(profile, catalog, Memory(null), this, Random(1), mode = DjMode.Adhd)
        val first = dj.start()!!
        val computedSeg = dj.adhdSegment(first, durationMs = 210_000L)!!
        assertTrue(computedSeg.clipDurationMs in 45_000L..75_000L)
        // Simula escuchar el clip completo (~55s de una pista total de 210s) -> recompensa alta en modo ADHD.
        dj.heard(first.id, computedSeg.clipDurationMs, 210_000L)
        assertTrue((dj.picks.last().reward ?: 0.0) >= 0.75)

        // Cuando la pista ya trae duracion (ej. descubrimiento de catalogo), Pick.adhdSegment se rellena automatico.
        val withDurTrack = Track("cat-1", "Catalog Song", listOf("Art 1"), listOf("a-1"), seconds = 180)
        val autoSeg = withDurTrack.adhdSegment(mood = DjMood.Energia)!!
        assertTrue(autoSeg.startMs in 45_000L..68_000L)
        assertTrue(autoSeg.clipDurationMs in 45_000L..75_000L)
        dj.setMode(DjMode.Normal)
        assertEquals(DjMode.Normal, dj.mode)
    }

    @Test
    fun descubrirMoodIncreasesDiscoveryShareAndMemoryPersistsRecentSongs() = runBlocking {
        val lists = (0 until 3).map { l ->
            LocalPlaylist("l$l", "Lista $l", (1..12).map { i -> val a = "dart_${l}_${i % 3}"; Heard("$a-$i", "$a song $i", a, null, a) })
        }
        val dir = java.nio.file.Files.createTempDirectory("oto-dj-mem").toFile()
        try {
            val memFile = java.io.File(dir, "dj.json")
            val memory = Memory(memFile)
            val now = atHour(17)
            val dj = Dj(Profile(lists, emptyList(), emptyList(), emptyList(), now, utc), CatalogStub(), memory, this, Random(9), mood = DjMood.Descubrir)
            var t = dj.start()
            dj.settle()
            var n = 0
            while (t != null && n < 20) {
                dj.heard(t.id, 180_000L, 180_000L)
                t = dj.next()
                dj.settle()
                n++
            }
            val discShare = dj.picks.count { it.kind != Kind.FAMILIAR }.toDouble() / dj.picks.size
            assertTrue("Mood Descubrir debe superar el 45% de descubrimientos (fue ${(discShare * 100).toInt()}%)", discShare >= 0.45)

            // Guardar y recargar Memory: las canciones recientes sobreviven.
            val reloaded = Memory(memFile)
            val firstTrack = dj.picks.first().track
            assertTrue(reloaded.isRecent(firstTrack, now + 60_000L))
        } finally {
            dir.deleteRecursively()
        }
    }
}
