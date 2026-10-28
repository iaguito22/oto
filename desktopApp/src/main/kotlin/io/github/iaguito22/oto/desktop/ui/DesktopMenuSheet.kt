package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import io.github.iaguito22.oto.importer.SourceList
import io.github.iaguito22.oto.importer.SourceTrack
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.playback.NowPlaying
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import io.github.iaguito22.oto.desktop.playback.DesktopDownloads
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

private enum class MenuSubView { None, Settings, Import, Downloads }

/** El id con el que una carpeta suena como una lista (y se vuelve a abrir desde lo que suena). */
private const val FOLDER_PREFIX = "folder:"


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
    isPlaying: Boolean = false,
    onTextInputFocusChange: (Boolean) -> Unit = {},
    onToggleOpen: (Boolean) -> Unit,
    onOpenQueue: () -> Unit = {},
    menu: MenuController,
    prefs: Prefs,
    importer: Importer,
    account: Account,
    cloud: Cloud,
    login: BrowserLogin,
    downloads: DesktopDownloads,
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
    onPlayLocal: (LocalPlaylist, Int?, Boolean) -> Unit,
    onPlayDj: () -> Unit,
    onSkipToQueue: (Int) -> Unit,
    selectedTab: Int = 0,
    onSelectTab: (Int) -> Unit = {},
    onSaveTrack: (Heard) -> Unit = {},
    nowPlaying: NowPlaying? = null,
    liked: Boolean = false,
    volume: Float = 1.0f,
    onAdjustVolume: (Float) -> Unit = {},
    onTogglePlay: () -> Unit = {},
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onToggleLike: () -> Unit = {},
) {
    val menuState by menu.state.collectAsState()
    val likedList by prefs.likedList.collectAsState(initial = LocalPlaylist(LIKED_ID, "Me gusta", emptyList()))
    val playlists by prefs.playlists.collectAsState(initial = emptyList())
    val history by prefs.history.collectAsState(initial = emptyList())
    val qualityMode by prefs.quality.collectAsState(initial = QualityMode.Auto)
    val dataSaver by prefs.dataSaver.collectAsState(initial = false)
    val importState by importer.state.collectAsState()
    val downloadEntries by downloads.entries.collectAsState()
    val downloadLists by prefs.downloadLists.collectAsState(initial = emptySet())
    val folders by prefs.folders.collectAsState(initial = emptySet())
    val pinned by prefs.pinned.collectAsState(initial = emptySet())
    val session by account.session.collectAsState()
    val cloudState by cloud.state.collectAsState()
    val scope = rememberCoroutineScope()

    var preMuteVol by remember { mutableFloatStateOf(1f) }
    var subView by remember { mutableStateOf(MenuSubView.None) }
    var showAllLists by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    var pillHovered by remember { mutableStateOf(false) }

    // Avisos con deshacer, como en el móvil: lo que quitas se oculta al instante y se borra de verdad cuando el
    // aviso se va. Deshacer lo devuelve a su sitio sin haberlo tocado.
    var notice by remember { mutableStateOf<DesktopNotice?>(null) }
    var gone by remember { mutableStateOf(emptySet<String>()) }
    fun settleNotice(n: DesktopNotice?) { n?.commit?.invoke() }
    fun postNotice(n: DesktopNotice) { settleNotice(notice); notice = n }
    fun say(text: String) = postNotice(DesktopNotice(text))
    fun undoNotice(text: String, undo: () -> Unit) = postNotice(DesktopNotice(text, undo = undo))
    fun later(key: String, text: String, commit: () -> Unit) {
        gone = gone + key
        postNotice(DesktopNotice(text, undo = { gone = gone - key }, commit = commit))
    }
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        kotlinx.coroutines.delay(if (n.undo != null) 5200L else 2600L)
        if (notice === n) {
            notice = null
            settleNotice(n)
        }
    }
    val pendingNotice by rememberUpdatedState(notice)
    DisposableEffect(Unit) { onDispose { settleNotice(pendingNotice) } }

    // Lo quitado y aún sin borrar ya no se ve en ningún sitio.
    val visiblePlaylists = playlists.filter { "l:${it.id}" !in gone }
    val visibleFolders = folders.filter { "d:$it" !in gone }.toSet()

    LaunchedEffect(open) {
        if (!open) {
            subView = MenuSubView.None
            showAllLists = false
            onTextInputFocusChange(false)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val openTargetW = (maxWidth - 36.dp).coerceAtMost(1040.dp).coerceAtLeast(320.dp)
        val openTargetH = (maxHeight - 56.dp).coerceAtLeast(360.dp)
        val closedPillH = if (!open && pillHovered) 28.dp else 24.dp

        val morphSpec = spring<androidx.compose.ui.unit.Dp>(
            dampingRatio = 0.82f,
            stiffness = 380f,
        )
        val currentHeight by animateDpAsState(
            targetValue = if (open) openTargetH else closedPillH,
            animationSpec = morphSpec,
            label = "islandHeight",
        )
        val currentCorner by animateDpAsState(
            targetValue = if (open) 30.dp else 12.dp,
            animationSpec = morphSpec,
            label = "islandCorner",
        )
        val idleAlpha by animateFloatAsState(
            targetValue = if (!open && isIdle) 0f else 1f,
            animationSpec = tween(260, easing = FastOutSlowInEasing),
            label = "pillIdleAlpha",
        )
        val idleTranslateY by animateFloatAsState(
            targetValue = if (!open && isIdle) 12f else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "pillIdleY",
        )

        // Capa exterior para cerrar al hacer clic fuera de la isla cuando está abierta
        if (open) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.16f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggleOpen(false) },
            )
        }

        // Contenedor único de cristal (Fondo 4 · Lente de Agua + Isla Flotante):
        // En reposo es la píldora inferior del mismo ancho del menú y sin textos; al abrir, sube desde ella sin fadeIn.
        val sheetShape = RoundedCornerShape(currentCorner)
        val isExpanded = open || currentHeight > 54.dp

        Box(
            modifier = Modifier
                .padding(bottom = 16.dp)
                .graphicsLayer {
                    alpha = if (open) 1f else idleAlpha
                    translationY = if (open) 0f else idleTranslateY
                }
                .size(width = openTargetW, height = currentHeight)
                .shadow(
                    elevation = if (open) 42.dp else 4.dp,
                    shape = sheetShape,
                    ambientColor = Color.Black.copy(alpha = if (open) 0.62f else 0.25f),
                    spotColor = Color.Black.copy(alpha = if (open) 1f else 0.45f),
                )
                .clip(sheetShape)
                .background(
                    if (open) {
                        Brush.verticalGradient(
                            0.00f to Color.White.copy(alpha = 0.14f),
                            0.28f to Color(0x82101010),
                            1.00f to Color(0x9E0C0C0C),
                        )
                    } else {
                        Brush.verticalGradient(
                            0.00f to Color.White.copy(alpha = if (pillHovered) 0.08f else 0.04f),
                            1.00f to Color(0x600C0C0C),
                        )
                    },
                )
                .border(
                    1.dp,
                    if (open) {
                        Brush.verticalGradient(
                            0.00f to Color.White.copy(alpha = 0.42f),
                            0.35f to Color.White.copy(alpha = 0.30f),
                            1.00f to palette.primary.copy(alpha = 0.26f),
                        )
                    } else {
                        Brush.verticalGradient(
                            0.00f to Color.White.copy(alpha = if (pillHovered) 0.18f else 0.08f),
                            1.00f to Color.White.copy(alpha = if (pillHovered) 0.08f else 0.04f),
                        )
                    },
                    sheetShape,
                )
                .onPointerEvent(PointerEventType.Enter) { pillHovered = true }
                .onPointerEvent(PointerEventType.Exit) { pillHovered = false }
                .onPointerEvent(PointerEventType.Scroll) { ev ->
                    val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (!open && dy > 0.3f) {
                        onToggleOpen(true)
                    }
                }
                .then(
                    if (!open) {
                        Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onToggleOpen(true) }
                    } else {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {}
                    },
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Refracción de Lente de Agua (Opción 4):
            // - Brillo blanco cristalino en el labio superior (inset 0 2px 3px rgba(255,255,255,0.6))
            // - Velo de agua ultratransparente arriba que deja pasar la luz del escenario desenfocado
            // - Resplandor interior inferior con el color --accent (inset 0 -16px 40px var(--accent))
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRect(
                    brush = Brush.verticalGradient(
                        0.00f to Color.White.copy(alpha = 0.09f),
                        0.22f to Color.Transparent,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(palette.primary.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(w * 0.50f, h * 1.02f),
                        radius = w * 0.68f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(palette.primary.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(w * 0.24f, h * 0.06f),
                        radius = w * 0.50f,
                    ),
                )
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

            if (!isExpanded) {
                // Píldora cerrada sin textos: solo barra tirador + 3 barritas mini-ecualizador animadas en --accent
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (pillHovered) 0.52f else 0.30f)),
                    )
                    if (isPlaying) {
                        Spacer(Modifier.width(10.dp))
                        MiniEqualizerBars(accent = palette.primary)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .requiredWidth(openTargetW)
                        .requiredHeight(openTargetH),
                ) {
                    // Tirador superior con mini-controles de reproducción en vivo (.sheet-grab)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // 1. Info mini del tema que suena
                        if (nowPlaying != null && !nowPlaying.title.isNullOrBlank()) {
                            Row(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                var miniArtBmp by remember(nowPlaying.artwork) { mutableStateOf<ImageBitmap?>(null) }
                                LaunchedEffect(nowPlaying.artwork) {
                                    if (!nowPlaying.artwork.isNullOrBlank()) miniArtBmp = ArtworkLoader.load(nowPlaying.artwork).first
                                }
                                if (miniArtBmp != null) {
                                    Image(
                                        bitmap = miniArtBmp!!,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
                                    )
                                }
                                Column(modifier = Modifier.widthIn(max = 240.dp)) {
                                    Text(
                                        text = nowPlaying.title,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFFFFFF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = nowPlaying.artist,
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.55f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }

                        // 2. Tirador central
                        Box(
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onToggleOpen(false) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(width = 38.dp, height = 4.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.28f)),
                                )
                                if (isPlaying) {
                                    MiniEqualizerBars(accent = palette.primary)
                                }
                            }
                        }

                        // 3. Mini controles para accionar mientras estás en el menú + Control de volumen (Opción C)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (nowPlaying != null && !nowPlaying.title.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable(onClick = onPrev),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Anterior", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(palette.primary)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable(onClick = onTogglePlay),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                        tint = Color(0xFF101010),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable(onClick = onNext),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.SkipNext, contentDescription = "Siguiente", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable(onClick = onToggleLike),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        contentDescription = "Me gusta",
                                        tint = if (liked) palette.primary else Color.White.copy(alpha = 0.75f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Spacer(Modifier.width(2.dp))
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(14.dp)
                                        .background(Color.White.copy(alpha = 0.18f)),
                                )
                                Spacer(Modifier.width(2.dp))
                            }

                            // Control de volumen interactivo (Opción C)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .pointerHoverIcon(PointerIcon.Default)
                                    .onPointerEvent(PointerEventType.Scroll) { ev ->
                                        val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                        if (dy < -0.1f) onAdjustVolume(0.04f)
                                        else if (dy > 0.1f) onAdjustVolume(-0.04f)
                                    },
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable {
                                            if (volume > 0.001f) {
                                                preMuteVol = volume
                                                onAdjustVolume(-volume)
                                            } else {
                                                onAdjustVolume(if (preMuteVol > 0.05f) preMuteVol else 0.7f)
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = when {
                                            volume <= 0.001f -> Icons.AutoMirrored.Rounded.VolumeMute
                                            volume < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                                            else -> Icons.AutoMirrored.Rounded.VolumeUp
                                        },
                                        contentDescription = if (volume <= 0.001f) "Desmutear" else "Silenciar",
                                        tint = Color.White.copy(alpha = 0.82f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .width(68.dp)
                                        .height(18.dp)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .pointerInput(Unit) {
                                            detectTapGestures { offset ->
                                                val ratio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                                onAdjustVolume(ratio - volume)
                                            }
                                        }
                                        .pointerInput(Unit) {
                                            detectHorizontalDragGestures { change, dragAmount ->
                                                change.consume()
                                                onAdjustVolume(dragAmount / 68f)
                                            }
                                        },
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.20f)),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(volume.coerceIn(0f, 1f))
                                                .clip(CircleShape)
                                                .background(palette.primary),
                                        )
                                    }
                                }
                                Text(
                                    text = "${(volume * 100f).roundToInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.75f),
                                    modifier = Modifier.width(32.dp),
                                )
                            }
                        }
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

                            // Botón Atrás cuando estamos en subvista o búsqueda activa (en listas y álbumes va arriba a la derecha junto al título)
                            val showTopBack = isDeepNav && topPage == null
                            if (showTopBack) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable {
                                            when {
                                                subView == MenuSubView.Import -> subView = MenuSubView.Settings
                                                subView != MenuSubView.None -> subView = MenuSubView.None
                                                showAllLists -> showAllLists = false
                                                menuState.query.isNotBlank() -> menu.onQuery("")
                                                else -> onToggleOpen(false)
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = "Volver",
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }

                            Spacer(Modifier.weight(1f))

                            // Botones circulares de acción (.sheet-icon-btn en core.css:828-852)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SheetCircleIconButton(
                                    icon = Icons.Rounded.Download,
                                    tooltip = "Descargas",
                                    active = subView == MenuSubView.Downloads,
                                    accent = palette.primary,
                                    badge = downloadEntries.size.takeIf { it > 0 }?.toString(),
                                    onClick = {
                                        subView = if (subView == MenuSubView.Downloads) MenuSubView.None else MenuSubView.Downloads
                                    },
                                )
                                SheetCircleIconButton(
                                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                    tooltip = "Cola de reproducción",
                                    active = false,
                                    accent = palette.primary,
                                    badge = if (queue.isNotEmpty()) "${queue.size}" else null,
                                    onClick = onOpenQueue,
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
                                subView == MenuSubView.Import -> {
                                    ImportPane(
                                        importer = importer,
                                        prefs = prefs,
                                        palette = palette,
                                        onBack = { subView = MenuSubView.Settings },
                                        onLists = {
                                            subView = MenuSubView.None
                                            onSelectTab(0)
                                        },
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
                                        shuffle = shuffle,
                                        loop = loop,
                                        onSetShuffle = onSetShuffle,
                                        onSetLoop = onSetLoop,
                                        palette = palette,
                                        onOpenImport = { subView = MenuSubView.Import },
                                    )
                                }
                                subView == MenuSubView.Downloads -> {
                                    DownloadsPane(
                                        downloads = downloads,
                                        prefs = prefs,
                                        palette = palette,
                                        currentMediaId = currentMediaId,
                                        onBack = { subView = MenuSubView.None },
                                        onOpenLocal = { id ->
                                            subView = MenuSubView.None
                                            menu.openLocal(id)
                                        },
                                        onPlayLocal = { list -> onPlayLocal(list, null, false); onToggleOpen(false) },
                                        onPlaySong = { song -> onPlaySong(song); onToggleOpen(false) },
                                        onRemoveList = { list ->
                                            scope.launch { prefs.setDownloadList(list.id, false) }
                                            undoNotice("«${list.name}» ya no está descargada") {
                                                scope.launch { prefs.setDownloadList(list.id, true) }
                                            }
                                        },
                                        onRemoveSong = { heard ->
                                            scope.launch { prefs.setDownloadSong(heard, false) }
                                            undoNotice("«${heard.title}» ya no está descargada") {
                                                scope.launch { prefs.setDownloadSong(heard, true) }
                                            }
                                        },
                                        onClearAll = {
                                            scope.launch { downloads.clear() }
                                            say("Descargas borradas")
                                        },
                                    )
                                }
                                topPage is Page.Folder -> {
                                    val inside = visiblePlaylists.filter { it.folder == topPage.name }
                                    FolderDetailPane(
                                        name = topPage.name,
                                        lists = inside,
                                        allPlaylists = visiblePlaylists,
                                        palette = palette,
                                        currentMediaId = currentMediaId,
                                        onBack = menu::closeDetail,
                                        onOpenLocal = menu::openLocal,
                                        onPlayFolder = { songs ->
                                            onPlayLocal(
                                                LocalPlaylist(FOLDER_PREFIX + topPage.name, topPage.name, songs),
                                                null,
                                                false,
                                            )
                                            onToggleOpen(false)
                                        },
                                        onToggleInside = { pl, isInside ->
                                            scope.launch { prefs.moveToFolder(pl.id, if (isInside) topPage.name else null) }
                                        },
                                        onRename = { newName ->
                                            scope.launch {
                                                prefs.renameFolder(topPage.name, newName)
                                                menu.renameFolder(topPage.name, newName)
                                            }
                                        },
                                        onUngroup = {
                                            val folderName = topPage.name
                                            val insideIds = visiblePlaylists.filter { it.folder == folderName }.map { it.id }
                                            menu.closeDetail()
                                            scope.launch { prefs.ungroup(folderName) }
                                            undoNotice("Carpeta «$folderName» deshecha") {
                                                scope.launch {
                                                    prefs.createFolder(folderName)
                                                    insideIds.forEach { prefs.moveToFolder(it, folderName) }
                                                }
                                            }
                                        },
                                        pinned = ("d:" + topPage.name) in pinned,
                                        onTogglePin = { scope.launch { prefs.togglePin("d:" + topPage.name) } },
                                    )
                                }
                                topPage is Page.Local -> {
                                    val list = if (topPage.id == LIKED_ID) likedList else playlists.firstOrNull { it.id == topPage.id }
                                    if (list != null) {
                                        // Lo que está pendiente de borrar desaparece ya de la lista (y del índice al reproducir).
                                        val shownList = list.copy(songs = list.songs.filter { "s:${list.id}:${it.id}" !in gone })
                                        LocalPlaylistDetailPane(
                                            list = shownList,
                                            palette = palette,
                                            currentMediaId = currentMediaId,
                                            onBack = menu::closeDetail,
                                            onPlayIndex = { idx, newest -> onPlayLocal(shownList, idx, newest); onToggleOpen(false) },
                                            onSaveTo = onSaveTrack,
                                            onDeletePlaylist = if (list.id != LIKED_ID) {
                                                {
                                                    menu.closeDetail()
                                                    later("l:${list.id}", "«${list.name}» borrada") {
                                                        scope.launch { prefs.deletePlaylist(list.id) }
                                                    }
                                                }
                                            } else null,
                                            onRemoveSong = if (list.id != LIKED_ID) {
                                                { songId ->
                                                    val title = list.songs.firstOrNull { it.id == songId }?.title ?: "Canción"
                                                    later("s:${list.id}:$songId", "«$title» quitada de «${list.name}»") {
                                                        scope.launch { prefs.removeFromPlaylist(list.id, songId) }
                                                    }
                                                }
                                            } else null,
                                            prefs = prefs,
                                            downloads = downloads,
                                            downloadLists = downloadLists,
                                            folders = folders,
                                            pinned = list.id in pinned,
                                            onTogglePin = { scope.launch { prefs.togglePin(list.id) } },
                                            onMoveToFolder = { folder -> scope.launch { prefs.moveToFolder(list.id, folder) } },
                                            onCreateFolderAndMove = { name ->
                                                scope.launch {
                                                    prefs.createFolder(name)
                                                    prefs.moveToFolder(list.id, name)
                                                }
                                            },
                                        )
                                    }
                                }
                                topPage is Page.Smart -> {
                                    LocalPlaylistDetailPane(
                                        list = topPage.list,
                                        palette = palette,
                                        currentMediaId = currentMediaId,
                                        onBack = menu::closeDetail,
                                        onPlayIndex = { idx, newest -> onPlayLocal(topPage.list, idx, newest); onToggleOpen(false) },
                                        onSaveTo = onSaveTrack,
                                        onDeletePlaylist = null,
                                        onRemoveSong = null,
                                        prefs = prefs,
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
                                showAllLists && selectedTab == 0 -> {
                                    AllListsSubPane(
                                        likedList = likedList,
                                        playlists = visiblePlaylists,
                                        folders = visibleFolders,
                                        palette = palette,
                                        onBack = { showAllLists = false },
                                        onOpenFolder = { name ->
                                            showAllLists = false
                                            menu.openFolder(name)
                                        },
                                        onOpenLocal = { id ->
                                            showAllLists = false
                                            menu.openLocal(id)
                                        },
                                        onPlayLocal = { list ->
                                            onPlayLocal(list, null, list.id == LIKED_ID)
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
                                        playlists = visiblePlaylists,
                                        folders = visibleFolders,
                                        pinned = pinned,
                                        history = history,
                                        palette = palette,
                                        currentMediaId = currentMediaId,
                                        onOpenLocal = menu::openLocal,
                                        onOpenSmart = menu::openSmart,
                                        onOpenFolder = menu::openFolder,
                                        onOpenAllLists = { showAllLists = true },
                                        onPlayLocal = { list, idx -> onPlayLocal(list, idx, list.id == LIKED_ID); onToggleOpen(false) },
                                        onPlayDj = { onPlayDj(); onToggleOpen(false) },
                                        onPlayHeard = { h -> onPlaySong(h.toSong()); onToggleOpen(false) },
                                        onSaveTo = onSaveTrack,
                                        onCreatePlaylist = { name ->
                                            scope.launch {
                                                val newId = prefs.createPlaylist(name)
                                                menu.openLocal(newId)
                                            }
                                        },
                                        onCreateFolder = { name -> scope.launch { prefs.createFolder(name) } },
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

                        // Aviso con deshacer, encima del buscador como en el móvil.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = notice != null,
                                enter = fadeIn(tween(Motion.EFFECTS)) + slideInVertically { it / 3 },
                                exit = fadeOut(tween(Motion.EFFECTS_FAST)) + slideOutVertically(tween(Motion.EFFECTS_FAST)) { it / 3 },
                            ) {
                                notice?.let { n ->
                                    NoticePillDesktop(n, palette.primary) {
                                        notice = null
                                        n.undo?.invoke()
                                    }
                                }
                            }
                        }

                        // El buscador va abajo, pegado al borde, como en el móvil.
                        MenuSearchBar(
                            query = menuState.query,
                            accent = palette.primary,
                            focusRequester = searchFocusRequester,
                            onQuery = {
                                subView = MenuSubView.None
                                showAllLists = false
                                menu.onQuery(it)
                            },
                            onClear = { menu.onQuery("") },
                            onFocusChange = onTextInputFocusChange,
                        )
                    }
                }
            }
        }
}

