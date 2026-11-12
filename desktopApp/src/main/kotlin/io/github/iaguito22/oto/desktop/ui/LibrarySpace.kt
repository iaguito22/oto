package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import kotlin.math.roundToInt
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import io.github.iaguito22.oto.dj.DjMode
import io.github.iaguito22.oto.dj.DjReaction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.metrolist.innertube.models.*
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.account.*
import io.github.iaguito22.oto.desktop.BrowserLogin
import io.github.iaguito22.oto.desktop.LocalWindowTooltipEpoch
import io.github.iaguito22.oto.desktop.WindowWorkTrace
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.desktop.playback.*
import io.github.iaguito22.oto.importer.Importer
import io.github.iaguito22.oto.ui.menu.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray

internal data class LibraryJump(val version:Int=0,val item:YTItem?=null,val local:String?=null,val artistId:String?=null,val artistName:String="")

private data class LibraryEdit(val kind:String,val id:String="",val value:String="")

/** Biblioteca de escritorio: navegación lateral y un lienzo que cambia entre colección, búsqueda y escucha. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun LibrarySpace(player:DesktopPlayerConnection,prefs:Prefs,menu:MenuController,importer:Importer,
    account:Account,cloud:Cloud,login:BrowserLogin,downloads:DesktopDownloads,palette:ArtworkPalette,onSave:(Heard)->Unit,onDevices:()->Unit,navigation:Pair<Int,Int> = 0 to 0,jump:LibraryJump=LibraryJump(),artworkBackground:Boolean=false,onArtworkBackground:(Boolean)->Unit={},onSearchFocus:(Boolean)->Unit={},onAccountFromMobile:()->Unit={},floatingPanel:Boolean=false,backdropOpacity:Float=1f,onBackdropOpacity:(Float)->Unit={},onSource:()->Unit={},shortcut:String="CTRL + ALT + O",shortcutIssue:String?=null,onShortcut:(String)->Unit={},playerVisible:Boolean=false,remoteLink:io.github.iaguito22.oto.remote.LocalLink?=null,following:io.github.iaguito22.oto.playback.NowPlaying?=null) {
    val lists by prefs.playlists.collectAsState(initial=emptyList())
    val liked by prefs.likedList.collectAsState(initial=LocalPlaylist(LIKED_ID,"Me gusta",emptyList()))
    val history by prefs.history.collectAsState(initial=emptyList())
    val folders by prefs.folders.collectAsState(initial=emptySet())
    val pinned by prefs.pinned.collectAsState(initial=emptySet())
    val wanted by prefs.downloadLists.collectAsState(initial=emptySet())
    val entries by downloads.entries.collectAsState()
    val state by menu.state.collectAsState()
    val np by player.state.collectAsState()
    val coverBeat by prefs.coverBeat.collectAsState(initial=true)
    val pulse=rememberCoverPulse(np.playing,coverBeat,player::beat)
    val qualityPlaying by player.currentQuality.collectAsState()
    val session by account.session.collectAsState()
    val cloudState by cloud.state.collectAsState()
    val importState by importer.state.collectAsState()
    val quality by prefs.quality.collectAsState(initial=QualityMode.Auto)
    val dataSaver by prefs.dataSaver.collectAsState(initial=false)
    val volume by player.volume.collectAsState()
    var volumeMenu by remember { mutableStateOf(false) }
    val shuffle by player.shuffle.collectAsState()
    val loop by player.loop.collectAsState()
    val djActive by player.djActive.collectAsState()
    val djMode by player.djMode.collectAsState()
    val djMood by player.djMood.collectAsState()
    val scope=rememberCoroutineScope()
    val snackbar=remember {SnackbarHostState()}
    val searchFocus=remember {FocusRequester()}
    LaunchedEffect(Unit){kotlinx.coroutines.delay(120);WindowWorkTrace.measure("biblioteca/foco") { searchFocus.requestFocus() }}
    var section by remember {mutableStateOf("Tu música")}
    var local by remember {mutableStateOf<String?>(null)}
    var folder by remember {mutableStateOf<String?>(null)}
    var editor by remember {mutableStateOf<LibraryEdit?>(null)}
    var moveList by remember {mutableStateOf<LocalPlaylist?>(null)}
    var deleting by remember {mutableStateOf<LocalPlaylist?>(null)}
    var newMenu by remember {mutableStateOf(false)}
    val libraryKv=remember { OtoDirs.keyValue("desktop_library") }
    var cardSize by remember { mutableStateOf(libraryKv.getString("card_size")?.toIntOrNull()?.coerceIn(0,2) ?: 1) }
    var sizeMenu by remember { mutableStateOf(false) }
    val spots by prefs.spots.collectAsState(initial=mapOf("dj" to 0,LIKED_ID to 1))
    var filling by remember {mutableStateOf<String?>(null)}
    // Lo buscado hace poco (compartido con la lógica del móvil): sale al pulsar el buscador vacío.
    val memory=remember { MenuMemory.of() }
    val recents by memory.recents.collectAsState(initial=null)
    var recentsOpen by remember {mutableStateOf(false)}
    // Pulsar una fila del historial quita el foco al buscador: no cerrar la lista hasta soltar, o el clic se pierde.
    var pressingRecents by remember {mutableStateOf(false)}
    var searchHasFocus by remember {mutableStateOf(false)}
    val focusManager=androidx.compose.ui.platform.LocalFocusManager.current
    fun rememberSearch(item:YTItem?) { val q=menu.state.value.query; scope.launch { if(q.trim().length>=2)memory.remember(Recent.query(q)); item?.let(Recent::of)?.let{memory.remember(it)} } }
    // «Hecho con lo tuyo»: se rehacen con tus listas, Me gusta y lo oído, sin esperar a YouTube.
    var smart by remember {mutableStateOf<List<LocalPlaylist>>(emptyList())}
    var smartOpen by remember {mutableStateOf<LocalPlaylist?>(null)}
    // «Tu resumen»: lo escuchado en este ordenador (ver Recap).
    var recap by remember {mutableStateOf<io.github.iaguito22.oto.dj.Recap?>(null)}
    var recapOpen by remember {mutableStateOf(false)}
    LaunchedEffect(lists,liked,history) {
        kotlinx.coroutines.delay(400)
        val plays=player.allPlays()
        smart=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { io.github.iaguito22.oto.dj.Smart.lists(lists,liked.songs,history,plays,System.currentTimeMillis()) }.getOrDefault(emptyList()) }
        recap=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { io.github.iaguito22.oto.dj.Recap.of(plays) }.getOrNull() }
    }
    fun home(name:String) { section=name;local=null;folder=null;smartOpen=null;recapOpen=false;recentsOpen=false;menu.onQuery("");while(menu.state.value.pages.isNotEmpty())menu.closeDetail() }
    LaunchedEffect(navigation.first) {if(navigation.first>0)home(if(navigation.second==1)"Explorar" else "Tu música")}
    LaunchedEffect(jump.version) { if(jump.version>0) { home("Tu música"); when { jump.item!=null -> menu.open(jump.item); jump.local==DOWNLOADS_ID -> home("Descargas"); jump.local?.startsWith(FOLDER_PREFIX)==true -> folder=jump.local.removePrefix(FOLDER_PREFIX); jump.local!=null -> local=jump.local; jump.artistId!=null -> menu.openArtist(jump.artistId,jump.artistName) } } }
    fun openItem(item:YTItem) {if(item is SongItem)player.play(item) else {local=null;menu.onQuery("");menu.open(item)}}
    fun openArtist(id:String,name:String) {home("Tu música");menu.openArtist(id,name)}
    val songActions=rememberSongActions(player,prefs,onSave,::openArtist)
    /** Toda la carpeta seguida, como una lista (igual que el móvil). */
    fun playFolder(name:String,inside:List<LocalPlaylist>) = player.playLocal(LocalPlaylist(FOLDER_PREFIX+name,name,inside.flatMap{it.songs}.distinctBy{it.id}),null,false)
    fun folderMenu(name:String)=listOf(
        ContextMenuItem(if("d:$name" in pinned)"Quitar de arriba" else "Fijar arriba") {scope.launch {prefs.togglePin("d:$name")}},
        ContextMenuItem("Añadir o quitar listas") {filling=name},
        ContextMenuItem("Cambiar el nombre") {editor=LibraryEdit("renameFolder",name,name)},
        ContextMenuItem("Deshacer la carpeta") {scope.launch{prefs.ungroup(name)};if(folder==name)folder=null},
    )
    fun listMenu(list:LocalPlaylist)=listOf(
        ContextMenuItem("Reproducir") {player.playLocal(list,null,false)},
        ContextMenuItem(if(list.id in pinned)"Quitar de arriba" else "Fijar arriba") {scope.launch {prefs.togglePin(list.id)}},
        ContextMenuItem("Renombrar") {editor=LibraryEdit("rename",list.id,list.name)},
        ContextMenuItem("Mover a carpeta…") {moveList=list},
        ContextMenuItem(if(list.id in wanted)"Quitar descarga" else "Descargar") {scope.launch {prefs.setDownloadList(list.id,list.id !in wanted)}},
        ContextMenuItem("Eliminar lista…") {deleting=list}
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>800.dp
        val contentWidth=maxWidth-(if(wide)176.dp else 62.dp)-44.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(if(wide)176.dp else 62.dp).fillMaxHeight().padding(horizontal=12.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(wide) Spacer(Modifier.height(58.dp))
                listOf("Tu música" to MobileIcons.libraryMusic,"Explorar" to Icons.Rounded.Explore,"Recientes" to MobileIcons.history,"Descargas" to MobileIcons.download).forEach { (name,icon) ->
                    LibraryNavigation(name,icon,wide,section==name) { home(name) }
                }
                Spacer(Modifier.weight(1f))
                // Dirección del último salto: anterior desliza hacia la derecha, siguiente hacia la izquierda.
                var miniDirection by remember { mutableIntStateOf(1) }
                var miniStamp by remember { mutableIntStateOf(0) }
                LaunchedEffect(miniStamp) { kotlinx.coroutines.delay(1500); miniDirection=1 }
                // Lo que suena en el móvil conectado: solo cambia con la canción o la pausa, no con cada posición.
                // Se decide en RemoteMirror, igual que para la ventana del reproductor y la bandeja.
                val rn=following
                fun remoteAction(action:String) { val link=remoteLink ?: return; scope.launch {
                    runCatching { link.command(org.json.JSONObject().put("action",action)) }.onFailure { snackbar.showSnackbar("No se pudo controlar el móvil") }
                } }
                val miniSong=if(rn!=null) MiniSong(rn.id,rn.artwork,rn.title,rn.artist) else MiniSong(np.id,np.artwork,np.title,np.artist)
                val miniPlaying=rn?.playing ?: np.playing
                val miniToggle={ if(rn!=null) remoteAction("toggle") else player.toggle() }
                val miniOpen={ if(rn!=null) onDevices() else onSource() }
                fun skipMini(d:Int) { miniDirection=d; miniStamp++; if(rn!=null) remoteAction(if(d<0) "previous" else "next") else if(d<0) player.previous() else player.next() }
                // Con la ventana del reproductor abierta, el mini reproductor sobraría (también muestra lo del móvil).
                androidx.compose.animation.AnimatedVisibility((rn!=null || np.id!=null) && !playerVisible,
                    enter=fadeIn(tween(220))+androidx.compose.animation.expandVertically(spring(dampingRatio=.8f,stiffness=380f),expandFrom=Alignment.Bottom),
                    exit=fadeOut(tween(160))+androidx.compose.animation.shrinkVertically(tween(220),shrinkTowards=Alignment.Bottom)) {
                    if(wide) {
                        Column(Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            MiniSongSwap(miniSong,miniDirection) { song ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick=miniOpen),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                                    LibraryArt(song.artwork,Modifier.size(40.dp).graphicsLayer { val s=1f+.022f*pulse.value;scaleX=s;scaleY=s })
                                    Column(Modifier.weight(1f)) {
                                        Text(song.title,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        // El artista llega un instante después que el título.
                                        Text(song.artist,style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1,overflow=TextOverflow.Ellipsis,
                                            modifier=Modifier.animateEnterExit(enter=fadeIn(tween(260,delayMillis=70))+androidx.compose.animation.slideInHorizontally(Motion.slide()){ miniDirection*it/6 },exit=fadeOut(tween(120))))
                                    }
                                }
                            }
                            val qualityText=if(rn!=null) (if(rn.buffering) "En el móvil · preparando…" else "Sonando en el móvil") else qualityPlaying?.let { q -> listOfNotNull(q.codec.uppercase(),q.bitrate?.let { "${(it+500)/1000} kbps" },q.sampleRate?.let { if(it%1000==0) "${it/1000} kHz" else "${it/1000f} kHz" }).joinToString(" · ") } ?: if(np.buffering) "Preparando audio…" else "Calidad no disponible"
                            // Siempre en una línea: si no cabe, la letra se reduce un poco hasta caber.
                            AnimatedContent(qualityText,transitionSpec={ fadeIn(tween(260,delayMillis=60)) togetherWith fadeOut(tween(140)) },label="calidad") { text ->
                                var qualitySize by remember(text) { mutableStateOf(11.5f) }
                                Text(text,style=OtoType.rowSub.copy(fontSize=androidx.compose.ui.unit.TextUnit(qualitySize,androidx.compose.ui.unit.TextUnitType.Sp),lineHeight=androidx.compose.ui.unit.TextUnit(qualitySize+4f,androidx.compose.ui.unit.TextUnitType.Sp)),
                                    color=Color.White.copy(alpha=.38f),maxLines=1,softWrap=false,
                                    onTextLayout={ if(it.hasVisualOverflow && qualitySize>8.5f) qualitySize-=.5f })
                            }
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                                KickAction(MobileIcons.skipPrevious,"Anterior",-1) { skipMini(-1) }
                                MinimalPlayback(miniPlaying,miniToggle,controlSize=36.dp)
                                KickAction(MobileIcons.skipNext,"Siguiente",1) { skipMini(1) }
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                            MiniSongSwap(miniSong,miniDirection) { song ->
                                LibraryArt(song.artwork,Modifier.size(36.dp).graphicsLayer { val s=1f+.022f*pulse.value;scaleX=s;scaleY=s }.clickable(onClick=miniOpen))
                            }
                            MinimalPlayback(miniPlaying,miniToggle,controlSize=36.dp)
                        }
                    }
                }
                if(wide) {
                    Column(verticalArrangement=Arrangement.spacedBy((-7).dp)) {
                    FineSlider(volume,player::setVolume,Modifier.padding(horizontal=8.dp),label="Volumen")
                    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp,"Volumen",Modifier.size(18.dp),tint=Color.White.copy(alpha=.55f))
                        Spacer(Modifier.weight(1f))
                        Text("${(volume*100).toInt()} %",style=OtoType.rowSub,color=Color.White.copy(alpha=.4f))
                    }
                    }
                } else Box {
                    HoverAction(Icons.AutoMirrored.Rounded.VolumeUp,"Volumen",onClick={volumeMenu=true})
                    LibraryDropdownMenu(volumeMenu,{volumeMenu=false},shape=RoundedCornerShape(18.dp),modifier=Modifier.width(190.dp)) {
                        Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)) {Text("Volumen · ${(volume*100).toInt()} %",style=OtoType.rowSub);FineSlider(volume,player::setVolume,label="Volumen")}
                    }
                }
                LibraryNavigation("Ajustes",MobileIcons.settings,wide,section=="Ajustes") { home("Ajustes") }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start=16.dp,end=28.dp,top=18.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                // Dispositivos junto al buscador, pero fuera de él.
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f).height(48.dp).clip(CircleShape).background(Color.White.copy(alpha=.045f)).padding(start=16.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(MobileIcons.search,null,Modifier.size(18.dp),tint=Color.White.copy(alpha=.35f))
                    BasicTextField(state.query,{menu.onQuery(it);local=null;smartOpen=null},Modifier.weight(1f).onFocusChanged { onSearchFocus(it.isFocused);searchHasFocus=it.isFocused;if(!it.isFocused && !pressingRecents)recentsOpen=false }.focusRequester(searchFocus).onPointerEvent(PointerEventType.Press){recentsOpen=true}.onPreviewKeyEvent { e -> if(e.type==KeyEventType.KeyDown && e.key==Key.Escape && recentsOpen){recentsOpen=false;true} else false },textStyle=OtoType.body.copy(color=Color.White),cursorBrush=SolidColor(Color.White),singleLine=true,decorationBox={field -> if(state.query.isBlank())Text("Buscar canciones, artistas o listas",style=OtoType.body,color=Color.White.copy(alpha=.36f));field()})
                    if(state.query.isNotBlank())IconButton(onClick={menu.onQuery("")}){Icon(MobileIcons.close,"Limpiar búsqueda",Modifier.size(17.dp))}
                }
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha=.045f)),contentAlignment=Alignment.Center) {
                    HoverAction(Icons.Rounded.Devices,"Dispositivos",onClick=onDevices,tint=Color.White.copy(alpha=.55f))
                }
                }
                val panelModifier = if(floatingPanel) Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF171A18).copy(alpha=.35f)).padding(16.dp) else Modifier
                Column(Modifier.weight(1f).fillMaxWidth().then(panelModifier),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                val page=state.pages.lastOrNull() as? Page.Remote
                val remoteSongs=page?.songs
                val selected=(if(local==LIKED_ID)liked else lists.firstOrNull{it.id==local})
                val title=when {state.query.isNotBlank()->"Resultados";page!=null->page.item.title;selected!=null->selected.name;folder!=null->folder!!;else->section}
                val showRecents=recentsOpen && state.query.isBlank()
                if(selected==null && smartOpen==null && !recapOpen && !showRecents && page?.item !is ArtistItem && page?.item !is AlbumItem && page?.item !is PlaylistItem) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    if(local!=null||page!=null||folder!=null)IconButton(onClick={local=null;folder=null;if(page!=null)menu.closeDetail()}){Icon(MobileIcons.arrowBack,"Volver",Modifier.size(20.dp))}
                    Text(title,style=OtoType.display,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                    if(section=="Tu música"&&selected==null&&page==null&&state.query.isBlank()){
                        Box {
                            HoverAction(Icons.Rounded.GridView,"Tamaño de las listas",onClick={sizeMenu=true})
                            LibraryDropdownMenu(expanded=sizeMenu,onDismissRequest={sizeMenu=false},shape=RoundedCornerShape(18.dp)) {
                                listOf("Pequeñas","Medianas","Grandes").forEachIndexed { i,label -> DropdownMenuItem(text={Text(if(cardSize==i) "$label  ✓" else label)},onClick={cardSize=i;libraryKv.putString("card_size",i.toString());sizeMenu=false}) }
                            }
                        }
                        Box(Modifier.padding(end=16.dp)) {
                            TextButton(onClick={newMenu=true}){Icon(MobileIcons.add,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("Nueva")}
                            LibraryDropdownMenu(expanded=newMenu,onDismissRequest={newMenu=false},shape=RoundedCornerShape(18.dp),offset=DpOffset((-12).dp,0.dp)) {
                                DropdownMenuItem(text={Text("Lista")},leadingIcon={Icon(MobileIcons.libraryMusic,null)},onClick={newMenu=false;editor=LibraryEdit("list")})
                                DropdownMenuItem(text={Text("Carpeta")},leadingIcon={Icon(MobileIcons.createNewFolder,null)},onClick={newMenu=false;editor=LibraryEdit("folder")})
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    AnimatedContent(section, transitionSpec = {
                        val direction = if (targetState == "Ajustes") 1 else if (initialState == "Ajustes") -1 else 1
                        (fadeIn(tween(260)) + slideInVertically(spring(dampingRatio=.8f,stiffness=280f)) { direction * it / 5 }) togetherWith
                            (fadeOut(tween(160)) + slideOutVertically(tween(200)) { -direction * it / 8 })
                    }, label="librarySection") { animatedSection ->
                    CascadeScope(listOf(animatedSection,showRecents,state.query.isNotBlank(),smartOpen,recapOpen,selected?.id,page?.item,folder)) {
                    when {
                        showRecents -> Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press,pass=androidx.compose.ui.input.pointer.PointerEventPass.Initial){pressingRecents=true}.onPointerEvent(PointerEventType.Release,pass=androidx.compose.ui.input.pointer.PointerEventPass.Final){scope.launch{kotlinx.coroutines.delay(80);pressingRecents=false;if(!searchHasFocus)recentsOpen=false}}) { RecentSearches(recents,onPick={ r ->
                            scope.launch { memory.remember(r) }
                            val item=r.item()
                            recentsOpen=false
                            if(item==null) menu.onQuery(r.title) else { focusManager.clearFocus();openItem(item) }
                        },onForget={ r -> scope.launch { memory.forget(r) } }) }
                        state.query.isNotBlank() -> SearchSpace(state,lists,np.id,player,menu,prefs,palette,onSave,onOpen=::openItem,onLocal={local=it;menu.onQuery("")},onArtist=::openArtist,onRemember=::rememberSearch)
                        recapOpen && recap!=null -> {
                            val art=remember(lists,liked,history) { (history+liked.songs+lists.flatMap{it.songs}).filter{it.artwork!=null}.associate{it.id to it.artwork} }
                            RecapSpace(recap!!,palette,{ art[it] },onPlay={ s -> player.play(Heard(s.id,s.title,s.artist,art[s.id],s.artistId).toSong()) },onArtist={id,name->recapOpen=false;openArtist(id,name)},onBack={recapOpen=false})
                        }
                        smartOpen!=null -> SmartSpace(smartOpen!!,np.id,player,prefs,palette,onSave,::openArtist,onBack={smartOpen=null})
                        selected!=null -> LocalPlaylistView(selected,np.id,player,prefs,downloads,palette,onSave,
                            onBack={local=null},onFind={home("Tu música");searchFocus.requestFocus()},
                            onArtist={id,name->home("Tu música");menu.openArtist(id,name)},
                            actions=if(selected.id==LIKED_ID) listOf(ContextMenuItem(if(selected.id in wanted) "Quitar la descarga" else "Descargar") {scope.launch { prefs.setDownloadList(selected.id,selected.id !in wanted) }}) else listMenu(selected))
                        page!=null && page.item is ArtistItem -> ArtistSpace(page,np.id,player,prefs,palette,onSave,onBack={menu.closeDetail()},onOpen=::openItem,onArtist={id,name->menu.openArtist(id,name)})
                        page!=null && (page.item is AlbumItem || page.item is PlaylistItem) -> AlbumSpace(page,np.id,player,prefs,palette,onSave,onBack={menu.closeDetail()},onArtist={id,name->menu.openArtist(id,name)})
                        page!=null -> {
                            when {page.error!=null->EmptyLibrary(page.error!!);remoteSongs==null->LinearProgressIndicator(Modifier.fillMaxWidth());else->Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                    LibraryArt(page.item.thumbnail,Modifier.size(80.dp))
                                    Button(enabled=remoteSongs.isNotEmpty(),onClick={player.playAll(remoteSongs,0,page.item)}){Text("Reproducir")}
                                }
                                if(remoteSongs.isNotEmpty())SongList(remoteSongs.map{it.toHeard()},np.id,{i->player.playAll(remoteSongs,i,page.item)},onSave,player,prefs,onArtist=::openArtist)
                                else LazyColumn {page.shelves.forEach{shelf->item{Text(shelf.title,style=OtoType.section)};items(shelf.items){item->LibraryLine(item.title,"",item.thumbnail,{openItem(item)})}}}
                            }}
                        }
                        animatedSection=="Ajustes" -> SettingsSpace(prefs,player,palette,session,importState,artworkBackground,onArtworkBackground,backdropOpacity,onBackdropOpacity,shortcut,shortcutIssue,onShortcut,onAccount={home("Cuenta")},onImport={home("Importar")},onDevices=onDevices)
                        animatedSection=="Cuenta" -> SettingsAndAccountPane(prefs,importer,account,cloud,login,session,cloudState,importState,quality,dataSaver,shuffle,loop,player::setShuffle,player::setLoop,palette,{home("Importar")}, accountOnly = true, onAccountFromMobile = onAccountFromMobile)
                        animatedSection=="Importar" -> ImportPane(importer,prefs,palette,{home("Ajustes")},{home("Tu música")})
                        animatedSection=="Descargas" -> DownloadsSpace(player,prefs,downloads,palette,np.id,onOpen={local=it},onSave=onSave,workScope=scope,onArtist=::openArtist)
                        animatedSection=="Recientes" -> SongList(history,np.id,{i->player.play(history[i].toSong())},onSave,player,prefs,onArtist=::openArtist)
                        animatedSection=="Explorar" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(24.dp)) {
                            item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){state.chips.forEach{chip->FilterChip(selected=state.chip==chip.title,onClick={menu.onChip(chip.title)},label={Text(chip.title)})}}}
                            recap?.takeIf { !it.empty && state.chip==null }?.let { r -> item(key="recap") { RecapCard(r,palette) { recapOpen=true } } }
                            if(smart.isNotEmpty() && state.chip==null) item(key="smart") { SmartRow(smart,palette) { smartOpen=it } }
                            if(state.homeError!=null)item{TextButton(onClick=menu::loadHome){Text("Volver a cargar Explorar")}}
                            if(state.shelves==null)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
                            state.shelves.orEmpty().forEach{shelf->item{Column(verticalArrangement=Arrangement.spacedBy(14.dp)){Text(shelf.title,style=OtoType.section);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(18.dp)){shelf.items.forEach{item->LibraryCard(item.title,when(item){is SongItem->item.artists.joinToString{it.name};else->""},item.thumbnail,Modifier.width(150.dp)){openItem(item)}}}}}}
                        }
                        else -> {
                            // Mismo orden que el móvil: el de tus listas (compartido) y los sitios del DJ y Me gusta.
                            val shelf=remember(lists,folders){shelfOf(lists,folders)}
                            val inFolder=folder
                            val folderLists=if(inFolder!=null) lists.filter{it.folder==inFolder} else emptyList()
                            val (pinnedShelf,rest)=shelf.partition{it.key in pinned}
                            val top=pinnedShelf.toMutableList().apply {
                                listOf(Shelved(null,emptyList(),special="dj"),Shelved(null,listOf(liked),special=LIKED_ID))
                                    .sortedBy{spots[it.special] ?: 0}
                                    .forEach{add((spots[it.special] ?: 0).coerceIn(0,size),it)}
                            }.toList()
                            val gridEntries=if(inFolder!=null) folderLists.map{Shelved(null,listOf(it))} else rest
                            var topOrder by remember{mutableStateOf(top)}
                            LaunchedEffect(top){topOrder=top}
                            var gridOrder by remember{mutableStateOf(gridEntries)}
                            LaunchedEffect(gridEntries){gridOrder=gridEntries}
                            // Todas las tarjetas con el ancho de «Tus listas», arriba y en la cuadrícula.
                            val featuredWidth=when(cardSize){0->112.dp;2->184.dp;else->144.dp}
                            val grid=rememberLazyGridState()
                            val moveIn:(List<Shelved>,Any,Any)->List<Shelved>? = { order,from,to ->
                                val a=order.indexOfFirst{it.key==from};val b=order.indexOfFirst{it.key==to}
                                if(a<0||b<0||a==b||!order[b].movable||!order[a].movable) null else order.toMutableList().apply{add(b,removeAt(a))}
                            }
                            val reorder=rememberReorderableLazyGridState(grid) { from,to -> moveIn(gridOrder,from.key,to.key)?.let{gridOrder=it} }
                            val pinnedRow=rememberLazyListState()
                            val pinnedReorder=rememberReorderableLazyListState(pinnedRow) { from,to -> moveIn(topOrder,from.key,to.key)?.let{topOrder=it} }
                            val saveTop by rememberUpdatedState({ if(topOrder.map{it.key}!=top.map{it.key}){val row=topOrder;scope.launch{prefs.orderLists(reorderedIds(shelf,row));prefs.setSpots(spotsOf(row))}} })
                            val saveGrid by rememberUpdatedState({
                                if(gridOrder.map{it.key}!=gridEntries.map{it.key}) {
                                    val base=if(inFolder!=null) lists.map{Shelved(null,listOf(it))} else shelf
                                    val row=gridOrder
                                    scope.launch{prefs.orderLists(reorderedIds(base,row))}
                                }
                            })
                            @Composable fun ShelvedCard(e:Shelved,modifier:Modifier,dragging:Boolean) {
                                when {
                                    e.special=="dj" -> PlaylistCard(
                                        "DJ",
                                        if(djActive && djMode==DjMode.Normal) "Pinchando · ${djMood.label}" else "Mezcla para ti",
                                        null,
                                        modifier,
                                        special="dj",
                                        accent=palette.primary,
                                        lifted=dragging,
                                        onPlay={player.playDj(DjMode.Normal) {scope.launch{snackbar.showSnackbar("No se pudo iniciar el DJ")}}}
                                    ) {player.playDj(DjMode.Normal) {scope.launch{snackbar.showSnackbar("No se pudo iniciar el DJ")}}}
                                    e.special!=null -> PlaylistContextMenu(listOf(ContextMenuItem(if(LIKED_ID in wanted) "Quitar la descarga" else "Descargar") {scope.launch { prefs.setDownloadList(LIKED_ID,LIKED_ID !in wanted) }})) {
                                        PlaylistCard("Me gusta","${liked.songs.size} canciones",null,modifier,special="liked",accent=palette.primary,lifted=dragging,onPlay=if(liked.songs.isNotEmpty()){{player.playLocal(liked,null,true)}}else null) {local=LIKED_ID}
                                    }
                                    e.folder!=null -> PlaylistContextMenu(folderMenu(e.folder)) {
                                        PlaylistCard(e.folder,folderCount(e.lists.size),null,modifier,special="folder",accent=palette.primary,lifted=dragging,folderLists=e.lists,
                                            onPlay=if(e.lists.any{it.songs.isNotEmpty()}){{playFolder(e.folder,e.lists)}}else null){folder=e.folder}
                                    }
                                    else -> { val list=e.lists.first(); PlaylistContextMenu(listMenu(list)) {
                                        PlaylistCard(list.name,"${list.songs.size} canciones",list.songs.firstOrNull()?.artwork,modifier,onPlay=if(list.songs.isNotEmpty()){{player.playLocal(list,null,false)}}else null,lifted=dragging,playlist=list){local=list.id}
                                    } }
                                }
                            }
                            LazyVerticalGrid(columns=GridCells.FixedSize(featuredWidth),state=grid,verticalArrangement=Arrangement.spacedBy(18.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=28.dp)) {
                                if(inFolder==null) item(key="featured",span={GridItemSpan(maxLineSpan)}) {
                                    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                            Text("Tus listas",style=OtoType.section,modifier=Modifier.weight(1f))
                                            DjMoodSelector(djMood,palette.primary,player::setDjMood)
                                        }
                                        LazyRow(state=pinnedRow,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                            items(topOrder,key={it.key}) { e ->
                                                ReorderableItem(pinnedReorder,e.key) { dragging ->
                                                    ShelvedCard(e,Modifier.width(featuredWidth).longPressDraggableHandle(enabled=e.movable,onDragStopped={saveTop()}),dragging)
                                                }
                                            }
                                            item(key="dj_adhd") {
                                                PlaylistCard(
                                                    "DJ rápido (ADHD)",
                                                    if(djActive && djMode==DjMode.Adhd) "Pinchando · ${djMood.label}" else "Saltando intros · ~1 min",
                                                    null,
                                                    Modifier.width(featuredWidth),
                                                    special="dj_adhd",
                                                    accent=palette.primary,
                                                    onPlay={player.playAdhdDj {scope.launch{snackbar.showSnackbar("No se pudo iniciar el DJ rápido")}}}
                                                ) {player.playAdhdDj {scope.launch{snackbar.showSnackbar("No se pudo iniciar el DJ rápido")}}}
                                            }
                                        }
                                    }
                                } else item(key="folder-actions",span={GridItemSpan(maxLineSpan)}) {
                                    val folderShuffle=rememberListShuffle(player,FOLDER_PREFIX+inFolder)
                                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Text(folderCount(folderLists.size),style=OtoType.rowSub,color=Color.White.copy(alpha=.5f),modifier=Modifier.padding(end=8.dp))
                                        if(folderLists.any{it.songs.isNotEmpty()}) {
                                            Button(onClick={playFolder(inFolder,folderLists)},colors=ButtonDefaults.buttonColors(containerColor=palette.primary,contentColor=Color(0xFF101010))){Text("Reproducir",style=OtoType.rowTitle)}
                                            HoverAction(MobileIcons.shuffle,if(folderShuffle)"Desactivar aleatorio" else "Activar aleatorio",onClick={player.setShuffle(!folderShuffle,FOLDER_PREFIX+inFolder)},tint=if(folderShuffle)palette.primary else Color.White.copy(alpha=.6f))
                                        }
                                        TextButton(onClick={filling=inFolder}){Icon(MobileIcons.add,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("Añadir o quitar listas")}
                                        Box { var open by remember{mutableStateOf(false)}
                                            HoverAction(MobileIcons.moreHoriz,"Opciones de la carpeta",onClick={open=true})
                                            LibraryDropdownMenu(open,{open=false},shape=RoundedCornerShape(18.dp),modifier=Modifier.width(230.dp)) {
                                                folderMenu(inFolder).forEach { item -> DropdownMenuItem(text={Text(item.label,style=OtoType.rowSub)},onClick={open=false;item.onClick()}) }
                                            }
                                        }
                                    }
                                }
                                if(gridOrder.isNotEmpty())item(key="lists-title",span={GridItemSpan(maxLineSpan)}) {Text(if(inFolder==null) "Más de tus listas" else "Listas de la carpeta",style=OtoType.section)}
                                items(gridOrder,key={it.key}) { e ->
                                    Box(Modifier.fillMaxWidth()) {
                                        ReorderableItem(reorder,e.key) { dragging ->
                                            ShelvedCard(e,Modifier.width(featuredWidth).longPressDraggableHandle(enabled=e.movable,onDragStopped={saveGrid()}),dragging)
                                        }
                                    }
                                }
                                if(inFolder!=null && folderLists.isEmpty())item(span={GridItemSpan(maxLineSpan)}){EmptyLibrary("Esta carpeta está vacía. Añade listas con «Añadir o quitar listas».")}
                                if(folder==null && history.isNotEmpty()) {
                                    item(key="recent-title",span={GridItemSpan(maxLineSpan)}) {Text("Oído hace poco",style=OtoType.section)}
                                    items(history.distinctBy{it.id}.take(8),key={"recent:${it.id}"},span={GridItemSpan(maxLineSpan)}) {h->val actions=songActions(h,null);PlaylistContextMenu(actions){LibraryLine(h.title,h.artist,h.artwork,{player.play(h.toSong())}){if(h.id==np.id)Icon(MobileIcons.graphicEq,"Sonando",Modifier.size(17.dp),tint=Color.White.copy(alpha=.7f));SongMenuButton(h.title,actions)}}}
                                }
                            }
                        }
                    }
                    }
                    }
                }
                }
            }
        }
        OtoSnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }
    editor?.let {edit ->
        var text by remember(edit){mutableStateOf(edit.value)}
        val isFolder = edit.kind == "folder" || edit.kind == "renameFolder" || edit.kind == "folderFor"
        val creating = edit.kind == "list" || edit.kind == "folder" || edit.kind == "folderFor"
        val title = when(edit.kind){"folder","folderFor"->"Nueva carpeta";"list"->"Nueva lista";"renameFolder"->"Renombrar carpeta";else->"Renombrar lista"}
        val action = when(edit.kind){"folder","folderFor"->"Crear carpeta";"list"->"Crear lista";else->"Guardar cambios"}
        val focus = remember(edit) { FocusRequester() }
        fun save() {
            val name = text.trim()
            if(name.isBlank()) return
            scope.launch { when(edit.kind){"list"->prefs.createPlaylist(name);"folder"->prefs.createFolder(name);"folderFor"->{prefs.createFolder(name);prefs.moveToFolder(edit.id,name)};"renameFolder"->{prefs.renameFolder(edit.id,name);if(folder==edit.id)folder=name};else->prefs.renamePlaylist(edit.id,name)} }
            editor=null
        }
        Dialog(onDismissRequest={editor=null}) {
            Column(Modifier.width(390.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF242826)).border(1.dp,Color.White.copy(alpha=.08f),RoundedCornerShape(24.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(15.dp)) {
                    Box(Modifier.size(62.dp).clip(RoundedCornerShape(16.dp)).background(palette.primary.copy(alpha=.14f)),contentAlignment=Alignment.Center) {
                        Icon(if(isFolder) MobileIcons.createNewFolder else MobileIcons.libraryMusic,null,Modifier.size(27.dp),tint=palette.primary)
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(title,style=OtoType.section,color=Color.White)
                        Text(if(creating) "Ponle un nombre para encontrarla fácilmente" else "Elige cómo quieres llamarla",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
                    }
                }
                Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text("Nombre",style=OtoType.rowSub,color=Color.White.copy(alpha=.55f),modifier=Modifier.padding(start=2.dp))
                    BasicTextField(text,{text=it},Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha=.055f)).border(1.dp,Color.White.copy(alpha=.08f),RoundedCornerShape(14.dp)).padding(horizontal=15.dp,vertical=14.dp).focusRequester(focus).onPreviewKeyEvent { event -> if(event.type==KeyEventType.KeyDown && event.key==Key.Enter){save();true}else false },singleLine=true,textStyle=OtoType.body.copy(color=Color.White),cursorBrush=SolidColor(palette.primary),decorationBox={field -> Box { if(text.isEmpty()) Text(if(isFolder) "Nombre de la carpeta" else "Nombre de la lista",style=OtoType.body,color=Color.White.copy(alpha=.3f)); field() }})
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.End) {
                    TextButton(onClick={editor=null}) { Text("Cancelar",color=Color.White.copy(alpha=.65f)) }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if(text.isNotBlank())palette.primary else Color.White.copy(alpha=.08f)).clickable(enabled=text.isNotBlank(),onClick=::save).padding(horizontal=20.dp,vertical=12.dp),contentAlignment=Alignment.Center) {
                        Text(action,style=OtoType.rowTitle,color=if(text.isNotBlank())Color(0xFF101410) else Color.White.copy(alpha=.3f))
                    }
                }
            }
            LaunchedEffect(edit) { focus.requestFocus() }
        }
    }
    moveList?.let {list ->
        val destinations=listOf<String?>(null)+(folders+lists.mapNotNull{it.folder}).sorted()
        Dialog(onDismissRequest={moveList=null}) {
            Column(Modifier.width(390.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF242826)).border(1.dp,Color.White.copy(alpha=.08f),RoundedCornerShape(24.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(15.dp)) {
                    Box(Modifier.size(62.dp).clip(RoundedCornerShape(16.dp)).background(palette.primary.copy(alpha=.14f)),contentAlignment=Alignment.Center) { Icon(MobileIcons.folder,null,Modifier.size(27.dp),tint=palette.primary) }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("Mover lista",style=OtoType.section,color=Color.White)
                        Text(list.name,style=OtoType.rowSub,color=Color.White.copy(alpha=.55f),maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
                Text("Elige una ubicación",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
                LazyColumn(Modifier.heightIn(max=300.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    items(destinations.size) { index ->
                        val name=destinations[index]
                        val selected=name==list.folder
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if(selected)palette.primary.copy(alpha=.10f) else Color.Transparent).clickable { if(!selected)scope.launch{prefs.moveToFolder(list.id,name)};moveList=null }.padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Icon(if(name==null)MobileIcons.libraryMusic else MobileIcons.folder,null,Modifier.size(20.dp),tint=if(selected)palette.primary else Color.White.copy(alpha=.55f))
                            Text(name?:"Fuera de carpetas",Modifier.weight(1f),style=OtoType.body,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis)
                            if(selected) Icon(MobileIcons.check,"Ubicación actual",Modifier.size(18.dp),tint=palette.primary)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={moveList=null;editor=LibraryEdit("folderFor",list.id)}) { Icon(MobileIcons.createNewFolder,null,Modifier.size(17.dp),tint=palette.primary);Spacer(Modifier.width(6.dp));Text("Nueva carpeta…",color=palette.primary) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick={moveList=null}) { Text("Cancelar",color=Color.White.copy(alpha=.65f)) }
                }
            }
        }
    }
    filling?.let { name ->
        // Qué listas van en la carpeta: todas las tuyas con su marca; las de otra carpeta dicen cuál (como el móvil).
        Dialog(onDismissRequest={filling=null}) {
            Column(Modifier.width(420.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF242826)).border(1.dp,Color.White.copy(alpha=.08f),RoundedCornerShape(24.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(15.dp)) {
                    FolderArt(lists.filter{it.folder==name},Modifier.size(62.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(name,style=OtoType.section,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("Marca las listas que van dentro",style=OtoType.rowSub,color=Color.White.copy(alpha=.55f))
                    }
                }
                if(lists.isEmpty()) Text("Aún no tienes listas.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
                LazyColumn(Modifier.heightIn(max=380.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    items(lists,key={it.id}) { l ->
                        val inside=l.folder==name
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { scope.launch { prefs.moveToFolder(l.id,if(inside) null else name) } }.padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            PlaylistMosaic(l,palette.primary,Modifier.size(40.dp))
                            Column(Modifier.weight(1f)) {
                                Text(l.name,style=OtoType.body,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(l.folder?.takeIf{!inside}?.let{"En «$it»"} ?: "${l.songs.size} canciones",style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1)
                            }
                            Box(Modifier.size(22.dp).clip(CircleShape).background(if(inside) palette.primary else Color.Transparent).border(1.5.dp,if(inside) palette.primary else Color.White.copy(alpha=.38f),CircleShape),contentAlignment=Alignment.Center) {
                                if(inside) Icon(MobileIcons.check,null,Modifier.size(15.dp),tint=Color(0xFF101010))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { TextButton(onClick={filling=null}) { Text("Listo",color=palette.primary) } }
            }
        }
    }
    deleting?.let { list ->
        Dialog(onDismissRequest={deleting=null}) {
            Column(Modifier.width(390.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF242826)).border(1.dp,Color.White.copy(alpha=.08f),RoundedCornerShape(24.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(15.dp)) {
                    LibraryArt(list.songs.firstOrNull()?.artwork,Modifier.size(62.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("Eliminar lista",style=OtoType.section)
                        Text(list.name,style=OtoType.rowSub,color=Color.White.copy(alpha=.55f),maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
                Text("Se quitará de tu biblioteca. Podrás deshacerlo desde el aviso que aparecerá después.",style=OtoType.body,color=Color.White.copy(alpha=.65f))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={deleting=null}) { Text("Cancelar",color=Color.White.copy(alpha=.65f)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick={
                        val wasPinned=list.id in pinned
                        val wasDownloaded=list.id in wanted
                        deleting=null
                        scope.launch {
                            prefs.deletePlaylist(list.id)
                            if(snackbar.showSnackbar("Lista eliminada","Deshacer",duration=SnackbarDuration.Long)==SnackbarResult.ActionPerformed) {
                                prefs.restore(listOf(list),emptySet(),if(wasPinned)setOf(list.id)else emptySet(),emptySet())
                                if(wasDownloaded)prefs.setDownloadList(list.id,true)
                            }
                        }
                    },colors=ButtonDefaults.buttonColors(containerColor=Danger.copy(alpha=.16f),contentColor=Danger)) { Text("Eliminar lista",style=OtoType.rowTitle) }
                }
            }
        }
    }
}

private data class MiniSong(val id:String?,val artwork:String?,val title:String,val artist:String)

/** Cambio de canción del mini reproductor: entra desde el lado del salto con un pequeño rebote. */
@Composable
private fun MiniSongSwap(song:MiniSong,direction:Int,content:@Composable androidx.compose.animation.AnimatedVisibilityScope.(MiniSong)->Unit) {
    AnimatedContent(song,contentKey={it.id},transitionSpec={
        val d=direction
        (androidx.compose.animation.slideInHorizontally(Motion.slide()){ d*it/4 }+fadeIn(tween(220))+androidx.compose.animation.scaleIn(Motion.slide(),initialScale=.92f)) togetherWith
            (androidx.compose.animation.slideOutHorizontally(tween(180)){ -d*it/4 }+fadeOut(tween(140))+androidx.compose.animation.scaleOut(tween(180),targetScale=.94f)) using
            androidx.compose.animation.SizeTransform(clip=false)
    },label="miniCancion") { s -> content(s) }
}

/** Anterior/siguiente: el icono da un empujón hacia su lado y vuelve con rebote. */
@Composable
private fun KickAction(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,direction:Int,onClick:()->Unit) {
    val kick=remember { Animatable(0f) }
    val scope=rememberCoroutineScope()
    val px=with(androidx.compose.ui.platform.LocalDensity.current) { 5.dp.toPx() }
    HoverAction(icon,label,onClick={ onClick(); scope.launch { kick.animateTo(direction*px,tween(70)); kick.animateTo(0f,Motion.pop()) } },
        size=32.dp,modifier=Modifier.graphicsLayer { translationX=kick.value })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryNavigation(name:String,icon:androidx.compose.ui.graphics.vector.ImageVector,wide:Boolean,selected:Boolean,onClick:()->Unit) {
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight by animateFloatAsState(if(selected).09f else if(hovered).05f else 0f,tween(180),label="navHighlight")
    val iconScale by animateFloatAsState(if(selected)1.08f else if(hovered)1.04f else 1f,tween(180),label="navIcon")
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha=highlight)).hoverable(interaction)
            .clickable(interactionSource=interaction,indication=null,onClick=onClick).padding(horizontal=if(wide)12.dp else 8.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=if(wide)Arrangement.spacedBy(12.dp)else Arrangement.Center) {
            Icon(icon,name,Modifier.size(18.dp).graphicsLayer { scaleX=iconScale;scaleY=iconScale },tint=Color.White.copy(alpha=if(selected).95f else .42f))
            if(wide)Text(name,style=OtoType.rowSub,color=Color.White.copy(alpha=if(selected).95f else .52f))
        }
    }
    if(wide) content() else key(LocalWindowTooltipEpoch.current) { TooltipArea(tooltip={
        Surface(color=Color(0xFF30362F),shape=RoundedCornerShape(8.dp)) {
            Text(name,Modifier.padding(8.dp),style=OtoType.rowSub)
        }
    }) { content() } }
}

@Composable internal fun LibraryArt(url:String?,modifier:Modifier=Modifier,rounded:Boolean=true) {
    var pixels by remember { mutableIntStateOf(0) }
    val previous=remember(url) { object { var value:ImageBitmap?=null } }
    Box(modifier.onSizeChanged { if(it.width>0 && it.height>0)pixels=artworkSize(maxOf(it.width,it.height)) }.then(if(rounded) Modifier.clip(RoundedCornerShape(12.dp)) else Modifier).background(Color.White.copy(alpha=.045f)),contentAlignment=Alignment.Center) {
        if(pixels==0) LibraryArtPlaceholder()
        else key(url,pixels) {
            // La primera composición de una imagen en caché ya contiene la imagen:
            // reabrir no crea decenas de transiciones desde un marcador vacío.
            var bitmap by remember { mutableStateOf(ArtworkLoader.cached(url,pixels) ?: previous.value) }
            val opacity=remember { Animatable(if(bitmap!=null) 1f else 0f) }
            LaunchedEffect(url,pixels) { ArtworkLoader.load(url,pixels).first?.let { bitmap=it;previous.value=it } }
            LaunchedEffect(bitmap) { if(bitmap!=null && opacity.value<1f)opacity.animateTo(1f,tween(220)) }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha=1f-opacity.value },contentAlignment=Alignment.Center) { LibraryArtPlaceholder() }
            // El nodo ya ocupa su sitio aunque la imagen aún esté llegando. Cambiar
            // sus píxeles solo invalida el dibujo, sin volver a medir toda la cuadrícula.
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha=opacity.value }) {
                val image=bitmap ?: return@Canvas
                if(size.minDimension<=0f)return@Canvas
                val scale=maxOf(size.width/image.width,size.height/image.height)
                val width=(size.width/scale).roundToInt().coerceIn(1,image.width)
                val height=(size.height/scale).roundToInt().coerceIn(1,image.height)
                drawImage(image,srcOffset=IntOffset((image.width-width)/2,(image.height-height)/2),srcSize=IntSize(width,height),
                    dstSize=IntSize(size.width.roundToInt(),size.height.roundToInt()),filterQuality=FilterQuality.Low)
            }
        }
    }
}

@Composable private fun LibraryArtPlaceholder() {
    Icon(MobileIcons.libraryMusic,null,Modifier.size(24.dp),tint=Color.White.copy(alpha=.15f))
}
@Composable private fun LibraryCard(title:String,sub:String,url:String?,modifier:Modifier=Modifier,click:()->Unit) {
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val grow by animateFloatAsState(if(hovered)1f else 0f,Motion.hover(),label="cardHover")
    Column(modifier.cascadeIn().hoverable(interaction).clickable(interactionSource=interaction,indication=null,onClick=click),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        val cardShape=RoundedCornerShape(12.dp)
        LibraryArt(url,Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer {
            val s=.97f+.03f*grow;scaleX=s;scaleY=s
            translationY=-4.dp.toPx()*grow
            shadowElevation=14.dp.toPx()*grow.coerceIn(0f,1f)
            shape=cardShape;clip=true
        })
        Text(title,Modifier.fillMaxWidth().padding(start=2.dp),style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(sub,Modifier.fillMaxWidth().padding(start=2.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.38f),maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
internal fun folderCount(n:Int)=if(n==1) "Carpeta · 1 lista" else "Carpeta · $n listas"

@Composable private fun PlaylistCard(title:String,sub:String,url:String?,modifier:Modifier=Modifier,special:String?=null,accent:Color=Color.White,onPlay:(()->Unit)?=null,lifted:Boolean=false,playlist:LocalPlaylist?=null,folderLists:List<LocalPlaylist> = emptyList(),click:()->Unit) {
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val grow by animateFloatAsState(if(hovered)1f else 0f,Motion.hover(),label="playlistHover")
    val playVisibility by animateFloatAsState(if(hovered && onPlay!=null)1f else 0f,Motion.hover(),label="playlistPlay")
    val elevation by animateFloatAsState(if(lifted)1f else 0f,tween(140),label="dragLift")
    Column(modifier.cascadeIn().graphicsLayer { alpha=1f-.08f*elevation }.hoverable(interaction).clickable(interactionSource=interaction,indication=null,enabled=!lifted,onClick=click),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f),contentAlignment=Alignment.Center) {
            // Se eleva con sombra y un pequeño rebote; solo capa gráfica, sin volver a medir la cuadrícula.
            val cardShape=RoundedCornerShape(12.dp)
            Box(Modifier.fillMaxSize().graphicsLayer {
                val s=.955f+.045f*grow
                scaleX=s;scaleY=s
                translationY=-4.dp.toPx()*grow
                shadowElevation=14.dp.toPx()*grow.coerceIn(0f,1f)
                shape=cardShape;clip=true
            }.then(if(lifted) Modifier.border(2.dp,accent.copy(alpha=.65f),RoundedCornerShape(12.dp)) else Modifier),contentAlignment=Alignment.Center) {
                when(special) {
                    "liked" -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent,lerp(accent,Color(0xFF121212),.55f)))),contentAlignment=Alignment.Center) {Icon(MobileIcons.favorite,null,Modifier.fillMaxSize(.42f),tint=Color(0xFF121212))}
                    "folder" -> FolderArt(folderLists,Modifier.fillMaxSize())
                    "dj" -> Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(lerp(accent,Color(0xFF121212),.25f),lerp(accent,Color(0xFF121212),.8f)))),contentAlignment=Alignment.Center) {
                        Box(Modifier.fillMaxSize(.72f).background(Color(0xFF121212),CircleShape),contentAlignment=Alignment.Center) {Box(Modifier.fillMaxSize(.36f).background(accent,CircleShape))}
                    }
                    "dj_adhd" -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lerp(accent,Color(0xFF121212),.18f),lerp(accent,Color(0xFF121212),.78f)))),contentAlignment=Alignment.Center) {
                        Box(Modifier.fillMaxSize(.74f).border(2.dp,Color(0xFF121212),CircleShape),contentAlignment=Alignment.Center) {
                            Box(Modifier.fillMaxSize(.58f).background(Color(0xFF121212),CircleShape),contentAlignment=Alignment.Center) {
                                Box(Modifier.fillMaxSize(.42f).background(accent,CircleShape))
                            }
                        }
                    }
                    else -> if(playlist != null) PlaylistMosaic(playlist,accent,Modifier.fillMaxSize()) else LibraryArt(url,Modifier.fillMaxSize())
                }
            }
            if(onPlay!=null) Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).graphicsLayer { alpha=playVisibility;scaleX=.8f+.2f*playVisibility;scaleY=.8f+.2f*playVisibility }.size(38.dp).background(Color.White.copy(alpha=.94f),CircleShape).clickable(interactionSource=remember { MutableInteractionSource() },indication=null,enabled=hovered,onClick=onPlay),contentAlignment=Alignment.Center) {Canvas(Modifier.size(18.dp).semantics { contentDescription="Reproducir $title" }) {
                val path=Path().apply { moveTo(size.width*.32f,size.height*.16f);lineTo(size.width*.82f,size.height*.5f);lineTo(size.width*.32f,size.height*.84f);close() }
                drawPath(path,Color(0xFF171A18),style=Stroke(1.8.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
            }}
        }
        Text(title,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(sub,style=OtoType.rowSub,color=Color.White.copy(alpha=.42f),maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
    }
}
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun PlaylistContextMenu(items:List<ContextMenuItem>,content:@Composable ()->Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.onPointerEvent(PointerEventType.Press) { if(it.buttons.isSecondaryPressed) { open=true;it.changes.forEach { change->change.consume() } } }) {
        content()
        LibraryDropdownMenu(expanded=open,onDismissRequest={open=false},shape=RoundedCornerShape(18.dp),modifier=Modifier.width(220.dp)) {
            items.forEach { item -> DropdownMenuItem(text={Text(item.label,style=OtoType.rowSub,color=if(item.label.startsWith("Eliminar")) Danger else Color.White.copy(alpha=.85f))},onClick={open=false;item.onClick()}) }
        }
    }
}
@Composable private fun LibraryLine(title:String,sub:String,url:String?,click:()->Unit,trailing:@Composable ()->Unit={}) {
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val glow by animateFloatAsState(if(hovered).055f else 0f,tween(160),label="songHover")
    Row(Modifier.fillMaxWidth().cascadeIn().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha=glow)).hoverable(interaction).clickable(interactionSource=interaction,indication=null,onClick=click).padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        LibraryArt(url,Modifier.size(44.dp));Column(Modifier.weight(1f)){Text(title,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis);Text(sub,style=OtoType.rowSub,maxLines=1,color=Color.White.copy(alpha=.4f))};trailing()
    }
}
@Composable private fun EmptyLibrary(text:String){Text(text,Modifier.padding(vertical=24.dp),style=OtoType.body,color=Color.White.copy(alpha=.4f))}
@Composable private fun SongList(songs:List<Heard>,current:String?,play:(Int)->Unit,save:(Heard)->Unit,player:DesktopPlayerConnection,prefs:Prefs,listId:String?=null,onArtist:((String,String)->Unit)?=null) {
    val songActions=rememberSongActions(player,prefs,save,onArtist)
    LazyColumn(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        itemsIndexed(songs,key={i,h->"${h.id}:$i"}){i,h ->
            val actions=songActions(h,listId)
            PlaylistContextMenu(actions) { LibraryLine(h.title,h.artist,h.artwork,{play(i)}) {if(h.id==current)Icon(MobileIcons.graphicEq,"Sonando",Modifier.size(17.dp),tint=Color.White.copy(alpha=.7f));SongMenuButton(h.title,actions)} }
        }
        if(songs.isEmpty())item{EmptyLibrary("Todavía no hay canciones aquí.")}
    }
}

