package io.github.iaguito22.oto.ui.menu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.media3.exoplayer.offline.Download
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.CROSSFADE_CHOICES
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.QualityMode
import io.github.iaguito22.oto.Storage
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

/** Pedir un nombre: para una lista nueva, para renombrar [rename] o para renombrar la carpeta [folder]. */
private data class Naming(val rename: LocalPlaylist? = null, val folder: String? = null, val newFolder: Boolean = false)

/** El id con el que una carpeta suena como una lista (y se vuelve a abrir desde lo que suena). */
const val FOLDER_PREFIX = "folder:"

/**
 * Lo que sale al subir la gota: pestañas Tu música | Explorar, el buscador abajo (al alcance del pulgar) y los
 * ajustes en la esquina. Buscar, un álbum, un artista, una lista o los ajustes se abren dentro de la misma hoja
 * y atrás vuelve. Mantener una canción abre lo que se puede hacer con ella.
 */
@Composable
fun MenuSheet(
    connection: PlayerConnection,
    currentId: String?,
    /** Abierto: cerrado sigue montado (ver [io.github.iaguito22.oto.ui.isla.SheetHost]), pero sin atender a atrás. */
    open: Boolean,
    onClose: () -> Unit,
    onTutorial: () -> Unit,
    vm: MenuViewModel = viewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as OtoApp
    val prefs = app.prefs
    val history by prefs.history.collectAsStateWithLifecycle(initialValue = null)
    val playlists by prefs.playlists.collectAsStateWithLifecycle(initialValue = null)
    val liked by prefs.likedList.collectAsStateWithLifecycle(initialValue = null)
    val pinned by prefs.pinned.collectAsStateWithLifecycle(initialValue = emptySet())
    val source by connection.source.collectAsStateWithLifecycle()
    val np by connection.state.collectAsStateWithLifecycle()
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
    var moving by remember { mutableStateOf<LocalPlaylist?>(null) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var filling by remember { mutableStateOf<String?>(null) }
    val folders by prefs.folders.collectAsStateWithLifecycle(initialValue = emptySet())
    var saving by remember { mutableStateOf<Saving?>(null) }
    var importing by remember { mutableStateOf(false) }
    var accounting by remember { mutableStateOf(false) }
    val downloadLists by prefs.downloadLists.collectAsStateWithLifecycle(initialValue = null)
    val downloadSongs by prefs.downloadSongs.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadStates by app.downloads.states.collectAsStateWithLifecycle()
    val saver by prefs.dataSaver.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    val waitsForWifi = { saver && context.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered }

    val view = when {
        accounting -> View.Account
        importing -> View.Import
        settings -> View.Settings
        s.pages.isNotEmpty() -> View.Page
        s.query.isNotBlank() -> View.Search
        else -> View.Tabs
    }
    BackHandler(open && view != View.Tabs) {
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
    BackHandler(open && (held != null || naming != null || listMenu != null || saving != null || moving != null || folderMenu != null || creating || filling != null)) {
        held = null; naming = null; listMenu = null; saving = null; moving = null; folderMenu = null; creating = false; filling = null
    }

    // Al cerrarse, vuelve a como se abre siempre: «Tu música», sin páginas, ajustes ni nada flotando.
    LaunchedEffect(open) {
        if (open) return@LaunchedEffect
        held = null; naming = null; listMenu = null; saving = null; moving = null; folderMenu = null; creating = false; filling = null
        notice = null; settings = false; importing = false; accounting = false
        focus.clearFocus()
        vm.closeAll()
        pager.scrollToPage(0)
    }

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
                val downloadsOpen = view == View.Page && s.pages.lastOrNull() == Page.Downloads
                RoundIcon(R.drawable.ic_download_for_offline, selected = downloadsOpen) {
                    settings = false; importing = false; accounting = false; focus.clearFocus()
                    if (downloadsOpen) vm.closeDetail() else vm.openDownloads()
                }
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
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = size.height - fade, endY = size.height),
                            Offset(0f, size.height - fade), Size(size.width, fade), blendMode = BlendMode.DstOut,
                        )
                    },
                label = "menuView",
            ) { nav ->
                when (nav.view) {
                    View.Tabs -> HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                        if (page == 0) YourMusic(history, liked, playlists, pinned, folders, currentId, play, onHold = { hold(it, null) }, onOpen = vm::openLocal, onFolder = vm::openFolder, onNew = { creating = true },
                            dj = source?.kind == "DJ" && np.playing,
                            onDj = {
                                if (playlists.orEmpty().all { it.songs.isEmpty() } && liked?.songs.isNullOrEmpty()) notice = "El DJ aprende de tus listas: guarda algo primero"
                                else { connection.playDj(onFail = { notice = "El DJ no ha podido empezar" }); onClose() }
                            },
                            onListMenu = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); listMenu = it },
                            onFolderMenu = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); folderMenu = it })
                        else Explore(s, currentId, play, onHold = { hold(it, null) }, onOpen = vm::open, onRetry = vm::loadHome)
                    }
                    View.Search -> Column {
                        Filters(s.filter, vm::onFilter)
                        Results(s, currentId, play, onHold = { hold(it, null) }, onOpen = { focus.clearFocus(); vm.open(it) })
                    }
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
                            onPlay = { list, i, newest -> connection.playLocal(list, i, newest); onClose() },
                            onHold = { song -> hold(song, p.id) }, onMenu = { listMenu = it },
                            downloaded = p.id in downloadLists.orEmpty(), downloadStates = downloadStates,
                            play = play, onHoldOther = { hold(it, null) },
                            onAdd = { list, h -> scope.launch { if (list.id == LIKED_ID) prefs.setLiked(h, true) else prefs.addToPlaylist(list.id, h) } },
                        )
                        Page.Downloads -> DownloadsView(
                            currentId, onBack = vm::closeDetail, onOpen = vm::openLocal, play = play,
                            onPlayAll = { list -> connection.playLocal(list, null, false); onClose() }, onHold = { hold(it, null) },
                        )
                        is Page.Folder -> FolderView(
                            p.name, playlists.orEmpty().filter { it.folder == p.name }, onBack = vm::closeDetail, onOpen = vm::openLocal,
                            onPlay = { lists -> connection.playLocal(LocalPlaylist(FOLDER_PREFIX + p.name, p.name, lists.flatMap { it.songs }.distinctBy { it.id }), null, false); onClose() },
                            onMenu = { folderMenu = p.name },
                            onAdd = { filling = p.name },
                            onListMenu = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); listMenu = it },
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
            // Empezar a escribir con los ajustes, la importación o una página abierta lleva a los resultados (si no,
            // se buscaba detrás, sin verse).
            if (view != View.Account) SearchField(s.query, s.searching, onQuery = { q ->
                if (q.isNotBlank() && s.query.isBlank()) { settings = false; importing = false; vm.closeAll() }
                vm.onQuery(q)
            }, onSearch = { focus.clearFocus() })
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
                // Suelta: se puede quitar; dentro de una lista descargada ya está (se quita con la lista).
                download = when {
                    downloadSongs.any { it.id == h.song.id } -> false
                    h.song.id in downloadStates -> null
                    else -> true
                },
                onDownload = {
                    held = null
                    val on = downloadSongs.none { it.id == h.song.id }
                    scope.launch { prefs.setDownloadSong(h.song.toHeard(), on) }
                    notice = if (on) "Descargando «${h.song.title}»" + (if (waitsForWifi()) " cuando haya wifi" else "") else "«${h.song.title}» ya no está descargada"
                },
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
                pinned = list.id in pinned,
                downloaded = list.id in downloadLists.orEmpty(),
                onDownload = {
                    listMenu = null
                    val on = list.id !in downloadLists.orEmpty()
                    scope.launch { prefs.setDownloadList(list.id, on) }
                    notice = if (on) "Descargando «${list.name}»" + (if (waitsForWifi()) " cuando haya wifi" else "") else "«${list.name}» ya no está descargada"
                },
                onPin = { listMenu = null; scope.launch { prefs.togglePin(list.id) } },
                onRename = { listMenu = null; naming = Naming(rename = list) },
                onMove = { listMenu = null; moving = list },
                onDelete = {
                    listMenu = null
                    vm.closeDetail()
                    scope.launch { prefs.deletePlaylist(list.id) }
                    notice = "«${list.name}» borrada"
                },
            )
        }
        Floating(moving, onDismiss = { moving = null }) { list ->
            MoveTo(list, (playlists.orEmpty().mapNotNull { it.folder } + folders).distinct(), onPick = { folder ->
                moving = null
                scope.launch { prefs.moveToFolder(list.id, folder) }
                notice = if (folder == null) "«${list.name}» fuera de la carpeta" else "«${list.name}» en «$folder»"
            })
        }
        Floating(creating.takeIf { it }, onDismiss = { creating = false }) {
            Text("Nueva", style = OtoType.title, color = Oto.colors.text, modifier = Modifier.padding(Space.md))
            ActionRow(R.drawable.ic_queue_music, "Lista") { creating = false; naming = Naming() }
            ActionRow(R.drawable.ic_create_new_folder, "Carpeta") { creating = false; naming = Naming(newFolder = true) }
        }
        Floating(filling, onDismiss = { filling = null }) { folder ->
            FillFolder(folder, playlists.orEmpty()) { list, inside -> scope.launch { prefs.moveToFolder(list.id, if (inside) folder else null) } }
        }
        Floating(folderMenu, onDismiss = { folderMenu = null }) { folder ->
            FolderActions(
                folder,
                pinned = "d:$folder" in pinned,
                onPin = { folderMenu = null; scope.launch { prefs.togglePin("d:$folder") } },
                onAdd = { folderMenu = null; filling = folder },
                onRename = { folderMenu = null; naming = Naming(folder = folder) },
                onUngroup = {
                    folderMenu = null
                    vm.closeDetail()
                    scope.launch { prefs.ungroup(folder) }
                    notice = "Carpeta «$folder» deshecha"
                },
            )
        }
        Floating(naming, onDismiss = { naming = null }) { n ->
            NameField(n, onDone = { name ->
                naming = null
                scope.launch {
                    if (n.newFolder) { prefs.createFolder(name); vm.openFolder(name.trim()) }
                    else if (n.folder != null) { prefs.renameFolder(n.folder, name); vm.renameFolder(n.folder, name.trim()) }
                    else if (n.rename != null) prefs.renamePlaylist(n.rename.id, name)
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

/** Todo · Canciones · Artistas · Álbumes · Listas, en una fila que se desliza. */
@Composable
private fun Filters(selected: Filter, onSelect: (Filter) -> Unit) {
    val c = Oto.colors
    LazyRow(contentPadding = PaddingValues(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.padding(bottom = Space.xs)) {
        items(Filter.entries, key = { it.name }) { f ->
            val on = f == selected
            val bg by animateColorAsState(if (on) c.accent.copy(alpha = 0.22f) else c.field, tween(Motion.EFFECTS), label = "filterBg")
            val fg by animateColorAsState(if (on) c.accent else c.textMuted, tween(Motion.EFFECTS), label = "filterFg")
            Text(
                f.label, style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = fg,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bg)
                    .clickable(remember { MutableInteractionSource() }, ripple(color = c.text)) { onSelect(f) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
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

/** Un botón redondo junto a «Reproducir». [on]: encendido (con el color de acento). */
@Composable
fun RoundAction(icon: Int, description: String, on: Boolean = false, onClick: () -> Unit) {
    val c = Oto.colors
    val bg by animateColorAsState(if (on) c.accent.copy(alpha = 0.22f) else c.field, tween(Motion.EFFECTS), label = "roundBg")
    val tint by animateColorAsState(if (on) c.accent else c.text, tween(Motion.EFFECTS), label = "roundTint")
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, Modifier.size(22.dp), tint = tint)
    }
}

/** Aleatorio sí o no, como el de Spotify: vale para lo que se ponga y, si suena una lista, la rebaraja ya. */
@Composable
fun ShuffleButton() {
    val connection = (LocalContext.current.applicationContext as OtoApp).player
    val on by connection.shuffle.collectAsStateWithLifecycle()
    RoundAction(R.drawable.ic_shuffle, "Aleatorio", on) { connection.setShuffle(!on) }
}

/** Bucle sí o no: una lista que acaba vuelve a empezar; sin él, siguen canciones parecidas. Vale ya, si suena una. */
@Composable
fun LoopButton() {
    val connection = (LocalContext.current.applicationContext as OtoApp).player
    val on by connection.loop.collectAsStateWithLifecycle()
    RoundAction(R.drawable.ic_repeat, "Repetir la lista", on) { connection.setLoop(!on) }
}

/** La cabecera de una página: atrás, la carátula, el nombre, una línea de datos y los botones. */
@Composable
private fun PageHeader(
    onBack: () -> Unit,
    art: @Composable () -> Unit,
    title: String,
    sub: String?,
    onMenu: (() -> Unit)? = null,
    menuIcon: Int = R.drawable.ic_more_horiz,
    actions: @Composable () -> Unit,
) {
    val c = Oto.colors
    Column(Modifier.fillMaxWidth().padding(bottom = Space.md)) {
        // Las opciones (···) arriba, frente a atrás: junto a los botones no caben.
        Row(Modifier.fillMaxWidth()) {
            RoundIcon(R.drawable.ic_arrow_back, onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (onMenu != null) RoundIcon(menuIcon, onClick = onMenu)
        }
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
    onPlay: (List<SongItem>, Int?) -> Unit,
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
            val context = LocalContext.current
            PageHeader(onBack, { Art(d.item.thumbnail, 112.dp, Radius.md) }, d.item.title, sub, onMenu = { shareItem(context, d.item) }, menuIcon = R.drawable.ic_share) {
                if (!songs.isNullOrEmpty()) {
                    Pill("Reproducir", { onPlay(songs, null) }, icon = R.drawable.ic_play_arrow)
                    ShuffleButton()
                    // El álbum entero a Me gusta o a una lista tuya (o a una nueva con su nombre). Redondo: al lado
                    // de «Reproducir» no cabe otra píldora con texto.
                    RoundAction(R.drawable.ic_add, "Guardar") { onSave(songs) }
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
    onPlay: (List<SongItem>, Int?) -> Unit,
    play: (SongItem) -> Unit,
    onOpen: (YTItem) -> Unit,
    onHold: (SongItem) -> Unit,
) {
    val c = Oto.colors
    val songs = d.songs
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.lg)) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.sm)) {
                val context = LocalContext.current
                Row(Modifier.fillMaxWidth()) {
                    RoundIcon(R.drawable.ic_arrow_back, onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    RoundIcon(R.drawable.ic_share) { shareItem(context, d.item) }
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Art(d.item.thumbnail, 148.dp, 148.dp)
                    Spacer(Modifier.height(Space.md))
                    Text(d.item.title, style = OtoType.display.copy(fontSize = OtoType.display.fontSize * 0.86f), color = c.text, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Space.lg))
                    if (!d.sub.isNullOrEmpty()) Text(d.sub, style = OtoType.rowSub, color = c.textMuted, maxLines = 1)
                    if (!songs.isNullOrEmpty()) {
                        Spacer(Modifier.height(Space.md))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Pill("Reproducir", { onPlay(songs, null) }, icon = R.drawable.ic_play_arrow)
                            ShuffleButton()
                        }
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
    onPlay: (LocalPlaylist, Int?, Boolean) -> Unit,
    onHold: (SongItem) -> Unit,
    onMenu: (LocalPlaylist) -> Unit,
    downloaded: Boolean,
    downloadStates: Map<String, Int>,
    play: (SongItem) -> Unit,
    onHoldOther: (SongItem) -> Unit,
    onAdd: (LocalPlaylist, Heard) -> Unit,
) {
    val prefs = (LocalContext.current.applicationContext as OtoApp).prefs
    val flipped by prefs.sortFlipped.collectAsStateWithLifecycle(initialValue = emptySet())
    val scope = rememberCoroutineScope()
    if (list == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.sm)) {
            RoundIcon(R.drawable.ic_arrow_back, onClick = onBack)
            Empty(R.drawable.ic_queue_music, "Esta lista ya no existe", "")
        }
        return
    }
    val likes = list.id == LIKED_ID
    // Me gusta se guarda con lo último arriba y las demás en el orden en que entraron; volteada, al revés.
    val turned = list.id in flipped
    val newest = likes != turned
    val shown = remember(list, turned) { if (turned) list.copy(songs = list.songs.asReversed()) else list }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
        item(key = "head") {
            val got = if (downloaded) list.songs.count { downloadStates[it.id] == Download.STATE_COMPLETED } else 0
            val sub = count(list.songs.size) + when {
                !downloaded -> ""
                got == list.songs.size -> " · descargada"
                else -> " · descargando ($got de ${list.songs.size})"
            }
            PageHeader(onBack, { if (likes) LikedArt(112.dp) else Mosaic(list.songs, 112.dp) }, list.name, sub, onMenu = { onMenu(list) }) {
                if (list.songs.isNotEmpty()) {
                    Pill("Reproducir", { onPlay(shown, null, newest) }, icon = R.drawable.ic_play_arrow)
                    ShuffleButton()
                }
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
        if (list.songs.size > 1) item(key = "sort") {
            SortRow(newest) { scope.launch { prefs.flipSort(list.id) } }
        }
        itemsIndexed(shown.songs, key = { _, it -> it.id }) { i, h ->
            SongRow(h.title, h.artist, h.artwork, h.id == currentId, onClick = { onPlay(shown, i, newest) }, onLongClick = { onHold(h.toSong()) }, modifier = Modifier.animateItem(),
                downloaded = downloadStates[h.id] == Download.STATE_COMPLETED)
        }
        if (list.songs.isNotEmpty()) item(key = "suggest") {
            Suggestions(list, currentId, play, onHoldOther) { onAdd(list, it) }
        }
    }
}

/** «Lo último que añadiste» o «Lo primero que añadiste» arriba; tocar le da la vuelta. */
@Composable
private fun SortRow(newest: Boolean, onFlip: () -> Unit) {
    val c = Oto.colors
    val turn by animateFloatAsState(if (newest) 0f else 180f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "sortTurn")
    Row(
        Modifier
            .padding(start = Space.xs, bottom = Space.xs)
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onFlip)
            .padding(horizontal = Space.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_swap_vert), null, Modifier.size(18.dp).graphicsLayer { rotationX = turn }, tint = c.textMuted)
        Spacer(Modifier.width(6.dp))
        AnimatedContent(newest, transitionSpec = { fadeIn(tween(Motion.EFFECTS)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) }, label = "sortLabel") { n ->
            Text(if (n) "Lo último que añadiste, arriba" else "Lo primero que añadiste, arriba", style = OtoType.rowSub, color = c.textMuted)
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
        // Con scroll: con el texto grande del sistema no cabe todo.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingRow(
                R.drawable.ic_account_circle,
                "Cuenta de YouTube Music",
                session?.let { listOfNotNull(it.name.ifEmpty { null }, it.email).joinToString(" · ").ifEmpty { "Has entrado" } }
                    ?: "Sin cuenta: recomendaciones de visitante",
                onAccount,
            )
            SettingRow(R.drawable.ic_playlist_add, "Importar de Spotify", importSummary(state), onImport)
            AudioSettings(app.prefs)
            CrossfadeRow(app.prefs)
            SpaceRow(app)
            SettingRow(R.drawable.ic_swipe_up, "Ver los gestos otra vez", "El tutorial del principio", onTutorial)
        }
        Text(
            "Oto ${BuildConfig.VERSION_NAME} · GPL-3.0\nCon InnerTubeX y partes de Metrolist.\nLetras de lyricsplus, NetEase, LRCLIB y YouTube Music.\ngithub.com/iaguito22/oto",
            style = OtoType.rowSub, color = c.textFaint, modifier = Modifier.padding(Space.md),
        )
    }
}

/** Calidad del audio (Auto o Máxima) y ahorro de datos. Lo cambiado vale desde la siguiente canción. */
@Composable
private fun AudioSettings(prefs: Prefs) {
    val c = Oto.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val mode by prefs.quality.collectAsStateWithLifecycle(initialValue = null)
    val saver by prefs.dataSaver.collectAsStateWithLifecycle(initialValue = null)
    val m = mode ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.md)) {
        SettingIcon(R.drawable.ic_high_quality)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Calidad del audio", style = OtoType.rowTitle, color = c.text, modifier = Modifier.weight(1f))
                // Como las pestañas del Menú: el indicador se desliza a la opción elegida.
                val at by animateFloatAsState(m.ordinal.toFloat(), spring(dampingRatio = 0.8f, stiffness = 500f), label = "quality")
                Row(
                    Modifier
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(c.field)
                        .drawBehind {
                            val w = size.width / 2
                            drawRoundRect(c.accent.copy(alpha = 0.22f), Offset(lerp(0f, w, at) + 3.dp.toPx(), 3.dp.toPx()), Size(w - 6.dp.toPx(), size.height - 6.dp.toPx()), CornerRadius(size.height))
                        },
                ) {
                    QualityMode.entries.forEach { q ->
                        Box(
                            Modifier
                                .width(78.dp)
                                .height(36.dp)
                                .clip(CircleShape)
                                .clickable(remember { MutableInteractionSource() }, null) {
                                    if (q != m) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    scope.launch { prefs.setQuality(q) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val fg by animateColorAsState(if (q == m) c.text else c.textMuted, tween(Motion.EFFECTS), label = "qualityFg")
                            Text(if (q == QualityMode.Auto) "Auto" else "Máxima", style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = fg)
                        }
                    }
                }
            }
            AnimatedContent(
                targetState = m,
                transitionSpec = { fadeIn(tween(Motion.EFFECTS, delayMillis = 40)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) },
                label = "qualityBody",
            ) { q ->
                Text(
                    if (q == QualityMode.Auto) "La mejor calidad que aguante tu conexión. Se elige al empezar cada canción y nunca cambia a mitad de la canción."
                    else "Siempre la mejor calidad. Si la conexión va justa, la canción tarda más en empezar, pero la calidad no baja.",
                    style = OtoType.rowSub, color = c.textMuted, modifier = Modifier.padding(top = Space.xs),
                )
            }
        }
    }
    val on = saver ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text)) {
                haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                scope.launch { prefs.setDataSaver(!on) }
            }
            .padding(horizontal = Space.md, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIcon(R.drawable.ic_data_saver_on)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text("Ahorro de datos", style = OtoType.rowTitle, color = c.text)
            Text("Con datos móviles, las canciones suenan en calidad baja (unos 48 kbps) y las descargas esperan al wifi.", style = OtoType.rowSub, color = c.textMuted)
        }
        Spacer(Modifier.width(Space.md))
        Check(on)
    }
}

