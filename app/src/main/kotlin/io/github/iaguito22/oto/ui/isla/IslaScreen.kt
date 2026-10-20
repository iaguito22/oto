package io.github.iaguito22.oto.ui.isla

import androidx.activity.compose.BackHandler
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.composed
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.iaguito22.oto.playback.Source
import io.github.iaguito22.oto.playback.StreamInfo
import io.github.iaguito22.oto.ui.menu.DOWNLOADS_ID
import io.github.iaguito22.oto.ui.menu.LocalPlaying
import io.github.iaguito22.oto.ui.menu.FOLDER_PREFIX
import io.github.iaguito22.oto.ui.menu.MenuViewModel
import kotlin.math.tanh
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import io.github.iaguito22.oto.DropStyle
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.ui.menu.Floating
import io.github.iaguito22.oto.ui.menu.SaveTo
import io.github.iaguito22.oto.ui.menu.Saving
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.playback.NowPlaying
import io.github.iaguito22.oto.playback.PlayerConnection
import io.github.iaguito22.oto.ui.menu.MenuSheet
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoTheme
import io.github.iaguito22.oto.ui.theme.OtoType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * La pantalla única de Oto: la carátula sobre su propio color desenfocado y, abajo, la gota. Todo lo demás
 * (Menú, cola, letra) sale de la gota y vuelve a ella.
 */
@Composable
fun IslaScreen() {
    val app = LocalContext.current.applicationContext as OtoApp
    val connection = app.player
    val np by connection.state.collectAsStateWithLifecycle()
    val accent = rememberAccent(np.artwork)
    OtoTheme(accent) {
        CompositionLocalProvider(LocalPlaying provides np.playing) { Isla(np, connection, app) }
    }
}

