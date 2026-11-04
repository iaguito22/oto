package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.models.SongItem
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.desktop.playback.*
import io.github.iaguito22.oto.importer.songKey
import io.github.iaguito22.oto.playback.Radio
import kotlinx.coroutines.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.random.Random

@Composable
internal fun LocalPlaylistView(list:LocalPlaylist,currentId:String?,player:DesktopPlayerConnection,prefs:Prefs,downloads:DesktopDownloads,palette:ArtworkPalette,onSave:(Heard)->Unit,onBack:()->Unit,onFind:()->Unit,onArtist:(String,String)->Unit,actions:List<ContextMenuItem>) {
    val scope=rememberCoroutineScope()
    val flipped by prefs.sortFlipped.collectAsState(initial=emptySet())
    val wanted by prefs.downloadLists.collectAsState(initial=emptySet())
    val entries by downloads.entries.collectAsState()
    val downloadedSongs by prefs.downloadSongs.collectAsState(initial=emptyList())
    val wantedSongs=downloadedSongs.mapTo(HashSet()){it.id}
    val shuffle=rememberListShuffle(player,list.id)
    var menuOpen by remember(list.id){mutableStateOf(false)}
    val shown=remember(list,flipped){ if(list.id in flipped) list.copy(songs=list.songs.reversed()) else list }
    val newest=(list.id==LIKED_ID) != (list.id in flipped)
    val downloaded=list.id in wanted
    val got=list.songs.count { entries[it.id]?.state==DesktopDownloads.State.Done }
    fun play(index:Int?) {player.playLocal(shown,index,newest)}
    val songActions=rememberSongActions(player,prefs,onSave,onArtist)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        item(key="header") {
            Column(Modifier.fillMaxWidth().padding(bottom=24.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    HoverAction(MobileIcons.arrowBack,"Volver",onClick=onBack)
                    Spacer(Modifier.weight(1f))
                    Box {
                        HoverAction(MobileIcons.moreHoriz,"Opciones de la lista",onClick={menuOpen=true})
                        LibraryDropdownMenu(menuOpen,{menuOpen=false},shape=RoundedCornerShape(18.dp),modifier=Modifier.width(230.dp)) {
                            actions.forEach { action -> DropdownMenuItem(text={Text(action.label,style=OtoType.rowSub,color=if(action.label.startsWith("Eliminar"))Danger else Color.White)},onClick={menuOpen=false;action.onClick()}) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=12.dp,start=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    PlaylistMosaic(list,palette.primary,Modifier.size(140.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(list.name,style=OtoType.display,maxLines=3,overflow=TextOverflow.Ellipsis)
                        Text("${list.songs.size} canciones" + if(downloaded) { if(got==list.songs.size) " · descargada" else " · descargando ($got de ${list.songs.size})" } else "",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
                        if(list.songs.isNotEmpty()) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={play(null)},colors=ButtonDefaults.buttonColors(containerColor=palette.primary,contentColor=Color(0xFF101010))) {Text("Reproducir",fontWeight=FontWeight.SemiBold)}
                            HoverAction(MobileIcons.shuffle,if(shuffle) "Desactivar aleatorio" else "Activar aleatorio",onClick={player.setShuffle(!shuffle,list.id)},tint=if(shuffle)palette.primary else Color.White.copy(alpha=.6f))
                        }
                    }
                }
            }
        }
        if(list.songs.size>1) item(key="sort") {
            Row(Modifier.clip(CircleShape).clickable {scope.launch{prefs.flipSort(list.id)}}.padding(horizontal=8.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(MobileIcons.swapVert,null,Modifier.size(18.dp),tint=Color.White.copy(alpha=.5f))
                Text(if(newest)"Lo último que añadiste, arriba" else "Lo primero que añadiste, arriba",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
            }
        }
        if(list.songs.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(36.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(list.id==LIKED_ID)"Aún no te gusta nada" else "Aún no tiene canciones",style=OtoType.section)
                if(list.id==LIKED_ID)Text("Toca dos veces la carátula de lo que suena para añadirla.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f)) else TextButton(onClick=onFind){Text("Buscar canciones")}
            }
        }
        itemsIndexed(shown.songs,key={i,h->"${h.id}:$i"}) { i,h ->
            val actions=songActions(h,list.id)
            PlaylistContextMenu(actions) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable{play(i)}.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    LibraryArt(h.artwork,Modifier.size(48.dp))
                    Column(Modifier.weight(1f)){Text(h.title,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis);Text(h.artist,style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1,overflow=TextOverflow.Ellipsis)}
                    if(h.id==currentId)Icon(MobileIcons.graphicEq,"Sonando",Modifier.size(17.dp),tint=palette.primary)
                    SongMenuButton(h.title,actions)
                }
            }
        }
        if(list.songs.isNotEmpty()) item(key="suggestions") { PlaylistSuggestions(list,currentId,player,prefs,palette,songActions) }
    }
}

/** El aleatorio que recuerda [listId], o el de Ajustes si aún no se ha usado (como el móvil). */
@Composable internal fun rememberListShuffle(player:DesktopPlayerConnection,listId:String):Boolean {
    val modes by player.modes.collectAsState()
    val default by player.defaultShuffle.collectAsState()
    return modes[listId]?.shuffle ?: default
}

@Composable internal fun PlaylistMosaic(list:LocalPlaylist,accent:Color,modifier:Modifier=Modifier) {
    val arts=remember(list){list.songs.take(4).map{it.artwork}}
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha=.04f)),contentAlignment=Alignment.Center) {
        if(list.id==LIKED_ID) Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent,lerp(accent,Color(0xFF121212),.55f)))),contentAlignment=Alignment.Center){Icon(MobileIcons.favorite,null,Modifier.fillMaxSize(.42f),tint=Color(0xFF121212))}
        else if(arts.size>1) Column(Modifier.fillMaxSize()) { for(row in 0..1) Row(Modifier.weight(1f)) { for(col in 0..1) LibraryArt(arts.getOrNull(row*2+col),Modifier.weight(1f).fillMaxHeight(),rounded=false) } }
        else if(arts.isNotEmpty()) LibraryArt(arts.first(),Modifier.fillMaxSize())
        else Icon(MobileIcons.libraryMusic,null,Modifier.size(38.dp),tint=Color.White.copy(alpha=.22f))
    }
}

