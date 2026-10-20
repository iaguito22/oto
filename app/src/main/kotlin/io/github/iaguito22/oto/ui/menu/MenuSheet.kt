package io.github.iaguito22.oto.ui.menu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.playback.PlayerConnection
import io.github.iaguito22.oto.toHeard
import io.github.iaguito22.oto.toSong
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Radius
import io.github.iaguito22.oto.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class View { Tabs, Search, Page, Settings, Import, Account }

/** Qué se ve: la vista y, en las páginas, cuántas hay apiladas (cada una entra como un paso más hondo). */
private data class Nav(val view: View, val depth: Int)

/** Una canción mantenida pulsada. [list]: la lista tuya en la que está, si es una. */
private data class Held(val song: SongItem, val list: String? = null)

/** Pedir un nombre: para una lista nueva o para renombrar [rename]. */
private data class Naming(val rename: LocalPlaylist? = null)

/**
 * Lo que sale al subir la gota: pestañas Tu música | Explorar, el buscador abajo (al alcance del pulgar) y los
 * ajustes en la esquina. Buscar, un álbum, un artista, una lista o los ajustes se abren dentro de la misma hoja
 * y atrás vuelve. Mantener una canción abre lo que se puede hacer con ella.
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
    val prefs = (LocalContext.current.applicationContext as OtoApp).prefs
    val history by prefs.history.collectAsStateWithLifecycle(initialValue = null)
    val playlists by prefs.playlists.collectAsStateWithLifecycle(initialValue = null)
    val liked by prefs.likedList.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(history, liked, playlists) {
        vm.taste = withContext(Dispatchers.Default) {
            Taste.of(history.orEmpty().asSequence() + liked?.songs.orEmpty() + playlists.orEmpty().asSequence().flatMap { it.songs })
        }
    }
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    var settings by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { delay(1800); notice = null } }
    var held by remember { mutableStateOf<Held?>(null) }
    var naming by remember { mutableStateOf<Naming?>(null) }
    var listMenu by remember { mutableStateOf<LocalPlaylist?>(null) }
    var saving by remember { mutableStateOf<Saving?>(null) }
    var importing by remember { mutableStateOf(false) }
    var accounting by remember { mutableStateOf(false) }

    val view = when {
        accounting -> View.Account
        importing -> View.Import
        settings -> View.Settings
        s.pages.isNotEmpty() -> View.Page
        s.query.isNotBlank() -> View.Search
        else -> View.Tabs
    }
    BackHandler(view != View.Tabs) {
        when (view) {
            View.Account -> accounting = false
            View.Import -> importing = false
            View.Settings -> settings = false
            View.Page -> vm.closeDetail()
            View.Search -> { vm.onQuery(""); focus.clearFocus() }
            View.Tabs -> Unit
        }
    }
    // Después del de arriba: con algo flotando, atrás lo cierra primero.
    BackHandler(held != null || naming != null || listMenu != null || saving != null) { held = null; naming = null; listMenu = null; saving = null }

    val play: (SongItem) -> Unit = { focus.clearFocus(); connection.play(it); onClose() }
    val hold: (SongItem, String?) -> Unit = { song, list ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        focus.clearFocus()
        held = Held(song, list)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(Oto.colors.textFaint))
            }
            Row(Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.sm, top = Space.md, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Tabs(pager, onSelect = { i ->
                    settings = false; importing = false; accounting = false; vm.closeAll(); vm.onQuery(""); focus.clearFocus()
                    scope.launch { pager.animateScrollToPage(i, animationSpec = Motion.spatial()) }
                })
                Spacer(Modifier.weight(1f))
                RoundIcon(R.drawable.ic_settings, selected = settings) { settings = !settings; importing = false; accounting = false; focus.clearFocus() }
            }

            AnimatedContent(
                targetState = Nav(view, s.pages.size),
                transitionSpec = {
                    val deeper = targetState.view.ordinal > initialState.view.ordinal ||
                        (targetState.view == initialState.view && targetState.depth > initialState.depth)
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
            ) { nav ->
                when (nav.view) {
                    View.Tabs -> HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                        if (page == 0) YourMusic(history, liked, playlists, currentId, play, onHold = { hold(it, null) }, onOpen = vm::openLocal, onNew = { naming = Naming() })
                        else Explore(s, currentId, play, onHold = { hold(it, null) }, onOpen = vm::open, onRetry = vm::loadHome)
                    }
                    View.Search -> Results(s, currentId, play, onHold = { hold(it, null) }, onOpen = { focus.clearFocus(); vm.open(it) })
                    View.Page -> when (val p = s.pages.getOrNull(nav.depth - 1)) {
                        is Page.Remote -> if (p.item is ArtistItem) ArtistView(
                            p, currentId, onBack = vm::closeDetail,
                            onPlay = { songs, i -> connection.playAll(songs, i, p.item); onClose() },
                            play = play, onOpen = vm::open, onHold = { hold(it, null) },
                        ) else DetailView(
                            p, currentId, onBack = vm::closeDetail,
                            onPlay = { songs, i -> connection.playAll(songs, i, p.item); onClose() }, onHold = { hold(it, null) },
                            onSave = { songs -> saving = Saving(songs.map { it.toHeard() }, p.item.title, count(songs.size), p.item.thumbnail) },
                        )
                        is Page.Local -> LocalView(
                            if (p.id == LIKED_ID) liked else playlists?.firstOrNull { it.id == p.id }, currentId, onBack = vm::closeDetail,
                            onPlay = { list, i -> connection.playLocal(list, i); onClose() },
                            onHold = { song -> hold(song, p.id) }, onMenu = { listMenu = it },
                        )
                        null -> Unit
                    }
                    View.Settings -> Settings(onBack = { settings = false }, onTutorial = onTutorial, onImport = { importing = true }, onAccount = { accounting = true })
                    View.Account -> AccountView(onBack = { accounting = false }, onSignedIn = vm::loadHome)
                    View.Import -> Import(onBack = { importing = false }, onLists = {
                        importing = false; settings = false
                        scope.launch { pager.animateScrollToPage(0, animationSpec = Motion.spatial()) }
                    })
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
            // Entrando con Google, el teclado es para la contraseña: el buscador sobra.
            if (view != View.Account) SearchField(s.query, s.searching, vm::onQuery, onSearch = { focus.clearFocus() })
        }

        // ---- Lo que flota encima: acciones de una canción, opciones de una lista y pedir un nombre ----

        Floating(held, onDismiss = { held = null }) { h ->
            SongActions(
                h,
                onNext = {
                    connection.playNext(h.song)
                    notice = "«${h.song.title}» va después de esta"
                    held = null
                },
                onSave = { held = null; saving = Saving(h.song.toHeard()) },
                onArtist = { id, name -> held = null; vm.openArtist(id, name) },
                onRemove = h.list?.let { list ->
                    {
                        held = null
                        scope.launch { if (list == LIKED_ID) prefs.setLiked(h.song.toHeard(), false) else prefs.removeFromPlaylist(list, h.song.id) }
                    }
                },
                // En la página de un artista no se ofrece ir a él.
                here = ((s.pages.lastOrNull() as? Page.Remote)?.item as? ArtistItem)?.id,
            )
        }
        Floating(listMenu, onDismiss = { listMenu = null }) { list ->
            ListActions(
                list,
                onRename = { listMenu = null; naming = Naming(rename = list) },
                onDelete = {
                    listMenu = null
                    vm.closeDetail()
                    scope.launch { prefs.deletePlaylist(list.id) }
                    notice = "«${list.name}» borrada"
                },
            )
        }
        Floating(naming, onDismiss = { naming = null }) { n ->
            NameField(n, onDone = { name ->
                naming = null
                scope.launch {
                    if (n.rename != null) prefs.renamePlaylist(n.rename.id, name)
                    // Una lista vacía se abre: así se ve qué hacer para llenarla.
                    else vm.openLocal(prefs.createPlaylist(name))
                }
            })
        }
        Floating(saving, onDismiss = { saving = null }) { song -> SaveTo(song, onDone = { saving = null }) }
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
fun RoundIcon(icon: Int, selected: Boolean = false, onClick: () -> Unit) {
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
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = OtoType.title.copy(fontSize = OtoType.title.fontSize * 0.86f), color = Oto.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = Space.lg, end = Space.lg, top = Space.lg, bottom = Space.sm),
    )
}

/** Un carrusel (de Explorar o de un artista). YouTube repite a veces canciones: las claves llevan la posición. */
private fun LazyListScope.shelves(list: List<Shelf>, currentId: String?, play: (SongItem) -> Unit, onHold: (SongItem) -> Unit, onOpen: (YTItem) -> Unit) {
    itemsIndexed(list, key = { i, it -> "$i:${it.title}" }) { _, shelf ->
        SectionTitle(shelf.title)
        LazyRow(contentPadding = PaddingValues(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            itemsIndexed(shelf.items, key = { i, it -> "$i:${it.id}" }) { _, item ->
                Card(item, current = item.id == currentId, onClick = { if (item is SongItem) play(item) else onOpen(item) }, onLongClick = (item as? SongItem)?.let { { onHold(it) } })
            }
        }
    }
}

@Composable
private fun Explore(s: MenuState, currentId: String?, play: (SongItem) -> Unit, onHold: (SongItem) -> Unit, onOpen: (YTItem) -> Unit, onRetry: () -> Unit) {
    val list = s.shelves
    when {
        list == null && s.homeError != null -> Empty(R.drawable.ic_library_music, "No se pudo cargar", s.homeError) { Pill("Reintentar", onRetry, filled = false) }
        list == null -> Skeleton(modifier = Modifier.padding(Space.md))
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.lg)) {
            shelves(list, currentId, play, onHold, onOpen)
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
            .clip(RoundedCornerShape(Radius.lg))
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
private fun Results(s: MenuState, currentId: String?, play: (SongItem) -> Unit, onHold: (SongItem) -> Unit, onOpen: (YTItem) -> Unit) {
    when {
        s.searchError != null && s.results.isEmpty() -> Empty(R.drawable.ic_search, "La búsqueda falló", s.searchError)
        s.results.isEmpty() && s.searching -> Skeleton(modifier = Modifier.padding(Space.md))
        s.results.isEmpty() && s.query.trim().length < 2 -> Empty(R.drawable.ic_search, "Sigue escribiendo", "Con dos letras ya busca.")
        s.results.isEmpty() -> Empty(R.drawable.ic_search, "Sin resultados", "Prueba con otro nombre o con el artista.")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.sm, vertical = Space.xs)) {
            // Canciones, artistas, álbumes y listas mezclados, como en Spotify: lo que no es una canción se abre.
            items(s.results, key = { it.id }) { item ->
                when (item) {
                    is SongItem -> SongRow(item.title, item.subtitle(), item.thumbnail, item.id == currentId, onClick = { play(item) }, onLongClick = { onHold(item) },
                        modifier = Modifier.animateItem())
                    else -> SongRow(item.title, kind(item), item.thumbnail, false, onClick = { onOpen(item) }, modifier = Modifier.animateItem(), round = item is ArtistItem)
                }
            }
        }
    }
}

