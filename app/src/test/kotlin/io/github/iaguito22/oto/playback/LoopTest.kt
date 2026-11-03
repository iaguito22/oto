package io.github.iaguito22.oto.playback

import androidx.media3.common.Player
import io.github.iaguito22.oto.LoopMode
import io.github.iaguito22.oto.PlaylistMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LoopTest {
    @Test
    fun cycleLoopOrder() {
        var mode = LoopMode.Off
        val next = { m: LoopMode ->
            when (m) {
                LoopMode.Off -> LoopMode.All
                LoopMode.All -> LoopMode.One
                LoopMode.One -> LoopMode.Off
            }
        }
        mode = next(mode)
        assertEquals(LoopMode.All, mode)
        mode = next(mode)
        assertEquals(LoopMode.One, mode)
        mode = next(mode)
        assertEquals(LoopMode.Off, mode)
    }

    @Test
    fun playerRepeatModes() {
        val map = { m: LoopMode ->
            when (m) {
                LoopMode.Off -> Player.REPEAT_MODE_OFF
                LoopMode.All -> Player.REPEAT_MODE_ALL
                LoopMode.One -> Player.REPEAT_MODE_ONE
            }
        }
        assertEquals(Player.REPEAT_MODE_OFF, map(LoopMode.Off))
        assertEquals(Player.REPEAT_MODE_ALL, map(LoopMode.All))
        assertEquals(Player.REPEAT_MODE_ONE, map(LoopMode.One))
    }

    @Test
    fun playlistModesUseDefaultsForNewAndRememberUsed() {
        val modes = mutableMapOf<String, PlaylistMode>()
        var defaultShuffle = true
        var defaultLoop = false

        fun resolve(listId: String): PlaylistMode {
            return modes.getOrPut(listId) {
                PlaylistMode(defaultShuffle, if (defaultLoop) LoopMode.All else LoopMode.Off)
            }
        }

        // Nueva playlist toma los ajustes por defecto (shuffle=true, loop=Off)
        assertEquals(PlaylistMode(true, LoopMode.Off), resolve("list-a"))

        // Cambiar el estado en list-a a LoopMode.One y shuffle=false se guarda para list-a
        modes["list-a"] = PlaylistMode(false, LoopMode.One)

        // Cambiar los ajustes por defecto afecta a una nueva playlist (list-b), pero no a list-a
        defaultShuffle = false
        defaultLoop = true
        assertEquals(PlaylistMode(false, LoopMode.All), resolve("list-b"))
        assertEquals(PlaylistMode(false, LoopMode.One), resolve("list-a"))
    }
}