@Composable
private fun Isla(np: NowPlaying, connection: PlayerConnection, app: OtoApp) {
    val c = Oto.colors
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val drop = remember { DropState() }
    val sheet = rememberSheetState()
    val tutorial = remember { TutorialState() }
    val tutorialDone by app.prefs.tutorialDone.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(tutorialDone) { if (tutorialDone == false && !tutorial.active) tutorial.start() }

    var cue by remember { mutableStateOf<CueLabel?>(null) }
    var flash by remember { mutableStateOf<CueLabel?>(null) }
    LaunchedEffect(flash) { if (flash != null) { delay(FLASH_MS); flash = null } }
    var dropCenter by remember { mutableStateOf(Offset.Zero) }
    // Durante el tutorial la carátula se aparta: solo cuentan la gota y lo que se explica.
    val teaching by animateFloatAsState(if (tutorial.active) 1f else 0f, tween(Motion.SPATIAL_FAST, easing = Motion.Standard), label = "teaching")
    val dropRadius = with(density) { DROP_RADIUS.toPx() }
    var lyrics by rememberSaveable { mutableStateOf(false) }
    BackHandler(lyrics && !sheet.isOpen && !tutorial.active) { lyrics = false }
    val menu: MenuViewModel = viewModel()
    val next by connection.queue.collectAsStateWithLifecycle()
    val previous by connection.previousEntry.collectAsStateWithLifecycle()
    val openSource: (Offset) -> Unit = { origin ->
        val src = connection.source.value
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        // Solo esa página: atrás cierra la hoja, no vuelve a lo que quedara abierto de antes.
        menu.closeAll()
        when {
            src?.item != null -> { menu.open(src.item); sheet.open(SheetKind.Menu, origin) }
            src?.local != null -> {
                if (src.local == DOWNLOADS_ID) menu.openDownloads()
                else if (src.local.startsWith(FOLDER_PREFIX)) menu.openFolder(src.local.removePrefix(FOLDER_PREFIX)) else menu.openLocal(src.local)
                sheet.open(SheetKind.Menu, origin)
            }
            else -> sheet.open(SheetKind.Queue, origin)
        }
    }
    // Guardar lo que suena: doble toque en la carátula = Me gusta; mantenerla (o tocar su marca) = elegir dónde.
    val likedIds by app.prefs.likedIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val lists by app.prefs.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    var saving by remember { mutableStateOf<Heard?>(null) }
    var burst by remember { mutableIntStateOf(0) }
    val saved: (String?) -> Saved = { id ->
        when {
            id == null -> Saved.No
            id in likedIds -> Saved.Liked
            lists.any { l -> l.songs.any { it.id == id } } -> Saved.Listed
            else -> Saved.No
        }
    }
    val like: () -> Unit = like@{
        val h = np.toHeard() ?: return@like
        burst++
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        // Como en las fotos: dos toques solo dan me gusta, nunca lo quitan (eso, con la marca o manteniendo).
        if (h.id in likedIds) flash = CueLabel(R.drawable.ic_favorite, "Ya está en Me gusta")
        else {
            flash = CueLabel(R.drawable.ic_favorite, "En Me gusta")
            scope.launch { app.prefs.setLiked(h, true) }
        }
    }
    val save: () -> Unit = save@{
        val h = np.toHeard() ?: return@save
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        saving = h
    }
    BackHandler(saving != null) { saving = null }
    val openArtist: (String, String, Offset) -> Unit = { id, name, origin ->
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        menu.closeAll()
        menu.openArtist(id, name)
        sheet.open(SheetKind.Menu, origin)
    }

    PrefetchArtwork(connection)
    val dropStyle by app.prefs.dropStyle.collectAsStateWithLifecycle(initialValue = null)

    val onGesture: (Gesture) -> Unit = { g ->
        if (tutorial.active) {
            if (tutorial.attempt(g)) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            else haptics.performHapticFeedback(HapticFeedbackType.Reject)
        } else when (g) {
            Gesture.Toggle -> if (np.id == null) {
                flash = CueLabel(R.drawable.ic_search, "Sube la gota para buscar")
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
            } else {
                flash = g.label(np.playing)
                haptics.performHapticFeedback(if (np.playing) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                connection.toggle()
            }
            Gesture.Next -> { haptics.performHapticFeedback(HapticFeedbackType.Confirm); connection.next() }
            Gesture.Previous -> { haptics.performHapticFeedback(HapticFeedbackType.Confirm); connection.previous() }
            Gesture.Menu -> { menu.closeAll(); sheet.open(SheetKind.Menu) }
            Gesture.Queue -> sheet.open(SheetKind.Queue)
            Gesture.Lyrics -> { haptics.performHapticFeedback(HapticFeedbackType.Confirm); lyrics = !lyrics }
        }
    }

    Box(Modifier.fillMaxSize().background(c.base)) {
        Backdrop(np.artwork)
        Stage(
            np, connection, drop, lyrics, openSource, openArtist, saved, like, save, burst, { flash = it },
            Modifier.graphicsLayer {
                // Lo de detrás se aleja y se desenfoca mientras la hoja crece: hace de cristal sin capturar la pantalla.
                val m = sheet.morph.value.coerceIn(0f, 1.2f)
                alpha = 1f - 0.92f * teaching
                val s = 1f - 0.05f * m
                scaleX = s; scaleY = s
                renderEffect = if (m > 0.01f) BlurEffect(28.dp.toPx() * m, 28.dp.toPx() * m, TileMode.Decal) else null
            },
        )
        Drop(
            state = drop,
            playing = np.playing,
            style = dropStyle ?: DropStyle.Ring,
            accent = { c.accent },
            visible = { if (sheet.isOpen) 1f - (sheet.morph.value / 0.06f).coerceIn(0f, 1f) else 1f },
            onCue = { g, hold ->
                cue = when (g) {
                    // Con la siguiente o la anterior se dice cuál es, no solo «Siguiente».
                    Gesture.Next -> CueLabel(R.drawable.ic_skip_next, next.firstOrNull()?.title?.ifEmpty { null } ?: "Siguiente")
                    Gesture.Previous -> CueLabel(R.drawable.ic_skip_previous, previous?.title?.ifEmpty { null } ?: "Desde el principio")
                    Gesture.Lyrics -> if (lyrics) CueLabel(R.drawable.ic_lyrics, "Carátula") else g.label(np.playing)
                    else -> g?.label(np.playing, hold)
                }
            },
            onGesture = onGesture,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = DROP_LIFT)
                .fillMaxWidth()
                .height(DROP_HOST)
                .onGloballyPositioned { dropCenter = it.boundsInRoot().center },
        )
        Cue(
            cue ?: flash,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = DROP_LIFT + DROP_HOST / 2 + 72.dp),
        )
        Floating(
            saving, onDismiss = { saving = null },
            cardModifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) { song -> SaveTo(Saving(song), onDone = { saving = null }) }
        SheetHost(sheet, { dropCenter }, dropRadius, warm = SheetKind.Menu) { kind ->
            when (kind) {
                // Cerrado sigue montado (ver [SheetHost]): sus barras de «sonando» no se mueven donde no se ven.
                SheetKind.Menu -> CompositionLocalProvider(LocalPlaying provides (np.playing && sheet.kind == SheetKind.Menu)) {
                    MenuSheet(connection, np.id, open = sheet.kind == SheetKind.Menu, onClose = sheet::close, onTutorial = {
                        sheet.close()
                        lyrics = false
                        scope.launch { delay(350); tutorial.start() }
                    }, vm = menu)
                }
                SheetKind.Queue -> QueueSheet(connection, onClose = sheet::close)
            }
        }
        Tutorial(tutorial, { dropCenter }, drop, onDone = { scope.launch { app.prefs.setTutorialDone(true) } })
    }
}

