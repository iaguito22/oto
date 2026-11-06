package io.github.iaguito22.oto.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ViewQuilt
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

internal data class WelcomeClip(val asset: String, val step: String, val title: String, val description: String)

/** Grabados por Iago el 06/11/2026 (Recortes de Windows, 30 fps) y codificados a 1280 px (WebP q82). */
internal val welcomeSteps = listOf(
    WelcomeClip("01-unir-ventanas", "Unir", "Une las ventanas",
        "Oto se reparte en ventanas: reproductor, tu música, letra y cola. Ábrelas con los botones del reproductor y se colocan juntas, como una sola."),
    WelcomeClip("02-cambiar-orden", "Ordenar", "Cambia el orden",
        "Arrastra la barra superior de una ventana sobre otra y se intercambian. La del punto (·) es la principal: con ella mueves todo el conjunto."),
    WelcomeClip("03-redimensionar", "Tamaño", "Ajusta el tamaño",
        "Arrastra la unión entre dos ventanas para repartir el espacio, o los bordes y las esquinas para cambiar el tamaño del conjunto."),
    WelcomeClip("04-vistas-reproductor", "Reproductor", "Elige tu reproductor",
        "En ··· cambia entre carátula, horizontal y vertical. El pequeño se puede fijar encima de todo con la chincheta."),
    WelcomeClip("05-atajo-global", "Atajo", "Escóndelo y vuelve",
        "Ctrl + Alt + O oculta o recupera Oto desde cualquier sitio (lo cambias en Ajustes). La X lo deja en la bandeja, junto al reloj."),
)

/** Cinco pasos con su demostración: avanzan solos al terminar cada clip y se pueden elegir. */
@Composable
internal fun WelcomeWindow(close: () -> Unit) {
    val state = rememberWindowState(position = WindowPosition(Alignment.Center), width = 1040.dp, height = 780.dp)
    ModuleWindow("Bienvenida", state, true, close, accent = DefaultAccent, minimum = DpSize(780.dp, 620.dp), keys = {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false
    }) {
        WelcomeContent(close)
    }
}

@Composable
private fun WelcomeContent(close: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    val clip = welcomeSteps[step]
    val last = step == welcomeSteps.lastIndex
    Surface(Modifier.fillMaxSize(), color = Color(0xFF121715)) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bienvenido a Oto", style = MaterialTheme.typography.headlineMedium)
                    Text("Cinco gestos para tenerlo a tu manera.", color = Color.White.copy(alpha = .55f), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = close) { Text("Omitir") }
            }
            // Los pasos: el actual lleva debajo cuánto queda de su demostración.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                welcomeSteps.forEachIndexed { i, s ->
                    val on = i == step
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { step = i; playing = true }.padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${i + 1}  ${s.step}", maxLines = 1, style = MaterialTheme.typography.labelLarge,
                            color = if (on) Color.White else Color.White.copy(alpha = if (i < step) .6f else .4f))
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .1f))) {
                            val filled = if (i < step) 1f else if (on) progress else 0f
                            Box(Modifier.fillMaxHeight().fillMaxWidth(filled).background(if (on) DefaultAccent else Color.White.copy(alpha = .45f)))
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF0D1210)).border(1.dp, Color.White.copy(alpha = .09f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                WelcomeAnimation(clip, playing, onProgress = { progress = it }, onEnd = { if (!last) step++ })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(clip.title, style = MaterialTheme.typography.titleMedium)
                    Text(clip.description, color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { playing = !playing }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pausar demostración" else "Reproducir demostración") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${step + 1} de ${welcomeSteps.size}", Modifier.weight(1f), color = Color.White.copy(alpha = .4f), style = MaterialTheme.typography.labelMedium)
                if (step > 0) OutlinedButton(onClick = { step--; playing = true }) { Text("Anterior") }
                Button(onClick = { if (last) close() else { step++; playing = true } }, contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp)) {
                    Text(if (last) "Empezar" else "Siguiente")
                }
            }
        }
    }
}

/** WebP animado local: un decodificador y un bitmap, sin audio, red ni procesos de vídeo. */
@Composable
private fun WelcomeAnimation(clip: WelcomeClip, playing: Boolean, onProgress: (Float) -> Unit, onEnd: () -> Unit) {
    val shouldPlay by rememberUpdatedState(playing)
    val finished by rememberUpdatedState(onEnd)
    val progress by rememberUpdatedState(onProgress)
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
                                progress((index + 1f) / codec.frameCount)
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
    if (failed) Text(clip.description, Modifier.padding(32.dp), color = Color.White.copy(alpha = .7f))
    else Canvas(Modifier.fillMaxSize().semantics { contentDescription = clip.title }) {
        frame // Read in the drawing phase so only the canvas invalidates on a frame.
        bitmap?.let { decoded ->
            val scale = minOf(size.width / decoded.width, size.height / decoded.height)
            val width = decoded.width * scale
            val height = decoded.height * scale
            val x = (size.width - width) / 2
            val y = (size.height - height) / 2
            drawIntoCanvas { canvas ->
                // Con filtrado: sin él, al reducir el clip salían bordes dentados (parte de la «baja calidad»).
                Image.makeFromBitmap(decoded).use { image ->
                    canvas.nativeCanvas.drawImageRect(image, Rect.makeWH(decoded.width.toFloat(), decoded.height.toFloat()), Rect.makeXYWH(x, y, width, height),
                        org.jetbrains.skia.CubicResampler(1f / 3, 1f / 3), null, true)
                }
            }
        }
    }
}
