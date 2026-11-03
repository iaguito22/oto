package io.github.iaguito22.oto.remote

import android.app.*
import android.content.Intent
import android.os.IBinder
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.R

class ConnectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("oto_devices", "Conexión con dispositivos", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(this, 0, Intent(this, DevicesActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(83, Notification.Builder(this,"oto_devices").setSmallIcon(R.drawable.ic_play_arrow).setContentTitle("Oto · conexión local activa").setContentText("Toca para controlar o desconectar el otro dispositivo").setContentIntent(intent).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { (application as OtoApp).devices.start(); return START_NOT_STICKY }
    override fun onBind(intent: Intent?): IBinder? = null
}
