package io.github.iaguito22.oto.ui.menu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.sp
import sh.calvin.reorderable.rememberReorderableLazyGridState
import java.text.Collator
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.media3.exoplayer.offline.Download
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import io.github.iaguito22.oto.BuildConfig
import io.github.iaguito22.oto.CROSSFADE_CHOICES
import io.github.iaguito22.oto.DropStyle
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.QualityMode
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.Storage
import io.github.iaguito22.oto.dj.Smart
import io.github.iaguito22.oto.playback.PlayerConnection
import io.github.iaguito22.oto.toHeard
import io.github.iaguito22.oto.toSong
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Radius
import io.github.iaguito22.oto.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class View { Tabs, Recent, Search, Page, Settings, Import, Account }

/** Qué se ve: la vista y, en las páginas, cuántas hay apiladas (cada una entra como un paso más hondo). */
private data class Nav(val view: View, val depth: Int)

/** Una canción mantenida pulsada. [list]: la lista tuya en la que está, si es una. */
private data class Held(val song: SongItem, val list: String? = null)

/** Pedir un nombre: para una lista nueva, para renombrar [rename] o para renombrar la carpeta [folder]. */
private data class Naming(val rename: LocalPlaylist? = null, val folder: String? = null, val newFolder: Boolean = false)

/**
 * Un aviso de abajo. Con [undo] dura más y lleva «Deshacer»; [commit] hace de verdad lo avisado cuando el aviso se va
 * sin deshacerlo (o lo tapa otro, o se cierra el Menú).
 */
private class Notice(val text: String, val undo: (() -> Unit)? = null, val commit: (() -> Unit)? = null)

/** El id con el que una carpeta suena como una lista (y se vuelve a abrir desde lo que suena). */
const val FOLDER_PREFIX = "folder:"

/**
 * Lo que sale al subir la gota: pestañas Tu música | Explorar, el buscador abajo (al alcance del pulgar) y los
 * ajustes en la esquina. Buscar, un álbum, un artista, una lista o los ajustes se abren dentro de la misma hoja
 * y atrás vuelve. Mantener una canción abre lo que se puede hacer con ella.
 */
