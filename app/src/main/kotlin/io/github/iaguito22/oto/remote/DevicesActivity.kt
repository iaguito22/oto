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
            val local by (application as OtoApp).player.localState.collectAsState()
            val scope=rememberCoroutineScope()
            var error by remember { mutableStateOf<String?>(null) }
            var busy by remember { mutableStateOf(false) }
            var confirmAccount by remember { mutableStateOf(false) }
            var accountSent by remember { mutableStateOf(false) }
            var details by remember { mutableStateOf(false) }
            var confirmDisconnect by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf<String?>(null) }
            var paired by remember { mutableStateOf(devices.canResume) }
            fun action(block:suspend ()->Unit) {
                if(busy) return
                busy=true; error=null; result=null
                // En la app, no en la pantalla: salir de Dispositivos no corta una transferencia a medias.
                (application as OtoApp).scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    try { block() } catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; error=e.message ?: "No se pudo completar la acción" }
                    finally { busy=false }
                }
            }
            fun scan() {
                GmsBarcodeScanning.getClient(this@DevicesActivity).startScan()
                    .addOnSuccessListener { scan -> runCatching { devices.pair(scan.rawValue ?: error("Código vacío")); paired=true }.onFailure { error=it.message } }
                    .addOnFailureListener { error=it.message }
            }
            val connected = remote!=null
            val linked = connected || paired
            val faint = MaterialTheme.colorScheme.onSurface.copy(alpha=.6f)
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=28.dp), verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        RemoteIcon(ImageVector.vectorResource(R.drawable.ic_arrow_back),"Volver") { finish() }
                        Text("Dispositivos",style=MaterialTheme.typography.titleMedium)
                    }
                    // Primero el estado del vínculo; debajo, mover la música y gestionar el enlace.
                    DeviceIdentity(
                        remote?.optString("name")?.takeIf { it.isNotBlank() && it!="null" } ?: if(linked) "Oto Desktop" else "Vincula un ordenador",
                        when { connected -> "Conectado · Red local"; linked -> "Sin conexión"; else -> "Sin vincular" },
                        connected,
                    )
                    when {
                        connected -> TransferChoices("Este móvil","El ordenador",!busy,remote!!.optString("id").let { it.isNotBlank() && it!="null" },local.id!=null, { action { devices.transferHere();result="La música continúa aquí" } }, { action { devices.transferThere();result="La música continúa en el ordenador" } })
                        linked -> {
                            Text("Oto reintentará la conexión. Comprueba que los dos equipos estén en la misma red y que Oto esté abierto en el ordenador.",style=MaterialTheme.typography.bodyMedium,color=faint)
                            OutlinedButton(onClick=::scan,shape=RoundedCornerShape(16.dp)) { Text("Volver a escanear el QR") }
                        }
                        else -> {
                            Text("Conecta los dos equipos a la misma red. En Oto Desktop, abre Dispositivos y escanea su código.",style=MaterialTheme.typography.bodyMedium,color=faint)
                            Button(onClick=::scan,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().height(54.dp)) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_link_qr),null,Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Escanear código QR",color=MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    result?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
                    error?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                    if(linked) {
                        HorizontalDivider(color=MaterialTheme.colorScheme.onSurface.copy(alpha=.07f))
                        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text("Cuenta y sincronización",style=MaterialTheme.typography.titleSmall)
                            Text(if(connected) "Tus listas y Me gusta de Oto se sincronizan en la red local. Los Me gusta de YouTube Music van en su propia lista." else "La sincronización se reanudará al conectar.",style=MaterialTheme.typography.bodySmall,color=faint)
                            syncNotice?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.55f)) }
                            syncIssue?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                            OutlinedButton(enabled=connected && !busy,onClick={confirmAccount=true},shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) { Text("Compartir cuenta") }
                            Text(if(accountSent) "Cuenta enviada al ordenador" else "Inicia sesión en el ordenador con tu cuenta del móvil.",style=MaterialTheme.typography.bodySmall,color=if(accountSent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=.45f))
                        }
                    }
                    HorizontalDivider(color=MaterialTheme.colorScheme.onSurface.copy(alpha=.07f))
                    RemoteDisclosure("Conexión",status,details,{details=!details})
                    AnimatedVisibility(details) {
                        Text("La conexión es directa entre los dos equipos, sin pasar por internet.",style=MaterialTheme.typography.bodySmall,color=faint)
                    }
                    if(linked) TextButton(onClick={confirmDisconnect=true}) { Text("Desvincular",color=MaterialTheme.colorScheme.error) }
                }
            }
            if(confirmDisconnect) AlertDialog(onDismissRequest={confirmDisconnect=false},shape=RoundedCornerShape(24.dp),title={Text("Desvincular el ordenador")},text={Text("Se cerrará la conexión local. Tu música y tus listas se conservarán.")},confirmButton={TextButton(onClick={confirmDisconnect=false;devices.disconnect();paired=false}){Text("Desvincular",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={confirmDisconnect=false}){Text("Cancelar")}})
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
