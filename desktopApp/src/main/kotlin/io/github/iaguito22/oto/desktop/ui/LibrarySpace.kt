package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.Crossfade
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.material.icons.rounded.VolumeUp
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.desktop.playback.*
import io.github.iaguito22.oto.importer.Importer
import io.github.iaguito22.oto.ui.menu.*
import kotlinx.coroutines.launch
import org.json.JSONArray

internal data class LibraryJump(val version:Int=0,val item:YTItem?=null,val local:String?=null,val artistId:String?=null,val artistName:String="")

private data class LibraryEdit(val kind:String,val id:String="",val value:String="")

/** Biblioteca de escritorio: navegación lateral y un lienzo que cambia entre colección, búsqueda y escucha. */
@Composable
internal fun LibrarySpace(player:DesktopPlayerConnection,prefs:Prefs,menu:MenuController,importer:Importer,
    account:Account,cloud:Cloud,login:BrowserLogin,downloads:DesktopDownloads,palette:ArtworkPalette,onSave:(Heard)->Unit,onDevices:()->Unit,navigation:Pair<Int,Int> = 0 to 0,jump:LibraryJump=LibraryJump(),artworkBackground:Boolean=false,onArtworkBackground:(Boolean)->Unit={},onSearchFocus:(Boolean)->Unit={},onAccountFromMobile:()->Unit={}) {
    val lists by prefs.playlists.collectAsState(initial=emptyList())
    val liked by prefs.likedList.collectAsState(initial=LocalPlaylist(LIKED_ID,"Me gusta",emptyList()))
    val history by prefs.history.collectAsState(initial=emptyList())
    val folders by prefs.folders.collectAsState(initial=emptySet())
    val pinned by prefs.pinned.collectAsState(initial=emptySet())
    val wanted by prefs.downloadLists.collectAsState(initial=emptySet())
    val entries by downloads.entries.collectAsState()
    val state by menu.state.collectAsState()
    val np by player.state.collectAsState()
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
    val scope=rememberCoroutineScope()
    val snackbar=remember {SnackbarHostState()}
    val searchFocus=remember {FocusRequester()}
    LaunchedEffect(Unit){kotlinx.coroutines.delay(120);searchFocus.requestFocus()}
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
    var desktopOrder by remember { mutableStateOf(runCatching { val a=JSONArray(libraryKv.getString("list_order") ?: "[]"); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())) }
    fun reorderCards(id:String,target:String,available:List<String>) {
        val order=available.toMutableList()
        val from=order.indexOf(id);val to=order.indexOf(target)
        if(from<0 || to<0 || from==to)return
        order.add(to,order.removeAt(from))
        desktopOrder=desktopOrder.filter { it !in available }+order
    }
    fun saveOrder() {
        libraryKv.putString("list_order",JSONArray(desktopOrder).toString())
        val ranks=desktopOrder.withIndex().associate { it.value to it.index }
        val ordered=lists.sortedBy { ranks[it.id] ?: Int.MAX_VALUE }.map { it.id }
        scope.launch { prefs.orderLists(ordered) }
    }
    fun home(name:String) { section=name;local=null;folder=null;menu.onQuery("");while(menu.state.value.pages.isNotEmpty())menu.closeDetail() }
    LaunchedEffect(navigation.first) {if(navigation.first>0)home(if(navigation.second==1)"Explorar" else "Tu música")}
    LaunchedEffect(jump.version) { if(jump.version>0) { home("Tu música"); when { jump.item!=null -> menu.open(jump.item); jump.local==DOWNLOADS_ID -> home("Descargas"); jump.local!=null -> local=jump.local; jump.artistId!=null -> menu.openArtist(jump.artistId,jump.artistName) } } }
    fun openItem(item:YTItem) {if(item is SongItem)player.play(item) else {local=null;menu.onQuery("");menu.open(item)}}
    fun listMenu(list:LocalPlaylist)=listOf(
        ContextMenuItem("Reproducir") {player.playLocal(list,0,false)},
        ContextMenuItem(if(list.id in pinned)"Desfijar" else "Fijar arriba") {scope.launch {prefs.togglePin(list.id)}},
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
                listOf("Tu música" to MobileIcons.libraryMusic,"Explorar" to MobileIcons.graphicEq,"Recientes" to MobileIcons.history,"Descargas" to MobileIcons.download).forEach { (name,icon) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if(section==name)Color.White.copy(alpha=.07f)else Color.Transparent).clickable{home(name)}.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(icon,name,Modifier.size(18.dp),tint=Color.White.copy(alpha=if(section==name).95f else .42f))
                        if(wide)Text(name,style=OtoType.rowSub,color=Color.White.copy(alpha=if(section==name).95f else .52f))
                    }
                }
                Spacer(Modifier.weight(1f))
                if(np.id!=null) {
                    if(wide) {
                        Column(Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                                LibraryArt(np.artwork,Modifier.size(40.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(np.title,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    Text(np.artist,style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                            Text(qualityPlaying?.let { q -> listOfNotNull(q.codec.uppercase(),q.bitrate?.let { "${(it+500)/1000} kbps" },q.sampleRate?.let { if(it%1000==0) "${it/1000} kHz" else "${it/1000f} kHz" }).joinToString(" · ") } ?: if(np.buffering) "Preparando audio…" else "Calidad no disponible",style=OtoType.rowSub,color=Color.White.copy(alpha=.38f),maxLines=2)
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                                HoverAction(MobileIcons.skipPrevious,"Anterior",onClick=player::previous,size=32.dp)
                                MinimalPlayback(np.playing,player::toggle,controlSize=36.dp)
                                HoverAction(MobileIcons.skipNext,"Siguiente",onClick=player::next,size=32.dp)
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                            LibraryArt(np.artwork,Modifier.size(36.dp))
                            MinimalPlayback(np.playing,player::toggle,controlSize=36.dp)
                        }
                    }
                }
                if(wide) {
                    Column(verticalArrangement=Arrangement.spacedBy((-7).dp)) {
                    FineSlider(volume,player::setVolume,Modifier.padding(horizontal=8.dp),label="Volumen")
                    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Rounded.VolumeUp,"Volumen",Modifier.size(18.dp),tint=Color.White.copy(alpha=.55f))
                        Spacer(Modifier.weight(1f))
                        Text("${(volume*100).toInt()} %",style=OtoType.rowSub,color=Color.White.copy(alpha=.4f))
                    }
                    }
                } else Box {
                    HoverAction(Icons.Rounded.VolumeUp,"Volumen",onClick={volumeMenu=true})
                    DropdownMenu(volumeMenu,{volumeMenu=false},shape=RoundedCornerShape(18.dp),modifier=Modifier.width(190.dp)) {
                        Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)) {Text("Volumen · ${(volume*100).toInt()} %",style=OtoType.rowSub);FineSlider(volume,player::setVolume,label="Volumen")}
                    }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable{home("Ajustes")}.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){Icon(MobileIcons.settings,"Ajustes",Modifier.size(18.dp));if(wide)Text("Ajustes",style=OtoType.rowSub)}
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start=16.dp,end=28.dp,top=18.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(Color.White.copy(alpha=.045f)).padding(start=16.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(MobileIcons.search,null,Modifier.size(18.dp),tint=Color.White.copy(alpha=.35f))
                    BasicTextField(state.query,{menu.onQuery(it);local=null},Modifier.weight(1f).onFocusChanged { onSearchFocus(it.isFocused) }.focusRequester(searchFocus),textStyle=OtoType.body.copy(color=Color.White),cursorBrush=SolidColor(Color.White),singleLine=true,decorationBox={field -> if(state.query.isBlank())Text("Buscar canciones, artistas o listas",style=OtoType.body,color=Color.White.copy(alpha=.36f));field()})
                    if(state.query.isNotBlank())IconButton(onClick={menu.onQuery("")}){Icon(MobileIcons.close,"Limpiar búsqueda",Modifier.size(17.dp))}
                    HoverAction(Icons.Rounded.Devices,"Dispositivos",onClick=onDevices,tint=Color.White.copy(alpha=.55f))
                }
                val page=state.pages.lastOrNull() as? Page.Remote
                val remoteSongs=page?.songs
                val selected=(if(local==LIKED_ID)liked else lists.firstOrNull{it.id==local})
                val title=when {state.query.isNotBlank()->"Resultados";page!=null->page.item.title;selected!=null->selected.name;folder!=null->folder!!;else->section}
                if(selected==null && page?.item !is ArtistItem && page?.item !is AlbumItem && page?.item !is PlaylistItem) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    if(local!=null||page!=null||folder!=null)IconButton(onClick={local=null;folder=null;if(page!=null)menu.closeDetail()}){Icon(MobileIcons.arrowBack,"Volver",Modifier.size(20.dp))}
                    Text(title,style=OtoType.display,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                    if(section=="Tu música"&&selected==null&&page==null&&state.query.isBlank()){
                        Box {
                            HoverAction(Icons.Rounded.GridView,"Tamaño de las listas",onClick={sizeMenu=true})
                            DropdownMenu(expanded=sizeMenu,onDismissRequest={sizeMenu=false},shape=RoundedCornerShape(18.dp)) {
                                listOf("Pequeñas","Medianas","Grandes").forEachIndexed { i,label -> DropdownMenuItem(text={Text(if(cardSize==i) "$label  ✓" else label)},onClick={cardSize=i;libraryKv.putString("card_size",i.toString());sizeMenu=false}) }
                            }
                        }
                        Box(Modifier.padding(end=16.dp)) {
                            TextButton(onClick={newMenu=true}){Icon(MobileIcons.add,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("Nueva")}
                            DropdownMenu(expanded=newMenu,onDismissRequest={newMenu=false},shape=RoundedCornerShape(18.dp),offset=DpOffset((-12).dp,0.dp)) {
                                DropdownMenuItem(text={Text("Lista")},leadingIcon={Icon(MobileIcons.libraryMusic,null)},onClick={newMenu=false;editor=LibraryEdit("list")})
                                DropdownMenuItem(text={Text("Carpeta")},leadingIcon={Icon(MobileIcons.createNewFolder,null)},onClick={newMenu=false;editor=LibraryEdit("folder")})
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    when {
                        state.query.isNotBlank() -> SearchSpace(state,lists,np.id,player,menu,prefs,palette,onSave,onOpen=::openItem,onLocal={local=it;menu.onQuery("")})
                        selected!=null -> LocalPlaylistView(selected,np.id,player,prefs,downloads,palette,onSave,
                            onBack={local=null},onFind={home("Tu música");searchFocus.requestFocus()},
                            onArtist={id,name->home("Tu música");menu.openArtist(id,name)},
                            actions=if(selected.id==LIKED_ID) listOf(ContextMenuItem(if(selected.id in wanted) "Quitar la descarga" else "Descargar") {scope.launch { prefs.setDownloadList(selected.id,selected.id !in wanted) }}) else listMenu(selected))
                        page!=null && page.item is ArtistItem -> ArtistSpace(page,np.id,player,prefs,palette,onSave,onBack={menu.closeDetail()},onOpen=::openItem)
                        page!=null && (page.item is AlbumItem || page.item is PlaylistItem) -> AlbumSpace(page,np.id,player,prefs,palette,onSave,onBack={menu.closeDetail()},onArtist={id,name->menu.openArtist(id,name)})
                        page!=null -> {
                            when {page.error!=null->EmptyLibrary(page.error!!);remoteSongs==null->LinearProgressIndicator(Modifier.fillMaxWidth());else->Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                    LibraryArt(page.item.thumbnail,Modifier.size(80.dp))
                                    Button(enabled=remoteSongs.isNotEmpty(),onClick={player.playAll(remoteSongs,0,page.item)}){Text("Reproducir")}
                                }
                                if(remoteSongs.isNotEmpty())SongList(remoteSongs.map{it.toHeard()},np.id,{i->player.playAll(remoteSongs,i,page.item)},onSave,player,prefs)
                                else LazyColumn {page.shelves.forEach{shelf->item{Text(shelf.title,style=OtoType.section)};items(shelf.items){item->LibraryLine(item.title,"",item.thumbnail,{openItem(item)})}}}
                            }}
                        }
                        section=="Ajustes" -> SettingsSpace(prefs,player,palette,session,importState,artworkBackground,onArtworkBackground,onAccount={home("Cuenta")},onImport={home("Importar")},onDevices=onDevices)
                        section=="Cuenta" -> SettingsAndAccountPane(prefs,importer,account,cloud,login,session,cloudState,importState,quality,dataSaver,shuffle,loop,player::setShuffle,player::setLoop,palette,{home("Importar")}, accountOnly = true, onAccountFromMobile = onAccountFromMobile)
                        section=="Importar" -> ImportPane(importer,prefs,palette,{home("Ajustes")},{home("Tu música")})
                        section=="Descargas" -> DownloadsSpace(player,prefs,downloads,palette,np.id,onOpen={local=it},onSave=onSave,workScope=scope)
                        section=="Recientes" -> SongList(history,np.id,{i->player.play(history[i].toSong())},onSave,player,prefs)
                        section=="Explorar" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(24.dp)) {
                            item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){state.chips.forEach{chip->FilterChip(selected=state.chip==chip.title,onClick={menu.onChip(chip.title)},label={Text(chip.title)})}}}
                            if(state.homeError!=null)item{TextButton(onClick=menu::loadHome){Text("Volver a cargar Explorar")}}
                            if(state.shelves==null)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
                            state.shelves.orEmpty().forEach{shelf->item{Column(verticalArrangement=Arrangement.spacedBy(14.dp)){Text(shelf.title,style=OtoType.section);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(18.dp)){shelf.items.forEach{item->LibraryCard(item.title,when(item){is SongItem->item.artists.joinToString{it.name};else->""},item.thumbnail,Modifier.width(150.dp)){openItem(item)}}}}}}
                        }
                        else -> {
                            val ranks=desktopOrder.withIndex().associate { it.value to it.index }
                            val visible=lists.filter{it.folder==folder}.sortedBy { ranks[it.id] ?: Int.MAX_VALUE }
                            val featured=visible.filter{it.id in pinned}
                            val regular=visible.filter{it.id !in pinned}
                            val allFolders=(folders+lists.mapNotNull{it.folder}).sorted()
                            val featuredWidth=when(cardSize){0->112.dp;2->184.dp;else->144.dp}
                            val cards=((if(folder==null) allFolders.map { it to null } else emptyList()) + (if(folder==null)regular else visible).map { it.name to it }).sortedBy { (name,list) -> ranks[list?.id ?: "folder:$name"] ?: if(list==null) -1 else Int.MAX_VALUE }
                            val ids=cards.map { (name,list)->list?.id ?: "folder:$name" }
                            val grid=rememberLazyGridState()
                            val moveGrid by rememberUpdatedState<(String,String)->Unit>({from,to->reorderCards(from,to,ids)})
                            val movePinned by rememberUpdatedState<(String,String)->Unit>({from,to->reorderCards(from,to,featured.map{it.id})})
                            val persistOrder by rememberUpdatedState(::saveOrder)
                            val reorder=rememberReorderableLazyGridState(grid) { from,to -> moveGrid(from.key as String,to.key as String) }
                            val pinnedRow=rememberLazyListState()
                            val pinnedReorder=rememberReorderableLazyListState(pinnedRow) { from,to -> movePinned(from.key as String,to.key as String) }
                            LazyVerticalGrid(columns=GridCells.Adaptive(when(cardSize){0->130.dp;2->220.dp;else->170.dp}),state=grid,verticalArrangement=Arrangement.spacedBy(18.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=28.dp)) {
                                if(folder==null) item(key="featured",span={GridItemSpan(maxLineSpan)}) {
                                    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                        Text("Tus listas",style=OtoType.section)
                                        LazyRow(state=pinnedRow,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                            item(key="dj") { PlaylistCard("DJ","Mezcla para ti",null,Modifier.width(featuredWidth),special="dj",accent=palette.primary,onPlay={player.playDj {scope.launch{snackbar.showSnackbar("No se pudo iniciar el DJ")}}}) {player.playDj {scope.launch{snackbar.showSnackbar("No se pudo iniciar el DJ")}}} }
                                            item(key="liked") { PlaylistCard("Me gusta","${liked.songs.size} canciones",null,Modifier.width(featuredWidth),special="liked",accent=palette.primary,onPlay=if(liked.songs.isNotEmpty()){{player.playLocal(liked,0,false)}}else null) {local=LIKED_ID} }
                                            items(featured,key={it.id}) { list ->
                                                ReorderableItem(pinnedReorder,list.id) { dragging ->
                                                    PlaylistContextMenu(listMenu(list)) {
                                                        PlaylistCard(list.name,"${list.songs.size} canciones",list.songs.firstOrNull()?.artwork,Modifier.width(featuredWidth).longPressDraggableHandle(onDragStopped={persistOrder()}),onPlay=if(list.songs.isNotEmpty()){{player.playLocal(list,0,false)}}else null,lifted=dragging){local=list.id}
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if(cards.isNotEmpty())item(key="lists-title",span={GridItemSpan(maxLineSpan)}) {Text(if(folder==null) "Más de tus listas" else "Listas de la carpeta",style=OtoType.section)}
                                items(cards,key={ (name,list)->list?.id ?: "folder:$name" }) { (name,list) ->
                                    val id=list?.id ?: "folder:$name"
                                    ReorderableItem(reorder,id) { dragging ->
                                        val drag=Modifier.fillMaxWidth().longPressDraggableHandle(onDragStopped={persistOrder()})
                                        if(list==null) PlaylistContextMenu(listOf(ContextMenuItem("Renombrar"){editor=LibraryEdit("renameFolder",name,name)},ContextMenuItem("Desagrupar"){scope.launch{prefs.ungroup(name)}})) {
                                            val count=lists.count{it.folder==name}
                                            PlaylistCard(name,if(count==1) "Carpeta · 1 lista" else "Carpeta · $count listas",null,drag,special="folder",accent=palette.primary,lifted=dragging){folder=name}
                                        } else PlaylistContextMenu(listMenu(list)) {
                                            PlaylistCard(list.name,"${list.songs.size} canciones",list.songs.firstOrNull()?.artwork,drag,onPlay=if(list.songs.isNotEmpty()){{player.playLocal(list,0,false)}}else null,lifted=dragging){local=list.id}
                                        }
                                    }
                                }
                                if(folder!=null && visible.isEmpty())item(span={GridItemSpan(maxLineSpan)}){EmptyLibrary("Esta carpeta está vacía.")}
                                if(folder==null && history.isNotEmpty()) {
                                    item(key="recent-title",span={GridItemSpan(maxLineSpan)}) {Text("Oído hace poco",style=OtoType.section)}
                                    items(history.take(8),key={"recent:${it.id}"},span={GridItemSpan(maxLineSpan)}) {h->LibraryLine(h.title,h.artist,h.artwork,{player.play(h.toSong())})}
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
        val isFolder = edit.kind == "folder" || edit.kind == "renameFolder"
        val creating = edit.kind == "list" || edit.kind == "folder"
        val title = when(edit.kind){"folder"->"Nueva carpeta";"list"->"Nueva lista";"renameFolder"->"Renombrar carpeta";else->"Renombrar lista"}
        val action = when(edit.kind){"folder"->"Crear carpeta";"list"->"Crear lista";else->"Guardar cambios"}
        val focus = remember(edit) { FocusRequester() }
        fun save() {
            val name = text.trim()
            if(name.isBlank()) return
            scope.launch { when(edit.kind){"list"->prefs.createPlaylist(name);"folder"->prefs.createFolder(name);"renameFolder"->prefs.renameFolder(edit.id,name);else->prefs.renamePlaylist(edit.id,name)} }
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
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { TextButton(onClick={moveList=null}) { Text("Cancelar",color=Color.White.copy(alpha=.65f)) } }
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
                            if(snackbar.showSnackbar("Lista eliminada","Deshacer")==SnackbarResult.ActionPerformed) {
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

@Composable internal fun LibraryArt(url:String?,modifier:Modifier=Modifier) {
    var bitmap by remember(url){mutableStateOf<ImageBitmap?>(null)}
    LaunchedEffect(url){bitmap=ArtworkLoader.load(url).first}
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha=.045f)),contentAlignment=Alignment.Center){if(bitmap!=null)Image(bitmap!!,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else Icon(MobileIcons.libraryMusic,null,Modifier.size(24.dp),tint=Color.White.copy(alpha=.15f))}
}
@Composable private fun LibraryCard(title:String,sub:String,url:String?,modifier:Modifier=Modifier,click:()->Unit) {
    Column(modifier.clickable(onClick=click),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        LibraryArt(url,Modifier.fillMaxWidth().aspectRatio(1f))
        Text(title,Modifier.fillMaxWidth().padding(start=2.dp),style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(sub,Modifier.fillMaxWidth().padding(start=2.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.38f),maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
@Composable private fun PlaylistCard(title:String,sub:String,url:String?,modifier:Modifier=Modifier,special:String?=null,accent:Color=Color.White,onPlay:(()->Unit)?=null,lifted:Boolean=false,click:()->Unit) {
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val grow by animateFloatAsState(if(hovered)1f else 0f,tween(180),label="playlistHover")
    val elevation by animateFloatAsState(if(lifted)1f else 0f,tween(140),label="dragLift")
    Column(modifier.graphicsLayer { alpha=1f-.08f*elevation }.hoverable(interaction).clickable(interactionSource=interaction,indication=null,enabled=!lifted,onClick=click),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f),contentAlignment=Alignment.Center) {
            Box(Modifier.fillMaxSize().padding((4f*(1f-grow)).dp).clip(RoundedCornerShape(12.dp)).then(if(lifted) Modifier.border(2.dp,accent.copy(alpha=.65f),RoundedCornerShape(12.dp)) else Modifier),contentAlignment=Alignment.Center) {
                when(special) {
                    "liked" -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent,lerp(accent,Color(0xFF121212),.55f)))),contentAlignment=Alignment.Center) {Icon(MobileIcons.favorite,null,Modifier.fillMaxSize(.42f),tint=Color(0xFF121212))}
                    "folder" -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lerp(accent,Color(0xFF121212),.7f),Color(0xFF222822)))),contentAlignment=Alignment.Center) { Icon(MobileIcons.folder,null,Modifier.fillMaxSize(.34f),tint=accent.copy(alpha=.8f)) }
                    "dj" -> Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(lerp(accent,Color(0xFF121212),.25f),lerp(accent,Color(0xFF121212),.8f)))),contentAlignment=Alignment.Center) {
                        Box(Modifier.fillMaxSize(.72f).background(Color(0xFF121212),CircleShape),contentAlignment=Alignment.Center) {Box(Modifier.fillMaxSize(.36f).background(accent,CircleShape))}
                    }
                    else -> LibraryArt(url,Modifier.fillMaxSize())
                }
            }
            if(onPlay!=null && hovered) Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(38.dp).background(Color.White.copy(alpha=.94f),CircleShape).clickable(interactionSource=remember { MutableInteractionSource() },indication=null,onClick=onPlay),contentAlignment=Alignment.Center) {Canvas(Modifier.size(18.dp).semantics { contentDescription="Reproducir $title" }) {
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
        DropdownMenu(expanded=open,onDismissRequest={open=false},shape=RoundedCornerShape(18.dp),modifier=Modifier.width(220.dp)) {
            items.forEach { item -> DropdownMenuItem(text={Text(item.label,style=OtoType.rowSub,color=if(item.label.startsWith("Eliminar")) Danger else Color.White.copy(alpha=.85f))},onClick={open=false;item.onClick()}) }
        }
    }
}
@Composable private fun LibraryLine(title:String,sub:String,url:String?,click:()->Unit,trailing:@Composable ()->Unit={}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick=click).padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        LibraryArt(url,Modifier.size(44.dp));Column(Modifier.weight(1f)){Text(title,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis);Text(sub,style=OtoType.rowSub,maxLines=1,color=Color.White.copy(alpha=.4f))};trailing()
    }
}
@Composable private fun EmptyLibrary(text:String){Text(text,Modifier.padding(vertical=24.dp),style=OtoType.body,color=Color.White.copy(alpha=.4f))}
@Composable private fun SongList(songs:List<Heard>,current:String?,play:(Int)->Unit,save:(Heard)->Unit,player:DesktopPlayerConnection,prefs:Prefs,listId:String?=null) {
    val scope=rememberCoroutineScope()
    val requestedDownloads by prefs.downloadSongs.collectAsState(initial=emptyList())
    val requestedIds=remember(requestedDownloads){requestedDownloads.mapTo(HashSet()){it.id}}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        itemsIndexed(songs,key={i,h->"${h.id}:$i"}){i,h ->
            PlaylistContextMenu(buildList {
                add(ContextMenuItem("Guardar en…"){save(h)})
                add(ContextMenuItem("Reproducir después"){player.playNext(h.toSong())})
                add(ContextMenuItem(if(h.id in requestedIds) "Quitar la descarga" else "Descargar"){scope.launch{prefs.setDownloadSong(h,h.id !in requestedIds)}})
                if(listId!=null)add(ContextMenuItem("Quitar de esta lista"){scope.launch{if(listId==LIKED_ID)prefs.setLiked(h,false)else prefs.removeFromPlaylist(listId,h.id)}})
            }) { LibraryLine(h.title,h.artist,h.artwork,{play(i)}) {if(h.id==current)Icon(MobileIcons.graphicEq,"Sonando",Modifier.size(17.dp),tint=Color.White.copy(alpha=.7f));IconButton(onClick={save(h)}){Icon(MobileIcons.moreHoriz,"Guardar en…",Modifier.size(18.dp),tint=Color.White.copy(alpha=.35f))}} }
        }
        if(songs.isEmpty())item{EmptyLibrary("Todavía no hay canciones aquí.")}
    }
}

@Composable internal fun SaveSpace(track:Heard,prefs:Prefs,palette:ArtworkPalette) {
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
                SaveDestination("Me gusta",null,track.id in liked,palette,true) { scope.launch { prefs.toggleLike(track) } }
            }
            items(lists.filter{it.name.contains(search,true)},key={it.id}){list ->
            val saved=list.songs.any{it.id==track.id}
                SaveDestination(list.name,list.songs.firstOrNull()?.artwork,saved,palette,false) { scope.launch { if(saved)prefs.removeFromPlaylist(list.id,track.id)else prefs.addToPlaylist(list.id,track) } }
            }
            if(search.isNotEmpty() && lists.none{it.name.equals(search,true)}) item(key="create") {
                TextButton(onClick={scope.launch{prefs.createPlaylist(search,track)};query=""}){Text("Crear «$search»",color=palette.primary,maxLines=1,overflow=TextOverflow.Ellipsis)}
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
