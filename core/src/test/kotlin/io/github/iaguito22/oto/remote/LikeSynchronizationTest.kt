package io.github.iaguito22.oto.remote

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.account.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LikeSynchronizationTest {
    private val song = Heard("song", "Canción", "Artista", null)
    private val other = Heard("other", "Otra", "Artista", null)

    private class Devices {
        val dir = Files.createTempDirectory("oto-likes-sync").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun prefs(name: String) = Prefs(
            PreferenceDataStoreFactory.create(scope=scope,produceFile={File(dir,"$name.preferences_pb")}),
            PreferenceDataStoreFactory.create(scope=scope,produceFile={File(dir,"$name-lists.preferences_pb")}),
        )
        val a = prefs("a")
        val b = prefs("b")
        fun replica(prefs: Prefs, name: String) = LibraryReplica(prefs, File(dir,"$name.json"))
        var ra = replica(a,"a")
        var rb = replica(b,"b")
        suspend fun sync() {
            val ea = ra.export(); val eb = rb.export()
            ra.merge(eb); rb.merge(ea)
            assertEquals(a.liked.first(), b.liked.first())
            assertEquals(ra.digest(), rb.digest())
        }
        suspend fun close() { scope.coroutineContext[Job]!!.cancelAndJoin(); dir.deleteRecursively() }
    }

    @Test fun firstConnectionUnitesOtoLikesWithoutDuplicatesOrYoutubeHearts() = runBlocking {
        val d = Devices()
        try {
            d.a.setLiked(song,true)
            d.b.setLikedAll(listOf(song,other),true)
            importCloudPlaylist(d.a,LocalPlaylist(YOUTUBE_LIKES_LIST_ID,"YouTube",listOf(Heard("yt","Solo YouTube","Artista",null))))
            d.sync()
            assertEquals(setOf("song","other"),d.a.liked.first().map { it.id }.toSet())
            assertEquals(2,d.b.liked.first().size)
            assertEquals(listOf("yt"),d.b.playlists.first().single().songs.map { it.id })
            d.a.toggleLike(song)
            d.sync()
            assertEquals(listOf("other"),d.b.liked.first().map { it.id })
            assertEquals(listOf("yt"),d.a.playlists.first().single().songs.map { it.id })
        } finally { d.close() }
    }

    @Test fun deletionSurvivesRestartStaleSnapshotAndOldOtoBackup() = runBlocking {
        val d = Devices()
        try {
            d.a.setLiked(song,true); d.sync()
            val stale = d.rb.export()
            d.a.setLiked(song,false)
            // No export between touching the heart and restarting the replica.
            d.ra = d.replica(d.a,"a")
            d.sync()
            d.rb = d.replica(d.b,"b")
            d.rb.merge(stale)
            importCloudPlaylist(d.b,LocalPlaylist(LIKED_ID,"Me gusta de Oto",listOf(song)))
            d.sync()
            assertTrue(d.a.liked.first().isEmpty())
            // A new explicit heart can restore it on both devices.
            d.b.setLiked(song,true); d.sync()
            assertEquals(listOf(song),d.a.liked.first())
        } finally { d.close() }
    }

    @Test fun explicitRemovalBeforeFirstConnectionIsNotLostEvenWhenAlreadyAbsent() = runBlocking {
        val d = Devices()
        try {
            d.a.setLiked(song,true)
            delay(10)
            d.b.setLiked(song,false)
            d.sync()
            assertTrue(d.a.liked.first().isEmpty())
        } finally { d.close() }
    }

    @Test fun reconnectOrderDoesNotChangeWhichOfflineDecisionWins() = runBlocking {
        val d = Devices()
        try {
            d.a.setLiked(song,true); d.sync()
            d.a.setLiked(song,false)
            delay(10)
            d.b.setLiked(song,true)
            // Capture the later action first, the earlier action last.
            val later = d.rb.export()
            val earlier = d.ra.export()
            d.ra.merge(later); d.rb.merge(earlier)
            assertEquals(listOf(song),d.a.liked.first())
            d.sync()
        } finally { d.close() }
    }

    @Test fun backupRestoredOfflineCannotOverrideDeletionReceivedLater() = runBlocking {
        val d = Devices()
        try {
            d.a.setLiked(song,true)
            d.a.setLiked(song,false)
            importCloudPlaylist(d.b,LocalPlaylist(LIKED_ID,"Me gusta de Oto",listOf(song)))
            d.sync()
            assertTrue(d.b.liked.first().isEmpty())
        } finally { d.close() }
    }

    @Test fun receivingSnapshotRejectsRemovalMadeWhenSongWasAlreadyAbsent() = runBlocking {
        val d = Devices()
        try {
            val expected = d.a.linkedLibrarySnapshot()
            d.a.setLiked(song,false)
            assertFalse(d.a.applyLinkedLibrary(expected,emptyList(),listOf(song),emptySet(),emptySet(),emptySet()))
            assertTrue(d.a.liked.first().isEmpty())
        } finally { d.close() }
    }
}
