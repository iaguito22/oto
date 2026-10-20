package io.github.iaguito22.oto.ui.menu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import io.github.iaguito22.oto.BuildConfig
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.OtoApp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.metrolist.innertube.models.Artist
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.playback.PlayerConnection
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Radius
import io.github.iaguito22.oto.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class View { Tabs, Search, Detail, Settings }

/**
 * Lo que sale al subir la gota: pestañas Explorar | Tu música, el buscador abajo (al alcance del pulgar) y los
 * ajustes en la esquina. Buscar, un álbum o los ajustes se abren dentro de la misma hoja y atrás vuelve.
 */
@Composable
fun MenuSheet(
    connection: PlayerConnection,
    currentId: String?,
    onClose: () -> Unit,
    onTutorial: () -> Unit,
    vm: MenuViewModel = viewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val history by (LocalContext.current.applicationContext as OtoApp).prefs.history.collectAsStateWithLifecycle(initialValue = null)
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    var settings by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { delay(1600); notice = null } }

    val view = when {
        settings -> View.Settings
        s.detail != null -> View.Detail
        s.query.isNotBlank() -> View.Search
        else -> View.Tabs
    }
    BackHandler(view != View.Tabs) {
        when (view) {
            View.Settings -> settings = false
            View.Detail -> vm.closeDetail()
            View.Search -> { vm.onQuery(""); focus.clearFocus() }
            View.Tabs -> Unit
        }
    }

    val play: (SongItem) -> Unit = { focus.clearFocus(); connection.play(it); onClose() }
    val queueNext: (SongItem) -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        connection.playNext(it)
        notice = "«${it.title}» va después de esta"
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(Oto.colors.textFaint))
        }
        Row(Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.sm, top = Space.md, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Tabs(pager, onSelect = { i ->
                settings = false; vm.closeDetail(); vm.onQuery(""); focus.clearFocus()
                scope.launch { pager.animateScrollToPage(i, animationSpec = Motion.spatial()) }
            })
            Spacer(Modifier.weight(1f))
            RoundIcon(R.drawable.ic_settings, selected = settings) { settings = !settings; focus.clearFocus() }
        }

        AnimatedContent(
            targetState = view,
            transitionSpec = {
                val deeper = targetState.ordinal > initialState.ordinal
                val d = if (deeper) 1 else -1
                (fadeIn(tween(Motion.EFFECTS, delayMillis = 40)) + slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f)) { d * it / 10 }) togetherWith
                    (fadeOut(tween(Motion.EFFECTS_FAST)) + slideOutHorizontally(tween(Motion.EFFECTS_FAST)) { -d * it / 14 })
            },
            // Lo de arriba se desvanece antes de llegar al buscador, en vez de cortarse contra él.
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = 28.dp.toPx()
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fade, endY = size.height),
                        Offset(0f, size.height - fade), Size(size.width, fade), blendMode = BlendMode.DstOut,
                    )
                },
            label = "menuView",
        ) { v ->
            when (v) {
                View.Tabs -> HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                    if (page == 0) YourMusic(history, currentId, play, queueNext)
                    else Explore(s, currentId, play, queueNext, onOpen = { item ->
                        if (item is ArtistItem) vm.onQuery(item.title) else vm.open(item)
                    }, onRetry = vm::loadHome)
                }
                View.Search -> Results(s, currentId, play, queueNext)
                View.Detail -> s.detail?.let { d -> DetailView(d, currentId, onBack = vm::closeDetail, onPlay = { songs, i -> connection.playAll(songs, i, d.item); onClose() }, onLong = queueNext) }
                View.Settings -> Settings(onBack = { settings = false }, onTutorial = onTutorial)
            }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(
                notice != null,
                enter = fadeIn(tween(Motion.EFFECTS)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 600f), initialScale = 0.9f),
                exit = fadeOut(tween(Motion.EFFECTS_FAST)) + scaleOut(tween(Motion.EFFECTS_FAST), targetScale = 0.95f),
            ) {
                var last by remember { mutableStateOf("") }
                notice?.let { last = it }
                Text(
                    last, style = OtoType.rowSub, color = Oto.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = Space.sm).widthIn(max = 320.dp).clip(CircleShape).background(Oto.colors.field).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        SearchField(s.query, s.searching, vm::onQuery, onSearch = { focus.clearFocus() })
    }
}

