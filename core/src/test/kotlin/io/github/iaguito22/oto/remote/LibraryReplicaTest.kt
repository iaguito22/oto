package io.github.iaguito22.oto.remote

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iaguito22.oto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class LibraryReplicaTest {
    @Test fun concurrentSongsMergeAndDeletionSurvivesReconnect()=runBlocking {
        val dir=Files.createTempDirectory("oto-replica").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        fun prefs(name:String)=Prefs(PreferenceDataStoreFactory.create(scope=scope,produceFile={File(dir,"$name.preferences_pb")}),PreferenceDataStoreFactory.create(scope=scope,produceFile={File(dir,"$name-lists.preferences_pb")}))
        val a=prefs("a");val b=prefs("b")
        val ra=LibraryReplica(a,File(dir,"a.json"));val rb=LibraryReplica(b,File(dir,"b.json"))
        try {
            val list=a.createPlaylist("Compartida",Heard("one","Uno","Artista",null))
            rb.merge(ra.export());ra.merge(rb.export())
            a.addToPlaylist(list,Heard("two","Dos","Artista",null))
            b.addToPlaylist(list,Heard("three","Tres","Artista",null))
            val ea=ra.export();val eb=rb.export();ra.merge(eb);rb.merge(ea)
            assertEquals(setOf("one","two","three"),a.playlists.first().single().songs.map{it.id}.toSet())
            assertEquals(a.playlists.first(),b.playlists.first())
            a.removeFromPlaylist(list,"one");rb.merge(ra.export());ra.merge(rb.export())
            assertFalse(b.playlists.first().single().songs.any{it.id=="one"})
            assertEquals(ra.digest(),rb.digest())
            val restarted=LibraryReplica(a,File(dir,"a.json"));assertEquals(ra.digest(),restarted.digest())
        } finally {scope.cancel()}
    }
    @Test fun receivedSnapshotDoesNotOverwriteAnEditMadeDuringReception()=runBlocking {
        val dir=Files.createTempDirectory("oto-replica-cas").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val prefs=Prefs(PreferenceDataStoreFactory.create(scope=scope,produceFile={File(dir,"main.preferences_pb")}),PreferenceDataStoreFactory.create(scope=scope,produceFile={File(dir,"lists.preferences_pb")}))
        try {
            val id=prefs.createPlaylist("Antes")
            val baseline=prefs.linkedLibrarySnapshot()
            prefs.renamePlaylist(id,"Edición local reciente")
            val accepted=prefs.applyLinkedLibrary(baseline,baseline.lists,emptyList(),emptySet(),emptySet(),emptySet())
            assertFalse(accepted)
            assertEquals("Edición local reciente",prefs.linkedLibrarySnapshot().lists.single().name)
        } finally {scope.cancel()}
    }

}