/** La carátula como fondo: pequeña, ampliada, muy desenfocada y saturada, con un velo encima. */
@Composable
private fun Backdrop(artwork: String?) {
    val context = LocalContext.current
    // Se carga antes de cambiar, así el fundido va de una imagen a otra y no pasa por negro.
    val image by produceState<ImageBitmap?>(null, artwork) {
        if (artwork == null) { value = null; return@produceState }
        val r = context.imageLoader.execute(ImageRequest.Builder(context).data(smallArtwork(artwork)).build())
        r.image?.toBitmap()?.asImageBitmap()?.let { value = it }
    }
    Crossfade(image, animationSpec = tween(BACKDROP_MS, easing = Motion.Standard), label = "backdrop") { bmp ->
        if (bmp != null) Image(
            bmp, null,
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.4f) }),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.35f; scaleY = 1.35f
                    renderEffect = BlurEffect(64.dp.toPx(), 64.dp.toPx(), TileMode.Clamp)
                },
        )
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color(0x8C080808), 0.55f to Color(0x6B080808), 1f to Color(0xCC080808)),
        ),
    )
}

@Composable
private fun Stage(
    np: NowPlaying,
    connection: PlayerConnection,
    drop: DropState,
    lyrics: Boolean,
    onSource: (Offset) -> Unit,
    onArtist: (String, String, Offset) -> Unit,
    saved: (String?) -> Saved,
    onLike: () -> Unit,
    onSave: () -> Unit,
    burst: Int,
    onFlash: (CueLabel) -> Unit,
    modifier: Modifier,
) {
    val c = Oto.colors
    val density = LocalDensity.current
    // Hacia dónde entra la canción nueva: de la derecha si avanza, de la izquierda si retrocede.
    var lastIndex by remember { mutableIntStateOf(np.index) }
    var lastId by remember { mutableStateOf(np.id) }
    var dir by remember { mutableIntStateOf(1) }
    val peekGate = remember { PeekGate() }
    if (np.id != lastId) {
        dir = if (np.index < lastIndex) -1 else 1
        lastIndex = np.index
        lastId = np.id
        // La carátula que se asomaba es la que entra: el asomo se esconde hasta que la gota vuelva al centro.
        peekGate.mute()
    }
    // Lo que anima el cambio de canción es solo la canción: el resto de NowPlaying (cargando, duración, cola) cambia
    // varias veces mientras entra, y cada cambio reiniciaba la transición y se llevaba de golpe la carátula vieja.
    val song = remember(np.id, np.title, np.artist, np.artists, np.artwork) { Shown(np.id, np.title, np.artist, np.artists, np.artwork) }
    val titleShift = with(density) { 26.dp.roundToPx() }
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val paused by animateFloatAsState(if (np.id != null && !np.playing && !np.buffering) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 300f), label = "paused")
    val beat by (LocalContext.current.applicationContext as OtoApp).prefs.coverBeat.collectAsStateWithLifecycle(initialValue = true)
    val source by connection.source.collectAsStateWithLifecycle()
    val next by connection.queue.collectAsStateWithLifecycle()
    val previous by connection.previousEntry.collectAsStateWithLifecycle()
    // Tirar hacia abajo del origen o de la carátula abre de dónde viene lo que suena.
    val pull = remember { Animatable(0f) }
    var sourceCenter by remember { mutableStateOf(Offset.Zero) }
    val canPull = np.id != null && source != null
    // El detector de toques vive más que una recomposición: tiene que llamar a la versión de ahora (la canción de ahora).
    val like by rememberUpdatedState(onLike)
    val save by rememberUpdatedState(onSave)

    Column(
        modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = DROP_LIFT + DROP_HOST),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(SOURCE_TOP))
        SourceHeader(source.takeIf { np.id != null }, pull, onCenter = { sourceCenter = it }, onOpen = { onSource(sourceCenter) })
        Spacer(Modifier.weight(1f))
        BoxWithConstraints(Modifier.padding(horizontal = 36.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, 340.dp)
            // La letra ocupa el sitio de la carátula: cambiar a ella es como cambiar de carátula, no de pantalla.
            AnimatedContent(
                targetState = lyrics,
                transitionSpec = {
                    val down = if (targetState) 1 else -1
                    (fadeIn(tween(Motion.EFFECTS, delayMillis = 50)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 300f), 0.94f) +
                        slideInVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) { -down * it / 14 }) togetherWith
                        (fadeOut(tween(Motion.EFFECTS_FAST)) + scaleOut(tween(Motion.SPATIAL_FAST, easing = Motion.EmphasizedAccelerate), 0.94f)) using
                        SizeTransform(clip = false)
                },
                contentAlignment = Alignment.Center,
                label = "coverOrLyrics",
            ) { showLyrics ->
                if (showLyrics) LyricsPanel(np, connection, side, side + LYRICS_EXTRA, onFlash)
                else Box(
                    Modifier
                        .pullToOpen(canPull, pull, onOpen = { onSource(sourceCenter) })
                        .pointerInput(np.id != null) {
                            if (np.id != null) detectTapGestures(onDoubleTap = { like() }, onLongPress = { save() })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Al llevar la gota a un lado se asoma por ese lado la canción que vendría.
                    Peek(next.firstOrNull()?.artwork, side, drop, peekGate, toRight = true)
                    Peek(previous?.artwork, side, drop, peekGate, toRight = false)
                    // La nueva sale de donde se asomaba (a su tamaño) y crece hasta el centro; la que se va se encoge
                    // hacia el otro lado. Con muelles: si se pasa otra vez a mitad, sigue desde donde va.
                    val from = with(density) { (screenW / 2 + side * PEEK_SCALE / 2 + 8.dp - PEEK_REVEAL).roundToPx() }
                    AnimatedContent(
                        targetState = song,
                        contentKey = { it.id },
                        transitionSpec = {
                            val d = dir
                            val move = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.84f, stiffness = 230f)
                            // Opaca desde el principio: la que se asomaba ya se veía entera.
                            (slideInHorizontally(move) { d * from } + scaleIn(spring(dampingRatio = 0.84f, stiffness = 230f), PEEK_SCALE)) togetherWith
                                (slideOutHorizontally(move) { -d * (it * 0.62f).toInt() } + scaleOut(tween(Motion.SPATIAL_FAST, easing = Motion.Standard), 0.8f) +
                                    fadeOut(tween(280, easing = Motion.Standard))) using
                                SizeTransform(clip = false)
                        },
                        modifier = Modifier.graphicsLayer {
                            drop.frame
                            // El mismo latido que la gota, ya suavizado, pero mucho más pequeño.
                            if (beat) (1f + COVER_BEAT * drop.pulse).let { scaleX = it; scaleY = it }
                            translationX = -drop.lean * side.toPx() * COVER_LEAN
                            translationY = COVER_PULL * pullOffset(pull.value, density)
                        },
                        label = "cover",
                    ) { song -> Cover(song.artwork, side, paused, saved(song.id), onSave) }
                    HeartBurst(burst, side, Modifier.graphicsLayer { translationY = COVER_PULL * pullOffset(pull.value, density) })
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        AnimatedContent(
            targetState = song,
            contentKey = { it.id },
            transitionSpec = {
                val d = dir
                (fadeIn(tween(Motion.EFFECTS, delayMillis = 90)) + slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 300f)) { d * titleShift * 2 }) togetherWith
                    (fadeOut(tween(Motion.EFFECTS_FAST)) + slideOutHorizontally(tween(Motion.SPATIAL_FAST, easing = Motion.Standard)) { -d * titleShift }) using
                    SizeTransform(clip = false)
            },
            label = "titles",
        ) { song ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Un título largo se desplaza y se desvanece por los bordes en vez de cortarse en seco.
                Box(Modifier.fillMaxWidth().fadeEdges(), contentAlignment = Alignment.Center) {
                    Text(
                        song.title.ifEmpty { if (song.id == null) "Nada sonando" else "" },
                        style = OtoType.title, color = c.text, maxLines = 1, textAlign = TextAlign.Center,
                        modifier = Modifier.basicMarquee(initialDelayMillis = 2500, repeatDelayMillis = 2500, velocity = 36.dp, spacing = MarqueeSpacing(48.dp)),
                    )
                }
                Artists(song, onArtist)
            }
        }
        StatusLine(np)
        Spacer(Modifier.height(10.dp))
        if (np.id != null) Progress(np, connection, Modifier.padding(horizontal = 36.dp))
        else Spacer(Modifier.height(PROGRESS_H))
        Spacer(Modifier.weight(1.25f))
    }
}