/** Dos pestañas con un indicador que sigue al dedo mientras se desliza entre páginas. */
@Composable
private fun Tabs(pager: PagerState, onSelect: (Int) -> Unit) {
    val c = Oto.colors
    val labels = listOf("Tu música", "Explorar")
    Row(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(c.field)
            .drawBehind {
                val f = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                val w = size.width / 2
                drawRoundRect(c.accent.copy(alpha = 0.22f), Offset(lerp(0f, w, f) + 3.dp.toPx(), 3.dp.toPx()), Size(w - 6.dp.toPx(), size.height - 6.dp.toPx()), CornerRadius(size.height))
            },
    ) {
        labels.forEachIndexed { i, l ->
            val selected = pager.currentPage == i
            Box(
                Modifier
                    .width(104.dp)
                    .height(40.dp)
                    .clip(CircleShape)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(l, style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.08f), color = if (selected) c.text else c.textMuted)
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: Int, selected: Boolean = false, onClick: () -> Unit) {
    val c = Oto.colors
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) c.accent.copy(alpha = 0.22f) else c.field.copy(alpha = 0f))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = if (selected) c.accent else c.textMuted)
    }
}

@Composable
private fun SearchField(query: String, searching: Boolean, onQuery: (String) -> Unit, onSearch: () -> Unit) {
    val c = Oto.colors
    Row(
        Modifier
            .padding(start = Space.md, end = Space.md, top = Space.sm, bottom = Space.md)
            .fillMaxWidth()
            .height(50.dp)
            .clip(CircleShape)
            .background(c.field)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_search), null, Modifier.size(22.dp), tint = if (searching) c.accent else c.textMuted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            val style = OtoType.body.copy(fontSize = OtoType.body.fontSize * 1.07f)
            if (query.isEmpty()) Text("Canciones, artistas…", style = style, color = c.textFaint)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = style.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(tween(Motion.EFFECTS)) + scaleIn(initialScale = 0.6f), exit = fadeOut(tween(Motion.EFFECTS_FAST)) + scaleOut(targetScale = 0.6f)) {
            RoundIcon(R.drawable.ic_close) { onQuery("") }
        }
    }
}

