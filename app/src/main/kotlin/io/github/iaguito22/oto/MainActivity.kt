package io.github.iaguito22.oto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.iaguito22.oto.ui.isla.IslaScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            IslaScreen()
        }
    }

    // La interfaz solo habla con el servicio mientras se ve; la música sigue sin ella.
    override fun onStart() {
        super.onStart()
        (application as OtoApp).player.connect()
    }

    override fun onStop() {
        (application as OtoApp).player.disconnect()
        super.onStop()
    }
}
