package io.github.iaguito22.oto.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.iaguito22.oto.remote.LocalLink
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Controles del equipo remoto; la cola se abre solo cuando hace falta. */
@Composable
internal fun RemoteActions(link: LocalLink) {
    val state by link.remote.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    fun send(action: String, fill: (JSONObject) -> Unit = {}) {
        if (pending) return
        pending = true
        error = null
        scope.launch {
            try { link.command(JSONObject().put("action", action).also(fill)) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: "No se pudo completar la acción" }
            finally { pending = false }
        }
    }
    val remote = state ?: return
    val id = remote.optString("id")
    val hasSong = id.isNotBlank() && id != "null"
    val queue = remote.optJSONArray("queue")
    val current = remote.optInt("index", -1)
    val artwork = remote.optString("artwork").takeUnless { it.isBlank() || it == "null" }
        ?: queue?.optJSONObject(current)?.optString("artwork")
    val accent = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = accent.copy(alpha = .055f)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    RemoteArtwork(artwork, Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (remote.optBoolean("buffering")) "Preparando música…" else if (remote.optBoolean("playing")) "Sonando allí" else if (hasSong) "En pausa" else "Listo para escuchar", color = accent, style = MaterialTheme.typography.labelSmall)
                        Text(remote.optString("title").takeIf { hasSong && it.isNotBlank() } ?: "Elige una canción", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (hasSong) Text(remote.optString("artist"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                    }
                }
                if (hasSong) {
                    var seeking by remember(id) { mutableStateOf<Float?>(null) }
                    val duration = remote.optLong("duration").coerceAtLeast(0)
                    val fraction = seeking ?: (remote.optLong("position").toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)
                    if (duration > 0) Column {
                        RemoteSlider(fraction, { seeking = it }, { seeking?.let { value -> send("seek") { it.put("position", (value * duration).toLong()) } }; seeking = null }, "Posición en el dispositivo")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(remoteTime((fraction * duration).toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .4f))
                            Text(remoteTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .4f))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        RemoteIcon(Icons.Rounded.Shuffle, "Aleatorio", !pending, remote.optBoolean("shuffle")) { send("shuffle") { it.put("value", !remote.optBoolean("shuffle")) } }
                        RemoteIcon(Icons.Rounded.SkipPrevious, "Anterior", !pending) { send("previous") }
                        RemotePlay(remote.optBoolean("playing"), !pending) { send("toggle") }
                        RemoteIcon(Icons.Rounded.SkipNext, "Siguiente", !pending) { send("next") }
                        RemoteIcon(Icons.Rounded.Repeat, "Repetir", !pending, remote.optBoolean("loop")) { send("loop") { it.put("value", !remote.optBoolean("loop")) } }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volumen del dispositivo", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
            var volume by remember { mutableStateOf<Float?>(null) }
            val level = volume ?: remote.optDouble("volume", .5).toFloat().coerceIn(0f, 1f)
            Box(Modifier.weight(1f)) { RemoteSlider(level, { volume = it }, { volume?.let { v -> send("volume") { it.put("value", v) } }; volume = null }, "Volumen del dispositivo") }
            Text("${(level * 100).toInt()} %", Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
            if (hasSong) RemoteIcon(Icons.Rounded.FavoriteBorder, "Alternar Me gusta", !pending) { send("like") }
        }
        var expanded by remember { mutableStateOf(false) }
        var shown by remember(id) { mutableIntStateOf(8) }
        val count = queue?.length() ?: 0
        RemoteDisclosure("Cola", if(count==1) "1 canción" else "$count canciones", expanded, { expanded = !expanded })
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (count == 0) Text("La próxima canción aparecerá aquí.", style = MaterialTheme.typography.bodySmall)
                for (i in 0 until minOf(count, shown)) {
                    val song = queue!!.getJSONObject(i)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (i == current) accent.copy(alpha = .09f) else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable(enabled = !pending) { send("skip") { it.put("index", i) } }.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (i == current) "•" else "${i + 1}", Modifier.width(20.dp), color = if (i == current) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = .35f), style = MaterialTheme.typography.labelSmall)
                            Column(Modifier.weight(1f)) {
                                Text(song.optString("title"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(song.optString("artist"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .4f))
                            }
                        }
                        var menu by remember(song.optString("id"), i) { mutableStateOf(false) }
                        Box {
                            RemoteIcon(Icons.Rounded.MoreHoriz, "Opciones de ${song.optString("title")}") { menu = true }
                            DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(20.dp)) {
                                if (i > 0) DropdownMenuItem(text = { Text("Mover antes") }, enabled = !pending, onClick = { menu = false; send("move") { it.put("from", i).put("to", i - 1) } })
                                if (i < count - 1) DropdownMenuItem(text = { Text("Mover después") }, enabled = !pending, onClick = { menu = false; send("move") { it.put("from", i).put("to", i + 1) } })
                                DropdownMenuItem(text = { Text("Quitar de la cola") }, enabled = !pending, onClick = { menu = false; send("remove") { it.put("index", i) } })
                            }
                        }
                    }
                }
                if (shown < count) TextButton(onClick = { shown += 20 }) { Text("Ver más · ${count - shown} restantes") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun remoteTime(ms: Long): String = "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"

@Composable
internal fun RemoteIcon(icon: ImageVector, label: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered && enabled) 1.14f else 1f, tween(150), label = "remoteIcon")
    Box(Modifier.size(44.dp).hoverable(interaction).clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick).semantics { contentDescription = label; role = Role.Button }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(21.dp * scale), tint = (if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface).copy(alpha = if (!enabled) .3f else if (selected) 1f else .75f))
    }
}

