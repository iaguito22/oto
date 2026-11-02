package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import kotlin.math.*

internal enum class PlayerForm(val label:String, val width:Int, val height:Int) {
    Full("Carátula",320,530), Horizontal("Horizontal",360,120), Vertical("Vertical",112,320)
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
    val scale by animateFloatAsState(if(hovered)1.22f else 1f,tween(160),label="playHover")
    Box(modifier.size(controlSize).hoverable(interaction).clickable(interactionSource=interaction,indication=null,onClickLabel=if(playing)"Pausar" else "Reproducir",onClick=toggle).semantics {contentDescription=if(playing)"Pausar" else "Reproducir"},contentAlignment=Alignment.Center) {
        Canvas(Modifier.size(19.dp * scale)) {
            val c=Color.White.copy(alpha=.95f)
            if(phase<1f) {
                val p=Path().apply {moveTo(size.width*.32f,size.height*.16f);lineTo(size.width*.82f,size.height*.5f);lineTo(size.width*.32f,size.height*.84f);close()}
                drawPath(p,c.copy(alpha=(1-phase)*.95f),style=Stroke(1.8.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
            }
            if(phase>0f)for(x in listOf(.34f,.68f))drawLine(c.copy(alpha=phase*.95f),Offset(size.width*x,size.height*.18f),Offset(size.width*x,size.height*.82f),1.8.dp.toPx(),StrokeCap.Round)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerSurface(np:NowPlaying, position:Long, artwork:ImageBitmap?, form:PlayerForm, chrome:Boolean,
    beat:()->Float, beatEnabled:Boolean, liked:Boolean, showRemaining:Boolean, onPlay:()->Unit,onPrev:()->Unit,onNext:()->Unit,onSeek:(Long)->Unit,
    libraryOpen:Boolean,lyricsOpen:Boolean,queueOpen:Boolean,onLibrary:()->Unit,onLyrics:()->Unit,onQueue:()->Unit,
    onLike:()->Unit,onSave:()->Unit, source:Source?, onSource:()->Unit, onArtist:(String,String)->Unit) {
    var pulse by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(np.playing,beatEnabled) {
        var last=0L
        while(np.playing&&beatEnabled||pulse>.001f)withFrameNanos { now ->
            val dt=if(last==0L)1f/60 else((now-last)/1e9f).coerceIn(0f,.1f);last=now
            val target=if(np.playing&&beatEnabled)beat() else 0f
            pulse=if(target>pulse)pulse+(target-pulse)*min(1f,dt/.025f) else pulse*exp(-dt/.16f)
        }
    }
    val controls by animateFloatAsState(if(chrome)1f else 0f,tween(180),label="playerChrome")
    var seek by remember(np.id) {mutableStateOf<Float?>(null)}
    val fraction=seek?:(position.toFloat()/np.durationMs.coerceAtLeast(1)).coerceIn(0f,1f)
    @Composable fun Cover(modifier:Modifier) {
        Crossfade(artwork, animationSpec=tween(300),label="artwork",modifier=modifier) { image ->
            Box(Modifier.fillMaxSize().graphicsLayer { val s=1f+.026f*pulse;scaleX=s;scaleY=s }.clip(RoundedCornerShape(14.dp)).combinedClickable(onDoubleClick=onLike,onClick=onPlay),contentAlignment=Alignment.Center) {
                if(image!=null)Image(image,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop) else Text("音",style=OtoType.display,color=Color.White.copy(alpha=.2f))
            }
        }
    }
    @Composable fun Transport(reveal:Boolean=false, compact:Boolean=false) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
            HoverAction(MobileIcons.skipPrevious,"Anterior",!reveal||chrome,onPrev,Modifier.graphicsLayer{alpha=if(reveal)controls else 1f},size=if(compact)30.dp else 36.dp)
            MinimalPlayback(np.playing,onPlay,controlSize=if(compact)36.dp else 42.dp)
            HoverAction(MobileIcons.skipNext,"Siguiente",!reveal||chrome,onNext,Modifier.graphicsLayer{alpha=if(reveal)controls else 1f},size=if(compact)30.dp else 36.dp)
        }
    }
    @Composable fun Saved(modifier:Modifier=Modifier) {
        val interaction=remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val scale by animateFloatAsState(if(hovered)1.22f else 1f,tween(160),label="savedHover")
        Box(modifier.size(30.dp).hoverable(interaction).combinedClickable(interactionSource=interaction,indication=null,onClick=onLike,onLongClick=onSave).semantics {contentDescription="Me gusta; mantener para guardar en una lista"},contentAlignment=Alignment.Center) {
            Icon(MobileIcons.favorite,if(liked)"En Me gusta" else "Me gusta",Modifier.size(16.dp * scale),tint=Color.White.copy(alpha=if(liked).95f else .35f))
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
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            listOf(Triple(MobileIcons.libraryMusic,"Biblioteca",libraryOpen),Triple(MobileIcons.lyrics,"Letra",lyricsOpen),Triple(MobileIcons.queueMusic,"Cola",queueOpen)).forEachIndexed { i,(icon,title,selected) ->
                HoverAction(icon,title,true,listOf(onLibrary,onLyrics,onQueue)[i],tint=Color.White.copy(alpha=if(selected).95f else .55f),size=30.dp)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val verticalArtLimit=(maxHeight-210.dp).coerceAtLeast(32.dp)
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
            Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Cover(Modifier.aspectRatio(1f,matchHeightConstraintsFirst=true).padding(4.dp))}
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)){Text(np.title.ifBlank{"Elige tu música"},style=OtoType.title,maxLines=1,overflow=TextOverflow.Ellipsis);Text(buildAnnotatedString { if(np.artists.isEmpty()) append(np.artist) else np.artists.forEachIndexed { i,(name,id) -> if(i>0) append(", "); if(id!=null) pushLink(LinkAnnotation.Clickable(id) { onArtist(id,name) }); append(name); if(id!=null) pop() } },style=OtoType.rowSub,color=Color.White.copy(alpha=.5f),maxLines=1)}
                Saved()
            }
            if(np.buffering || np.error != null) Text(if(np.buffering) "Preparando canción…" else "No se pudo reproducir · pulsa para reintentar",Modifier.clickable(interactionSource=remember { MutableInteractionSource() },indication=null,onClick=onPlay),style=OtoType.rowSub,color=Color.White.copy(alpha=.6f),maxLines=1)
            FineSlider(fraction,{seek=it},label="Tiempo de la canción",onFinish={seek?.let{onSeek((it*np.durationMs).toLong())};seek=null})
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(formatTimeMs(position),style=OtoType.rowSub,color=Color.White.copy(alpha=.3f));Text(if(showRemaining) "−"+formatTimeMs((np.durationMs-position).coerceAtLeast(0)) else formatTimeMs(np.durationMs),style=OtoType.rowSub,color=Color.White.copy(alpha=.3f))}
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){Transport()}
            Box(Modifier.fillMaxWidth().height(34.dp),contentAlignment=Alignment.Center){Modules()}
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
            var overProgress by remember { mutableStateOf(false) }
            val progressAlpha by animateFloatAsState(if(overProgress || seek != null) 1f else 0f,tween(160),label="progressDetails")
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp).onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { overProgress=true }.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { overProgress=false }) {
                Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).graphicsLayer { alpha=progressAlpha },horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(formatTimeMs(if(seek != null) (fraction*np.durationMs).toLong() else position),style=OtoType.rowSub,color=Color.White.copy(alpha=.75f))
                    Text(formatTimeMs(np.durationMs),style=OtoType.rowSub,color=Color.White.copy(alpha=.75f))
                }
                FineSlider(fraction,{seek=it},Modifier.align(Alignment.BottomCenter),onFinish={seek?.let{onSeek((it*np.durationMs).toLong())};seek=null},expanded=overProgress || seek != null)
            }
        }
        PlayerForm.Vertical -> Column(Modifier.fillMaxSize().padding(start=8.dp,end=8.dp,top=34.dp,bottom=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Cover(Modifier.sizeIn(maxWidth=verticalArtLimit,maxHeight=verticalArtLimit).fillMaxWidth().aspectRatio(1f))
            Text(np.title.ifBlank{"Oto"},style=OtoType.rowSub,maxLines=1,overflow=TextOverflow.Ellipsis)
            Transport(reveal=true,compact=true)
            VerticalModules()
        }
    }
    }
    }
}

@Composable
internal fun HoverAction(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,enabled:Boolean=true,onClick:()->Unit,modifier:Modifier=Modifier,tint:Color=Color.White.copy(alpha=.7f),size:Dp=36.dp) {
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if(hovered && enabled) 1.22f else 1f,tween(160),label="hover")
    Box(modifier.size(size).hoverable(interaction,enabled).clickable(interactionSource=interaction,indication=null,enabled=enabled,onClick=onClick),contentAlignment=Alignment.Center) {
        Icon(icon,label,Modifier.size(18.dp * scale),tint=tint)
    }
}
