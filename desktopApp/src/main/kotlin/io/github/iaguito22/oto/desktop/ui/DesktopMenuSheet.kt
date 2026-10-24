package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileUpload
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

/**
 * Cajón inferior (`Sheet` estilo prototipo 2 + `fusion.html`):
 * - Píldora inferior de asomo (`Tu música · Explorar · Buscar`) siempre accesible.
 * - Se abre y se cierra también con la rueda del ratón (`WheelEvent`) o con `Ctrl+K` / `Escape`.
 * - Comparte `MenuController`, `Prefs`, `Importer`, `Account` y `Cloud` de `:core`.
 */
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
    onPlaySong: (SongItem) -> Unit,
    onPlayAll: (List<SongItem>, Int?, YTItem?) -> Unit,
    onPlayLocal: (LocalPlaylist, Int?) -> Unit,
    onPlayDj: () -> Unit,
    onSkipToQueue: (Int) -> Unit,
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

    var activeTab by remember { mutableIntStateOf(0) } // 0 = Tu música, 1 = Explorar, 2 = Cola, 3 = Ajustes/Cuenta

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Píldora inferior cuando está cerrado
        AnimatedVisibility(
            visible = !open,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.padding(bottom = 18.dp),
        ) {
            Row(
                modifier = Modifier
                    .shadow(20.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xD9161321))
                    .border(1.dp, palette.primary.copy(alpha = 0.28f), CircleShape)
                    .clickable { onToggleOpen(true) }
                    .padding(horizontal = 22.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "Abrir menú",
                    tint = palette.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Tu música · Explorar · Buscar",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.88f),
                )
                Text(
                    text = "Scroll ↓ o Ctrl+K",
                    fontSize = 11.sp,
                    color = palette.primary.copy(alpha = 0.78f),
                )
            }
        }

        // Fondo oscurecido y cajón desplegado desde abajo
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.56f))
                    .clickable { onToggleOpen(false) },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 1180.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(0.84f)
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xF5171424), Color(0xFA0E0C17)),
                            ),
                        )
                        .border(
                            1.dp,
                            palette.primary.copy(alpha = 0.24f),
                            RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        )
                        .clickable(enabled = false) {}
                        .padding(horizontal = 28.dp, vertical = 18.dp),
                ) {
                    // Tirador superior
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(44.dp, 4.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.28f)),
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Cabecera: Pestañas + Buscador en vivo + Botón cerrar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val topPage = menuState.pages.lastOrNull()
                        if (topPage != null) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .clickable { menu.closeDetail() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.ArrowBack, "Atrás", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }

                        MenuTabPill("Tu música", activeTab == 0 && menuState.query.isBlank() && topPage == null, palette.primary) {
                            menu.closeAll(); menu.onQuery(""); activeTab = 0
                        }
                        MenuTabPill("Explorar", activeTab == 1 && menuState.query.isBlank() && topPage == null, palette.primary) {
                            menu.closeAll(); menu.onQuery(""); activeTab = 1
                        }
                        MenuTabPill("Cola (${queue.size})", activeTab == 2 && menuState.query.isBlank() && topPage == null, palette.primary) {
                            menu.closeAll(); menu.onQuery(""); activeTab = 2
                        }
                        MenuTabPill("Cuenta y Ajustes", activeTab == 3 && menuState.query.isBlank() && topPage == null, palette.primary) {
                            menu.closeAll(); menu.onQuery(""); activeTab = 3
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
                                    modifier = Modifier.fillMaxWidth(),
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

                        Box(
                            modifier = Modifier
                                .size(38.dp)
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
                                    )
                                }
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
                            activeTab == 0 -> {
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
                                )
                            }
                            activeTab == 1 -> {
                                ExplorePane(
                                    shelves = menuState.shelves,
                                    chips = menuState.chips,
                                    selectedChip = menuState.chip,
                                    error = menuState.homeError,
                                    palette = palette,
                                    onChip = menu::onChip,
                                    onRetry = menu::loadHome,
                                    onPlaySong = { onPlaySong(it); onToggleOpen(false) },
                                    onOpenItem = menu::open,
                                )
                            }
                            activeTab == 2 -> {
                                QueuePane(
                                    queue = queue,
                                    currentMediaId = currentMediaId,
                                    palette = palette,
                                    onSkipTo = { idx -> onSkipToQueue(idx); onToggleOpen(false) },
                                )
                            }
                            else -> {
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
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // Tarjetas rápidas arriba: DJ + Me gusta + Listas guardadas
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 220.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.height(156.dp),
        ) {
            item(key = "dj") {
                QuickCard(
                    title = "DJ de Oto",
                    subtitle = "Mezcla inteligente con lo que oyes",
                    accent = palette.secondary,
                    icon = { Icon(Icons.Rounded.AutoAwesome, null, tint = palette.secondary) },
                    onClick = onPlayDj,
                )
            }
            item(key = LIKED_ID) {
                QuickCard(
                    title = "Me gusta",
                    subtitle = "${likedList.songs.size} canciones",
                    accent = palette.primary,
                    icon = { Icon(Icons.Rounded.Favorite, null, tint = palette.primary) },
                    onClick = { onOpenLocal(LIKED_ID) },
                    onPlayDirect = { onPlayLocal(likedList, null) },
                )
            }
            items(playlists, key = { it.id }) { pl ->
                QuickCard(
                    title = pl.name,
                    subtitle = "${pl.songs.size} canciones",
                    accent = palette.tertiary,
                    icon = { Icon(Icons.Rounded.QueueMusic, null, tint = palette.tertiary) },
                    onClick = { onOpenLocal(pl.id) },
                    onPlayDirect = { onPlayLocal(pl, null) },
                )
            }
        }

        Text(
            text = "Escuchado recientemente",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.85f),
        )

        if (history.isEmpty()) {
            Text(
                text = "Aún no hay canciones recientes. Busca arriba o ve a «Cuenta y Ajustes» para importar tu oto-spotify.json.",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.55f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(history, key = { it.id }) { h ->
                    SongRowDesktop(
                        title = h.title,
                        subtitle = h.artist,
                        isCurrent = h.id == currentMediaId,
                        accent = palette.primary,
                        onClick = { onPlayHeard(h) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickCard(
    title: String,
    subtitle: String,
    accent: Color,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    onPlayDirect: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onPlayDirect != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .clickable(onClick = onPlayDirect),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, "Reproducir", tint = Color(0xFF140E21), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun LocalPlaylistDetailPane(
    list: LocalPlaylist,
    palette: ArtworkPalette,
    currentMediaId: String?,
    onPlayIndex: (Int?) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(list.name, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text("${list.songs.size} canciones", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
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
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(list.songs, key = { idx, s -> "${s.id}-$idx" }) { idx, s ->
                SongRowDesktop(
                    title = s.title,
                    subtitle = s.artist,
                    isCurrent = s.id == currentMediaId,
                    accent = palette.primary,
                    onClick = { onPlayIndex(idx) },
                )
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            songs == null -> Text("Cargando pistas de YouTube Music…", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(songs, key = { i, s -> "${s.id}-$i" }) { idx, s ->
                    SongRowDesktop(
                        title = s.title,
                        subtitle = s.artists.joinToString { it.name },
                        isCurrent = s.id == currentMediaId,
                        accent = palette.primary,
                        onClick = { onPlayAll(songs, idx) },
                    )
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
    onChip: (String) -> Unit,
    onRetry: () -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onOpenItem: (YTItem) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (chips.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.take(7).forEach { chip ->
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
            shelves == null -> Text("Cargando portada de YouTube Music…", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(shelves, key = { it.title }) { shelf ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(shelf.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        shelf.items.take(6).forEach { item ->
                            val sub = when (item) {
                                is SongItem -> item.artists.joinToString { it.name }
                                is AlbumItem -> "Álbum · " + item.artists?.joinToString { it.name }.orEmpty()
                                is ArtistItem -> "Artista"
                                is PlaylistItem -> "Playlist · " + (item.author?.name ?: "")
                                else -> ""
                            }
                            SongRowDesktop(
                                title = item.title,
                                subtitle = sub,
                                isCurrent = false,
                                accent = palette.primary,
                                onClick = { if (item is SongItem) onPlaySong(item) else onOpenItem(item) },
                            )
                        }
                    }
                }
            }
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
    palette: ArtworkPalette,
    onSkipTo: (Int) -> Unit,
) {
    if (queue.isEmpty()) {
        Text("La cola está vacía.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(queue, key = { _, e -> e.index }) { idx, entry ->
            SongRowDesktop(
                title = entry.title,
                subtitle = entry.artist,
                isCurrent = entry.id == currentMediaId,
                accent = palette.primary,
                onClick = { onSkipTo(idx) },
            )
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
    isCurrent: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.03f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (isCurrent) Icons.Rounded.PlayArrow else Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = if (isCurrent) accent else Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
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
    }
}