/**
 * Lo que suena viene de algún sitio (un álbum, una lista, la radio de una canción): se dice arriba, y tirar de
 * ahí (o de la carátula) hacia abajo, o tocarlo, lo abre.
 */
@Composable
private fun SourceHeader(source: Source?, pull: Animatable<Float, *>, onCenter: (Offset) -> Unit, onOpen: () -> Unit) {
    val c = Oto.colors
    val density = LocalDensity.current
    var shown by remember { mutableStateOf(source) }
    if (source != null) shown = source
    val alpha by animateFloatAsState(if (source != null) 1f else 0f, tween(Motion.EFFECTS, easing = Motion.Standard), label = "source")

    Column(
        Modifier
            .fillMaxWidth()
            .height(SOURCE_H)
            .graphicsLayer {
                this.alpha = alpha
                translationY = pullOffset(pull.value, density)
            }
            .onGloballyPositioned { onCenter(it.boundsInRoot().center) }
            .pullToOpen(source != null, pull, onOpen)
            .pointerInput(source != null) { if (source != null) detectTapGestures { onOpen() } },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val s = shown ?: return@Column
        Text(
            (if (s.item == null && s.local == null) s.kind else "Desde ${s.kind.lowercase()}").uppercase(),
            style = OtoType.label.copy(fontSize = 11.sp), color = c.textMuted, maxLines = 1,
        )
        Text(
            s.title, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 48.dp),
        )
    }
}

