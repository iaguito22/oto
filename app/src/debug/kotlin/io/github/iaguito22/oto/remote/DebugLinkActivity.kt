package io.github.iaguito22.oto.remote

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.BuildConfig
import io.github.iaguito22.oto.Heard
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Punto de prueba solo debug y protegido por DUMP (shell); nunca entra en una release. */
class DebugLinkActivity : Activity() {
    private val commands=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        runCommand(intent)
    }
    override fun onNewIntent(newIntent:Intent) {super.onNewIntent(newIntent);intent=newIntent;runCommand(newIntent)}
    private fun runCommand(command:Intent) {
        val app=application as OtoApp
        app.player.connect()
        commands.launch {
            try {
                when(command.getStringExtra("action")) {
                    "capture-habitual" -> usualSession(this@DebugLinkActivity,false)
                    "restore-habitual" -> usualSession(this@DebugLinkActivity,true)
                    "pair" -> {
                        val pair=command.getStringExtra("pair") ?: run {
                            check(BuildConfig.APPLICATION_ID.endsWith(".linktest"))
                            File(filesDir,"debug-pair.json").readText()
                        }
                        app.devices.pair(pair)
                        startActivity(Intent(this@DebugLinkActivity,DevicesActivity::class.java))
                    }
                    "test-like" -> {
                        check(BuildConfig.APPLICATION_ID.endsWith(".linktest"))
                        val id=command.getStringExtra("id") ?: error("Falta canción")
                        check(id.startsWith("oto-link-test-"))
                        app.prefs.setLiked(Heard(id,"Prueba de sincronización","Oto",null),command.getBooleanExtra("on",false))
                    }
                    "test-queue" -> {
                        check(BuildConfig.APPLICATION_ID.endsWith(".linktest"))
                        app.devices.addToRemoteQueue(Heard("oto-link-test-queue", "Prueba de cola remota", "Oto", null))
                    }
                    "capture" -> {
                        delay(800)
                        val snapshot=app.devices.adapter.snapshot().put("remote",app.devices.link.remote.value).put("status",app.devices.link.status.value)
                        if(BuildConfig.APPLICATION_ID.endsWith(".linktest")) {
                            val library=app.prefs.linkedLibrarySnapshot()
                            snapshot.put("captureId",command.getStringExtra("captureId"))
                                .put("likes",JSONArray(library.likes.map { it.id }))
                                .put("lists",JSONArray(library.lists.map { list -> JSONObject().put("id",list.id).put("songs",JSONArray(list.songs.map { it.id })) }))
                        }
                        withTimeoutOrNull(1500) { app.prefs.history.first() }?.firstOrNull()?.let { h -> snapshot.put("recent",JSONObject().put("id",h.id).put("title",h.title).put("artist",h.artist).put("artwork",h.artwork)) }
                        File(filesDir,"debug-session.json").writeText(snapshot.toString())
                    }
                    "remote" -> {
                        val command=JSONObject(File(filesDir,"debug-remote-command.json").readText())
                        app.devices.link.command(command)
                    }
                    "restore" -> {
                        val snapshot=JSONObject(File(filesDir,"debug-session-restore.json").readText())
                        LinkedPlayback(app.devices.adapter).execute(JSONObject().put("action","transfer").put("state",snapshot))
                    }
                }
            } catch(error:Exception) {android.util.Log.e("OtoLinkDebug","Prueba incompleta",error)} finally {finish()}
        }
    }
}
