package io.github.iaguito22.oto.remote

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLinkTest {
    @Test fun authenticatedChannelCarriesStateAndCommandsBothWays(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val a=LocalLink(scope,{JSONObject().put("name","desktop")},{JSONObject().put("answer",it.getString("action"))})
        val b=LocalLink(scope,{JSONObject().put("name","mobile")},{JSONObject().put("answer",it.getString("action"))})
        try {
            val secret=LocalLink.newSecret();val port=a.listen(secret)
            b.connect("127.0.0.1",port,secret)
            withTimeout(5000) { a.remote.first { it?.optString("name")=="mobile" };b.remote.first { it?.optString("name")=="desktop" } }
            assertEquals("next",a.command(JSONObject().put("action","next")).getString("answer"))
            assertEquals("pause",b.command(JSONObject().put("action","pause")).getString("answer"))
            b.close();withTimeout(3000){a.remote.first{it==null}}
        } finally { a.close();b.close();scope.cancel();Dispatchers.resetMain() }
    }
    @Test fun wrongSecretCannotReadStateOrRunCommands() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var called=false
        val a=LocalLink(scope,{JSONObject()},{called=true;JSONObject()})
        val b=LocalLink(scope,{JSONObject()},{JSONObject()})
        try {
            b.connect("127.0.0.1",a.listen(LocalLink.newSecret()),LocalLink.newSecret())
            delay(500)
            assertNull(a.remote.value);assertNull(b.remote.value);assertFalse(called)
            assertTrue(runCatching { b.command(JSONObject().put("action","next")) }.isFailure)
        } finally {a.close();b.close();scope.cancel();Dispatchers.resetMain()}
    }
    @Test fun libraryLargerThanFourMegabytesCanBeTransferred(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val large="biblioteca de prueba ".repeat(250000)
        val a=LocalLink(scope,{JSONObject().put("name","desktop")},{JSONObject().put("library",large)})
        val b=LocalLink(scope,{JSONObject().put("name","mobile")},{JSONObject()})
        try {
            val secret=LocalLink.newSecret();b.connect("127.0.0.1",a.listen(secret),secret)
            withTimeout(5000){b.remote.first{it!=null}}
            assertEquals(large,b.command(JSONObject().put("action","library")).getString("library"))
            assertNotNull(b.remote.value)
        } finally {a.close();b.close();scope.cancel();Dispatchers.resetMain()}
    }

    @Test fun simultaneousLargeRepliesDoNotBlockTheReceiveLoop(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Poco compresible para superar las ventanas TCP incluso en loopback.
        val bytes = ByteArray(6 * 1024 * 1024).also { java.util.Random(7).nextBytes(it) }
        val large = java.util.Base64.getEncoder().encodeToString(bytes)
        val arrived = java.util.concurrent.atomic.AtomicInteger()
        val both = CompletableDeferred<Unit>()
        suspend fun answer(): JSONObject {
            if (arrived.incrementAndGet() == 2) both.complete(Unit)
            both.await()
            return JSONObject().put("library", large)
        }
        val a = LocalLink(scope, { JSONObject().put("name", "desktop") }, { answer() })
        val b = LocalLink(scope, { JSONObject().put("name", "mobile") }, { answer() })
        try {
            val secret = LocalLink.newSecret()
            b.connect("127.0.0.1", a.listen(secret), secret)
            withTimeout(5000) { a.remote.first { it != null }; b.remote.first { it != null } }
            withTimeout(12000) {
                val first = async { a.command(JSONObject().put("action", "library")) }
                val second = async { b.command(JSONObject().put("action", "library")) }
                assertEquals(large, first.await().getString("library"))
                assertEquals(large, second.await().getString("library"))
            }
        } finally { a.close(); b.close(); scope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun timedOutActionDoesNotDisableFollowingCommands(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val a=LocalLink(scope,{JSONObject().put("name","desktop")},{ message ->
            if(message.optString("action")=="slow") withTimeout(20) { delay(1000) }
            JSONObject().put("ok",true)
        })
        val b=LocalLink(scope,{JSONObject().put("name","mobile")},{JSONObject()})
        try {
            val secret=LocalLink.newSecret();b.connect("127.0.0.1",a.listen(secret),secret)
            withTimeout(5000) { b.remote.first { it!=null } }
            assertTrue(runCatching { withTimeout(3000) { b.command(JSONObject().put("action","slow")) } }.isFailure)
            assertTrue(withTimeout(3000) { b.command(JSONObject().put("action","next")) }.getBoolean("ok"))
        } finally {a.close();b.close();scope.cancel();Dispatchers.resetMain()}
    }

    @Test fun commandPublishesNewStateBeforeAcknowledging(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Default)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val position=java.util.concurrent.atomic.AtomicLong(0)
        val a=LocalLink(scope,{JSONObject().put("position",position.get())},{position.set(it.getLong("position"));JSONObject().put("ok",true)})
        val b=LocalLink(scope,{JSONObject()},{JSONObject()})
        try {
            val secret=LocalLink.newSecret();b.connect("127.0.0.1",a.listen(secret),secret)
            withTimeout(5000) { b.remote.first { it!=null } }
            withTimeout(3000) { b.command(JSONObject().put("action","seek").put("position",99000)) }
            assertEquals(99000L,b.remote.value!!.getLong("position"))
        } finally {a.close();b.close();scope.cancel();Dispatchers.resetMain()}
    }

}