@Composable private fun PlaylistSuggestions(list:LocalPlaylist,currentId:String?,player:DesktopPlayerConnection,prefs:Prefs,palette:ArtworkPalette,songActions:(Heard,String?)->List<ContextMenuItem>) {
    var round by remember(list.id){mutableIntStateOf(0)}
    val scope=rememberCoroutineScope()
    val suggestions by produceState<List<SongItem>?>(null,list.id,round) {
        value=null
        value=withContext(Dispatchers.IO) {
            val rnd=Random(list.id.hashCode()*31+round)
            val newest=if(round==0)listOf(if(list.id==LIKED_ID)list.songs.first() else list.songs.last()) else emptyList()
            val seeds=(newest+list.songs.shuffled(rnd)).distinctBy{it.id}.take(3)
            val pages=coroutineScope {seeds.map { h->async {runCatching{Radio(h.id).more().getOrDefault(emptyList())}.getOrDefault(emptyList())} }.awaitAll()}
            val have=list.songs.flatMapTo(HashSet()){listOf(it.id,songKey(it.title,it.artist))};val seen=HashSet<String>();val result=mutableListOf<SongItem>()
            for(i in 0 until (pages.maxOfOrNull{it.size}?:0)) for(page in pages) {val song=page.getOrNull(i)?:continue;val key=songKey(song.title,song.artists.joinToString{it.name});if(song.id !in have && key !in have && seen.add(song.id) && seen.add(key))result+=song}
            result.take(5)
        }
    }
    val inList=list.songs.mapTo(HashSet()){it.id}
    Column(Modifier.fillMaxWidth().padding(top=28.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)){Text("Recomendadas",style=OtoType.section);Text("Parecidas a lo que tiene esta lista",style=OtoType.rowSub,color=Color.White.copy(alpha=.45f))};TextButton(onClick={round++}){Text("Otras")}}
        if(suggestions==null)LinearProgressIndicator(Modifier.fillMaxWidth(),color=palette.primary)
        else if(suggestions!!.isEmpty())Text("No han llegado: prueba con «Otras».",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
        else suggestions!!.filter{it.id !in inList}.forEach { song ->
            val h=Heard(song.id,song.title,song.artists.joinToString{it.name},song.thumbnail,song.artists.firstOrNull()?.id)
            PlaylistContextMenu(songActions(h,null)) {
                Row(Modifier.fillMaxWidth().clickable{player.play(song)}.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    LibraryArt(h.artwork,Modifier.size(48.dp));Column(Modifier.weight(1f)){Text(h.title,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis);Text(h.artist,style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1)}
                    if(h.id==currentId)Icon(MobileIcons.graphicEq,"Sonando",Modifier.size(17.dp),tint=palette.primary)
                    HoverAction(MobileIcons.add,"Añadir a la lista",onClick={scope.launch{if(list.id==LIKED_ID)prefs.setLiked(h,true)else prefs.addToPlaylist(list.id,h)}})
                }
            }
        }
    }
}
