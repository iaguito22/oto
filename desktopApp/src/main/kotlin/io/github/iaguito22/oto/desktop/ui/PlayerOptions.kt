package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
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
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable internal fun PlayerOptions(open:Boolean,dismiss:()->Unit,form:PlayerForm,choose:(PlayerForm)->Unit,volume:Float,setVolume:(Float)->Unit,devices:()->Unit,arrange:()->Unit,quit:()->Unit) {
    if(!open)return
    Popup(alignment=Alignment.TopEnd,offset=IntOffset(0,36),onDismissRequest=dismiss,properties=PopupProperties(focusable=true)) {
        Surface(Modifier.width(270.dp).border(1.dp,Color.White.copy(alpha=.09f),RoundedCornerShape(20.dp)),shape=RoundedCornerShape(20.dp),color=Color(0xFF202520),shadowElevation=18.dp) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("Vista del reproductor",style=OtoType.rowSub,color=Color.White.copy(alpha=.45f))
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
                OptionLine(Icons.Rounded.Devices,"Dispositivos y atajo",devices)
                OptionLine(MobileIcons.libraryMusic,"Organizar ventanas",arrange)
                OptionLine(MobileIcons.close,"Salir de Oto",quit)
            }
        }
    }
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
