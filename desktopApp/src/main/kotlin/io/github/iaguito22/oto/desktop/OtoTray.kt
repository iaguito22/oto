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

@Composable
internal fun OtoTray(toggle: () -> Unit, player: () -> Unit, library: () -> Unit, lyrics: () -> Unit, queue: () -> Unit, quit: () -> Unit,
    playing: Boolean, hasSong: Boolean, previous: () -> Unit, playPause: () -> Unit, next: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(WindowPosition(0.dp,0.dp)) }
    val currentToggle by rememberUpdatedState(toggle)
    DisposableEffect(Unit) {
        val tray = if (SystemTray.isSupported()) SystemTray.getSystemTray() else null
        val icon = runCatching {
            val image = requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("oto-icon.png")).use(ImageIO::read)
            TrayIcon(image,"Oto").apply {
                isImageAutoSize = true
                addMouseListener(object : MouseAdapter() {
                    override fun mouseReleased(e: MouseEvent) {
                        if (e.button == MouseEvent.BUTTON3 || e.isPopupTrigger) {
                            val point = MouseInfo.getPointerInfo().location
                            val area = desktopArea(point)
                            val x = (point.x-210).coerceIn(area.x,(area.x+area.width-220).coerceAtLeast(area.x))
                            val y = (point.y-293).coerceIn(area.y,(area.y+area.height-293).coerceAtLeast(area.y))
                            EventQueue.invokeLater { position=WindowPosition(x.dp,y.dp);menuOpen=true }
                        } else if (e.button == MouseEvent.BUTTON1) EventQueue.invokeLater { currentToggle() }
                    }
                })
                tray?.add(this)
            }
        }.getOrNull()
        onDispose { if(icon != null) tray?.remove(icon) }
    }
    if (menuOpen) {
        val state = rememberWindowState(position=position,width=220.dp,height=293.dp)
        LaunchedEffect(position) { state.position=position }
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