/** Un aviso efímero del menú; [undo] solo aparece en lo que se puede deshacer. */
private data class DesktopNotice(
    val text: String,
    val undo: (() -> Unit)? = null,
    val commit: (() -> Unit)? = null,
)

@Composable
private fun NoticePillDesktop(n: DesktopNotice, accent: Color, onUndo: () -> Unit) {
    Row(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .height(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .padding(start = 14.dp, end = if (n.undo != null) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = n.text,
            style = OtoType.rowSub,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (n.undo != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Deshacer",
                style = OtoType.rowTitle,
                color = accent,
                modifier = Modifier
                    .clip(CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onUndo)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** El buscador del menú: abajo, en píldora, como el del móvil. */
@Composable
private fun MenuSearchBar(
    query: String,
    accent: Color,
    focusRequester: FocusRequester,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 4.dp, bottom = 16.dp)
            .height(42.dp)
            .clip(CircleShape)
            .background(
                if (query.isNotBlank()) Color.White.copy(alpha = 0.09f)
                else Color.White.copy(alpha = 0.06f),
            )
            .border(
                1.dp,
                if (query.isNotBlank()) accent.copy(alpha = 0.48f)
                else Color.White.copy(alpha = 0.09f),
                CircleShape,
            )
            .clickable { focusRequester.requestFocus() }
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
            if (query.isEmpty()) {
                Text(
                    text = "Canciones, artistas, listas…",
                    style = OtoType.rowTitle,
                    color = Color.White.copy(alpha = 0.42f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = OtoFontFamily,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChange(it.isFocused) },
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Limpiar búsqueda",
                tint = Color.White.copy(alpha = 0.65f),
                modifier = Modifier
                    .size(16.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onClear),
            )
        }
    }
}

@Composable
private fun MiniEqualizerBars(accent: Color) {
    val transition = rememberInfiniteTransition(label = "miniEq")
    val bar1 by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eqBar1",
    )
    val bar2 by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 340, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eqBar2",
    )
    val bar3 by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 510, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eqBar3",
    )

    Row(
        modifier = Modifier.height(12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (frac in listOf(bar1, bar2, bar3)) {
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .fillMaxHeight(frac.coerceIn(0.22f, 1f))
                    .clip(CircleShape)
                    .background(accent),
            )
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
internal fun MenuTabPill(
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
            color = if (selected) Color(0xFF101010) else if (hovered) Color.White else Color.White.copy(alpha = 0.72f),
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
                    color = if (isSelected) Color(0xFFFFFFFF) else if (isHovered) Color.White else Color.White.copy(alpha = 0.66f),
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
    folders: Set<String>,
    pinned: Set<String>,
    history: List<Heard>,
    palette: ArtworkPalette,
    currentMediaId: String?,
    onOpenLocal: (String) -> Unit,
    onOpenSmart: (LocalPlaylist) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenAllLists: () -> Unit,
    onPlayLocal: (LocalPlaylist, Int?) -> Unit,
    onPlayDj: () -> Unit,
    onPlayHeard: (Heard) -> Unit,
    onSaveTo: (Heard) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    var newFolderName by remember { mutableStateOf("") }
    val createFocusRequester = remember { FocusRequester() }
    val folderFocusRequester = remember { FocusRequester() }

    LaunchedEffect(showCreateDialog) {
        if (showCreateDialog) {
            kotlinx.coroutines.delay(100L)
            createFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(showFolderDialog) {
        if (showFolderDialog) {
            kotlinx.coroutines.delay(100L)
            folderFocusRequester.requestFocus()
        }
    }

    // Una carpeta existe aunque esté vacía: las que aún no tienen listas también salen.
    val folderLists = remember(playlists, folders) {
        val used = playlists.mapNotNullTo(HashSet()) { it.folder }
        folders.filter { it !in used }.sorted().map { it to emptyList<LocalPlaylist>() } +
            playlists.groupBy { it.folder }.filterKeys { it != null }
                .map { (folder, lists) -> folder!! to lists }
    }

    // Las listas que están dentro de una carpeta no se repiten sueltas: se abren desde su carpeta.
    val looseLists = remember(playlists) { playlists.filter { it.folder == null } }

    // Estante de 4: DJ, Me gusta y dos huecos para lo fijado (o, si no hay, las primeras listas).
    val shelfEntries = remember(looseLists, folderLists, pinned, history, likedList) {
        val chosen = mutableListOf<ListsEntry>()
        folderLists.filter { "d:" + it.first in pinned }.forEach { (folder, lists) ->
            if (chosen.size < 2) chosen.add(ListsEntry.Folder(folder, lists))
        }
        looseLists.filter { it.id in pinned }.forEach { pl ->
            if (chosen.size < 2) chosen.add(ListsEntry.Playlist(pl))
        }
        val used = chosen.mapNotNull { (it as? ListsEntry.Playlist)?.list?.id }.toSet()
        looseLists.filter { it.id !in used }.forEach { pl ->
            if (chosen.size < 2) chosen.add(ListsEntry.Playlist(pl))
        }
        if (chosen.size < 2) {
            val pool = (history + likedList.songs).distinctBy { it.id }
            val fallbacks = listOf(
                LocalPlaylist(id = "__smart_recent__", name = "En bucle", songs = pool.take(12)),
                LocalPlaylist(id = "__smart_night__", name = "Nocturna", songs = pool.drop(2).take(12).ifEmpty { pool.take(8) }),
            )
            fallbacks.forEach { if (chosen.size < 2) chosen.add(ListsEntry.Playlist(it)) }
        }
        chosen
    }
    val tileLists = looseLists
        .filter { pl -> shelfEntries.none { it is ListsEntry.Playlist && it.list.id == pl.id } }
        .take(6)

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
                    color = Color(0xFFFFFFFF),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                            .clickable { showCreateDialog = !showCreateDialog; showFolderDialog = false }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Color(0xFFFFFFFF), modifier = Modifier.size(15.dp))
                        Text("Nueva", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFFFFF))
                    }
                    var folderHovered by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(CircleShape)
                            .background(if (folderHovered) Color.White.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.08f))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .onPointerEvent(PointerEventType.Enter) { folderHovered = true }
                            .onPointerEvent(PointerEventType.Exit) { folderHovered = false }
                            .clickable { showFolderDialog = !showFolderDialog; showCreateDialog = false }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Rounded.CreateNewFolder, null, tint = Color(0xFFFFFFFF), modifier = Modifier.size(15.dp))
                        Text("Carpeta", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFFFFF))
                    }
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
                        textStyle = TextStyle(fontFamily = OtoFontFamily, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
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
                        Text("Crear", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF101010))
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

        // Creación inline de carpeta (Nueva → Carpeta)
        if (showFolderDialog) {
            item(key = "createFolderInline") {
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
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = OtoFontFamily, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(palette.primary),
                        decorationBox = { innerTextField ->
                            if (newFolderName.isBlank()) {
                                Text("Nombre de la nueva carpeta…", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
                            }
                            innerTextField()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(folderFocusRequester)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && newFolderName.isNotBlank()) {
                                    onCreateFolder(newFolderName.trim())
                                    newFolderName = ""
                                    showFolderDialog = false
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
                                if (newFolderName.isNotBlank()) {
                                    onCreateFolder(newFolderName.trim())
                                    newFolderName = ""
                                    showFolderDialog = false
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text("Crear", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF101010))
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { showFolderDialog = false; newFolderName = "" }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Cancelar", fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.70f))
                    }
                }
            }
        }

        // Carpetas: se abren como una página con sus listas dentro.
        if (folderLists.isNotEmpty()) {
            item(key = "foldersTitle") {
                Text(
                    text = "Carpetas",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.4).sp,
                    color = Color(0xFFFFFFFF),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item(key = "foldersGrid") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    folderLists.chunked(2).forEach { rowFolders ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowFolders.forEach { (folder, lists) ->
                                Box(Modifier.weight(1f)) {
                                    FolderTileDesktop(
                                        name = folder,
                                        lists = lists,
                                        accent = palette.primary,
                                        onClick = { onOpenFolder(folder) },
                                        onPlayDirect = {
                                            val songs = lists.flatMap { it.songs }.distinctBy { it.id }
                                            if (songs.isNotEmpty()) {
                                                onPlayLocal(LocalPlaylist(FOLDER_PREFIX + folder, folder, songs), null)
                                            }
                                        },
                                    )
                                }
                            }
                            if (rowFolders.size == 1) Spacer(Modifier.weight(1f))
                        }
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
                shelfEntries.forEach { entry ->
                    Box(Modifier.weight(1f)) {
                        when (entry) {
                            is ListsEntry.Folder -> FolderShelfCard(
                                name = entry.name,
                                lists = entry.lists,
                                accent = palette.primary,
                                onClick = { onOpenFolder(entry.name) },
                                onPlayDirect = {
                                    val songs = entry.lists.flatMap { it.songs }.distinctBy { it.id }
                                    if (songs.isNotEmpty()) onPlayLocal(LocalPlaylist(FOLDER_PREFIX + entry.name, entry.name, songs), null)
                                },
                            )
                            is ListsEntry.Playlist -> PlaylistShelfCard(
                                playlist = entry.list,
                                accent = palette.primary,
                                onClick = {
                                    if (entry.list.id.startsWith("__smart_")) onOpenSmart(entry.list)
                                    else onOpenLocal(entry.list.id)
                                },
                                onPlayDirect = { onPlayLocal(entry.list, null) },
                            )
                        }
                    }
                }
                repeat((2 - shelfEntries.size).coerceAtLeast(0)) {
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
                    color = Color(0xFFFFFFFF),
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
                color = Color(0xFFFFFFFF),
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
    folders: Set<String>,
    palette: ArtworkPalette,
    onBack: () -> Unit,
    onOpenLocal: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
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
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Volver",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = "Todas tus listas",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFFFFFFF),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("↑↓ Tu orden", "Listas", "Favoritas", "Carpetas").forEachIndexed { idx, label ->
                MenuTabPill(
                    label = label,
                    selected = filterIdx == idx,
                    accent = palette.primary,
                    onClick = { filterIdx = idx },
                )
            }
        }
        val folderEntries = remember(playlists, folders) {
            val used = playlists.mapNotNullTo(HashSet()) { it.folder }
            folders.filter { it !in used }.sorted().map { it to emptyList<LocalPlaylist>() } +
                playlists.groupBy { it.folder }.filterKeys { it != null }
                    .map { (folder, lists) -> folder!! to lists }
        }
        val looseLists = remember(playlists) { playlists.filter { it.folder == null } }
        val allItems = remember(likedList, looseLists, folderEntries, filterIdx) {
            when (filterIdx) {
                1 -> listOf(likedList) + looseLists
                2 -> listOf(likedList)
                3 -> emptyList()
                else -> folderEntries.map { ListsEntry.Folder(it.first, it.second) } +
                    (listOf(likedList) + looseLists).map { ListsEntry.Playlist(it) }
            }
        }
        val folderOnly = filterIdx == 3
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            if (folderOnly) {
                val pairs = folderEntries.chunked(2)
                itemsIndexed(pairs) { _, pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pair.forEach { (folder, lists) ->
                            Box(Modifier.weight(1f)) {
                                FolderTileDesktop(
                                    name = folder,
                                    lists = lists,
                                    accent = palette.primary,
                                    onClick = { onOpenFolder(folder) },
                                    onPlayDirect = {
                                        val songs = lists.flatMap { it.songs }.distinctBy { it.id }
                                        if (songs.isNotEmpty()) onPlayLocal(LocalPlaylist(FOLDER_PREFIX + folder, folder, songs))
                                    },
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (folderEntries.isEmpty()) {
                    item(key = "noFolders") {
                        Text(
                            "No tienes carpetas. Crea una con «Carpeta» en Tus listas.",
                            fontSize = 13.5.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 18.dp),
                        )
                    }
                }
            } else {
                val pairs = allItems.chunked(2)
                itemsIndexed(pairs) { _, pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pair.forEach { entry ->
                            Box(Modifier.weight(1f)) {
                                when (entry) {
                                    is ListsEntry.Folder -> FolderTileDesktop(
                                        name = entry.name,
                                        lists = entry.lists,
                                        accent = palette.primary,
                                        onClick = { onOpenFolder(entry.name) },
                                        onPlayDirect = {
                                            val songs = entry.lists.flatMap { it.songs }.distinctBy { it.id }
                                            if (songs.isNotEmpty()) onPlayLocal(LocalPlaylist(FOLDER_PREFIX + entry.name, entry.name, songs))
                                        },
                                    )
                                    is ListsEntry.Playlist -> PlaylistTileDesktop(
                                        playlist = entry.list,
                                        accent = palette.primary,
                                        onClick = { onOpenLocal(entry.list.id) },
                                        onPlayDirect = { onPlayLocal(entry.list) },
                                    )
                                }
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

/** Una entrada de «Todas tus listas»: una carpeta o una lista suelta. */
private sealed interface ListsEntry {
    data class Folder(val name: String, val lists: List<LocalPlaylist>) : ListsEntry
    data class Playlist(val list: LocalPlaylist) : ListsEntry
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
                .shadow(22.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black.copy(alpha = 0.65f))
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF2B2B2B),
                            Color(0xFF1E1E1E),
                            Color(0xFF121212),
                        ),
                        center = Offset.Unspecified,
                        radius = 280f,
                    ),
                )
                .border(
                    1.dp,
                    if (isHovered) accent.copy(alpha = 0.65f)
                    else Color.White.copy(alpha = 0.14f),
                    RoundedCornerShape(18.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(14.dp)) {
                val r = size.minDimension * 0.48f
                val center = Offset(size.width / 2, size.height / 2)
                // Sombra suave bajo el vinilo
                drawCircle(Color.Black.copy(alpha = 0.65f), r * 1.04f, center + Offset(0f, r * 0.05f))
                // Borde exterior biselado brillante del vinilo
                drawCircle(Color(0xFF2B2B2B), r, center)
                drawCircle(Color(0xFF0B0B0B), r * 0.985f, center)
                // Surcos concéntricos del vinilo con brillo
                var g = 0.38f
                while (g < 0.96f) {
                    drawCircle(Color.White.copy(alpha = 0.055f), r * g, center, style = Stroke(width = r * 0.012f))
                    g += 0.048f
                }
                // Brillo especular sobre el vinilo
                for (start in listOf(-65f, 115f)) {
                    drawArc(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), center, r),
                        start, 38f, useCenter = true, topLeft = center - Offset(r, r), size = Size(r * 2, r * 2),
                    )
                }
                // Galleta central en color --accent
                drawCircle(accent, r * 0.30f, center)
                rotate(angle, center) {
                    drawCircle(Color.White.copy(alpha = 0.90f), r * 0.04f, center + Offset(r * 0.19f, 0f))
                }
                drawCircle(Color(0xFF101010), r * 0.065f, center)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "DJ",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFFFFF),
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
                tint = Color(0xFF101010).copy(alpha = 0.90f),
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
                        .background(Color(0xFF101010))
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
            color = Color(0xFFFFFFFF),
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
private fun FolderShelfCard(
    name: String,
    lists: List<LocalPlaylist>,
    accent: Color,
    onClick: () -> Unit,
    onPlayDirect: () -> Unit,
) {
    var isHovered by remember { mutableStateOf(false) }
    val songs = remember(lists) { lists.flatMap { it.songs }.distinctBy { it.id } }
    val firstArt = songs.firstOrNull { !it.artwork.isNullOrBlank() }?.artwork
    var coverBmp by remember(firstArt) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(firstArt) {
        if (!firstArt.isNullOrBlank()) coverBmp = ArtworkLoader.load(firstArt).first
    }
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "folderScale",
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
                .background(Brush.linearGradient(listOf(Color(0xFF242424), Color(0xFF161616))))
                .border(1.dp, if (isHovered) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (coverBmp != null) {
                Image(coverBmp!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            }
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = if (coverBmp != null) Color.White.copy(alpha = 0.92f) else accent.copy(alpha = 0.85f),
                modifier = Modifier.size(56.dp),
            )
            if (isHovered && songs.isNotEmpty()) {
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
                    Icon(Icons.Rounded.PlayArrow, "Reproducir carpeta", tint = Color(0xFF101010), modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(folderSubtitle(lists), fontSize = 12.sp, color = Color.White.copy(alpha = 0.58f), maxLines = 1)
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
                            Color(0xFF222222),
                            Color(0xFF161616),
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
                        tint = Color(0xFF101010),
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
            color = Color(0xFFFFFFFF),
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
                        Brush.linearGradient(listOf(Color(0xFF242424), Color(0xFF161616)))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                playlist.id == LIKED_ID -> {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = Color(0xFF101010),
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
                color = Color(0xFFFFFFFF),
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
                    tint = Color(0xFF101010),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FolderTileDesktop(
    name: String,
    lists: List<LocalPlaylist>,
    accent: Color,
    onClick: () -> Unit,
    onPlayDirect: () -> Unit,
) {
    var isHovered by remember { mutableStateOf(false) }
    val songs = remember(lists) { lists.flatMap { it.songs }.distinctBy { it.id } }
    val firstArt = songs.firstOrNull { !it.artwork.isNullOrBlank() }?.artwork
    var coverBmp by remember(firstArt) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(firstArt) {
        if (!firstArt.isNullOrBlank()) coverBmp = ArtworkLoader.load(firstArt).first
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isHovered) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.045f))
            .border(
                1.dp,
                if (isHovered) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.07f),
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
                .background(Brush.linearGradient(listOf(Color(0xFF242424), Color(0xFF161616)))),
            contentAlignment = Alignment.Center,
        ) {
            if (coverBmp != null) {
                Image(coverBmp!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            }
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = if (coverBmp != null) Color.White.copy(alpha = 0.92f) else accent,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFFFFF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = folderSubtitle(lists),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.56f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isHovered && songs.isNotEmpty()) {
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
                    contentDescription = "Reproducir carpeta",
                    tint = Color(0xFF101010),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun folderSubtitle(lists: List<LocalPlaylist>): String {
    val songs = lists.sumOf { it.songs.size }
    val folderCount = when (lists.size) {
        1 -> "1 lista"
        else -> "${lists.size} listas"
    }
    return "Carpeta · $folderCount · $songs canciones"
}

@Composable
private fun SortRow(newest: Boolean, onFlip: () -> Unit) {
    val turn by animateFloatAsState(
        targetValue = if (newest) 0f else 180f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "sortTurn",
    )
    Row(
        modifier = Modifier
            .padding(bottom = 6.dp)
            .clip(CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onFlip)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.Sort,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.size(18.dp).graphicsLayer { rotationX = turn },
        )
        AnimatedContent(newest, label = "sortLabel") { n ->
            Text(
                text = if (n) "Lo último que añadiste, arriba" else "Lo primero que añadiste, arriba",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.65f),
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FolderDetailPane(
    name: String,
    lists: List<LocalPlaylist>,
    allPlaylists: List<LocalPlaylist>,
    palette: ArtworkPalette,
    currentMediaId: String?,
    onBack: () -> Unit,
    onOpenLocal: (String) -> Unit,
    onPlayFolder: (List<Heard>) -> Unit,
    onToggleInside: (LocalPlaylist, Boolean) -> Unit,
    onRename: (String) -> Unit,
    onUngroup: () -> Unit,
    pinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var filling by remember { mutableStateOf(false) }
    var nameInput by remember(name) { mutableStateOf(name) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(editing) {
        if (editing) {
            kotlinx.coroutines.delay(120L)
            focusRequester.requestFocus()
        }
    }

    val songs = remember(lists) { lists.flatMap { it.songs }.distinctBy { it.id } }
    val cover = songs.firstOrNull { !it.artwork.isNullOrBlank() }?.artwork
    var coverBmp by remember(cover) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cover) {
        if (!cover.isNullOrBlank()) coverBmp = ArtworkLoader.load(cover).first
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item(key = "head") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Volver",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF2A2A2A), Color(0xFF171717))))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (coverBmp != null) {
                        Image(coverBmp!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
                    }
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = if (coverBmp != null) Color.White.copy(alpha = 0.95f) else palette.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = name,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp,
                        color = Color(0xFFFFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = folderSubtitle(lists),
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.60f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (songs.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .height(44.dp)
                                    .clip(CircleShape)
                                    .background(palette.primary)
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable { onPlayFolder(songs) }
                                    .padding(horizontal = 22.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF101010), modifier = Modifier.size(20.dp))
                                Text("Reproducir", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF101010))
                            }
                        }
                        Box {
                            SheetCircleIconButton(
                                icon = Icons.Rounded.MoreVert,
                                tooltip = "Opciones de carpeta",
                                active = showMenu,
                                accent = palette.primary,
                                onClick = { showMenu = true },
                            )
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier
                                    .background(Color(0xFF171717))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                            ) {
                                if (onTogglePin != null) {
                                    DropdownMenuItem(
                                        text = { Text(if (pinned) "Quitar de arriba" else "Fijar arriba", color = Color(0xFFFFFFFF), fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Rounded.PushPin, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showMenu = false
                                            onTogglePin()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Añadir o quitar listas", color = Color(0xFFFFFFFF), fontSize = 13.sp) },
                                    leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        showMenu = false
                                        editing = false
                                        filling = !filling
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Cambiar el nombre", color = Color(0xFFFFFFFF), fontSize = 13.sp) },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        showMenu = false
                                        filling = false
                                        nameInput = name
                                        editing = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Deshacer la carpeta", color = Color(0xFFFF6E6E), fontSize = 13.sp) },
                                    leadingIcon = { Icon(Icons.Rounded.Folder, null, tint = Color(0xFFFF6E6E), modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        showMenu = false
                                        onUngroup()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (editing) {
            item(key = "rename") {
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
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = OtoFontFamily, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(palette.primary),
                        decorationBox = { innerTextField ->
                            if (nameInput.isBlank()) {
                                Text("Nombre de la carpeta…", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
                            }
                            innerTextField()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && nameInput.isNotBlank()) {
                                    onRename(nameInput.trim())
                                    editing = false
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
                                if (nameInput.isNotBlank()) {
                                    onRename(nameInput.trim())
                                    editing = false
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text("Guardar", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF101010))
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { editing = false; nameInput = name }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Cancelar", fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.70f))
                    }
                }
            }
        }

        if (filling) {
            item(key = "fill") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.055f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                        .padding(vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Marca las listas que van dentro",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                        MenuTabPill("Listo", selected = false, accent = palette.primary) { filling = false }
                    }
                    if (allPlaylists.isEmpty()) {
                        Text(
                            "Aún no tienes listas.",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    allPlaylists.forEach { pl ->
                        val inside = pl.folder == name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { onToggleInside(pl, !inside) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (inside) palette.primary else Color.White.copy(alpha = 0.10f))
                                    .border(1.dp, if (inside) palette.primary else Color.White.copy(alpha = 0.18f), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (inside) {
                                    Icon(Icons.Rounded.Check, null, tint = Color(0xFF101010), modifier = Modifier.size(14.dp))
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = pl.folder?.takeIf { !inside }?.let { "En «$it»" } ?: "${pl.songs.size} canciones",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.55f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (lists.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 46.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(40.dp), tint = Color.White.copy(alpha = 0.32f))
                    Text("Está vacía", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f))
                    Text(
                        "Añade listas con el botón de arriba, o desde el menú (···) de cada lista.",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
            }
        } else {
            items(lists, key = { it.id }) { pl ->
                val first = pl.songs.firstOrNull { !it.artwork.isNullOrBlank() }?.artwork
                var bmp by remember(first) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(first) {
                    if (!first.isNullOrBlank()) bmp = ArtworkLoader.load(first).first
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onOpenLocal(pl.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1A1A1A)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bmp != null) {
                            Image(bmp!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(22.dp), tint = palette.primary)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(pl.name, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pl.songs.size} canciones", fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.56f), maxLines = 1)
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(18.dp),
                    )
                }
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
    onPlayIndex: (Int?, Boolean) -> Unit,
    onSaveTo: (Heard) -> Unit = {},
    onDeletePlaylist: (() -> Unit)? = null,
    onRemoveSong: ((String) -> Unit)? = null,
    prefs: Prefs? = null,
    downloads: DesktopDownloads? = null,
    downloadLists: Set<String> = emptySet(),
    folders: Set<String> = emptySet(),
    pinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onMoveToFolder: ((String?) -> Unit)? = null,
    onCreateFolderAndMove: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var showMovePanel by remember { mutableStateOf(false) }
    var moveNewFolder by remember { mutableStateOf("") }
    val sortFlipped by prefs?.sortFlipped?.collectAsState(initial = emptySet()) ?: remember { mutableStateOf(emptySet()) }
    val requestedSongs by (prefs?.downloadSongs?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList()) })
    val normal = list.id == LIKED_ID
    val flipped = list.id in sortFlipped
    val newest = if (normal) !flipped else flipped
    val shownSongs = remember(list.songs, newest) {
        if (newest) list.songs.reversed() else list.songs
    }

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
                            Brush.linearGradient(listOf(Color(0xFF242424), Color(0xFF161616)))
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
                            tint = Color(0xFF101010),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = list.name,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp,
                        color = Color(0xFFFFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Botón ATRÁS desnudo (sin fondo ni borde, solo la flechita) a la altura del título arriba derecha
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Volver",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
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
                            .clickable { onPlayIndex(0, newest) }
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF101010),
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Reproducir",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF101010),
                        )
                    }

                    SheetCircleIconButton(
                        icon = Icons.Rounded.Shuffle,
                        tooltip = "Aleatorio",
                        active = false,
                        accent = palette.primary,
                        onClick = { onPlayIndex(null, newest) },
                    )

                    if (onDeletePlaylist != null || downloads != null || onMoveToFolder != null || onTogglePin != null) {
                        var showPlaylistMenu by remember { mutableStateOf(false) }
                        Box {
                            SheetCircleIconButton(
                                icon = Icons.Rounded.MoreVert,
                                tooltip = "Opciones de lista",
                                active = showPlaylistMenu,
                                accent = palette.primary,
                                onClick = { showPlaylistMenu = true },
                            )
                            DropdownMenu(
                                expanded = showPlaylistMenu,
                                onDismissRequest = { showPlaylistMenu = false },
                                modifier = Modifier
                                    .background(Color(0xFF171717))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                            ) {
                                if (downloads != null) {
                                    val isDownloaded = list.id in downloadLists
                                    DropdownMenuItem(
                                        text = { Text(if (isDownloaded) "Quitar descarga" else "Descargar lista", color = Color(0xFFFFFFFF), fontSize = 13.sp) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        onClick = {
                                            showPlaylistMenu = false
                                            scope.launch { prefs?.setDownloadList(list.id, !isDownloaded) }
                                        },
                                    )
                                }
                                if (onTogglePin != null) {
                                    DropdownMenuItem(
                                        text = { Text(if (pinned) "Quitar de arriba" else "Fijar arriba", color = Color(0xFFFFFFFF), fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showPlaylistMenu = false
                                            onTogglePin()
                                        },
                                    )
                                }
                                if (onMoveToFolder != null) {
                                    DropdownMenuItem(
                                        text = { Text("Mover a carpeta…", color = Color(0xFFFFFFFF), fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showPlaylistMenu = false
                                            moveNewFolder = ""
                                            showMovePanel = !showMovePanel
                                        },
                                    )
                                }
                                if (onDeletePlaylist != null) {
                                    DropdownMenuItem(
                                        text = { Text("Eliminar playlist", color = Color(0xFFFF6E6E), fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF6E6E), modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showPlaylistMenu = false
                                            onDeletePlaylist()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showMovePanel && onMoveToFolder != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.055f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (list.folder == null) "Elige en qué carpeta va" else "Ahora en «${list.folder}»",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                    MenuTabPill("Listo", selected = false, accent = palette.primary) { showMovePanel = false }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MenuTabPill(
                        label = "Sin carpeta",
                        selected = list.folder == null,
                        accent = palette.primary,
                    ) { onMoveToFolder(null) }
                    folders.sorted().forEach { folder ->
                        MenuTabPill(
                            label = folder,
                            selected = list.folder == folder,
                            accent = palette.primary,
                        ) { onMoveToFolder(folder) }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = moveNewFolder,
                        onValueChange = { moveNewFolder = it },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = OtoFontFamily, color = Color.White, fontSize = 13.5.sp),
                        cursorBrush = SolidColor(palette.primary),
                        decorationBox = { innerTextField ->
                            if (moveNewFolder.isBlank()) {
                                Text("Nueva carpeta…", color = Color.White.copy(alpha = 0.40f), fontSize = 13.5.sp)
                            }
                            innerTextField()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && moveNewFolder.isNotBlank()) {
                                    onCreateFolderAndMove?.invoke(moveNewFolder.trim())
                                    moveNewFolder = ""
                                    showMovePanel = false
                                    true
                                } else false
                            },
                    )
                    MenuTabPill("Crear y mover", selected = false, accent = palette.primary) {
                        if (moveNewFolder.isNotBlank()) {
                            onCreateFolderAndMove?.invoke(moveNewFolder.trim())
                            moveNewFolder = ""
                            showMovePanel = false
                        }
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
                if (list.songs.size > 1) {
                    item(key = "sort") {
                        SortRow(
                            newest = newest,
                            onFlip = {
                                scope.launch { prefs?.flipSort(list.id) }
                            },
                        )
                    }
                }
                itemsIndexed(shownSongs, key = { idx, s -> "${s.id}-$idx" }) { idx, s ->
                    SongRowDesktop(
                        title = s.title,
                        subtitle = s.artist,
                        thumbnail = s.artwork,
                        isCurrent = s.id == currentMediaId,
                        accent = palette.primary,
                        leadingIndex = idx + 1,
                        onClick = { onPlayIndex(idx, newest) },
                        onSaveTo = { onSaveTo(s) },
                        trailing = {
                            var showSongMenu by remember { mutableStateOf(false) }
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable { showSongMenu = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = "Opciones",
                                        tint = Color.White.copy(alpha = 0.65f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSongMenu,
                                    onDismissRequest = { showSongMenu = false },
                                    modifier = Modifier
                                        .background(Color(0xFF171717))
                                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Guardar en otra lista…", color = Color(0xFFFFFFFF), fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showSongMenu = false
                                            onSaveTo(s)
                                        },
                                    )
                                    if (downloads != null) {
                                        val isDownloaded = requestedSongs.any { it.id == s.id }
                                        DropdownMenuItem(
                                            text = { Text(if (isDownloaded) "Quitar descarga" else "Descargar", color = Color(0xFFFFFFFF), fontSize = 13.sp) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (isDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                                                    contentDescription = null,
                                                    tint = Color.White.copy(alpha = 0.8f),
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            },
                                            onClick = {
                                                showSongMenu = false
                                                scope.launch { prefs?.setDownloadSong(s, !isDownloaded) }
                                            },
                                        )
                                    }
                                    if (onRemoveSong != null) {
                                        DropdownMenuItem(
                                            text = { Text("Quitar de esta lista", color = Color(0xFFFF6E6E), fontSize = 13.sp) },
                                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color(0xFFFF6E6E), modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                showSongMenu = false
                                                onRemoveSong(s.id)
                                            },
                                        )
                                    }
                                }
                            }
                        },
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
    val isArtist = page.item is ArtistItem
    val shape = if (isArtist) CircleShape else RoundedCornerShape(20.dp)
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
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(if (isArtist) 130.dp else 116.dp)
                    .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.6f))
                    .clip(shape)
                    .background(Color(0xFF1B1B1B))
                    .border(
                        if (isArtist) 2.dp else 1.dp,
                        if (isArtist) Brush.sweepGradient(listOf(palette.primary, palette.secondary, palette.primary)) else SolidColor(Color.White.copy(alpha = 0.12f)),
                        shape,
                    ),
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
                        imageVector = if (isArtist) Icons.Rounded.Person else Icons.Rounded.Album,
                        contentDescription = null,
                        tint = palette.primary,
                        modifier = Modifier.size(if (isArtist) 54.dp else 48.dp),
                    )
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isArtist) {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(palette.primary.copy(alpha = 0.16f))
                                .border(1.dp, palette.primary.copy(alpha = 0.40f), CircleShape)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = "ARTISTA",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp,
                                color = palette.primary,
                            )
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    // Botón ATRÁS desnudo (sin fondo ni borde, solo la flechita) a la altura del título arriba derecha
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Volver",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Text(
                    text = page.item.title,
                    fontSize = if (isArtist) 30.sp else 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    color = Color(0xFFFFFFFF),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                page.sub?.let {
                    Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.65f))
                }
                if (!songs.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .height(42.dp)
                                .clip(CircleShape)
                                .background(palette.primary)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { onPlayAll(songs, 0) }
                                .padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color(0xFF101010),
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Reproducir",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF101010),
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                if (!songs.isNullOrEmpty()) {
                    if (isArtist) {
                        item {
                            Text(
                                text = "Canciones populares",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFFFFF),
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                            )
                        }
                    }
                    itemsIndexed(songs, key = { i, s -> "${s.id}-$i" }) { idx, s ->
                        SongRowDesktop(
                            title = s.title,
                            subtitle = s.artists.joinToString { it.name },
                            thumbnail = s.thumbnail,
                            isCurrent = s.id == currentMediaId,
                            accent = palette.primary,
                            leadingIndex = idx + 1,
                            onClick = { onPlayAll(songs, idx) },
                            onSaveTo = { onSaveTo(s.toHeard()) },
                        )
                    }
                }
                if (shelves.isNotEmpty()) {
                    items(shelves, key = { it.title }) { shelf ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(shelf.title, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFFFFFF))
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
                            color = Color(0xFFFFFFFF),
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
                .background(Color(0xFF181818))
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
                        tint = Color(0xFF101010),
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
            color = Color(0xFFFFFFFF),
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
                            color = Color(0xFFFFFFFF),
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
                                color = Color(0xFFFFFFFF),
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
                .background(Color(0xFF1C1C1C)),
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
                color = Color(0xFFFFFFFF),
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
                tint = Color(0xFF101010),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
internal fun QueuePane(
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
    onClose: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Cola de reproducción",
                    style = OtoType.section,
                    color = Color.White,
                )
                Text(
                    text = if (queue.isEmpty()) "Nada en cola" else "${queue.size} canciones en total",
                    style = OtoType.rowSub,
                    color = Color.White.copy(alpha = 0.62f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (queue.size > 1) {
                    MenuTabPill(
                        label = "Vaciar",
                        selected = false,
                        accent = Color(0xFFFF5252),
                        onClick = onClearQueue,
                    )
                }
                SheetCircleIconButton(
                    icon = Icons.Rounded.Shuffle,
                    tooltip = if (shuffle) "Aleatorio activo" else "Activar aleatorio",
                    active = shuffle,
                    accent = palette.primary,
                    onClick = onToggleShuffle,
                )
                SheetCircleIconButton(
                    icon = Icons.Rounded.Repeat,
                    tooltip = if (loop) "Bucle activo" else "Activar bucle",
                    active = loop,
                    accent = palette.primary,
                    onClick = onToggleLoop,
                )
                if (onClose != null) {
                    SheetCircleIconButton(
                        icon = Icons.Rounded.Close,
                        tooltip = "Cerrar (Esc)",
                        active = false,
                        accent = palette.primary,
                        onClick = onClose,
                    )
                }
            }
        }
        if (queue.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.30f),
                        modifier = Modifier.size(38.dp),
                    )
                    Text(
                        text = "La cola está vacía",
                        style = OtoType.rowTitle,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    Text(
                        text = "Pon algo a sonar y aquí verás lo que viene después.",
                        style = OtoType.rowSub,
                        color = Color.White.copy(alpha = 0.52f),
                    )
                }
            }
        } else {
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
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(artwork) {
        if (!artwork.isNullOrBlank()) {
            thumbBmp = ArtworkLoader.load(artwork).first
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                translationY = dragOffsetY
                shadowElevation = if (isDragging) 12f else 0f
            }
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
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR)))
                    .pointerInput(canMoveUp, canMoveDown) {
                        detectVerticalDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = { isDragging = false; dragOffsetY = 0f },
                            onDragCancel = { isDragging = false; dragOffsetY = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            dragOffsetY += dragAmount
                            val step = 44f
                            if (dragOffsetY > step && canMoveDown) {
                                onMoveDown()
                                dragOffsetY -= step
                            } else if (dragOffsetY < -step && canMoveUp) {
                                onMoveUp()
                                dragOffsetY += step
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = "Mover manteniendo",
                    tint = if (isDragging) accent else Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp),
                )
            }
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
    shuffle: Boolean,
    loop: Boolean,
    onSetShuffle: (Boolean) -> Unit,
    onSetLoop: (Boolean) -> Unit,
    palette: ArtworkPalette,
    onOpenImport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val crossfade by prefs.crossfade.collectAsState(initial = 3)
    val showRemaining by prefs.showRemaining.collectAsState(initial = false)
    val romanize by prefs.romanize.collectAsState(initial = true)
    val coverBeat by prefs.coverBeat.collectAsState(initial = true)

    var loginMode by remember { mutableIntStateOf(0) } // 0 = Navegador / Consola, 1 = Pegar cookie / cURL
    var cookieInput by remember { mutableStateOf("") }
    var cookieFeedback by remember { mutableStateOf<String?>(null) }
    var connectingCookie by remember { mutableStateOf(false) }
    var snippetCopied by remember { mutableStateOf(false) }

    var cacheSizeBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val f = OtoDirs.cacheDir
            cacheSizeBytes = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }
    val cacheFormatted = remember(cacheSizeBytes) {
        val mb = cacheSizeBytes / (1024L * 1024L)
        if (mb >= 1024L) "%.1f GB".format(mb / 1024f) else "$mb MB"
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // 1. CUENTA DE YOUTUBE MUSIC
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, palette.primary.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.CloudDone, null, tint = palette.primary)
                    Text("Cuenta de YouTube Music", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                if (session != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(palette.primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Person, null, tint = palette.primary, modifier = Modifier.size(24.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(session.name.ifBlank { "Conectado" }, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            session.email?.let {
                                Text(it, fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f))
                            }
                            Text(
                                when (cloudState) {
                                    CloudState.Off -> "Copia en la nube: desactivada"
                                    is CloudState.Working -> "Sincronizando: ${cloudState.what}…"
                                    is CloudState.Done -> "Guardadas en tu cuenta (${if (cloudState.lists == 1) "1 lista" else "${cloudState.lists} listas"} privadas)"
                                    is CloudState.Failed -> "No se pudo sincronizar: ${cloudState.why}"
                                },
                                fontSize = 11.sp,
                                color = palette.primary.copy(alpha = 0.85f),
                            )
                        }
                        MenuTabPill("Cerrar sesión", false, palette.secondary) {
                            scope.launch { account.signOut(); cloud.forget() }
                        }
                    }
                } else {
                    Text(
                        "Explorar y la radio salen de tus gustos, lo que oigas cuenta en tu historial de YouTube y lo que te guste en Oto también le gustará a YouTube Music.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.65f),
                    )

                    // Inicio de sesión: Chrome temporal con CDP (lo normal) o pegar la cookie a mano.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MenuTabPill("Entrar con Google", loginMode == 0, palette.primary) { loginMode = 0 }
                        MenuTabPill("Pegar cookie", loginMode == 1, palette.primary) { loginMode = 1 }
                    }

                    if (loginMode == 0) {
                        val loginState by login.state.collectAsState()
                        MenuTabPill("Entrar con Google", true, palette.primary) {
                            snippetCopied = false
                            login.start()
                        }

                        when (val ls = loginState) {
                            is LoginState.Waiting -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(palette.primary.copy(alpha = 0.08f))
                                        .border(1.dp, palette.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        if (ls.automated) {
                                            "Se ha abierto Chrome. Entra en YouTube Music con tu cuenta; Oto leerá la sesión y cerrará esa ventana solo."
                                        } else {
                                            "Se ha abierto el navegador. Pega el comando de abajo en la consola (F12) o instala la extensión."
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                    )
                                    if (!ls.automated) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            MenuTabPill(
                                                if (snippetCopied) "✓ Comando copiado" else "Copiar comando (F12)",
                                                snippetCopied,
                                                palette.primary,
                                            ) {
                                                val snippet = login.getConsoleSnippet()
                                                if (snippet != null) {
                                                    val sel = StringSelection(snippet)
                                                    Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                                                    snippetCopied = true
                                                }
                                            }
                                            MenuTabPill("Instalar extensión", false, palette.secondary) { login.extractExtension() }
                                        }
                                    }
                                    MenuTabPill("Cancelar", false, palette.secondary) {
                                        login.cancel()
                                    }
                                }
                            }
                            is LoginState.Failed -> {
                                Text(ls.why, fontSize = 12.sp, color = palette.secondary)
                            }
                            LoginState.Done, LoginState.Idle -> Unit
                        }
                    } else {
                        // Método 1: Pegar cookie o cURL
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "En music.youtube.com con tu sesión: F12 → Red (Network) → clic en cualquier petición → Copiar como cURL (o copiar valor de Cookie) y pégalo abajo:",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.65f),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .border(1.dp, if (cookieFeedback != null) palette.secondary.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                BasicTextField(
                                    value = cookieInput,
                                    onValueChange = { cookieInput = it; cookieFeedback = null },
                                    singleLine = true,
                                    textStyle = TextStyle(fontFamily = OtoFontFamily, color = Color.White, fontSize = 13.sp),
                                    cursorBrush = SolidColor(palette.primary),
                                    decorationBox = { inner ->
                                        if (cookieInput.isBlank()) {
                                            Text("Pega aquí las cookies (con SAPISID) o el comando cURL…", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
                                        }
                                        inner()
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                MenuTabPill(if (connectingCookie) "Conectando…" else "Conectar", true, palette.primary) {
                                    if (cookieInput.isNotBlank() && !connectingCookie) {
                                        connectingCookie = true
                                        cookieFeedback = null
                                        scope.launch {
                                            login.signInWithCookie(cookieInput)
                                                .onSuccess {
                                                    cookieInput = ""
                                                    connectingCookie = false
                                                }
                                                .onFailure {
                                                    cookieFeedback = it.message ?: "No se pudo conectar"
                                                    connectingCookie = false
                                                }
                                        }
                                    }
                                }
                            }
                            cookieFeedback?.let {
                                Text(it, fontSize = 12.sp, color = palette.secondary)
                            }
                        }
                    }
                }
            }
        }

        // 2. IMPORTAR DE SPOTIFY
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
                    Text("Importar de Spotify", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Text(
                    importSummary(importState),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.65f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MenuTabPill("Abrir importador completo…", true, palette.secondary) {
                        onOpenImport()
                    }
                }
            }
        }

        // 3. AUDIO Y CALIDAD
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
                Text(
                    if (qualityMode == QualityMode.Auto) "La mejor calidad que aguante tu conexión. Se elige al empezar cada canción."
                    else "Siempre la mejor calidad disponible (Opus 256/160k).",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.55f),
                )
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

        // 4. REPRODUCCIÓN (SHUFFLE Y LOOP)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Reproducción", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuTabPill(if (shuffle) "Aleatorio: activo" else "Aleatorio: apagado", shuffle, palette.primary) {
                        onSetShuffle(!shuffle)
                    }
                    MenuTabPill(if (loop) "Repetir: activo" else "Repetir: apagado", loop, palette.primary) {
                        onSetLoop(!loop)
                    }
                }
                Text(
                    if (loop) "Al acabar la lista o cola de reproducción, vuelve a empezar desde la primera."
                    else "Al acabar la lista, continúan recomendaciones automáticas parecidas.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }

        // 5. PANTALLA Y VISUALIZACIÓN
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Pantalla y visualización", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuTabPill(
                        if (showRemaining) "Tiempo: restante (-1:23)" else "Tiempo: total (3:45)",
                        showRemaining,
                        palette.primary,
                    ) {
                        scope.launch { prefs.setShowRemaining(!showRemaining) }
                    }
                    MenuTabPill(
                        if (romanize) "Romanización de letras: sí" else "Romanización: no",
                        romanize,
                        palette.secondary,
                    ) {
                        scope.launch { prefs.setRomanize(!romanize) }
                    }
                    MenuTabPill(
                        if (coverBeat) "Carátula al ritmo: sí" else "Carátula al ritmo: no",
                        coverBeat,
                        palette.tertiary,
                    ) {
                        scope.launch { prefs.setCoverBeat(!coverBeat) }
                    }
                }
                Text(
                    "Personaliza cómo se muestran los tiempos en la barra de progreso y la pronunciación en canciones en japonés, coreano o chino.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }

        // 6. ALMACENAMIENTO Y CACHÉ
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, null, tint = palette.primary)
                    Text("Almacenamiento y caché", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Caché de canciones y carátulas:", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                        Text(cacheFormatted, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = palette.primary)
                    }
                    MenuTabPill("Limpiar caché", false, palette.secondary) {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                OtoDirs.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                                cacheSizeBytes = 0L
                            }
                        }
                    }
                }
                // «Espacio en el móvil» del móvil, pero con el disco del ordenador: lo descargado no se toca.
                SpaceRowDesktop(palette = palette)
            }
        }

        // 7. ATAJOS DE TECLADO
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

        // 8. ACERCA DE OTO
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Oto 1.2.0 · GPL-3.0",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.50f),
                )
                Text(
                    "Con InnerTubeX y partes de Metrolist. Letras de lyricsplus, NetEase, LRCLIB y YouTube Music.\ngithub.com/iaguito22/oto",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.35f),
                )
            }
        }
    }
}

