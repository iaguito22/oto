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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
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
    onPlaySong: (SongItem) -> Unit,
    onPlayAll: (List<SongItem>, Int?, YTItem?) -> Unit,
    onPlayLocal: (LocalPlaylist, Int?) -> Unit,
    onPlayDj: () -> Unit,
    onSkipToQueue: (Int) -> Unit,
    selectedTab: Int = 0,
    onSelectTab: (Int) -> Unit = {},
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
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(open) {
        if (!open) {
            subView = MenuSubView.None
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Píldora inferior liquid glass cuando está cerrado
        AnimatedVisibility(
            visible = !open,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.padding(bottom = 18.dp),
        ) {
            Row(
                modifier = Modifier
                    .shadow(24.dp, CircleShape, ambientColor = palette.primary.copy(alpha = 0.4f), spotColor = Color.Black)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.16f),
                                Color(0xC0171324),
                            ),
                        ),
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.50f),
                                palette.primary.copy(alpha = 0.35f),
                            ),
                        ),
                        CircleShape,
                    )
                    .clickable { onToggleOpen(true) }
                    .onPointerEvent(PointerEventType.Scroll) { ev ->
                        val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy > 0.5f) {
                            onToggleOpen(true)
                        }
                    }
                    .padding(horizontal = 22.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "Abrir menú",
                    tint = palette.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Tu música · Explorar",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.92f),
                )
                Text(
                    text = "Scroll ↓ o Ctrl+K",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.primary.copy(alpha = 0.85f),
                )
            }
        }

        // Fondo oscurecido y cajón desplegado desde abajo con estilo Liquid Glass
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.50f))
                    .clickable { onToggleOpen(false) }
                    .onPointerEvent(PointerEventType.Scroll) { ev ->
                        val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy < -0.5f) {
                            onToggleOpen(false)
                        }
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 1180.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f)
                        .shadow(
                            elevation = 40.dp,
                            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                            ambientColor = palette.primary.copy(alpha = 0.35f),
                            spotColor = Color.Black,
                        )
                        .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xC41B172E),
                                    Color(0xD6110E20),
                                    Color(0xF00A0814),
                                ),
                            ),
                        )
                        .border(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.45f),
                                    palette.primary.copy(alpha = 0.38f),
                                    Color.White.copy(alpha = 0.08f),
                                ),
                            ),
                            RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                        )
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(horizontal = 28.dp, vertical = 18.dp),
                ) {
                    // Tirador superior
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggleOpen(false) }
                            .onPointerEvent(PointerEventType.Scroll) { ev ->
                                val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (dy < -0.5f) {
                                    onToggleOpen(false)
                                }
                            }
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(44.dp, 4.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.28f)),
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Cabecera estilo móvil / prototipo: Selector Segmentado de 2 pestañas (o Botón Atrás + Título)
                    // + Buscador YouTube Music + Botón Cola + Botón Ajustes + Botón Cerrar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val topPage = menuState.pages.lastOrNull()
                        val isDeepNav = topPage != null || subView != MenuSubView.None || menuState.query.isNotBlank()

                        if (isDeepNav) {
                            // Sub-navegación: Botón Atrás + Título
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .clickable {
                                            when {
                                                menuState.query.isNotBlank() -> menu.onQuery("")
                                                topPage != null -> menu.closeDetail()
                                                else -> subView = MenuSubView.None
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.ArrowBack, "Atrás", tint = Color.White, modifier = Modifier.size(19.dp))
                                }
                                Text(
                                    text = when {
                                        menuState.query.isNotBlank() -> "Resultados de búsqueda"
                                        topPage is Page.Local -> if (topPage.id == LIKED_ID) "Me gusta" else (playlists.firstOrNull { it.id == topPage.id }?.name ?: "Lista")
                                        topPage is Page.Remote -> topPage.item.title
                                        subView == MenuSubView.Settings -> "Ajustes y Cuenta"
                                        subView == MenuSubView.Queue -> "Cola de reproducción"
                                        else -> ""
                                    },
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }
                        } else {
                            // Selector segmentado de 2 pestañas: [ Tu música | Explorar ]
                            DesktopSegmentedTabs(
                                selectedPage = selectedTab,
                                accent = palette.primary,
                                onSelect = onSelectTab,
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        // Buscador de YouTube Music
                        Row(
                            modifier = Modifier
                                .width(320.dp)
                                .height(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(1.dp, palette.primary.copy(alpha = 0.22f), CircleShape)
                                .clickable { searchFocusRequester.requestFocus() }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Search, null, tint = palette.primary, modifier = Modifier.size(18.dp))
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (menuState.query.isEmpty()) {
                                    Text(
                                        "Buscar canciones, álbumes, artistas…",
                                        fontSize = 13.sp,
                                        color = Color.White.copy(alpha = 0.42f),
                                    )
                                }
                                BasicTextField(
                                    value = menuState.query,
                                    onValueChange = { menu.onQuery(it) },
                                    singleLine = true,
                                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                    cursorBrush = SolidColor(palette.primary),
                                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                                )
                            }
                            if (menuState.query.isNotEmpty()) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Limpiar",
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp).clickable { menu.onQuery("") },
                                )
                            }
                        }

                        // Botón Cola de reproducción
                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .clip(CircleShape)
                                .background(if (subView == MenuSubView.Queue) palette.primary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
                                .border(1.dp, if (subView == MenuSubView.Queue) palette.primary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f), CircleShape)
                                .clickable {
                                    subView = if (subView == MenuSubView.Queue) MenuSubView.None else MenuSubView.Queue
                                }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Rounded.QueueMusic, "Cola", tint = if (subView == MenuSubView.Queue) palette.primary else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(17.dp))
                                if (queue.isNotEmpty()) {
                                    Text(
                                        text = "${queue.size}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (subView == MenuSubView.Queue) palette.primary else Color.White.copy(alpha = 0.85f),
                                    )
                                }
                            }
                        }

                        // Botón Ajustes
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (subView == MenuSubView.Settings) palette.primary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
                                .border(1.dp, if (subView == MenuSubView.Settings) palette.primary.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f), CircleShape)
                                .clickable {
                                    subView = if (subView == MenuSubView.Settings) MenuSubView.None else MenuSubView.Settings
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Settings, "Ajustes", tint = if (subView == MenuSubView.Settings) palette.primary else Color.White, modifier = Modifier.size(18.dp))
                        }

                        // Botón Cerrar
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { onToggleOpen(false) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Close, "Cerrar", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Cuerpo dinámico del cajón
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val topPage = menuState.pages.lastOrNull()
                        when {
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
                                )
                            }
                            topPage is Page.Local -> {
                                val list = if (topPage.id == LIKED_ID) likedList else playlists.firstOrNull { it.id == topPage.id }
                                if (list != null) {
                                    LocalPlaylistDetailPane(
                                        list = list,
                                        palette = palette,
                                        currentMediaId = currentMediaId,
                                        onPlayIndex = { idx -> onPlayLocal(list, idx); onToggleOpen(false) },
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
                                    onPlayIndex = { idx -> onPlayLocal(topPage.list, idx); onToggleOpen(false) },
                                    onDeletePlaylist = null,
                                    onRemoveSong = null,
                                )
                            }
                            topPage is Page.Remote -> {
                                RemoteDetailPane(
                                    page = topPage,
                                    palette = palette,
                                    currentMediaId = currentMediaId,
                                    onPlayAll = { songs, idx -> onPlayAll(songs, idx, topPage.item); onToggleOpen(false) },
                                    onOpenItem = menu::open,
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
                                    onSkipTo = { idx -> onSkipToQueue(idx); onToggleOpen(false) },
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
                                    onPlayLocal = { list, idx -> onPlayLocal(list, idx); onToggleOpen(false) },
                                    onPlayDj = { onPlayDj(); onToggleOpen(false) },
                                    onPlayHeard = { h -> onPlaySong(h.toSong()); onToggleOpen(false) },
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
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuTabPill(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (selected) accent.copy(alpha = 0.45f) else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else Color.White.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun DesktopSegmentedTabs(
    selectedPage: Int,
    accent: Color,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(114.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(if (selectedPage == 0) accent.copy(alpha = 0.25f) else Color.Transparent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onSelect(0) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Tu música",
                fontSize = 13.5.sp,
                fontWeight = if (selectedPage == 0) FontWeight.Bold else FontWeight.Medium,
                color = if (selectedPage == 0) Color.White else Color.White.copy(alpha = 0.65f),
            )
        }
        Box(
            modifier = Modifier
                .width(114.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(if (selectedPage == 1) accent.copy(alpha = 0.25f) else Color.Transparent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onSelect(1) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Explorar",
                fontSize = 13.5.sp,
                fontWeight = if (selectedPage == 1) FontWeight.Bold else FontWeight.Medium,
                color = if (selectedPage == 1) Color.White else Color.White.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
private fun YourMusicPane(
    likedList: LocalPlaylist,
    playlists: List<LocalPlaylist>,
    history: List<Heard>,
    palette: ArtworkPalette,
    currentMediaId: String?,
    onOpenLocal: (String) -> Unit,
    onPlayLocal: (LocalPlaylist, Int?) -> Unit,
    onPlayDj: () -> Unit,
    onPlayHeard: (Heard) -> Unit,
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // 1. Estante fijado (Pinned Shelf): DJ + Me gusta + Listas fijadas + Nueva lista
        item(key = "pinnedShelf") {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // DJ de Oto con vinilo interactivo giratorio
                item(key = "djCard") {
                    DjShelfCard(
                        accent = palette.secondary,
                        onClick = onPlayDj,
                    )
                }

                // Me gusta con gradiente y corazón brillante
                item(key = LIKED_ID) {
                    MeGustaShelfCard(
                        count = likedList.songs.size,
                        accent = palette.primary,
                        onClick = { onOpenLocal(LIKED_ID) },
                        onPlayDirect = { onPlayLocal(likedList, null) },
                    )
                }

                // Listas fijadas / primeras listas
                items(playlists.take(6), key = { it.id }) { pl ->
                    PlaylistShelfCard(
                        playlist = pl,
                        accent = palette.tertiary,
                        onClick = { onOpenLocal(pl.id) },
                        onPlayDirect = { onPlayLocal(pl, null) },
                    )
                }

                // Tarjeta rápida para crear nueva lista
                item(key = "newPlaylistCard") {
                    NewPlaylistShelfCard(
                        accent = palette.primary,
                        onClick = { showCreateDialog = true },
                    )
                }
            }
        }

        // Fila de creación rápida de lista si está activada
        if (showCreateDialog) {
            item(key = "createPlaylistInline") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.08f))
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
                                if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                                    ev.key == androidx.compose.ui.input.key.Key.Enter &&
                                    newListName.isNotBlank()
                                ) {
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
                            .clickable {
                                if (newListName.isNotBlank()) {
                                    onCreatePlaylist(newListName.trim())
                                    newListName = ""
                                    showCreateDialog = false
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text("Crear", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF130E1F))
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { showCreateDialog = false; newListName = "" }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Cancelar", fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.70f))
                    }
                }
            }
        }

        // 2. Baldosas de 2 columnas para tus listas
        item(key = "playlistsGridHeader") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Tus listas",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { showCreateDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.Add, null, tint = palette.primary, modifier = Modifier.size(16.dp))
                    Text("Nueva lista", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
        if (playlists.isNotEmpty()) {
            item(key = "playlistsGrid") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pairs = playlists.chunked(2)
                    pairs.forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            pair.forEach { pl ->
                                Box(modifier = Modifier.weight(1f)) {
                                    PlaylistTileDesktop(
                                        playlist = pl,
                                        accent = palette.primary,
                                        onClick = { onOpenLocal(pl.id) },
                                        onPlayDirect = { onPlayLocal(pl, null) },
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

        // 3. Oído hace poco
        item(key = "historyHeader") {
            Text(
                text = "Oído hace poco",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
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
            items(history, key = { it.id }) { h ->
                SongRowDesktop(
                    title = h.title,
                    subtitle = h.artist,
                    thumbnail = h.artwork,
                    isCurrent = h.id == currentMediaId,
                    accent = palette.primary,
                    onClick = { onPlayHeard(h) },
                )
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
    var spinning by remember { mutableStateOf(false) }
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

    Column(
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { spinning = true }
            .onPointerEvent(PointerEventType.Exit) { spinning = false }
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(124.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF261D38), Color(0xFF130E1F)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(116.dp)) {
                val r = size.minDimension * 0.44f
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(Color.Black.copy(alpha = 0.45f), r * 1.02f, center + Offset(0f, r * 0.03f))
                drawCircle(Color(0xFF121214), r, center)
                // Surcos del vinilo
                var g = 0.46f
                while (g < 0.96f) {
                    drawCircle(Color.White.copy(alpha = 0.05f), r * g, center, style = Stroke(width = r * 0.009f))
                    g += 0.045f * (1.2f - g)
                }
                // Brillo de luz sobre el disco
                for (start in listOf(-70f, 110f)) {
                    drawArc(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent), center, r),
                        start, 34f, useCenter = true, topLeft = center - Offset(r, r), size = Size(r * 2, r * 2),
                    )
                }
                // Galleta central
                drawCircle(accent, r * 0.36f, center)
                rotate(angle, center) {
                    drawCircle(Color.White.copy(alpha = 0.90f), r * 0.05f, center + Offset(r * 0.22f, 0f))
                    drawArc(
                        Color.White.copy(alpha = 0.40f), -40f, 80f, useCenter = false,
                        topLeft = center - Offset(r * 0.28f, r * 0.28f), size = Size(r * 0.56f, r * 0.56f),
                        style = Stroke(width = r * 0.02f),
                    )
                }
                drawCircle(Color(0xFF121214), r * 0.04f, center)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "DJ de Oto",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
        )
        Text(
            text = "Mezcla para ti",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
        )
    }
}

@Composable
private fun MeGustaShelfCard(
    count: Int,
    accent: Color,
    onClick: () -> Unit,
    onPlayDirect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.28f), Color(0x33120E1F)),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.38f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(124.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.35f), Color(0x22120E1F)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(46.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Me gusta",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
        )
        Text(
            text = "$count canciones",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
        )
    }
}

@Composable
private fun PlaylistShelfCard(
    playlist: LocalPlaylist,
    accent: Color,
    onClick: () -> Unit,
    onPlayDirect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(124.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.QueueMusic,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(42.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${playlist.songs.size} canciones",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
        )
    }
}

@Composable
private fun NewPlaylistShelfCard(
    accent: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(124.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.18f), Color.White.copy(alpha = 0.04f)),
                    ),
                )
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Nueva lista",
                tint = accent,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Nueva lista",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
        )
        Text(
            text = "Crear lista propia",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.50f),
            maxLines = 1,
        )
    }
}

