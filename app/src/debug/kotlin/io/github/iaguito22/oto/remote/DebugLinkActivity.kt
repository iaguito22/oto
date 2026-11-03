package io.github.iaguito22.oto.remote

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import io.github.iaguito22.oto.OtoApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
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
                    "pair" -> { app.devices.pair(command.getStringExtra("pair") ?: error("Falta código"));startActivity(Intent(this@DebugLinkActivity,DevicesActivity::class.java)) }
                    "capture" -> {
                        delay(800)
                        val snapshot=app.devices.adapter.snapshot().put("remote",app.devices.link.remote.value).put("status",app.devices.link.status.value)
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
