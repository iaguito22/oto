package io.github.iaguito22.oto.desktop.playback

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DesktopBeatTest {
    @Test fun silenceDoesNotInventABeat() {
        val pulses=mutableListOf<Float>()
        val detector=DesktopBeat.Detector(pulses::add)
        repeat(48000){detector.sample(0f)}
        assertTrue(pulses.isNotEmpty());assertTrue(pulses.all{it==0f})
    }
    @Test fun bassBurstsProducePulsesRatherThanAClockAnimation() {
        val pulses=mutableListOf<Float>()
        val detector=DesktopBeat.Detector(pulses::add)
        repeat(48000*3){i ->
            val envelope=if(i%24000<2880) exp(-(i%24000)/900f) else 0f
            detector.sample((sin(2*PI*80*i/48000)*envelope*.8f).toFloat())
        }
        assertTrue(pulses.any{it>.7f})
        assertTrue(pulses.count{it==0f}>pulses.size/2)
        assertTrue(pulses.all{it in 0f..1f})
    }
}
