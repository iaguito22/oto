package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Typography
import androidx.compose.material3.Text
import androidx.compose.ui.text.platform.Font
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iaguito22.oto.lyrics.Line
import io.github.iaguito22.oto.playback.Quality
import io.github.iaguito22.oto.lyrics.Lyrics
import io.github.iaguito22.oto.playback.NowPlaying
import io.github.iaguito22.oto.playback.Source
import io.github.iaguito22.oto.desktop.ui.MobileIcons
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
    isIdle: Boolean = false,
    menuOpen: Boolean = false,
    headerStyle: Int = 0,
    showRemaining: Boolean = false,
    onToggleShowRemaining: () -> Unit = {},
    coverBeat: Boolean = true,
    nextArtwork: String? = null,
    prevArtwork: String? = null,
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

    var isVolHovered by remember { mutableStateOf(false) }
    var preMuteVol by remember { mutableFloatStateOf(1f) }
    var isPointerOverLyrics by remember { mutableStateOf(false) }
    var showVolOsd by remember { mutableStateOf(false) }
    LaunchedEffect(volume) {
        showVolOsd = true
        kotlinx.coroutines.delay(1800L)
        showVolOsd = false
    }
    val volAlpha by animateFloatAsState(
        targetValue = when {
            menuOpen -> 0f
            isVolHovered -> 1f
            showVolOsd -> 0.85f
            else -> 0f
        },
        animationSpec = tween(220),
        label = "volAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animPal.deep)
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy > 0.5f && !isPointerOverLyrics) {
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
                        Color(0x55080808),
                        Color(0x28080808),
                        Color(0x88080808),
                    ),
                ),
            )
        }

        // 2. Contenido principal centrado abarcando de carátula a letra.
        // Al abrir el menú (menuOpen), el escenario se retrae, desenfoca y atenúa como en core.css (.sheet-open .oto-stage-wrap).
        val stageScale by animateFloatAsState(
            targetValue = if (menuOpen) 0.945f else 1f,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 280f),
            label = "stageScale",
        )
        val stageTransY by animateFloatAsState(
            targetValue = if (menuOpen) -14f else 0f,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 280f),
            label = "stageTransY",
        )
        val stageAlpha by animateFloatAsState(
            targetValue = if (menuOpen) 0.85f else 1f,
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "stageAlpha",
        )
        val stageBlur by animateFloatAsState(
            targetValue = if (menuOpen) 14f else 0f,
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "stageBlur",
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = stageScale
                    scaleY = stageScale
                    translationY = stageTransY
                    alpha = stageAlpha
                }
                .blur(stageBlur.dp)
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 1520.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Cabecera de origen superior centrada (se oculta suavemente en modo reposo idle o con cue activo, como en fusion.html)
                val showTopBar = !isIdle && cue == null && !menuOpen
                val topBarAlpha by animateFloatAsState(
                    targetValue = if (showTopBar) 1f else 0f,
                    animationSpec = tween(360, easing = FastOutSlowInEasing),
                    label = "topBarAlpha",
                )
                val topBarTransY by animateFloatAsState(
                    targetValue = if (showTopBar) 0f else -8f,
                    animationSpec = tween(360, easing = FastOutSlowInEasing),
                    label = "topBarTransY",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .graphicsLayer {
                            alpha = topBarAlpha
                            translationY = topBarTransY
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    TopSourceOriginBar(
                        source = source,
                        accent = animPal.primary,
                        onOpenSource = onOpenSource,
                    )
                }

                // Escenario reactivo (si caben juntos >= 820dp: carátula + letra; si no caben < 820dp: solo carátula centrada)
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 18.dp),
                ) {
                    val showLyrics = maxWidth >= 820.dp
                    if (showLyrics) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(68.dp),
                        ) {
                            Box(
                                modifier = Modifier.weight(0.44f).fillMaxHeight(),
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
                                    showRemaining = showRemaining,
                                    onToggleShowRemaining = onToggleShowRemaining,
                                    coverBeat = coverBeat,
                                    nextArtwork = nextArtwork,
                                    prevArtwork = prevArtwork,
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

                            // Columna de letra a pantalla completa con difuminado suave arriba/abajo
                            Box(
                                modifier = Modifier
                                    .weight(0.56f)
                                    .fillMaxHeight()
                                    .onPointerEvent(PointerEventType.Enter) { isPointerOverLyrics = true }
                                    .onPointerEvent(PointerEventType.Exit) { isPointerOverLyrics = false },
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
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
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
                                showRemaining = showRemaining,
                                onToggleShowRemaining = onToggleShowRemaining,
                                coverBeat = coverBeat,
                                nextArtwork = nextArtwork,
                                prevArtwork = prevArtwork,
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
                    }
                }
            }
        }

        // Píldora interactiva permanente de volumen estilo liquid glass (arriba a la derecha)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 26.dp, end = 32.dp)
                .graphicsLayer {
                    alpha = volAlpha
                    translationY = (1f - volAlpha) * -8f
                }
                .shadow(20.dp, CircleShape, ambientColor = animPal.primary.copy(alpha = 0.35f), spotColor = Color.Black)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xE61B1B1B),
                            Color(0xF0121212),
                        ),
                    ),
                )
                .border(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = if (isVolHovered) 0.50f else 0.25f),
                            animPal.primary.copy(alpha = 0.40f),
                            Color.White.copy(alpha = 0.15f),
                        ),
                    ),
                    CircleShape,
                )
                .pointerHoverIcon(PointerIcon.Default)
                .onPointerEvent(PointerEventType.Enter) { isVolHovered = true }
                .onPointerEvent(PointerEventType.Exit) { isVolHovered = false }
                .onPointerEvent(PointerEventType.Scroll) { ev ->
                    val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy < -0.1f) onAdjustVolume(0.04f)
                    else if (dy > 0.1f) onAdjustVolume(-0.04f)
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
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
                    imageVector = MobileIcons.speaker,
                    contentDescription = if (volume <= 0.001f) "Desmutear" else "Silenciar",
                    tint = if (volume <= 0.001f) Color.White.copy(alpha = 0.36f) else animPal.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Box(
                modifier = Modifier
                    .width(84.dp)
                    .height(18.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val ratio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onAdjustVolume(ratio - volume)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val delta = dragAmount.x / 84f
                            onAdjustVolume(delta)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(volume.coerceIn(0f, 1f))
                            .clip(CircleShape)
                            .background(animPal.primary),
                    )
                }
            }
            Text(
                text = "${(volume * 100f).roundToInt()}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.width(36.dp),
            )
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
                                    Color(0xE61E1E1E),
                                    Color(0xF2121212),
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopSourceOriginBar(
    source: Source?,
    accent: Color,
    onOpenSource: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.03f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sourceScale",
    )
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenSource,
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = (source?.kind ?: "DESDE TU LISTA").uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.6.sp,
            color = if (hovered) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.62f),
        )
        Text(
            text = source?.title ?: "Me gusta",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (hovered) accent else Color(0xFFFFFFFF),
        )
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
    showRemaining: Boolean = false,
    onToggleShowRemaining: () -> Unit = {},
    coverBeat: Boolean = true,
    nextArtwork: String? = null,
    prevArtwork: String? = null,
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

    // Latido de la carátula al reproducir
    val beatInf = rememberInfiniteTransition(label = "coverBeat")
    val beatPulse by beatInf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1150
                0.0f at 0
                1.0f at 130 using FastOutSlowInEasing
                0.18f at 340 using FastOutSlowInEasing
                0.55f at 480 using FastOutSlowInEasing
                0.0f at 820 using LinearEasing
                0.0f at 1150
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "beatPulse",
    )
    val activePulse = if (coverBeat && nowPlaying.playing) beatPulse else 0f
    val beatScale = 1f + 0.026f * activePulse

    // Asomo (peek) de carátula de siguiente / anterior en hover
    var hoverPrev by remember { mutableStateOf(false) }
    var hoverNext by remember { mutableStateOf(false) }

    var prevThumbBmp by remember(prevArtwork) { mutableStateOf<ImageBitmap?>(null) }
    var nextThumbBmp by remember(nextArtwork) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(prevArtwork) {
        if (!prevArtwork.isNullOrBlank()) prevThumbBmp = ArtworkLoader.load(prevArtwork).first
    }
    LaunchedEffect(nextArtwork) {
        if (!nextArtwork.isNullOrBlank()) nextThumbBmp = ArtworkLoader.load(nextArtwork).first
    }

    val prevPeekOffset by animateDpAsState(if (hoverPrev && prevThumbBmp != null) (-42).dp else 0.dp, spring(dampingRatio = 0.72f, stiffness = 380f))
    val prevPeekAlpha by animateFloatAsState(if (hoverPrev && prevThumbBmp != null) 0.88f else 0f, tween(200))
    val nextPeekOffset by animateDpAsState(if (hoverNext && nextThumbBmp != null) 42.dp else 0.dp, spring(dampingRatio = 0.72f, stiffness = 380f))
    val nextPeekAlpha by animateFloatAsState(if (hoverNext && nextThumbBmp != null) 0.88f else 0f, tween(200))

    // Inclinación 3D suave y reflejo especular al mover el ratón sobre la carátula (como en fusion.html)
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    var sheenX by remember { mutableFloatStateOf(0.5f) }
    var sheenY by remember { mutableFloatStateOf(0.5f) }
    val animTiltX by animateFloatAsState(
        targetValue = if (isCoverActive) tiltX else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 260f),
        label = "tiltX",
    )
    val animTiltY by animateFloatAsState(
        targetValue = if (isCoverActive) tiltY else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 260f),
        label = "tiltY",
    )

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

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val coverSize = minOf(maxWidth, maxHeight - 135.dp).coerceIn(200.dp, 500.dp)

        Column(
            modifier = Modifier.width(coverSize),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center,
        ) {
        // 1. Carátula cuadrada con bordes redondeados + 3D tilt + controles limpios en hover + asomo (peek)
        Box(
            modifier = Modifier.size(coverSize),
            contentAlignment = Alignment.Center,
        ) {
            // Asomo de la anterior por la izquierda
            if (prevThumbBmp != null && prevPeekAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(coverSize * 0.86f)
                        .graphicsLayer {
                            translationX = prevPeekOffset.toPx()
                            alpha = prevPeekAlpha
                            scaleX = 0.88f
                            scaleY = 0.88f
                            rotationZ = -3.5f
                        }
                        .shadow(18.dp, RoundedCornerShape(18.dp))
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF141414))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp)),
                ) {
                    Image(
                        bitmap = prevThumbBmp!!,
                        contentDescription = "Anterior",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Asomo de la siguiente por la derecha
            if (nextThumbBmp != null && nextPeekAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(coverSize * 0.86f)
                        .graphicsLayer {
                            translationX = nextPeekOffset.toPx()
                            alpha = nextPeekAlpha
                            scaleX = 0.88f
                            scaleY = 0.88f
                            rotationZ = 3.5f
                        }
                        .shadow(18.dp, RoundedCornerShape(18.dp))
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF141414))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp)),
                ) {
                    Image(
                        bitmap = nextThumbBmp!!,
                        contentDescription = "Siguiente",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(coverSize)
                    .hoverable(coverInteraction)
                    .onPointerEvent(PointerEventType.Enter) { manualHover = true }
                    .onPointerEvent(PointerEventType.Move) { ev ->
                        manualHover = true
                        val pos = ev.changes.firstOrNull()?.position
                        if (pos != null) {
                            val nx = (pos.x / 500f).coerceIn(0f, 1f)
                            val ny = (pos.y / 500f).coerceIn(0f, 1f)
                            sheenX = nx
                            sheenY = ny
                            tiltY = (nx - 0.5f) * 8f
                            tiltX = (0.5f - ny) * 8f
                        }
                    }
                    .onPointerEvent(PointerEventType.Exit) {
                        manualHover = false
                        tiltX = 0f
                        tiltY = 0f
                    }
                    .onPointerEvent(PointerEventType.Scroll) { ev ->
                        val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy < -0.1f) onAdjustVolume(0.04f)
                        else if (dy > 0.1f) onAdjustVolume(-0.04f)
                    }
                    .graphicsLayer {
                        rotationX = animTiltX
                        rotationY = animTiltY
                        cameraDistance = 16f * density
                        scaleX = beatScale
                        scaleY = beatScale
                    }
                    .shadow(
                        elevation = 28.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = Color.Black.copy(alpha = 0.6f),
                        spotColor = Color.Black.copy(alpha = 0.85f),
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF141414))
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
                        slideInHorizontally(spring(dampingRatio = 0.72f, stiffness = 220f)) { skipDir * it * 4 / 5 } +
                        scaleIn(spring(dampingRatio = 0.78f, stiffness = 300f), initialScale = 0.92f)) togetherWith
                        (fadeOut(tween(180)) +
                            slideOutHorizontally(tween(260)) { -skipDir * it * 3 / 5 } +
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
                            imageVector = MobileIcons.libraryMusic,
                            contentDescription = null,
                            tint = palette.primary.copy(alpha = 0.55f),
                            modifier = Modifier.size(80.dp),
                        )
                    }
                }
            }

            // Controles internos en la carátula visibles en hover (calcados de fusion.html)
            if (controlsAlpha > 0.001f || isCoverActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = controlsAlpha }
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF0B0B0B).copy(alpha = 0.58f),
                                    Color(0xFF0B0B0B).copy(alpha = 0.28f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // Reflejo especular suave que sigue el cursor sobre la carátula
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                                center = Offset(size.width * sheenX, size.height * sheenY),
                                radius = size.width * 0.55f,
                            ),
                        )
                    }

                    Row(
                        modifier = Modifier.graphicsLayer {
                            scaleX = controlsScale
                            scaleY = controlsScale
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(26.dp),
                    ) {
                        CoverControlButton(
                            size = 48.dp,
                            isPrimary = false,
                            accent = palette.primary,
                            modifier = Modifier
                                .onPointerEvent(PointerEventType.Enter) { hoverPrev = true }
                                .onPointerEvent(PointerEventType.Exit) { hoverPrev = false },
                            onClick = onPrev,
                        ) {
                            Icon(
                                imageVector = MobileIcons.skipPrevious,
                                contentDescription = "Anterior",
                                tint = Color(0xFFFFFFFF),
                                modifier = Modifier.size(28.dp),
                            )
                        }

                        CoverControlButton(
                            size = 66.dp,
                            isPrimary = true,
                            accent = palette.primary,
                            onClick = onTogglePlay,
                        ) {
                            Icon(
                                imageVector = if (nowPlaying.playing) MobileIcons.pause else MobileIcons.play,
                                contentDescription = if (nowPlaying.playing) "Pausar" else "Reproducir",
                                tint = Color(0xFF151515),
                                modifier = Modifier.size(34.dp),
                            )
                        }

                        CoverControlButton(
                            size = 48.dp,
                            isPrimary = false,
                            accent = palette.primary,
                            modifier = Modifier
                                .onPointerEvent(PointerEventType.Enter) { hoverNext = true }
                                .onPointerEvent(PointerEventType.Exit) { hoverNext = false },
                            onClick = onNext,
                        ) {
                            Icon(
                                imageVector = MobileIcons.skipNext,
                                contentDescription = "Siguiente",
                                tint = Color(0xFFFFFFFF),
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                    // Píldora inferior centrada en hover: Me gusta + Guardar en lista (como .cover-like-pill en fusion.html)
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 18.dp)
                            .shadow(12.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.45f))
                            .clip(CircleShape)
                            .background(Color(0xFF121212).copy(alpha = 0.68f))
                            .border(1.dp, if (liked) palette.primary.copy(alpha = 0.48f) else Color.White.copy(alpha = 0.16f), CircleShape)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(onClick = onToggleLike),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (liked) MobileIcons.favorite else MobileIcons.favoriteOutline,
                                contentDescription = if (liked) "Quitar de Me gusta" else "Añadir a Me gusta",
                                tint = if (liked) palette.primary else Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(onClick = onSaveTo),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = MobileIcons.playlistAdd,
                                contentDescription = "Guardar en lista",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Marca discreta visible en reposo si está en Me gusta
            if (!isCoverActive && liked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.46f))
                        .border(1.dp, palette.primary.copy(alpha = 0.45f), CircleShape)
                        .clickable(onClick = onSaveTo),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MobileIcons.favorite,
                        contentDescription = "Guardado en Me gusta",
                        tint = palette.primary,
                        modifier = Modifier.size(15.dp),
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
                        imageVector = MobileIcons.favorite,
                        contentDescription = null,
                        tint = palette.primary,
                        modifier = Modifier.size(96.dp),
                    )
                }
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
                fontSize = 42.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFFFFFFF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(8.dp))

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
                            fontSize = 23.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (artHover) Color.White else Color.White.copy(alpha = 0.65f),
                            textDecoration = if (artHover) TextDecoration.Underline else TextDecoration.None,
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .onPointerEvent(PointerEventType.Enter) { artHover = true }
                                .onPointerEvent(PointerEventType.Exit) { artHover = false }
                                .clickable { onOpenArtist(id, name) },
                        )
                        if (i < artists.lastIndex) {
                            Text(
                                text = "·",
                                fontSize = 23.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.36f),
                            )
                        }
                    }
                }
            } else {
                var artHover by remember { mutableStateOf(false) }
                Text(
                    text = fallbackArtist.ifBlank { "Oto · YouTube Music" },
                    fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (artHover) Color.White else Color.White.copy(alpha = 0.65f),
                    textDecoration = if (artHover) TextDecoration.Underline else TextDecoration.None,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .onPointerEvent(PointerEventType.Enter) { artHover = true }
                        .onPointerEvent(PointerEventType.Exit) { artHover = false }
                        .clickable { onOpenArtist(null, fallbackArtist) },
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
            showRemaining = showRemaining,
            onToggleShowRemaining = onToggleShowRemaining,
            onSeek = onSeek,
        )
        }
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
    showRemaining: Boolean = false,
    onToggleShowRemaining: () -> Unit = {},
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

        // Fila de tiempos y calidad minimal: oculta en reposo como en Spotify Caelestia, aparece suavemente en hover
        val metaAlpha by animateFloatAsState(
            targetValue = if (barHovered) 1f else 0f,
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            label = "barMetaAlpha",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .graphicsLayer { alpha = metaAlpha },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTimeMs(positionMs),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.62f),
            )
            Text(
                text = quality?.toString()?.uppercase() ?: "OPUS",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.36f),
            )
            val durationText = if (showRemaining && durationMs > 0L) {
                val rem = (durationMs - positionMs).coerceAtLeast(0L)
                "-${formatTimeMs(rem)}"
            } else {
                formatTimeMs(durationMs)
            }
            Text(
                text = durationText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.62f),
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleShowRemaining,
                    ),
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CoverControlButton(
    size: androidx.compose.ui.unit.Dp,
    isPrimary: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (hovered) (if (isPrimary) 1.07f else 1.15f) else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
        label = "coverBtnScale",
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (isPrimary) {
                    Modifier
                        .shadow(22.dp, CircleShape, ambientColor = accent, spotColor = accent)
                        .clip(CircleShape)
                        .background(accent)
                        .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                } else {
                    Modifier
                        .clip(CircleShape)
                        .background(if (hovered) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                },
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
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
internal fun LyricsStageColumn(
    lyrics: Lyrics?,
    loading: Boolean,
    positionMs: Long,
    palette: ArtworkPalette,
    onSeek: (Long) -> Unit,
) {
    val listState = rememberLazyListState()

    // Máscara vertical suave superior e inferior (idéntica a mask-image en core.css:590 y spicetify-caelestia:2410)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.13f to Color.Black,
                        0.85f to Color.Black,
                        1.00f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        val boxHeightPx = constraints.maxHeight.toFloat()
        val anchorOffset = (boxHeightPx * 0.35f).toInt()
        val topPad = with(LocalDensity.current) { (boxHeightPx * 0.35f).toDp() }
        val bottomPad = with(LocalDensity.current) { (boxHeightPx * 0.65f).toDp() }

        var userScrolledAt by remember { mutableLongStateOf(0L) }
        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) {
                userScrolledAt = System.currentTimeMillis()
            }
        }

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = "Buscando letra sincronizada…",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.primary.copy(alpha = 0.78f),
                    )
                }
            }
            lyrics is Lyrics.Synced && lyrics.lines.isNotEmpty() -> {
                val lines = lyrics.lines
                val activeIdx = remember(lines, positionMs) {
                    lines.indexOfLast { it.start <= positionMs + 250L }.coerceAtLeast(0)
                }
                LaunchedEffect(activeIdx, userScrolledAt) {
                    val elapsed = System.currentTimeMillis() - userScrolledAt
                    if (elapsed < 2500L) {
                        kotlinx.coroutines.delay(2500L - elapsed)
                    }
                    runCatching {
                        listState.animateScrollToItem(activeIdx, scrollOffset = -anchorOffset)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    itemsIndexed(lines, key = { idx, l -> "${l.start}-$idx" }) { idx, line ->
                        val dist = idx - activeIdx
                        val nextStart = lines.getOrNull(idx + 1)?.start ?: (line.start + 4500L)
                        SyncedLyricRow(
                            line = line,
                            nextLineStart = nextStart,
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
                    contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(lyrics.lines) { _, line: String ->
                        Text(
                            text = line,
                            fontSize = 29.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.78f),
                            lineHeight = 40.sp,
                        )
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Instrumental o sin letra sincronizada",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.78f),
                    )
                    Text(
                        text = "Desliza la rueda del ratón hacia abajo o pulsa Ctrl+K para abrir tu música y explorar.",
                        fontSize = 15.sp,
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
    nextLineStart: Long,
    dist: Int,
    positionMs: Long,
    palette: ArtworkPalette,
    onClick: () -> Unit,
) {
    val isActive = dist == 0
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.03f else if (isHovered) 1.015f else 1f,
        animationSpec = spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow),
        label = "lyricScale",
    )
    val transX by animateFloatAsState(
        targetValue = if (isActive) 8f else if (isHovered) 5f else 0f,
        animationSpec = spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow),
        label = "lyricTransX",
    )
    val absDist = abs(dist)
    val baseAlpha = when {
        isActive -> 1f
        dist == 1 -> 0.44f
        dist == -1 -> 0.34f
        absDist == 2 -> 0.22f
        absDist == 3 -> 0.14f
        else -> 0.09f
    }
    val targetAlpha = if (isHovered && !isActive) 0.85f else baseAlpha
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "lyricAlpha",
    )
    val blurRadius = when {
        isActive || isHovered || absDist <= 1 -> 0.dp
        absDist == 2 -> 0.7.dp
        else -> 1.5.dp
    }

    val rawText = line.text.ifBlank { "♪" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = transX
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                this.alpha = if (isActive) 1f else alpha
            }
            .blur(blurRadius)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        if (isActive) {
            // Karaoke palabra por palabra (como en Spotify Caelestia y core.js:388-401):
            // Las palabras ya cantadas brillan en blanco puro; las que faltan de la línea van al 48%, sin caja de sombra Skia.
            val words = remember(rawText) { rawText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
            val spanDur = (nextLineStart - line.start).coerceAtLeast(600L)
            val lineProg = ((positionMs - line.start).toFloat() / spanDur.toFloat()).coerceIn(0f, 1f)
            val exactWord = lineProg * words.size
            val currentWordIdx = exactWord.toInt()
            val wordSubProg = (exactWord - currentWordIdx).coerceIn(0f, 1f)

            val annotated = buildAnnotatedString {
                val isBeforeFirstLine = positionMs < line.start
                words.forEachIndexed { idx, w ->
                    if (idx > 0) append(" ")
                    val wordAlpha = when {
                        isBeforeFirstLine -> 0.96f
                        idx < currentWordIdx -> 1f
                        idx == currentWordIdx -> 0.92f + 0.08f * wordSubProg
                        else -> 0.48f
                    }
                    withStyle(
                        SpanStyle(
                            color = Color.White.copy(alpha = wordAlpha),
                        ),
                    ) {
                        append(w)
                    }
                }
            }

            Text(
                text = annotated,
                fontFamily = OtoFontFamily,
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                lineHeight = 50.sp,
            )
        } else {
            Text(
                text = rawText,
                fontFamily = OtoFontFamily,
                fontSize = 31.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = Color.White,
                lineHeight = 42.sp,
            )
        }
    }
}

fun formatTimeMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
