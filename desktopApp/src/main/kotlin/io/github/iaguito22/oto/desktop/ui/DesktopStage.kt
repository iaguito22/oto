package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.style.TextDecoration
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iaguito22.oto.lyrics.Line
import io.github.iaguito22.oto.playback.Quality
import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.playback.NowPlaying
import io.github.iaguito22.oto.playback.Source
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Escenario principal a ventana completa de Oto Desktop (Linux / Windows),
 * calcado del prototipo `fusion.html`:
 * - Fondo degradado vivo sin bordes de panel exterior (100% del lienzo).
 * - `DESDE TU LISTA` arriba del todo centrado.
 * - Cabecera `Título` a la izquierda y `Artista` a la derecha abarcando desde el inicio de la carátula
 *   hasta el final de la letra, **sin línea divisoria inferior (`border-bottom`) ni `text-shadow`**.
 * - Tiempo desnudo arriba de la carátula y calidad desnuda debajo (sin bordes ni fondo).
 * - Carátula circular grande con Corona 360° de progreso, inclinación 3D al mover el ratón y
 *   controles internos traslúcidos (`Anterior`, `Play/Pause`, `Siguiente`) que **solo aparecen al hacer hover**.
 * - Columna derecha con letras sincronizadas estilo karaoke Caelestia.
 */
