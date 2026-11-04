package io.github.iaguito22.oto.desktop.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.dj.Plays
import io.github.iaguito22.oto.dj.YouTubeCatalog
import io.github.iaguito22.oto.lyrics.LyricsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopQueueTest {
    private fun withPlayer(test: (DesktopPlayerConnection) -> Unit) {
        val root = Files.createTempDirectory("oto-queue").toFile()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        OtoDirs.init(root, File(root, "cache"))
        val prefs = Prefs(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "prefs.preferences_pb") }),
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "lists.preferences_pb") }),
        )
        val player = DesktopPlayerConnection(prefs, Plays(File(root, "plays")), YouTubeCatalog(File(root, "dj")),
            LyricsStore(File(root, "lyrics")), DesktopDownloads(prefs, scope), scope)
        try { test(player) } finally { scope.cancel(); root.deleteRecursively() }
    }
    private fun song(id: String) = Heard(id, id, "Prueba", null).toSong()

    @Test fun addingToEndAndNextKeepsCurrentTrack() = withPlayer { player ->
        player.prepareTransfer(listOf(song("a"), song("b")), 0, 0, false)
        player.enqueue(song("c"))
        player.playNext(song("d"))
        assertEquals(listOf("a", "d", "b", "c"), player.queue.value.map { it.id })
        assertEquals(0, player.state.value.index)
        player.setShuffle(true); player.setShuffle(false)
        assertEquals(setOf("a", "b", "c", "d"), player.queue.value.map { it.id }.toSet())
    }

    @Test fun removingCurrentAdvancesAndRemovingLastClearsPlayback() = withPlayer { player ->
        player.prepareTransfer(listOf(song("a"), song("b"), song("c")), 1, 0, false)
        player.remove(1)
        assertEquals(listOf("a", "c"), player.queue.value.map { it.id })
        assertEquals(1, player.state.value.index)
        assertEquals("a", player.previousEntry.value?.id)
        player.remove(1)
        assertEquals(0, player.state.value.index)
        player.remove(0)
        assertTrue(player.queue.value.isEmpty())
        assertNull(player.state.value.id)
        assertFalse(player.state.value.playing)
    }

    @Test fun movingCurrentAndRemovingPreviousKeepPositionConsistent() = withPlayer { player ->
        player.prepareTransfer(listOf(song("a"), song("b"), song("c")), 1, 0, false)
        player.move(1, 2)
        assertEquals(2, player.state.value.index)
        assertEquals("c", player.previousEntry.value?.id)
        player.remove(0)
        assertEquals(1, player.state.value.index)
        player.clearQueue()
        player.setShuffle(true); player.setShuffle(false)
        assertEquals(listOf("b"), player.queue.value.map { it.id })
        assertEquals(0, player.state.value.upNext)
    }

    @Test fun eachListRemembersItsShuffleLikeMobile() = withPlayer { player ->
        val list = LocalPlaylist("L", "Lista", (1..8).map { Heard("s$it", "s$it", "Artista $it", null) })
        player.playLocal(list, null, false)
        // Lista nueva: el aleatorio de Ajustes (por defecto, encendido); suena todo, cada una una vez.
        assertTrue(player.shuffle.value)
        assertEquals(list.songs.map { it.id }.toSet(), player.queue.value.map { it.id }.toSet())
        assertEquals(8, player.queue.value.size)
        val current = player.queue.value[player.state.value.index].id
        player.setShuffle(false)
        // Sin aleatorio, lo que queda vuelve a su orden detrás de la actual, y la lista lo recuerda.
        val after = player.queue.value.drop(player.state.value.index + 1).map { it.id }
        assertEquals(list.songs.map { it.id }.filter { it in after }, after)
        assertEquals(current, player.queue.value[player.state.value.index].id)
        assertEquals(false, player.modes.value["L"]?.shuffle)
        player.playLocal(list, 2, false)
        assertFalse(player.shuffle.value)
        assertEquals(list.songs.map { it.id }, player.queue.value.map { it.id })
        assertEquals(2, player.state.value.index)
        // Otra lista nueva sigue con el valor de Ajustes, no con el de la anterior.
        player.playLocal(LocalPlaylist("M", "Otra", list.songs), null, false)
        assertTrue(player.shuffle.value)
    }

    @Test fun loopCyclesThroughListAndSongAndIsRememberedPerList() = withPlayer { player ->
        val list = LocalPlaylist("L", "Lista", listOf(Heard("a", "a", "x", null), Heard("b", "b", "y", null)))
        player.playLocal(list, null, false)
        assertEquals(LoopMode.Off, player.loopMode.value)
        player.cycleLoop(); assertEquals(LoopMode.All, player.loopMode.value); assertTrue(player.loop.value)
        player.cycleLoop(); assertEquals(LoopMode.One, player.loopMode.value)
        assertEquals(LoopMode.One, player.modes.value["L"]?.loop)
        player.playLocal(LocalPlaylist("M", "Otra", list.songs), null, false)
        assertEquals(LoopMode.Off, player.loopMode.value)
        player.playLocal(list, null, false)
        assertEquals(LoopMode.One, player.loopMode.value)
        player.cycleLoop(); assertEquals(LoopMode.Off, player.loopMode.value); assertFalse(player.loop.value)
    }
}
