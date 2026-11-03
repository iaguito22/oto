package io.github.iaguito22.oto.ui.menu

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.LocalPlaylist
import io.github.iaguito22.oto.LIKED_ID
import io.github.iaguito22.oto.account.Cloud
import io.github.iaguito22.oto.account.CloudSelection
import kotlinx.coroutines.launch

private data class AccountListChoice(val id: String, val name: String, val detail: String)

@Composable
internal fun CloudChoices(cloud: Cloud, local: List<LocalPlaylist>) {
    val saved by cloud.selection.collectAsState(initial=null)
    val scope=rememberCoroutineScope()
    var importing by remember { mutableStateOf(true) }
    var imports by remember { mutableStateOf(emptySet<String>()) }
    var exports by remember { mutableStateOf(emptySet<String>()) }
    var catalog by remember { mutableStateOf(emptyList<AccountListChoice>()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(saved) { if(!busy) { val choice=CloudSelection.decode(saved);imports=choice?.imports.orEmpty();exports=choice?.exports.orEmpty() } }
    LaunchedEffect(revision) {
        loading=true;failure=null
        try { catalog=cloud.available().map { AccountListChoice(it.id.removePrefix("VL"),it.title,it.songCountText.orEmpty()) } }
        catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; failure="No se pudieron cargar las listas. Comprueba la conexión y reintenta." }
        finally { loading=false }
    }
    val entries=if(importing) listOf(AccountListChoice("LM","Me gusta","De YouTube Music a Oto"))+catalog else listOf(AccountListChoice(LIKED_ID,"Me gusta","Sincronizar tus Me gusta con YouTube Music"))+local.map { AccountListChoice(it.id,it.name,"${it.songs.size} canciones") }
    val filtered=entries.filter { it.name.contains(query,ignoreCase=true) }
    val selected=if(importing) imports else exports
    val accent=MaterialTheme.colorScheme.primary
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Elige tus listas",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
        Text("Trae listas a Oto o mantén las que elijas actualizadas en YouTube Music.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.55f))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(accent.copy(alpha=.05f)).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf(true,false).forEach { tab ->
                val active=tab==importing
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if(active)accent.copy(alpha=.15f)else Color.Transparent).clickable { importing=tab;query="" }.padding(vertical=12.dp),contentAlignment=Alignment.Center) {
                    Text(if(tab) "Importar · ${imports.size}" else "Exportar · ${exports.size}",style=MaterialTheme.typography.labelLarge,color=if(active)accent else MaterialTheme.colorScheme.onSurface.copy(alpha=.55f))
                }
            }
        }
        BasicTextField(query,{query=it},singleLine=true,textStyle=MaterialTheme.typography.bodyMedium.copy(color=MaterialTheme.colorScheme.onSurface),cursorBrush=SolidColor(accent),modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha=.035f)).padding(horizontal=14.dp,vertical=12.dp).semantics { contentDescription="Buscar listas de la cuenta" },decorationBox={inner->Box { if(query.isEmpty())Text("Buscar listas",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.35f));inner() }})
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(if(importing) "YouTube Music → Oto" else "Oto → YouTube Music",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.45f))
            TextButton(enabled=!busy,onClick={if(importing)imports=emptySet()else exports=emptySet()}) { Text("Ninguna") }
            TextButton(enabled=!busy && (!importing || !loading),onClick={if(importing)imports=imports+filtered.map{it.id}else exports=exports+filtered.map{it.id}}) { Text("Todas") }
        }
        if(importing && loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxWidth().heightIn(min=100.dp,max=220.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            if(filtered.isEmpty()) item { Text("No hay listas con ese nombre",Modifier.padding(14.dp),style=MaterialTheme.typography.bodySmall) }
            items(filtered,key={it.id}) { item ->
                val checked=item.id in selected
                val interaction=remember { MutableInteractionSource() }
                val hover by interaction.collectIsHoveredAsState()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if(checked)accent.copy(alpha=.06f)else Color.Transparent).hoverable(interaction).clickable(interactionSource=interaction,indication=null,enabled=!busy) { if(importing) imports=if(checked)imports-item.id else imports+item.id else exports=if(checked)exports-item.id else exports+item.id }.semantics { role=Role.Checkbox; stateDescription=if(checked)"Seleccionada"else"Sin seleccionar" }.padding(horizontal=12.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(item.name,style=MaterialTheme.typography.bodyMedium.copy(fontSize=MaterialTheme.typography.bodyMedium.fontSize*(if(hover)1.04f else 1f)),maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(item.detail.isNotBlank())Text(item.detail,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.4f),maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(22.dp).border(1.5.dp,if(checked)accent else MaterialTheme.colorScheme.onSurface.copy(alpha=.3f),CircleShape),contentAlignment=Alignment.Center) { if(checked)Box(Modifier.size(12.dp).background(accent,CircleShape)) }
                }
            }
        }
        failure?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall);TextButton(onClick={revision++},enabled=!busy){Text("Volver a cargar")} }
        result?.let { Text(it,color=accent,style=MaterialTheme.typography.bodySmall) }
        Button(enabled=!busy && (!loading || imports.isEmpty()) && (failure==null || imports.isEmpty()),shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth(),onClick={
            busy=true;failure=null;result=null
            scope.launch {
                try { cloud.choose(CloudSelection(imports,exports));result=if(imports.isEmpty()&&exports.isEmpty())"Cuenta conectada sin sincronizar listas"else"Selección guardada" }
                catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException)throw e;failure=e.message ?: "No se pudo guardar la selección" }
                finally { busy=false }
            }
        }) { Text(if(busy) "Preparando tus listas…" else if(imports.isEmpty()&&exports.isEmpty()) "Continuar sin sincronizar listas" else "Aplicar selección") }
        Text("Desmarcar una lista no la borra. Puedes cambiar tu selección aquí cuando quieras.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.4f))
    }
}
