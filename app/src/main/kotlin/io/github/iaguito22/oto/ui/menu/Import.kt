package io.github.iaguito22.oto.ui.menu

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.importer.ImportState
import io.github.iaguito22.oto.importer.Matcher
import io.github.iaguito22.oto.importer.SourceList
import io.github.iaguito22.oto.importer.Spotify
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Radius
import io.github.iaguito22.oto.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** Lo que dice la fila de Ajustes según cómo vaya. */
fun importSummary(s: ImportState) = when (s) {
    ImportState.Idle -> "Tus listas y tus «me gusta»"
    is ImportState.Running -> "Importando: ${percent(s.done, s.total)} % · lista ${s.index + 1} de ${s.lists}"
    is ImportState.Done ->
        if (s.stopped) "Parado a medias: ${num(s.songs)} canciones guardadas"
        else "Hecho: ${lists(s.lists)} y ${num(s.songs)} canciones"
}

private enum class Step { Source, Pick, Running, Done }

/**
 * Traer listas de Spotify: pegar el enlace de una pública o abrir el archivo que exporta la extensión de Spicetify
 * (todas, también las privadas y «Canciones que te gustan»). Luego se eligen cuáles y se buscan en YouTube Music.
 */
@Composable
fun Import(onBack: () -> Unit, onLists: () -> Unit) {
    val c = Oto.colors
    val app = LocalContext.current.applicationContext as OtoApp
    val importer = app.importer
    val state by importer.state.collectAsStateWithLifecycle()
    val playlists by app.prefs.playlists.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<List<SourceList>?>(null) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }

    val show: (List<SourceList>) -> Unit = { l ->
        loaded = l
        // Por defecto, lo tuyo: tus listas y lo que te gusta. Las que sigues, si las quieres, se marcan a mano.
        picked = l.filter { it.own || it.liked }.mapTo(HashSet()) { it.id }
        error = null
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    Spotify.parseExport(text)
                }
            }.onSuccess(show).onFailure { error = "Ese archivo no es el que exporta «Exportar a Oto»" }
            reading = false
        }
    }
    val link: (String) -> Unit = { text ->
        reading = true
        error = null
        scope.launch {
            withContext(Dispatchers.IO) { Spotify.fromLink(text) }
                .onSuccess { show(listOf(it)) }
                .onFailure { error = it.message ?: "No se pudo abrir ese enlace" }
            reading = false
        }
    }

    val step = when {
        state is ImportState.Running -> Step.Running
        loaded != null -> Step.Pick
        state is ImportState.Done -> Step.Done
        else -> Step.Source
    }
    // Desde la selección, atrás vuelve a elegir de dónde. Va después del del Menú: gana él.
    BackHandler(step == Step.Pick) { loaded = null }

    Column(Modifier.fillMaxSize().padding(horizontal = Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(R.drawable.ic_arrow_back, onClick = { if (step == Step.Pick) loaded = null else onBack() })
            Text("Importar de Spotify", style = OtoType.title, color = c.text)
        }
        Spacer(Modifier.height(Space.sm))
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(Motion.EFFECTS, delayMillis = 40)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) },
            modifier = Modifier.weight(1f),
            label = "importStep",
        ) { s ->
            when (s) {
                Step.Source -> Sources(reading, error, onLink = link, onFile = { file.launch(arrayOf("*/*")) })
                Step.Pick -> Pick(
                    loaded.orEmpty(), picked, imported = playlists.orEmpty().mapNotNullTo(HashSet()) { it.from },
                    onToggle = { id -> picked = if (id in picked) picked - id else picked + id },
                    onAll = { all -> picked = if (all) loaded.orEmpty().mapTo(HashSet()) { it.id } else emptySet() },
                    onStart = {
                        importer.start(loaded.orEmpty().filter { it.id in picked })
                        loaded = null
                    },
                )
                Step.Running -> (state as? ImportState.Running)?.let { Progress(it, onStop = importer::stop) }
                Step.Done -> (state as? ImportState.Done)?.let {
                    Summary(it, onLists = { importer.dismiss(); onLists() }, onMore = importer::dismiss)
                }
            }
        }
    }
}

