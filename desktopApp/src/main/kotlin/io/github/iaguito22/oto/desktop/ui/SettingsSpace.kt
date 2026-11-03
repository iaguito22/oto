package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.account.Session
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
import io.github.iaguito22.oto.desktop.supportsDesktopShortcut
import io.github.iaguito22.oto.desktop.isWindowsDesktop
import io.github.iaguito22.oto.importer.ImportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsSpace(prefs:Prefs,player:DesktopPlayerConnection,palette:ArtworkPalette,session:Session?,importState:ImportState,artworkBackground:Boolean,onArtworkBackground:(Boolean)->Unit,backdropOpacity:Float,onBackdropOpacity:(Float)->Unit,shortcut:String,shortcutIssue:String?,onShortcut:(String)->Unit,onAccount:()->Unit,onImport:()->Unit,onDevices:()->Unit) {
    val scope=rememberCoroutineScope()
    val quality by prefs.quality.collectAsState(initial=QualityMode.Auto)
    val saver by prefs.dataSaver.collectAsState(initial=false)
    val ramSaver by prefs.ramSaver.collectAsState(initial=false)
    val shuffle by player.shuffle.collectAsState()
    val loop by player.loop.collectAsState()
    val beat by prefs.coverBeat.collectAsState(initial=true)
    val remaining by prefs.showRemaining.collectAsState(initial=false)
    val romanize by prefs.romanize.collectAsState(initial=true)
    val crossfade by prefs.crossfade.collectAsState(initial=DEFAULT_CROSSFADE)
    var confirm by remember {mutableStateOf(false)}
    var cacheRevision by remember {mutableIntStateOf(0)}
    val bytes by produceState<Long?>(null,cacheRevision) {value=withContext(Dispatchers.IO){OtoDirs.cacheDir.walkTopDown().filter{it.isFile}.sumOf{it.length()}}}
    LaunchedEffect(confirm){if(confirm){delay(3000);confirm=false}}
    MaterialTheme(colorScheme=MaterialTheme.colorScheme.copy(primary=palette.primary,onPrimary=Color(0xFF101010),primaryContainer=palette.primary.copy(alpha=.22f),onPrimaryContainer=palette.primary,secondary=palette.primary,secondaryContainer=palette.primary.copy(alpha=.22f),onSecondaryContainer=palette.primary)) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        item {SettingsLink(MobileIcons.accountCircle,"Cuenta de YouTube Music",session?.name?.ifBlank { session.email } ?: "Sin cuenta: recomendaciones de visitante",onAccount)}
        item {SettingsLink(MobileIcons.playlistAdd,"Importar de Spotify",when(importState){is ImportState.Running->"Importando ${importState.done} de ${importState.total}";is ImportState.Done->"Importación terminada";else->"Tus listas y tus Me gusta"},onImport)}
        item {SettingsLink(Icons.Rounded.Devices,"Dispositivos","Conecta con Oto móvil",onDevices)}
        if(supportsDesktopShortcut) item { ShortcutSetting(shortcut,shortcutIssue,onShortcut) }
        item {SettingsToggle(MobileIcons.libraryMusic,"Fondo con la carátula","GPU: dibuja y anima el fondo. CPU y RAM: prepara la imagen al cambiar de canción.",artworkBackground){onArtworkBackground(!artworkBackground)}}
        if(artworkBackground) item {SettingFrame(MobileIcons.libraryMusic) { Text("Intensidad del fondo · ${(backdropOpacity*100).toInt()} %",style=OtoType.rowTitle); Slider(backdropOpacity,onBackdropOpacity) }}
        item {
            SettingFrame(MobileIcons.highQuality) {
                Text("Calidad del audio",style=OtoType.rowTitle)
                Text(if(quality==QualityMode.Auto)"La calidad se elige al empezar cada canción." else "Usa la mejor calidad disponible.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
                Row(Modifier.padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    QualityMode.entries.forEach { q ->
                        val selected=quality==q
                        val interaction=remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        Box(Modifier.clip(CircleShape).background(if(selected)palette.primary.copy(alpha=if(hovered).30f else .20f) else Color.White.copy(alpha=if(hovered).10f else .045f)).border(1.dp,if(selected)palette.primary.copy(alpha=.45f) else Color.White.copy(alpha=if(hovered).22f else .09f),CircleShape).hoverable(interaction).clickable(interactionSource=interaction,indication=null) {scope.launch{prefs.setQuality(q)}}.padding(horizontal=20.dp,vertical=9.dp),contentAlignment=Alignment.Center) {
                            Text(if(q==QualityMode.Auto)"Auto" else "Máxima",style=OtoType.rowSub,color=if(selected)palette.primary else Color.White.copy(alpha=if(hovered).9f else .65f))
                        }
                    }
                }
            }
        }
        item {SettingsToggle(MobileIcons.dataSaver,"Ahorro de datos","Pide menor calidad de audio.",saver){scope.launch{prefs.setDataSaver(!saver)}}}
        item {SettingsToggle(MobileIcons.hardDrive,"Ahorro de RAM","Limita las carátulas en caché a 12 y desactiva la precarga de portadas y el fundido. Puede tardar más al cambiar de canción.",ramSaver){scope.launch{prefs.setRamSaver(!ramSaver)}}}
        item {SettingFrame(MobileIcons.graphicEq){Text("Consumo de recursos",style=OtoType.rowTitle);Text("La letra animada usa CPU y GPU mientras se reproduce. Más ventanas visibles aumentan el trabajo gráfico. Estas indicaciones son orientativas; el consumo depende del equipo y del contenido.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))}}
        item {SettingsToggle(MobileIcons.shuffle,"Aleatorio","Mezcla las canciones de la cola.",shuffle){player.setShuffle(!shuffle)}}
        item {SettingsToggle(MobileIcons.repeat,"Repetir la lista","Al terminar vuelve al principio.",loop){player.setLoop(!loop)}}
        item {SettingsToggle(MobileIcons.graphicEq,"Carátula al ritmo","CPU y GPU: anima la portada continuamente durante la reproducción.",beat){scope.launch{prefs.setCoverBeat(!beat)}}}
        item {SettingsToggle(MobileIcons.timer,"Tiempo que falta","Muestra el tiempo restante en el reproductor.",remaining){scope.launch{prefs.setShowRemaining(!remaining)}}}
        item {SettingsToggle(MobileIcons.translate,"Romanización","Añade la pronunciación en letras japonesas, coreanas o chinas.",romanize){scope.launch{prefs.setRomanize(!romanize)}}}
        item {SettingFrame(MobileIcons.transitionFade){Text("Fundido entre canciones",style=OtoType.rowTitle);Text(if(ramSaver) "Desactivado mientras esté activo el ahorro de RAM. RAM y CPU: el fundido prepara y reproduce dos canciones a la vez." else if(isWindowsDesktop) "RAM y CPU: prepara y reproduce dos canciones a la vez durante el fundido." else "Disponible en Windows.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f)); if(isWindowsDesktop && !ramSaver) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { CROSSFADE_CHOICES.forEach { seconds -> FilterChip(selected=crossfade==seconds,onClick={scope.launch {prefs.setCrossfade(seconds)}},label={Text(if(seconds==0)"No" else "${seconds}s")},shape=CircleShape) } }}}
        item {
            SettingFrame(MobileIcons.hardDrive) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)){Text("Espacio en el ordenador",style=OtoType.rowTitle);Text(bytes?.let{if(it>=1_048_576)"${it/1_048_576} MB de caché" else "${it/1024} KB de caché"}?:"Midiendo…",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))}
                    TextButton(enabled=(bytes?:0)>0,onClick={if(confirm){confirm=false;scope.launch(Dispatchers.IO){OtoDirs.cacheDir.listFiles()?.forEach{it.deleteRecursively()};withContext(Dispatchers.Main){cacheRevision++}}}else confirm=true}){Text(if(confirm)"Toca otra vez" else "Vaciar")}
                }
                Text("Las descargas se conservan.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
            }
        }
        item {SettingFrame(MobileIcons.settings){Text("Atajos de teclado",style=OtoType.rowTitle);Text("Espacio: reproducir o pausar · Ctrl + ← / →: canción anterior o siguiente · Ctrl + ↑ / ↓: volumen · Ctrl + Mayús + M: cambiar reproductor · Ctrl + B: guardar",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))}}
        item {Text("Oto · GPL-3.0\nCon InnerTubeX y partes de Metrolist.",Modifier.padding(16.dp,24.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.3f))}
    }
    }
}