/** El tirón con resistencia, en px: sigue al dedo al principio y cada vez cuesta más. */
private fun pullOffset(pull: Float, density: androidx.compose.ui.unit.Density): Float {
    val k = with(density) { 64.dp.toPx() }
    return k * tanh(pull / k)
}

/** Arrastrar hacia abajo: sigue al dedo, vibra al pasar el umbral y, si se suelta pasado, abre. */
private fun Modifier.pullToOpen(enabled: Boolean, pull: Animatable<Float, *>, onOpen: () -> Unit): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        var armed = false
        val settle = {
            armed = false
            scope.launch { pull.animateTo(0f, Motion.spatial()) }
        }
        detectVerticalDragGestures(
            onDragEnd = {
                if (armed) onOpen()
                settle()
            },
            onDragCancel = { settle() },
        ) { change, dy ->
            val d = (pull.value + dy).coerceAtLeast(0f)
            if (d == 0f && dy < 0) return@detectVerticalDragGestures
            change.consume()
            if ((d > threshold) != armed) {
                armed = !armed
                if (armed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            }
            scope.launch { pull.snapTo(d) }
        }
    }
}

/** Lo que se ve de la canción (sin el estado de reproducción). */
private data class Shown(val id: String?, val title: String, val artist: String, val artists: List<Pair<String, String?>>, val artwork: String?)

/**
 * Mientras cambia la canción, los asomos no se ven (enseñarían la de después) hasta que la gota se ha asentado en
 * el centro: el muelle cruza el centro varias veces al volver.
 */
private class PeekGate {
    var mutedAt = 0L

    fun mute() {
        mutedAt = android.os.SystemClock.uptimeMillis()
    }

    fun muted(lean: Float): Boolean {
        if (mutedAt == 0L) return false
        if (android.os.SystemClock.uptimeMillis() - mutedAt > PEEK_MUTE_MS && abs(lean) < 0.02f) mutedAt = 0L
        return mutedAt != 0L
    }
}

