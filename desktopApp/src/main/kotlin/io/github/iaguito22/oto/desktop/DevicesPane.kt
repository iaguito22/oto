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
    val accountStatus by devices.accountStatus.collectAsState()
    val remote by devices.link.remote.collectAsState()
    val peer by devices.peer.collectAsState()
    val local by devices.player.state.collectAsState()
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
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
    val connected = remote != null
    val linked = connected || peer != null
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha=.6f)
    Column(Modifier.fillMaxSize().padding(horizontal=22.dp).verticalScroll(rememberScrollState()).padding(top=8.dp,bottom=24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // Primero el estado del vínculo; debajo, mover la música y gestionar el enlace.
        DeviceIdentity(
            remote?.optString("name")?.takeIf { it.isNotBlank() && it != "null" } ?: peer ?: "Vincula un móvil",
            when { connected -> "Conectado · Red local"; linked -> "Sin conexión"; else -> "Sin vincular" },
            connected,
        )
        when {
            connected -> TransferChoices("Este ordenador", "El móvil", !busy, remote!!.optString("id").let { it.isNotBlank() && it!="null" }, local.id!=null, { action("La música continúa aquí") { devices.transferHere() } }, { action("La música continúa en el móvil") { devices.transferThere() } })
            linked -> {
                Text("Oto reintentará la conexión. Comprueba que los dos equipos estén en la misma red y que Oto esté abierto en el móvil.", style=MaterialTheme.typography.bodyMedium, color=faint)
                TextButton(onClick={ showQr = !showQr }) { Text(if (showQr) "Ocultar el QR" else "Volver a vincular con el QR") }
            }
            else -> Text("En Oto móvil, abre Dispositivos y toca «Escanear código QR». Los dos equipos deben estar en la misma red.", style=MaterialTheme.typography.bodyMedium, color=faint)
        }
        AnimatedVisibility(!connected && (!linked || showQr)) {
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                val qr = remember(devices.pairing) {
                    val bits = MultiFormatWriter().encode(devices.pairing, BarcodeFormat.QR_CODE, 260, 260)
                    BufferedImage(260, 260, BufferedImage.TYPE_INT_RGB).apply { for (y in 0 until 260) for (x in 0 until 260) setRGB(x,y, if(bits[x,y]) 0 else 0xffffff) }.toComposeImageBitmap()
                }
                Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) { Image(qr,"Código QR de vinculación",Modifier.sizeIn(maxWidth=260.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp)) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
        failure?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
        if (linked) {
            HorizontalDivider(color=MaterialTheme.colorScheme.onSurface.copy(alpha=.07f))
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Cuenta y sincronización", style=MaterialTheme.typography.titleSmall)
                Text(if (connected) "Tus listas y Me gusta de Oto se sincronizan en la red local. Los Me gusta de YouTube Music van en su propia lista." else "La sincronización se reanudará al conectar.", style=MaterialTheme.typography.bodySmall, color=faint)
                syncNotice?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.55f)) }
                syncIssue?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick=devices::requestAccount,enabled=connected,shape=RoundedCornerShape(16.dp)) { Text(if(accountStatus?.startsWith("Esperando") == true) "Esperando al móvil…" else "Recibir cuenta del móvil") }
                if(accountStatus?.startsWith("Esperando") == true) Text("Pulsa Compartir cuenta en el móvil. Disponible durante 2 minutos.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                else accountStatus?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
            }
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.onSurface.copy(alpha=.07f))
        RemoteDisclosure("Conexión", status, details, { details=!details })
        AnimatedVisibility(details) {
            Text("Este PC: ${devices.host}:${devices.port}",style=MaterialTheme.typography.bodySmall,color=faint)
        }
        if (linked) TextButton(onClick={confirmRevoke=true}) { Text("Desvincular",color=MaterialTheme.colorScheme.error) }
    }
    if(confirmRevoke) AlertDialog(onDismissRequest={confirmRevoke=false},shape=RoundedCornerShape(24.dp),title={Text("Desvincular el móvil")},text={Text("Se cerrará la conexión y se generará un nuevo QR. Tu música y tus listas se conservarán.")},confirmButton={TextButton(onClick={confirmRevoke=false;devices.forgetPeer();revoke()}){Text("Desvincular",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={confirmRevoke=false}){Text("Cancelar")}})
}
