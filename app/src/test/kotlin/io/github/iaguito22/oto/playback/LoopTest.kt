package io.github.iaguito22.oto.playback

import androidx.media3.common.Player
import io.github.iaguito22.oto.LoopMode
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
}
