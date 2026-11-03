package io.github.iaguito22.oto.account

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CloudLikesTest {
    @Test fun youtubeLikesAndOtoBackupHaveSeparateDestinations() = runBlocking {
        val dir=Files.createTempDirectory("oto-cloud-likes").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val prefs=Prefs(PreferenceDataStoreFactory.create(scope=scope,produceFile={File(dir,"main.preferences_pb")}),PreferenceDataStoreFactory.create(scope=scope,produceFile={File(dir,"lists.preferences_pb")}))
        val local=Heard("local","Local","Artista",null)
        val youtube=Heard("youtube","YouTube","Artista",null)
        val backup=Heard("backup","Copia","Artista",null)
        try {
            prefs.setLiked(local,true)
            importCloudPlaylist(prefs,LocalPlaylist(YOUTUBE_LIKES_LIST_ID,"Me gusta de YouTube Music",listOf(youtube)))
            assertEquals(listOf("local"),prefs.liked.first().map { it.id })
            assertEquals(listOf("youtube"),prefs.playlists.first().single().songs.map { it.id })
            val metadata=ListMeta(LIKED_ID,null,null)
            assertEquals(LIKED_ID,ListMeta.find(metadata.encode())?.id)
            importCloudPlaylist(prefs,LocalPlaylist(LIKED_ID,"Me gusta de Oto",listOf(backup)))
            assertEquals(setOf("local","backup"),prefs.liked.first().map { it.id }.toSet())
            assertEquals(listOf("youtube"),prefs.playlists.first().single().songs.map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun selectionAllowsOtoBackupButNotExportOfYoutubeLikes() {
        val saved=CloudSelection(setOf("LM"),setOf(LIKED_ID,YOUTUBE_LIKES_LIST_ID,"other"))
        val read=CloudSelection.decode(saved.encode())!!
        assertEquals(setOf("LM"),read.imports)
        assertEquals(setOf(LIKED_ID,"other"),read.exports)
        val legacy=CloudSelection.decode("""{"imports":[],"exports":["liked","other"]}""")!!
        assertEquals(setOf("other"),legacy.exports)
    }
}
