package io.github.iaguito22.oto

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaylistModePersistenceTest {
    @Test fun modesSurvivePreferenceRecreationAndFollowListAndFolderChanges() = runBlocking {
        val dir = Files.createTempDirectory("oto-playlist-modes").toFile()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun preferences() = Prefs(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "main.preferences_pb") }),
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "lists.preferences_pb") }),
        )
        try {
            var prefs = preferences()
            val id = prefs.createPlaylist("Lista")
            prefs.createFolder("Antes")
            prefs.setPlaylistMode(id, PlaylistMode(true, LoopMode.One))
            prefs.setPlaylistMode("folder:Antes", PlaylistMode(false, LoopMode.All))
            assertEquals(PlaylistMode(true, LoopMode.One), prefs.playlistModes.first()[id])
            prefs.renameFolder("Antes", "Después")
            assertFalse(prefs.playlistModes.first().containsKey("folder:Antes"))
            assertEquals(PlaylistMode(false, LoopMode.All), prefs.playlistModes.first()["folder:Después"])
            prefs.deletePlaylist(id)
            assertFalse(prefs.playlistModes.first().containsKey(id))
            scope.coroutineContext[Job]?.cancelAndJoin()
            // Una nueva instancia usa los mismos archivos, como tras reiniciar el proceso.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            prefs = preferences()
            assertEquals(PlaylistMode(false, LoopMode.All), prefs.playlistModes.first()["folder:Después"])
        } finally { scope.cancel() }
    }
}