@Composable internal fun SaveSpace(track:Heard,prefs:Prefs,palette:ArtworkPalette,onReact:(DjReaction)->Unit={}) {
    val lists by prefs.playlists.collectAsState(initial=emptyList())
    val liked by prefs.likedIds.collectAsState(initial=emptySet())
    val scope=rememberCoroutineScope()
    var query by remember{mutableStateOf("")}
    val search = query.trim()
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp, vertical=14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        LibraryLine(track.title,track.artist,track.artwork,{})
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha=.045f)).padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(MobileIcons.search,null,Modifier.size(17.dp),tint=Color.White.copy(alpha=.45f))
            BasicTextField(query,{query=it},Modifier.weight(1f),textStyle=OtoType.body.copy(color=Color.White),cursorBrush=SolidColor(palette.primary),singleLine=true,decorationBox={field -> Box { if(query.isBlank()) Text("Buscar o crear una lista",style=OtoType.body,color=Color.White.copy(alpha=.38f)); field() }})
        }
        Text("Guardar en",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f),modifier=Modifier.padding(start=2.dp,top=4.dp))
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            if("Me gusta".contains(search,true)) item(key="liked") {
                val isLiked = track.id in liked
                SaveDestination("Me gusta",null,isLiked,palette,true) {
                    scope.launch { prefs.toggleLike(track) }
                    if(!isLiked) onReact(DjReaction.Like)
                }
            }
            items(lists.filter{it.name.contains(search,true)},key={it.id}){list ->
            val saved=list.songs.any{it.id==track.id}
                SaveDestination(list.name,list.songs.firstOrNull()?.artwork,saved,palette,false) {
                    scope.launch { if(saved)prefs.removeFromPlaylist(list.id,track.id)else prefs.addToPlaylist(list.id,track) }
                    if(!saved) onReact(DjReaction.Save)
                }
            }
            if(search.isNotEmpty() && lists.none{it.name.equals(search,true)}) item(key="create") {
                TextButton(onClick={scope.launch{prefs.createPlaylist(search,track)};onReact(DjReaction.Save);query=""}){Text("Crear «$search»",color=palette.primary,maxLines=1,overflow=TextOverflow.Ellipsis)}
            }
        }
    }
}

@Composable internal fun SaveDestination(name:String,artwork:String?,saved:Boolean,palette:ArtworkPalette,liked:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick).padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        if(liked) Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(palette.primary.copy(alpha=.18f)),contentAlignment=Alignment.Center) { Icon(MobileIcons.favorite,null,Modifier.size(19.dp),tint=palette.primary) }
        else LibraryArt(artwork,Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)))
        Text(name,Modifier.weight(1f),style=OtoType.body,maxLines=1,overflow=TextOverflow.Ellipsis)
        Box(Modifier.size(22.dp).clip(CircleShape).background(if(saved) palette.primary else Color.Transparent).border(1.5.dp,if(saved) palette.primary else Color.White.copy(alpha=.38f),CircleShape).semantics { contentDescription=if(saved) "Guardado" else "No guardado" },contentAlignment=Alignment.Center) {
            if(saved) Icon(MobileIcons.check,null,Modifier.size(15.dp),tint=Color(0xFF101010))
        }
    }
}
