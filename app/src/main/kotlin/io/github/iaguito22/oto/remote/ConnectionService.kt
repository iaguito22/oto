package io.github.iaguito22.oto.remote

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import io.github.iaguito22.oto.MainActivity
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.playback.ARTWORK_PX
import io.github.iaguito22.oto.playback.resize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Mantiene la conexión local. Mientras el móvil sigue lo que suena en el ordenador, su notificación pasa a
 * ser un reproductor (también en la pantalla de bloqueo) que controla el ordenador; si no, es un aviso discreto.
 */
@UnstableApi
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var player: PcPlayer
    private lateinit var session: MediaSession
    private var art: Pair<String, Bitmap?>? = null
    private var shown: PcNotice? = null
    private var last: JSONObject? = null

    /** Lo que se pinta en la notificación; solo se vuelve a publicar si cambia. */
    private data class PcNotice(val id: String, val title: String, val artist: String, val artwork: String?, val playing: Boolean, val bitmap: Bitmap?)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Conexión con dispositivos", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, idle())
        val devices = (application as OtoApp).devices
        player = PcPlayer { command -> scope.launch { runCatching { devices.link.command(command) }.onFailure { timber.log.Timber.w(it, "No se pudo controlar el ordenador") } } }
        session = MediaSession.Builder(this, player).setId("oto-ordenador")
            .setSessionActivity(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
        scope.launch {
            combine(devices.following, devices.link.remote) { following, remote -> if (following != null) remote else null }.collect { state ->
                player.show(state)
                publish(state)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun publish(state: JSONObject?) {
        last = state
        val manager = getSystemService(NotificationManager::class.java)
        if (state == null) {
            if (shown != null) { shown = null; manager.notify(NOTIFICATION, idle()) }
            return
        }
        fun text(key: String) = state.optString(key).takeUnless { it == "null" }.orEmpty()
        val artwork = text("artwork").ifBlank { null }
        val bitmap = art?.takeIf { it.first == artwork }?.second
        if (artwork != null && art?.first != artwork) {
            art = artwork to null
            // La carátula llega después; mientras, la notificación sale sin ella.
            val uri = android.net.Uri.parse(artwork.resize(ARTWORK_PX))
            val future = session.bitmapLoader.loadBitmap(uri)
            future.addListener({ runCatching { future.get() }.getOrNull()?.let { b -> if (art?.first == artwork) { art = artwork to b; publish(last) } } }, mainExecutor)
        }
        val notice = PcNotice(text("id"), text("title"), text("artist"), artwork, state.optBoolean("playing"), bitmap)
        if (notice == shown) return
        shown = notice
        fun action(icon: Int, title: String, what: String) = NotificationCompat.Action(icon, title,
            PendingIntent.getService(this, what.hashCode(), Intent(this, ConnectionService::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE))
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.media3_notification_small_icon)
            .setContentTitle(notice.title).setContentText(notice.artist).setSubText("Sonando en el ordenador")
            .setLargeIcon(bitmap).setOngoing(notice.playing).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(session.sessionActivity)
            .addAction(action(R.drawable.ic_skip_previous, "Anterior", PREVIOUS))
            .addAction(if (notice.playing) action(R.drawable.ic_pause, "Pausar", TOGGLE) else action(R.drawable.ic_play_arrow, "Reproducir", TOGGLE))
            .addAction(action(R.drawable.ic_skip_next, "Siguiente", NEXT))
            .setStyle(MediaStyleNotificationHelper.MediaStyle(session).setShowActionsInCompactView(0, 1, 2))
            .build()
        manager.notify(NOTIFICATION, notification)
    }

    private fun idle(): Notification {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, DevicesActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_play_arrow).setContentTitle("Oto · conexión local activa")
            .setContentText("Toca para controlar o desconectar el otro dispositivo").setContentIntent(intent).setSilent(true).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TOGGLE -> player.playWhenReady = !player.playWhenReady
            NEXT -> player.seekToNext()
            PREVIOUS -> player.seekToPrevious()
            else -> (application as OtoApp).devices.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel(); session.release(); player.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "oto_devices"
        const val NOTIFICATION = 83
        const val TOGGLE = "io.github.iaguito22.oto.pc.TOGGLE"
        const val NEXT = "io.github.iaguito22.oto.pc.NEXT"
        const val PREVIOUS = "io.github.iaguito22.oto.pc.PREVIOUS"
    }
}
