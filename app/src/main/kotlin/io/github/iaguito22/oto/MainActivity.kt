package io.github.iaguito22.oto

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.contentcapture.ContentCaptureManager
import io.github.iaguito22.oto.ui.isla.IslaScreen

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {
    // Oto se ve igual en todos los móviles: la escala sale del ancho de la pantalla, no del tamaño de pantalla ni de
    // letra que haya en Ajustes del sistema (con ellos agrandados no cabía y se cortaba la barra de tiempo). En una
    // tableta se deja la del sistema: ahí estirarlo todo quedaría enorme.
    override fun attachBaseContext(newBase: Context) {
        val m = newBase.resources.displayMetrics
        val shortPx = minOf(m.widthPixels, m.heightPixels)
        if (shortPx / m.xdpi >= TABLET_INCHES) return super.attachBaseContext(newBase)
        val config = Configuration(newBase.resources.configuration)
        val dpi = (shortPx * DisplayMetrics.DENSITY_DEFAULT / BASE_WIDTH_DP).toInt()
        config.densityDpi = dpi
        config.fontScale = 1f
        config.screenWidthDp = m.widthPixels * DisplayMetrics.DENSITY_DEFAULT / dpi
        config.screenHeightDp = m.heightPixels * DisplayMetrics.DENSITY_DEFAULT / dpi
        config.smallestScreenWidthDp = minOf(config.screenWidthDp, config.screenHeightDp)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

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

    private companion object {
        // El ancho del móvil con el que está hecha (1080 px a 420 dpi).
        const val BASE_WIDTH_DP = 411.43f
        // Lo estrecho de una tableta: un móvil ronda las 2,8 pulgadas (es físico, no cambia con la escala).
        const val TABLET_INCHES = 3.5f
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
