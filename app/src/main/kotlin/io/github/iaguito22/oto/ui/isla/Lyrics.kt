package io.github.iaguito22.oto.ui.isla

import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import io.github.iaguito22.oto.playback.NowPlaying
import io.github.iaguito22.oto.ui.menu.Skeleton
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface Lyrics {
    data object Loading : Lyrics
    data object Missing : Lyrics
    data class Plain(val lines: List<String>) : Lyrics
}

// Ir y volver de la letra no vuelve a pedirla.
private val cache = LruCache<String, Lyrics>(32)

/**
 * La letra en el sitio de la carátula, con los bordes desvanecidos. De momento la de YouTube Music, sin
 * sincronizar; la palabra a palabra (lyricsplus, Netease, LRCLIB) es la fase 5.
 */
@Composable
fun LyricsPanel(np: NowPlaying, side: Dp) {
    val lyrics by produceState(np.id?.let(cache::get) ?: Lyrics.Loading, np.id) {
        val id = np.id ?: run { value = Lyrics.Missing; return@produceState }
        cache.get(id)?.let { value = it; return@produceState }
        value = Lyrics.Loading
        val text = withContext(Dispatchers.IO) {
            runCatching { YouTube.next(WatchEndpoint(videoId = id)).getOrThrow().lyricsEndpoint?.let { YouTube.lyrics(it).getOrThrow() } }
        }
        // Un fallo de red no se guarda: la próxima vez se vuelve a intentar.
        val result = text.getOrNull()
        value = if (result.isNullOrBlank()) Lyrics.Missing else Lyrics.Plain(result.trim().lines())
        if (text.isSuccess) cache.put(id, value)
    }
    val c = Oto.colors
    Box(
        Modifier
            .size(side)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = 36.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), endY = fade), size = Size(size.width, fade), blendMode = BlendMode.DstOut)
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = size.height - fade, endY = size.height),
                    Offset(0f, size.height - fade), Size(size.width, fade), blendMode = BlendMode.DstOut,
                )
            },
    ) {
        AnimatedContent(
            targetState = np.id to lyrics,
            contentKey = { (id, l) -> id to (l is Lyrics.Plain) },
            transitionSpec = { fadeIn(tween(Motion.EFFECTS, delayMillis = 60)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) },
            label = "lyrics",
        ) { (_, l) ->
            when (l) {
                Lyrics.Loading -> Skeleton(rows = 5, modifier = Modifier.padding(top = Space.xl))
                Lyrics.Missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (np.id == null) "No suena nada" else "YouTube Music no tiene\nla letra de esta canción",
                        style = OtoType.body, color = c.textMuted, textAlign = TextAlign.Center,
                    )
                }
                is Lyrics.Plain -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 32.dp)) {
                    item {
                        Row(Modifier.padding(bottom = Space.md).clip(CircleShape).background(c.field).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("Sin sincronizar", style = OtoType.label, color = c.textMuted)
                        }
                    }
                    items(l.lines.size) { i ->
                        val line = l.lines[i]
                        if (line.isBlank()) Spacer(Modifier.height(Space.lg))
                        else Text(
                            line,
                            style = OtoType.lyric.copy(fontSize = OtoType.lyric.fontSize * 0.8f, lineHeight = OtoType.lyric.lineHeight * 0.8f),
                            color = c.text, modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