private fun kind(item: YTItem) = when (item) {
    is ArtistItem -> "Artista"
    is AlbumItem -> listOfNotNull("Álbum", item.artists?.joinToString { it.name }?.ifEmpty { null }, item.year?.toString()).joinToString(" · ")
    is PlaylistItem -> listOfNotNull("Lista", item.author?.name).joinToString(" · ")
    else -> ""
}

/** La cabecera de una página: atrás, la carátula, el nombre, una línea de datos y los botones. */
@Composable
private fun PageHeader(onBack: () -> Unit, art: @Composable () -> Unit, title: String, sub: String?, actions: @Composable () -> Unit) {
    val c = Oto.colors
    Column(Modifier.fillMaxWidth().padding(bottom = Space.md)) {
        RoundIcon(R.drawable.ic_arrow_back, onClick = onBack)
        Row(Modifier.padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            art()
            Spacer(Modifier.width(Space.lg))
            Column(Modifier.weight(1f)) {
                Text(title, style = OtoType.title, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!sub.isNullOrEmpty()) Text(sub, style = OtoType.rowSub, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.md))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) { actions() }
            }
        }
    }
}

@Composable
private fun DetailView(
    d: Page.Remote,
    currentId: String?,
    onBack: () -> Unit,
    onPlay: (List<SongItem>, Int) -> Unit,
    onHold: (SongItem) -> Unit,
    onSave: (List<SongItem>) -> Unit,
) {
    val songs = d.songs
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
        item(key = "head") {
            val sub = when (val i = d.item) {
                is AlbumItem -> listOfNotNull(i.artists?.joinToString { it.name }, i.year?.toString()).joinToString(" · ")
                is PlaylistItem -> listOfNotNull(i.author?.name, i.songCountText).joinToString(" · ")
                else -> ""
            }
            PageHeader(onBack, { Art(d.item.thumbnail, 112.dp, Radius.md) }, d.item.title, sub) {
                if (!songs.isNullOrEmpty()) {
                    Pill("Reproducir", { onPlay(songs, 0) }, icon = R.drawable.ic_play_arrow)
                    // El álbum entero a Me gusta o a una lista tuya (o a una nueva con su nombre). Redondo: al lado
                    // de «Reproducir» no cabe otra píldora con texto.
                    val c = Oto.colors
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(c.field)
                            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text)) { onSave(songs) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_add), "Guardar", Modifier.size(22.dp), tint = c.text)
                    }
                }
            }
        }
        when {
            d.error != null -> item(key = "error") { Empty(R.drawable.ic_library_music, "No se pudo abrir", d.error, Modifier.height(240.dp)) }
            songs == null -> item(key = "loading") { Skeleton(rows = 8) }
            else -> itemsIndexed(songs, key = { i, it -> "${it.id}-$i" }) { i, song ->
                SongRow(song.title, song.subtitle(), song.thumbnail, song.id == currentId, onClick = { onPlay(songs, i) }, onLongClick = { onHold(song) })
            }
        }
    }
}

