package io.github.iaguito22.oto.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.iaguito22.oto.desktop.ui.FineSlider
import io.github.iaguito22.oto.desktop.ui.LibraryArt
import io.github.iaguito22.oto.desktop.ui.MobileIcons
import io.github.iaguito22.oto.desktop.ui.OtoType
import java.awt.EventQueue
import java.awt.MouseInfo
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.imageio.ImageIO

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun OtoTray(toggle: () -> Unit, player: () -> Unit, library: () -> Unit, lyrics: () -> Unit, queue: () -> Unit, quit: () -> Unit,
    playing: Boolean, hasSong: Boolean, previous: () -> Unit, playPause: () -> Unit, next: () -> Unit,
    volume: Float, setVolume: (Float) -> Unit, title: String = "", artist: String = "", artwork: String? = null) {
    var menuOpen by remember { mutableStateOf(false) }
    // Con canción, el menú lleva arriba su tarjeta: la altura debe saberse al colocarlo.
    val menuHeight = if (hasSong && title.isNotBlank()) 385 else 329
    val currentHeight by rememberUpdatedState(menuHeight)
    var trayIcon by remember { mutableStateOf<TrayIcon?>(null) }
    LaunchedEffect(trayIcon, hasSong, title, artist) {
        // Windows corta el tooltip de la bandeja a 127 caracteres.
        trayIcon?.toolTip = (if (hasSong && title.isNotBlank()) "Oto · $title" + (if (artist.isNotBlank()) " — $artist" else "") else "Oto").take(127)
    }
    var position by remember { mutableStateOf(WindowPosition(0.dp,0.dp)) }
    val currentToggle by rememberUpdatedState(toggle)
    DisposableEffect(Unit) {
        val tray = if (SystemTray.isSupported()) SystemTray.getSystemTray() else null
        val icon = runCatching {
            // Ya al tamaño exacto de la bandeja: AWT reduce sin suavizado y el icono salía dentado.
            val image = AppIcon.tray() ?: requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("oto-icon.png")).use(ImageIO::read)
            TrayIcon(image,"Oto").apply {
                isImageAutoSize = true
                addMouseListener(object : MouseAdapter() {
                    override fun mouseReleased(e: MouseEvent) {
                        if (e.button == MouseEvent.BUTTON3 || e.isPopupTrigger) {
                            val point = MouseInfo.getPointerInfo().location
                            val area = desktopArea(point)
                            val x = (point.x-210).coerceIn(area.x,(area.x+area.width-220).coerceAtLeast(area.x))
                            val h = currentHeight
                            val y = (point.y-h).coerceIn(area.y,(area.y+area.height-h).coerceAtLeast(area.y))
                            EventQueue.invokeLater { position=WindowPosition(x.dp,y.dp);menuOpen=true }
                        } else if (e.button == MouseEvent.BUTTON1) EventQueue.invokeLater { currentToggle() }
                    }
                })
                tray?.add(this)
            }
        }.getOrNull()
        trayIcon = icon
        onDispose { trayIcon = null; if(icon != null) tray?.remove(icon) }
    }
    if (menuOpen) {
        val state = rememberWindowState(position=position,width=220.dp,height=menuHeight.dp)
        LaunchedEffect(position) { state.position=position }
        LaunchedEffect(menuHeight) { state.size=androidx.compose.ui.unit.DpSize(220.dp,menuHeight.dp) }
        Window(onCloseRequest={menuOpen=false},state=state,title="Oto · Menú",undecorated=true,transparent=true,alwaysOnTop=true,
            onKeyEvent={ if(it.type==KeyEventType.KeyDown && it.key==Key.Escape) {menuOpen=false;true} else false }) {
            DisposableEffect(window) {
                val listener=object : WindowAdapter() { override fun windowLostFocus(e: WindowEvent) { menuOpen=false } }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            Surface(Modifier.fillMaxSize().border(1.dp,Color.White.copy(alpha=.10f),RoundedCornerShape(16.dp)),shape=RoundedCornerShape(16.dp),color=Color(0xFF202520)) {
                Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(1.dp)) {
                    Text("Oto",Modifier.padding(start=10.dp,top=4.dp,bottom=5.dp),style=OtoType.rowTitle,color=Color.White)
                    if (hasSong && title.isNotBlank()) {
                        // Lo que suena, como en el reproductor: un clic lo abre.
                        val interaction=remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        Row(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha=if(hovered) .09f else .05f)).hoverable(interaction)
                            .clickable(interactionSource=interaction,indication=null,onClickLabel="Abrir reproductor") { menuOpen=false; player() }
                            .padding(horizontal=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            LibraryArt(artwork,Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(title,style=OtoType.rowTitle,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis)
                                if (artist.isNotBlank()) Text(artist,style=OtoType.rowSub,color=Color.White.copy(alpha=.55f),maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(38.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                        @Composable fun PlaybackButton(icon: ImageVector, label: String, action: () -> Unit) {
                            val interaction=remember { MutableInteractionSource() }
                            val hovered by interaction.collectIsHoveredAsState()
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(if(hovered && hasSong) Color.White.copy(alpha=.1f) else Color.Transparent)
                                .hoverable(interaction).clickable(interactionSource=interaction,indication=null,enabled=hasSong,onClickLabel=label,onClick=action),contentAlignment=Alignment.Center) {
                                Icon(icon,label,Modifier.size(18.dp),tint=Color.White.copy(alpha=if(hasSong).9f else .3f))
                            }
                        }
                        PlaybackButton(MobileIcons.skipPrevious,"Anterior",previous)
                        PlaybackButton(if(playing) MobileIcons.pause else MobileIcons.play,if(playing) "Pausar" else "Reproducir",playPause)
                        PlaybackButton(MobileIcons.skipNext,"Siguiente",next)
                    }
                    Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal=10.dp)
                        .onPointerEvent(PointerEventType.Scroll) { e -> e.changes.firstOrNull()?.scrollDelta?.y?.let { if(it != 0f) setVolume(volume - it*.04f) } },
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Icon(when { volume <= 0f -> Icons.AutoMirrored.Rounded.VolumeOff; volume < .5f -> Icons.AutoMirrored.Rounded.VolumeDown; else -> Icons.AutoMirrored.Rounded.VolumeUp },
                            "Volumen",Modifier.size(16.dp).clickable(interactionSource=null,indication=null) { setVolume(if(volume > 0f) 0f else .5f) },tint=Color.White.copy(alpha=.65f))
                        FineSlider(volume,setVolume,Modifier.weight(1f),label="Volumen")
                        Text("${(volume*100).toInt()}",Modifier.width(24.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.5f),textAlign=TextAlign.End)
                    }
                    @Composable fun Entry(icon:ImageVector,label:String,action:()->Unit) {
                        val interaction=remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        Row(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(if(hovered) Color.White.copy(alpha=.08f) else Color.Transparent).hoverable(interaction).clickable(interactionSource=interaction,indication=null) { menuOpen=false;action() }.padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Icon(icon,null,Modifier.size(16.dp),tint=Color.White.copy(alpha=.65f))
                            Text(label,style=OtoType.rowSub,color=Color.White.copy(alpha=.9f))
                        }
                    }
                    Entry(MobileIcons.play,"Reproductor",player)
                    Entry(MobileIcons.libraryMusic,"Tu música",library)
                    Entry(MobileIcons.lyrics,"Letra",lyrics)
                    Entry(MobileIcons.queueMusic,"Cola",queue)
                    HorizontalDivider(Modifier.padding(vertical=4.dp),color=Color.White.copy(alpha=.08f))
                    Entry(MobileIcons.close,"Salir de Oto",quit)
                }
            }
        }
    }
}