/** La carátula de al lado, escondida fuera de la pantalla hasta que la gota se lleva hacia su lado. */
@Composable
private fun Peek(artwork: String?, side: Dp, drop: DropState, gate: PeekGate, toRight: Boolean) {
    if (artwork == null) return
    val screen = LocalConfiguration.current.screenWidthDp.dp
    val peekSide = side * PEEK_SCALE
    AsyncImage(
        model = artwork,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(peekSide)
            .graphicsLayer {
                drop.frame
                val sign = if (toRight) 1f else -1f
                val f = (drop.lean * sign).coerceIn(0f, 1f)
                val hidden = screen.toPx() / 2 + peekSide.toPx() / 2 + 8.dp.toPx()
                translationX = sign * (hidden - PEEK_REVEAL.toPx() * f)
                alpha = if (gate.muted(drop.lean)) 0f else (f * 2.5f).coerceIn(0f, 1f)
                rotationZ = sign * 4f * (1f - f)
                shape = RoundedCornerShape(24.dp)
                clip = true
            },
    )
}

/** Los artistas, cada uno pulsable si YouTube Music da su página. */
@Composable
private fun Artists(song: Shown, onArtist: (String, String, Offset) -> Unit) {
    val c = Oto.colors
    val style = OtoType.body.copy(fontSize = 15.sp)
    if (song.id == null || song.artist.isEmpty()) {
        Text(if (song.id == null) "Sube la gota para buscar" else "", style = style, color = c.textMuted, maxLines = 1, textAlign = TextAlign.Center)
        return
    }
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val text = buildAnnotatedString {
        song.artists.forEachIndexed { i, (name, id) ->
            if (i > 0) append(", ")
            if (id == null) append(name)
            else withLink(
                LinkAnnotation.Clickable(id, TextLinkStyles(pressedStyle = SpanStyle(color = c.text))) { onArtist(id, name, bounds.center) },
            ) { append(name) }
        }
    }
    Text(
        text, style = style, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        modifier = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() },
    )
}

/** Dónde está guardada una canción: en Me gusta (corazón), solo en alguna lista tuya (marca) o en ninguna. */
private enum class Saved { No, Listed, Liked }

private fun NowPlaying.toHeard() = id?.let { Heard(it, title, artist, artwork, artists.firstOrNull()?.second) }

@Composable
private fun Cover(artwork: String?, side: Dp, paused: Float, saved: Saved, onSaved: () -> Unit) {
    val c = Oto.colors
    val shape = RoundedCornerShape(28.dp)
    val sat = lerp(1f, 0.7f, paused)
    val bright = lerp(1f, 0.85f, paused)
    Box(
        Modifier
            .size(side)
            .graphicsLayer { val s = lerp(1f, 0.93f, paused); scaleX = s; scaleY = s }
            .shadow(30.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            // Opaco: con un fondo translúcido la sombra se vería a través.
            .background(c.field.compositeOver(c.base)),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork == null) {
            Text("音", style = OtoType.display.copy(fontSize = 96.sp, fontWeight = FontWeight.Normal), color = c.textFaint)
        } else {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (paused > 0.001f) ColorFilter.colorMatrix(dim(sat, bright)) else null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        SavedMark(saved, onSaved, Modifier.align(Alignment.BottomEnd).padding(12.dp))
    }
}

/** La marca de guardada, en la esquina de la carátula: tocarla abre dónde está. */
@Composable
private fun SavedMark(saved: Saved, onClick: () -> Unit, modifier: Modifier) {
    val c = Oto.colors
    var shown by remember { mutableStateOf(saved) }
    if (saved != Saved.No) shown = saved
    AnimatedVisibility(
        saved != Saved.No,
        modifier = modifier,
        enter = fadeIn(tween(Motion.EFFECTS_FAST)) + scaleIn(spring(dampingRatio = 0.5f, stiffness = 600f), initialScale = 0.3f),
        exit = fadeOut(tween(Motion.EFFECTS_FAST)) + scaleOut(tween(Motion.SPATIAL_FAST, easing = Motion.Standard), targetScale = 0.6f),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(shown, animationSpec = tween(Motion.EFFECTS), label = "savedMark") { s ->
                Icon(
                    painterResource(if (s == Saved.Liked) R.drawable.ic_favorite else R.drawable.ic_check), null,
                    Modifier.size(19.dp), tint = c.accent,
                )
            }
        }
    }
}