@Composable
private fun PlaylistTileDesktop(
    playlist: LocalPlaylist,
    accent: Color,
    onClick: () -> Unit,
    onPlayDirect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.LibraryMusic,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${playlist.songs.size} canciones · Lista propia",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.50f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.20f))
                .clickable(onClick = onPlayDirect),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Reproducir lista",
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun LocalPlaylistDetailPane(
    list: LocalPlaylist,
    palette: ArtworkPalette,
    currentMediaId: String?,
    onPlayIndex: (Int?) -> Unit,
    onDeletePlaylist: (() -> Unit)? = null,
    onRemoveSong: ((String) -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(list.name, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text("${list.songs.size} canciones", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onDeletePlaylist != null) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFFE53935).copy(alpha = 0.16f))
                            .border(1.dp, Color(0xFFE53935).copy(alpha = 0.35f), CircleShape)
                            .clickable(onClick = onDeletePlaylist)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("Eliminar lista", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF8A80))
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(palette.primary)
                        .clickable { onPlayIndex(null) }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text("Aleatoria", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF140E21))
                }
            }
        }
        if (list.songs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Esta lista está vacía.", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(list.songs, key = { idx, s -> "${s.id}-$idx" }) { idx, s ->
                    SongRowDesktop(
                        title = s.title,
                        subtitle = s.artist,
                        thumbnail = s.artwork,
                        isCurrent = s.id == currentMediaId,
                        accent = palette.primary,
                        onClick = { onPlayIndex(idx) },
                        trailing = if (onRemoveSong != null) {
                            {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.06f))
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

@Composable
private fun RemoteDetailPane(
    page: Page.Remote,
    palette: ArtworkPalette,
    currentMediaId: String?,
    onPlayAll: (List<SongItem>, Int?) -> Unit,
    onOpenItem: (YTItem) -> Unit,
) {
    val songs = page.songs
    val shelves = page.shelves
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(page.item.title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                page.sub?.let { Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f)) }
            }
            if (!songs.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(palette.primary)
                        .clickable { onPlayAll(songs, null) }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text("Reproducir", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF140E21))
                }
            }
        }
        when {
            page.error != null -> Text("Error: ${page.error}", color = palette.secondary, fontSize = 14.sp)
            songs == null && shelves.isEmpty() -> Text("Cargando pistas de YouTube Music…", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!songs.isNullOrEmpty()) {
                    itemsIndexed(songs, key = { i, s -> "${s.id}-$i" }) { idx, s ->
                        SongRowDesktop(
                            title = s.title,
                            subtitle = s.artists.joinToString { it.name },
                            thumbnail = s.thumbnail,
                            isCurrent = s.id == currentMediaId,
                            accent = palette.primary,
                            onClick = { onPlayAll(songs, idx) },
                        )
                    }
                }
                if (shelves.isNotEmpty()) {
                    items(shelves, key = { it.title }) { shelf ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(shelf.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (chips.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chips, key = { it.title }) { chip ->
                    MenuTabPill(chip.title, selectedChip == chip.title, palette.secondary) {
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
                // Skeleton loading placeholder
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(2) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.size(width = 160.dp, height = 20.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.08f)),
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(5) {
                                    Box(
                                        Modifier.size(136.dp)
                                            .clip(RoundedCornerShape(14.dp))
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
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(shelves, key = { it.title }) { shelf ->
                    val allSongs = shelf.items.all { it is SongItem }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = shelf.title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        if (allSongs && shelf.items.size > 3) {
                            // Columnas compactas de 3 canciones en carrusel horizontal
                            val cols = shelf.items.filterIsInstance<SongItem>().chunked(3)
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(cols) { colSongs ->
                                    Column(
                                        modifier = Modifier.width(310.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        colSongs.forEach { song ->
                                            SongRowDesktop(
                                                title = song.title,
                                                subtitle = song.artists.joinToString { it.name },
                                                thumbnail = song.thumbnail,
                                                isCurrent = song.id == currentMediaId,
                                                accent = palette.primary,
                                                onClick = { onPlaySong(song) },
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Carrusel horizontal de tarjetas con portada
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
    var thumbBmp by remember(item.thumbnail) { mutableStateOf<ImageBitmap?>(null) }
    var isHovered by remember { mutableStateOf(false) }

    LaunchedEffect(item.thumbnail) {
        if (!item.thumbnail.isNullOrBlank()) {
            thumbBmp = ArtworkLoader.load(item.thumbnail).first
        }
    }

    val isArtist = item is ArtistItem
    val shape = if (isArtist) CircleShape else RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .padding(4.dp),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.05f))
                .border(
                    width = if (isHovered) 1.5.dp else 1.dp,
                    color = if (isHovered) accent.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.10f),
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
                    modifier = Modifier.size(44.dp),
                )
            }

            if (isHovered) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Reproducir",
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = item.title,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = if (isArtist) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )

        val sub = when (item) {
            is SongItem -> item.artists.joinToString { it.name }
            is AlbumItem -> listOfNotNull("Álbum", item.year?.toString()).joinToString(" · ")
            is PlaylistItem -> item.author?.name ?: "Playlist"
            is ArtistItem -> "Artista"
            else -> ""
        }
        if (sub.isNotBlank()) {
            Text(
                text = sub,
                fontSize = 11.5.sp,
                color = Color.White.copy(alpha = 0.52f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

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
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f ->
                MenuTabPill(f.label, filter == f, palette.primary) { onFilter(f) }
            }
        }
        when {
            error != null -> Text("La búsqueda falló: $error", color = palette.secondary, fontSize = 13.sp)
            searching && results.isEmpty() -> Text("Buscando «$query» en YouTube Music…", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            results.isEmpty() -> Text("Sin resultados para «$query».", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results, key = { it.id }) { item ->
                    val sub = when (item) {
                        is SongItem -> "Canción · " + item.artists.joinToString { it.name }
                        is AlbumItem -> "Álbum · " + item.artists?.joinToString { it.name }.orEmpty()
                        is ArtistItem -> "Artista"
                        is PlaylistItem -> "Lista · " + (item.author?.name ?: "")
                        else -> ""
                    }
                    SongRowDesktop(
                        title = item.title,
                        subtitle = sub,
                        thumbnail = item.thumbnail,
                        isCurrent = item.id == currentMediaId,
                        accent = palette.primary,
                        onClick = { if (item is SongItem) onPlaySong(item) else onOpenItem(item) },
                    )
                }
            }
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
    LaunchedEffect(artwork) {
        if (!artwork.isNullOrBlank()) {
            thumbBmp = ArtworkLoader.load(artwork).first
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.03f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isCurrent) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
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
            }
        }
    }
}

@Composable
private fun SongRowDesktop(
    title: String,
    subtitle: String,
    thumbnail: String? = null,
    isCurrent: Boolean,
    accent: Color,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    var thumbBmp by remember(thumbnail) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(thumbnail) {
        if (!thumbnail.isNullOrBlank()) {
            thumbBmp = ArtworkLoader.load(thumbnail).first
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.03f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
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
            }
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.50f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Sonando",
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else if (thumbBmp == null) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(18.dp),
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
        if (trailing != null) {
            trailing()
        }
    }
}