/** La página de un artista: su foto, lo más oído y sus carruseles (álbumes, sencillos, parecidos…). */
@Composable
private fun ArtistView(
    d: Page.Remote,
    currentId: String?,
    onBack: () -> Unit,
    onPlay: (List<SongItem>, Int) -> Unit,
    play: (SongItem) -> Unit,
    onOpen: (YTItem) -> Unit,
    onHold: (SongItem) -> Unit,
) {
    val c = Oto.colors
    val songs = d.songs
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.lg)) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.sm)) {
                RoundIcon(R.drawable.ic_arrow_back, onClick = onBack)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Art(d.item.thumbnail, 148.dp, 148.dp)
                    Spacer(Modifier.height(Space.md))
                    Text(d.item.title, style = OtoType.display.copy(fontSize = OtoType.display.fontSize * 0.86f), color = c.text, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Space.lg))
                    if (!d.sub.isNullOrEmpty()) Text(d.sub, style = OtoType.rowSub, color = c.textMuted, maxLines = 1)
                    if (!songs.isNullOrEmpty()) {
                        Spacer(Modifier.height(Space.md))
                        Pill("Reproducir", { onPlay(songs, 0) }, icon = R.drawable.ic_play_arrow)
                    }
                }
            }
        }
        when {
            d.error != null -> item(key = "error") { Empty(R.drawable.ic_person, "No se pudo abrir", d.error, Modifier.height(240.dp)) }
            songs == null -> item(key = "loading") { Skeleton(rows = 6, modifier = Modifier.padding(top = Space.lg)) }
            else -> {
                if (songs.isNotEmpty()) {
                    item(key = "topTitle") { SectionTitle("Lo más oído") }
                    itemsIndexed(songs.take(TOP_SONGS), key = { i, it -> "top-${it.id}-$i" }) { i, song ->
                        SongRow(song.title, song.subtitle(), song.thumbnail, song.id == currentId, onClick = { onPlay(songs, i) }, onLongClick = { onHold(song) },
                            modifier = Modifier.padding(horizontal = Space.sm))
                    }
                }
                shelves(d.shelves, currentId, play, onHold, onOpen)
            }
        }
    }
}

