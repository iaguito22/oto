package io.github.iaguito22.oto.ui.isla

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import kotlinx.coroutines.delay

/** Por dónde sale el sonido ahora: el altavoz del móvil, unos cascos, un altavoz… */
data class Output(val name: String, val icon: Int)

private val MEDIA = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

private fun outputOf(audio: AudioManager): Output {
    val d = runCatching { audio.getAudioDevicesForAttributes(MEDIA).firstOrNull() }.getOrNull()
    val name = d?.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != Build.MODEL }
    return when (d?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID ->
            Output(name ?: "Bluetooth", R.drawable.ic_headphones)
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> Output(name ?: "Altavoz", R.drawable.ic_speaker)
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET ->
            Output(name ?: "Auriculares", R.drawable.ic_headphones)
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_DOCK_ANALOG,
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL ->
            Output(name ?: "Otro aparato", R.drawable.ic_speaker)
        else -> Output("Este móvil", R.drawable.ic_smartphone)
    }
}

/** El panel del sistema para elegir por dónde suena (cascos, altavoces bluetooth…), como el de los ajustes rápidos. */
fun pickOutput(context: Context) {
    val shown = runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
    if (!shown) runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Dónde suena, en pequeño; tocarlo abre el panel del sistema para cambiarlo. Se vuelve a mirar cada segundo mientras
 * se ve: cambiar de salida en ese panel no avisa a la app.
 */
@Composable
fun OutputChip(modifier: Modifier = Modifier) {
    val c = Oto.colors
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var out by remember { mutableStateOf(outputOf(audio)) }
    LaunchedEffect(audio) { while (true) { out = outputOf(audio); delay(1000) } }
    Row(
        modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(c.field)
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text)) { pickOutput(context) }
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(out.icon), null, Modifier.size(16.dp), tint = c.accent)
        Spacer(Modifier.width(6.dp))
        Text(out.name, style = OtoType.rowSub, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
    }
}
