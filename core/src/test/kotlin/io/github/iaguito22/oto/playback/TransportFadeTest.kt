package io.github.iaguito22.oto.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransportFadeTest {
    @Test fun pausesAtZeroAndResumesFromSilenceWhenAudioIsReady() = runTest {
        var gain = 1f
        var playing = true
        val fade = TransportFade(this) { gain = it }
        fade.request(false, playing, {}, { playing = false })
        advanceTimeBy(199L)
        assertTrue(playing)
        assertTrue(gain > 0f)
        advanceTimeBy(1L)
        runCurrent()
        assertFalse(playing)
        assertEquals(0f, gain, 0f)

        fade.request(true, playing, { playing = true }, {}, awaitAudio = { delay(300L) })
        assertTrue(playing)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(0f, gain, 0f)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1f, gain, 0f)
    }

    @Test fun repeatedTogglesReverseFromTheCurrentGainAndCancelTheOldPause() = runTest {
        var gain = 1f
        var pauses = 0
        val fade = TransportFade(this) { gain = it }
        fun request(play: Boolean) = fade.request(play, true, {}, { pauses++ })
        request(false)
        advanceTimeBy(90L)
        runCurrent()
        val beforeResume = gain
        request(true)
        assertEquals(beforeResume, gain, 0f)
        advanceTimeBy(60L)
        runCurrent()
        assertTrue(gain > beforeResume)
        request(false)
        advanceTimeBy(50L)
        runCurrent()
        request(true)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(0, pauses)
        assertEquals(1f, gain, 0f)
        assertNull(fade.requestedPlaying)
    }
}