/** Una lista tuya. */
@Composable
private fun LocalView(
    list: LocalPlaylist?,
    currentId: String?,
    onBack: () -> Unit,
    onPlay: (LocalPlaylist, Int) -> Unit,
    onHold: (SongItem) -> Unit,
    onMenu: (LocalPlaylist) -> Unit,
) {
    if (list == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.sm)) {
            RoundIcon(R.drawable.ic_arrow_back, onClick = onBack)
            Empty(R.drawable.ic_queue_music, "Esta lista ya no existe", "")
        }
        return
    }
    val likes = list.id == LIKED_ID
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
        item(key = "head") {
            PageHeader(onBack, { if (likes) LikedArt(112.dp) else Mosaic(list.songs, 112.dp) }, list.name, count(list.songs.size)) {
                if (list.songs.isNotEmpty()) Pill("Reproducir", { onPlay(list, 0) }, icon = R.drawable.ic_play_arrow)
                // Me gusta no se renombra ni se borra.
                if (!likes) RoundIcon(R.drawable.ic_more_horiz) { onMenu(list) }
            }
        }
        if (list.songs.isEmpty()) item(key = "empty") {
            if (likes) Empty(
                R.drawable.ic_favorite, "Aún no te gusta nada",
                "Toca dos veces la carátula de lo que suena. Mantenla pulsada para guardarla en otra lista.", Modifier.height(260.dp),
            ) else Empty(
                R.drawable.ic_add, "Está vacía",
                "Mantén pulsada cualquier canción, aquí o en Explorar, y elige «Guardar en…». O mantén pulsada la carátula de lo que suena.", Modifier.height(260.dp),
            )
        }
        itemsIndexed(list.songs, key = { _, it -> it.id }) { i, h ->
            SongRow(h.title, h.artist, h.artwork, h.id == currentId, onClick = { onPlay(list, i) }, onLongClick = { onHold(h.toSong()) }, modifier = Modifier.animateItem())
        }
    }
}

