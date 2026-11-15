package io.github.iaguito22.oto.desktop.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.dj.ArtistInfo
import io.github.iaguito22.oto.dj.Catalog
import io.github.iaguito22.oto.dj.DjMode
import io.github.iaguito22.oto.dj.Play
import io.github.iaguito22.oto.dj.Plays
import io.github.iaguito22.oto.dj.Track
import io.github.iaguito22.oto.dj.toTrack
import io.github.iaguito22.oto.lyrics.LyricsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
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

class DesktopDjConnectionTest {
    private fun withSeededPlayer(test: suspend (DesktopPlayerConnection, List<SongItem>) -> Unit) {
        val root = Files.createTempDirectory("oto-dj-conn").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        OtoDirs.init(root, File(root, "cache"))
        val prefs = Prefs(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "prefs.preferences_pb") }),
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "lists.preferences_pb") }),
        )
        val plays = Plays(File(root, "plays"))
        val offlineCatalog = object : Catalog {
            override suspend fun artist(id: String): ArtistInfo? = null
            override suspend fun songs(info: ArtistInfo): List<Track> = emptyList()
            override suspend fun radio(id: String): List<Track> = emptyList()
            override suspend fun find(name: String): String? = null
        }
        val player = DesktopPlayerConnection(
            prefs = prefs,
            plays = plays,
            djCatalog = offlineCatalog,
            lyricsStore = LyricsStore(File(root, "lyrics")),
            downloads = DesktopDownloads(prefs, scope),
            scope = scope,
        )
        try {
            runBlocking {
                val now = System.currentTimeMillis()
                val rock = (1..9).map { idx ->
                    Heard("rock-$idx", "Rock $idx", "Band Rock", null, "art-rock")
                }
                val chill = (1..9).map { idx ->
                    Heard("chill-$idx", "Chill $idx", "Band Chill", null, "art-chill")
                }
                val jazz = (1..6).map { idx ->
                    Heard("jazz-$idx", "Jazz $idx", "Band Jazz", null, "art-jazz")
                }
                prefs.createPlaylist("Rock List", rock)
                prefs.createPlaylist("Chill List", chill)
                prefs.createPlaylist("Jazz List", jazz)
                val genreJson = JSONObject()
                rock.forEach { h ->
                    val t = h.toTrack()
                    genreJson.put("s:${t.key}", JSONArray().put("Rock").put(now))
                    genreJson.put("a:${t.who}", JSONArray().put("Rock").put(now))
                }
                chill.forEach { h ->
                    val t = h.toTrack()
                    genreJson.put("s:${t.key}", JSONArray().put("Chill").put(now))
                    genreJson.put("a:${t.who}", JSONArray().put("Chill").put(now))
                }
                jazz.forEach { h ->
                    val t = h.toTrack()
                    genreJson.put("s:${t.key}", JSONArray().put("Jazz").put(now))
                    genreJson.put("a:${t.who}", JSONArray().put("Jazz").put(now))
                }
                File(root, "dj-genres.json").writeText(genreJson.toString())
                (rock + chill + jazz).forEachIndexed { idx, h ->
                    prefs.setLiked(h, true)
                    prefs.addHeard(h)
                    plays.add(
                        Play(
                            id = h.id,
                            title = h.title,
                            artist = h.artist,
                            artistId = h.artistId,
                            at = now - (idx + 1) * 3_600_000L,
                            ms = 180_000L,
                            dur = 190_000L,
                        ),
                    )
                }
                val songs = (rock + chill + jazz).map { h ->
                    SongItem(
                        id = h.id,
                        title = h.title,
                        artists = listOf(Artist(name = h.artist, id = h.artistId)),
                        duration = 190,
                        thumbnail = "",
                        explicit = false,
                    )
                }
                test(player, songs)
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `playDjFrom starts on seed, populates 3 nextUp picks, and playDjFullOnce clears ADHD segment`() = withSeededPlayer { player, songs ->
        val seed = songs.first()
        var failed = false
        player.playDjFrom(seed, DjMode.Adhd) { failed = true }

        withTimeout(10_000L) {
            player.djActive.first { it }
            player.state.first { it.id == seed.id }
        }
        assertFalse("playDjFrom no debería fallar con perfil local poblado", failed)
        assertFalse("mixing debe empezar en false", player.mixing.value)
        assertEquals(seed.id, player.state.value.id)
        assertEquals(DjMode.Adhd, player.djMode.value)
        assertEquals(3, player.djNextUp.value.size)
        assertEquals("Rock", player.djTanda.value)
        assertNotNull("adhdSegment debe calcularse para pistas con duración en modo ADHD", player.adhdSegment.value)

        // Oir esta entera anula el segmento actual pero mantiene modo ADHD
        player.playDjFullOnce()
        assertTrue(player.djFullOnce.value)
        assertNull(player.adhdSegment.value)
        assertEquals(DjMode.Adhd, player.djMode.value)

        // Al saltar al siguiente tema se restablece djFullOnce y djNextUp avanza
        val nextId = player.djNextUp.value.first().track.id
        player.skipTo(1)
        withTimeout(10_000L) {
            player.state.first { it.id == nextId }
        }
        assertEquals(nextId, player.state.value.id)
        assertFalse(player.djFullOnce.value)
        assertFalse(player.djNextUp.value.any { it.track.id == nextId })
    }

    @Test
    fun `vetoDjNextUp replaces vetoed item and skipDjTanda syncs djTanda with new queue head`() = withSeededPlayer { player, songs ->
        player.playDjFrom(songs.first(), DjMode.Normal) {}
        withTimeout(10_000L) {
            player.djActive.first { it }
            player.state.first { it.id == songs.first().id }
        }
        assertEquals(3, player.djNextUp.value.size)

        val vetoedId = player.djNextUp.value[1].track.id
        player.vetoDjNextUp(vetoedId)
        withTimeout(10_000L) {
            player.djNextUp.first { picks -> picks.size >= 3 && picks.none { it.track.id == vetoedId } }
        }
        val nextUpIds = player.djNextUp.value.map { it.track.id }
        assertFalse("El tema vetado no debe seguir en djNextUp", vetoedId in nextUpIds)
        val futureQueueIds = player.queue.value.drop(player.state.value.index + 1).map { it.id }
        assertEquals(nextUpIds, futureQueueIds.take(nextUpIds.size))

        val beforeTrackId = player.state.value.id
        val tandaBefore = player.djTanda.value
        player.skipDjTanda()
        withTimeout(10_000L) {
            player.state.first { it.id != null && it.id != beforeTrackId }
            player.djNextUp.first { it.size >= 2 }
        }
        val newTrackId = player.state.value.id
        assertNotNull(newTrackId)
        assertNotNull("djTanda debe actualizarse con el género de la nueva tanda", player.djTanda.value)
        assertNotEquals("djTanda debe cambiar de género al saltar ambiente", tandaBefore, player.djTanda.value)
        assertFalse("djTanda no debe incluir 'Como ...'", player.djTanda.value!!.contains("como", ignoreCase = true))
        assertTrue(
            "La cola futura tras skipDjTanda debe reflejar los nuevos nextUp",
            player.queue.value.drop(player.state.value.index + 1).map { it.id }.startsWith(player.djNextUp.value.map { it.track.id }),
        )
    }

    private fun <T> List<T>.startsWith(prefix: List<T>): Boolean =
        size >= prefix.size && subList(0, prefix.size) == prefix
}
