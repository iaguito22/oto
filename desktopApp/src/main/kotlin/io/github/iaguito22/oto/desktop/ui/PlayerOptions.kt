package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.WindowPosition
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.Rectangle
import java.awt.Point

internal fun optionsPosition(parent: Rectangle?, area: Rectangle, width:Int=270, height:Int=380): Point {
    val anchor=parent ?: Rectangle(area.x+area.width/2,area.y+area.height/2,0,0)
    val x=(anchor.x+(anchor.width-width)/2).coerceIn(area.x,(area.x+area.width-width).coerceAtLeast(area.x))
    val above=anchor.y-height-8
    val below=anchor.y+anchor.height+8
    val y=when { above>=area.y -> above; below+height<=area.y+area.height -> below; else -> above.coerceIn(area.y,(area.y+area.height-height).coerceAtLeast(area.y)) }
    return Point(x,y)
}

@Composable internal fun PlayerOptions(open:Boolean,dismiss:()->Unit,form:PlayerForm,choose:(PlayerForm)->Unit,volume:Float,setVolume:(Float)->Unit,devices:()->Unit,arrange:()->Unit,quit:()->Unit,parentBounds:()->Rectangle?={null},welcome:()->Unit={}) {
    if(!open)return
    @Composable fun OptionsContent() {
        Surface(Modifier.width(270.dp).border(1.dp,Color.White.copy(alpha=.09f),RoundedCornerShape(20.dp)),shape=RoundedCornerShape(20.dp),color=Color(0xFF202520),shadowElevation=18.dp) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("Vista del reproductor",Modifier.weight(1f),style=OtoType.rowSub,color=Color.White.copy(alpha=.45f))
                    if(form==PlayerForm.Horizontal) HoverAction(MobileIcons.close,"Cerrar opciones",onClick=dismiss,size=28.dp)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    PlayerForm.entries.forEach {f->
                        val interaction=remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        val scale by animateFloatAsState(if(hovered)1.1f else 1f,tween(160),label="formHover")
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if(f==form)Color.White.copy(alpha=.12f)else Color.White.copy(alpha=.035f)).hoverable(interaction).clickable(interactionSource=interaction,indication=null){choose(f);dismiss()}.padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(9.dp)) {
                            Box(Modifier.size(38.dp,28.dp),contentAlignment=Alignment.Center){Box(Modifier.size((when(f){PlayerForm.Full->20.dp;PlayerForm.Horizontal->32.dp;PlayerForm.Vertical->12.dp})*scale,(when(f){PlayerForm.Full->26.dp;PlayerForm.Horizontal->12.dp;PlayerForm.Vertical->28.dp})*scale).border(1.dp,Color.White.copy(alpha=if(f==form).9f else .35f),RoundedCornerShape(4.dp)))}
                            Text(f.label,style=OtoType.rowSub.copy(fontSize=11.sp*scale),color=Color.White.copy(alpha=if(f==form).95f else .5f),maxLines=1)
                        }
                    }
                }
                Column {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Volumen",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f));Text("${(volume*100).toInt()} %",style=OtoType.rowSub,color=Color.White.copy(alpha=.4f))}
                    FineSlider(volume,setVolume,label="Volumen")
                }
                HorizontalDivider(color=Color.White.copy(alpha=.07f))
                OptionLine(Icons.Rounded.Devices,"Dispositivos",devices)
                OptionLine(MobileIcons.libraryMusic,"Organizar ventanas",arrange)
                OptionLine(Icons.AutoMirrored.Rounded.HelpOutline,"Ver bienvenida",welcome)
                OptionLine(MobileIcons.close,"Salir de Oto",quit)
            }
        }
    }
    if(form==PlayerForm.Horizontal) {
        val position = remember {
            val parent=parentBounds()
            val point = parent?.let { Point(it.x+it.width/2,it.y+it.height/2) } ?: MouseInfo.getPointerInfo()?.location
            val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            val config = screens.map { it.defaultConfiguration }.firstOrNull { point != null && it.bounds.contains(point) }
                ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
            val bounds = config.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            val area=Rectangle(bounds.x+insets.left,bounds.y+insets.top,bounds.width-insets.left-insets.right,bounds.height-insets.top-insets.bottom)
            val placed=optionsPosition(parent,area,height=430)
            WindowPosition(placed.x.dp,placed.y.dp)
        }
        DialogWindow(onCloseRequest=dismiss,title="Opciones del reproductor",state=rememberDialogState(position=position,width=270.dp,height=430.dp),undecorated=true,transparent=true) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            OptionsContent()
            var previous by remember { mutableStateOf<java.awt.Point?>(null) }
            Box(Modifier.align(Alignment.TopStart).padding(start=12.dp,end=54.dp).fillMaxWidth().height(48.dp).pointerInput(Unit) {
                detectDragGestures(onDragStart={ previous=MouseInfo.getPointerInfo().location },onDragEnd={previous=null},onDragCancel={previous=null}) { change,_ ->
                    change.consume()
                    val current=MouseInfo.getPointerInfo().location
                    previous?.let { window.setLocation(window.x+current.x-it.x,window.y+current.y-it.y) }
                    previous=current
                }
            })
        }
        }
    }
    else Popup(alignment=Alignment.TopEnd,offset=IntOffset(0,36),onDismissRequest=dismiss,properties=PopupProperties(focusable=true)) { OptionsContent() }
}
@Composable private fun OptionLine(icon:ImageVector,label:String,click:()->Unit) {
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if(hovered)1.1f else 1f,tween(160),label="optionHover")
    Row(Modifier.fillMaxWidth().hoverable(interaction).clickable(interactionSource=interaction,indication=null,onClick=click).padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(19.dp),contentAlignment=Alignment.Center) { Icon(icon,null,Modifier.size(17.dp*scale),tint=Color.White.copy(alpha=.4f)) }
        Text(label,style=OtoType.rowSub.copy(fontSize=OtoType.rowSub.fontSize*scale),color=Color.White.copy(alpha=.8f),maxLines=1)
    }
}
