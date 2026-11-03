package io.github.iaguito22.oto.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.iaguito22.oto.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.ui.theme.OtoTheme
import kotlinx.coroutines.launch

class DevicesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val devices=(application as OtoApp).devices
        if(devices.canResume && !devices.enabled) androidx.core.content.ContextCompat.startForegroundService(this, android.content.Intent(this,ConnectionService::class.java))
        setContent { OtoTheme { MaterialTheme(colorScheme=darkColorScheme(primary=Oto.colors.accent,onPrimary=Oto.colors.onAccent,surface=Oto.colors.base,background=Oto.colors.base,onSurface=Oto.colors.text,onBackground=Oto.colors.text,secondary=Oto.colors.accent,surfaceVariant=Color(0xFF242824),outline=Color.White.copy(alpha=.18f),surfaceTint=Color.Transparent), typography=Typography(bodyLarge=OtoType.body,bodyMedium=OtoType.body,bodySmall=OtoType.rowSub,titleLarge=OtoType.title,titleMedium=OtoType.rowTitle,titleSmall=OtoType.rowTitle,labelLarge=OtoType.rowTitle,labelMedium=OtoType.rowSub,labelSmall=OtoType.rowSub,headlineMedium=OtoType.title)) {
            val status by devices.link.status.collectAsState()
            val syncNotice by devices.syncNotice.collectAsState()
            val syncIssue by devices.syncIssue.collectAsState()
            val remote by devices.link.remote.collectAsState()
            val local by (application as OtoApp).player.state.collectAsState()
            val scope=rememberCoroutineScope()
            var error by remember { mutableStateOf<String?>(null) }
            var busy by remember { mutableStateOf(false) }
            var confirmAccount by remember { mutableStateOf(false) }
            var accountSent by remember { mutableStateOf(false) }
            var details by remember { mutableStateOf(false) }
            var confirmDisconnect by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf<String?>(null) }
            fun action(block:suspend ()->Unit) {
                if(busy) return
                busy=true; error=null; result=null
                scope.launch {
                    try { block() } catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; error=e.message ?: "No se pudo completar la acción" }
                    finally { busy=false }
                }
            }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=28.dp), verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        RemoteIcon(ImageVector.vectorResource(R.drawable.ic_arrow_back),"Volver") { finish() }
                        Text("Dispositivos",style=MaterialTheme.typography.titleMedium)
                    }
                    DeviceIdentity(remote?.optString("name") ?: "Conectar con Oto",remote!=null)
                    if(remote==null) {
                        Text("Tu música puede seguir en el ordenador. Conecta ambos a la misma red y escanea el QR de Oto Desktop.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.6f))
                        Button(onClick={ GmsBarcodeScanning.getClient(this@DevicesActivity).startScan().addOnSuccessListener { scan -> runCatching { devices.pair(scan.rawValue ?: error("Código vacío")) }.onFailure { error=it.message } }.addOnFailureListener { error=it.message } },shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().height(54.dp)) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_link_qr),null,Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Escanear código QR",color=MaterialTheme.colorScheme.onPrimary)
                        }
                        Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.45f))
                    } else {
                        RemoteActions(devices.link)
                        TransferChoices("Este móvil","El ordenador",!busy,remote!!.optString("id").let { it.isNotBlank() && it!="null" },local.id!=null, { action { devices.transferHere();result="La música continúa aquí" } }, { action { devices.transferThere();result="La música continúa en el ordenador" } })
                    }
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    result?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
                    error?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                    syncIssue?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                    if(devices.enabled || remote!=null) {
                        HorizontalDivider(color=MaterialTheme.colorScheme.onSurface.copy(alpha=.07f))
                        RemoteDisclosure("Conexión y cuenta",if(remote!=null) "Vinculado" else "Sin conexión",details,{details=!details})
                        AnimatedVisibility(details) {
                            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                syncNotice?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.55f)) }
                                if(remote!=null) {
                                    OutlinedButton(enabled=!busy,onClick={confirmAccount=true},shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) { Text("Compartir cuenta") }
                                    Text("Inicia sesión en el ordenador con tu cuenta del móvil.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.45f))
                                }
                                if(accountSent) Text("Cuenta enviada al ordenador",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                                TextButton(onClick={confirmDisconnect=true}) { Text("Desvincular",color=MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            if(confirmDisconnect) AlertDialog(onDismissRequest={confirmDisconnect=false},shape=RoundedCornerShape(24.dp),title={Text("Desvincular el ordenador")},text={Text("Se cerrará la conexión local. Tu música y tus listas se conservarán.")},confirmButton={TextButton(onClick={confirmDisconnect=false;devices.disconnect()}){Text("Desvincular",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={confirmDisconnect=false}){Text("Cancelar")}})
            if(confirmAccount) AlertDialog(
                onDismissRequest={confirmAccount=false},
                shape=RoundedCornerShape(24.dp),
                title={Text("Compartir cuenta")},
                text={Text("Se enviará tu sesión de YouTube Music al ordenador conectado para que pueda usar tu cuenta. Hazlo solo con un ordenador de confianza.")},
                confirmButton={TextButton(onClick={confirmAccount=false; action { devices.sendAccount(); accountSent=true }}) { Text("Compartir") }},
                dismissButton={TextButton(onClick={confirmAccount=false}) { Text("Cancelar") }},
            )
        } } }
    }
}
