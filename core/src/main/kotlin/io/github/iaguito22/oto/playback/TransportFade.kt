package io.github.iaguito22.oto.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ganancia de transporte independiente del volumen y de la mezcla de cada pista. */
class TransportFade(private val scope: CoroutineScope, private val applyGain: (Float) -> Unit) {
    private var gain = 1f
    private var job: Job? = null
    private var generation = 0L
    private var target: Boolean? = null
    val requestedPlaying: Boolean? @Synchronized get() = target

    @Synchronized
    fun request(
        playing: Boolean,
        actualPlaying: Boolean,
        play: () -> Unit,
        pause: () -> Unit,
        beforePause: suspend () -> Unit = {},
        awaitAudio: suspend () -> Unit = {},
    ) {
        val to = if (playing) 1f else 0f
        if (target == playing || (target == null && playing == actualPlaying && gain == to)) return
        val request = ++generation
        target = playing
        job?.cancel()
        if (playing && !actualPlaying) {
            setGain(0f)
            play()
        }
        if (!playing && !actualPlaying) {
            setGain(0f)
            target = null
            return
        }
        val from = gain
        job = scope.launch {
            try {
                if (playing) awaitAudio()
                if (from != to) {
                    for (step in 1..20) {
                        delay(10L)
                        synchronized(this@TransportFade) {
                            if (generation != request) return@launch
                            setGain(from + (to - from) * (step / 20f))
                        }
                    }
                }
                if (!playing) {
                    beforePause()
                    synchronized(this@TransportFade) {
                        if (generation == request) pause()
                    }
                }
            } finally {
                synchronized(this@TransportFade) {
                    if (generation == request) target = null
                }
            }
        }
    }

    @Synchronized
    fun reset() {
        generation++
        target = null
        job?.cancel()
        job = null
        setGain(1f)
    }

    private fun setGain(value: Float) {
        gain = value
        applyGain(value)
    }
}
