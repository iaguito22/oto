package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.dj.Recap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val es = Locale.forLanguageTag("es")

internal fun recapHours(minutes: Long) = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0L -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/** La tarjeta de Explorar que abre el resumen. */
@androidx.compose.runtime.Composable
internal fun RecapCard(recap: Recap, palette: ArtworkPalette, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.primary.copy(alpha = .14f)).clickable(onClick = onOpen).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("TU RESUMEN", style = OtoType.label, color = palette.primary)
            Text("${recapHours(recap.minutes)} escuchadas", style = OtoType.section)
            recap.topArtists.firstOrNull()?.let { Text("Lo que más: ${it.name}", style = OtoType.rowSub, color = Color.White.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(MobileIcons.arrowBack, null, Modifier.size(20.dp).graphicsLayer { rotationZ = 180f }, tint = Color.White.copy(alpha = .5f))
    }
}

/**
 * «Tu resumen»: lo escuchado en este ordenador (ver [Recap]). [artwork] busca la carátula de una canción entre lo
 * tuyo, porque el diario de escuchas no la guarda.
 */
@androidx.compose.runtime.Composable
internal fun RecapSpace(recap: Recap, palette: ArtworkPalette, artwork: (String) -> String?, onPlay: (Recap.Song) -> Unit, onArtist: (String, String) -> Unit, onBack: () -> Unit) {
    val since = remember(recap.since) { Instant.ofEpochMilli(recap.since).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d 'de' MMMM", es)) }
    // Las barras crecen al entrar, con un poco de rebote.
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, spring(dampingRatio = .62f, stiffness = 120f)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HoverAction(MobileIcons.arrowBack, "Volver", onClick = onBack)
                Text("Tu resumen", style = OtoType.display)
                Text(if (recap.empty) "Aún no hay escuchas en este ordenador" else "Desde el $since · en este ordenador", style = OtoType.rowSub, color = Color.White.copy(alpha = .5f))
            }
        }
        if (recap.empty) {
            item(key = "empty") { Text("Escucha unas cuantas canciones y aquí verás lo que más suena.", style = OtoType.body, color = Color.White.copy(alpha = .6f)) }
            return@LazyColumn
        }
        item(key = "numbers") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                @androidx.compose.runtime.Composable fun Stat(value: String, label: String, modifier: Modifier) =
                    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .05f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(value, style = OtoType.section, maxLines = 1)
                        Text(label, style = OtoType.rowSub, color = Color.White.copy(alpha = .5f), maxLines = 1)
                    }
                Stat(recapHours(recap.minutes), "escuchadas", Modifier.weight(1f))
                Stat("${recap.songs}", "canciones", Modifier.weight(1f))
                Stat("${recap.artists}", "artistas", Modifier.weight(1f))
                recap.hour?.let { Stat("%02d:00".format(it), "tu hora", Modifier.weight(1f)) }
            }
        }
        item(key = "songs") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tus canciones", style = OtoType.section, modifier = Modifier.padding(bottom = 8.dp))
                recap.topSongs.forEachIndexed { i, s ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPlay(s) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("${i + 1}", style = OtoType.section, color = if (i == 0) palette.primary else Color.White.copy(alpha = .4f), modifier = Modifier.width(22.dp))
                        LibraryArt(artwork(s.id), Modifier.size(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = OtoType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, style = OtoType.rowSub, color = Color.White.copy(alpha = .45f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (s.times == 1) "1 vez" else "${s.times} veces", style = OtoType.rowSub, color = Color.White.copy(alpha = .45f))
                    }
                }
            }
        }
        item(key = "artists") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tus artistas", style = OtoType.section, modifier = Modifier.padding(bottom = 4.dp))
                val most = recap.topArtists.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
                recap.topArtists.forEachIndexed { i, a ->
                    val open = a.id?.let { id -> Modifier.clickable { onArtist(id, a.name) } } ?: Modifier
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(open).padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}  ${a.name}", style = OtoType.rowTitle, fontWeight = if (i == 0) FontWeight.Bold else null, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(recapHours(a.minutes), style = OtoType.rowSub, color = Color.White.copy(alpha = .45f))
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = .06f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((a.minutes.toFloat() / most * grow.value).coerceIn(0f, 1f))
                                .clip(CircleShape).background(if (i == 0) palette.primary else Color.White.copy(alpha = .35f)))
                        }
                    }
                }
            }
        }
        if (recap.months.size > 1) item(key = "months") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Mes a mes", style = OtoType.section)
                val most = recap.months.maxOf { it.minutes }.coerceAtLeast(1)
                Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    recap.months.takeLast(12).forEach { m ->
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Text(recapHours(m.minutes), style = OtoType.rowSub, color = Color.White.copy(alpha = .45f), maxLines = 1)
                            Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().fillMaxHeight((.75f * m.minutes / most * grow.value).coerceIn(.02f, .8f))
                                .clip(RoundedCornerShape(8.dp)).background(if (m.minutes == most) palette.primary else Color.White.copy(alpha = .2f)))
                            Text(m.month.month.getDisplayName(TextStyle.SHORT, es), style = OtoType.rowSub, color = Color.White.copy(alpha = .6f))
                        }
                    }
                }
            }
        }
        if (recap.djFinds > 0) item(key = "dj") {
            Text(if (recap.djFinds == 1) "El DJ te descubrió 1 canción que escuchaste entera." else "El DJ te descubrió ${recap.djFinds} canciones que escuchaste enteras.",
                style = OtoType.body, color = Color.White.copy(alpha = .7f))
        }
    }
}
