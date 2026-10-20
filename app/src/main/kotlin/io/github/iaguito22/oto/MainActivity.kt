package io.github.iaguito22.oto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.contentcapture.ContentCaptureManager
import io.github.iaguito22.oto.ui.isla.IslaScreen

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Con un servicio de «content capture» en el móvil (el del Nothing lo tiene), Compose recorre todo el árbol de
        // semántica tras cada cambio para contárselo: medido, un 15 % del hilo principal al abrir el Menú, a tirones.
        // Aquí no aporta nada (es para que el sistema lea el texto de la pantalla).
        ContentCaptureManager.isEnabled = false
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
