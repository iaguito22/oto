package io.github.iaguito22.oto.ui.menu

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Space

/** Al final de una lista tuya: parecidas, con un + para meterlas; «Otras» trae otra tanda. */
@Composable
fun Suggestions(list: LocalPlaylist, currentId: String?, play: (SongItem) -> Unit, onHold: (SongItem) -> Unit, onAdd: (Heard) -> Unit) {
    val c = Oto.colors
    val haptics = LocalHapticFeedback.current
    var round by rememberSaveable(list.id) { mutableIntStateOf(0) }
    val songs by produceState<List<SongItem>?>(null, list.id, round) {
        value = null
        value = runCatching { Suggest.forList(list, round) }.getOrDefault(emptyList())
    }
    val inList = remember(list) { list.songs.mapTo(HashSet()) { it.id } }
    Column(Modifier.fillMaxWidth().padding(top = Space.lg)) {
        Row(Modifier.padding(end = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = Space.sm)) {
                Text("Recomendadas", style = OtoType.section, color = c.text)
                Text("Parecidas a lo que tiene esta lista", style = OtoType.rowSub, color = c.textMuted)
            }
            Pill("Otras", { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); round++ }, filled = false, icon = R.drawable.ic_refresh)
        }
        AnimatedContent(
            targetState = songs,
            contentKey = { it == null },
            transitionSpec = { fadeIn(tween(Motion.EFFECTS, delayMillis = 40)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) },
            label = "suggestions",
        ) { shown ->
            Column(Modifier.padding(top = Space.sm)) {
                when {
                    shown == null -> Skeleton(rows = 4)
                    shown.isEmpty() -> Text("No han llegado: prueba con «Otras».", style = OtoType.rowSub, color = c.textMuted, modifier = Modifier.padding(Space.sm))
                    // Las que se van añadiendo desaparecen de aquí (ya están arriba).
                    else -> shown.filter { it.id !in inList }.forEach { s ->
                        SongRow(
                            s.title, s.subtitle(), s.thumbnail, s.id == currentId, onClick = { play(s) }, onLongClick = { onHold(s) },
                            trailing = {
                                RoundAction(R.drawable.ic_add, "Añadir a la lista") {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    onAdd(Heard(s.id, s.title, s.artists.joinToString { it.name }, s.thumbnail, s.artists.firstOrNull()?.id))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