/** Lo que ocupa el disco del ordenador, con una barra hasta su total. */
@Composable
private fun SpaceRowDesktop(palette: ArtworkPalette) {
    var used by remember { mutableStateOf<Long?>(null) }
    var total by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val root = java.io.File(System.getProperty("user.home") ?: "/")
            total = root.totalSpace
            used = (root.totalSpace - root.usableSpace).coerceAtLeast(0L)
        }
    }
    val fill by animateFloatAsState(
        targetValue = if (total > 0L) ((used ?: 0L).toFloat() / total).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(Motion.SPATIAL, easing = Motion.Standard),
        label = "diskSpace",
    )
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FolderOpen, null, tint = palette.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Espacio en el disco", style = OtoType.rowTitle, color = Color.White)
        }
        Text(
            text = when (val u = used) {
                null -> "Midiendo…"
                else -> "${humanBytes(u)} usados de ${humanBytes(total)}. Lo descargado no se toca."
            },
            style = OtoType.rowSub,
            color = Color.White.copy(alpha = 0.62f),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fill)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.62f)),
            )
        }
    }
}

private fun humanBytes(n: Long): String =
    if (n >= 1L shl 30) "%.1f GB".format(n / (1L shl 30).toDouble())
    else "${(n + (1L shl 19)) shr 20} MB"