/**
 * El corazón del doble toque: brota del centro de la carátula con un rebote, suelta un aro de acento y se va
 * hinchándose y desvaneciéndose. Cada [key] nuevo lo lanza otra vez, aunque el anterior no hubiera acabado.
 * Solo los que llegan con él a la vista: al volver de la letra se crea de nuevo y no debe repetir el último.
 */
@Composable
private fun HeartBurst(key: Int, side: Dp, modifier: Modifier) {
    val c = Oto.colors
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val ring = remember { Animatable(1f) }
    val shownAt = remember { key }
    LaunchedEffect(key) {
        if (key == shownAt) return@LaunchedEffect
        scale.snapTo(0.35f); alpha.snapTo(1f); ring.snapTo(0f)
        launch { ring.animateTo(1f, tween(560, easing = Motion.EmphasizedDecelerate)) }
        // El rebote no se espera a que se calme: a su hora se va, aunque aún tiemble (y lo de irse lo corta).
        launch { scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 420f)) }
        delay(HEART_HOLD_MS)
        launch { scale.animateTo(1.22f, tween(240, easing = Motion.EmphasizedAccelerate)) }
        alpha.animateTo(0f, tween(240, easing = Motion.Standard))
    }
    Box(
        modifier.size(side).drawBehind {
            val a = alpha.value
            if (a <= 0f) return@drawBehind
            // Una sombra suave debajo: el corazón blanco se lee igual sobre una carátula clara.
            drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.32f * a), Color.Transparent), radius = size.minDimension * 0.34f))
            val r = ring.value
            if (r < 1f) drawCircle(
                c.accent.copy(alpha = (1f - r) * a),
                radius = size.minDimension * lerp(0.14f, 0.46f, r),
                style = Stroke(width = lerp(10.dp.toPx(), 1.dp.toPx(), r)),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_favorite), null,
            Modifier.size(side * 0.3f).graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
                this.alpha = alpha.value
            },
            tint = Color.White,
        )
    }
}

private fun dim(saturation: Float, brightness: Float) = ColorMatrix().apply {
    setToSaturation(saturation)
    timesAssign(ColorMatrix(floatArrayOf(brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
}

@Composable
private fun StatusLine(np: NowPlaying) {
    val c = Oto.colors
    val text = when {
        np.error != null -> "No se pudo reproducir"
        np.buffering -> "Cargando"
        np.id != null && !np.playing -> "En pausa"
        else -> null
    }
    Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn(tween(Motion.EFFECTS, delayMillis = 80)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) using SizeTransform(clip = false) },
            contentAlignment = Alignment.Center,
            label = "status",
        ) { t ->
            if (t != null) Text(t.uppercase(), style = OtoType.label, color = c.accent)
        }
    }
}