@Composable private fun ShortcutSetting(shortcut:String,issue:String?,onShortcut:(String)->Unit) {
    var recording by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val focus=remember { FocusRequester() }
    LaunchedEffect(recording) { if(recording) focus.requestFocus() }
    SettingFrame(MobileIcons.settings) {
        Text("Atajo global",style=OtoType.rowTitle)
        Text("Muestra u oculta todas las ventanas de Oto.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
        Box(Modifier.padding(top=6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha=if(recording).12f else .06f))
            .focusRequester(focus).onPreviewKeyEvent { e ->
                if(!recording) false else {
                    if(e.type==KeyEventType.KeyDown) {
                        if(e.key==Key.Escape) { recording=false;hint=null }
                        else {
                            val code=(e.nativeKeyEvent as? java.awt.event.KeyEvent)?.keyCode ?: e.key.keyCode.toInt()
                            val modifiers=buildList { if(e.isCtrlPressed)add("CTRL");if(e.isAltPressed)add("ALT");if(e.isShiftPressed)add("SHIFT");if(e.isMetaPressed)add("SUPER") }
                            if(modifiers.isNotEmpty() && (code in 65..90 || code in 48..57)) {
                                onShortcut((modifiers+code.toChar().toString()).joinToString(" + "));recording=false;hint=null
                            } else if(code in 65..90 || code in 48..57) hint="Añade Ctrl, Alt, Mayús o la tecla Windows."
                        }
                    }
                    true
                }
            }.focusable().clickable { recording=true;hint=null }.padding(horizontal=18.dp,vertical=10.dp)) {
            Text(if(recording) "Pulsa la combinación… (Esc para cancelar)" else "$shortcut  ·  Cambiar",style=OtoType.rowSub,color=Color.White)
        }
        (hint ?: issue)?.let { Text(it,style=OtoType.rowSub,color=MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun SettingFrame(icon:ImageVector,content:@Composable ColumnScope.()->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
        Icon(icon,null,Modifier.size(22.dp).padding(top=2.dp),tint=Color.White.copy(alpha=.65f));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp),content=content)
    }
}
@Composable private fun SettingsLink(icon:ImageVector,title:String,subtitle:String,onClick:()->Unit) {
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight by animateFloatAsState(if(hovered).055f else 0f,tween(180),label="settingsHover")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha=highlight)).hoverable(interaction).clickable(interactionSource=interaction,indication=null,onClick=onClick).padding(horizontal=12.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Icon(icon,null,Modifier.size(22.dp),tint=Color.White.copy(alpha=.65f));Column(Modifier.weight(1f)){Text(title,style=OtoType.rowTitle);Text(subtitle,style=OtoType.rowSub,color=Color.White.copy(alpha=.5f),maxLines=2,overflow=TextOverflow.Ellipsis)};Icon(MobileIcons.chevronRight,null,Modifier.size(18.dp),tint=Color.White.copy(alpha=.4f))
    }
}
@Composable private fun SettingsToggle(icon:ImageVector,title:String,subtitle:String,on:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick=onClick).padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Icon(icon,null,Modifier.size(22.dp),tint=Color.White.copy(alpha=.65f));Column(Modifier.weight(1f)){Text(title,style=OtoType.rowTitle);Text(subtitle,style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))};Switch(on,onCheckedChange={onClick()})
    }
}