@Composable
private fun Sources(reading: Boolean, error: String?, onLink: (String) -> Unit, onFile: () -> Unit) {
    val c = Oto.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Space.lg)) {
        Block("Una lista o un álbum", "Pega su enlace (Compartir → Copiar enlace). Tiene que ser pública, y sin iniciar sesión Spotify solo enseña las 100 primeras canciones.") {
            LinkField(reading, onLink)
        }
        Block(
            "Todas tus listas, desde el PC",
            "1. En el PC, abre github.com/iaguito22/oto/tree/main/tools/spicetify y pega la línea de tu sistema (en Windows, en PowerShell). Lo instala todo solo, también Spicetify si no lo tienes.\n" +
                "2. En Spotify, tu foto de perfil → «Exportar a Oto». Se guarda oto-spotify.json en Descargas.\n" +
                "3. Pásalo al móvil y ábrelo aquí. Trae todo: las privadas, las que sigues y «Canciones que te gustan».",
        ) {
            Pill(if (reading) "Leyendo…" else "Abrir archivo", onFile, icon = R.drawable.ic_library_music)
        }
        if (error != null) Text(
            error, style = OtoType.rowSub, color = c.text,
            modifier = Modifier.padding(Space.sm).clip(RoundedCornerShape(Radius.md)).background(c.field).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
internal fun Block(title: String, body: String, action: @Composable () -> Unit) {
    val c = Oto.colors
    Column(
        Modifier
            .padding(vertical = Space.xs)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(c.field)
            .padding(Space.md),
    ) {
        Text(title, style = OtoType.rowTitle, color = c.text)
        Spacer(Modifier.height(4.dp))
        Text(body, style = OtoType.rowSub, color = c.textMuted)
        Spacer(Modifier.height(Space.md))
        action()
    }
}

@Composable
private fun LinkField(reading: Boolean, onGo: (String) -> Unit) {
    val c = Oto.colors
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    val ok = Spotify.parseLink(text) != null
    val go = { if (ok && !reading) { focus.clearFocus(); onGo(text) } }
    // Mientras Spotify contesta, el botón respira.
    val breath by rememberInfiniteTransition(label = "reading").animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "breath")
    Row(
        Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.field).padding(start = 18.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            val style = OtoType.body
            if (text.isEmpty()) Text("open.spotify.com/playlist/…", style = style, color = c.textFaint, maxLines = 1)
            BasicTextField(
                value = text,
                onValueChange = { text = it.trim() },
                singleLine = true,
                textStyle = style.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            Modifier
                .size(40.dp)
                .graphicsLayer { alpha = if (reading) breath else if (ok) 1f else 0.4f }
                .clip(CircleShape)
                .background(c.accent)
                .clickable(remember { MutableInteractionSource() }, ripple(color = c.onAccent)) { go() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_arrow_back), null, Modifier.size(20.dp).graphicsLayer { rotationZ = 180f }, tint = c.onAccent)
        }
    }
}

@Composable
private fun Pick(
    lists: List<SourceList>,
    picked: Set<String>,
    imported: Set<String>,
    onToggle: (String) -> Unit,
    onAll: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    val c = Oto.colors
    val chosen = lists.filter { it.id in picked }
    // Lo que se va a buscar de verdad: cada canción una vez, aunque esté en varias listas. Las claves de cada lista se
    // sacan una sola vez y todo fuera del hilo de la pantalla: normalizar miles de canciones en cada toque daba tirones
    // de 70-150 ms con muchas listas. Mientras se cuenta, se queda la cifra anterior.
    val keys by produceState<Map<String, Set<String>>?>(null, lists) {
        value = withContext(Dispatchers.Default) {
            lists.associate { l -> l.id to l.tracks.mapTo(HashSet()) { Matcher.norm(it.title) + "|" + Matcher.norm(it.artists.firstOrNull().orEmpty()) } }
        }
    }
    val unique by produceState<Int?>(null, keys, picked) {
        val k = keys ?: return@produceState
        value = withContext(Dispatchers.Default) { picked.flatMapTo(HashSet()) { k[it].orEmpty() }.size }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            // La lista se funde al llegar al botón en vez de cortarse contra él.
            Modifier
                .weight(1f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = 24.dp.toPx()
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = size.height - fade, endY = size.height),
                        Offset(0f, size.height - fade), Size(size.width, fade), blendMode = BlendMode.DstOut,
                    )
                },
            contentPadding = PaddingValues(bottom = Space.lg),
        ) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(start = Space.md, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text("ELIGE QUÉ TRAER", style = OtoType.label, color = c.textMuted, modifier = Modifier.weight(1f))
                    if (lists.size > 1) {
                        val all = picked.size == lists.size
                        Text(
                            if (all) "Ninguna" else "Todas", style = OtoType.rowTitle, color = c.accent,
                            modifier = Modifier.clip(CircleShape).clickable { onAll(!all) }.padding(horizontal = Space.md, vertical = Space.sm),
                        )
                    }
                }
            }
            items(lists, key = { it.id }) { l ->
                val sub = listOfNotNull(
                    count(l.tracks.size),
                    "a Me gusta".takeIf { l.liked },
                    "la sigues".takeIf { !l.own && !l.liked },
                    "ya importada, se rehará".takeIf { l.id in imported },
                    "solo las 100 primeras".takeIf { l.partial },
                ).joinToString(" · ")
                ToggleRow(
                    { if (l.liked) LikedArt(40.dp) else Art(l.image, 40.dp, Radius.sm) },
                    if (l.liked) "Canciones que te gustan" else l.name, sub, l.id in picked,
                ) { onToggle(l.id) }
            }
        }
        // Abajo el Menú funde lo que hay antes del buscador: el botón se queda por encima de ese fundido.
        Row(Modifier.fillMaxWidth().padding(start = Space.md, end = Space.xs, top = Space.sm, bottom = Space.xl), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (chosen.isEmpty()) Text("Marca alguna", style = OtoType.rowSub, color = c.textMuted)
                else unique?.let { n ->
                    Text("${num(n)} canciones distintas", style = OtoType.rowSub, color = c.text, maxLines = 1)
                    Text(minutes(n), style = OtoType.rowSub, color = c.textMuted, maxLines = 1)
                }
            }
            Box(Modifier.graphicsLayer { alpha = if (chosen.isEmpty()) 0.4f else 1f }) {
                Pill(if (chosen.size == 1) "Importar" else "Importar ${chosen.size}", { if (chosen.isNotEmpty()) onStart() }, icon = R.drawable.ic_check)
            }
        }
    }
}

