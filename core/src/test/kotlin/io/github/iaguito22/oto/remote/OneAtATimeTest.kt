package io.github.iaguito22.oto.remote

import io.github.iaguito22.oto.remote.OneAtATime.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class OneAtATimeTest {
    @Test fun startingHereWhileTheOtherPlaysPausesTheOther() {
        val turn = OneAtATime()
        assertEquals(Action.None, turn.update(localPlaying = false, remotePlaying = true))
        assertEquals(Action.PauseRemote, turn.update(localPlaying = true, remotePlaying = true))
        // Mientras el estado del otro llega con retraso no se repite la orden.
        assertEquals(Action.None, turn.update(localPlaying = true, remotePlaying = true))
        assertEquals(Action.None, turn.update(localPlaying = true, remotePlaying = false))
    }

    @Test fun theOtherStartingWhileThisPlaysPausesThis() {
        val turn = OneAtATime()
        assertEquals(Action.None, turn.update(localPlaying = true, remotePlaying = false))
        assertEquals(Action.PauseLocal, turn.update(localPlaying = true, remotePlaying = true))
    }

    @Test fun pausingHereNeverHandsTheMusicToTheOther() {
        val turn = OneAtATime()
        turn.update(localPlaying = false, remotePlaying = false)
        assertEquals(Action.None, turn.update(localPlaying = true, remotePlaying = false))
        assertEquals(Action.None, turn.update(localPlaying = false, remotePlaying = false))
        assertEquals(Action.None, turn.update(localPlaying = true, remotePlaying = false))
    }

    @Test fun connectingWhileBothPlayDoesNotSilenceBoth() {
        val turn = OneAtATime()
        assertEquals(Action.None, turn.update(localPlaying = true, remotePlaying = null))
        // Al enlazarse ya sonaban los dos: sin saber quién empezó después, no se toca nada.
        assertEquals(Action.None, turn.update(localPlaying = true, remotePlaying = true))
        assertEquals(Action.None, turn.update(localPlaying = true, remotePlaying = true))
    }

    @Test fun reconnectingStartsFromScratch() {
        val turn = OneAtATime()
        turn.update(localPlaying = false, remotePlaying = false)
        turn.update(localPlaying = false, remotePlaying = null)
        assertEquals(Action.None, turn.update(localPlaying = true, remotePlaying = true))
    }
}