@OptIn(FlowPreview::class)
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
    val memory = remember { MenuMemory.of(app) }
    val history by prefs.history.collectAsStateWithLifecycle(initialValue = null)
    val savedLists by prefs.playlists.collectAsStateWithLifecycle(initialValue = null)
    val savedLiked by prefs.likedList.collectAsStateWithLifecycle(initialValue = null)
    val pinned by prefs.pinned.collectAsStateWithLifecycle(initialValue = emptySet())
    val recents by memory.recents.collectAsStateWithLifecycle(initialValue = null)
    val played by memory.played.collectAsStateWithLifecycle(initialValue = emptyMap())
    val source by connection.source.collectAsStateWithLifecycle()
    val np by connection.state.collectAsStateWithLifecycle()
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    // ---- Avisos y lo que se puede deshacer ----
    // Quitar algo no lo borra al momento: desaparece de la vista ([gone]) y se borra de verdad cuando el aviso se va
    // sin «Deshacer». Así deshacer lo deja exactamente como estaba (en su sitio, y una descarga sin volver a bajarla).
    var notice by remember { mutableStateOf<Notice?>(null) }
    var gone by remember { mutableStateOf(emptySet<String>()) }
    val settle = { n: Notice? -> n?.commit?.invoke(); Unit }
    val post = { n: Notice -> settle(notice); notice = n }
    val say = { text: String -> post(Notice(text)) }
    val removeLater = { key: String, text: String, act: suspend () -> Unit ->
        gone = gone + key
        post(
            Notice(
                text,
                undo = { gone = gone - key },
                commit = {
                    app.scope.launch {
                        act()
                        // Que llegue antes lo guardado: si no, asoma un instante lo quitado.
                        delay(400)
                        withContext(Dispatchers.Main) { gone = gone - key }
                    }
                },
            ),
        )
    }
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        delay(if (n.undo != null) UNDO_MS else NOTICE_MS)
        if (notice === n) { notice = null; settle(n) }
    }
    val pending by rememberUpdatedState(notice)
    DisposableEffect(Unit) { onDispose { settle(pending) } }

    // Lo quitado y aún sin borrar ya no se ve en ningún sitio.
    val playlists = remember(savedLists, gone) {
        savedLists?.filter { "l:${it.id}" !in gone }?.map { l -> if (gone.none { it.startsWith("s:${l.id}:") }) l else l.copy(songs = l.songs.filter { "s:${l.id}:${it.id}" !in gone }) }
    }
    val liked = remember(savedLiked, gone) {
        savedLiked?.let { l -> if (gone.none { it.startsWith("s:$LIKED_ID:") }) l else l.copy(songs = l.songs.filter { "s:$LIKED_ID:${it.id}" !in gone }) }
    }

    // El gusto (para la búsqueda) y las listas hechas con lo tuyo (para Explorar) se rehacen al cambiar lo guardado o
    // lo oído, y cada canción que empieza cambia el historial. Con muchas listas rehacerlas cuesta segundos y el
    // cálculo no se corta a medias: pasando canciones rápido se amontonaban hasta dejar el móvil sin memoria. Así se
    // espera a que el historial pare quieto un momento, y collectLatest no empieza uno sin que acabe el anterior.
    var smart by remember { mutableStateOf(emptyList<LocalPlaylist>()) }
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(history, savedLiked, savedLists) }
            .debounce(RECOMPUTE_MS)
            .collectLatest { (h, lk, l) ->
                vm.taste = withContext(Dispatchers.Default) {
                    Taste.of(h.orEmpty().asSequence() + lk?.songs.orEmpty() + l.orEmpty().asSequence().flatMap { it.songs })
                }
                if (h == null || l == null) return@collectLatest
                smart = withContext(Dispatchers.Default) {
                    runCatching { Smart.lists(l, lk?.songs.orEmpty(), h, app.plays.all(), System.currentTimeMillis()) }.getOrDefault(emptyList())
                }
            }
    }
    // Cuándo sonó cada lista o carpeta: Tu música enseña primero las que has oído hace menos.
    LaunchedEffect(Unit) {
        connection.source.collect { src ->
            val id = src?.local ?: return@collect
            if (id == DOWNLOADS_ID || id == LIKED_ID) return@collect
            memory.markPlayed(if (id.startsWith(FOLDER_PREFIX)) "d:" + id.removePrefix(FOLDER_PREFIX) else id, System.currentTimeMillis())
        }
    }
    // Recolocando la fila de arriba de Tus listas: cada una se arrastra a su sitio, hasta «Listo».
    var arranging by remember { mutableStateOf(false) }
    var djMenu by remember { mutableStateOf(false) }
    val spots by prefs.spots.collectAsStateWithLifecycle(initialValue = null)
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val searchFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    val musicList = rememberLazyListState()
    val exploreList = rememberLazyListState()
    var settings by remember { mutableStateOf(false) }
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
    val savedDownloadLists by prefs.downloadLists.collectAsStateWithLifecycle(initialValue = null)
    val savedDownloadSongs by prefs.downloadSongs.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadLists = remember(savedDownloadLists, gone) { savedDownloadLists?.filterTo(HashSet()) { "dl:$it" !in gone } }
    val downloadSongs = remember(savedDownloadSongs, gone) { savedDownloadSongs.filter { "ds:${it.id}" !in gone } }
    val downloadStates by app.downloads.states.collectAsStateWithLifecycle()
    val saver by prefs.dataSaver.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    val waitsForWifi = { saver && context.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered }

    val view = when {
        accounting -> View.Account
        // Tocar el buscador vacío enseña lo buscado hace poco, encima de lo que hubiera abierto.
        searchFocused && s.query.isBlank() -> View.Recent
        importing -> View.Import
        settings -> View.Settings
        s.pages.isNotEmpty() -> View.Page
        s.query.isNotBlank() -> View.Search
        else -> View.Tabs
    }
    val top = s.pages.lastOrNull()
    val downloadsOpen = view == View.Page && top == Page.Downloads
    BackHandler(open && view != View.Tabs) {
        when (view) {
            View.Account -> accounting = false
            View.Recent -> focus.clearFocus()
            View.Import -> importing = false
            View.Settings -> settings = false
            View.Page -> vm.closeDetail()
            View.Search -> { vm.onQuery(""); focus.clearFocus() }
            View.Tabs -> Unit
        }
    }
    // Después del de arriba: con algo flotando, atrás lo cierra primero.
    BackHandler(open && (held != null || naming != null || listMenu != null || saving != null || moving != null || folderMenu != null || creating || filling != null || djMenu)) {
        held = null; naming = null; listMenu = null; saving = null; moving = null; folderMenu = null; creating = false; filling = null; djMenu = false
    }
    BackHandler(open && arranging && view == View.Tabs) { arranging = false }

    // Al cerrarse, vuelve a como se abre siempre: «Tu música» arriba del todo, sin páginas, ajustes ni nada flotando.
    LaunchedEffect(open) {
        if (open) return@LaunchedEffect
        held = null; naming = null; listMenu = null; saving = null; moving = null; folderMenu = null; creating = false; filling = null
        arranging = false; djMenu = false
        settle(notice); notice = null
        settings = false; importing = false; accounting = false
        focus.clearFocus()
        vm.closeAll()
        pager.scrollToPage(0)
        musicList.scrollToItem(0)
        exploreList.scrollToItem(0)
    }

    val play: (SongItem) -> Unit = { focus.clearFocus(); connection.play(it); onClose() }
    val hold: (SongItem, String?) -> Unit = { song, list ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        focus.clearFocus()
        held = Held(song, list)
    }
    // Buscar desde cero (escribiendo o desde lo reciente) con algo abierto lleva a los resultados: si no, se buscaba
    // detrás, sin verse.
    val search = { q: String ->
        if (q.isNotBlank() && s.query.isBlank()) { settings = false; importing = false; vm.closeAll() }
        vm.onQuery(q)
    }
    val rememberSearch = { item: YTItem? ->
        val q = s.query
        scope.launch {
            if (q.trim().length >= 2) memory.remember(Recent.query(q))
            item?.let(Recent::of)?.let { memory.remember(it) }
        }
    }
    // «Mover»: lo que está en la fila de arriba se recoloca ahí; lo demás, en «Todas tus listas».
    val arrange = { key: String ->
        if (view == View.Tabs && (key == "dj" || key == LIKED_ID || key in pinned)) arranging = true else vm.openLists(arranging = true)
    }
    val canArrange = view == View.Tabs || top is Page.Lists

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(Oto.colors.textFaint))
            }
            Row(Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.sm, top = Space.md, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                // Fuera de las pestañas (ajustes, descargas) su indicador se apaga: ahí no estás en ninguna.
                Tabs(pager, dim = downloadsOpen || view == View.Settings || view == View.Import || view == View.Account, onSelect = { i ->
                    // La pestaña en la que ya estás sube arriba del todo.
                    if (view == View.Tabs && pager.currentPage == i) {
                        scope.launch { (if (i == 0) musicList else exploreList).animateScrollToItem(0) }
                        return@Tabs
                    }
                    settings = false; importing = false; accounting = false; vm.closeAll(); vm.onQuery(""); focus.clearFocus()
                    scope.launch { pager.animateScrollToPage(i, animationSpec = Motion.spatial()) }
                })
                Spacer(Modifier.weight(1f))
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
                    View.Tabs -> HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !arranging) { page ->
                        if (page == 0) YourMusic(history, liked, playlists, pinned, folders, played, currentId, play, onHold = { hold(it, null) }, onOpen = vm::openLocal, onFolder = vm::openFolder, onNew = { creating = true },
                            dj = source?.kind == "DJ" && np.playing,
                            onDj = {
                                if (playlists.orEmpty().all { it.songs.isEmpty() } && liked?.songs.isNullOrEmpty()) say("El DJ aprende de tus listas: guarda algo primero")
                                else { connection.playDj(onFail = { say("El DJ no ha podido empezar") }); onClose() }
                            },
                            onListMenu = { listMenu = it },
                            onFolderMenu = { folderMenu = it },
                            arranging = arranging, onDoneArranging = { arranging = false },
                            onReorder = { ids, s -> scope.launch { prefs.orderLists(ids); if (s.isNotEmpty()) prefs.setSpots(s) } },
                            spots = spots, onDjMenu = { djMenu = true }, onAll = { vm.openLists() }, state = musicList)
                        else Explore(
                            s, smart, currentId, play, onHold = { hold(it, null) }, onOpen = vm::open, onSmart = vm::openSmart, onRetry = vm::loadHome,
                            onChip = vm::onChip, state = exploreList,
                        )
                    }
                    View.Recent -> Recents(
                        recents,
                        onPick = { r ->
                            scope.launch { memory.remember(r) }
                            val item = r.item()
                            if (item == null) search(r.title) else vm.open(item)
                            focus.clearFocus()
                        },
                        onForget = { r -> scope.launch { memory.forget(r) } },
                    )
                    View.Search -> Column {
                        Filters(s.filter, vm::onFilter)
                        Results(
                            s, currentId,
                            play = { rememberSearch(null); play(it) },
                            onHold = { hold(it, null) },
                            onOpen = { focus.clearFocus(); rememberSearch(it); vm.open(it) },
                            // El resultado principal se pone sin abrirlo: lo suyo (un álbum, lo más oído de un artista) entero.
                            onPlayItem = { item, done ->
                                rememberSearch(item)
                                scope.launch {
                                    val songs = vm.songsOf(item)
                                    done()
                                    if (songs == null) say("No se ha podido poner «${item.title}»")
                                    else { focus.clearFocus(); connection.playAll(songs, null, item); onClose() }
                                }
                            },
                        )
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
                            onFind = { searchFocus.requestFocus() },
                        )
                        is Page.Smart -> SmartView(
                            p.list, currentId, onBack = vm::closeDetail,
                            onPlay = { i -> connection.playLocal(p.list, i, false); onClose() },
                            onHold = { hold(it, null) },
                            onSave = { saving = Saving(p.list.songs, p.list.name, count(p.list.songs.size), p.list.songs.firstOrNull()?.artwork) },
                        )
                        Page.Downloads -> DownloadsView(
                            currentId, gone = gone, onBack = vm::closeDetail, onOpen = vm::openLocal, play = play,
                            onPlayAll = { list -> connection.playLocal(list, null, false); onClose() }, onHold = { hold(it, null) },
                            onRemoveList = { l -> removeLater("dl:${l.id}", "«${l.name}» ya no está descargada") { prefs.setDownloadList(l.id, false) } },
                            onRemoveSong = { h -> removeLater("ds:${h.id}", "«${h.title}» ya no está descargada") { prefs.setDownloadSong(h, false) } },
                        )
                        is Page.Folder -> FolderView(
                            p.name, playlists.orEmpty().filter { it.folder == p.name }, onBack = vm::closeDetail, onOpen = vm::openLocal,
                            onPlay = { lists -> connection.playLocal(LocalPlaylist(FOLDER_PREFIX + p.name, p.name, lists.flatMap { it.songs }.distinctBy { it.id }), null, false); onClose() },
                            onMenu = { folderMenu = p.name },
                            onAdd = { filling = p.name },
                            onListMenu = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); listMenu = it },
                        )
                        is Page.Lists -> AllListsView(
                            shelfOf(playlists.orEmpty(), folders), played, downloadLists.orEmpty(), arranging = p.arranging,
                            onBack = vm::closeDetail,
                            onOpen = { e -> if (e.folder != null) vm.openFolder(e.folder) else vm.openLocal(e.lists.first().id) },
                            onMenu = { e -> if (e.folder != null) folderMenu = e.folder else listMenu = e.lists.first() },
                            onNew = { creating = true },
                            onDoneArranging = { vm.openLists(arranging = false) },
                            onMove = { ids -> scope.launch { prefs.orderLists(ids) } },
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
                    var last by remember { mutableStateOf<Notice?>(null) }
                    notice?.let { last = it }
                    last?.let { n -> NoticePill(n) { notice = null; n.undo?.invoke() } }
                }
            }
            // Entrando con Google, el teclado es para la contraseña: el buscador sobra.
            if (view != View.Account) SearchField(
                s.query, s.searching, onQuery = search,
                onSearch = { rememberSearch(null); focus.clearFocus() },
                focus = searchFocus,
                onFocus = { searchFocused = it },
            )
        }

        // ---- Lo que flota encima: acciones de una canción, opciones de una lista y pedir un nombre ----

        Floating(held, onDismiss = { held = null }) { h ->
            SongActions(
                h,
                onNext = {
                    connection.playNext(h.song)
                    say("«${h.song.title}» va después de esta")
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
                    val song = h.song.toHeard()
                    if (downloadSongs.none { it.id == song.id }) {
                        scope.launch { prefs.setDownloadSong(song, true) }
                        say("Descargando «${song.title}»" + (if (waitsForWifi()) " cuando haya wifi" else ""))
                    } else removeLater("ds:${song.id}", "«${song.title}» ya no está descargada") { prefs.setDownloadSong(song, false) }
                },
                onArtist = { id, name -> held = null; vm.openArtist(id, name) },
                onRemove = h.list?.let { list ->
                    {
                        held = null
                        val where = if (list == LIKED_ID) "Me gusta" else savedLists?.firstOrNull { it.id == list }?.name ?: "la lista"
                        removeLater("s:$list:${h.song.id}", "«${h.song.title}» quitada de «$where»") {
                            if (list == LIKED_ID) prefs.setLiked(h.song.toHeard(), false) else prefs.removeFromPlaylist(list, h.song.id)
                        }
                    }
                },
                // En la página de un artista no se ofrece ir a él.
                here = ((top as? Page.Remote)?.item as? ArtistItem)?.id,
            )
        }
        Floating(djMenu.takeIf { it }, onDismiss = { djMenu = false }) {
            FloatHeader({ DjArt(48.dp, source?.kind == "DJ" && np.playing) }, "DJ", "Mezcla para ti, con lo que oyes")
            ActionRow(R.drawable.ic_swap_horiz, "Mover") { djMenu = false; arranging = true }
        }
        Floating(listMenu, onDismiss = { listMenu = null }) { list ->
            val downloaded = list.id in downloadLists.orEmpty()
            ListActions(
                list,
                pinned = list.id in pinned,
                downloaded = downloaded,
                downloadState = if (downloaded) list.songs.count { downloadStates[it.id] == Download.STATE_COMPLETED } else null,
                onDownload = {
                    listMenu = null
                    if (!downloaded) {
                        scope.launch { prefs.setDownloadList(list.id, true) }
                        say("Descargando «${list.name}»" + (if (waitsForWifi()) " cuando haya wifi" else ""))
                    } else removeLater("dl:${list.id}", "«${list.name}» ya no está descargada") { prefs.setDownloadList(list.id, false) }
                },
                onPin = { listMenu = null; scope.launch { prefs.togglePin(list.id) } },
                onRename = { listMenu = null; naming = Naming(rename = list) },
                onMove = { listMenu = null; moving = list },
                // Suelta: dentro de una carpeta, o abierta, no hay sitio que recolocar.
                onArrange = if (list.folder == null && canArrange) ({ listMenu = null; arrange(list.id) }) else null,
                onDelete = {
                    listMenu = null
                    if ((top as? Page.Local)?.id == list.id) vm.closeDetail()
                    removeLater("l:${list.id}", "«${list.name}» borrada") { prefs.deletePlaylist(list.id) }
                },
            )
        }
        Floating(moving, onDismiss = { moving = null }) { list ->
            MoveTo(list, (playlists.orEmpty().mapNotNull { it.folder } + folders).distinct(), onPick = { folder ->
                moving = null
                scope.launch { prefs.moveToFolder(list.id, folder) }
                say(if (folder == null) "«${list.name}» fuera de la carpeta" else "«${list.name}» en «$folder»")
            })
        }
        Floating(creating.takeIf { it }, onDismiss = { creating = false }) {
            FloatHeader({ IconArt(R.drawable.ic_add, 48.dp) }, "Nueva", "Una lista, o una carpeta para juntar listas")
            ActionRow(R.drawable.ic_queue_music, "Lista") { creating = false; naming = Naming() }
            ActionRow(R.drawable.ic_create_new_folder, "Carpeta") { creating = false; naming = Naming(newFolder = true) }
        }
        Floating(filling, onDismiss = { filling = null }) { folder ->
            FillFolder(folder, playlists.orEmpty()) { list, inside -> scope.launch { prefs.moveToFolder(list.id, if (inside) folder else null) } }
        }
        Floating(folderMenu, onDismiss = { folderMenu = null }) { folder ->
            val inside = playlists.orEmpty().filter { it.folder == folder }
            FolderActions(
                folder,
                inside,
                pinned = "d:$folder" in pinned,
                onPin = { folderMenu = null; scope.launch { prefs.togglePin("d:$folder") } },
                onAdd = { folderMenu = null; filling = folder },
                onRename = { folderMenu = null; naming = Naming(folder = folder) },
                onArrange = if (canArrange && inside.isNotEmpty()) ({ folderMenu = null; arrange("d:$folder") }) else null,
                onUngroup = {
                    folderMenu = null
                    if ((top as? Page.Folder)?.name == folder) vm.closeDetail()
                    scope.launch { prefs.ungroup(folder) }
                    say("Carpeta «$folder» deshecha")
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

/** El aviso: una frase y, si se puede, «Deshacer». */
@Composable
private fun NoticePill(n: Notice, onUndo: () -> Unit) {
    val c = Oto.colors
    Row(
        Modifier
            .padding(bottom = Space.sm)
            .widthIn(max = 360.dp)
            .height(36.dp)
            .clip(CircleShape)
            .background(c.field)
            .padding(start = 14.dp, end = if (n.undo != null) Space.xs else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(n.text, style = OtoType.rowSub, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (n.undo != null) {
            Spacer(Modifier.width(Space.xs))
            Text(
                "Deshacer", style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize), color = c.accent,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onUndo)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** Dos pestañas con un indicador que sigue al dedo mientras se desliza entre páginas. [dim]: fuera de ellas, apagado. */
@Composable
private fun Tabs(pager: PagerState, dim: Boolean, onSelect: (Int) -> Unit) {
    val c = Oto.colors
    val labels = listOf("Tu música", "Explorar")
    val lit by animateFloatAsState(if (dim) 0.3f else 1f, tween(Motion.EFFECTS), label = "tabsLit")
    Row(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(c.field)
            .drawBehind {
                val f = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                val w = size.width / 2
                drawRoundRect(c.accent.copy(alpha = 0.22f * lit), Offset(lerp(0f, w, f) + 3.dp.toPx(), 3.dp.toPx()), Size(w - 6.dp.toPx(), size.height - 6.dp.toPx()), CornerRadius(size.height))
            },
    ) {
        labels.forEachIndexed { i, l ->
            val selected = pager.currentPage == i && !dim
            Box(
                Modifier
                    .width(104.dp)
                    .height(40.dp)
                    .clip(CircleShape)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                val fg by animateColorAsState(if (selected) c.text else c.textMuted, tween(Motion.EFFECTS), label = "tabFg")
                Text(l, style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.08f), color = fg)
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
private fun SearchField(query: String, searching: Boolean, onQuery: (String) -> Unit, onSearch: () -> Unit, focus: FocusRequester, onFocus: (Boolean) -> Unit) {
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
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { onFocus(it.isFocused) },
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
        text, style = OtoType.section, color = Oto.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = Space.lg, end = Space.lg, top = Space.lg, bottom = Space.sm),
    )
}

/** Un carrusel (de Explorar o de un artista). YouTube repite a veces canciones: las claves llevan la posición. */
private fun LazyListScope.shelves(list: List<Shelf>, currentId: String?, play: (SongItem) -> Unit, onHold: (SongItem) -> Unit, onOpen: (YTItem) -> Unit) {
    itemsIndexed(list, key = { i, it -> "$i:${it.title}" }) { _, shelf ->
        SectionTitle(shelf.title)
        val songs = shelf.items.filterIsInstance<SongItem>()
        // Solo canciones: en columnas de cuatro, como «Selecciones rápidas» de YouTube Music. Caben ocho a la vista y no
        // se confunden con los álbumes y las listas, que siguen con su carátula grande.
        if (songs.size == shelf.items.size && songs.size > SONGS_PER_COLUMN) SongColumns(songs, currentId, play, onHold)
        else LazyRow(contentPadding = PaddingValues(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            itemsIndexed(shelf.items, key = { i, it -> "$i:${it.id}" }) { _, item ->
                Card(item, current = item.id == currentId, onClick = { if (item is SongItem) play(item) else onOpen(item) }, onLongClick = (item as? SongItem)?.let { { onHold(it) } })
            }
        }
    }
}

/** Un carrusel de canciones en columnas de [SONGS_PER_COLUMN] filas; se para siempre con una columna al borde. */
@Composable
private fun SongColumns(songs: List<SongItem>, currentId: String?, play: (SongItem) -> Unit, onHold: (SongItem) -> Unit) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
        contentPadding = PaddingValues(horizontal = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        itemsIndexed(songs.chunked(SONGS_PER_COLUMN), key = { i, _ -> i }) { _, column ->
            // Lo justo para que asome la siguiente columna.
            Column(Modifier.fillParentMaxWidth(0.86f)) {
                column.forEach { song ->
                    SongRow(song.title, song.artists.joinToString { it.name }, song.thumbnail, song.id == currentId, onClick = { play(song) }, onLongClick = { onHold(song) })
                }
            }
        }
    }
}

@Composable
private fun Explore(
    s: MenuState,
    smart: List<LocalPlaylist>,
    currentId: String?,
    play: (SongItem) -> Unit,
    onHold: (SongItem) -> Unit,
    onOpen: (YTItem) -> Unit,
    onSmart: (LocalPlaylist) -> Unit,
    onRetry: () -> Unit,
    onChip: (String) -> Unit,
    state: LazyListState,
) {
    val list = s.shelves
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = Space.lg)) {
        // Los ánimos de YouTube Music (Relax, Entrenar…): elegir uno rehace lo de debajo con ese ánimo.
        if (s.chips.isNotEmpty()) item(key = "chips") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.xs),
                modifier = Modifier.padding(top = Space.xs),
            ) {
                items(s.chips, key = { it.title }) { chip -> Chip(chip.title, chip.title == s.chip) { onChip(chip.title) } }
            }
        }
        // Lo hecho con lo tuyo va primero y no espera a YouTube: sale aunque no haya red. Con un ánimo elegido, sobra.
        if (smart.isNotEmpty() && s.chip == null) item(key = "smart") {
            SectionTitle("Hecho con lo tuyo")
            LazyRow(contentPadding = PaddingValues(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(smart, key = { it.id }) { l -> PlaylistCard(l, onClick = { onSmart(l) }) }
            }
        }
        when {
            list == null && s.homeError != null -> item(key = "error") {
                Empty(R.drawable.ic_library_music, "No se pudo cargar", s.homeError, Modifier.height(320.dp)) { Pill("Reintentar", onRetry, filled = false) }
            }
            list == null -> item(key = "loading") { ShelfSkeleton(3) }
            else -> shelves(list, currentId, play, onHold, onOpen)
        }
    }
}

/** Una lista hecha con lo tuyo: se pone o se guarda como lista tuya, pero no se edita. */
@Composable
private fun SmartView(list: LocalPlaylist, currentId: String?, onBack: () -> Unit, onPlay: (Int?) -> Unit, onHold: (SongItem) -> Unit, onSave: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
        item(key = "head") {
            PageHeader(onBack, { Mosaic(list.songs, 112.dp) }, list.name, count(list.songs.size) + " · hecha con lo tuyo") {
                Pill("Reproducir", { onPlay(null) }, icon = R.drawable.ic_play_arrow)
                ShuffleButton()
                RoundAction(R.drawable.ic_add, "Guardar", onClick = onSave)
            }
        }
        itemsIndexed(list.songs, key = { _, it -> it.id }) { i, h ->
            SongRow(h.title, h.artist, h.artwork, h.id == currentId, onClick = { onPlay(i) }, onLongClick = { onHold(h.toSong()) })
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
        items(Filter.entries, key = { it.name }) { f -> Chip(f.label, f == selected) { onSelect(f) } }
    }
}

/** Una píldora que se elige: los filtros de la búsqueda, los ánimos de Explorar, los de Tus listas. */
@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Oto.colors
    val bg by animateColorAsState(if (on) c.accent.copy(alpha = 0.22f) else c.field, tween(Motion.EFFECTS), label = "chipBg")
    val fg by animateColorAsState(if (on) c.accent else c.textMuted, tween(Motion.EFFECTS), label = "chipFg")
    Text(
        label, style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = fg, maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Results(
    s: MenuState,
    currentId: String?,
    play: (SongItem) -> Unit,
    onHold: (SongItem) -> Unit,
    onOpen: (YTItem) -> Unit,
    /** Poner lo de un álbum, una lista o un artista sin abrirlo; luego [done]. */
    onPlayItem: (YTItem, done: () -> Unit) -> Unit,
) {
    when {
        s.searchError != null && s.results.isEmpty() -> Empty(R.drawable.ic_search, "La búsqueda falló", s.searchError)
        s.results.isEmpty() && s.searching -> Skeleton(modifier = Modifier.padding(Space.md))
        s.results.isEmpty() && s.query.trim().length < 2 -> Empty(R.drawable.ic_search, "Sigue escribiendo", "Con dos letras ya busca.")
        s.results.isEmpty() -> Empty(R.drawable.ic_search, "Sin resultados", "Prueba con otro nombre o con el artista.")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.sm, vertical = Space.xs)) {
            // Canciones, artistas, álbumes y listas mezclados, como en Spotify: lo que no es una canción se abre.
            itemsIndexed(s.results, key = { _, it -> it.id }) { i, item ->
                // Arriba, lo que YouTube Music da por principal (ver Search.rank): en grande y con su botón de poner.
                if (i == 0 && s.filter == Filter.All) TopResult(
                    item, item.id == currentId,
                    onClick = { if (item is SongItem) play(item) else onOpen(item) },
                    onPlay = { done -> if (item is SongItem) { done(); play(item) } else onPlayItem(item, done) },
                    onLongClick = (item as? SongItem)?.let { { onHold(it) } },
                    modifier = Modifier.animateItem(),
                )
                else when (item) {
                    is SongItem -> SongRow(item.title, item.subtitle(), item.thumbnail, item.id == currentId, onClick = { play(item) }, onLongClick = { onHold(item) },
                        modifier = Modifier.animateItem())
                    else -> SongRow(item.title, kind(item), item.thumbnail, false, onClick = { onOpen(item) }, modifier = Modifier.animateItem(), round = item is ArtistItem)
                }
            }
        }
    }
}

/** El resultado principal: carátula grande, nombre, qué es y un botón para ponerlo sin abrirlo. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopResult(item: YTItem, current: Boolean, onClick: () -> Unit, onPlay: (done: () -> Unit) -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Oto.colors
    val round = item is ArtistItem
    // Un álbum o un artista tarda un momento en llegar: mientras, el botón queda a medias y no se repite.
    var loading by remember(item.id) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (loading) 0.5f else 1f, tween(Motion.EFFECTS), label = "topPlay")
    Box(
        modifier
            .padding(horizontal = Space.sm, vertical = Space.xs)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.xl))
            .background(c.field)
            .combinedClickable(remember { MutableInteractionSource() }, ripple(color = c.text), onLongClick = onLongClick, onClick = onClick)
            .padding(Space.lg),
    ) {
        Column(Modifier.padding(end = 56.dp)) {
            Art(item.thumbnail, 88.dp, if (round) 88.dp else Radius.md)
            Spacer(Modifier.height(14.dp))
            Text(item.title, style = OtoType.title, color = if (current) c.accent else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (item is SongItem) "Canción · " + item.artists.joinToString { it.name } else kind(item), style = OtoType.rowSub, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(48.dp)
                .graphicsLayer { this.alpha = alpha }
                .clip(CircleShape)
                .background(c.accent)
                .clickable(remember { MutableInteractionSource() }, ripple(color = c.onAccent)) {
                    if (!loading) { loading = true; onPlay { loading = false } }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_play_arrow), "Reproducir", Modifier.size(28.dp), tint = c.onAccent)
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
            songs == null -> item(key = "loading") {
                Column {
                    Skeleton(rows = TOP_SONGS, modifier = Modifier.padding(top = Space.lg, start = Space.sm, end = Space.sm))
                    ShelfSkeleton(1)
                }
            }
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
    /** Ir a buscar canciones (abre el buscador). */
    onFind: () -> Unit,
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
            val art = @Composable {
                Box {
                    if (likes) LikedArt(112.dp) else Mosaic(list.songs, 112.dp)
                    if (downloaded && list.songs.isNotEmpty()) DownloadMark(got, list.songs.size, Modifier.align(Alignment.BottomEnd).padding(Space.xs + 2.dp), size = 28.dp)
                }
            }
            PageHeader(onBack, art, list.name, sub, onMenu = { onMenu(list) }) {
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
            ) else Empty(R.drawable.ic_queue_music, "Aún no tiene canciones", "", Modifier.height(260.dp)) {
                Pill("Buscar canciones", onFind, icon = R.drawable.ic_search)
            }
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

/** Aleatorio y bucle: los mismos que los botones de las listas; valen ya para lo que suene. */
@Composable
private fun PlaybackSettings(app: OtoApp) {
    val shuffle by app.player.shuffle.collectAsStateWithLifecycle()
    val loop by app.player.loop.collectAsStateWithLifecycle()
    ToggleRow(R.drawable.ic_shuffle, "Aleatorio", "Lo que pongas suena mezclado. También con el botón junto a «Reproducir».", shuffle) {
        app.player.setShuffle(!shuffle)
    }
    ToggleRow(
        R.drawable.ic_repeat, "Repetir la lista",
        if (loop) "Al acabar, la lista vuelve a empezar." else "Al acabar la lista, siguen canciones parecidas.", loop,
    ) { app.player.setLoop(!loop) }
}

/** Cómo se ve la pantalla de lo que suena: la gota, el tiempo y la letra. */
@Composable
private fun ScreenSettings(prefs: Prefs) {
    val scope = rememberCoroutineScope()
    val style by prefs.dropStyle.collectAsStateWithLifecycle(initialValue = null)
    val remaining by prefs.showRemaining.collectAsStateWithLifecycle(initialValue = null)
    val romanize by prefs.romanize.collectAsStateWithLifecycle(initialValue = null)
    val beat by prefs.coverBeat.collectAsStateWithLifecycle(initialValue = null)
    val s = style ?: return
    ChoiceRow(
        R.drawable.ic_ring, "Gota",
        if (s == DropStyle.Ring) "Un aro fino del color de la canción. Lo más limpio." else "La gota líquida, que se estira al llevarla y late con la música.",
        listOf("Aro", "Líquida"), DropStyle.entries.indexOf(s), 86.dp,
    ) { scope.launch { prefs.setDropStyle(DropStyle.entries[it]) } }
    val b = beat ?: return
    ToggleRow(R.drawable.ic_graphic_eq, "Carátula al ritmo", "La carátula late un poco con los graves de lo que suena.", b) {
        scope.launch { prefs.setCoverBeat(!b) }
    }
    val r = remaining ?: return
    ToggleRow(R.drawable.ic_timer, "Tiempo que falta", if (r) "A la derecha de la barra, lo que falta (-1:23)." else "A la derecha de la barra, lo que dura (3:45).", r) {
        scope.launch { prefs.setShowRemaining(!r) }
    }
    val rom = romanize ?: return
    ToggleRow(R.drawable.ic_translate, "Romanización", "La letra en japonés, coreano o chino lleva debajo cómo se lee. También manteniendo la letra.", rom) {
        scope.launch { prefs.setRomanize(!rom) }
    }
}

/** Un ajuste de sí o no: toda la fila se toca. */
@Composable
private fun ToggleRow(icon: Int, title: String, body: String, on: Boolean, onToggle: () -> Unit) {
    val c = Oto.colors
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text)) {
                haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onToggle()
            }
            .padding(horizontal = Space.md, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIcon(icon)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = OtoType.rowTitle, color = c.text)
            AnimatedContent(body, transitionSpec = { fadeIn(tween(Motion.EFFECTS, delayMillis = 40)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) }, label = "toggleBody") {
                Text(it, style = OtoType.rowSub, color = c.textMuted)
            }
        }
        Spacer(Modifier.width(Space.md))
        Check(on)
    }
}

