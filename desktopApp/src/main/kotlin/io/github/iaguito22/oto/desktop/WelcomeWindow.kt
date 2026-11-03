package io.github.iaguito22.oto.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.*
import io.github.iaguito22.oto.desktop.ui.DefaultAccent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

internal data class WelcomeClip(val asset: String, val title: String, val description: String)

internal val welcomeCombinations = listOf(
    WelcomeClip("01-biblioteca-reproductor", "Biblioteca + reproductor", "Elige tu música y ten el reproductor al lado."),
    WelcomeClip("02-reproductor-letra", "Reproductor + letra", "Deja solo el reproductor y la letra para seguir la canción."),
    WelcomeClip("03-biblioteca-reproductor-letra", "Biblioteca + reproductor + letra", "Abre la letra sin dejar de explorar tu biblioteca."),
    WelcomeClip("04-biblioteca-reproductor-cola", "Biblioteca + reproductor + cola", "Cambia la letra por la cola para ver qué viene después."),
)
private val welcomeReorder = WelcomeClip("05-intercambiar-ventanas", "Cada ventana en su sitio", "Arrastra la barra superior de una ventana secundaria sobre otra para intercambiar sus posiciones.")
private val welcomePlayer = WelcomeClip("06-reproductor-horizontal-fijado", "Tu reproductor, siempre a mano", "Elige la vista horizontal, déjala con la altura mínima y muévela a una esquina. La chincheta la mantiene sobre las demás ventanas.")

/** Una sola pantalla: los ejemplos relacionados cambian solos y se pueden elegir directamente. */
@Composable
internal fun WelcomeWindow(close: () -> Unit) {
    val state = rememberWindowState(position = WindowPosition(Alignment.Center), width = 1040.dp, height = 760.dp)
    ModuleWindow("Bienvenida", state, true, close, accent = DefaultAccent, minimum = DpSize(780.dp, 620.dp), keys = {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false
    }) {
        WelcomeContent(close)
    }
}

@Composable
private fun WelcomeContent(close: () -> Unit) {
    var section by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    // Updated callbacks let the decoder keep its current frame when pausing or changing controls.
    val clip = when (section) { 0 -> welcomeCombinations[selected]; 1 -> welcomeReorder; else -> welcomePlayer }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF121715)) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bienvenido a Oto", style = MaterialTheme.typography.headlineMedium)
                    Text("Tu música, a tu manera.", color = Color.White.copy(alpha = .55f), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = close) { Text("Omitir") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = section == 0, onClick = { section = 0 }, label = { Text("Combina ventanas") }, leadingIcon = { Icon(Icons.Rounded.ViewQuilt, null, Modifier.size(18.dp)) })
                FilterChip(selected = section == 1, onClick = { section = 1 }, label = { Text("Cambia el orden") }, leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp)) })
                FilterChip(selected = section == 2, onClick = { section = 2 }, label = { Text("Reproductor") }, leadingIcon = { Icon(Icons.Rounded.PushPin, null, Modifier.size(18.dp)) })
            }
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF0D1210)).border(1.dp, Color.White.copy(alpha = .09f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                WelcomeAnimation(clip, playing, onEnd = { if (section == 0) selected = (selected + 1) % welcomeCombinations.size })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(clip.title, style = MaterialTheme.typography.titleMedium)
                    Text(clip.description, color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { playing = !playing }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pausar demostración" else "Reproducir demostración") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                if (section == 0) Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    welcomeCombinations.forEachIndexed { index, item ->
                        FilledTonalIconButton(onClick = { selected = index }, modifier = Modifier.size(34.dp), colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (selected == index) DefaultAccent else Color.White.copy(alpha = .06f), contentColor = if (selected == index) Color(0xFF101412) else Color.White.copy(alpha = .6f))) {
                            Text("${index + 1}", modifier = Modifier.semantics { contentDescription = item.title }, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text("${selected + 1} de ${welcomeCombinations.size}", Modifier.padding(start = 6.dp), color = Color.White.copy(alpha = .4f), style = MaterialTheme.typography.labelMedium)
                } else Text(if (section == 1) "Arrastra desde la barra superior" else "Horizontal · altura mínima · fijado", color = Color.White.copy(alpha = .45f), style = MaterialTheme.typography.labelMedium)
                Button(onClick = close, contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp)) { Text("Empezar") }
            }
        }
    }
}

/** WebP animado local: un decodificador y un bitmap, sin audio, red ni procesos de vídeo. */
@Composable
private fun WelcomeAnimation(clip: WelcomeClip, playing: Boolean, onEnd: () -> Unit) {
    val shouldPlay by rememberUpdatedState(playing)
    val finished by rememberUpdatedState(onEnd)
    var bitmap by remember(clip.asset) { mutableStateOf<Bitmap?>(null) }
    var frame by remember(clip.asset) { mutableIntStateOf(0) }
    var failed by remember(clip.asset) { mutableStateOf(false) }
    LaunchedEffect(clip.asset) {
        try {
            val bytes = withContext(Dispatchers.IO) {
                checkNotNull(WelcomeClip::class.java.getResourceAsStream("/welcome/${clip.asset}.webp")) { "Falta la demostración ${clip.asset}" }.use { it.readBytes() }
            }
            Data.makeFromBytes(bytes).use { data ->
                Codec.makeFromData(data).use { codec ->
                    Bitmap().use { decoded ->
                        check(decoded.allocN32Pixels(codec.width, codec.height))
                        val timing = codec.framesInfo
                        var index = 0
                        bitmap = decoded
                        try {
                            while (isActive) {
                                while (!shouldPlay) delay(80)
                                codec.readPixels(decoded, index, if (index == 0) -1 else index - 1)
                                frame++
                                // Honour pauses during a frame as well as between frames.
                                var remaining = timing[index].duration.coerceAtLeast(20)
                                while (remaining > 0) {
                                    delay(20)
                                    if (shouldPlay) remaining -= 20
                                }
                                index++
                                if (index == codec.frameCount) {
                                    finished()
                                    index = 0
                                }
                            }
                        } finally { bitmap = null }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { timber.log.Timber.w(error, "No se pudo abrir la bienvenida"); failed = true }
    }
    if (failed) Text("Puedes combinar las ventanas desde sus iconos y arrastrarlas desde la barra superior.", Modifier.padding(32.dp), color = Color.White.copy(alpha = .7f))
    else Canvas(Modifier.fillMaxSize().semantics { contentDescription = clip.title }) {
        frame // Read in the drawing phase so only the canvas invalidates on a frame.
        bitmap?.let { decoded ->
            val scale = minOf(size.width / decoded.width, size.height / decoded.height)
            val width = decoded.width * scale
            val height = decoded.height * scale
            val x = (size.width - width) / 2
            val y = (size.height - height) / 2
            drawIntoCanvas { canvas ->
                Image.makeFromBitmap(decoded).use { image -> canvas.nativeCanvas.drawImageRect(image, Rect.makeXYWH(x, y, width, height)) }
            }
        }
    }
}