/** La línea de tiempo: fina, se engorda al tocarla y se arrastra para saltar. Se redibuja sin recomponer. */
@Composable
private fun Progress(np: NowPlaying, connection: PlayerConnection, modifier: Modifier) {
    val c = Oto.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val prefs = (LocalContext.current.applicationContext as OtoApp).prefs
    val remaining by prefs.showRemaining.collectAsStateWithLifecycle(initialValue = true)
    val pos = remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = np.durationMs
    LaunchedEffect(np.id, np.playing, dur) {
        do {
            withFrameNanos { }
            if (drag == null) pos.longValue = connection.position()
        } while (np.playing)
    }
    val thick by animateFloatAsState(if (drag != null) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 700f), label = "thick")
    val shownSecs by remember(dur) { derivedStateOf { ((drag?.let { it * dur }?.toLong() ?: pos.longValue) / 1000).toInt() } }
    val totalSecs = (dur / 1000).toInt()
    val seek: (Float) -> Unit = { f ->
        val ms = (f.coerceIn(0f, 1f) * dur).toLong()
        pos.longValue = ms
        connection.seekTo(ms)
    }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInputSeek(dur, onTap = { f -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); seek(f) }, onDrag = { f -> drag = f }, onEnd = {
                    drag?.let(seek)
                    drag = null
                })
                .drawBehind {
                    val f = drag ?: if (dur > 0) (pos.longValue.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    val h = lerp(4.dp.toPx(), 8.dp.toPx(), thick)
                    val y = (size.height - h) / 2
                    drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                    if (f > 0f) drawRoundRect(c.accent, Offset(0f, y), Size(maxOf(h, size.width * f), h), CornerRadius(h / 2))
                },
        )
        val style = OtoType.label.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp, fontFeatureSettings = "tnum")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(time(shownSecs), style = style, color = c.textMuted)
            Spacer(Modifier.weight(1f))
            // Tocar el de la derecha cambia entre lo que falta y lo que dura (y se recuerda).
            Box(
                Modifier
                    .clip(CircleShape)
                    .clickable(remember { MutableInteractionSource() }, null) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        scope.launch { prefs.setShowRemaining(!remaining) }
                    }
                    .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                AnimatedContent(
                    targetState = remaining,
                    transitionSpec = {
                        (fadeIn(tween(Motion.EFFECTS, delayMillis = 40)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 500f)) { it / 2 }) togetherWith
                            (fadeOut(tween(Motion.EFFECTS_FAST)) + slideOutVertically(tween(Motion.EFFECTS_FAST)) { -it / 2 }) using SizeTransform(clip = false)
                    },
                    contentAlignment = Alignment.CenterEnd,
                    label = "tail",
                ) { r ->
                    Text(if (totalSecs <= 0) "" else if (r) "-" + time((totalSecs - shownSecs).coerceAtLeast(0)) else time(totalSecs), style = style, color = c.textMuted)
                }
            }
        }
        // La calidad con la que llega, debajo: lo que dio YouTube (o lo que se guardó con la caché).
        val quality by StreamInfo.quality.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(16.dp), contentAlignment = Alignment.Center) {
            Crossfade(quality[np.id]?.toString(), animationSpec = tween(Motion.EFFECTS), label = "quality") { q ->
                if (q != null) Text(q, style = style.copy(fontSize = 11.sp, letterSpacing = 0.04.em), color = c.textFaint, maxLines = 1)
            }
        }
    }
}

private fun Modifier.pointerInputSeek(dur: Long, onTap: (Float) -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier =
    this
        .pointerInput(dur) { detectTapGestures { onTap(it.x / size.width) } }
        .pointerInput(dur) {
            var f = 0f
            detectHorizontalDragGestures(
                onDragStart = { f = it.x / size.width; onDrag(f.coerceIn(0f, 1f)) },
                onDragEnd = onEnd,
                onDragCancel = onEnd,
            ) { change, dx ->
                change.consume()
                f += dx / size.width
                onDrag(f.coerceIn(0f, 1f))
            }
        }

private fun Modifier.fadeEdges() = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val f = 18.dp.toPx()
        drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), endX = f), size = Size(f, size.height), blendMode = BlendMode.DstOut)
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = size.width - f, endX = size.width), Offset(size.width - f, 0f), Size(f, size.height), blendMode = BlendMode.DstOut)
    }

private fun time(secs: Int) = "%d:%02d".format(secs / 60, secs % 60)

/** Las carátulas de las dos siguientes, ya en caché: al pasar de canción la nueva entra ya pintada. */
@Composable
private fun PrefetchArtwork(connection: PlayerConnection) {
    val context = LocalContext.current
    val queue by connection.queue.collectAsStateWithLifecycle()
    val next = queue.take(2).mapNotNull { it.artwork }
    LaunchedEffect(next) {
        next.forEach { url ->
            context.imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
            context.imageLoader.enqueue(ImageRequest.Builder(context).data(smallArtwork(url)).build())
        }
    }
}

private val DROP_HOST = 168.dp
// La gota no va pegada a la barra de gestos: un poco más arriba queda donde cae el pulgar.
private val DROP_LIFT = 24.dp
private val DROP_RADIUS = 43.dp
private val SOURCE_H = 52.dp
// El origen no va pegado a la barra de estado: hay sitio de sobra.
private val SOURCE_TOP = 22.dp
private val PEEK_REVEAL = 136.dp
private const val PEEK_SCALE = 0.86f
// Cuánto se aparta la carátula al asomarse la de al lado, y cuánto baja con el tirón (el origen baja entero).
private const val COVER_LEAN = 0.16f
private const val COVER_PULL = 0.5f
private const val COVER_BEAT = 0.022f
// La letra es algo más alta que la carátula: debajo sobra sitio.
private val LYRICS_EXTRA = 56.dp
private val PROGRESS_H = 72.dp
private const val FLASH_MS = 650L
private const val PEEK_MUTE_MS = 500L
private const val BACKDROP_MS = 800
private const val HEART_HOLD_MS = 480L