@Composable
private fun Settings(onBack: () -> Unit, onTutorial: () -> Unit, onImport: () -> Unit, onAccount: () -> Unit) {
    val c = Oto.colors
    Column(Modifier.fillMaxSize().padding(horizontal = Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(R.drawable.ic_arrow_back, onClick = onBack)
            Text("Ajustes", style = OtoType.title, color = c.text)
        }
        Spacer(Modifier.height(Space.md))
        val app = LocalContext.current.applicationContext as OtoApp
        val state by app.importer.state.collectAsStateWithLifecycle()
        val session by app.account.session.collectAsStateWithLifecycle()
        SettingRow(
            "Cuenta de YouTube Music",
            session?.let { listOfNotNull(it.name.ifEmpty { null }, it.email).joinToString(" · ").ifEmpty { "Has entrado" } }
                ?: "Sin cuenta: recomendaciones de visitante",
            onAccount,
        )
        SettingRow("Importar de Spotify", importSummary(state), onImport)
        SettingRow("Ver los gestos otra vez", "El tutorial del principio", onTutorial)
        Spacer(Modifier.weight(1f))
        Text(
            "Oto ${BuildConfig.VERSION_NAME} · GPL-3.0\nCon InnerTubeX y partes de Metrolist.\nLetras de lyricsplus, NetEase, LRCLIB y YouTube Music.\ngithub.com/iaguito22/oto",
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
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.md),
    ) {
        Text(title, style = OtoType.rowTitle, color = c.text)
        Text(body, style = OtoType.rowSub, color = c.textMuted)
    }
}

/** Tus listas arriba (con crear una nueva a mano) y lo último que ha sonado debajo. */
@Composable
private fun YourMusic(
    history: List<Heard>?,
    liked: LocalPlaylist?,
    playlists: List<LocalPlaylist>?,
    currentId: String?,
    play: (SongItem) -> Unit,
    onHold: (SongItem) -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    if (history == null || playlists == null || liked == null) return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.lg)) {
        item(key = "listsTitle") {
            Row(Modifier.fillMaxWidth().padding(end = Space.md), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Tus listas", Modifier.weight(1f))
                // El título lleva más aire arriba que abajo: el botón baja lo mismo para quedar a su altura.
                if (playlists.isNotEmpty()) Box(Modifier.padding(top = Space.sm)) { Pill("Nueva", onNew, filled = false, icon = R.drawable.ic_add) }
            }
        }
        item(key = "lists") {
            LazyRow(contentPadding = PaddingValues(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                // Me gusta va siempre la primera, aunque esté vacía: así se sabe que existe.
                item(key = LIKED_ID) { PlaylistCard(liked, onClick = { onOpen(LIKED_ID) }) }
                items(playlists, key = { it.id }) { p -> PlaylistCard(p, onClick = { onOpen(p.id) }, modifier = Modifier.animateItem()) }
                if (playlists.isEmpty()) item(key = "new") { NewListCard(onNew) }
            }
        }
        item(key = "historyTitle") { SectionTitle("Oído hace poco") }
        if (history.isEmpty()) item(key = "historyEmpty") {
            Text("Lo que vayas oyendo aparecerá aquí.", style = OtoType.rowSub, color = Oto.colors.textMuted, modifier = Modifier.padding(horizontal = Space.lg))
        }
        items(history, key = { it.id }) { h ->
            SongRow(h.title, h.artist, h.artwork, h.id == currentId, onClick = { play(h.toSong()) }, onLongClick = { onHold(h.toSong()) },
                modifier = Modifier.padding(horizontal = Space.sm).animateItem())
        }
    }
}

@Composable
private fun PlaylistCard(p: LocalPlaylist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Oto.colors
    Column(
        modifier
            .width(140.dp + Space.md)
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(Space.xs + 2.dp),
    ) {
        if (p.id == LIKED_ID) LikedArt(140.dp) else Mosaic(p.songs, 140.dp)
        Spacer(Modifier.height(Space.sm))
        Text(p.name, style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count(p.songs.size), style = OtoType.rowSub, color = c.textMuted, maxLines = 1)
    }
}