/** Cuántos segundos se funden una canción y la siguiente (ver [io.github.iaguito22.oto.playback.Crossfade]). */
@Composable
private fun CrossfadeRow(prefs: Prefs) {
    val c = Oto.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val secs by prefs.crossfade.collectAsStateWithLifecycle(initialValue = null)
    val now = secs ?: return
    val at by animateFloatAsState(CROSSFADE_CHOICES.indexOf(now).coerceAtLeast(0).toFloat(), spring(dampingRatio = 0.8f, stiffness = 500f), label = "crossfade")
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.md)) {
        SettingIcon(R.drawable.ic_transition_fade)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text("Fundido entre canciones", style = OtoType.rowTitle, color = c.text)
            Text(
                if (now == 0) "Cada canción acaba del todo antes de que empiece la siguiente."
                else "Los últimos $now segundos de una canción suenan a la vez que el principio de la siguiente. Solo cuando acaba sola, no al pasarla tú.",
                style = OtoType.rowSub, color = c.textMuted, modifier = Modifier.padding(top = 2.dp, bottom = Space.sm),
            )
            // Como el de la calidad: el indicador se desliza a lo elegido.
            Row(
                Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(c.field)
                    .drawBehind {
                        val w = size.width / CROSSFADE_CHOICES.size
                        drawRoundRect(c.accent.copy(alpha = 0.22f), Offset(w * at + 3.dp.toPx(), 3.dp.toPx()), Size(w - 6.dp.toPx(), size.height - 6.dp.toPx()), CornerRadius(size.height))
                    },
            ) {
                CROSSFADE_CHOICES.forEach { n ->
                    Box(
                        Modifier
                            .width(60.dp)
                            .height(36.dp)
                            .clip(CircleShape)
                            .clickable(remember { MutableInteractionSource() }, null) {
                                if (n != now) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                scope.launch { prefs.setCrossfade(n) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val fg by animateColorAsState(if (n == now) c.text else c.textMuted, tween(Motion.EFFECTS), label = "crossfadeFg")
                        Text(if (n == 0) "No" else "$n s", style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = fg)
                    }
                }
            }
        }
    }
}