/** Un ajuste con varias opciones: debajo de la explicación, un selector cuyo indicador se desliza a lo elegido. */
@Composable
private fun ChoiceRow(icon: Int, title: String, body: String, options: List<String>, selected: Int, width: Dp, onSelect: (Int) -> Unit) {
    val c = Oto.colors
    val haptics = LocalHapticFeedback.current
    val at by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.8f, stiffness = 500f), label = "choice")
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.md)) {
        SettingIcon(icon)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = OtoType.rowTitle, color = c.text)
            AnimatedContent(body, transitionSpec = { fadeIn(tween(Motion.EFFECTS, delayMillis = 40)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) }, label = "choiceBody") {
                Text(it, style = OtoType.rowSub, color = c.textMuted, modifier = Modifier.padding(top = 2.dp, bottom = Space.sm))
            }
            Row(
                Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(c.field)
                    .drawBehind {
                        val w = size.width / options.size
                        drawRoundRect(c.accent.copy(alpha = 0.22f), Offset(w * at + 3.dp.toPx(), 3.dp.toPx()), Size(w - 6.dp.toPx(), size.height - 6.dp.toPx()), CornerRadius(size.height))
                    },
            ) {
                options.forEachIndexed { i, label ->
                    Box(
                        Modifier
                            .width(width)
                            .height(36.dp)
                            .clip(CircleShape)
                            .clickable(remember { MutableInteractionSource() }, null) {
                                if (i != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(i)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val fg by animateColorAsState(if (i == selected) c.text else c.textMuted, tween(Motion.EFFECTS), label = "choiceFg")
                        Text(label, style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = fg)
                    }
                }
            }
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
            PlaybackSettings(app)
            ScreenSettings(app.prefs)
            SpaceRow(app)
            SettingRow(R.drawable.ic_swipe_up, "Ver los gestos otra vez", "El tutorial del principio", onTutorial)
            // Al final de todo, no fijo debajo de los ajustes.
            Text(
                "Oto ${BuildConfig.VERSION_NAME} · GPL-3.0\nCon InnerTubeX y partes de Metrolist.\nLetras de lyricsplus, NetEase, LRCLIB y YouTube Music.\ngithub.com/iaguito22/oto",
                style = OtoType.rowSub, color = c.textFaint, modifier = Modifier.padding(start = Space.md, end = Space.md, top = Space.lg, bottom = Space.lg),
            )
        }
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

/**
 * Lo que ocupa Oto en el móvil, con una barra hasta su tope (ver [Storage]). Se vacía con su botón y un segundo toque:
 * antes bastaba rozar la fila.
 */
@Composable
private fun SpaceRow(app: OtoApp) {
    val c = Oto.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var used by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(3000); confirm = false } }
    LaunchedEffect(clearing) { if (!clearing) used = withContext(Dispatchers.IO) { Storage.used(app) } }
    val body = when {
        clearing -> "Vaciando…"
        used == null -> "Midiendo…"
        else -> "${bytes(used!!)} de ${bytes(Storage.TOTAL_BYTES)}. Lo descargado no cuenta ni se toca."
    }
    val fill by animateFloatAsState(((used ?: 0L).toFloat() / Storage.TOTAL_BYTES).coerceIn(0f, 1f), tween(Motion.SPATIAL, easing = Motion.Standard), label = "space")
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.md)) {
        SettingIcon(R.drawable.ic_hard_drive)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Espacio en el móvil", style = OtoType.rowTitle, color = c.text, modifier = Modifier.weight(1f))
                val fg by animateColorAsState(if (confirm) DANGER else c.text, tween(Motion.EFFECTS), label = "clearFg")
                Text(
                    if (confirm) "Toca otra vez" else "Vaciar", style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = fg,
                    modifier = Modifier
                        .graphicsLayer { alpha = if (clearing || used == 0L) 0.4f else 1f }
                        .clip(CircleShape)
                        .background(c.field)
                        .clickable(remember { MutableInteractionSource() }, ripple(color = c.text)) {
                            if (clearing || used == 0L) return@clickable
                            if (!confirm) { confirm = true; return@clickable }
                            confirm = false
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            clearing = true
                            scope.launch {
                                withContext(Dispatchers.IO) { Storage.clear(app, app.player.state.value.id) }
                                clearing = false
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Text(body, style = OtoType.rowSub, color = c.textMuted, modifier = Modifier.padding(top = 2.dp))
            Box(Modifier.padding(top = Space.sm).fillMaxWidth().height(6.dp).clip(CircleShape).background(c.field)) {
                Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(CircleShape).background(c.textMuted))
            }
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
    played: Map<String, Long>,
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
    arranging: Boolean,
    onDoneArranging: () -> Unit,
    onReorder: (List<String>, Map<String, Int>) -> Unit,
    spots: Map<String, Int>?,
    onDjMenu: () -> Unit,
    onAll: () -> Unit,
    state: LazyListState,
) {
    if (spots == null) return
    if (history == null || playlists == null || liked == null) return
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = Space.lg)) {
        item(key = "listsTitle") {
            Row(Modifier.fillMaxWidth().padding(end = Space.md), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Tus listas", Modifier.weight(1f))
                // El título lleva más aire arriba que abajo: el botón baja lo mismo para quedar a su altura.
                if (arranging) Box(Modifier.padding(top = Space.sm)) { Pill("Listo", onDoneArranging, icon = R.drawable.ic_check) }
                else if (playlists.isNotEmpty()) Box(Modifier.padding(top = Space.sm)) { Pill("Nueva", onNew, filled = false, icon = R.drawable.ic_add) }
            }
        }
        if (arranging) item(key = "arrangeHint") {
            Text(
                "Arrastra para cambiar el orden de esta fila. Las demás se recolocan en «Todas tus listas».",
                style = OtoType.rowSub, color = Oto.colors.textMuted, modifier = Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.sm),
            )
        }
        // Arriba, en grande, el DJ, Me gusta y lo fijado; debajo, en baldosas, lo demás que has oído hace menos.
        val shelf = shelfOf(playlists, folders)
        val (pinnedShelf, rest) = shelf.partition { it.key in pinned }
        // El DJ y Me gusta están siempre arriba, aunque no tengas nada (así se sabe que existen), donde los dejaras.
        val top = pinnedShelf.toMutableList().apply {
            listOf(Shelved(null, emptyList(), special = "dj"), Shelved(null, listOf(liked), special = LIKED_ID))
                .sortedBy { spots[it.special] ?: 0 }
                .forEach { add((spots[it.special] ?: 0).coerceIn(0, size), it) }
        }
        // Una fila recolocada vuelve a sus mismos huecos entre todas, y cada carpeta lleva sus listas consigo.
        val reorder = { row: List<Shelved> ->
            val moved = row.filter { it.special == null && it.lists.isNotEmpty() }.iterator()
            val keys = row.mapTo(HashSet()) { it.key }
            val ids = shelf.filter { it.lists.isNotEmpty() }.map { if (it.key in keys) moved.next() else it }.flatMap { e -> e.lists.map { it.id } }
            onReorder(ids, row.withIndex().filter { it.value.special != null }.associate { (i, e) -> e.special!! to i })
        }
        val open = { e: Shelved ->
            when {
                e.special == "dj" -> onDj()
                e.special != null -> onOpen(LIKED_ID)
                e.folder == null -> onOpen(e.lists.first().id)
                else -> onFolder(e.folder)
            }
        }
        val menu = { e: Shelved ->
            when {
                e.special == "dj" -> onDjMenu()
                e.folder == null -> onListMenu(e.lists.first())
                else -> onFolderMenu(e.folder)
            }
        }
        item(key = "pinned") {
            ShelfRow(top, arranging, onMove = reorder, onOpen = open, onMenu = menu, dj = dj) {
                if (playlists.isEmpty()) item(key = "new") { NewListCard(onNew) }
            }
        }
        // Con muchas listas, un segundo carrusel no se acababa nunca. Seis a la vista; todas, con su orden y sus filtros,
        // en su página.
        if (rest.isNotEmpty()) item(key = "rest") {
            Tiles(byPlayed(rest, played).take(TILES), Modifier.padding(top = Space.sm), enabled = !arranging, onOpen = open, onMenu = menu)
        }
        if (rest.size > TILES) item(key = "all") { AllRow(playlists.size, enabled = !arranging, onClick = onAll) }
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

/**
 * Una fila de Tus listas. Mantener una lista o carpeta abre sus opciones; con [arranging] («Mover» en ellas), cada
 * una se arrastra a su sitio y tocar no abre nada. Las carpetas vacías no se mueven: no tienen sitio entre las
 * listas, van delante.
 */
@Composable
private fun ShelfRow(
    entries: List<Shelved>,
    arranging: Boolean,
    modifier: Modifier = Modifier,
    onMove: (List<Shelved>) -> Unit,
    onOpen: (Shelved) -> Unit,
    onMenu: (Shelved) -> Unit,
    dj: Boolean = false,
    tail: LazyListScope.() -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val state = rememberLazyListState()
    // Un solo estado para siempre: el gesto de arrastre se queda con lo que veía al empezar, y con
    // remember(entries) el segundo arrastre leía (y guardaba) la fila de antes del primero.
    var order by remember { mutableStateOf(entries) }
    LaunchedEffect(entries) { order = entries }
    val saved by rememberUpdatedState(entries)
    val moved by rememberUpdatedState(onMove)
    val reorder = rememberReorderableLazyListState(state) { from, to ->
        val a = order.indexOfFirst { it.key == from.key }
        val b = order.indexOfFirst { it.key == to.key }
        if (a < 0 || b < 0 || !order[b].movable) return@rememberReorderableLazyListState
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        order = order.toMutableList().apply { add(b, removeAt(a)) }
    }
    LazyRow(modifier, state = state, contentPadding = PaddingValues(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        items(order, key = { it.key }) { e ->
            ReorderableItem(reorder, e.key) { dragging ->
                val movable = arranging && e.movable
                // Recolocando, lo que se puede mover tiembla un poco, como en la pantalla de inicio.
                val wobble = if (movable && !dragging) {
                    val t = rememberInfiniteTransition(label = "wobble")
                    t.animateFloat(-1.2f, 1.2f, infiniteRepeatable(tween(150 + (e.key.hashCode() and 63), easing = LinearEasing), RepeatMode.Reverse), label = "wobble").value
                } else 0f
                val lift = Modifier
                    .graphicsLayer {
                        rotationZ = wobble
                        if (dragging) {
                            scaleX = 1.05f; scaleY = 1.05f
                            shadowElevation = 12.dp.toPx()
                            shape = RoundedCornerShape(Radius.lg)
                            clip = true
                        }
                    }
                    .background(if (dragging) Oto.colors.field else Color.Transparent, RoundedCornerShape(Radius.lg))
                    .draggableHandle(
                        enabled = movable,
                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragStopped = { if (order.map { it.key } != saved.map { it.key }) moved(order) },
                    )
                val click = { if (!arranging) onOpen(e) }
                val hold = { if (!arranging) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onMenu(e) } }
                when {
                    e.special == "dj" -> DjCard(dj, onClick = click, modifier = lift, onLongClick = hold)
                    e.folder == null -> PlaylistCard(e.lists.first(), onClick = click, onLongClick = hold, modifier = lift)
                    else -> FolderCard(e.folder, e.lists, onClick = click, onLongClick = hold, modifier = lift)
                }
            }
        }
        tail()
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

/** Lo que va en Tus listas: una lista suelta, una carpeta ([folder]) con las suyas, o el DJ o Me gusta ([special]). */
private data class Shelved(val folder: String?, val lists: List<LocalPlaylist>, val special: String? = null) {
    val key get() = special ?: folder?.let { "d:$it" } ?: lists.first().id

    /** Las carpetas vacías no se mueven: no tienen sitio entre las listas, van delante. */
    val movable get() = special != null || lists.isNotEmpty()

    val name get() = folder ?: lists.firstOrNull()?.name.orEmpty()
}

/**
 * Tus listas como se ven en Tus listas: las carpetas aún vacías delante (como lo recién creado) y después cada lista
 * suelta, o cada carpeta en el sitio de la primera de sus listas.
 */
private fun shelfOf(playlists: List<LocalPlaylist>, folders: Set<String>): List<Shelved> {
    val used = playlists.mapNotNullTo(HashSet()) { it.folder }
    return folders.filter { it !in used }.sorted().map { Shelved(it, emptyList()) } +
        playlists.groupBy { it.folder ?: "\u0000" + it.id }.values.map { g -> Shelved(g.first().folder, g) }
}

/** Lo oído hace menos, primero; lo nunca oído, detrás y en su orden. Una carpeta cuenta cuando suena ella o una suya. */
private fun byPlayed(entries: List<Shelved>, played: Map<String, Long>): List<Shelved> =
    entries.sortedByDescending { e -> maxOf(played[e.key] ?: 0L, e.lists.maxOfOrNull { played[it.id] ?: 0L } ?: 0L) }

/** Listas y carpetas en baldosas de dos en dos. [enabled] = false: recolocando la fila de arriba, se apagan. */
@Composable
private fun Tiles(entries: List<Shelved>, modifier: Modifier = Modifier, enabled: Boolean = true, onOpen: (Shelved) -> Unit, onMenu: (Shelved) -> Unit) {
    val dim by animateFloatAsState(if (enabled) 1f else 0.4f, tween(Motion.EFFECTS), label = "tilesDim")
    Column(modifier.padding(horizontal = Space.lg).graphicsLayer { alpha = dim }, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                pair.forEach { e -> Tile(e, Modifier.weight(1f), enabled, onClick = { onOpen(e) }, onLongClick = { onMenu(e) }) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Una lista o carpeta en pequeño: la portada a la izquierda, a sangre, y el nombre con lo que tiene. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(e: Shelved, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Oto.colors
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .height(TILE)
            .clip(RoundedCornerShape(Radius.md))
            .background(c.field)
            .combinedClickable(
                remember { MutableInteractionSource() }, ripple(color = c.text), enabled = enabled,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() }, onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (e.folder != null) FolderArt(e.lists, TILE, corner = 0.dp) else Mosaic(e.lists.first().songs, TILE, corner = 0.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(end = Space.sm)) {
            Text(e.name, style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (e.folder != null) folderCount(e.lists.size) else count(e.lists.first().songs.size),
                style = OtoType.rowSub.copy(fontSize = 12.sp, lineHeight = 16.sp), color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** «Todas tus listas», con cuántas hay. */
@Composable
private fun AllRow(n: Int, enabled: Boolean, onClick: () -> Unit) {
    val c = Oto.colors
    val dim by animateFloatAsState(if (enabled) 1f else 0.4f, tween(Motion.EFFECTS), label = "allDim")
    Row(
        Modifier
            .padding(start = Space.lg, end = Space.lg, top = Space.sm)
            .fillMaxWidth()
            .height(48.dp)
            .graphicsLayer { alpha = dim }
            .clip(CircleShape)
            .background(c.field)
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), enabled = enabled, onClick = onClick)
            .padding(start = Space.lg, end = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Todas tus listas", style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.08f), color = c.text)
        Spacer(Modifier.width(Space.sm))
        Text("$n", style = OtoType.rowSub, color = c.textMuted, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(22.dp), tint = c.textMuted)
    }
}

private enum class ListsFilter(val label: String) { Lists("Listas"), Folders("Carpetas"), Downloaded("Descargadas") }

/**
 * Todas tus listas y carpetas en baldosas: por cuándo sonaron, por nombre o en tu orden; y solo las listas, las
 * carpetas o lo descargado. Recolocando ([arranging]) cada una se arrastra a su sitio: ese es «Tu orden», el mismo de
 * Tu música.
 */
@Composable
private fun AllListsView(
    entries: List<Shelved>,
    played: Map<String, Long>,
    downloaded: Set<String>,
    arranging: Boolean,
    onBack: () -> Unit,
    onOpen: (Shelved) -> Unit,
    onMenu: (Shelved) -> Unit,
    onNew: () -> Unit,
    onDoneArranging: () -> Unit,
    onMove: (List<String>) -> Unit,
) {
    val c = Oto.colors
    val memory = MenuMemory.of(LocalContext.current)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val savedSort by memory.listsSort.collectAsStateWithLifecycle(initialValue = null)
    var filter by rememberSaveable { mutableStateOf<ListsFilter?>(null) }
    // Recolocar es cambiar «Tu orden»: al acabar se sigue viendo así, no vuelve a otro orden.
    LaunchedEffect(arranging) { if (arranging) memory.setListsSort(ListsSort.Custom) }
    val sort = if (arranging) ListsSort.Custom else savedSort ?: return
    val lists = remember(entries) { entries.flatMap { it.lists } }
    val shown = remember(entries, played, downloaded, sort, filter, arranging) {
        val base = when (if (arranging) null else filter) {
            null -> entries
            ListsFilter.Lists -> lists.map { Shelved(null, listOf(it)) }
            ListsFilter.Folders -> entries.filter { it.folder != null }
            ListsFilter.Downloaded -> lists.filter { it.id in downloaded }.map { Shelved(null, listOf(it)) }
        }
        when (sort) {
            ListsSort.Recent -> byPlayed(base, played)
            ListsSort.Name -> Collator.getInstance(Locale.forLanguageTag("es")).let { k -> k.strength = Collator.PRIMARY; base.sortedWith { a, b -> k.compare(a.name, b.name) } }
            ListsSort.Custom -> base
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = Space.sm, end = Space.md), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(R.drawable.ic_arrow_back, onClick = onBack)
            Column(Modifier.weight(1f)) {
                Text("Tus listas", style = OtoType.title, color = c.text)
                val folders = entries.count { it.folder != null }
                Text(
                    listOfNotNull(if (lists.size == 1) "1 lista" else "${lists.size} listas", folders.takeIf { it > 0 }?.let { if (it == 1) "1 carpeta" else "$it carpetas" }).joinToString(" · "),
                    style = OtoType.rowSub, color = c.textMuted,
                )
            }
            AnimatedContent(arranging, transitionSpec = { fadeIn(tween(Motion.EFFECTS)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) }, label = "listsAction") { a ->
                if (a) Pill("Listo", onDoneArranging, icon = R.drawable.ic_check) else Pill("Nueva", onNew, filled = false, icon = R.drawable.ic_add)
            }
        }
        AnimatedContent(arranging, transitionSpec = { fadeIn(tween(Motion.EFFECTS, delayMillis = 40)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) }, label = "listsBar") { a ->
            if (a) Text(
                "Arrastra cada una a su sitio. Este es «Tu orden», el mismo que en Tu música.",
                style = OtoType.rowSub, color = c.textMuted, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
            ) else LazyRow(
                contentPadding = PaddingValues(horizontal = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.xs),
                modifier = Modifier.padding(vertical = Space.sm),
            ) {
                item(key = "sort") {
                    SortChip(sort) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        scope.launch { memory.setListsSort(ListsSort.entries[(sort.ordinal + 1) % ListsSort.entries.size]) }
                    }
                }
                items(ListsFilter.entries, key = { it.name }) { f -> Chip(f.label, f == filter) { filter = if (filter == f) null else f } }
            }
        }
        if (shown.isEmpty()) when (filter) {
            ListsFilter.Folders -> Empty(R.drawable.ic_folder, "No tienes carpetas", "Junta varias listas en una con «Nueva» y «Carpeta».")
            ListsFilter.Downloaded -> Empty(R.drawable.ic_download_for_offline, "Nada descargado", "En una lista, toca ··· y elige «Descargar».")
            else -> Empty(R.drawable.ic_queue_music, "Aún no tienes listas", "Crea una con «Nueva», o tráete las tuyas de Spotify desde Ajustes.")
        } else ListsGrid(shown, arranging, onOpen, onMenu, onMove)
    }
}

/** El orden de «Todas tus listas»: tocarla pasa al siguiente. */
@Composable
private fun SortChip(sort: ListsSort, onNext: () -> Unit) {
    val c = Oto.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(c.field)
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onNext)
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_swap_vert), null, Modifier.size(18.dp), tint = c.text)
        Spacer(Modifier.width(Space.xs))
        AnimatedContent(sort, transitionSpec = { fadeIn(tween(Motion.EFFECTS)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) using SizeTransform(clip = false) }, label = "sortChip") { s ->
            Text(s.label, style = OtoType.rowTitle.copy(fontSize = OtoType.rowSub.fontSize * 1.05f), color = c.text, maxLines = 1)
        }
    }
}

/** Las baldosas de «Todas tus listas»; recolocando, cada una se arrastra (como la fila de arriba de Tu música). */
@Composable
private fun ListsGrid(entries: List<Shelved>, arranging: Boolean, onOpen: (Shelved) -> Unit, onMenu: (Shelved) -> Unit, onMove: (List<String>) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val state = rememberLazyGridState()
    // Un solo estado para siempre, como en ShelfRow: el gesto se queda con lo que veía al empezar.
    var order by remember { mutableStateOf(entries) }
    LaunchedEffect(entries) { order = entries }
    val saved by rememberUpdatedState(entries)
    val moved by rememberUpdatedState(onMove)
    val reorder = rememberReorderableLazyGridState(state) { from, to ->
        val a = order.indexOfFirst { it.key == from.key }
        val b = order.indexOfFirst { it.key == to.key }
        if (a < 0 || b < 0 || !order[b].movable) return@rememberReorderableLazyGridState
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        order = order.toMutableList().apply { add(b, removeAt(a)) }
    }
    LazyVerticalGrid(
        GridCells.Fixed(2), Modifier.fillMaxSize(), state = state,
        contentPadding = PaddingValues(start = Space.lg, end = Space.lg, top = Space.xs, bottom = Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        items(order, key = { it.key }) { e ->
            ReorderableItem(reorder, e.key) { dragging ->
                val movable = arranging && e.movable
                val wobble = if (movable && !dragging) {
                    val t = rememberInfiniteTransition(label = "tileWobble")
                    t.animateFloat(-0.8f, 0.8f, infiniteRepeatable(tween(150 + (e.key.hashCode() and 63), easing = LinearEasing), RepeatMode.Reverse), label = "tileWobble").value
                } else 0f
                Tile(
                    e,
                    Modifier
                        .graphicsLayer {
                            rotationZ = wobble
                            if (dragging) {
                                scaleX = 1.05f; scaleY = 1.05f
                                shadowElevation = 12.dp.toPx()
                                shape = RoundedCornerShape(Radius.md)
                                clip = true
                            }
                        }
                        .draggableHandle(
                            enabled = movable,
                            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragStopped = { if (order.map { it.key } != saved.map { it.key }) moved(order.flatMap { o -> o.lists.map { it.id } }) },
                        ),
                    enabled = !arranging,
                    onClick = { onOpen(e) },
                    onLongClick = { onMenu(e) },
                )
            }
        }
    }
}

/** Lo buscado hace poco, al tocar el buscador vacío: búsquedas y lo que abriste desde ellas, cada cosa con su ×. */
@Composable
private fun Recents(list: List<Recent>?, onPick: (Recent) -> Unit, onForget: (Recent) -> Unit) {
    val c = Oto.colors
    when {
        list == null -> Unit
        list.isEmpty() -> Empty(R.drawable.ic_search, "Busca lo que quieras", "Canciones, artistas, álbumes o listas. Lo que busques se queda aquí.")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.sm, end = Space.sm, bottom = Space.lg)) {
            item(key = "title") {
                Text("RECIENTES", style = OtoType.label, color = c.textMuted, modifier = Modifier.padding(start = Space.sm, top = Space.sm, bottom = Space.xs))
            }
            items(list, key = { it.key }) { r ->
                SongRow(
                    r.title,
                    when (r.kind) {
                        Recent.Kind.Query -> ""
                        Recent.Kind.Artist -> "Artista"
                        Recent.Kind.Album -> listOfNotNull("Álbum", r.sub).joinToString(" · ")
                        Recent.Kind.Playlist -> listOfNotNull("Lista", r.sub).joinToString(" · ")
                    },
                    r.art, false, onClick = { onPick(r) },
                    modifier = Modifier.animateItem(),
                    round = r.kind == Recent.Kind.Artist,
                    lead = if (r.kind != Recent.Kind.Query) null else ({
                        Box(Modifier.size(44.dp).clip(CircleShape).background(c.field), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_history), null, Modifier.size(22.dp), tint = c.textMuted)
                        }
                    }),
                    trailing = {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable(remember { MutableInteractionSource() }, ripple(color = c.text)) { onForget(r) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(R.drawable.ic_close), "Quitar", Modifier.size(20.dp), tint = c.textMuted) }
                    },
                )
            }
        }
    }
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
private fun FolderArt(lists: List<LocalPlaylist>, size: Dp, corner: Dp = if (size < 64.dp) Radius.sm else Radius.md) {
    val c = Oto.colors
    // Vacía, solo la carpeta, grande.
    if (lists.none { it.songs.isNotEmpty() }) {
        Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(c.field), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(size * 0.3f), tint = c.textFaint)
        }
        return
    }
    Box(Modifier.size(size)) {
        Mosaic(lists.mapNotNull { l -> l.songs.firstOrNull { it.artwork != null } }, size, corner)
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
    FloatHeader({ FolderArt(lists.filter { it.folder == folder }, 48.dp) }, folder, "Marca las listas que van dentro")
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
    FloatHeader({ Mosaic(list.songs, 48.dp) }, list.name, if (list.folder == null) "Elige en qué carpeta va" else "Ahora en «${list.folder}»")
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
private fun ColumnScope.FolderActions(
    folder: String,
    lists: List<LocalPlaylist>,
    pinned: Boolean,
    onPin: () -> Unit,
    onAdd: () -> Unit,
    onRename: () -> Unit,
    onArrange: (() -> Unit)?,
    onUngroup: () -> Unit,
) {
    FloatHeader({ FolderArt(lists, 48.dp) }, folder, folderCount(lists.size))
    ActionRow(R.drawable.ic_keep, if (pinned) "Quitar de arriba" else "Fijar arriba", onClick = onPin)
    onArrange?.let { ActionRow(R.drawable.ic_swap_horiz, "Mover", onClick = it) }
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
    FloatHeader({ Art(song.thumbnail, 48.dp, Radius.sm) }, song.title, song.artists.joinToString { it.name })
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
    /** Cuántas hay ya descargadas, si la lista lo está. */
    downloadState: Int?,
    onDownload: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onArrange: (() -> Unit)? = null,
) {
    // Borrar y quitar la descarga van al primer toque: el aviso de abajo deja deshacerlo.
    val sub = count(list.songs.size) + when {
        downloadState == null -> ""
        downloadState >= list.songs.size -> " · descargada"
        else -> " · bajando $downloadState de ${list.songs.size}"
    }
    FloatHeader({ if (list.id == LIKED_ID) LikedArt(48.dp) else Mosaic(list.songs, 48.dp) }, list.name, sub)
    if (list.songs.isNotEmpty() || downloaded) {
        if (!downloaded) ActionRow(R.drawable.ic_download, "Descargar", onClick = onDownload)
        else ActionRow(R.drawable.ic_download_done, "Quitar la descarga", onClick = onDownload)
    }
    // Me gusta no se fija, ni se renombra, ni se mueve ni se borra.
    if (list.id == LIKED_ID) {
        onArrange?.let { ActionRow(R.drawable.ic_swap_horiz, "Mover", onClick = it) }
        return
    }
    // Dentro de una carpeta, lo que se fija es la carpeta.
    if (list.folder == null) ActionRow(R.drawable.ic_keep, if (pinned) "Quitar de arriba" else "Fijar arriba", onClick = onPin)
    onArrange?.let { ActionRow(R.drawable.ic_swap_horiz, "Mover", onClick = it) }
    ActionRow(R.drawable.ic_edit, "Cambiar el nombre", onClick = onRename)
    ActionRow(R.drawable.ic_folder, if (list.folder == null) "Meter en una carpeta" else "Cambiar de carpeta", onClick = onMove)
    ActionRow(R.drawable.ic_delete, "Borrar la lista", onClick = onDelete)
}

@Composable
private fun ColumnScope.NameField(n: Naming, onDone: (String) -> Unit) {
    val c = Oto.colors
    var name by remember(n) { mutableStateOf(n.folder ?: n.rename?.name.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(n) { delay(120); focus.requestFocus() }
    val ok = name.isNotBlank()
    FloatHeader(
        {
            IconArt(
                when { n.rename != null || n.folder != null -> R.drawable.ic_edit; n.newFolder -> R.drawable.ic_create_new_folder; else -> R.drawable.ic_queue_music },
                48.dp,
            )
        },
        when { n.rename != null || n.folder != null -> "Cambiar el nombre"; n.newFolder -> "Nueva carpeta"; else -> "Nueva lista" },
        n.rename?.name ?: n.folder,
    )
    Spacer(Modifier.height(Space.sm))
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
private const val RECOMPUTE_MS = 1_500L
private const val SONGS_PER_COLUMN = 4
private const val TILES = 6
private val TILE = 56.dp
private const val NOTICE_MS = 1_800L
private const val UNDO_MS = 4_000L

/** El rojo suave de lo que pide un segundo toque. */
private val DANGER = Color(0xFFFF8A80)
