package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.hoverable
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import io.github.iaguito22.oto.playback.Source
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.text.style.TextOverflow
import io.github.iaguito22.oto.playback.NowPlaying
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlin.math.*

internal enum class PlayerForm(val label:String, val width:Int, val height:Int) {
    Full("Carátula",360,640), Horizontal("Horizontal",351,108), Vertical("Vertical",96,240)
}

/** Barra de 2 px, área táctil amplia y sin pomo permanente, como la del móvil. */
@Composable
internal fun FineSlider(value:Float, onChange:(Float)->Unit, modifier:Modifier=Modifier, label:String="Posición", onFinish:()->Unit={}, expanded:Boolean=false) {
    val current by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinish)
    val fraction by rememberUpdatedState(value.coerceIn(0f,1f))
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val emphasis by animateFloatAsState(if(expanded || hovered || dragging) 1f else 0f,tween(160),label="sliderHover")
    Canvas(modifier.height(22.dp).fillMaxWidth().hoverable(interaction).semantics {
        contentDescription=label;progressBarRangeInfo=ProgressBarRangeInfo(fraction,0f..1f)
        setProgress { current(it.coerceIn(0f,1f));finish();true }
    }.pointerInput(Unit) {
        detectTapGestures { current((it.x/size.width).coerceIn(0f,1f));finish() }
    }.pointerInput(Unit) {
        detectDragGestures(onDragStart={dragging=true},onDragEnd={dragging=false;finish()},onDragCancel={dragging=false;finish()}) { change,_ -> change.consume();current((change.position.x/size.width).coerceIn(0f,1f)) }
    }) {
        val y=size.height/2
        drawLine(Color.White.copy(alpha=.13f),Offset(0f,y),Offset(size.width,y),(2.dp + 2.dp * emphasis).toPx(),StrokeCap.Round)
        if(fraction>0)drawLine(Color.White.copy(alpha=.85f),Offset(0f,y),Offset(size.width*fraction,y),(2.dp + 2.dp * emphasis).toPx(),StrokeCap.Round)
        if(emphasis>0f) drawCircle(Color.White.copy(alpha=emphasis),radius=(4.dp * emphasis).toPx(),center=Offset(size.width*fraction,y))
    }
}

