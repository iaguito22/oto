package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
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
internal fun ArtistSpace(page:Page.Remote,currentId:String?,player:DesktopPlayerConnection,prefs:Prefs,palette:ArtworkPalette,onSave:(Heard)->Unit,onBack:()->Unit,onOpen:(YTItem)->Unit,onArtist:(String,String)->Unit={_,_->}) {
    val immersive = LocalFloatingDockInset.current > 0.dp
    val songs=page.songs
    val songActions=rememberSongActions(player,prefs,onSave,onArtist,here=page.item.id)
    val scope=rememberCoroutineScope()
    val shuffle=rememberListShuffle(player,page.item.id)
    val downloaded by prefs.downloadSongs.collectAsState(initial=emptyList())
    fun play(index:Int?) { if(songs!=null && songs.isNotEmpty()) {
        player.playAll(songs,index,page.item)
        val artist=page.item as? ArtistItem ?: return
        val albums=page.shelves.flatMap{it.items}.filterIsInstance<AlbumItem>()
        scope.launch { val more=ArtistTracks.more(artist,songs,albums);player.appendArtistTracks(artist.id,more) }
    } }
    LazyColumn(Modifier.fillMaxSize().then(if(immersive)Modifier.background(Color(0x400F0F11)) else Modifier),contentPadding=PaddingValues(bottom=32.dp+LocalFloatingDockInset.current),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item(key="artistHeader") {
            if (immersive) {
                ArtistHero(page, palette, !songs.isNullOrEmpty(), shuffle, onBack, { play(null) }, { player.setShuffle(!shuffle,page.item.id) })
            } else Column(Modifier.fillMaxWidth()) {
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
                            HoverAction(MobileIcons.shuffle,if(shuffle)"Desactivar aleatorio" else "Activar aleatorio",onClick={player.setShuffle(!shuffle,page.item.id)},tint=if(shuffle)palette.primary else Color.White.copy(alpha=.55f))
                        }
                    }
                }
            }
        }
        when {
            page.error!=null -> item {Text("No se pudo abrir · ${page.error}",Modifier.padding(20.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))}
            songs==null -> item {LinearProgressIndicator(Modifier.fillMaxWidth(),color=palette.primary)}
            else -> {
                if(songs.isNotEmpty()) item {Text(if(immersive)"Populares" else "Destacadas",Modifier.padding(start=if(immersive)24.dp else 0.dp,top=8.dp,bottom=8.dp),style=OtoType.section)}
                itemsIndexed(songs.take(5),key={i,s->"top:${s.id}:$i"}) {i,song ->
                    val h=song.toHeard();val actions=songActions(h,null)
                    PlaylistContextMenu(actions) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=if(immersive)16.dp else 0.dp).clip(RoundedCornerShape(12.dp)).clickable {play(i)}.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                            if(immersive)Text((i+1).toString(),Modifier.width(18.dp),style=OtoType.rowSub,color=Color.White.copy(.5f),textAlign=TextAlign.Center)
                            LibraryArt(h.artwork,Modifier.size(if(immersive)36.dp else 48.dp).then(if(immersive)Modifier.clip(RoundedCornerShape(4.dp)) else Modifier),rounded=!immersive);Column(Modifier.weight(1f)){Text(h.title,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis);Text(h.artist,style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1,overflow=TextOverflow.Ellipsis)}
                            if(h.id==currentId)Icon(MobileIcons.graphicEq,"Sonando",Modifier.size(17.dp),tint=palette.primary)
                            SongMenuButton(h.title,actions)
                        }
                    }
                }
                page.shelves.forEachIndexed { index,shelf -> item(key="shelf:$index") {
                    Column(Modifier.fillMaxWidth().padding(start=if(immersive)24.dp else 0.dp,end=if(immersive)24.dp else 0.dp,top=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
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


@Composable
private fun ArtistHero(page: Page.Remote, palette: ArtworkPalette, playable: Boolean, shuffle: Boolean,
    onBack: () -> Unit, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val heroHeight = (maxWidth * .48f).coerceIn(240.dp, 360.dp)
            val heroFontSize = if(maxWidth > 600.dp) 52.sp else 40.sp
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                LibraryArt(page.item.thumbnail, Modifier.fillMaxSize(), rounded = false, alignment = Alignment.TopCenter)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.12f), Color.Transparent, Color(0xFF17181B)))))
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    HoverAction(MobileIcons.arrowBack, "Volver", onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    HoverAction(MobileIcons.share, "Copiar enlace del artista", onClick = {
                        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(page.item.shareLink), null) }
                    })
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(page.item.title, style = OtoType.display.copy(fontSize = heroFontSize, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!page.sub.isNullOrBlank()) Text(page.sub!!, style = OtoType.rowSub, color = Color.White.copy(.8f), maxLines = 2)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledIconButton(onClick = onPlay, enabled = playable, modifier = Modifier.size(52.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = palette.primary, contentColor = Color(0xFF101010))) {
                Icon(androidx.compose.material.icons.Icons.Rounded.PlayArrow, "Reproducir artista", Modifier.size(28.dp))
            }
            HoverAction(MobileIcons.shuffle, if(shuffle) "Desactivar aleatorio" else "Activar aleatorio", onClick = onShuffle, tint = if(shuffle)palette.primary else Color.White.copy(.65f))
        }
    }
}