@Composable
private fun Progress(s: ImportState.Running, onStop: () -> Unit) {
    val c = Oto.colors
    val f by animateFloatAsState(if (s.total == 0) 0f else s.done.toFloat() / s.total, spring(dampingRatio = 1f, stiffness = 120f), label = "progress")
    Column(Modifier.fillMaxSize().padding(horizontal = Space.md), verticalArrangement = Arrangement.Center) {
        Text("LISTA ${s.index + 1} DE ${s.lists}", style = OtoType.label, color = c.textMuted)
        Spacer(Modifier.height(4.dp))
        Text(s.list, style = OtoType.title, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Space.lg))
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.field).drawBehind {
                drawRoundRect(c.accent, size = Size(size.width * f, size.height), cornerRadius = CornerRadius(size.height))
            },
        )
        Spacer(Modifier.height(Space.sm))
        Row {
            Text("${num(s.done)} de ${num(s.total)} canciones", style = OtoType.rowSub, color = c.text, modifier = Modifier.weight(1f))
            Text("${percent(s.done, s.total)} %", style = OtoType.rowSub, color = c.textMuted)
        }
        if (s.missing > 0) Text("${num(s.missing)} sin encontrar", style = OtoType.rowSub, color = c.textMuted)
        Spacer(Modifier.height(Space.lg))
        Text(
            "Cada lista se guarda en cuanto acaba. Puedes cerrar el Menú y seguir oyendo música mientras tanto, con Oto abierto.",
            style = OtoType.rowSub, color = c.textMuted,
        )
        Spacer(Modifier.height(Space.lg))
        Pill("Parar", onStop, filled = false, icon = R.drawable.ic_close)
    }
}

@Composable
private fun Summary(s: ImportState.Done, onLists: () -> Unit, onMore: () -> Unit) {
    val c = Oto.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.lg)) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = Space.md, vertical = Space.sm)) {
                Text(if (s.stopped) "Parado a medias" else "Hecho", style = OtoType.title, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${lists(s.lists)} y ${num(s.songs)} canciones en Tus listas." +
                        if (s.missing.isEmpty()) " No faltó ninguna." else " ${num(s.missing.size)} no están en YouTube Music (o no se parecían lo bastante).",
                    style = OtoType.rowSub, color = c.textMuted,
                )
                Spacer(Modifier.height(Space.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Pill("Ver Tus listas", onLists, icon = R.drawable.ic_queue_music)
                    Pill("Importar más", onMore, filled = false)
                }
            }
        }
        if (s.missing.isNotEmpty()) item(key = "missingTitle") {
            Text("NO ENCONTRADAS", style = OtoType.label, color = c.textMuted, modifier = Modifier.padding(start = Space.md, top = Space.lg, bottom = Space.xs))
        }
        items(s.missing.size) { i ->
            val m = s.missing[i]
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = 6.dp)) {
                Text(m.track.title, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${m.track.artists.joinToString()} · ${m.list}", style = OtoType.rowSub, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val ES = Locale.forLanguageTag("es-ES")

private fun num(n: Int) = String.format(ES, "%,d", n)

private fun percent(done: Int, total: Int) = if (total == 0) 0 else (done * 100f / total).roundToInt()

private fun lists(n: Int) = if (n == 1) "1 lista" else "${num(n)} listas"

/** Con 6 búsquedas a la vez salieron ~11 canciones por segundo (medido en el PC); se cuenta algo menos. */
private fun minutes(songs: Int): String {
    val m = (songs / SONGS_PER_SECOND / 60).toInt() + 1
    return if (m <= 1) "menos de un minuto" else "unos $m min"
}

private const val SONGS_PER_SECOND = 9.0