@Composable
internal fun MinimalPlayback(playing:Boolean, toggle:()->Unit, modifier:Modifier=Modifier, controlSize:Dp=42.dp) {
    val phase by animateFloatAsState(if(playing)1f else 0f,tween(240,easing=Motion.Standard),label="playPause")
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if(hovered)1.22f else 1f,Motion.hover(),label="playHover")
    val press by animateFloatAsState(if(pressed) .84f else 1f,if(pressed) Motion.press() else Motion.release(),label="playPress")
    val halo by animateFloatAsState(if(pressed) .16f else if(hovered) .10f else 0f,tween(180,easing=Motion.Standard),label="playHalo")
    Box(modifier.size(controlSize).graphicsLayer { scaleX=press;scaleY=press }.clip(CircleShape).background(Color.White.copy(alpha=halo)).hoverable(interaction).clickable(interactionSource=interaction,indication=null,onClickLabel=if(playing)"Pausar" else "Reproducir",onClick=toggle).semantics {contentDescription=if(playing)"Pausar" else "Reproducir"},contentAlignment=Alignment.Center) {
        // Escala en la capa gráfica: el hover no vuelve a medir los controles vecinos.
        Canvas(Modifier.size(19.dp).graphicsLayer { scaleX=scale;scaleY=scale }) {
            val c=Color.White.copy(alpha=.95f)
            if(phase<1f) {
                val p=Path().apply {moveTo(size.width*.32f,size.height*.16f);lineTo(size.width*.82f,size.height*.5f);lineTo(size.width*.32f,size.height*.84f);close()}
                drawPath(p,c.copy(alpha=(1-phase)*.95f),style=Stroke(1.8.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
            }
            if(phase>0f)for(x in listOf(.34f,.68f))drawLine(c.copy(alpha=phase*.95f),Offset(size.width*x,size.height*.18f),Offset(size.width*x,size.height*.82f),1.8.dp.toPx(),StrokeCap.Round)
        }
    }
}

@Composable
internal fun rememberCoverPulse(playing: Boolean, enabled: Boolean, beat: () -> Float): State<Float> {
    val pulse = remember { mutableFloatStateOf(0f) }
    val currentBeat by rememberUpdatedState(beat)
    LaunchedEffect(playing, enabled) {
        var last=0L
        while (playing && enabled || pulse.floatValue > .001f) {
            withFrameNanos { now ->
                val dt=if(last==0L) 1f/60 else ((now-last)/1e9f).coerceIn(0f,.1f); last=now
                val target=if(playing && enabled) currentBeat() else 0f
                val current = pulse.floatValue
                pulse.floatValue=if(target>current) current+(target-current)*min(1f,dt/.025f) else current*exp(-dt/.16f)
            }
            // El latido no necesita despertar toda una ventana en cada refresco de
            // una pantalla de 120/144 Hz. El tiempo real mantiene la curva del pulso.
            delay(10)
        }
        if (!playing || !enabled) pulse.floatValue=0f
    }
    return pulse
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerSurface(np:NowPlaying, position:StateFlow<Long>, artwork:ImageBitmap?, form:PlayerForm, chrome:Boolean,
    beat:()->Float, beatEnabled:Boolean, liked:Boolean, showRemaining:Boolean, onPlay:()->Unit,onPrev:()->Unit,onNext:()->Unit,onSeek:(Long)->Unit,
    libraryOpen:Boolean,lyricsOpen:Boolean,queueOpen:Boolean,onLibrary:()->Unit,onLyrics:()->Unit,onQueue:()->Unit,
    onLike:()->Unit,onSave:()->Unit, source:Source?, onSource:()->Unit, onArtist:(String,String)->Unit,
    qualityLabel:String? = null, verticalPin:(@Composable () -> Unit)? = null,
    shuffle:Boolean=false, loopMode:io.github.iaguito22.oto.LoopMode=io.github.iaguito22.oto.LoopMode.Off,
    onShuffle:(()->Unit)?=null, onLoop:(()->Unit)?=null) {
    val pulse = rememberCoverPulse(np.playing,beatEnabled,beat)
    val controls by animateFloatAsState(if(chrome)1f else 0f,tween(180),label="playerChrome")
    // Sentido del último salto: «Anterior» desliza hacia la derecha; el resto, hacia la izquierda.
    // Vuelve a «adelante» poco después, por si «Anterior» solo reinició la canción.
    var direction by remember { mutableIntStateOf(1) }
    var directionStamp by remember { mutableIntStateOf(0) }
    LaunchedEffect(directionStamp) { if(direction != 1) { delay(1500); direction=1 } }
    val previous:()->Unit = { direction=-1; directionStamp++; onPrev() }
    val next:()->Unit = { direction=1; directionStamp++; onNext() }
    @Composable fun Cover(modifier:Modifier) {
        AnimatedContent(artwork, transitionSpec={
            val d=direction
            (slideInHorizontally(Motion.slide()) { d*it } + fadeIn(tween(160))) togetherWith
                (slideOutHorizontally(tween(320,easing=Motion.Standard)) { -d*it } +
                    scaleOut(tween(320,easing=Motion.Standard),targetScale=.9f) + fadeOut(tween(280)))
        },label="artwork",modifier=modifier.clipToBounds()) { image ->
            Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) {
            Box(Modifier.fillMaxSize(.975f).graphicsLayer { val s=1f+.022f*pulse.value;scaleX=s;scaleY=s }.clip(RoundedCornerShape(14.dp)).combinedClickable(onDoubleClick=onLike,onClick=onPlay),contentAlignment=Alignment.Center) {
                if(image!=null)Image(image,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop) else Text("音",style=OtoType.display,color=Color.White.copy(alpha=.2f))
            }
            }
        }
    }
    @Composable fun Transport(reveal:Boolean=false, compact:Boolean=false, modes:Boolean=false) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
            val accent=MaterialTheme.colorScheme.primary
            if(modes && onShuffle!=null) {
                HoverAction(MobileIcons.shuffle,if(shuffle)"Desactivar aleatorio" else "Activar aleatorio",chrome,onShuffle,
                    Modifier.graphicsLayer { alpha=controls },tint=if(shuffle) accent else Color.White.copy(alpha=.45f))
                Spacer(Modifier.width(10.dp))
            }
            HoverAction(MobileIcons.skipPrevious,"Anterior",!reveal||chrome,previous,Modifier.graphicsLayer{alpha=if(reveal)controls else 1f},size=if(compact)26.dp else 36.dp)
            MinimalPlayback(np.playing,onPlay,controlSize=if(compact)28.dp else 42.dp)
            HoverAction(MobileIcons.skipNext,"Siguiente",!reveal||chrome,next,Modifier.graphicsLayer{alpha=if(reveal)controls else 1f},size=if(compact)26.dp else 36.dp)
            if(modes && onLoop!=null) {
                Spacer(Modifier.width(10.dp))
                HoverAction(if(loopMode==io.github.iaguito22.oto.LoopMode.One) MobileIcons.repeatOne else MobileIcons.repeat,
                    when(loopMode) {
                        io.github.iaguito22.oto.LoopMode.Off -> "Repetir la lista"
                        io.github.iaguito22.oto.LoopMode.All -> "Repitiendo la lista · repetir esta canción"
                        io.github.iaguito22.oto.LoopMode.One -> "Repitiendo esta canción · quitar bucle"
                    },chrome,onLoop,Modifier.graphicsLayer { alpha=controls },
                    tint=if(loopMode!=io.github.iaguito22.oto.LoopMode.Off) accent else Color.White.copy(alpha=.45f))
            }
        }
    }
    @Composable fun Saved(modifier:Modifier=Modifier) {
        val interaction=remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val scale by animateFloatAsState(if(hovered)1.22f else 1f,Motion.hover(),label="savedHover")
        val tint by animateFloatAsState(if(liked).95f else .55f,tween(220),label="savedTint")
        // Rebote solo cuando cambia el Me gusta de la misma canción, no al pasar a otra.
        val pop = remember { Animatable(1f) }
        var known by remember { mutableStateOf(np.id to liked) }
        LaunchedEffect(np.id, liked) {
            val (id, was) = known
            known = np.id to liked
            if(id == np.id && was != liked) {
                if(liked) { pop.snapTo(.55f); pop.animateTo(1f, Motion.pop()) }
                else { pop.animateTo(.78f, tween(90)); pop.animateTo(1f, Motion.release()) }
            }
        }
        // Un clic abre «Guardar» (Me gusta va primero ahí); antes había que mantenerlo. Doble clic en la carátula: Me gusta directo.
        Box(modifier.size(30.dp).hoverable(interaction).clickable(interactionSource=interaction,indication=null,onClick=onSave).semantics {contentDescription="Guardar en Me gusta o en una lista"},contentAlignment=Alignment.Center) {
            Icon(if(liked) MobileIcons.favorite else MobileIcons.favoriteOutline,if(liked)"En Me gusta" else "Me gusta",
                Modifier.size(16.dp).graphicsLayer { val s=scale*pop.value;scaleX=s;scaleY=s },tint=Color.White.copy(alpha=tint))
        }
    }
    @Composable fun Modules() {
        Row(Modifier.graphicsLayer {alpha=controls},horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            listOf(Triple(MobileIcons.libraryMusic,"Biblioteca",libraryOpen),Triple(MobileIcons.lyrics,"Letra",lyricsOpen),Triple(MobileIcons.queueMusic,"Cola",queueOpen)).forEachIndexed { i,(icon,title,selected) ->
                HoverAction(icon,title,chrome,listOf(onLibrary,onLyrics,onQueue)[i],tint=Color.White.copy(alpha=if(selected).95f else .55f))
            }
        }
    }
    @Composable fun VerticalModules() {
        Row(Modifier.graphicsLayer { alpha=controls },horizontalArrangement=Arrangement.Center) {
            listOf(Triple(MobileIcons.libraryMusic,"Biblioteca",libraryOpen),Triple(MobileIcons.lyrics,"Letra",lyricsOpen),Triple(MobileIcons.queueMusic,"Cola",queueOpen)).forEachIndexed { i,(icon,title,selected) ->
                HoverAction(icon,title,chrome,listOf(onLibrary,onLyrics,onQueue)[i],tint=Color.White.copy(alpha=if(selected).95f else .55f),size=26.dp)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val verticalArtLimit=(maxHeight-150.dp).coerceAtLeast(0.dp)
    val coverSize = minOf(maxWidth-40.dp, (maxHeight-310.dp).coerceAtLeast(0.dp))
    AnimatedContent(form, transitionSpec={ (fadeIn(tween(220))+scaleIn(tween(220),initialScale=.98f)) togetherWith fadeOut(tween(100)) },label="playerForm") { view ->
    when(view) {
        PlayerForm.Full -> Column(Modifier.fillMaxSize().padding(start=20.dp,end=20.dp,top=42.dp,bottom=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(source != null && np.id != null) {
                val interaction=remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val scale by animateFloatAsState(if(hovered && source.kind != "DJ") 1.08f else 1f,tween(160),label="sourceHover")
                Column(Modifier.fillMaxWidth().height(38.dp).hoverable(interaction,source.kind != "DJ").clickable(interactionSource=interaction,indication=null,enabled=source.kind != "DJ",onClick=onSource),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                    Text(source.kind.uppercase(),style=OtoType.rowSub.copy(fontSize=OtoType.rowSub.fontSize * scale),color=Color.White.copy(alpha=.45f),maxLines=1)
                    Text(source.title,style=OtoType.rowTitle.copy(fontSize=OtoType.rowTitle.fontSize * scale),maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){Cover(Modifier.size(coverSize).padding(4.dp))}
            Row(verticalAlignment=Alignment.CenterVertically) {
                AnimatedContent(np,Modifier.weight(1f),contentKey={it.id},transitionSpec={
                    val d=direction
                    (fadeIn(tween(260,delayMillis=40))+slideInHorizontally(Motion.slide()){d*it/4}) togetherWith
                        (fadeOut(tween(150))+slideOutHorizontally(tween(200,easing=Motion.Standard)){-d*it/6})
                },label="songInfo") { song ->
                    val d=remember { direction }
                    Column {Text(song.title.ifBlank{"Elige tu música"},style=OtoType.title,maxLines=1,overflow=TextOverflow.Ellipsis)
                        // El artista llega un instante después que el título, con el mismo sentido.
                        // Sin id también se puede clicar: se busca por nombre (onArtist con id vacío).
                        Text(buildAnnotatedString {
                            val shown = song.artists.ifEmpty { if(song.artist.isBlank() || song.id == null) emptyList() else listOf(song.artist to null) }
                            if(shown.isEmpty()) append(song.artist) else shown.forEachIndexed { i,(name,id) ->
                                if(i>0) append(", ")
                                val link = name.isNotBlank() && song.id != null
                                if(link) pushLink(LinkAnnotation.Clickable(id ?: "name:$name") { onArtist(id.orEmpty(),name) })
                                append(name); if(link) pop()
                            }
                        },Modifier.animateEnterExit(
                            enter=slideInHorizontally(tween(420,delayMillis=70,easing=Motion.EmphasizedDecelerate)){d*it/5}+fadeIn(tween(260,delayMillis=90)),
                            exit=ExitTransition.None),style=OtoType.rowSub,color=Color.White.copy(alpha=.5f),maxLines=1)}
                }
                Saved()
            }
            // El aviso flota sobre la barra de progreso: no empuja el resto del reproductor.
            // Las cargas breves (reanudar, cambiar de canción en caché) no llegan a mostrarlo.
            var slowBuffering by remember { mutableStateOf(false) }
            LaunchedEffect(np.buffering) { if(np.buffering) { delay(600); slowBuffering=true } else slowBuffering=false }
            val notice=if(np.error != null) "No se pudo reproducir · reintentar" else if(slowBuffering) "Preparando canción…" else null
            Box {
                PlayerProgress(np.id,position,np.durationMs,showRemaining,onSeek)
                androidx.compose.animation.AnimatedVisibility(notice!=null,Modifier.align(Alignment.BottomCenter),enter=fadeIn(tween(200)),exit=fadeOut(tween(160))) {
                    var shown by remember { mutableStateOf(notice ?: "") }
                    if(notice!=null) shown=notice
                    Text(shown,Modifier.clickable(interactionSource=remember { MutableInteractionSource() },indication=null,onClick=onPlay),
                        style=OtoType.rowSub,color=Color.White.copy(alpha=.6f),maxLines=1)
                }
            }
            if(qualityLabel != null) Text(qualityLabel,Modifier.align(Alignment.CenterHorizontally),style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1)
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){Transport(modes=true)}
            Box(Modifier.fillMaxWidth().height(34.dp),contentAlignment=Alignment.Center){Modules()}
            Spacer(Modifier.weight(1f))
        }
        PlayerForm.Horizontal -> Box(Modifier.fillMaxSize().padding(12.dp)) {
            Row(Modifier.fillMaxSize().padding(bottom=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Cover(Modifier.size(58.dp))
                Column(Modifier.weight(1f)) {
                    Text(np.title.ifBlank{"Oto"},style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Box(Modifier.height(30.dp)) {
                        Text(np.artist,style=OtoType.rowSub,color=Color.White.copy(alpha=.45f*(1-controls)),maxLines=1)
                        Modules()
                    }
                }
                Transport(reveal=true)
            }
            PlayerProgress(np.id,position,np.durationMs,showRemaining,onSeek,Modifier.align(Alignment.BottomCenter),compact=true)
        }
        PlayerForm.Vertical -> Column(Modifier.fillMaxSize().padding(start=8.dp,end=8.dp,top=34.dp,bottom=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                Cover(Modifier.sizeIn(maxWidth=verticalArtLimit,maxHeight=verticalArtLimit).fillMaxWidth().aspectRatio(1f))
            }
            Text(np.title.ifBlank{"Oto"},style=OtoType.rowSub,maxLines=1,overflow=TextOverflow.Ellipsis)
            Transport(reveal=true,compact=true)
            VerticalModules()
            Spacer(Modifier.weight(1f))
        }
    }
    }
    if(form==PlayerForm.Vertical && verticalPin!=null) Box(Modifier.align(Alignment.BottomStart).padding(start=3.dp,bottom=3.dp).graphicsLayer { alpha=controls }) { verticalPin() }
    }
}

/** Solo el progreso observa el reloj de audio; portada y controles conservan su composición. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PlayerProgress(id:String?,position:StateFlow<Long>,duration:Long,showRemaining:Boolean,onSeek:(Long)->Unit,
    modifier:Modifier=Modifier,compact:Boolean=false) {
    val elapsed by position.collectAsState()
    var seek by remember(id) { mutableStateOf<Float?>(null) }
    val fraction=seek ?: (elapsed.toFloat()/duration.coerceAtLeast(1)).coerceIn(0f,1f)
    val finish:()->Unit = { seek?.let { onSeek((it*duration).toLong()) }; seek=null }
    if(compact) {
        var hovered by remember { mutableStateOf(false) }
        val alpha by animateFloatAsState(if(hovered || seek!=null) 1f else 0f,tween(160),label="progressDetails")
        Box(modifier.fillMaxWidth().height(30.dp).onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { hovered=true }
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { hovered=false }) {
            ProgressTimes((if(seek!=null) (fraction*duration).toLong() else elapsed)/1000*1000,duration,false,
                Modifier.align(Alignment.TopCenter).graphicsLayer { this.alpha=alpha },.75f)
            FineSlider(fraction,{seek=it},Modifier.align(Alignment.BottomCenter),onFinish=finish,expanded=hovered || seek!=null)
        }
    } else Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        FineSlider(fraction,{seek=it},label="Tiempo de la canción",onFinish=finish)
        ProgressTimes(elapsed/1000*1000,duration,showRemaining)
    }
}

@Composable
private fun ProgressTimes(position:Long,duration:Long,remaining:Boolean,modifier:Modifier=Modifier,alpha:Float=.3f) {
    Row(modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(formatTimeMs(position),style=OtoType.rowSub,color=Color.White.copy(alpha=alpha))
        Text(if(remaining) "−"+formatTimeMs((duration-position).coerceAtLeast(0)) else formatTimeMs(duration),style=OtoType.rowSub,color=Color.White.copy(alpha=alpha))
    }
}

@Composable
internal fun HoverAction(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,enabled:Boolean=true,onClick:()->Unit,modifier:Modifier=Modifier,tint:Color=Color.White.copy(alpha=.7f),size:Dp=36.dp) {
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if(hovered && enabled) 1.22f else 1f,Motion.hover(),label="hover")
    val press by animateFloatAsState(if(pressed && enabled) .82f else 1f,if(pressed) Motion.press() else Motion.release(),label="press")
    Box(modifier.size(size).hoverable(interaction,enabled).clickable(interactionSource=interaction,indication=null,enabled=enabled,onClick=onClick),contentAlignment=Alignment.Center) {
        Icon(icon,label,Modifier.size(18.dp).graphicsLayer { scaleX=scale*press;scaleY=scale*press },tint=tint)
    }
}
