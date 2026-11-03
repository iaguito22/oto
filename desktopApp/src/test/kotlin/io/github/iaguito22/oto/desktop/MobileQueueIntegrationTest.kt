package io.github.iaguito22.oto.desktop

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.account.Account
import io.github.iaguito22.oto.desktop.playback.*
import io.github.iaguito22.oto.dj.Plays
import io.github.iaguito22.oto.dj.YouTubeCatalog
import io.github.iaguito22.oto.lyrics.LyricsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Móvil físico y reproductor de escritorio reales, con perfiles aislados y sin audio. */
@OptIn(ExperimentalCoroutinesApi::class)
class MobileQueueIntegrationTest {
    @Test fun phoneAppendsToDesktopWithoutReplacingItsQueue(): Unit = runBlocking {
        val adb = System.getenv("OTO_LINK_TEST_ADB")
        val serial = System.getenv("OTO_LINK_TEST_SERIAL")
        assumeTrue("Requiere dispositivo explícito y APK .linktest", !adb.isNullOrBlank() && !serial.isNullOrBlank())
        val pkg = "io.github.iaguito22.oto.linktest"
        fun command(vararg args: String): String {
            val process = ProcessBuilder(listOf(adb!!, "-s", serial!!) + args).redirectErrorStream(true).start()
            check(process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); "adb no respondió" }
            return process.inputStream.bufferedReader().readText().also { check(process.exitValue() == 0) { it } }
        }
        fun activity(action: String) {
            val result = command("shell", "am", "start", "-W", "--activity-multiple-task", "-n",
                "$pkg/io.github.iaguito22.oto.remote.DebugLinkActivity", "--es", "action", action)
            check(!result.contains("Error")) { result }
        }
        val root = Files.createTempDirectory("oto-mobile-queue").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // El reproductor no ejecuta tareas de red/audio: solo se comprueba su cola.
        val playerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        Dispatchers.setMain(Dispatchers.Default)
        OtoDirs.init(root, File(root, "cache"))
        val prefs = Prefs(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "prefs.preferences_pb") }),
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "lists.preferences_pb") }))
        val player = DesktopPlayerConnection(prefs, Plays(File(root, "plays")), YouTubeCatalog(File(root, "dj")),
            LyricsStore(File(root, "lyrics")), DesktopDownloads(prefs, playerScope), playerScope)
        val devices = DeviceConnection(player, prefs, Account(prefs, scope), scope)
        try {
            player.prepareTransfer(listOf(Heard("a", "Actual", "Oto", null).toSong(), Heard("b", "Pendiente", "Oto", null).toSong()), 0, 23000, false)
            val before = player.state.value
            val position = player.position()
            val current = player.queue.value[before.index].id
            command("reverse", "tcp:${devices.port}", "tcp:${devices.port}")
            val pair = File(root, "pair.json").apply {
                writeText(JSONObject(devices.pairing).put("host", "127.0.0.1").toString())
            }
            command("push", pair.absolutePath, "/data/local/tmp/oto-queue-pair.json")
            command("shell", "run-as", pkg, "mkdir", "-p", "files")
            command("shell", "run-as", pkg, "cp", "/data/local/tmp/oto-queue-pair.json", "files/debug-pair.json")
            activity("pair")
            withTimeout(20000) { devices.link.remote.first { it != null } }
            activity("test-queue")
            withTimeout(15000) { player.queue.first { it.size == 3 } }
            assertEquals(listOf("a", "b", "oto-link-test-queue"), player.queue.value.map { it.id })
            assertEquals("Prueba de cola remota", player.queue.value.last().title)
            assertEquals(before.id, player.state.value.id)
            assertEquals(before.index, player.state.value.index)
            assertEquals(before.playing, player.state.value.playing)
            assertEquals(position, player.position())
            assertEquals(current, player.queue.value[player.state.value.index].id)
            activity("test-queue")
            withTimeout(15000) { player.queue.first { it.size == 4 } }
            assertEquals(listOf("a", "b", "oto-link-test-queue", "oto-link-test-queue"), player.queue.value.map { it.id })
            println("Móvil físico → cola Desktop: dos envíos al final, metadatos, canción actual, posición y pausa conservados.")
        } finally {
            runCatching { command("shell", "am", "force-stop", pkg) }
            runCatching { command("reverse", "--remove", "tcp:${devices.port}") }
            runCatching { command("shell", "rm", "-f", "/data/local/tmp/oto-queue-pair.json") }
            devices.close(); playerScope.cancel(); scope.coroutineContext[Job]!!.cancelAndJoin()
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }
}