/** Aún sin listas: un hueco con el borde suave que invita a crear la primera. */
@Composable
private fun NewListCard(onClick: () -> Unit) {
    val c = Oto.colors
    Column(
        Modifier
            .width(140.dp + Space.md)
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(Space.xs + 2.dp),
    ) {
        Box(
            Modifier.size(140.dp).clip(RoundedCornerShape(Radius.md)).background(c.field),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_add), null, Modifier.size(28.dp), tint = c.accent)
            }
        }
        Spacer(Modifier.height(Space.sm))
        Text("Nueva lista", style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = c.text, maxLines = 1)
        Text("Crea la primera", style = OtoType.rowSub, color = c.textMuted, maxLines = 1)
    }
}

// ---- Lo que flota ----

@Composable
private fun ActionRow(icon: Int, text: String, tint: Color = Oto.colors.text, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = Oto.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(horizontal = Space.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = if (tint == c.text) c.textMuted else tint)
        Spacer(Modifier.width(Space.lg))
        Text(text, style = OtoType.rowTitle, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun ColumnScope.SongActions(
    h: Held,
    onNext: () -> Unit,
    onSave: () -> Unit,
    onArtist: (String, String) -> Unit,
    onRemove: (() -> Unit)?,
    here: String?,
) {
    val c = Oto.colors
    val song = h.song
    Row(Modifier.padding(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Art(song.thumbnail, 48.dp, Radius.sm)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artists.joinToString { it.name }, style = OtoType.rowSub, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(Space.xs))
    ActionRow(R.drawable.ic_queue_music, "Reproducir a continuación", onClick = onNext)
    ActionRow(R.drawable.ic_add, "Guardar en…", onClick = onSave)
    song.artists.firstOrNull { it.id != null && it.id != here }?.let { a -> ActionRow(R.drawable.ic_person, "Ir a ${a.name}") { onArtist(a.id!!, a.name) } }
    if (onRemove != null) ActionRow(R.drawable.ic_delete, if (h.list == LIKED_ID) "Quitar de Me gusta" else "Quitar de esta lista", onClick = onRemove)
}

@Composable
private fun ColumnScope.ListActions(list: LocalPlaylist, onRename: () -> Unit, onDelete: () -> Unit) {
    val c = Oto.colors
    // Borrar pide un segundo toque: no hay deshacer.
    var confirm by remember(list) { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(3000); confirm = false } }
    Text(list.name, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(Space.md))
    ActionRow(R.drawable.ic_edit, "Cambiar el nombre", onClick = onRename)
    ActionRow(R.drawable.ic_delete, if (confirm) "Toca otra vez para borrarla" else "Borrar la lista", tint = if (confirm) Color(0xFFFF8A80) else c.text) {
        if (confirm) onDelete() else confirm = true
    }
}

@Composable
private fun ColumnScope.NameField(n: Naming, onDone: (String) -> Unit) {
    val c = Oto.colors
    var name by remember(n) { mutableStateOf(n.rename?.name.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(n) { delay(120); focus.requestFocus() }
    val ok = name.isNotBlank()
    Text(
        if (n.rename != null) "Cambiar el nombre" else "Nueva lista",
        style = OtoType.title, color = c.text, modifier = Modifier.padding(start = Space.md, top = Space.md, end = Space.md),
    )
    Spacer(Modifier.height(Space.md))
    Box(
        Modifier
            .padding(horizontal = Space.sm)
            .fillMaxWidth()
            .height(50.dp)
            .clip(CircleShape)
            .background(c.field)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val style = OtoType.body.copy(fontSize = OtoType.body.fontSize * 1.07f)
        if (name.isEmpty()) Text("Ponle un nombre", style = style, color = c.textFaint)
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            singleLine = true,
            textStyle = style.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
            keyboardActions = KeyboardActions(onDone = { if (ok) onDone(name) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    Spacer(Modifier.height(Space.md))
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.xs), horizontalArrangement = Arrangement.End) {
        Box(Modifier.graphicsLayer { alpha = if (ok) 1f else 0.4f }) {
            Pill(if (n.rename != null) "Guardar" else "Crear", { if (ok) onDone(name) }, icon = R.drawable.ic_check)
        }
    }
}

private const val TOP_SONGS = 5
