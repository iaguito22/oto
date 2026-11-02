package io.github.iaguito22.oto.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun OtoSnackbarHost(state:SnackbarHostState,modifier:Modifier=Modifier) {
    SnackbarHost(state,modifier) {data ->
        Surface(shape=RoundedCornerShape(18.dp),color=Color(0xFF303630),shadowElevation=12.dp,tonalElevation=0.dp) {
            Row(Modifier.widthIn(max=520.dp).padding(start=18.dp,end=10.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(data.visuals.message,Modifier.weight(1f),style=OtoType.rowSub,color=Color.White.copy(alpha=.9f))
                data.visuals.actionLabel?.let { label -> Text(label,Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha=.09f)).clickable{data.performAction()}.padding(horizontal=14.dp,vertical=10.dp),style=OtoType.rowSub.copy(fontWeight=FontWeight.SemiBold),color=Color.White) }
            }
        }
    }
}
