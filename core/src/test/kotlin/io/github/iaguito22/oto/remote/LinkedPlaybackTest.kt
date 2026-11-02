package io.github.iaguito22.oto.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LinkedPlaybackTest {
    private class Player : LinkPlayer {
        var ready=false;var playingState=false;var position=0L;var repeat=false
        override fun snapshot()=JSONObject().put("id","song").put("duration",100000).put("buffering",!ready)
        override fun replace(state:JSONObject) { ready=true;playingState=false }
        override fun setPlaying(on:Boolean){playingState=on}
        override fun seek(ms:Long){position=ms}
        override fun loop(on:Boolean){repeat=on}
        override suspend fun like(){}
        override fun toggle(){playingState=!playingState}
        override fun next(){}
        override fun previous(){}
        override fun shuffle(on:Boolean){}
        override fun skip(index:Int){}
        override fun move(from:Int,to:Int){}
        override fun remove(index:Int){}
        override fun volume(value:Float){}
    }
    @Test fun transferPreservesPausedStateAndPosition(): Unit=runBlocking {
        val player=Player()
        val state=JSONObject().put("id","song").put("position",37000).put("playing",false).put("loop",true).put("queue",JSONArray().put(JSONObject().put("id","song")))
        LinkedPlayback(player).execute(JSONObject().put("action","transfer").put("state",state))
        assertFalse(player.playingState);assertEquals(37000,player.position);assertTrue(player.repeat)
    }
    @Test fun unsupportedCommandIsRejected(): Unit=runBlocking {
        assertTrue(runCatching{LinkedPlayback(Player()).execute(JSONObject().put("action","unknown"))}.isFailure)
    }
}