@Composable
private fun RemotePlay(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.15f else 1f, tween(150), label = "remotePlay")
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.size(52.dp).hoverable(interaction).clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = .12f)).clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick).semantics { contentDescription = if (playing) "Pausar" else "Reproducir"; role = Role.Button }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp * scale)) {
            if (playing) for (x in listOf(.34f, .68f)) drawLine(color, Offset(size.width*x, size.height*.18f), Offset(size.width*x, size.height*.82f), 1.8.dp.toPx(), StrokeCap.Round)
            else drawPath(Path().apply { moveTo(size.width*.3f,size.height*.16f); lineTo(size.width*.82f,size.height*.5f); lineTo(size.width*.3f,size.height*.84f); close() }, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun RemoteDisclosure(title: String, subtitle: String, expanded: Boolean, onClick: () -> Unit) {
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if(hovered)1.06f else 1f,tween(150),label="disclosureHover")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).hoverable(interaction).clickable(interactionSource=interaction,indication=null,onClick=onClick).padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontSize=MaterialTheme.typography.titleSmall.fontSize*scale))
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .4f))
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.padding(start = 10.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .4f))
    }
}

@Composable
internal fun DeviceIdentity(name: String, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .1f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Devices, null, Modifier.size(27.dp), tint = MaterialTheme.colorScheme.primary) }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(if (connected) "Conectado · Red local" else "Tu música, entre dispositivos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary.copy(alpha = .7f))
        }
    }
}

@Composable
internal fun TransferChoices(localName: String, remoteName: String, enabled: Boolean, canBring: Boolean, canSend: Boolean, here: () -> Unit, there: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Continuar escuchando", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Triple(localName, "Traer aquí", here), Triple(remoteName, "Enviar allí", there)).forEachIndexed { index, (name, hint, action) ->
                val available = enabled && (if(index==0) canBring else canSend)
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val size by animateFloatAsState(if (hovered && available) 1.06f else 1f, tween(150), label = "transferHover")
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = if (index == 0) .12f else .05f)).clickable(interactionSource = interaction, indication = null, enabled = available, onClick = action).padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(if (index == 0) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast, null, Modifier.size(22.dp * size), tint = MaterialTheme.colorScheme.primary.copy(alpha = if (available) 1f else .3f))
                    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (available) 1f else .35f))
                    Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
                }
            }
        }
    }
}

@Composable private fun RemoteArtwork(url: String?, modifier: Modifier) = io.github.iaguito22.oto.desktop.ui.LibraryArt(url, modifier)
@Composable private fun RemoteSlider(value: Float, onValueChange: (Float)->Unit, onValueChangeFinished: ()->Unit, label: String) = io.github.iaguito22.oto.desktop.ui.FineSlider(value, onValueChange, label=label, onFinish=onValueChangeFinished)