@Composable
private fun Explore(
    s: MenuState,
    currentId: String?,
    play: (SongItem) -> Unit,
    queueNext: (SongItem) -> Unit,
    onOpen: (YTItem) -> Unit,
    onRetry: () -> Unit,
) {
    val shelves = s.shelves
    when {
        shelves == null && s.homeError != null -> Empty(R.drawable.ic_library_music, "No se pudo cargar", s.homeError) { Pill("Reintentar", onRetry, filled = false) }
        shelves == null -> Skeleton(modifier = Modifier.padding(Space.md))
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.lg)) {
            // YouTube repite a veces títulos y canciones: las claves llevan la posición para no chocar.
            itemsIndexed(shelves, key = { i, it -> "$i:${it.title}" }) { _, shelf ->
                Text(shelf.title, style = OtoType.title.copy(fontSize = OtoType.title.fontSize * 0.86f), color = Oto.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Space.lg, end = Space.lg, top = Space.lg, bottom = Space.sm))
                LazyRow(contentPadding = PaddingValues(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    itemsIndexed(shelf.items, key = { i, it -> "$i:${it.id}" }) { _, item ->
                        Card(item, current = item.id == currentId, onClick = { if (item is SongItem) play(item) else onOpen(item) }, onLongClick = (item as? SongItem)?.let { { queueNext(it) } })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Card(item: YTItem, current: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val c = Oto.colors
    val round = item is ArtistItem
    val size = if (round) 120.dp else 140.dp
    Column(
        Modifier
            .width(size + Space.md)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.lg))
            .combinedClickable(remember { MutableInteractionSource() }, ripple(color = c.text), onLongClick = onLongClick, onClick = onClick)
            .padding(Space.xs + 2.dp),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Art(item.thumbnail, size, if (round) size else Radius.md)
        Spacer(Modifier.height(Space.sm))
        Text(item.title, style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = if (current) c.accent else c.text, maxLines = if (round) 1 else 2,
            overflow = TextOverflow.Ellipsis, textAlign = if (round) TextAlign.Center else TextAlign.Start)
        val sub = when (item) {
            is SongItem -> item.artists.joinToString { it.name }
            is AlbumItem -> listOfNotNull("Álbum", item.year?.toString()).joinToString(" · ")
            is PlaylistItem -> item.author?.name ?: "Lista"
            is ArtistItem -> "Artista"
            else -> ""
        }
        Text(sub, style = OtoType.rowSub, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Results(s: MenuState, currentId: String?, play: (SongItem) -> Unit, queueNext: (SongItem) -> Unit) {
    when {
        s.searchError != null && s.results.isEmpty() -> Empty(R.drawable.ic_search, "La búsqueda falló", s.searchError)
        s.results.isEmpty() && s.searching -> Skeleton(modifier = Modifier.padding(Space.md))
        s.results.isEmpty() && s.query.trim().length < 2 -> Empty(R.drawable.ic_search, "Sigue escribiendo", "Con dos letras ya busca.")
        s.results.isEmpty() -> Empty(R.drawable.ic_search, "Sin resultados", "Prueba con otro nombre o con el artista.")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.sm, vertical = Space.xs)) {
            items(s.results, key = { it.id }) { song ->
                SongRow(song.title, song.subtitle(), song.thumbnail, song.id == currentId, onClick = { play(song) }, onLongClick = { queueNext(song) },
                    modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun DetailView(d: Detail, currentId: String?, onBack: () -> Unit, onPlay: (List<SongItem>, Int) -> Unit, onLong: (SongItem) -> Unit) {
    val c = Oto.colors
    val songs = d.songs
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(bottom = Space.md)) {
                RoundIcon(R.drawable.ic_arrow_back, onClick = onBack)
                Row(Modifier.padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    Art(d.item.thumbnail, 112.dp, Radius.md)
                    Spacer(Modifier.width(Space.lg))
                    Column(Modifier.weight(1f)) {
                        Text(d.item.title, style = OtoType.title, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val sub = when (val i = d.item) {
                            is AlbumItem -> listOfNotNull(i.artists?.joinToString { it.name }, i.year?.toString()).joinToString(" · ")
                            is PlaylistItem -> listOfNotNull(i.author?.name, i.songCountText).joinToString(" · ")
                            else -> ""
                        }
                        if (sub.isNotEmpty()) Text(sub, style = OtoType.rowSub, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!songs.isNullOrEmpty()) {
                            Spacer(Modifier.height(Space.md))
                            Pill("Reproducir", { onPlay(songs, 0) }, icon = R.drawable.ic_play_arrow)
                        }
                    }
                }
            }
        }
        when {
            d.error != null -> item(key = "error") { Empty(R.drawable.ic_library_music, "No se pudo abrir", d.error, Modifier.height(240.dp)) }
            songs == null -> item(key = "loading") { Skeleton(rows = 8) }
            else -> itemsIndexed(songs, key = { i, it -> "${it.id}-$i" }) { i, song ->
                SongRow(song.title, song.subtitle(), song.thumbnail, song.id == currentId, onClick = { onPlay(songs, i) }, onLongClick = { onLong(song) })
            }
        }
    }
}

@Composable
private fun Settings(onBack: () -> Unit, onTutorial: () -> Unit) {
    val c = Oto.colors
    Column(Modifier.fillMaxSize().padding(horizontal = Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(R.drawable.ic_arrow_back, onClick = onBack)
            Text("Ajustes", style = OtoType.title, color = c.text)
        }
        Spacer(Modifier.height(Space.md))
        SettingRow("Ver los gestos otra vez", "El tutorial del principio", onTutorial)
        Spacer(Modifier.weight(1f))
        Text(
            "Oto ${BuildConfig.VERSION_NAME} · GPL-3.0\nCon InnerTubeX y partes de Metrolist.\ngithub.com/iaguito22/oto",
            style = OtoType.rowSub, color = c.textFaint, modifier = Modifier.padding(Space.md),
        )
    }
}

@Composable
private fun SettingRow(title: String, body: String, onClick: () -> Unit) {
    val c = Oto.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.lg))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.md),
    ) {
        Text(title, style = OtoType.rowTitle, color = c.text)
        Text(body, style = OtoType.rowSub, color = c.textMuted)
    }
}

/** Lo último que ha sonado; los me gusta y las listas propias llegan con la biblioteca (fase 6). */
@Composable
private fun YourMusic(history: List<Heard>?, currentId: String?, play: (SongItem) -> Unit, queueNext: (SongItem) -> Unit) {
    when {
        history == null -> Unit
        history.isEmpty() -> Empty(R.drawable.ic_favorite, "Aún no hay nada", "Lo que vayas oyendo aparecerá aquí. Busca algo abajo o mira en Explorar.")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
            item(key = "title") {
                Text("Oído hace poco", style = OtoType.title.copy(fontSize = OtoType.title.fontSize * 0.86f), color = Oto.colors.text,
                    modifier = Modifier.padding(start = Space.sm, end = Space.sm, top = Space.sm, bottom = Space.sm))
            }
            items(history, key = { it.id }) { h ->
                val song = SongItem(id = h.id, title = h.title, artists = listOf(Artist(h.artist, null)), thumbnail = h.artwork.orEmpty())
                SongRow(h.title, h.artist, h.artwork, h.id == currentId, onClick = { play(song) }, onLongClick = { queueNext(song) }, modifier = Modifier.animateItem())
            }
        }
    }
}
