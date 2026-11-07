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
    @Test fun aKickBetweenTwoReadsIsNotLost() {
        // Un bombo en un solo trozo (el 10, 106,7–117,3 ms); la carátula lee a los 100 y a los 125 ms.
        val values=FloatArray(40).also{it[10]=1f}
        assertEquals(0f,DesktopBeat.peak(values,100,1)); assertEquals(0f,DesktopBeat.peak(values,125,1))
        assertEquals(1f,DesktopBeat.peak(values,125,3))
        assertEquals(0f,DesktopBeat.peak(values,160,3)) // ya pasó: no se repite
        assertEquals(0f,DesktopBeat.peak(FloatArray(0),50,3))
    }
}
