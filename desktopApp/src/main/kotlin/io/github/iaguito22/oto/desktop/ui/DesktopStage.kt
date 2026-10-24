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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
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
@OptIn(ExperimentalComposeUiApi::class)
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
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleLike: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenMenu: () -> Unit,
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
        // 1. Carátula desenfocada de fondo + 3 halos radiales vivos que respiran con la música
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.26f; scaleX = 1.25f; scaleY = 1.25f }
                    .blur(90.dp),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(animPal.primary.copy(alpha = 0.34f), Color.Transparent),
                    center = Offset(w * (0.26f + 0.06f * cos(drift)), h * (0.35f + 0.05f * sin(drift))),
                    radius = w * 0.62f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(animPal.secondary.copy(alpha = 0.25f), Color.Transparent),
                    center = Offset(w * (0.76f - 0.05f * cos(drift)), h * (0.28f + 0.06f * sin(drift))),
                    radius = w * 0.55f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(animPal.tertiary.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(w * 0.52f, h * (0.82f - 0.04f * sin(drift))),
                    radius = w * 0.58f,
                ),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0x3808070C),
                        Color(0x6608070C),
                        Color(0xB808070C),
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
                // Escenario de 2 columnas:
                // Izquierda: Tiempo + Corona 360° con controles hover + Calidad
                // Derecha: Origen + Título + Artista/Guardar + Letras con difuminado suave arriba y abajo
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(52.dp),
                ) {
                    Box(
                        modifier = Modifier.weight(0.44f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CoverCoronaColumn(
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
                        )
                    }

                    Column(
                        modifier = Modifier.weight(0.56f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        // Pill superior alineado al inicio de la columna de la letra
                        TopSourceOriginBar(
                            source = source,
                            accent = animPal.primary,
                            onOpenSource = onOpenSource,
                        )

                        Spacer(Modifier.height(10.dp))

                        // Cabecera: Título arriba, Artista y botón Guardar en la misma fila
                        TrackHeader(
                            title = nowPlaying.title.ifBlank { "Elige algo para empezar" },
                            artist = nowPlaying.artist.ifBlank { "Oto · YouTube Music" },
                            mediaId = nowPlaying.id,
                            accent = animPal.primary,
                            liked = liked,
                            onToggleLike = onToggleLike,
                            skipDir = skipDir,
                        )

                        Spacer(Modifier.height(14.dp))

                        // Columna de letra con difuminado suave arriba y abajo
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

@Composable
private fun TrackHeader(
    title: String,
    artist: String,
    mediaId: String?,
    accent: Color,
    liked: Boolean,
    onToggleLike: () -> Unit,
    skipDir: Int,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedContent(
            targetState = Pair(mediaId, title),
            transitionSpec = {
                (fadeIn(tween(320)) + slideInHorizontally(spring(dampingRatio = 0.75f, stiffness = 320f)) { skipDir * it / 6 }) togetherWith
                    (fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -skipDir * it / 8 })
            },
            label = "trackTitle",
        ) { (_, curTitle) ->
            Text(
                text = curTitle,
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFF7F4FF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AnimatedContent(
                targetState = Pair(mediaId, artist),
                transitionSpec = {
                    (fadeIn(tween(320)) + slideInHorizontally(spring(dampingRatio = 0.75f, stiffness = 320f)) { skipDir * it / 6 }) togetherWith
                        (fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -skipDir * it / 8 })
                },
                label = "trackArtist",
            ) { (_, curArtist) ->
                Text(
                    text = curArtist,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = "·",
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.40f),
            )

            // Botón Guardar / Me gusta interactivo tipo píldora
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (liked) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
                    .border(
                        1.dp,
                        if (liked) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.16f),
                        CircleShape,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleLike,
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (liked) "En Me gusta" else "Guardar en Me gusta",
                    tint = if (liked) accent else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = if (liked) "Guardada" else "Guardar",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (liked) accent else Color.White.copy(alpha = 0.88f),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CoverCoronaColumn(
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
) {
    var discHovered by remember { mutableStateOf(false) }
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    var burstTrigger by remember { mutableLongStateOf(0L) }

    val animTiltX by animateFloatAsState(if (discHovered) tiltX else 0f, spring(stiffness = Spring.StiffnessLow), label = "tiltX")
    val animTiltY by animateFloatAsState(if (discHovered) tiltY else 0f, spring(stiffness = Spring.StiffnessLow), label = "tiltY")
    val controlsAlpha by animateFloatAsState(if (discHovered) 1f else 0f, tween(220, easing = FastOutSlowInEasing), label = "ctrlAlpha")
    val controlsScale by animateFloatAsState(if (discHovered) 1f else 0.92f, spring(dampingRatio = 0.75f, stiffness = 400f), label = "ctrlScale")

    val duration = nowPlaying.durationMs.coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Tiempo desnudo encima de la carátula (sin bordes ni fondo, como la app)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            Text(
                text = formatTimeMs(positionMs),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.primary,
            )
            Text(
                text = "/",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.32f),
            )
            Text(
                text = formatTimeMs(nowPlaying.durationMs),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.54f),
            )
        }

        // Corona 360° + Carátula circular con hover controls
        Box(
            modifier = Modifier
                .size(390.dp)
                .onPointerEvent(PointerEventType.Enter) { discHovered = true }
                .onPointerEvent(PointerEventType.Exit) {
                    discHovered = false
                    tiltX = 0f
                    tiltY = 0f
                }
                .onPointerEvent(PointerEventType.Move) { ev ->
                    val pos = ev.changes.firstOrNull()?.position ?: return@onPointerEvent
                    val cx = 195f
                    val cy = 195f
                    val dx = ((pos.x - cx) / cx).coerceIn(-1f, 1f)
                    val dy = ((pos.y - cy) / cy).coerceIn(-1f, 1f)
                    tiltY = dx * 8f
                    tiltX = -dy * 8f
                }
                .pointerInput(nowPlaying.id) {
                    detectTapGestures(
                        onDoubleTap = {
                            onToggleLike()
                            burstTrigger = System.currentTimeMillis()
                        },
                        onTap = { offset ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val dx = offset.x - cx
                            val dy = offset.y - cy
                            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                            val outerR = size.width / 2f
                            if (dist in (outerR * 0.84f)..outerR && nowPlaying.durationMs > 0L) {
                                var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                                if (deg < 0f) deg += 360f
                                val targetMs = ((deg / 360f) * nowPlaying.durationMs).toLong()
                                onSeek(targetMs)
                            } else {
                                onTogglePlay()
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // Anillo Corona 360°
            Canvas(Modifier.fillMaxSize()) {
                val strokePx = 4.5.dp.toPx()
                val pad = 12.dp.toPx()
                val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
                drawArc(
                    color = Color.White.copy(alpha = 0.11f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(pad, pad),
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                val sweep = 360f * progress
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(palette.primary, palette.secondary, palette.tertiary, palette.primary),
                    ),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(pad, pad),
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                // Punto luminoso en la punta del progreso
                val rad = Math.toRadians((sweep - 90f).toDouble())
                val r = arcSize.width / 2f
                val dotX = size.width / 2f + (r * cos(rad)).toFloat()
                val dotY = size.height / 2f + (r * sin(rad)).toFloat()
                drawCircle(
                    color = palette.primary.copy(alpha = 0.45f),
                    radius = 9.dp.toPx(),
                    center = Offset(dotX, dotY),
                )
                drawCircle(
                    color = Color.White,
                    radius = 4.5.dp.toPx(),
                    center = Offset(dotX, dotY),
                )
            }

            // Carátula circular animada al cambiar de canción + controles internos en hover
            AnimatedContent(
                targetState = nowPlaying.id to artwork,
                transitionSpec = {
                    (fadeIn(tween(360)) +
                        slideInHorizontally(spring(dampingRatio = 0.72f, stiffness = 300f)) { skipDir * it / 3 } +
                        scaleIn(spring(dampingRatio = 0.78f, stiffness = 300f), initialScale = 0.88f)) togetherWith
                        (fadeOut(tween(180)) +
                            slideOutHorizontally(tween(180)) { -skipDir * it / 4 } +
                            scaleOut(tween(180), targetScale = 1.05f))
                },
                label = "discArt",
            ) { (_, bmp) ->
                Box(
                    modifier = Modifier
                        .size(340.dp)
                        .graphicsLayer {
                            rotationX = animTiltX
                            rotationY = animTiltY
                            cameraDistance = 14f * density
                        }
                        .shadow(32.dp, CircleShape, ambientColor = palette.primary, spotColor = palette.primary)
                        .clip(CircleShape)
                        .background(Color(0xFF171422))
                        .border(1.5.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
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
                            modifier = Modifier.size(84.dp),
                        )
                    }

                    // Velo oscuro suave + botones traslúcidos dentro de la carátula SOLO en hover
                    if (controlsAlpha > 0.01f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = controlsAlpha }
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.54f),
                                            Color.Black.copy(alpha = 0.26f),
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
                                horizontalArrangement = Arrangement.spacedBy(22.dp),
                            ) {
                                CoverControlButton(
                                    size = 52.dp,
                                    isPrimary = false,
                                    accent = palette.primary,
                                    onClick = onPrev,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SkipPrevious,
                                        contentDescription = "Anterior",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }

                                CoverControlButton(
                                    size = 72.dp,
                                    isPrimary = true,
                                    accent = palette.primary,
                                    onClick = onTogglePlay,
                                ) {
                                    Icon(
                                        imageVector = if (nowPlaying.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (nowPlaying.playing) "Pausar" else "Reproducir",
                                        tint = Color(0xFF120D1D),
                                        modifier = Modifier.size(36.dp),
                                    )
                                }

                                CoverControlButton(
                                    size = 52.dp,
                                    isPrimary = false,
                                    accent = palette.primary,
                                    onClick = onNext,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SkipNext,
                                        contentDescription = "Siguiente",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                            }

                            // Botón inferior derecho de guardar en la propia carátula
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(22.dp)
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.62f))
                                    .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                                    .clickable(onClick = onToggleLike),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Guardar",
                                    tint = if (liked) palette.primary else Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    }

                    // HeartBurst animado al hacer doble clic
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
            }
        }

        // Calidad desnuda debajo de la carátula (sin bordes ni fondo)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = palette.secondary.copy(alpha = 0.85f),
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = quality?.toString()?.uppercase() ?: "—",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.48f),
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

    // Difuminado generoso arriba y abajo con máscara de gradiente vertical (BlendMode.DstOut)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fadeTop = 130.dp.toPx()
                val fadeBottom = 160.dp.toPx()
                // Difuminado superior suave
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startY = 0f,
                        endY = fadeTop,
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, fadeTop),
                    blendMode = BlendMode.DstOut,
                )
                // Difuminado inferior suave
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startY = size.height - fadeBottom,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - fadeBottom),
                    size = Size(size.width, fadeBottom),
                    blendMode = BlendMode.DstOut,
                )
            },
    ) {
        val boxHeightPx = constraints.maxHeight.toFloat()
        val anchorOffset = (boxHeightPx * 0.30f).toInt()

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
                    contentPadding = PaddingValues(top = 40.dp, bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
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
                    contentPadding = PaddingValues(top = 40.dp, bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(lyrics.lines) { _, line: String ->
                        Text(
                            text = line,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.78f),
                            lineHeight = 30.sp,
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
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.045f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "lyricScale",
    )
    val transX by animateFloatAsState(
        targetValue = if (isActive) 8f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "lyricTransX",
    )
    val absDist = kotlin.math.abs(dist)
    val targetAlpha = when {
        isActive -> 1f
        dist == 1 -> 0.62f
        dist == -1 -> 0.42f
        absDist == 2 -> 0.32f
        absDist == 3 -> 0.22f
        else -> 0.15f
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "lyricAlpha",
    )
    val blurRadius = when {
        isActive || absDist <= 1 -> 0.dp
        absDist == 2 -> 0.8.dp
        else -> 1.6.dp
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp, horizontal = 6.dp),
    ) {
        Text(
            text = line.text.ifBlank { "♪" },
            fontSize = if (isActive) 28.sp else 22.sp,
            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
            color = if (isActive) Color.White else Color.White.copy(alpha = alpha),
            lineHeight = if (isActive) 36.sp else 29.sp,
            style = if (isActive) {
                TextStyle(
                    shadow = Shadow(
                        color = palette.primary.copy(alpha = 0.65f),
                        blurRadius = 26f,
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
