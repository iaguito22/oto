package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.QualityMode
import io.github.iaguito22.oto.account.Account
import io.github.iaguito22.oto.desktop.BrowserLogin
import io.github.iaguito22.oto.desktop.LoginState
import io.github.iaguito22.oto.account.Cloud
import io.github.iaguito22.oto.account.CloudState
import io.github.iaguito22.oto.account.Session
import io.github.iaguito22.oto.importer.ImportState
import io.github.iaguito22.oto.importer.Spotify
import io.github.iaguito22.oto.importer.Importer
import io.github.iaguito22.oto.playback.QueueEntry
import io.github.iaguito22.oto.toHeard
import io.github.iaguito22.oto.toSong
import io.github.iaguito22.oto.ui.menu.Filter
import io.github.iaguito22.oto.ui.menu.MenuController
import io.github.iaguito22.oto.ui.menu.Page
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private enum class MenuSubView { None, Settings, Queue }

/**
 * Cajón inferior (`Sheet` estilo prototipo 2 + `fusion.html`):
 * - Píldora inferior de asomo (`Tu música · Explorar · Buscar`) siempre accesible.
 * - Se abre y se cierra también con la rueda del ratón (`WheelEvent`) o con `Ctrl+K` / `Escape`.
 * - Comparte `MenuController`, `Prefs`, `Importer`, `Account` y `Cloud` de `:core`.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DesktopMenuSheet(
    open: Boolean,
    isIdle: Boolean = false,
    onToggleOpen: (Boolean) -> Unit,
    menu: MenuController,
    prefs: Prefs,
    importer: Importer,
    account: Account,
    cloud: Cloud,
    login: BrowserLogin,
    palette: ArtworkPalette,
    currentMediaId: String?,
    queue: List<QueueEntry>,
    shuffle: Boolean,
    loop: Boolean,
    onSetShuffle: (Boolean) -> Unit,
    onSetLoop: (Boolean) -> Unit,
    onMoveQueue: (from: Int, to: Int) -> Unit,
    onRemoveQueue: (index: Int) -> Unit,
    onClearQueue: () -> Unit = {},
    onPlaySong: (SongItem) -> Unit,
    onPlayAll: (List<SongItem>, Int?, YTItem?) -> Unit,
    onPlayLocal: (LocalPlaylist, Int?) -> Unit,
    onPlayDj: () -> Unit,
    onSkipToQueue: (Int) -> Unit,
    selectedTab: Int = 0,
    onSelectTab: (Int) -> Unit = {},
    onSaveTrack: (Heard) -> Unit = {},
) {
    val menuState by menu.state.collectAsState()
    val likedList by prefs.likedList.collectAsState(initial = LocalPlaylist(LIKED_ID, "Me gusta", emptyList()))
    val playlists by prefs.playlists.collectAsState(initial = emptyList())
    val history by prefs.history.collectAsState(initial = emptyList())
    val qualityMode by prefs.quality.collectAsState(initial = QualityMode.Auto)
    val dataSaver by prefs.dataSaver.collectAsState(initial = false)
    val importState by importer.state.collectAsState()
    val session by account.session.collectAsState()
    val cloudState by cloud.state.collectAsState()
    val scope = rememberCoroutineScope()

    var subView by remember { mutableStateOf(MenuSubView.None) }
    var showAllLists by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(open) {
        if (!open) {
            subView = MenuSubView.None
            showAllLists = false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Cajón desplegado desde abajo con el Fondo 4 · Lente de Agua (fondos-menu.html:265-282),
        // sin píldora inferior de asomo cuando está cerrado (se abre con rueda abajo o Ctrl+K).
        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + slideInVertically(spring(dampingRatio = 0.84f, stiffness = 320f)) { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.16f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggleOpen(false) }
                    .onPointerEvent(PointerEventType.Scroll) { ev ->
                        val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy < -0.5f) {
                            onToggleOpen(false)
                        }
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                val sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                Box(
                    modifier = Modifier
                        .widthIn(max = 1040.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .fillMaxHeight(0.84f)
                        .shadow(
                            elevation = 42.dp,
                            shape = sheetShape,
                            ambientColor = Color.Black.copy(alpha = 0.62f),
                            spotColor = Color.Black,
                        )
                        .clip(sheetShape)
                        .background(
                            Brush.verticalGradient(
                                0.00f to Color.White.copy(alpha = 0.14f),
                                0.28f to Color(0x82101218),
                                1.00f to Color(0x9E0C0D12),
                            ),
                        )
                        .border(
                            1.dp,
                            Brush.verticalGradient(
                                0.00f to Color.White.copy(alpha = 0.42f),
                                0.35f to Color.White.copy(alpha = 0.30f),
                                1.00f to palette.primary.copy(alpha = 0.24f),
                            ),
                            sheetShape,
                        )
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                ) {
                    // Refracción de Lente de Agua (Opción 4):
                    // - Brillo blanco cristalino en el labio superior (inset 0 2px 3px rgba(255,255,255,0.6))
                    // - Velo de agua ultratransparente arriba que deja pasar la luz del escenario desenfocado
                    // - Resplandor interior inferior con el color --accent (inset 0 -16px 40px var(--accent))
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        // Luz blanca suave en el tercio superior (efecto lente de agua)
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.00f to Color.White.copy(alpha = 0.09f),
                                0.22f to Color.Transparent,
                            ),
                        )
                        // Refracción cromática inferior (inset 0 -16px 40px color-mix(in srgb, var(--accent) 18%, transparent))
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(palette.primary.copy(alpha = 0.22f), Color.Transparent),
                                center = Offset(w * 0.50f, h * 1.02f),
                                radius = w * 0.68f,
                            ),
                        )
                        // Refracción sutil secundaria en la esquina superior izquierda
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(palette.primary.copy(alpha = 0.14f), Color.Transparent),
                                center = Offset(w * 0.24f, h * 0.06f),
                                radius = w * 0.50f,
                            ),
                        )
                        // Labio especular blanco de 2.5px en el borde superior (inset 0 2px 3px rgba(255, 255, 255, 0.6))
                        drawLine(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.18f),
                                    Color.White.copy(alpha = 0.62f),
                                    Color.White.copy(alpha = 0.62f),
                                    Color.White.copy(alpha = 0.18f),
                                ),
                            ),
                            start = Offset(w * 0.03f, 1.5f),
                            end = Offset(w * 0.97f, 1.5f),
                            strokeWidth = 2.5f,
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Tirador superior (.sheet-grab en core.css:712-725)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .onPointerEvent(PointerEventType.Scroll) { ev ->
                                    val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    if (dy < -0.5f) {
                                        onToggleOpen(false)
                                    }
                                }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onToggleOpen(false) }
                                .padding(top = 10.dp, bottom = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(width = 42.dp, height = 4.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.24f)),
                            )
                        }

                        // Cabecera (.sheet-top-bar en core.css:728-853):
                        // Píldora segmentada [Tu música | Explorar] siempre visible + Buscador central + Iconos circulares
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            val topPage = menuState.pages.lastOrNull()
                            val isDeepNav = topPage != null || subView != MenuSubView.None || menuState.query.isNotBlank() || showAllLists

                            // Selector segmentado [ Tu música | Explorar ]: permanece en su sitio como en core.js:972
                            Box(
                                modifier = Modifier.graphicsLayer {
                                    alpha = if (isDeepNav) 0.58f else 1f
                                },
                            ) {
                                DesktopSegmentedTabs(
                                    selectedPage = selectedTab,
                                    accent = palette.primary,
                                    onSelect = { idx ->
                                        onSelectTab(idx)
                                        subView = MenuSubView.None
                                        showAllLists = false
                                        if (menuState.query.isNotBlank()) menu.onQuery("")
                                        while (menu.state.value.pages.isNotEmpty()) {
                                            menu.closeDetail()
                                        }
                                    },
                                )
                            }

                            // Botón circular Atrás cuando estamos dentro de una lista, búsqueda o subvista
                            if (isDeepNav) {
                                SheetCircleIconButton(
                                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                                    tooltip = "Volver",
                                    active = false,
                                    accent = palette.primary,
                                    onClick = {
                                        when {
                                            topPage != null -> menu.closeDetail()
                                            showAllLists -> showAllLists = false
                                            menuState.query.isNotBlank() -> menu.onQuery("")
                                            else -> subView = MenuSubView.None
                                        }
                                    },
                                )
                            }

                            // Buscador central en píldora (.sheet-search en core.css:782-826)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (menuState.query.isNotBlank()) Color.White.copy(alpha = 0.09f)
                                        else Color.White.copy(alpha = 0.06f),
                                    )
                                    .border(
                                        1.dp,
                                        if (menuState.query.isNotBlank()) palette.primary.copy(alpha = 0.48f)
                                        else Color.White.copy(alpha = 0.09f),
                                        CircleShape,
                                    )
                                    .clickable { searchFocusRequester.requestFocus() }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.56f),
                                    modifier = Modifier.size(16.dp),
                                )
                                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                    if (menuState.query.isEmpty()) {
                                        Text(
                                            text = "Canciones, artistas, listas…",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White.copy(alpha = 0.42f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    BasicTextField(
                                        value = menuState.query,
                                        onValueChange = {
                                            subView = MenuSubView.None
                                            showAllLists = false
                                            menu.onQuery(it)
                                        },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        cursorBrush = SolidColor(palette.primary),
                                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                                    )
                                }
                                if (menuState.query.isNotEmpty()) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Limpiar búsqueda",
                                        tint = Color.White.copy(alpha = 0.65f),
                                        modifier = Modifier
                                            .size(16.dp)
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .clickable { menu.onQuery("") },
                                    )
                                }
                            }

                            // Botones circulares de acción (.sheet-icon-btn en core.css:828-852)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SheetCircleIconButton(
                                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                    tooltip = "Cola de reproducción",
                                    active = subView == MenuSubView.Queue,
                                    accent = palette.primary,
                                    badge = if (queue.isNotEmpty()) "${queue.size}" else null,
                                    onClick = {
                                        subView = if (subView == MenuSubView.Queue) MenuSubView.None else MenuSubView.Queue
                                    },
                                )
                                SheetCircleIconButton(
                                    icon = Icons.Rounded.Settings,
                                    tooltip = "Ajustes y cuenta",
                                    active = subView == MenuSubView.Settings,
                                    accent = palette.primary,
                                    onClick = {
                                        subView = if (subView == MenuSubView.Settings) MenuSubView.None else MenuSubView.Settings
                                    },
                                )
                                SheetCircleIconButton(
                                    icon = Icons.Rounded.Close,
                                    tooltip = "Cerrar (Esc)",
                                    active = false,
                                    accent = palette.primary,
                                    onClick = { onToggleOpen(false) },
                                )
                            }
                        }

                        // Cuerpo desplazable (.sheet-scroll en core.css:856-879)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 28.dp),
                        ) {
                            val topPage = menuState.pages.lastOrNull()
                            when {
                                topPage is Page.Local -> {
                                    val list = if (topPage.id == LIKED_ID) likedList else playlists.firstOrNull { it.id == topPage.id }
                                    if (list != null) {
                                        LocalPlaylistDetailPane(
                                            list = list,
                                            palette = palette,
                                            currentMediaId = currentMediaId,
                                            onBack = menu::closeDetail,
                                            onPlayIndex = { idx -> onPlayLocal(list, idx); onToggleOpen(false) },
                                            onSaveTo = onSaveTrack,
                                            onDeletePlaylist = if (list.id != LIKED_ID) {
                                                {
                                                    scope.launch {
                                                        prefs.deletePlaylist(list.id)
                                                        menu.closeDetail()
                                                    }
                                                }
                                            } else null,
                                            onRemoveSong = if (list.id != LIKED_ID) {
                                                { songId ->
                                                    scope.launch {
                                                        prefs.removeFromPlaylist(list.id, songId)
                                                    }
                                                }
                                            } else null,
                                        )
                                    }
                                }
                                topPage is Page.Smart -> {
                                    LocalPlaylistDetailPane(
                                        list = topPage.list,
                                        palette = palette,
                                        currentMediaId = currentMediaId,
                                        onBack = menu::closeDetail,
                                        onPlayIndex = { idx -> onPlayLocal(topPage.list, idx); onToggleOpen(false) },
                                        onSaveTo = onSaveTrack,
                                        onDeletePlaylist = null,
                                        onRemoveSong = null,
                                    )
                                }
                                topPage is Page.Remote -> {
                                    RemoteDetailPane(
                                        page = topPage,
                                        palette = palette,
                                        currentMediaId = currentMediaId,
                                        onBack = menu::closeDetail,
                                        onPlayAll = { songs, idx -> onPlayAll(songs, idx, topPage.item); onToggleOpen(false) },
                                        onOpenItem = menu::open,
                                        onSaveTo = onSaveTrack,
                                    )
                                }
                                menuState.query.isNotBlank() -> {
                                    SearchResultsPane(
                                        query = menuState.query,
                                        searching = menuState.searching,
                                        filter = menuState.filter,
                                        results = menuState.results,
                                        error = menuState.searchError,
                                        palette = palette,
                                        currentMediaId = currentMediaId,
                                        onFilter = menu::onFilter,
                                        onPlaySong = { onPlaySong(it); onToggleOpen(false) },
                                        onOpenItem = menu::open,
                                        onSaveTo = onSaveTrack,
                                    )
                                }
                                subView == MenuSubView.Settings -> {
                                    SettingsAndAccountPane(
                                        prefs = prefs,
                                        importer = importer,
                                        account = account,
                                        cloud = cloud,
                                        login = login,
                                        session = session,
                                        cloudState = cloudState,
                                        importState = importState,
                                        qualityMode = qualityMode,
                                        dataSaver = dataSaver,
                                        palette = palette,
                                    )
                                }
                                subView == MenuSubView.Queue -> {
                                    QueuePane(
                                        queue = queue,
                                        currentMediaId = currentMediaId,
                                        shuffle = shuffle,
                                        loop = loop,
                                        palette = palette,
                                        onToggleShuffle = { onSetShuffle(!shuffle) },
                                        onToggleLoop = { onSetLoop(!loop) },
                                        onMove = onMoveQueue,
                                        onRemove = onRemoveQueue,
                                        onClearQueue = onClearQueue,
                                        onSkipTo = { idx -> onSkipToQueue(idx); onToggleOpen(false) },
                                    )
                                }
                                showAllLists && selectedTab == 0 -> {
                                    AllListsSubPane(
                                        likedList = likedList,
                                        playlists = playlists,
                                        palette = palette,
                                        onBack = { showAllLists = false },
                                        onOpenLocal = { id ->
                                            showAllLists = false
                                            menu.openLocal(id)
                                        },
                                        onPlayLocal = { list ->
                                            onPlayLocal(list, null)
                                            onToggleOpen(false)
                                        },
                                        onCreatePlaylist = { name ->
                                            scope.launch {
                                                val newId = prefs.createPlaylist(name)
                                                showAllLists = false
                                                menu.openLocal(newId)
                                            }
                                        },
                                    )
                                }
                                selectedTab == 0 -> {
                                    YourMusicPane(
                                        likedList = likedList,
                                        playlists = playlists,
                                        history = history,
                                        palette = palette,
                                        currentMediaId = currentMediaId,
                                        onOpenLocal = menu::openLocal,
                                        onOpenSmart = menu::openSmart,
                                        onOpenAllLists = { showAllLists = true },
                                        onPlayLocal = { list, idx -> onPlayLocal(list, idx); onToggleOpen(false) },
                                        onPlayDj = { onPlayDj(); onToggleOpen(false) },
                                        onPlayHeard = { h -> onPlaySong(h.toSong()); onToggleOpen(false) },
                                        onSaveTo = onSaveTrack,
                                        onCreatePlaylist = { name ->
                                            scope.launch {
                                                val newId = prefs.createPlaylist(name)
                                                menu.openLocal(newId)
                                            }
                                        },
                                    )
                                }
                                else -> {
                                    ExplorePane(
                                        shelves = menuState.shelves,
                                        chips = menuState.chips,
                                        selectedChip = menuState.chip,
                                        error = menuState.homeError,
                                        palette = palette,
                                        currentMediaId = currentMediaId,
                                        onChip = menu::onChip,
                                        onRetry = menu::loadHome,
                                        onPlaySong = { onPlaySong(it); onToggleOpen(false) },
                                        onOpenItem = menu::open,
                                        onSaveTo = onSaveTrack,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SheetCircleIconButton(
    icon: ImageVector,
    tooltip: String,
    active: Boolean,
    accent: Color,
    badge: String? = null,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .height(42.dp)
            .widthIn(min = 42.dp)
            .clip(CircleShape)
            .background(
                when {
                    active -> accent.copy(alpha = 0.20f)
                    hovered -> Color.White.copy(alpha = 0.11f)
                    else -> Color.White.copy(alpha = 0.06f)
                },
            )
            .border(
                1.dp,
                when {
                    active -> accent.copy(alpha = 0.50f)
                    hovered -> Color.White.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.09f)
                },
                CircleShape,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(onClick = onClick)
            .padding(horizontal = if (badge != null) 12.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = tooltip,
                tint = if (active) accent else if (hovered) Color.White else Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(18.dp),
            )
            if (badge != null) {
                Text(
                    text = badge,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (active) accent else Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MenuTabPill(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(
                when {
                    selected -> accent
                    hovered -> Color.White.copy(alpha = 0.11f)
                    else -> Color.White.copy(alpha = 0.06f)
                },
            )
            .border(
                1.dp,
                when {
                    selected -> accent
                    hovered -> Color.White.copy(alpha = 0.15f)
                    else -> Color.White.copy(alpha = 0.09f)
                },
                CircleShape,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) Color(0xFF101214) else if (hovered) Color.White else Color.White.copy(alpha = 0.72f),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DesktopSegmentedTabs(
    selectedPage: Int,
    accent: Color,
    onSelect: (Int) -> Unit,
) {
    // Idéntico a .oto-tabs en core.css:738-780
    Row(
        modifier = Modifier
            .width(236.dp)
            .height(42.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), CircleShape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("Tu música", "Explorar").forEachIndexed { idx, title ->
            val isSelected = selectedPage == idx
            var isHovered by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(
                        when {
                            isSelected -> Color.White.copy(alpha = 0.12f)
                            isHovered -> Color.White.copy(alpha = 0.05f)
                            else -> Color.Transparent
                        },
                    )
                    .border(
                        1.dp,
                        if (isSelected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
                        CircleShape,
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(idx) }
                    .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { isHovered = false },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) Color(0xFFF3EEFF) else if (isHovered) Color.White else Color.White.copy(alpha = 0.66f),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun YourMusicPane(
    likedList: LocalPlaylist,
    playlists: List<LocalPlaylist>,
    history: List<Heard>,
    palette: ArtworkPalette,
    currentMediaId: String?,
    onOpenLocal: (String) -> Unit,
    onOpenSmart: (LocalPlaylist) -> Unit,
    onOpenAllLists: () -> Unit,
    onPlayLocal: (LocalPlaylist, Int?) -> Unit,
    onPlayDj: () -> Unit,
    onPlayHeard: (Heard) -> Unit,
    onSaveTo: (Heard) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    val createFocusRequester = remember { FocusRequester() }

    LaunchedEffect(showCreateDialog) {
        if (showCreateDialog) {
            kotlinx.coroutines.delay(100L)
            createFocusRequester.requestFocus()
        }
    }

    // Si el usuario tiene menos de 2 listas creadas, completamos los 2 huecos del estante fijado de 4
    // con mezclas inteligentes derivadas de su historial/Me gusta (para mantener siempre la rejilla de 4 de core.js:823).
    val displayLists = remember(playlists, history, likedList) {
        if (playlists.size >= 2) {
            playlists
        } else {
            val pool = (history + likedList.songs).distinctBy { it.id }
            val fallback1 = LocalPlaylist(
                id = "__smart_recent__",
                name = "En bucle",
                songs = pool.take(12),
            )
            val fallback2 = LocalPlaylist(
                id = "__smart_night__",
                name = "Nocturna",
                songs = pool.drop(2).take(12).ifEmpty { pool.take(8) },
            )
            (playlists + listOf(fallback1, fallback2)).take(maxOf(playlists.size, 2))
        }
    }
    val pinnedTwo = displayLists.take(2)
    val tileLists = playlists.drop(2).take(6)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
    ) {
        // 1. Cabecera "Tus listas" + botón píldora "+ Nueva" (idéntico a core.js:816-820)
        item(key = "sectionHeadLists") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Tus listas",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.4).sp,
                    color = Color(0xFFF3EEFF),
                )
                var newHovered by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(CircleShape)
                        .background(if (newHovered) Color.White.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .onPointerEvent(PointerEventType.Enter) { newHovered = true }
                        .onPointerEvent(PointerEventType.Exit) { newHovered = false }
                        .clickable { showCreateDialog = !showCreateDialog }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.Add, null, tint = Color(0xFFF3EEFF), modifier = Modifier.size(15.dp))
                    Text("Nueva", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF3EEFF))
                }
            }
        }

        // Creación inline de lista cuando se pulsa "+ Nueva"
        if (showCreateDialog) {
            item(key = "createPlaylistInline") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .border(1.dp, palette.primary.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicTextField(
                        value = newListName,
                        onValueChange = { newListName = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(palette.primary),
                        decorationBox = { innerTextField ->
                            if (newListName.isBlank()) {
                                Text("Nombre de la nueva lista…", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
                            }
                            innerTextField()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(createFocusRequester)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && newListName.isNotBlank()) {
                                    onCreatePlaylist(newListName.trim())
                                    newListName = ""
                                    showCreateDialog = false
                                    true
                                } else false
                            },
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(palette.primary)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable {
                                if (newListName.isNotBlank()) {
                                    onCreatePlaylist(newListName.trim())
                                    newListName = ""
                                    showCreateDialog = false
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text("Crear", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF101214))
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { showCreateDialog = false; newListName = "" }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Cancelar", fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.70f))
                    }
                }
            }
        }

        // 2. Estantería fijada de 4 tarjetas cuadradas limpias (.pinned-shelf en core.css:914-971):
        // DJ + Me gusta + 2 listas fijadas, en una fila de 4 columnas iguales sin caja gris exterior.
        item(key = "pinnedShelf4") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    DjShelfCard(
                        accent = palette.primary,
                        onClick = onPlayDj,
                    )
                }
                Box(Modifier.weight(1f)) {
                    MeGustaShelfCard(
                        count = likedList.songs.size,
                        accent = palette.primary,
                        accentDark = palette.secondary,
                        onClick = { onOpenLocal(LIKED_ID) },
                        onPlayDirect = { onPlayLocal(likedList, null) },
                    )
                }
                pinnedTwo.forEach { pl ->
                    Box(Modifier.weight(1f)) {
                        PlaylistShelfCard(
                            playlist = pl,
                            accent = palette.primary,
                            onClick = {
                                if (pl.id.startsWith("__smart_")) onOpenSmart(pl)
                                else onOpenLocal(pl.id)
                            },
                            onPlayDirect = { onPlayLocal(pl, null) },
                        )
                    }
                }
                repeat((2 - pinnedTwo.size).coerceAtLeast(0)) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        // 3. Cuadrícula de baldosas horizontales compactas (.tiles-grid en core.css:973-1024)
        if (tileLists.isNotEmpty()) {
            item(key = "tilesGrid") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tileLists.chunked(3).forEach { rowLists ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowLists.forEach { pl ->
                                Box(Modifier.weight(1f)) {
                                    PlaylistTileDesktop(
                                        playlist = pl,
                                        accent = palette.primary,
                                        onClick = { onOpenLocal(pl.id) },
                                        onPlayDirect = { onPlayLocal(pl, null) },
                                    )
                                }
                            }
                            repeat((3 - rowLists.size).coerceAtLeast(0)) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // 4. Botón píldora de ancho completo "Todas tus listas  N  >" (.all-lists-row en core.css:1027-1052)
        item(key = "allListsRow") {
            var allHovered by remember { mutableStateOf(false) }
            val totalCount = playlists.size + 1 // +1 por Me gusta
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (allHovered) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.045f))
                    .border(
                        1.dp,
                        if (allHovered) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.07f),
                        RoundedCornerShape(14.dp),
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .onPointerEvent(PointerEventType.Enter) { allHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { allHovered = false }
                    .clickable(onClick = onOpenAllLists)
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Todas tus listas",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF3EEFF),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "$totalCount",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.62f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // 5. Cabecera "Oído hace poco" + Rejilla de 2 columnas sin bordes (core.js:856-860)
        item(key = "historyHeader") {
            Text(
                text = "Oído hace poco",
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
                color = Color(0xFFF3EEFF),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (history.isEmpty()) {
            item(key = "historyEmpty") {
                Text(
                    text = "Aún no hay canciones recientes. Busca arriba o explora música para empezar a escuchar.",
                    fontSize = 13.5.sp,
                    color = Color.White.copy(alpha = 0.50f),
                )
            }
        } else {
            val rowsOfTwo = history.take(16).chunked(2)
            itemsIndexed(rowsOfTwo, key = { idx, pair -> "hist-$idx-${pair.first().id}" }) { _, pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pair.forEach { h ->
                        Box(Modifier.weight(1f)) {
                            SongRowDesktop(
                                title = h.title,
                                subtitle = h.artist,
                                thumbnail = h.artwork,
                                isCurrent = h.id == currentMediaId,
                                accent = palette.primary,
                                onClick = { onPlayHeard(h) },
                                onSaveTo = { onSaveTo(h) },
                            )
                        }
                    }
                    if (pair.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AllListsSubPane(
    likedList: LocalPlaylist,
    playlists: List<LocalPlaylist>,
    palette: ArtworkPalette,
    onBack: () -> Unit,
    onOpenLocal: (String) -> Unit,
    onPlayLocal: (LocalPlaylist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    var filterIdx by remember { mutableIntStateOf(0) }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetCircleIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                tooltip = "Volver",
                active = false,
                accent = palette.primary,
                onClick = onBack,
            )
            Text(
                text = "Todas tus listas",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFF3EEFF),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("↑↓ Tu orden", "Listas", "Favoritas").forEachIndexed { idx, label ->
                MenuTabPill(
                    label = label,
                    selected = filterIdx == idx,
                    accent = palette.primary,
                    onClick = { filterIdx = idx },
                )
            }
        }
        val allItems = remember(likedList, playlists, filterIdx) {
            val base = listOf(likedList) + playlists
            when (filterIdx) {
                1 -> playlists
                2 -> listOf(likedList)
                else -> base
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            val pairs = allItems.chunked(2)
            itemsIndexed(pairs) { _, pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { pl ->
                        Box(Modifier.weight(1f)) {
                            PlaylistTileDesktop(
                                playlist = pl,
                                accent = palette.primary,
                                onClick = { onOpenLocal(pl.id) },
                                onPlayDirect = { onPlayLocal(pl) },
                            )
                        }
                    }
                    if (pair.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DjShelfCard(
    accent: Color,
    onClick: () -> Unit,
) {
    // Idéntico a .dj-vinyl-box en core.css:932-960: cuadrado limpio sin marco envolvente
    var spinning by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    var angle by remember { mutableFloatStateOf(-35f) }
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                angle = (angle + (now - last) / 1e9f * 360f / 3.8f) % 360f
                last = now
            }
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "djScale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .onPointerEvent(PointerEventType.Enter) { spinning = true; isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { spinning = false; isHovered = false },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(18.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = 0.55f))
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF15161B))
                .border(1.dp, if (isHovered) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(14.dp)) {
                val r = size.minDimension * 0.48f
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(Color.Black.copy(alpha = 0.55f), r * 1.03f, center + Offset(0f, r * 0.04f))
                drawCircle(Color(0xFF0E0E12), r, center)
                // Surcos concéntricos del vinilo
                var g = 0.38f
                while (g < 0.96f) {
                    drawCircle(Color.White.copy(alpha = 0.045f), r * g, center, style = Stroke(width = r * 0.011f))
                    g += 0.048f
                }
                // Brillo especular sobre el vinilo
                for (start in listOf(-65f, 115f)) {
                    drawArc(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.11f), Color.Transparent), center, r),
                        start, 36f, useCenter = true, topLeft = center - Offset(r, r), size = Size(r * 2, r * 2),
                    )
                }
                // Galleta central en color --accent
                drawCircle(accent, r * 0.30f, center)
                rotate(angle, center) {
                    drawCircle(Color.White.copy(alpha = 0.85f), r * 0.04f, center + Offset(r * 0.19f, 0f))
                }
                drawCircle(Color(0xFF101214), r * 0.065f, center)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "DJ",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF3EEFF),
            maxLines = 1,
        )
        Text(
            text = "Pon música con un toque",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.58f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MeGustaShelfCard(
    count: Int,
    accent: Color,
    accentDark: Color,
    onClick: () -> Unit,
    onPlayDirect: () -> Unit,
) {
    // Idéntico a .liked-heart-box en core.css:962-971: degradado vivo de acento + corazón oscuro central (#101214)
    var isHovered by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "likedScale",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(18.dp, RoundedCornerShape(16.dp), ambientColor = accent.copy(alpha = 0.35f))
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            accent,
                            accentDark.copy(alpha = 0.92f),
                        ),
                        start = Offset.Zero,
                        end = Offset(400f, 400f),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = Color(0xFF101214).copy(alpha = 0.90f),
                modifier = Modifier.size(64.dp),
            )
            if (isHovered) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .size(40.dp)
                        .shadow(10.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color(0xFF101214))
                        .clickable(onClick = onPlayDirect),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Reproducir Me gusta",
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Me gusta",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF3EEFF),
            maxLines = 1,
        )
        Text(
            text = "$count canciones",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.58f),
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlaylistShelfCard(
    playlist: LocalPlaylist,
    accent: Color,
    onClick: () -> Unit,
    onPlayDirect: () -> Unit,
) {
    // Idéntico a .shelf-card en core.css:919-930: portada cuadrada limpia con sombra y texto debajo
    var isHovered by remember { mutableStateOf(false) }
    val firstArt = playlist.songs.firstOrNull { !it.artwork.isNullOrBlank() }?.artwork
    var coverBmp by remember(firstArt) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(firstArt) {
        if (!firstArt.isNullOrBlank()) {
            coverBmp = ArtworkLoader.load(firstArt).first
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "plScale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(18.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = 0.55f))
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF221C32),
                            Color(0xFF151220),
                        ),
                    ),
                )
                .border(1.dp, if (isHovered) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (coverBmp != null) {
                Image(
                    bitmap = coverBmp!!,
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.75f),
                    modifier = Modifier.size(48.dp),
                )
            }
            if (isHovered) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .size(40.dp)
                        .shadow(10.dp, CircleShape)
                        .clip(CircleShape)
                        .background(accent)
                        .clickable(onClick = onPlayDirect),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Reproducir",
                        tint = Color(0xFF101214),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF3EEFF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${playlist.songs.size} canciones",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.58f),
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlaylistTileDesktop(
    playlist: LocalPlaylist,
    accent: Color,
    onClick: () -> Unit,
    onPlayDirect: () -> Unit,
) {
    // Idéntico a .tile-card en core.css:979-1024
    var isHovered by remember { mutableStateOf(false) }
    val firstArt = playlist.songs.firstOrNull { !it.artwork.isNullOrBlank() }?.artwork
    var coverBmp by remember(firstArt) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(firstArt) {
        if (!firstArt.isNullOrBlank()) {
            coverBmp = ArtworkLoader.load(firstArt).first
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isHovered) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.045f))
            .border(
                1.dp,
                if (isHovered) Color.White.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(14.dp),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (playlist.id == LIKED_ID) {
                        Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.65f)))
                    } else {
                        Brush.linearGradient(listOf(Color(0xFF231D33), Color(0xFF161221)))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                playlist.id == LIKED_ID -> {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = Color(0xFF101214),
                        modifier = Modifier.size(26.dp),
                    )
                }
                coverBmp != null -> {
                    Image(
                        bitmap = coverBmp!!,
                        contentDescription = playlist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Rounded.LibraryMusic,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF3EEFF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Lista · ${playlist.songs.size} canciones",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.56f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isHovered) {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .clickable(onClick = onPlayDirect),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Reproducir lista",
                    tint = Color(0xFF101214),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LocalPlaylistDetailPane(
    list: LocalPlaylist,
    palette: ArtworkPalette,
    currentMediaId: String?,
    onBack: () -> Unit = {},
    onPlayIndex: (Int?) -> Unit,
    onSaveTo: (Heard) -> Unit = {},
    onDeletePlaylist: (() -> Unit)? = null,
    onRemoveSong: ((String) -> Unit)? = null,
) {
    // Idéntico a openPlaylistInSheet en core.js:608-643: cabecera con portada 116dp + botón píldora ▶ Reproducir + botón circular Aleatorio
    val firstArt = list.songs.firstOrNull { !it.artwork.isNullOrBlank() }?.artwork
    var coverBmp by remember(firstArt) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(firstArt) {
        if (!firstArt.isNullOrBlank()) {
            coverBmp = ArtworkLoader.load(firstArt).first
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(116.dp)
                    .shadow(24.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.6f))
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (list.id == LIKED_ID) {
                            Brush.linearGradient(listOf(palette.primary, palette.secondary))
                        } else {
                            Brush.linearGradient(listOf(Color(0xFF241E34), Color(0xFF151220)))
                        },
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    list.id == LIKED_ID -> {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = Color(0xFF101214),
                            modifier = Modifier.size(54.dp),
                        )
                    }
                    coverBmp != null -> {
                        Image(
                            bitmap = coverBmp!!,
                            contentDescription = list.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.LibraryMusic,
                            contentDescription = null,
                            tint = palette.primary,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = list.name,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    color = Color(0xFFF3EEFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Lista · ${list.songs.size} canciones",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.60f),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(CircleShape)
                            .background(palette.primary)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onPlayIndex(0) }
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF101214),
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Reproducir",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF101214),
                        )
                    }

                    SheetCircleIconButton(
                        icon = Icons.Rounded.Shuffle,
                        tooltip = "Aleatorio",
                        active = false,
                        accent = palette.primary,
                        onClick = { onPlayIndex(null) },
                    )

                    if (onDeletePlaylist != null) {
                        SheetCircleIconButton(
                            icon = Icons.Rounded.Delete,
                            tooltip = "Eliminar lista",
                            active = false,
                            accent = Color(0xFFFF6E6E),
                            onClick = onDeletePlaylist,
                        )
                    }
                }
            }
        }

        if (list.songs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Esta lista está vacía.", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                itemsIndexed(list.songs, key = { idx, s -> "${s.id}-$idx" }) { idx, s ->
                    SongRowDesktop(
                        title = s.title,
                        subtitle = s.artist,
                        thumbnail = s.artwork,
                        isCurrent = s.id == currentMediaId,
                        accent = palette.primary,
                        onClick = { onPlayIndex(idx) },
                        onSaveTo = { onSaveTo(s) },
                        trailing = if (onRemoveSong != null) {
                            {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.06f))
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable { onRemoveSong(s.id) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Quitar",
                                        tint = Color.White.copy(alpha = 0.55f),
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        } else null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RemoteDetailPane(
    page: Page.Remote,
    palette: ArtworkPalette,
    currentMediaId: String?,
    onBack: () -> Unit = {},
    onPlayAll: (List<SongItem>, Int?) -> Unit,
    onOpenItem: (YTItem) -> Unit,
    onSaveTo: (Heard) -> Unit = {},
) {
    val songs = page.songs
    val shelves = page.shelves
    var coverBmp by remember(page.item.thumbnail) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(page.item.thumbnail) {
        if (!page.item.thumbnail.isNullOrBlank()) {
            coverBmp = ArtworkLoader.load(page.item.thumbnail).first
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(116.dp)
                    .shadow(24.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.6f))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1B1626))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (coverBmp != null) {
                    Image(
                        bitmap = coverBmp!!,
                        contentDescription = page.item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        tint = palette.primary,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = page.item.title,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    color = Color(0xFFF3EEFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                page.sub?.let {
                    Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.60f))
                }
                if (!songs.isNullOrEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .height(44.dp)
                                .clip(CircleShape)
                                .background(palette.primary)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { onPlayAll(songs, 0) }
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color(0xFF101214),
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Reproducir",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF101214),
                            )
                        }
                        SheetCircleIconButton(
                            icon = Icons.Rounded.Shuffle,
                            tooltip = "Aleatorio",
                            active = false,
                            accent = palette.primary,
                            onClick = { onPlayAll(songs.shuffled(), 0) },
                        )
                    }
                }
            }
        }

        when {
            page.error != null -> Text("Error: ${page.error}", color = palette.secondary, fontSize = 14.sp)
            songs == null && shelves.isEmpty() -> Text("Cargando pistas de YouTube Music…", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                if (!songs.isNullOrEmpty()) {
                    itemsIndexed(songs, key = { i, s -> "${s.id}-$i" }) { idx, s ->
                        SongRowDesktop(
                            title = s.title,
                            subtitle = s.artists.joinToString { it.name },
                            thumbnail = s.thumbnail,
                            isCurrent = s.id == currentMediaId,
                            accent = palette.primary,
                            onClick = { onPlayAll(songs, idx) },
                            onSaveTo = { onSaveTo(s.toHeard()) },
                        )
                    }
                }
                if (shelves.isNotEmpty()) {
                    items(shelves, key = { it.title }) { shelf ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(shelf.title, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFF3EEFF))
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(shelf.items, key = { it.id }) { item ->
                                    ExploreShelfCard(
                                        item = item,
                                        accent = palette.primary,
                                        onClick = {
                                            if (item is SongItem) onPlayAll(listOf(item), 0)
                                            else onOpenItem(item)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplorePane(
    shelves: List<io.github.iaguito22.oto.ui.menu.Shelf>?,
    chips: List<com.metrolist.innertube.pages.HomePage.Chip>,
    selectedChip: String?,
    error: String?,
    palette: ArtworkPalette,
    currentMediaId: String?,
    onChip: (String) -> Unit,
    onRetry: () -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onOpenItem: (YTItem) -> Unit,
    onSaveTo: (Heard) -> Unit = {},
) {
    // Idéntico a renderExploreTab en core.js:865-895 y .quick-picks-grid en core.css:1134-1147
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (chips.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chips, key = { it.title }) { chip ->
                    MenuTabPill(chip.title, selectedChip == chip.title, palette.primary) {
                        onChip(chip.title)
                    }
                }
            }
        }
        when {
            error != null -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No se pudo cargar Explorar: $error", color = palette.secondary, fontSize = 13.sp)
                    MenuTabPill("Reintentar", true, palette.primary, onRetry)
                }
            }
            shelves == null -> {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(2) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.size(width = 160.dp, height = 20.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.08f)),
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(4) {
                                    Box(
                                        Modifier.size(180.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Color.White.copy(alpha = 0.05f)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(26.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp),
            ) {
                items(shelves, key = { it.title }) { shelf ->
                    val allSongs = shelf.items.all { it is SongItem }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = shelf.title,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.4).sp,
                            color = Color(0xFFF3EEFF),
                        )
                        if (allSongs && shelf.items.size > 3) {
                            // Columnas de 4 canciones envueltas en tarjeta sutil (.quick-picks-col en core.css:1139-1147)
                            val cols = shelf.items.filterIsInstance<SongItem>().chunked(4)
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(cols) { colSongs ->
                                    Column(
                                        modifier = Modifier
                                            .width(420.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(Color.White.copy(alpha = 0.03f))
                                            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        colSongs.forEach { song ->
                                            SongRowDesktop(
                                                title = song.title,
                                                subtitle = song.artists.joinToString { it.name },
                                                thumbnail = song.thumbnail,
                                                isCurrent = song.id == currentMediaId,
                                                accent = palette.primary,
                                                onClick = { onPlaySong(song) },
                                                onSaveTo = { onSaveTo(song.toHeard()) },
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Estantería de álbumes/listas con portadas cuadradas limpias
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(shelf.items, key = { it.id }) { item ->
                                    ExploreShelfCard(
                                        item = item,
                                        accent = palette.primary,
                                        onClick = {
                                            if (item is SongItem) onPlaySong(item)
                                            else onOpenItem(item)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExploreShelfCard(
    item: YTItem,
    accent: Color,
    onClick: () -> Unit,
) {
    // Idéntico a buildCoverCardHTML en core.js:750-758: portada cuadrada limpia con sombra y botón play flotante
    var thumbBmp by remember(item.thumbnail) { mutableStateOf<ImageBitmap?>(null) }
    var isHovered by remember { mutableStateOf(false) }

    LaunchedEffect(item.thumbnail) {
        if (!item.thumbnail.isNullOrBlank()) {
            thumbBmp = ArtworkLoader.load(item.thumbnail).first
        }
    }

    val isArtist = item is ArtistItem
    val shape = if (isArtist) CircleShape else RoundedCornerShape(16.dp)
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "expScale",
    )

    Column(
        modifier = Modifier
            .width(184.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false },
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .size(184.dp)
                .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.55f))
                .clip(shape)
                .background(Color(0xFF181522))
                .border(
                    width = 1.dp,
                    color = if (isHovered) accent.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.08f),
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbBmp != null) {
                Image(
                    bitmap = thumbBmp!!,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (isArtist) Icons.Rounded.Person else Icons.Rounded.Album,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.40f),
                    modifier = Modifier.size(48.dp),
                )
            }

            if (isHovered) {
                Box(
                    modifier = Modifier
                        .align(if (isArtist) Alignment.Center else Alignment.BottomEnd)
                        .padding(if (isArtist) 0.dp else 10.dp)
                        .size(40.dp)
                        .shadow(12.dp, CircleShape)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Reproducir",
                        tint = Color(0xFF101214),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = item.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF3EEFF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )

        val sub = when (item) {
            is SongItem -> item.artists.joinToString { it.name }
            is AlbumItem -> listOfNotNull("Álbum", item.year?.toString()).joinToString(" · ")
            is PlaylistItem -> item.author?.name ?: "Lista"
            is ArtistItem -> "Artista"
            else -> ""
        }
        if (sub.isNotBlank()) {
            Text(
                text = sub,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.58f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchResultsPane(
    query: String,
    searching: Boolean,
    filter: Filter,
    results: List<YTItem>,
    error: String?,
    palette: ArtworkPalette,
    currentMediaId: String?,
    onFilter: (Filter) -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onOpenItem: (YTItem) -> Unit,
    onSaveTo: (Heard) -> Unit = {},
) {
    // Idéntico a renderSearchView en core.js:897-945 y .top-result-card en core.css:1150-1204
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Filter.entries.forEach { f ->
                MenuTabPill(f.label, filter == f, palette.primary) { onFilter(f) }
            }
        }
        when {
            error != null -> Text("La búsqueda falló: $error", color = palette.secondary, fontSize = 13.sp)
            searching && results.isEmpty() -> Text("Buscando «$query» en YouTube Music…", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            results.isEmpty() -> Text("Sin resultados para «$query».", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            else -> {
                val topItem = results.first()
                val restItems = results.drop(1)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    item(key = "topResultHeader") {
                        Text(
                            text = "Resultado principal",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFF3EEFF),
                        )
                    }
                    item(key = "topResultCard-${topItem.id}") {
                        TopResultCard(
                            item = topItem,
                            accent = palette.primary,
                            onClick = { if (topItem is SongItem) onPlaySong(topItem) else onOpenItem(topItem) },
                        )
                    }
                    if (restItems.isNotEmpty()) {
                        item(key = "moreResultsHeader") {
                            Text(
                                text = "Canciones y más",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFF3EEFF),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        val pairs = restItems.chunked(2)
                        itemsIndexed(pairs, key = { idx, p -> "sr-$idx-${p.first().id}" }) { _, pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                pair.forEach { item ->
                                    val sub = when (item) {
                                        is SongItem -> "Canción · " + item.artists.joinToString { it.name }
                                        is AlbumItem -> "Álbum · " + item.artists?.joinToString { it.name }.orEmpty()
                                        is ArtistItem -> "Artista"
                                        is PlaylistItem -> "Lista · " + (item.author?.name ?: "")
                                        else -> ""
                                    }
                                    Box(Modifier.weight(1f)) {
                                        SongRowDesktop(
                                            title = item.title,
                                            subtitle = sub,
                                            thumbnail = item.thumbnail,
                                            isCurrent = item.id == currentMediaId,
                                            accent = palette.primary,
                                            onClick = { if (item is SongItem) onPlaySong(item) else onOpenItem(item) },
                                            onSaveTo = if (item is SongItem) { { onSaveTo(item.toHeard()) } } else null,
                                        )
                                    }
                                }
                                if (pair.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopResultCard(
    item: YTItem,
    accent: Color,
    onClick: () -> Unit,
) {
    // Idéntico a .top-result-card en core.css:1150-1204
    var thumbBmp by remember(item.thumbnail) { mutableStateOf<ImageBitmap?>(null) }
    var isHovered by remember { mutableStateOf(false) }
    LaunchedEffect(item.thumbnail) {
        if (!item.thumbnail.isNullOrBlank()) {
            thumbBmp = ArtworkLoader.load(item.thumbnail).first
        }
    }
    val sub = when (item) {
        is SongItem -> "Canción · " + item.artists.joinToString { it.name }
        is AlbumItem -> "Álbum · " + item.artists?.joinToString { it.name }.orEmpty()
        is ArtistItem -> "Artista · YouTube Music"
        is PlaylistItem -> "Lista · " + (item.author?.name ?: "YouTube Music")
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (isHovered) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.05f))
            .border(
                1.dp,
                if (isHovered) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.09f),
                RoundedCornerShape(22.dp),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .shadow(16.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1726)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbBmp != null) {
                Image(
                    bitmap = thumbBmp!!,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = item.title,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                color = Color(0xFFF3EEFF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sub,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(50.dp)
                .shadow(16.dp, CircleShape, ambientColor = accent, spotColor = accent)
                .clip(CircleShape)
                .background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Reproducir",
                tint = Color(0xFF101214),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun QueuePane(
    queue: List<QueueEntry>,
    currentMediaId: String?,
    shuffle: Boolean,
    loop: Boolean,
    palette: ArtworkPalette,
    onToggleShuffle: () -> Unit,
    onToggleLoop: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onClearQueue: () -> Unit = {},
    onSkipTo: (Int) -> Unit,
) {
    if (queue.isEmpty()) {
        Text("La cola está vacía.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Cola de reproducción", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${queue.size} canciones en total", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (queue.size > 1) {
                    MenuTabPill(
                        label = "Vaciar cola",
                        selected = false,
                        accent = Color(0xFFFF5252),
                        onClick = onClearQueue,
                    )
                }
                MenuTabPill(
                    label = if (shuffle) "Aleatorio: Sí" else "Aleatorio: No",
                    selected = shuffle,
                    accent = palette.primary,
                    onClick = onToggleShuffle,
                )
                MenuTabPill(
                    label = if (loop) "Repetir: Sí" else "Repetir: No",
                    selected = loop,
                    accent = palette.secondary,
                    onClick = onToggleLoop,
                )
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(queue, key = { idx, e -> "${e.id}-$idx" }) { idx, entry ->
                QueueRowDesktop(
                    index = idx + 1,
                    title = entry.title,
                    subtitle = entry.artist,
                    artwork = entry.artwork,
                    isCurrent = entry.id == currentMediaId,
                    accent = palette.primary,
                    canMoveUp = idx > 0,
                    canMoveDown = idx < queue.lastIndex,
                    onClick = { onSkipTo(idx) },
                    onMoveUp = { onMove(idx, idx - 1) },
                    onMoveDown = { onMove(idx, idx + 1) },
                    onRemove = { onRemove(idx) },
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun QueueRowDesktop(
    index: Int,
    title: String,
    subtitle: String,
    artwork: String? = null,
    isCurrent: Boolean,
    accent: Color,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var thumbBmp by remember(artwork) { mutableStateOf<ImageBitmap?>(null) }
    var isHovered by remember { mutableStateOf(false) }
    LaunchedEffect(artwork) {
        if (!artwork.isNullOrBlank()) {
            thumbBmp = ArtworkLoader.load(artwork).first
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isCurrent) Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0.14f)),
                )
                else if (isHovered) Brush.horizontalGradient(
                    listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.05f)),
                )
                else SolidColor(Color.White.copy(alpha = 0.03f)),
            )
            .border(
                1.dp,
                if (isCurrent) accent.copy(alpha = 0.50f)
                else if (isHovered) Color.White.copy(alpha = 0.20f)
                else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isCurrent) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = "Sonando",
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                text = "$index",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.width(22.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbBmp != null) {
                Image(
                    bitmap = thumbBmp!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.40f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isCurrent) accent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.56f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canMoveUp) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onMoveUp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.KeyboardArrowUp, "Subir", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                }
            } else {
                Spacer(Modifier.size(28.dp))
            }
            if (canMoveDown) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onMoveDown),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Bajar", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                }
            } else {
                Spacer(Modifier.size(28.dp))
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, "Quitar", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsAndAccountPane(
    prefs: Prefs,
    importer: Importer,
    account: Account,
    cloud: Cloud,
    login: BrowserLogin,
    session: Session?,
    cloudState: CloudState,
    importState: ImportState,
    qualityMode: QualityMode,
    dataSaver: Boolean,
    palette: ArtworkPalette,
) {
    val scope = rememberCoroutineScope()
    val crossfade by prefs.crossfade.collectAsState(initial = 3)
    var importError by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, palette.primary.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.CloudDone, null, tint = palette.primary)
                    Text("Cuenta de YouTube Music", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                if (session != null) {
                    Text("Conectado como ${session.name.ifBlank { session.email ?: "tu cuenta" }}", fontSize = 13.sp, color = palette.primary)
                    Text(
                        when (cloudState) {
                            CloudState.Off -> "Copia en la nube: apagada"
                            is CloudState.Working -> "Sincronizando: ${cloudState.what}"
                            is CloudState.Done -> "Sincronizado: ${cloudState.lists} listas"
                            is CloudState.Failed -> "Error de copia: ${cloudState.why}"
                        },
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                    MenuTabPill("Cerrar sesión", false, palette.secondary) {
                        scope.launch { account.signOut(); cloud.forget() }
                    }
                } else {
                    val loginState by login.state.collectAsState()
                    Text(
                        "Se abre music.youtube.com en tu navegador y la sesión llega sola a Oto (solo a 127.0.0.1).",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        MenuTabPill("Iniciar sesión", true, palette.primary) { login.start() }
                        MenuTabPill("Instalar extensión (una vez)", false, palette.secondary) { login.extractExtension() }
                    }
                    when (val ls = loginState) {
                        LoginState.Waiting -> Text("Esperando al navegador… inicia sesión en la pestaña que se ha abierto.", fontSize = 12.sp, color = palette.primary)
                        is LoginState.Failed -> Text(ls.why, fontSize = 12.sp, color = palette.secondary)
                        LoginState.Done, LoginState.Idle -> Unit
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, palette.secondary.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.FileUpload, null, tint = palette.secondary)
                    Text("Importar biblioteca (oto-spotify.json)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MenuTabPill("Elegir archivo JSON…", true, palette.secondary) {
                        val fd = FileDialog(null as Frame?, "Seleccionar oto-spotify.json", FileDialog.LOAD)
                        fd.isVisible = true
                        val file = fd.file?.let { File(fd.directory, it) }
                        if (file != null && file.exists()) {
                            runCatching { Spotify.parseExport(file.readText()) }
                                .onSuccess { importError = null; importer.start(it) }
                                .onFailure { importError = it.message }
                        }
                    }
                    importError?.let { Text("Error: $it", fontSize = 12.sp, color = palette.secondary) }
                    when (val st = importState) {
                        is ImportState.Running -> Text("Importando ${st.done}/${st.total} (${st.list})…", fontSize = 12.sp, color = palette.primary)
                        is ImportState.Done -> Text("Importadas ${st.songs} canciones en ${st.lists} listas.", fontSize = 12.sp, color = palette.primary)
                        ImportState.Idle -> Unit
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Audio y calidad", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuTabPill("Auto", qualityMode == QualityMode.Auto, palette.primary) {
                        scope.launch { prefs.setQuality(QualityMode.Auto) }
                    }
                    MenuTabPill("Máxima", qualityMode == QualityMode.Max, palette.primary) {
                        scope.launch { prefs.setQuality(QualityMode.Max) }
                    }
                    MenuTabPill(if (dataSaver) "Ahorro de datos: sí" else "Ahorro de datos: no", dataSaver, palette.tertiary) {
                        scope.launch { prefs.setDataSaver(!dataSaver) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Fundido entre canciones (crossfade)", fontSize = 13.sp, color = Color.White.copy(alpha = 0.70f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Sin fundido", 3 to "3s", 6 to "6s", 10 to "10s").forEach { (secs, label) ->
                        MenuTabPill(label, crossfade == secs, palette.secondary) {
                            scope.launch { prefs.setCrossfade(secs) }
                        }
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Tune, null, tint = palette.primary)
                    Text("Atajos de teclado en el escritorio", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                val shortcuts = listOf(
                    "Espacio" to "Reproducir / Pausar",
                    "N  /  Ctrl+→" to "Canción siguiente",
                    "P  /  Ctrl+←" to "Canción anterior",
                    "←  /  →" to "Retroceder / Avanzar ±5s",
                    "↑  /  ↓  /  Rueda" to "Volumen ±4% (en carátula)",
                    "M" to "Silenciar / Restaurar",
                    "L" to "Guardar en Me gusta",
                    "B" to "Guardar canción en lista",
                    "Ctrl+K  /  Ctrl+F" to "Abrir / Cerrar menú",
                    "F11" to "Pantalla completa",
                    "Tab" to "Alternar Tu música / Explorar",
                    "Escape" to "Cerrar modal o cajón",
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shortcuts.forEach { (key, desc) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = key,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = palette.primary,
                            )
                            Text(
                                text = desc,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.70f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SongRowDesktop(
    title: String,
    subtitle: String,
    thumbnail: String? = null,
    isCurrent: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onSaveTo: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var thumbBmp by remember(thumbnail) { mutableStateOf<ImageBitmap?>(null) }
    var isHovered by remember { mutableStateOf(false) }
    LaunchedEffect(thumbnail) {
        if (!thumbnail.isNullOrBlank()) {
            thumbBmp = ArtworkLoader.load(thumbnail).first
        }
    }

    // Idéntico a .song-row en core.css:1054-1131: sin caja ni borde en reposo; barra lateral 3dp en color acento cuando suena
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    isCurrent -> accent.copy(alpha = 0.13f)
                    isHovered -> Color.White.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Barra vertical izquierda de 3.dp cuando está sonando (box-shadow: inset 3px 0 0 var(--accent))
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF191622)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbBmp != null) {
                    Image(
                        bitmap = thumbBmp!!,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (isCurrent || isHovered) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.48f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isCurrent && !isHovered) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                            contentDescription = "Reproducir",
                            tint = if (isCurrent) accent else Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrent) accent else Color(0xFFF3EEFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.5.sp,
                        color = Color.White.copy(alpha = 0.58f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            }
            if (onSaveTo != null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isHovered) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable(onClick = onSaveTo),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Guardar en lista",
                        tint = if (isHovered) Color.White else Color.White.copy(alpha = 0.36f),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SaveToModal(
    track: Heard,
    playlists: List<LocalPlaylist>,
    likedList: LocalPlaylist,
    accent: Color,
    onToggleLike: (Heard) -> Unit,
    onTogglePlaylist: (playlistId: String, Heard) -> Unit,
    onCreateAndAdd: (name: String, Heard) -> Unit,
    onDismiss: () -> Unit,
) {
    var thumbBmp by remember(track.artwork) { mutableStateOf<ImageBitmap?>(null) }
    var creatingNew by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    val newFocusRequester = remember { FocusRequester() }

    LaunchedEffect(track.artwork) {
        if (!track.artwork.isNullOrBlank()) {
            thumbBmp = ArtworkLoader.load(track.artwork).first
        }
    }

    LaunchedEffect(creatingNew) {
        if (creatingNew) {
            kotlinx.coroutines.delay(100L)
            newFocusRequester.requestFocus()
        }
    }

    val isLiked = remember(likedList, track.id) {
        likedList.songs.any { it.id == track.id }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .shadow(32.dp, RoundedCornerShape(24.dp), ambientColor = accent.copy(alpha = 0.35f), spotColor = Color.Black)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFA1F1A30),
                            Color(0xF5130F21),
                            Color(0xFA0B0816),
                        ),
                    ),
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.50f),
                            accent.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.10f),
                        ),
                    ),
                    RoundedCornerShape(24.dp),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Cabecera: Carátula + Datos + Botón cerrar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumbBmp != null) {
                        Image(
                            bitmap = thumbBmp!!,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Rounded.MusicNote, null, tint = accent, modifier = Modifier.size(24.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        fontSize = 12.5.sp,
                        color = Color.White.copy(alpha = 0.60f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, "Cerrar", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }

            // Separador
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.10f)),
            )

            Text(
                text = "Guardar en tus listas",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.50f),
            )

            // Lista de destinos
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 1. Me gusta
                item {
                    SaveToDestinationRow(
                        title = "Me gusta",
                        subtitle = "${likedList.songs.size} canciones",
                        icon = Icons.Rounded.Favorite,
                        iconTint = if (isLiked) accent else Color.White.copy(alpha = 0.50f),
                        checked = isLiked,
                        accent = accent,
                        onClick = { onToggleLike(track) },
                    )
                }

                // 2. Playlists locales
                items(playlists, key = { it.id }) { pl ->
                    val inThis = pl.songs.any { it.id == track.id }
                    SaveToDestinationRow(
                        title = pl.name,
                        subtitle = "${pl.songs.size} canciones",
                        icon = Icons.Rounded.LibraryMusic,
                        iconTint = if (inThis) accent else Color.White.copy(alpha = 0.50f),
                        checked = inThis,
                        accent = accent,
                        onClick = { onTogglePlaylist(pl.id, track) },
                    )
                }
            }

            // Fila de creación inline
            if (!creatingNew) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .clickable { creatingNew = true }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.Add, null, tint = accent, modifier = Modifier.size(18.dp))
                    Text("Nueva lista y añadir aquí…", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, accent.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicTextField(
                        value = newListName,
                        onValueChange = { newListName = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(accent),
                        decorationBox = { inner ->
                            if (newListName.isBlank()) {
                                Text("Nombre de la lista…", color = Color.White.copy(alpha = 0.40f), fontSize = 13.sp)
                            }
                            inner()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(newFocusRequester)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && newListName.isNotBlank()) {
                                    onCreateAndAdd(newListName.trim(), track)
                                    newListName = ""
                                    creatingNew = false
                                    true
                                } else false
                            },
                    )
                    Text(
                        text = "Añadir",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (newListName.isNotBlank()) accent else Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.clickable {
                            if (newListName.isNotBlank()) {
                                onCreateAndAdd(newListName.trim(), track)
                                newListName = ""
                                creatingNew = false
                            }
                        },
                    )
                    Text(
                        text = "Cancelar",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.50f),
                        modifier = Modifier.clickable {
                            creatingNew = false
                            newListName = ""
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SaveToDestinationRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    checked: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    var isHovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) accent.copy(alpha = 0.18f) else if (isHovered) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f))
            .border(1.dp, if (checked) accent.copy(alpha = 0.45f) else if (isHovered) Color.White.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (checked) accent else Color.White)
            Text(text = subtitle, fontSize = 11.sp, color = Color.White.copy(alpha = 0.50f))
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) accent else Color.White.copy(alpha = 0.10f))
                .border(1.dp, if (checked) accent else Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(Icons.Rounded.Check, null, tint = Color(0xFF140E20), modifier = Modifier.size(14.dp))
            }
        }
    }
}
