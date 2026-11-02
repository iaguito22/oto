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
import androidx.compose.ui.window.Dialog
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.PlaylistItem
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
import io.github.iaguito22.oto.ui.menu.Page
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
internal fun AlbumSpace(page:Page.Remote,currentId:String?,player:DesktopPlayerConnection,prefs:Prefs,palette:ArtworkPalette,onSave:(Heard)->Unit,onBack:()->Unit,onArtist:(String,String)->Unit) {
    val album=page.item
    val songs=page.songs
    val globalShuffle by player.shuffle.collectAsState()
    val kv=remember {OtoDirs.keyValue("desktop_list_modes")}
    var shuffle by remember(album.id){mutableStateOf(kv.getString(album.id)?.toBooleanStrictOrNull() ?: globalShuffle)}
    var saving by remember(album.id){mutableStateOf(false)}
    val downloaded by prefs.downloadSongs.collectAsState(initial=emptyList())
    val scope=rememberCoroutineScope()
    fun play(index:Int?) {if(!songs.isNullOrEmpty()){player.setShuffle(shuffle);player.playAll(songs,index,album)}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        item(key="header") {Column(Modifier.fillMaxWidth().padding(bottom=24.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                HoverAction(MobileIcons.arrowBack,"Volver",onClick=onBack);Spacer(Modifier.weight(1f))
                HoverAction(MobileIcons.share,"Copiar enlace",onClick={runCatching{Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(album.shareLink),null)}})
            }
            Row(Modifier.fillMaxWidth().padding(top=12.dp,start=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                LibraryArt(album.thumbnail,Modifier.size(140.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(album.title,style=OtoType.display,maxLines=3,overflow=TextOverflow.Ellipsis)
                    val sub=when(album) {
                        is AlbumItem -> listOfNotNull(album.artists?.joinToString{it.name}?.takeIf{it.isNotBlank()},album.year?.toString()).joinToString(" · ")
                        is PlaylistItem -> listOfNotNull(album.author?.name,album.songCountText).joinToString(" · ")
                        else -> ""
                    }
                    if(sub.isNotBlank())Text(sub,style=OtoType.rowSub,color=Color.White.copy(alpha=.5f),maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(!songs.isNullOrEmpty())Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={play(null)},colors=ButtonDefaults.buttonColors(containerColor=palette.primary,contentColor=Color(0xFF101010))){Text("Reproducir",fontWeight=FontWeight.SemiBold)}
                        HoverAction(MobileIcons.shuffle,if(shuffle)"Desactivar aleatorio" else "Activar aleatorio",onClick={shuffle=!shuffle;kv.putString(album.id,shuffle.toString());if(player.source.value?.item?.id==album.id)player.setShuffle(shuffle)},tint=if(shuffle)palette.primary else Color.White.copy(alpha=.6f))
                        HoverAction(MobileIcons.add,"Guardar canciones",onClick={saving=true})
                    }
                }
            }
        }}
        when {
            page.error!=null -> item{Text("No se pudo abrir · ${page.error}",Modifier.padding(20.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))}
            songs==null -> item{LinearProgressIndicator(Modifier.fillMaxWidth(),color=palette.primary)}
            songs.isEmpty() -> item{Text("No hay canciones disponibles.",Modifier.padding(20.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))}
            else -> itemsIndexed(songs,key={i,s->"${s.id}:$i"}){i,song ->
                val h=song.toHeard();var menu by remember(song.id){mutableStateOf(false)}
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable{play(i)}.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    LibraryArt(h.artwork,Modifier.size(48.dp));Column(Modifier.weight(1f)){Text(h.title,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis);Text(h.artist,style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1,overflow=TextOverflow.Ellipsis)}
                    if(h.id==currentId)Icon(MobileIcons.graphicEq,"Sonando",Modifier.size(17.dp),tint=palette.primary)
                    Box {HoverAction(MobileIcons.moreHoriz,"Opciones de ${h.title}",onClick={menu=true});DropdownMenu(menu,{menu=false},shape=RoundedCornerShape(18.dp),modifier=Modifier.width(240.dp)) {
                        @Composable fun Action(label:String,click:()->Unit){DropdownMenuItem(text={Text(label,style=OtoType.rowSub)},onClick={menu=false;click()})}
                        Action("Reproducir a continuación"){player.playNext(song)}
                        Action("Guardar en…"){onSave(h)}
                        Action(if(downloaded.any{it.id==h.id})"Quitar la descarga" else "Descargar"){scope.launch{prefs.setDownloadSong(h,downloaded.none{it.id==h.id})}}
                        h.artistId?.let{id->Action("Ir a ${h.artist}"){onArtist(id,h.artist)}}
                        Action("Copiar enlace"){runCatching{Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(song.shareLink),null)}}
                    }}
                }
            }
        }
    }
    if(saving && !songs.isNullOrEmpty()) {
        val lists by prefs.playlists.collectAsState(initial=emptyList())
        val liked by prefs.likedIds.collectAsState(initial=emptySet())
        val tracks=remember(songs){songs.map{it.toHeard()}}
        Dialog(onDismissRequest={saving=false}) {
            Column(Modifier.width(390.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF242826)).border(1.dp,Color.White.copy(alpha=.08f),RoundedCornerShape(24.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    LibraryArt(album.thumbnail,Modifier.size(62.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("Guardar canciones",style=OtoType.section)
                        Text(album.title,style=OtoType.rowSub,color=Color.White.copy(alpha=.55f),maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text("${tracks.size} canciones",style=OtoType.rowSub,color=palette.primary)
                    }
                }
                LazyColumn(Modifier.heightIn(max=340.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    item {
                        val saved=tracks.all{it.id in liked}
                        SaveDestination("Me gusta",null,saved,palette,true) { if(!saved)scope.launch { tracks.forEach { prefs.setLiked(it,true) } } }
                    }
                    items(lists,key={it.id}) { list ->
                        val saved=tracks.all { h -> list.songs.any { it.id==h.id } }
                        SaveDestination(list.name,list.songs.firstOrNull()?.artwork,saved,palette,false) { if(!saved)scope.launch { prefs.addAllToPlaylist(list.id,tracks) } }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { Button(onClick={saving=false}) { Text("Listo") } }
            }
        }
    }
}