data class CueInfo(
    val icon: ImageVector,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun DesktopStage(
    nowPlaying: NowPlaying,
    positionMs: Long,
    quality: Quality?,
    source: Source?,
    artwork: ImageBitmap?,
    palette: ArtworkPalette,
    liked: Boolean,
    lyrics: Lyrics?,
    lyricsLoading: Boolean,
    volume: Float = 1.0f,
    cue: CueInfo? = null,
    headerStyle: Int = 0,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleLike: () -> Unit,
    onSaveTo: () -> Unit = {},
    onOpenArtist: (id: String?, name: String) -> Unit = { _, _ -> },
    onOpenSource: () -> Unit,
    onOpenMenu: () -> Unit,
    onAdjustVolume: (Float) -> Unit = {},
) {
    val animPal = rememberAnimatedPalette(palette)
    val inf = rememberInfiniteTransition(label = "bgDrift")
    val drift by inf.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(tween(22000, easing = LinearEasing), RepeatMode.Restart),
        label = "driftAngle",
    )

    // Dirección de salto para animar la transición de canción en la carátula y el título.
    var skipDir by remember { mutableIntStateOf(1) }

    // Control de visibilidad del overlay flotante de volumen al modificarlo
    var lastVolumeChangeMs by remember { mutableLongStateOf(0L) }
    var prevVol by remember { mutableFloatStateOf(volume) }
    LaunchedEffect(volume) {
        if (abs(volume - prevVol) > 0.001f) {
            lastVolumeChangeMs = System.currentTimeMillis()
            prevVol = volume
        }
    }
    val showVolume = (System.currentTimeMillis() - lastVolumeChangeMs) < 1600L

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animPal.deep)
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy > 0.5f) {
                    onOpenMenu()
                }
            },
    ) {
        // 1. Carátula desenfocada de fondo + halos radiales vivos (Spotify Caelestia)
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.48f) }),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.52f; scaleX = 1.35f; scaleY = 1.35f }
                    .blur(95.dp),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Halo cálido y vibrante detrás de la columna de letras (derecha-centro)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(animPal.primary.copy(alpha = 0.44f), Color.Transparent),
                    center = Offset(w * (0.68f + 0.04f * cos(drift)), h * (0.36f + 0.04f * sin(drift))),
                    radius = w * 0.56f,
                ),
            )
            // Halo secundario envolviendo la carátula y el cuadrante izquierdo
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(animPal.secondary.copy(alpha = 0.30f), Color.Transparent),
                    center = Offset(w * (0.28f - 0.04f * cos(drift)), h * (0.42f + 0.05f * sin(drift))),
                    radius = w * 0.52f,
                ),
            )
            // Halo terciario de profundidad
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(animPal.tertiary.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(w * 0.50f, h * (0.85f - 0.03f * sin(drift))),
                    radius = w * 0.60f,
                ),
            )
            // Velo suave para legibilidad: oscurece arriba y abajo sin asfixiar la luz central
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0x5508070C),
                        Color(0x2808070C),
                        Color(0x8808070C),
                    ),
                ),
            )
        }

        // 2. Contenido principal centrado abarcando de carátula a letra
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 1220.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Escenario de 2 columnas (estilo Spotify Caelestia):
                // Izquierda: Carátula cuadrada redondeada con controles hover + Título + Artista + Barra minimal
                // Derecha: Origen de lista + Letras sincronizadas con difuminado suave
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(56.dp),
                ) {
                    Box(
                        modifier = Modifier.weight(0.42f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        SpotifyPlayerColumn(
                            nowPlaying = nowPlaying,
                            positionMs = positionMs,
                            quality = quality,
                            artwork = artwork,
                            palette = animPal,
                            liked = liked,
                            skipDir = skipDir,
                            onTogglePlay = onTogglePlay,
                            onPrev = { skipDir = -1; onPrev() },
                            onNext = { skipDir = 1; onNext() },
                            onSeek = onSeek,
                            onToggleLike = onToggleLike,
                            onSaveTo = onSaveTo,
                            onOpenArtist = onOpenArtist,
                            onAdjustVolume = onAdjustVolume,
                        )
                    }

                    Column(
                        modifier = Modifier.weight(0.58f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        // Pill superior con origen de reproducción (se oculta suavemente mientras hay un aviso activo)
                        AnimatedVisibility(
                            visible = cue == null,
                            enter = fadeIn(tween(200)),
                            exit = fadeOut(tween(120)),
                        ) {
                            TopSourceOriginBar(
                                source = source,
                                accent = animPal.primary,
                                onOpenSource = onOpenSource,
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Columna de letra a pantalla completa con difuminado suave
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            LyricsStageColumn(
                                lyrics = lyrics,
                                loading = lyricsLoading,
                                positionMs = positionMs,
                                palette = animPal,
                                onSeek = onSeek,
                            )
                        }
                    }
                }
            }
        }

        // Píldora flotante animada de volumen estilo liquid glass (arriba a la derecha)
        AnimatedVisibility(
            visible = showVolume,
            enter = fadeIn(tween(180)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 500f), initialScale = 0.88f),
            exit = fadeOut(tween(240)),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 36.dp),
        ) {
            Row(
                modifier = Modifier
                    .shadow(20.dp, CircleShape, ambientColor = animPal.primary.copy(alpha = 0.40f), spotColor = Color.Black)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xE61B152A),
                                Color(0xF0120D1F),
                            ),
                        ),
                    )
                    .border(
                        1.dp,
                        Brush.horizontalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.45f),
                                animPal.primary.copy(alpha = 0.40f),
                                Color.White.copy(alpha = 0.15f),
                            ),
                        ),
                        CircleShape,
                    )
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = when {
                        volume <= 0.001f -> Icons.AutoMirrored.Rounded.VolumeMute
                        volume < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                        else -> Icons.AutoMirrored.Rounded.VolumeUp
                    },
                    contentDescription = "Volumen",
                    tint = animPal.primary,
                    modifier = Modifier.size(19.dp),
                )
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(volume.coerceIn(0f, 1f))
                            .clip(CircleShape)
                            .background(animPal.primary),
                    )
                }
                Text(
                    text = "${(volume * 100f).roundToInt()}%",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }

        // Píldora OSD animada de acciones rápidas (arriba al centro, estilo Caelestia)
        AnimatedVisibility(
            visible = cue != null,
            enter = fadeIn(tween(140)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 600f)) { -it / 2 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
        ) {
            if (cue != null) {
                Row(
                    modifier = Modifier
                        .shadow(24.dp, CircleShape, ambientColor = animPal.primary.copy(alpha = 0.45f), spotColor = Color.Black)
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xE61E1830),
                                    Color(0xF2120D20),
                                ),
                            ),
                        )
                        .border(
                            1.dp,
                            Brush.horizontalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.50f),
                                    animPal.primary.copy(alpha = 0.40f),
                                    Color.White.copy(alpha = 0.20f),
                                ),
                            ),
                            CircleShape,
                        )
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = cue.icon,
                        contentDescription = null,
                        tint = animPal.primary,
                        modifier = Modifier.size(19.dp),
                    )
                    Text(
                        text = cue.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopSourceOriginBar(
    source: Source?,
    accent: Color,
    onOpenSource: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
                .clickable(onClick = onOpenSource)
                .padding(horizontal = 14.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Text(
                text = (source?.kind ?: "DESDE TU LISTA").uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
                color = Color.White.copy(alpha = 0.62f),
            )
            Text(
                text = "·",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.40f),
            )
            Text(
                text = source?.title ?: "Me gusta",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SpotifyPlayerColumn(
    nowPlaying: NowPlaying,
    positionMs: Long,
    quality: Quality?,
    artwork: ImageBitmap?,
    palette: ArtworkPalette,
    liked: Boolean,
    skipDir: Int,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleLike: () -> Unit,
    onSaveTo: () -> Unit = {},
    onOpenArtist: (id: String?, name: String) -> Unit = { _, _ -> },
    onAdjustVolume: (Float) -> Unit = {},
) {
    val coverInteraction = remember { MutableInteractionSource() }
    val isHovered by coverInteraction.collectIsHoveredAsState()
    var manualHover by remember { mutableStateOf(false) }
    val isCoverActive = isHovered || manualHover
    var burstTrigger by remember { mutableLongStateOf(0L) }

    val controlsAlpha by animateFloatAsState(
        targetValue = if (isCoverActive) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "ctrlAlpha",
    )
    val controlsScale by animateFloatAsState(
        targetValue = if (isCoverActive) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "ctrlScale",
    )

    val duration = nowPlaying.durationMs.coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val coverSize = 460.dp

    Column(
        modifier = Modifier.width(coverSize),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        // 1. Carátula cuadrada con bordes redondeados (estilo Spotify Caelestia)
        Box(
            modifier = Modifier
                .size(coverSize)
                .hoverable(coverInteraction)
                .onPointerEvent(PointerEventType.Enter) { manualHover = true }
                .onPointerEvent(PointerEventType.Move) { manualHover = true }
                .onPointerEvent(PointerEventType.Exit) { manualHover = false }
                .onPointerEvent(PointerEventType.Scroll) { ev ->
                    val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy < -0.1f) onAdjustVolume(0.04f)
                    else if (dy > 0.1f) onAdjustVolume(-0.04f)
                }
                .shadow(
                    elevation = 28.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = Color.Black.copy(alpha = 0.6f),
                    spotColor = Color.Black.copy(alpha = 0.85f),
                )
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF14121A))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                .pointerInput(nowPlaying.id) {
                    detectTapGestures(
                        onDoubleTap = {
                            onToggleLike()
                            burstTrigger = System.currentTimeMillis()
                        },
                        onLongPress = { onSaveTo() },
                        onTap = { onTogglePlay() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // Imagen animada al cambiar de canción
            AnimatedContent(
                targetState = nowPlaying.id to artwork,
                transitionSpec = {
                    (fadeIn(tween(360)) +
                        slideInHorizontally(spring(dampingRatio = 0.72f, stiffness = 300f)) { skipDir * it / 4 } +
                        scaleIn(spring(dampingRatio = 0.78f, stiffness = 300f), initialScale = 0.92f)) togetherWith
                        (fadeOut(tween(180)) +
                            slideOutHorizontally(tween(180)) { -skipDir * it / 5 } +
                            scaleOut(tween(180), targetScale = 1.04f))
                },
                label = "coverArt",
            ) { (_, bmp) ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = nowPlaying.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = palette.primary.copy(alpha = 0.55f),
                            modifier = Modifier.size(80.dp),
                        )
                    }
                }
            }

            // Controles internos en la carátula visibles en hover
            if (controlsAlpha > 0.001f || isCoverActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = controlsAlpha }
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.58f),
                                    Color.Black.copy(alpha = 0.32f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.graphicsLayer {
                            scaleX = controlsScale
                            scaleY = controlsScale
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        CoverControlButton(
                            size = 48.dp,
                            isPrimary = false,
                            accent = palette.primary,
                            onClick = onPrev,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Anterior",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        CoverControlButton(
                            size = 66.dp,
                            isPrimary = true,
                            accent = palette.primary,
                            onClick = onTogglePlay,
                        ) {
                            Icon(
                                imageVector = if (nowPlaying.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (nowPlaying.playing) "Pausar" else "Reproducir",
                                tint = Color(0xFF120D1D),
                                modifier = Modifier.size(34.dp),
                            )
                        }

                        CoverControlButton(
                            size = 48.dp,
                            isPrimary = false,
                            accent = palette.primary,
                            onClick = onNext,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Siguiente",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    // Botones en la esquina superior derecha: Guardar en lista + Me gusta
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.60f))
                                .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                                .clickable(onClick = onSaveTo),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                contentDescription = "Guardar en lista",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(19.dp),
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.60f))
                                .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                                .clickable(onClick = onToggleLike),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (liked) "Quitar de Me gusta" else "Añadir a Me gusta",
                                tint = if (liked) palette.primary else Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Marca de guardado visible en la esquina inferior derecha cuando no hay hover si está guardada
            if (!isCoverActive && liked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.50f))
                        .border(1.dp, palette.primary.copy(alpha = 0.50f), CircleShape)
                        .clickable(onClick = onSaveTo),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = "Guardado en Me gusta",
                        tint = palette.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // HeartBurst animado al hacer doble tap
            val burstElapsed = System.currentTimeMillis() - burstTrigger
            if (burstElapsed < 650L) {
                val p = (burstElapsed.toFloat() / 650f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 0.6f + p * 1.3f
                            scaleY = 0.6f + p * 1.3f
                            alpha = (1f - p).coerceIn(0f, 1f)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = palette.primary,
                        modifier = Modifier.size(96.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // 2. Título abajo de la carátula
        AnimatedContent(
            targetState = Pair(nowPlaying.id, nowPlaying.title.ifBlank { "Elige algo para empezar" }),
            transitionSpec = {
                (fadeIn(tween(320)) + slideInHorizontally(spring(dampingRatio = 0.75f, stiffness = 320f)) { skipDir * it / 6 }) togetherWith
                    (fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -skipDir * it / 8 })
            },
            label = "trackTitle",
        ) { (_, title) ->
            Text(
                text = title,
                fontSize = 38.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFF7F4FF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(6.dp))

        // 3. Artista abajo del título (interactivo con enlace a artista)
        AnimatedContent(
            targetState = Pair(nowPlaying.id, nowPlaying.artists to nowPlaying.artist),
            transitionSpec = {
                (fadeIn(tween(320)) + slideInHorizontally(spring(dampingRatio = 0.75f, stiffness = 320f)) { skipDir * it / 6 }) togetherWith
                    (fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -skipDir * it / 8 })
            },
            label = "trackArtist",
        ) { (_, pair) ->
            val (artists, fallbackArtist) = pair
            if (artists.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    artists.forEachIndexed { i, (name, id) ->
                        var artHover by remember { mutableStateOf(false) }
                        Text(
                            text = name,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (artHover) Color.White else Color.White.copy(alpha = 0.65f),
                            textDecoration = if (artHover) TextDecoration.Underline else TextDecoration.None,
                            modifier = Modifier
                                .onPointerEvent(PointerEventType.Enter) { artHover = true }
                                .onPointerEvent(PointerEventType.Exit) { artHover = false }
                                .clickable { onOpenArtist(id, name) },
                        )
                        if (i < artists.lastIndex) {
                            Text(
                                text = "·",
                                fontSize = 21.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.40f),
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = fallbackArtist.ifBlank { "Oto · YouTube Music" },
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 4. Barra de progreso minimal abajo
        MinimalProgressBar(
            progress = progress,
            positionMs = positionMs,
            durationMs = duration,
            quality = quality,
            accent = palette.primary,
            onSeek = onSeek,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MinimalProgressBar(
    progress: Float,
    positionMs: Long,
    durationMs: Long,
    quality: Quality?,
    accent: Color,
    onSeek: (Long) -> Unit,
) {
    var barHovered by remember { mutableStateOf(false) }
    val barHeight by animateFloatAsState(
        targetValue = if (barHovered) 5f else 3f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "barHeight",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) { barHovered = true }
            .onPointerEvent(PointerEventType.Exit) { barHovered = false },
    ) {
        // Línea interactiva con zona táctil y de clic amplia (18.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        val p = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek((p * durationMs).toLong())
                    }
                }
                .pointerInput(durationMs) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val p = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek((p * durationMs).toLong())
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // Fondo de la barra
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
            )
            // Relleno de la barra
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.85f), Color.White),
                        ),
                    ),
            )
            // Tirador / pomo visible al pasar el ratón (estilo Spotify)
            if (barHovered && progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(18.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .shadow(4.dp, CircleShape, ambientColor = accent, spotColor = Color.White)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Fila de tiempos y calidad minimal
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTimeMs(positionMs),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.40f),
            )
            Text(
                text = quality?.toString()?.uppercase() ?: "OPUS",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.30f),
            )
            Text(
                text = formatTimeMs(durationMs),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.40f),
            )
        }
    }
}

