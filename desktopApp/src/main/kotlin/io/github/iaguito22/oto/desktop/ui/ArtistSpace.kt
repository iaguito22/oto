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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.models.*
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.desktop.playback.*
import io.github.iaguito22.oto.ui.menu.Page
import io.github.iaguito22.oto.playback.ArtistTracks
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
internal fun ArtistSpace(page:Page.Remote,currentId:String?,player:DesktopPlayerConnection,prefs:Prefs,palette:ArtworkPalette,onSave:(Heard)->Unit,onBack:()->Unit,onOpen:(YTItem)->Unit) {
    val songs=page.songs
    val scope=rememberCoroutineScope()
    val globalShuffle by player.shuffle.collectAsState()
    val kv=remember{OtoDirs.keyValue("desktop_list_modes")}
    var shuffle by remember(page.item.id){mutableStateOf(kv.getString(page.item.id)?.toBooleanStrictOrNull() ?: globalShuffle)}
    val downloaded by prefs.downloadSongs.collectAsState(initial=emptyList())
    fun play(index:Int?) { if(songs!=null && songs.isNotEmpty()) {
        player.setShuffle(shuffle);player.playAll(songs,index,page.item)
        val artist=page.item as? ArtistItem ?: return
        val albums=page.shelves.flatMap{it.items}.filterIsInstance<AlbumItem>()
        scope.launch { val more=ArtistTracks.more(artist,songs,albums);player.appendArtistTracks(artist.id,more) }
    } }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item(key="artistHeader") {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    HoverAction(MobileIcons.arrowBack,"Volver",onClick=onBack)
                    Spacer(Modifier.weight(1f))
                    HoverAction(MobileIcons.share,"Copiar enlace del artista",onClick={runCatching{Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("https://music.youtube.com/channel/${page.item.id}"),null)}})
                }
                Row(Modifier.fillMaxWidth().padding(top=12.dp,start=8.dp,bottom=22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.size(140.dp).clip(CircleShape)){LibraryArt(page.item.thumbnail,Modifier.fillMaxSize())}
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(page.item.title,style=OtoType.display,maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(!page.sub.isNullOrBlank())Text(page.sub!!,style=OtoType.rowSub,color=Color.White.copy(alpha=.5f),maxLines=1)
                        if(!songs.isNullOrEmpty()) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={play(null)},colors=ButtonDefaults.buttonColors(containerColor=palette.primary,contentColor=Color(0xFF101010))){Text("Reproducir",fontWeight=FontWeight.SemiBold)}
                            HoverAction(MobileIcons.shuffle,if(shuffle)"Desactivar aleatorio" else "Activar aleatorio",onClick={shuffle=!shuffle;kv.putString(page.item.id,shuffle.toString());if(player.source.value?.item?.id==page.item.id)player.setShuffle(shuffle)},tint=if(shuffle)palette.primary else Color.White.copy(alpha=.55f))
                        }
                    }
                }
            }
        }
        when {
            page.error!=null -> item {Text("No se pudo abrir · ${page.error}",Modifier.padding(20.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))}
            songs==null -> item {LinearProgressIndicator(Modifier.fillMaxWidth(),color=palette.primary)}
            else -> {
                if(songs.isNotEmpty()) item {Text("Destacadas",Modifier.padding(top=8.dp,bottom=8.dp),style=OtoType.section)}
                itemsIndexed(songs.take(5),key={i,s->"top:${s.id}:$i"}) {i,song ->
                    val h=song.toHeard();var open by remember(song.id){mutableStateOf(false)}
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {play(i)}.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        LibraryArt(h.artwork,Modifier.size(48.dp));Column(Modifier.weight(1f)){Text(h.title,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis);Text(h.artist,style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1,overflow=TextOverflow.Ellipsis)}
                        if(h.id==currentId)Icon(MobileIcons.graphicEq,"Sonando",Modifier.size(17.dp),tint=palette.primary)
                        Box {HoverAction(MobileIcons.moreHoriz,"Opciones de ${h.title}",onClick={open=true});LibraryDropdownMenu(open,{open=false},shape=RoundedCornerShape(18.dp),modifier=Modifier.width(230.dp)) {
                            @Composable fun Action(label:String,click:()->Unit){DropdownMenuItem(text={Text(label,style=OtoType.rowSub)},onClick={open=false;click()})}
                            Action("Reproducir a continuación"){player.playNext(song)}
                            Action("Guardar en…"){onSave(h)}
                            Action(if(downloaded.any{it.id==h.id})"Quitar la descarga" else "Descargar"){scope.launch{prefs.setDownloadSong(h,downloaded.none{it.id==h.id})}}
                            Action("Copiar enlace"){runCatching{Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("https://music.youtube.com/watch?v=${h.id}"),null)}}
                        }}
                    }
                }
                page.shelves.forEachIndexed { index,shelf -> item(key="shelf:$index") {
                    Column(Modifier.fillMaxWidth().padding(top=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(shelf.title,style=OtoType.section)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            shelf.items.forEach { item ->
                                Column(Modifier.width(144.dp).clickable {if(item is SongItem)player.play(item) else onOpen(item)},verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                    LibraryArt(item.thumbnail,Modifier.fillMaxWidth().aspectRatio(1f));Text(item.title,style=OtoType.rowTitle,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    val subtitle=when(item){is SongItem->item.artists.joinToString{it.name};is AlbumItem->item.artists?.joinToString{it.name}.orEmpty();is ArtistItem->"Artista";is PlaylistItem->"Lista";else->""}
                                    if(subtitle.isNotBlank())Text(subtitle,style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                } }
            }
        }
    }
}
