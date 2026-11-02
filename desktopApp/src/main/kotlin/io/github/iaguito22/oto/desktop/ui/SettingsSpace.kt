package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.account.Session
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
import io.github.iaguito22.oto.importer.ImportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsSpace(prefs:Prefs,player:DesktopPlayerConnection,palette:ArtworkPalette,session:Session?,importState:ImportState,artworkBackground:Boolean,onArtworkBackground:(Boolean)->Unit,onAccount:()->Unit,onImport:()->Unit,onDevices:()->Unit) {
    val scope=rememberCoroutineScope()
    val quality by prefs.quality.collectAsState(initial=QualityMode.Auto)
    val saver by prefs.dataSaver.collectAsState(initial=false)
    val shuffle by player.shuffle.collectAsState()
    val loop by player.loop.collectAsState()
    val beat by prefs.coverBeat.collectAsState(initial=true)
    val remaining by prefs.showRemaining.collectAsState(initial=false)
    val romanize by prefs.romanize.collectAsState(initial=true)
    var confirm by remember {mutableStateOf(false)}
    var cacheRevision by remember {mutableIntStateOf(0)}
    val bytes by produceState<Long?>(null,cacheRevision) {value=withContext(Dispatchers.IO){OtoDirs.cacheDir.walkTopDown().filter{it.isFile}.sumOf{it.length()}}}
    LaunchedEffect(confirm){if(confirm){delay(3000);confirm=false}}
    MaterialTheme(colorScheme=MaterialTheme.colorScheme.copy(primary=palette.primary,onPrimary=Color(0xFF101010),primaryContainer=palette.primary.copy(alpha=.22f),onPrimaryContainer=palette.primary,secondary=palette.primary,secondaryContainer=palette.primary.copy(alpha=.22f),onSecondaryContainer=palette.primary)) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        item {SettingsLink(MobileIcons.accountCircle,"Cuenta de YouTube Music",session?.name?.ifBlank { session.email } ?: "Sin cuenta: recomendaciones de visitante",onAccount)}
        item {SettingsLink(MobileIcons.playlistAdd,"Importar de Spotify",when(importState){is ImportState.Running->"Importando ${importState.done} de ${importState.total}";is ImportState.Done->"Importación terminada";else->"Tus listas y tus Me gusta"},onImport)}
        item {SettingsLink(Icons.Rounded.Devices,"Dispositivos y atajos","Conecta con Oto móvil y configura el atajo global",onDevices)}
        item {
            SettingFrame(MobileIcons.highQuality) {
                Text("Calidad del audio",style=OtoType.rowTitle)
                Text(if(quality==QualityMode.Auto)"La calidad se elige al empezar cada canción." else "Usa la mejor calidad disponible.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
                Row(Modifier.padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {QualityMode.entries.forEach{q->FilterChip(selected=quality==q,onClick={scope.launch{prefs.setQuality(q)}},label={Text(if(q==QualityMode.Auto)"Auto" else "Máxima")})}}
            }
        }
        item {SettingsToggle(MobileIcons.dataSaver,"Ahorro de datos","Pide menor calidad de audio.",saver){scope.launch{prefs.setDataSaver(!saver)}}}
        item {SettingsToggle(MobileIcons.shuffle,"Aleatorio","Mezcla las canciones de la cola.",shuffle){player.setShuffle(!shuffle)}}
        item {SettingsToggle(MobileIcons.repeat,"Repetir la lista","Al terminar vuelve al principio.",loop){player.setLoop(!loop)}}
        item {SettingsToggle(MobileIcons.graphicEq,"Carátula al ritmo","La carátula late con los graves de la canción.",beat){scope.launch{prefs.setCoverBeat(!beat)}}}
        item {SettingsToggle(MobileIcons.libraryMusic,"Fondo con la carátula","Usa la portada desenfocada como fondo del reproductor.",artworkBackground){onArtworkBackground(!artworkBackground)}}
        item {SettingsToggle(MobileIcons.timer,"Tiempo que falta","Muestra el tiempo restante en el reproductor.",remaining){scope.launch{prefs.setShowRemaining(!remaining)}}}
        item {SettingsToggle(MobileIcons.translate,"Romanización","Añade la pronunciación en letras japonesas, coreanas o chinas.",romanize){scope.launch{prefs.setRomanize(!romanize)}}}
        item {SettingFrame(MobileIcons.transitionFade){Text("Fundido entre canciones",style=OtoType.rowTitle);Text("Todavía no disponible en escritorio.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))}}
        item {
            SettingFrame(MobileIcons.hardDrive) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)){Text("Espacio en el ordenador",style=OtoType.rowTitle);Text(bytes?.let{if(it>=1_048_576)"${it/1_048_576} MB de caché" else "${it/1024} KB de caché"}?:"Midiendo…",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))}
                    TextButton(enabled=(bytes?:0)>0,onClick={if(confirm){confirm=false;scope.launch(Dispatchers.IO){OtoDirs.cacheDir.listFiles()?.forEach{it.deleteRecursively()};withContext(Dispatchers.Main){cacheRevision++}}}else confirm=true}){Text(if(confirm)"Toca otra vez" else "Vaciar")}
                }
                Text("Las descargas se conservan.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
            }
        }
        item {SettingFrame(MobileIcons.settings){Text("Atajos de teclado",style=OtoType.rowTitle);Text("Espacio: reproducir o pausar · Ctrl + ← / →: canción anterior o siguiente · Ctrl + ↑ / ↓: volumen · Ctrl + Mayús + M: cambiar reproductor · Ctrl + B: guardar",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f));TextButton(onClick=onDevices){Text("Configurar atajo global")}}}
        item {Text("Oto · GPL-3.0\nCon InnerTubeX y partes de Metrolist.",Modifier.padding(16.dp,24.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.3f))}
    }
    }
}

@Composable private fun SettingFrame(icon:ImageVector,content:@Composable ColumnScope.()->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
        Icon(icon,null,Modifier.size(22.dp).padding(top=2.dp),tint=Color.White.copy(alpha=.65f));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp),content=content)
    }
}
@Composable private fun SettingsLink(icon:ImageVector,title:String,subtitle:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick=onClick).padding(horizontal=12.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Icon(icon,null,Modifier.size(22.dp),tint=Color.White.copy(alpha=.65f));Column(Modifier.weight(1f)){Text(title,style=OtoType.rowTitle);Text(subtitle,style=OtoType.rowSub,color=Color.White.copy(alpha=.5f),maxLines=2,overflow=TextOverflow.Ellipsis)};Icon(MobileIcons.chevronRight,null,Modifier.size(18.dp),tint=Color.White.copy(alpha=.4f))
    }
}
@Composable private fun SettingsToggle(icon:ImageVector,title:String,subtitle:String,on:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick=onClick).padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Icon(icon,null,Modifier.size(22.dp),tint=Color.White.copy(alpha=.65f));Column(Modifier.weight(1f)){Text(title,style=OtoType.rowTitle);Text(subtitle,style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))};Switch(on,onCheckedChange={onClick()})
    }
}