@Composable
private fun CoverControlButton(
    size: androidx.compose.ui.unit.Dp,
    isPrimary: Boolean,
    accent: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .shadow(if (isPrimary) 18.dp else 8.dp, CircleShape)
            .clip(CircleShape)
            .background(
                if (isPrimary) {
                    Brush.linearGradient(listOf(accent, Color.White))
                } else {
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.12f)))
                },
            )
            .border(
                width = 1.dp,
                color = if (isPrimary) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.26f),
                shape = CircleShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun LyricsStageColumn(
    lyrics: Lyrics?,
    loading: Boolean,
    positionMs: Long,
    palette: ArtworkPalette,
    onSeek: (Long) -> Unit,
) {
    val listState = rememberLazyListState()

    // Contenedor de letras sin capa offscreen pesada para evitar artefactos de Skia en escritorio
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val boxHeightPx = constraints.maxHeight.toFloat()
        val anchorOffset = (boxHeightPx * 0.32f).toInt()

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Buscando letra sincronizada…",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.primary.copy(alpha = 0.75f),
                    )
                }
            }
            lyrics is Lyrics.Synced && lyrics.lines.isNotEmpty() -> {
                val lines = lyrics.lines
                val activeIdx = remember(lines, positionMs) {
                    lines.indexOfLast { it.start <= positionMs + 120L }.coerceAtLeast(0)
                }
                LaunchedEffect(activeIdx) {
                    runCatching {
                        listState.animateScrollToItem(activeIdx, scrollOffset = -anchorOffset)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 90.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    itemsIndexed(lines, key = { idx, l -> "${l.start}-$idx" }) { idx, line ->
                        val dist = idx - activeIdx
                        SyncedLyricRow(
                            line = line,
                            dist = dist,
                            positionMs = positionMs,
                            palette = palette,
                            onClick = { onSeek(line.start) },
                        )
                    }
                }
            }
            lyrics is Lyrics.Plain && lyrics.lines.isNotEmpty() -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 90.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(lyrics.lines) { _, line: String ->
                        Text(
                            text = line,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.78f),
                            lineHeight = 34.sp,
                        )
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Pura música instrumental o sin letra registrada",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Text(
                        text = "Desliza la rueda del ratón hacia abajo o pulsa la píldora inferior para abrir tu música y explorar.",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.46f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricRow(
    line: Line,
    dist: Int,
    positionMs: Long,
    palette: ArtworkPalette,
    onClick: () -> Unit,
) {
    val isActive = dist == 0
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.035f else if (isHovered) 1.018f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "lyricScale",
    )
    val transX by animateFloatAsState(
        targetValue = if (isActive) 8f else if (isHovered) 5f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "lyricTransX",
    )
    val absDist = kotlin.math.abs(dist)
    val baseAlpha = when {
        isActive -> 1f
        dist == 1 -> 0.58f
        dist == -1 -> 0.40f
        absDist == 2 -> 0.28f
        absDist == 3 -> 0.18f
        else -> 0.10f
    }
    val targetAlpha = if (isHovered && !isActive) 0.88f else baseAlpha
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "lyricAlpha",
    )
    val blurRadius = when {
        isActive || isHovered || absDist <= 1 -> 0.dp
        absDist == 2 -> 0.6.dp
        else -> 1.4.dp
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = transX
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                this.alpha = alpha
            }
            .blur(blurRadius)
            .clip(RoundedCornerShape(12.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp, horizontal = 6.dp),
    ) {
        Text(
            text = line.text.ifBlank { "♪" },
            fontSize = if (isActive) 42.sp else 30.sp,
            fontWeight = if (isActive) FontWeight.Black else FontWeight.Bold,
            color = if (isActive) Color.White else Color.White.copy(alpha = alpha),
            lineHeight = if (isActive) 54.sp else 40.sp,
            style = if (isActive) {
                TextStyle(
                    shadow = Shadow(
                        color = Color.White.copy(alpha = 0.82f),
                        blurRadius = 32f,
                    ),
                )
            } else TextStyle.Default,
        )
    }
}

fun formatTimeMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
