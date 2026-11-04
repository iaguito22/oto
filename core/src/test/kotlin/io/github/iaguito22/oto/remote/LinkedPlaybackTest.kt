package io.github.iaguito22.oto.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LinkedPlaybackTest {
    private class Player : LinkPlayer {
        var ready=false;var playingState=false;var position=0L;var repeat=false
        var added: JSONObject? = null; var nextAdded = false
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
        override fun addToQueue(song:JSONObject,next:Boolean){added=song;nextAdded=next}
        override fun volume(value:Float){}
    }
    @Test fun remoteNowReadsSnapshotAndIgnoresEmptySongs() {
        assertNull(RemoteNow.of(null))
        assertNull(RemoteNow.of(JSONObject().put("id", JSONObject.NULL)))
        val now = RemoteNow.of(JSONObject().put("id","a").put("title","T").put("artist","A").put("artwork",JSONObject.NULL).put("playing",true))!!
        assertEquals(RemoteNow("a","T","A",null,true,false), now)
    }
    @Test fun followingRemoteSticksAfterPausingThereAndYieldsToLocalPlayback() {
        val playing = RemoteNow("a","T","A",null,true,false)
        val paused = playing.copy(playing=false)
        // Suena allí y aquí no: se muestra lo de allí.
        assertTrue(RemoteNow.follow(false, localHasSong=true, localPlaying=false, remote=playing))
        // Pausado allí desde aquí: se queda, para poder reanudarlo.
        assertTrue(RemoteNow.follow(true, localHasSong=true, localPlaying=false, remote=paused))
        // Ambos en pausa sin haberlo seguido: manda lo local.
        assertFalse(RemoteNow.follow(false, localHasSong=true, localPlaying=false, remote=paused))
        // Sin nada local, se muestra lo de allí aunque esté en pausa.
        assertTrue(RemoteNow.follow(false, localHasSong=false, localPlaying=false, remote=paused))
        // En cuanto suena aquí, o se desconecta, vuelve lo local.
        assertFalse(RemoteNow.follow(true, localHasSong=true, localPlaying=true, remote=playing))
        assertFalse(RemoteNow.follow(true, localHasSong=true, localPlaying=false, remote=null))
    }
    @Test fun transferPreservesPausedStateAndPosition(): Unit=runBlocking {
        val player=Player()
        val state=JSONObject().put("id","song").put("position",37000).put("playing",false).put("loop",true).put("queue",JSONArray().put(JSONObject().put("id","song")))
        LinkedPlayback(player).execute(JSONObject().put("action","transfer").put("state",state))
        assertFalse(player.playingState);assertEquals(37000,player.position);assertTrue(player.repeat)
    }
    @Test fun addingSongKeepsInsertionChoice(): Unit=runBlocking {
        val player=Player()
        val song=JSONObject().put("id","track").put("title","Song").put("artist","Artist")
        LinkedPlayback(player).execute(JSONObject().put("action","addToQueue").put("song",song).put("next",true))
        assertEquals("track",player.added?.getString("id"));assertTrue(player.nextAdded)
        assertTrue(runCatching { LinkedPlayback(player).execute(JSONObject().put("action","addToQueue").put("song",JSONObject().put("id","").put("title","Song"))) }.isFailure)
    }
    @Test fun unsupportedCommandIsRejected(): Unit=runBlocking {
        assertTrue(runCatching{LinkedPlayback(Player()).execute(JSONObject().put("action","unknown"))}.isFailure)
    }
}
