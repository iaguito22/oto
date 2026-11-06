package io.github.iaguito22.oto.desktop

import io.github.iaguito22.oto.playback.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteMirrorTest {
    private fun phone(playing: Boolean) = JSONObject().put("id", "m1").put("title", "Canción").put("artist", "Artista")
        .put("artwork", "https://lh3.googleusercontent.com/x=w1080-h1080").put("playing", playing).put("buffering", false)
        .put("duration", 200_000).put("position", 30_000).put("index", 2).put("shuffle", true).put("loop", false)
        .put("sourceKind", "Álbum").put("sourceTitle", "Disco")

    @Test fun parseReadsWhatThePhoneSends() {
        val m = RemoteMirror.parse(phone(true))
        assertEquals("m1", m.now.id); assertEquals("Canción", m.now.title); assertEquals(200_000L, m.now.durationMs)
        assertEquals(true, m.shuffle); assertEquals("Disco", m.source?.title); assertEquals("Álbum", m.source?.kind)
    }

    @Test fun followsThePhoneUntilThisComputerPlays() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val remote = MutableStateFlow<JSONObject?>(null)
            val local = MutableStateFlow(NowPlaying(id = "pc", playing = false))
            val mirror = RemoteMirror(remote, local, scope)
            remote.value = phone(true)
            withTimeout(2000) { mirror.state.first { it?.now?.id == "m1" } }
            // Sigue mostrándolo tras pausar en el móvil, para poder reanudarlo desde aquí.
            remote.value = phone(false)
            withTimeout(2000) { mirror.state.first { it?.now?.playing == false } }
            assertEquals(30_000L, withTimeout(2000) { mirror.position.first { it == 30_000L } })
            // En cuanto suena el ordenador, manda lo local.
            local.value = NowPlaying(id = "pc", playing = true)
            withTimeout(2000) { mirror.state.first { it == null } }
            assertNull(mirror.state.value)
        } finally { scope.cancel() }
    }
}
