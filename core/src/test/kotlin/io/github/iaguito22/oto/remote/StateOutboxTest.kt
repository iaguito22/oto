package io.github.iaguito22.oto.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StateOutboxTest {
    private val queue = JSONArray().put(JSONObject().put("id", "a")).put(JSONObject().put("id", "b"))
    private fun state(playing: Boolean, position: Long, q: JSONArray = queue) =
        JSONObject().put("id", "a").put("playing", playing).put("position", position).put("queue", q)

    @Test fun playingSendsOncePerSecondAndQueueOnlyWhenItChanges() {
        val out = StateOutbox()
        val first = out.next(state(true, 0), 0)!!
        assertSame(queue, first.get("queue"))
        // La posición sola no es un cambio: hasta pasado un segundo no se envía.
        assertNull(out.next(state(true, 500), 500))
        val beat = out.next(state(true, 1000), 1000)!!
        assertEquals(1000L, beat.getLong("position"))
        assertFalse(beat.has("queue")); assertTrue(beat.getBoolean("queueSame"))
        // Una cola nueva viaja al momento.
        val other = JSONArray().put(JSONObject().put("id", "c"))
        assertSame(other, out.next(state(true, 1200, other), 1200)!!.get("queue"))
    }

    @Test fun pausedOnlyOnChangesOrKeepAlive() {
        val out = StateOutbox()
        out.next(state(false, 10), 0)
        assertNull(out.next(state(false, 10), 5_000))
        // En pausa, mover la posición sí cuenta.
        assertNotNull(out.next(state(false, 90_000), 5_500))
        assertNotNull(out.next(state(false, 90_000), 16_000))
        // Reanudar se envía al instante.
        assertNotNull(out.next(state(true, 90_000), 16_100))
    }

    @Test fun receiverKeepsThePreviousQueue() {
        val previous = state(true, 0)
        val body = JSONObject().put("id", "a").put("queueSame", true)
        val merged = StateOutbox.receive(body, previous)
        assertSame(queue, merged.get("queue")); assertFalse(merged.has("queueSame"))
    }
}
