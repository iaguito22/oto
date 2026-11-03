package io.github.iaguito22.oto.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage

@Composable
internal fun DevicesPane(devices: DeviceConnection, revoke: () -> Unit) {
    val status by devices.link.status.collectAsState()
    val syncNotice by devices.syncNotice.collectAsState()
    val syncIssue by devices.syncIssue.collectAsState()
    val remote by devices.link.remote.collectAsState()
    val local by devices.player.state.collectAsState()
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    var accountUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(accountUntil) { if(accountUntil>0) { kotlinx.coroutines.delay(120_000); accountUntil=0L } }
    var result by remember { mutableStateOf<String?>(null) }
    fun action(message: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null; result = null
        scope.launch {
            try { block(); result = message }
            catch (e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; failure = e.message ?: "No se pudo completar la acción" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal=22.dp).verticalScroll(rememberScrollState()).padding(top=8.dp,bottom=24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        DeviceIdentity(remote?.optString("name") ?: "Conectar un dispositivo", remote != null)
        if (remote == null) {
            Text("Abre Dispositivos en Oto móvil y escanea este código. Ambos deben estar en la misma red.", style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurface.copy(alpha=.6f))
            val qr = remember(devices.pairing) {
                val bits = MultiFormatWriter().encode(devices.pairing, BarcodeFormat.QR_CODE, 260, 260)
                BufferedImage(260, 260, BufferedImage.TYPE_INT_RGB).apply { for (y in 0 until 260) for (x in 0 until 260) setRGB(x,y, if(bits[x,y]) 0 else 0xffffff) }.toComposeImageBitmap()
            }
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) { Image(qr,"Código QR de vinculación",Modifier.sizeIn(maxWidth=260.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp)) }
            Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.5f))
        } else {
            RemoteActions(devices.link)
            TransferChoices("Este ordenador", "El móvil", !busy, remote!!.optString("id").let { it.isNotBlank() && it!="null" }, local.id!=null, { action("La música continúa aquí") { devices.transferHere() } }, { action("La música continúa en el móvil") { devices.transferThere() } })
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
        failure?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
        syncIssue?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
        HorizontalDivider(color=MaterialTheme.colorScheme.onSurface.copy(alpha=.07f))
        RemoteDisclosure("Conexión y ajustes", if(remote!=null) "Vinculado" else "Red local", details, { details=!details })
        AnimatedVisibility(details) {
            Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                syncNotice?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.55f)) }
                Text("Usa la cuenta del móvil sin escribir la contraseña.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.55f))
                OutlinedButton(onClick={devices.requestAccount();accountUntil=System.currentTimeMillis()},enabled=remote!=null,shape=RoundedCornerShape(16.dp)) { Text(if(accountUntil>0) "Esperando al móvil…" else "Recibir cuenta del móvil") }
                if(accountUntil>0) Text("Pulsa Compartir cuenta en el móvil. Disponible durante 2 minutos.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                Text("${devices.host}:${devices.port}",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={confirmRevoke=true}) { Text("Desvincular dispositivos",color=MaterialTheme.colorScheme.error) }
            }
        }
    }
    if(confirmRevoke) AlertDialog(onDismissRequest={confirmRevoke=false},shape=RoundedCornerShape(24.dp),title={Text("Desvincular dispositivos")},text={Text("Se cerrará la conexión y se generará un nuevo QR. Tu música y tus listas se conservarán.")},confirmButton={TextButton(onClick={confirmRevoke=false;revoke()}){Text("Desvincular",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={confirmRevoke=false}){Text("Cancelar")}})
}
