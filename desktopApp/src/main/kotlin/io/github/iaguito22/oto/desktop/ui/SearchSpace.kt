package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.*
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.desktop.playback.*
import io.github.iaguito22.oto.playback.ArtistTracks
import io.github.iaguito22.oto.ui.menu.*
import kotlinx.coroutines.launch

@Composable
internal fun SearchSpace(state:MenuState,lists:List<LocalPlaylist>,currentId:String?,player:DesktopPlayerConnection,menu:MenuController,prefs:Prefs,palette:ArtworkPalette,onSave:(Heard)->Unit,onOpen:(YTItem)->Unit,onLocal:(String)->Unit,onArtist:(String,String)->Unit={_,_->},onRemember:(YTItem?)->Unit={}) {
    val scope=rememberCoroutineScope()
    val songActions=rememberSongActions(player,prefs,onSave,onArtist)
    var loading by remember {mutableStateOf<String?>(null)}
    val local=if(state.filter==Filter.All||state.filter==Filter.Lists)lists.filter{it.name.contains(state.query,true)} else emptyList()
    fun playItem(item:YTItem) {
        onRemember(item)
        if(item is SongItem){player.play(item);return}
        if(loading!=null)return
        loading=item.id
        scope.launch {
            val songs=menu.songsOf(item)
            if(!songs.isNullOrEmpty()) {
                player.playAll(songs,null,item)
                if(item is ArtistItem) {
                    val artist=YouTube.artist(item.id).getOrNull()
                    if(artist!=null)player.appendArtistTracks(item.id,ArtistTracks.more(artist.artist,songs,artist.sections.flatMap{it.items}.filterIsInstance<AlbumItem>()))
                }
            }
            loading=null
        }
    }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach {f->Text(f.label,Modifier.clip(CircleShape).background(if(state.filter==f)palette.primary.copy(alpha=.22f) else Color.White.copy(alpha=.05f)).clickable{menu.onFilter(f)}.padding(horizontal=14.dp,vertical=8.dp),style=OtoType.rowSub,color=if(state.filter==f)palette.primary else Color.White.copy(alpha=.6f))}
        }
        when {
            state.searching && state.results.isEmpty() && local.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth(),color=palette.primary)
            state.searchError!=null && state.results.isEmpty() && local.isEmpty() -> Text("La búsqueda falló · ${state.searchError}",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
            state.query.trim().length<2 && state.results.isEmpty() && local.isEmpty() -> Text("Sigue escribiendo. Con dos letras ya busca.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
            state.results.isEmpty() && local.isEmpty() -> Text("Sin resultados. Prueba con otro nombre o artista.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
            else -> LazyColumn(contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                items(local,key={"local:${it.id}"}) {list->SearchLine(list.name,"Tu lista · ${list.songs.size} canciones",list.songs.firstOrNull()?.artwork,false,{onRemember(null);onLocal(list.id)})}
                itemsIndexed(state.results,key={i,item->"$i:${item.id}"}) {i,item->
                    val subtitle=when(item){is SongItem->"Canción · "+item.artists.joinToString{it.name};is ArtistItem->"Artista";is AlbumItem->listOfNotNull("Álbum",item.artists?.joinToString{it.name}?.takeIf{it.isNotBlank()},item.year?.toString()).joinToString(" · ");is PlaylistItem->listOfNotNull("Lista",item.author?.name).joinToString(" · ");else->""}
                    val open={onRemember(item);if(item is SongItem)player.play(item) else onOpen(item)}
                    if(i==0 && state.filter==Filter.All) {
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha=.045f)).clickable(onClick=open).padding(20.dp)) {
                            Column(Modifier.padding(end=60.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(88.dp).clip(if(item is ArtistItem)CircleShape else RoundedCornerShape(12.dp))){LibraryArt(item.thumbnail,Modifier.fillMaxSize())}
                                Text(item.title,style=OtoType.title,color=if(item.id==currentId)palette.primary else Color.White,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(subtitle,style=OtoType.rowSub,color=Color.White.copy(alpha=.5f),maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            Box(Modifier.align(Alignment.BottomEnd).size(48.dp).clip(CircleShape).background(palette.primary.copy(alpha=if(loading==item.id).5f else 1f)).clickable(enabled=loading==null){playItem(item)},contentAlignment=Alignment.Center) {
                                androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {val p=androidx.compose.ui.graphics.Path().apply{moveTo(size.width*.32f,size.height*.16f);lineTo(size.width*.82f,size.height*.5f);lineTo(size.width*.32f,size.height*.84f);close()};drawPath(p,Color(0xFF111111),style=androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round))}
                            }
                        }
                    } else if(item is SongItem) {
                        val h=item.toHeard();val actions=songActions(h,null)
                        PlaylistContextMenu(actions) { SearchLine(item.title,subtitle,item.thumbnail,false,open) { SongMenuButton(item.title,actions) } }
                    } else SearchLine(item.title,subtitle,item.thumbnail,item is ArtistItem,open)
                }
            }
        }
    }
}

@Composable private fun SearchLine(title:String,subtitle:String,art:String?,round:Boolean,click:()->Unit,trailing:@Composable ()->Unit={}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick=click).padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).clip(if(round)CircleShape else RoundedCornerShape(12.dp))){LibraryArt(art,Modifier.fillMaxSize())}
        Column(Modifier.weight(1f)){Text(title,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis);Text(subtitle,style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1,overflow=TextOverflow.Ellipsis)}
        trailing()
    }
}
