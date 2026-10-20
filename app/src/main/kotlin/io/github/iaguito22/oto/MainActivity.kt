package io.github.iaguito22.oto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.iaguito22.oto.ui.sonda.SondaScreen
import io.github.iaguito22.oto.ui.theme.OtoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            OtoTheme {
                // Pantalla provisional de la fase 2: comprueba que InnerTube responde. La isla la sustituye en la fase 4.
                SondaScreen()
            }
        }
    }
}
