package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.desktop.playback.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DownloadsSpace(player:DesktopPlayerConnection,prefs:Prefs,downloads:DesktopDownloads,palette:ArtworkPalette,currentId:String?,onOpen:(String)->Unit,onSave:(Heard)->Unit,workScope:CoroutineScope) {
    val wanted by prefs.downloadLists.collectAsState(initial=emptySet())
    val singles by prefs.downloadSongs.collectAsState(initial=emptyList())
    val playlists by prefs.playlists.collectAsState(initial=emptyList())
    val liked by prefs.likedList.collectAsState(initial=LocalPlaylist(LIKED_ID,"Me gusta",emptyList()))
    val entries by downloads.entries.collectAsState()
    val scope=rememberCoroutineScope()
    val snackbar=remember {SnackbarHostState()}
    val hidden=remember {mutableStateListOf<String>()}
    var confirm by remember {mutableStateOf(false)}
    LaunchedEffect(confirm){if(confirm){delay(3000);confirm=false}}
    val lists=wanted.filter{"list:$it" !in hidden}.mapNotNull{id->if(id==LIKED_ID)liked else playlists.firstOrNull{it.id==id}}
    val songs=singles.filter{"song:${it.id}" !in hidden}
    val all=(lists.flatMap{it.songs}+songs).distinctBy{it.id}.filter{entries[it.id]?.state==DesktopDownloads.State.Done}
    val totalBytes by produceState(0L,entries){value=withContext(Dispatchers.IO){downloads.bytes()}}
    val pending=entries.values.count{it.state==DesktopDownloads.State.Queued||it.state==DesktopDownloads.State.Downloading}
    var shuffle by remember {mutableStateOf(false)}
    fun removeList(list:LocalPlaylist) {val key="list:${list.id}";if(key in hidden)return;hidden.add(key);workScope.launch{val result=withTimeoutOrNull(6000){snackbar.showSnackbar("Descarga de «${list.name}» quitada","Deshacer")};if(result!=SnackbarResult.ActionPerformed)prefs.setDownloadList(list.id,false);hidden.remove(key)}}
    fun removeSong(song:Heard) {val key="song:${song.id}";if(key in hidden)return;hidden.add(key);workScope.launch{val result=withTimeoutOrNull(6000){snackbar.showSnackbar("Descarga de «${song.title}» quitada","Deshacer")};if(result!=SnackbarResult.ActionPerformed)prefs.setDownloadSong(song,false);hidden.remove(key)}}
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            item(key="head") {
                Column(verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxWidth().padding(bottom=12.dp)) {
                    if(lists.isNotEmpty()||songs.isNotEmpty())Text(buildString {append("Suenan sin conexión. ${all.size} canciones");if(totalBytes>0)append(" · ${formatBytes(totalBytes)}");if(pending>0)append(" · bajando $pending")},style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
                    if(all.isNotEmpty())Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={player.setShuffle(shuffle);player.playLocal(LocalPlaylist(DOWNLOADS_ID,"Tus descargas",all),null,false)},colors=ButtonDefaults.buttonColors(containerColor=palette.primary,contentColor=Color(0xFF101010))){Text("Reproducir todo",fontWeight=FontWeight.SemiBold)}
                        HoverAction(MobileIcons.shuffle,if(shuffle)"Desactivar aleatorio" else "Activar aleatorio",onClick={shuffle=!shuffle;if(player.source.value?.local==DOWNLOADS_ID)player.setShuffle(shuffle)},tint=if(shuffle)palette.primary else Color.White.copy(alpha=.55f))
                    }
                }
            }
            if(lists.isEmpty()&&songs.isEmpty())item(key="empty") {
                Column(Modifier.fillMaxWidth().padding(vertical=60.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(MobileIcons.downloadOffline,null,Modifier.size(42.dp),tint=Color.White.copy(alpha=.25f));Text("Nada descargado",style=OtoType.section)
                    Text("En una lista, abre sus opciones y elige «Descargar». También puedes descargar canciones sueltas desde sus opciones.",Modifier.widthIn(max=430.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
                }
            }
            if(lists.isNotEmpty())item(key="listsTitle"){Text("LISTAS",Modifier.padding(top=12.dp,bottom=6.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))}
            items(lists,key={"l:${it.id}"}) {list ->
                val got=list.songs.count{entries[it.id]?.state==DesktopDownloads.State.Done}
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable{onOpen(list.id)}.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    PlaylistMosaic(list,palette.primary,Modifier.size(52.dp))
                    Column(Modifier.weight(1f)){Text(list.name,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(got==list.songs.size)"Descargada · $got canciones" else "Bajando · $got de ${list.songs.size}",style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1)}
                    HoverAction(MobileIcons.close,"Quitar descarga de ${list.name}",onClick={removeList(list)})
                }
            }
            if(songs.isNotEmpty())item(key="songsTitle"){Text("CANCIONES",Modifier.padding(top=12.dp,bottom=6.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))}
            items(songs,key={"s:${it.id}"}) {song ->
                val entry=entries[song.id]
                PlaylistContextMenu(listOf(ContextMenuItem("Guardar en…"){onSave(song)},ContextMenuItem("Reproducir a continuación"){player.playNext(song.toSong())})) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable{player.play(song.toSong())}.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        LibraryArt(song.artwork,Modifier.size(48.dp));Column(Modifier.weight(1f)){Text(song.title,style=OtoType.rowTitle,maxLines=1,overflow=TextOverflow.Ellipsis);Text(song.artist,style=OtoType.rowSub,color=Color.White.copy(alpha=.45f),maxLines=1,overflow=TextOverflow.Ellipsis)}
                        if(entry?.state==DesktopDownloads.State.Done)Icon(MobileIcons.downloadDone,"Descargada",Modifier.size(16.dp),tint=palette.primary)
                        if(entry?.state==DesktopDownloads.State.Failed)TextButton(onClick={downloads.retry(song.id)}) { Text("Reintentar") }
                        if(song.id==currentId)Icon(MobileIcons.graphicEq,"Sonando",Modifier.size(17.dp),tint=palette.primary)
                        HoverAction(MobileIcons.close,"Quitar descarga de ${song.title}",onClick={removeSong(song)})
                    }
                }
                if(entry?.state==DesktopDownloads.State.Failed)Text(entry.error ?: "No se pudo descargar",style=OtoType.rowSub,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=8.dp))
            }
            if(lists.isNotEmpty()||songs.isNotEmpty())item(key="clear") {Box(Modifier.fillMaxWidth().padding(top=20.dp),contentAlignment=Alignment.Center){TextButton(onClick={if(confirm){confirm=false;scope.launch{downloads.clear()}}else confirm=true}){Text(if(confirm)"Toca otra vez para borrarlo todo" else "Borrar todas las descargas")}}}
        }
        OtoSnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

private fun formatBytes(bytes:Long):String = when {
    bytes>=1_073_741_824L -> "%.1f GB".format(bytes/1_073_741_824.0)
    bytes>=1_048_576L -> "%.1f MB".format(bytes/1_048_576.0)
    else -> "${(bytes/1024).coerceAtLeast(1)} KB"
}