private fun importSummary(s: ImportState): String = when (s) {
    ImportState.Idle -> "Trae tus listas públicas, privadas y «Canciones que te gustan» desde Spotify."
    is ImportState.Running -> "Importando: ${if (s.total > 0) (s.done * 100 / s.total) else 0}% · lista ${s.index + 1} de ${s.lists} (${s.list})"
    is ImportState.Done -> if (s.stopped) "Parado a medias: ${s.songs} canciones guardadas" else "Hecho: ${s.lists} listas y ${s.songs} canciones importadas"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportPane(
    importer: Importer,
    prefs: Prefs,
    palette: ArtworkPalette,
    onBack: () -> Unit,
    onLists: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val importState by importer.state.collectAsState()
    val playlists by prefs.playlists.collectAsState(initial = emptyList())
    val importedFromIds = remember(playlists) { playlists.mapNotNull { it.from }.toSet() }

    var loaded by remember { mutableStateOf<List<SourceList>?>(null) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }

    val step = when {
        importState is ImportState.Running -> 2
        loaded != null -> 1
        importState is ImportState.Done -> 3
        else -> 0
    }

    Column(Modifier.fillMaxSize()) {
        when (step) {
            0 -> {
                // PASO 0: FUENTE (ENLACE O ARCHIVO)
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                            Text("Una lista o un álbum de Spotify", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "Pega su enlace público (Compartir → Copiar enlace). Sin sesión en Spotify, se leen hasta 100 canciones.",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.65f),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                BasicTextField(
                                    value = linkText,
                                    onValueChange = { linkText = it; error = null },
                                    singleLine = true,
                                    textStyle = TextStyle(fontFamily = OtoFontFamily, color = Color.White, fontSize = 13.sp),
                                    cursorBrush = SolidColor(palette.primary),
                                    decorationBox = { inner ->
                                        if (linkText.isBlank()) {
                                            Text("open.spotify.com/playlist/…", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
                                        }
                                        inner()
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                MenuTabPill(if (reading) "Leyendo…" else "Cargar enlace", true, palette.primary) {
                                    if (linkText.isNotBlank() && !reading) {
                                        reading = true
                                        error = null
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            Spotify.fromLink(linkText.trim())
                                                .onSuccess {
                                                    loaded = listOf(it)
                                                    picked = setOf(it.id)
                                                    reading = false
                                                }
                                                .onFailure {
                                                    error = it.message ?: "No se pudo leer el enlace de Spotify"
                                                    reading = false
                                                }
                                        }
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
                                .border(1.dp, palette.secondary.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Todas tus listas (archivo oto-spotify.json)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "1. En el navegador o en Spotify con la extensión «Exportar a Oto», descarga tu archivo oto-spotify.json.\n" +
                                "2. Selecciónalo aquí para importar todas tus listas privadas, públicas y «Canciones que te gustan».",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.65f),
                            )
                            MenuTabPill("Elegir archivo oto-spotify.json…", true, palette.secondary) {
                                val fd = FileDialog(null as Frame?, "Seleccionar oto-spotify.json", FileDialog.LOAD)
                                fd.isVisible = true
                                val file = fd.file?.let { File(fd.directory, it) }
                                if (file != null && file.exists()) {
                                    reading = true
                                    error = null
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching { Spotify.parseExport(file.readText()) }
                                            .onSuccess { lists ->
                                                loaded = lists
                                                picked = lists.filter { it.own || it.liked }.mapTo(HashSet()) { it.id }
                                                reading = false
                                            }
                                            .onFailure {
                                                error = "El archivo no es un oto-spotify.json válido (${it.message})"
                                                reading = false
                                            }
                                    }
                                }
                            }
                        }
                    }

                    if (error != null) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(palette.secondary.copy(alpha = 0.15f))
                                    .border(1.dp, palette.secondary.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                    .padding(14.dp),
                            ) {
                                Text(error!!, color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            1 -> {
                // PASO 1: ELEGIR QUÉ LISTAS TRAER (PICK)
                val lists = loaded.orEmpty()
                val chosen = lists.filter { it.id in picked }
                val uniqueCount = remember(lists, picked) {
                    picked.flatMap { id ->
                        lists.firstOrNull { it.id == id }?.tracks?.map { "${it.title}|${it.artists.firstOrNull()}" } ?: emptyList()
                    }.toSet().size
                }
                val estMinutes = remember(uniqueCount) {
                    val m = (uniqueCount / 9 / 60) + 1
                    if (m <= 1) "menos de 1 min" else "unos $m min"
                }

                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "ELIGE QUÉ TRAER (${lists.size} listas)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.60f),
                        )
                        val allPicked = picked.size == lists.size
                        MenuTabPill(if (allPicked) "Ninguna" else "Todas", false, palette.primary) {
                            picked = if (allPicked) emptySet() else lists.mapTo(HashSet()) { it.id }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(lists, key = { it.id }) { l ->
                            val isChecked = l.id in picked
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isChecked) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.03f))
                                    .border(
                                        1.dp,
                                        if (isChecked) palette.primary.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f),
                                        RoundedCornerShape(14.dp),
                                    )
                                    .clickable {
                                        picked = if (isChecked) picked - l.id else picked + l.id
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(if (isChecked) palette.primary else Color.White.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isChecked) {
                                        Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                    }
                                }

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (l.liked) "Canciones que te gustan" else l.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                    )
                                    val subParts = listOfNotNull(
                                        "${l.tracks.size} canciones",
                                        "a Me gusta".takeIf { l.liked },
                                        "la sigues".takeIf { !l.own && !l.liked },
                                        "ya importada".takeIf { l.id in importedFromIds },
                                        "solo las primeras 100".takeIf { l.partial },
                                    ).joinToString(" · ")
                                    Text(subParts, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
                                }
                            }
                        }
                    }

                    // Barra inferior de confirmación
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("$uniqueCount canciones distintas", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(estMinutes, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MenuTabPill("Atrás", false, palette.secondary) {
                                loaded = null
                            }
                            MenuTabPill(
                                if (chosen.isEmpty()) "Marca alguna lista" else "Importar (${chosen.size})",
                                chosen.isNotEmpty(),
                                palette.primary,
                            ) {
                                if (chosen.isNotEmpty()) {
                                    importer.start(chosen)
                                    loaded = null
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                // PASO 2: PROGRESO EN VIVO (RUNNING)
                val s = importState as? ImportState.Running
                if (s != null) {
                    val progressFrac = if (s.total > 0) s.done.toFloat() / s.total.toFloat() else 0f
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "LISTA ${s.index + 1} DE ${s.lists}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            s.list,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))

                        // Barra de progreso
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressFrac.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(palette.primary),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("${s.done} de ${s.total} canciones", fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f))
                            Text("${(progressFrac * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.primary)
                        }
                        if (s.missing > 0) {
                            Text("${s.missing} sin encontrar en YouTube Music", fontSize = 12.sp, color = palette.secondary)
                        }
                        Spacer(Modifier.height(28.dp))
                        Text(
                            "Cada lista se guarda en cuanto acaba. Puedes cerrar el Menú y seguir oyendo música mientras tanto.",
                            fontSize = 12.5.sp,
                            color = Color.White.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        MenuTabPill("Parar importación", false, palette.secondary) {
                            importer.stop()
                        }
                    }
                }
            }

            3 -> {
                // PASO 3: RESUMEN FINAL (DONE)
                val s = importState as? ImportState.Done
                if (s != null) {
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, palette.primary.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    if (s.stopped) "Parado a medias" else "¡Importación completada!",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                Text(
                                    "${s.lists} listas y ${s.songs} canciones guardadas en Tus listas." +
                                        if (s.missing.isEmpty()) " No faltó ninguna canción."
                                        else " ${s.missing.size} canciones no se encontraron en YouTube Music.",
                                    fontSize = 13.sp,
                                    color = Color.White.copy(alpha = 0.70f),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MenuTabPill("Ver Tus listas", true, palette.primary) {
                                        importer.dismiss()
                                        onLists()
                                    }
                                    MenuTabPill("Importar más", false, palette.secondary) {
                                        importer.dismiss()
                                    }
                                }
                            }
                        }

                        if (s.missing.isNotEmpty()) {
                            item {
                                Text(
                                    "NO ENCONTRADAS (${s.missing.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.50f),
                                )
                            }
                            items(s.missing) { m ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White.copy(alpha = 0.03f))
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text(m.track.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Text("${m.track.artists.joinToString()} · ${m.list}", fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.50f))
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
private fun SongRowDesktop(
    title: String,
    subtitle: String,
    thumbnail: String? = null,
    isCurrent: Boolean,
    accent: Color,
    leadingIndex: Int? = null,
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
            if (leadingIndex != null) {
                Text(
                    text = "$leadingIndex",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isCurrent) accent else Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.width(22.dp),
                    textAlign = TextAlign.Center,
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A1A)),
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
                    color = if (isCurrent) accent else Color(0xFFFFFFFF),
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
                            Color(0xFA1F1F1F),
                            Color(0xF5121212),
                            Color(0xFA0B0B0B),
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
                        textStyle = TextStyle(fontFamily = OtoFontFamily, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium),
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
                Icon(Icons.Rounded.Check, null, tint = Color(0xFF101010), modifier = Modifier.size(14.dp))
            }
        }
    }
}
