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

    @Test fun playNextMovesExistingSongWithoutDuplicatingIt() = withPlayer { player ->
        player.prepareTransfer(listOf(song("a"), song("b"), song("c")), 0, 0, false)
        player.playNext(song("c"))
        assertEquals(listOf("a", "c", "b"), player.queue.value.map { it.id })
        assertEquals("a", player.queue.value[player.state.value.index].id)
    }

    @Test fun addingKeepsAManualBlockBeforeThePlaylist() = withPlayer { player ->
        player.prepareTransfer(listOf(song("a"), song("b"), song("e")), 0, 0, false)
        player.enqueue(song("c"))
        player.playNext(song("d"))
        player.enqueue(song("e"))
        player.enqueue(song("d"))
        assertEquals(listOf("a", "c", "e", "d", "b"), player.queue.value.map { it.id })
        assertEquals(0, player.state.value.index)
        player.setShuffle(true); player.setShuffle(false)
        assertEquals(listOf("a", "c", "e", "d", "b"), player.queue.value.map { it.id })
        player.prepareTransfer(listOf(song("a"), song("d"), song("b")), 0, 0, false)
        player.enqueue(song("c"))
        assertEquals(listOf("a", "c", "d", "b"), player.queue.value.map { it.id })
    }

    @Test fun transferKeepsTheManualBlockAtItsDestination() = withPlayer { player ->
        player.prepareTransfer(listOf(song("a"), song("b"), song("c")), 0, 0, false, manualIndices = setOf(1))
        player.enqueue(song("d"))
        assertEquals(listOf("a", "b", "d", "c"), player.queue.value.map { it.id })
        assertEquals(listOf("b", "d"), player.queue.value.filter { it.manual }.map { it.id })
    }

    @Test fun transferRestoresRadioFromItsSeed() = withPlayer { player ->
        player.prepareTransfer(listOf(song("a"), song("b")), 0, 0, false, radioSeed = "seed")
        assertEquals("seed", player.radioSeed())
        player.prepareTransfer((0..65).map { song("s$it") }, 15, 0, false, radioSeed = "seed")
        player.move(15, 44)
        assertEquals(15, player.state.value.index)
        assertEquals((0..65).map { "s$it" }, player.queue.value.map { it.id })
        player.prepareTransfer(listOf(song("c"), song("d")), 0, 0, false)
        assertNull(player.radioSeed())
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

    @Test fun playedManualEntryDoesNotMarkAnotherCopy() = withPlayer { player ->
        player.prepareTransfer(listOf(song("a"), song("b"), song("c"), song("b")), 0, 0, false, manualIndices = setOf(1))
        assertEquals(listOf(1), player.queue.value.filter { it.manual }.map { it.index })
        player.skipTo(1)
        assertTrue(player.queue.value.none { it.manual })
        player.enqueue(song("d"))
        assertEquals(listOf("a", "b", "d", "c", "b"), player.queue.value.map { it.id })
        player.skipTo(0)
        assertTrue(player.queue.value.none { it.manual })
    }

    @Test fun movingCurrentAndRemovingPreviousKeepPositionConsistent() = withPlayer { player ->
        player.prepareTransfer(listOf(song("a"), song("b"), song("c")), 1, 0, false)
        player.move(1, 2)
        assertEquals(1, player.state.value.index)
        assertEquals("c", player.queue.value[player.state.value.index + 1].id)
        player.remove(0)
        assertEquals(0, player.state.value.index)
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