/** Lo que ocupa Oto en el móvil y su tope (ver [Storage]); tocar lo vacía. */
@Composable
private fun SpaceRow(app: OtoApp) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var used by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(clearing) { if (!clearing) used = withContext(Dispatchers.IO) { Storage.used(app) } }
    val body = when {
        clearing -> "Vaciando…"
        used == null -> "Midiendo…"
        else -> "${bytes(used!!)} ahora · nunca más de ${bytes(Storage.TOTAL_BYTES)}. Toca para vaciarlo."
    }
    SettingRow(R.drawable.ic_hard_drive, "Espacio en el móvil", body) {
        if (clearing) return@SettingRow
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        clearing = true
        scope.launch {
            withContext(Dispatchers.IO) { Storage.clear(app, app.player.state.value.id) }
            clearing = false
        }
    }
}

/** 231 MB, 1,3 GB. */
internal fun bytes(n: Long): String =
    if (n >= 1L shl 30) "%.1f GB".format(n / (1L shl 30).toDouble()) else "${(n + (1L shl 19)) shr 20} MB"

@Composable
private fun SettingRow(icon: Int, title: String, body: String, onClick: () -> Unit) {
    val c = Oto.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIcon(icon)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = OtoType.rowTitle, color = c.text)
            Text(body, style = OtoType.rowSub, color = c.textMuted)
        }
    }
}

