package io.github.iaguito22.oto.remote

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.account.YOUTUBE_LIKES_LIST_ID
import io.github.iaguito22.oto.account.importCloudPlaylist
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Optativa: solo actúa sobre la APK aislada construida con -PotoLinkTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class MobileLikeIntegrationTest {
    @Test fun realPhoneSyncsOtoHeartsAndKeepsYoutubeSeparate(): Unit = runBlocking {
        val adb = System.getenv("OTO_LINK_TEST_ADB")
        val serial = System.getenv("OTO_LINK_TEST_SERIAL")
        assumeTrue("Requiere adb, dispositivo explícito y APK .linktest", !adb.isNullOrBlank() && !serial.isNullOrBlank())
        val pkg = "io.github.iaguito22.oto.linktest"
        fun command(vararg args: String): String {
            val process = ProcessBuilder(listOf(adb!!,"-s",serial!!) + args).redirectErrorStream(true).start()
            check(process.waitFor(20,TimeUnit.SECONDS)) { process.destroyForcibly(); "adb no respondió" }
            val output = process.inputStream.bufferedReader().readText()
            check(process.exitValue()==0) { "Falló adb (${args.firstOrNull()}): $output" }
            return output
        }
        fun activity(action: String, vararg extras: String) {
            val output=command("shell","am","start","-W","--activity-multiple-task","-n","$pkg/io.github.iaguito22.oto.remote.DebugLinkActivity","--es","action",action,*extras)
            check(!output.contains("Error")) { output }
        }
        suspend fun phone(): JSONObject {
            val id=UUID.randomUUID().toString()
            activity("capture","--es","captureId",id)
            return withTimeout(10000) {
                var captured: JSONObject?=null
                while(captured==null) {
                    delay(300)
                    val result=runCatching { JSONObject(command("shell","run-as",pkg,"cat","files/debug-session.json")) }.getOrNull()
                    if(result?.optString("captureId")==id) captured=result
                }
                captured
            }
        }
        fun JSONObject.likes(): Set<String> = getJSONArray("likes").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        suspend fun phoneLikes(expected: Set<String>) {
            withTimeout(20000) { while(phone().likes()!=expected) delay(300) }
        }
        val dir=Files.createTempDirectory("oto-phone-likes").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        Dispatchers.setMain(Dispatchers.Default)
        val prefs=Prefs(
            PreferenceDataStoreFactory.create(scope=scope,produceFile={File(dir,"main.preferences_pb")}),
            PreferenceDataStoreFactory.create(scope=scope,produceFile={File(dir,"lists.preferences_pb")}),
        )
        val replica=LibraryReplica(prefs,File(dir,"replica.json"))
        val link=LocalLink(scope,{JSONObject().put("name","Oto de pruebas").put("libraryHash",replica.digest())},{replica.export()})
        var port=0
        val remoteFile="/data/local/tmp/oto-link-test-${UUID.randomUUID()}.json"
        try {
            check(command("shell","pm","path",pkg).contains("package:")) { "Instala primero la APK .linktest" }
            check(command("shell","run-as",pkg,"pwd").contains(pkg)) { "La APK aislada debe ser depurable" }
            check(command("shell","pm","clear",pkg).contains("Success")) { "No se pudo preparar la app aislada" }
            val secret=LocalLink.newSecret()
            port=link.listen(secret)
            command("reverse","tcp:$port","tcp:$port")
            val pair=File(dir,"pair.json").apply { writeText(JSONObject().put("oto",1).put("host","127.0.0.1").put("port",port).put("secret",secret).toString()) }
            command("push",pair.absolutePath,remoteFile)
            command("shell","run-as",pkg,"mkdir","-p","files")
            command("shell","run-as",pkg,"cp",remoteFile,"files/debug-pair.json")
            val sync=scope.launch {
                link.remote.collect { state ->
                    if(state!=null && state.optString("libraryHash")!=replica.digest()) replica.merge(link.command(JSONObject().put("action","library")))
                }
            }
            activity("pair")
            withTimeout(20000) { link.remote.first { it!=null } }
            delay(1000)
            assertTrue("La instalación aislada debe estar vacía",phone().likes().isEmpty())
            val pc=Heard("oto-link-test-pc","Prueba de sincronización","Oto",null)
            val mobile=pc.copy(id="oto-link-test-mobile")
            prefs.setLiked(pc,true)
            phoneLikes(setOf(pc.id))
            activity("test-like","--es","id",mobile.id,"--ez","on","true")
            withTimeout(15000) { prefs.liked.first { it.map { h->h.id }.toSet()==setOf(pc.id,mobile.id) } }
            activity("test-like","--es","id",pc.id,"--ez","on","false")
            withTimeout(15000) { prefs.liked.first { it.map { h->h.id }.toSet()==setOf(mobile.id) } }
            command("shell","am","force-stop",pkg)
            withTimeout(10000) { link.remote.first { it==null } }
            prefs.setLiked(mobile,false)
            activity("pair")
            withTimeout(20000) { link.remote.first { it!=null } }
            phoneLikes(emptySet())
            val yt=pc.copy(id="oto-link-test-youtube")
            importCloudPlaylist(prefs,LocalPlaylist(YOUTUBE_LIKES_LIST_ID,"Me gusta de YouTube Music",listOf(yt),"youtube:LM"))
            withTimeout(20000) {
                while(true) {
                    val state=phone()
                    assertTrue(state.likes().isEmpty())
                    val lists=state.getJSONArray("lists")
                    if((0 until lists.length()).any { lists.getJSONObject(it).let { l -> l.getString("id")==YOUTUBE_LIKES_LIST_ID && l.getJSONArray("songs").getString(0)==yt.id } }) break
                }
            }
            activity("test-like","--es","id",pc.id,"--ez","on","true")
            withTimeout(15000) { prefs.liked.first { it.map { h->h.id }==listOf(pc.id) } }
            phoneLikes(setOf(pc.id))
            sync.cancelAndJoin()
            println("Móvil real: alta PC→móvil, alta/baja móvil→PC, reconexión tras cierre, lista YouTube separada y nuevo marcado: correctos.")
        } finally {
            runCatching { command("shell","am","force-stop",pkg) }
            if(port!=0) runCatching { command("reverse","--remove","tcp:$port") }
            runCatching { command("shell","rm","-f",remoteFile) }
            link.close();scope.coroutineContext[Job]!!.cancelAndJoin();Dispatchers.resetMain()
            dir.deleteRecursively()
        }
    }
}