/** El icono de un ajuste: en una pastilla redonda del color de lo que suena. */
@Composable
private fun SettingIcon(icon: Int) {
    val c = Oto.colors
    Box(Modifier.size(40.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = c.accent)
    }
}

/** Tus listas arriba (con crear una nueva a mano) y lo último que ha sonado debajo. */
@Composable
private fun YourMusic(
    history: List<Heard>?,
    liked: LocalPlaylist?,
    playlists: List<LocalPlaylist>?,
    pinned: Set<String>,
    folders: Set<String>,
    currentId: String?,
    play: (SongItem) -> Unit,
    onHold: (SongItem) -> Unit,
    onOpen: (String) -> Unit,
    onFolder: (String) -> Unit,
    onNew: () -> Unit,
    onListMenu: (LocalPlaylist) -> Unit,
    onFolderMenu: (String) -> Unit,
    dj: Boolean,
    onDj: () -> Unit,
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
        // Dos filas: arriba Me gusta y lo fijado; abajo, el resto. Una carpeta ocupa el sitio de la primera de sus listas.
        // Las carpetas aún vacías van delante, como lo recién creado.
        val used = playlists.mapNotNullTo(HashSet()) { it.folder }
        val shelf = folders.filter { it !in used }.sorted().map { Shelved(it, emptyList()) } +
            playlists.groupBy { it.folder ?: "\u0000" + it.id }.values.map { g -> Shelved(g.first().folder, g) }
        val (top, rest) = shelf.partition { it.key in pinned }
        val card: @Composable LazyItemScope.(Shelved) -> Unit = { e ->
            // Mantener la carátula: sus opciones, sin tener que abrirla.
            if (e.folder == null) PlaylistCard(e.lists.first(), onClick = { onOpen(e.lists.first().id) }, onLongClick = { onListMenu(e.lists.first()) }, modifier = Modifier.animateItem())
            else FolderCard(e.folder, e.lists, onClick = { onFolder(e.folder) }, onLongClick = { onFolderMenu(e.folder) }, modifier = Modifier.animateItem())
        }
        item(key = "pinned") {
            LazyRow(contentPadding = PaddingValues(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                // El DJ y Me gusta van siempre delante, aunque no tengas nada: así se sabe que existen.
                item(key = "dj") { DjCard(dj, onDj) }
                item(key = LIKED_ID) { PlaylistCard(liked, onClick = { onOpen(LIKED_ID) }) }
                items(top, key = { it.key }, itemContent = card)
                if (playlists.isEmpty()) item(key = "new") { NewListCard(onNew) }
            }
        }
        if (rest.isNotEmpty()) item(key = "rest") {
            LazyRow(Modifier.padding(top = Space.xs), contentPadding = PaddingValues(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(rest, key = { it.key }, itemContent = card)
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
private fun PlaylistCard(p: LocalPlaylist, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val c = Oto.colors
    Column(
        modifier
            .width(140.dp + Space.md)
            .clip(RoundedCornerShape(Radius.lg))
            .combinedClickable(remember { MutableInteractionSource() }, ripple(color = c.text), onLongClick = onLongClick, onClick = onClick)
            .padding(Space.xs + 2.dp),
    ) {
        if (p.id == LIKED_ID) LikedArt(140.dp) else Mosaic(p.songs, 140.dp)
        Spacer(Modifier.height(Space.sm))
        Text(p.name, style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count(p.songs.size), style = OtoType.rowSub, color = c.textMuted, maxLines = 1)
    }
}

/** Lo que va en Tus listas: una lista suelta o una carpeta ([folder]) con las suyas. */
private data class Shelved(val folder: String?, val lists: List<LocalPlaylist>) {
    val key get() = folder?.let { "d:$it" } ?: lists.first().id
}

/** Una carpeta: las portadas de sus primeras listas, con la marca de carpeta. */
@Composable
private fun FolderCard(name: String, lists: List<LocalPlaylist>, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Oto.colors
    Column(
        modifier
            .width(140.dp + Space.md)
            .clip(RoundedCornerShape(Radius.lg))
            .combinedClickable(remember { MutableInteractionSource() }, ripple(color = c.text), onLongClick = onLongClick, onClick = onClick)
            .padding(Space.xs + 2.dp),
    ) {
        FolderArt(lists, 140.dp)
        Spacer(Modifier.height(Space.sm))
        Text(name, style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(folderCount(lists.size), style = OtoType.rowSub, color = c.textMuted, maxLines = 1)
    }
}

/** Cuatro listas en cuadrícula (cada una, su primera carátula) y la carpeta en una esquina. */
@Composable
private fun FolderArt(lists: List<LocalPlaylist>, size: Dp) {
    val c = Oto.colors
    // Vacía, solo la carpeta, grande.
    if (lists.none { it.songs.isNotEmpty() }) {
        Box(Modifier.size(size).clip(RoundedCornerShape(if (size < 64.dp) Radius.sm else Radius.md)).background(c.field), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(size * 0.3f), tint = c.textFaint)
        }
        return
    }
    Box(Modifier.size(size)) {
        Mosaic(lists.mapNotNull { l -> l.songs.firstOrNull { it.artwork != null } }, size)
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(size * 0.06f)
                .size(size * 0.24f)
                .clip(CircleShape)
                .background(c.base.copy(alpha = 0.78f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(size * 0.13f), tint = c.text)
        }
    }
}

private fun folderCount(n: Int) = if (n == 1) "Carpeta · 1 lista" else "Carpeta · $n listas"

/** Dentro de una carpeta: sus listas, para abrirlas, y sonar todas seguidas. */
@Composable
private fun FolderView(
    name: String,
    lists: List<LocalPlaylist>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onPlay: (List<LocalPlaylist>) -> Unit,
    onMenu: () -> Unit,
    onAdd: () -> Unit,
    onListMenu: (LocalPlaylist) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
        item(key = "head") {
            PageHeader(onBack, { FolderArt(lists, 112.dp) }, name, folderCount(lists.size), onMenu = onMenu) {
                if (lists.any { it.songs.isNotEmpty() }) {
                    Pill("Reproducir", { onPlay(lists) }, icon = R.drawable.ic_play_arrow)
                    ShuffleButton()
                } else Pill("Añadir listas", onAdd, icon = R.drawable.ic_add)
            }
        }
        if (lists.isEmpty()) item(key = "empty") {
            Empty(R.drawable.ic_folder, "Está vacía", "Añade listas con el botón de arriba, o desde el menú (···) de cada lista.", Modifier.height(220.dp))
        }
        items(lists, key = { it.id }) { l ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.lg))
                    .combinedClickable(remember { MutableInteractionSource() }, ripple(color = Oto.colors.text), onLongClick = { onListMenu(l) }) { onOpen(l.id) }
                    .padding(horizontal = Space.sm, vertical = 6.dp)
                    .animateItem(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mosaic(l.songs, 52.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(l.name, style = OtoType.rowTitle, color = Oto.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(count(l.songs.size), style = OtoType.rowSub, color = Oto.colors.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/** Qué listas van en [folder]: todas las tuyas con su marca; las de otra carpeta dicen cuál. */
@Composable
private fun ColumnScope.FillFolder(folder: String, lists: List<LocalPlaylist>, onToggle: (LocalPlaylist, Boolean) -> Unit) {
    val c = Oto.colors
    Text(folder, style = OtoType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(Space.md))
    if (lists.isEmpty()) Text("Aún no tienes listas.", style = OtoType.rowSub, color = c.textMuted, modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm))
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        items(lists, key = { it.id }) { l ->
            val inside = l.folder == folder
            ToggleRow({ Mosaic(l.songs, 40.dp) }, l.name, l.folder?.takeIf { !inside }?.let { "En «$it»" } ?: count(l.songs.size), inside) { onToggle(l, !inside) }
        }
    }
}

/** A qué carpeta va una lista: una de las que hay, una nueva o ninguna. */
@Composable
private fun ColumnScope.MoveTo(list: LocalPlaylist, folders: List<String>, onPick: (String?) -> Unit) {
    val c = Oto.colors
    Text(list.name, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(Space.md))
    folders.forEach { f ->
        ActionRow(R.drawable.ic_folder, f, trailing = if (f == list.folder) { { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = c.accent) } } else null) {
            onPick(f)
        }
    }
    if (list.folder != null) ActionRow(R.drawable.ic_close, "Sacarla de «${list.folder}»") { onPick(null) }
    Box(Modifier.padding(horizontal = Space.xs)) {
        NewListRow("", label = "Nueva carpeta…", icon = R.drawable.ic_create_new_folder) { onPick(it) }
    }
}

@Composable
private fun ColumnScope.FolderActions(folder: String, pinned: Boolean, onPin: () -> Unit, onAdd: () -> Unit, onRename: () -> Unit, onUngroup: () -> Unit) {
    Text(folder, style = OtoType.rowTitle, color = Oto.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(Space.md))
    ActionRow(R.drawable.ic_keep, if (pinned) "Quitar de arriba" else "Fijar arriba", onClick = onPin)
    ActionRow(R.drawable.ic_add, "Añadir o quitar listas", onClick = onAdd)
    ActionRow(R.drawable.ic_edit, "Cambiar el nombre", onClick = onRename)
    // Sus listas se quedan, fuera de ella.
    ActionRow(R.drawable.ic_folder, "Deshacer la carpeta", onClick = onUngroup)
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
    download: Boolean?,
    onDownload: () -> Unit,
    onArtist: (String, String) -> Unit,
    onRemove: (() -> Unit)?,
    here: String?,
) {
    val c = Oto.colors
    val context = LocalContext.current
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
    if (download != null) ActionRow(if (download) R.drawable.ic_download else R.drawable.ic_close, if (download) "Descargar" else "Quitar la descarga", onClick = onDownload)
    song.artists.firstOrNull { it.id != null && it.id != here }?.let { a -> ActionRow(R.drawable.ic_person, "Ir a ${a.name}") { onArtist(a.id!!, a.name) } }
    ActionRow(R.drawable.ic_share, "Compartir") { shareSong(context, song.id, song.title, song.artists.joinToString { it.name }) }
    if (onRemove != null) ActionRow(R.drawable.ic_delete, if (h.list == LIKED_ID) "Quitar de Me gusta" else "Quitar de esta lista", onClick = onRemove)
}

@Composable
private fun ColumnScope.ListActions(
    list: LocalPlaylist,
    pinned: Boolean,
    downloaded: Boolean,
    onDownload: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Oto.colors
    // Borrar y quitar la descarga piden un segundo toque: no hay deshacer, y volver a bajarla gasta datos.
    var confirm by remember(list) { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(3000); confirm = false } }
    var unDownload by remember(list) { mutableStateOf(false) }
    LaunchedEffect(unDownload) { if (unDownload) { delay(3000); unDownload = false } }
    Text(list.name, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(Space.md))
    if (list.songs.isNotEmpty() || downloaded) {
        if (!downloaded) ActionRow(R.drawable.ic_download, "Descargar", onClick = onDownload)
        else ActionRow(R.drawable.ic_download_done, if (unDownload) "Toca otra vez para quitarla" else "Quitar la descarga", tint = if (unDownload) Color(0xFFFF8A80) else c.text) {
            if (unDownload) onDownload() else unDownload = true
        }
    }
    // Me gusta no se fija, ni se renombra, ni se mueve ni se borra.
    if (list.id == LIKED_ID) return
    // Dentro de una carpeta, lo que se fija es la carpeta.
    if (list.folder == null) ActionRow(R.drawable.ic_keep, if (pinned) "Quitar de arriba" else "Fijar arriba", onClick = onPin)
    ActionRow(R.drawable.ic_edit, "Cambiar el nombre", onClick = onRename)
    ActionRow(R.drawable.ic_folder, if (list.folder == null) "Meter en una carpeta" else "Cambiar de carpeta", onClick = onMove)
    ActionRow(R.drawable.ic_delete, if (confirm) "Toca otra vez para borrarla" else "Borrar la lista", tint = if (confirm) Color(0xFFFF8A80) else c.text) {
        if (confirm) onDelete() else confirm = true
    }
}

@Composable
private fun ColumnScope.NameField(n: Naming, onDone: (String) -> Unit) {
    val c = Oto.colors
    var name by remember(n) { mutableStateOf(n.folder ?: n.rename?.name.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(n) { delay(120); focus.requestFocus() }
    val ok = name.isNotBlank()
    Text(
        when { n.rename != null || n.folder != null -> "Cambiar el nombre"; n.newFolder -> "Nueva carpeta"; else -> "Nueva lista" },
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
