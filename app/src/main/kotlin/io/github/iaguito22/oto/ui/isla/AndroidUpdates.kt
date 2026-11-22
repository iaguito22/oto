package io.github.iaguito22.oto.ui.isla

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.iaguito22.oto.BuildConfig
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.update.OtoRelease
import io.github.iaguito22.oto.update.OtoUpdates
import io.github.iaguito22.oto.update.UpdatePlatform
import io.github.iaguito22.oto.update.downloadUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal val supportsAndroidUpdates = BuildConfig.APPLICATION_ID == "io.github.iaguito22.oto"

internal class AndroidUpdateState {
    var release by mutableStateOf<OtoRelease?>(null)
    var progress by mutableStateOf<Float?>(null)
    var feedback by mutableStateOf<String?>(null)
    var onUpdate: () -> Unit = {}
}

@Composable
internal fun rememberAndroidUpdates(prefs: Prefs): AndroidUpdateState? {
    if (!supportsAndroidUpdates) return null
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val state = remember { AndroidUpdateState() }
    LaunchedEffect(prefs) {
        OtoUpdates(prefs, BuildConfig.VERSION_NAME, UpdatePlatform.Android).observe { state.release = it }
    }

    fun download(release: OtoRelease) {
        if (state.progress != null) return
        state.progress = 0f
        state.feedback = null
        scope.launch {
            val progress = MutableStateFlow(0f)
            val meter = launch { progress.collect { state.progress = it } }
            var apk: File? = null
            var handedOff = false
            try {
                val folder = File(context.filesDir, "updates")
                withContext(Dispatchers.IO) {
                    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                    folder.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
                }
                val file = downloadUpdate(release.asset, folder) { progress.value = it }
                apk = file
                val packageInfo = withContext(Dispatchers.IO) {
                    context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
                }
                check(packageInfo != null && packageInfo.packageName == context.packageName && packageInfo.longVersionCode > BuildConfig.VERSION_CODE)
                lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                handedOff = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.feedback = "No se pudo preparar la actualización"
            } finally {
                if (!handedOff) apk?.delete()
                meter.cancel()
                state.progress = null
            }
        }
    }

    var pendingPermission by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val version = pendingPermission
        pendingPermission = null
        state.release?.takeIf { it.version == version }?.let { release ->
            if (context.packageManager.canRequestPackageInstalls()) download(release)
            else state.feedback = "Permite que Oto instale aplicaciones"
        }
    }
    SideEffect {
        state.onUpdate = update@{
            val release = state.release ?: return@update
            if (context.packageManager.canRequestPackageInstalls()) {
                download(release)
            } else {
                pendingPermission = release.version
                try {
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } catch (_: Exception) {
                    pendingPermission = null
                    state.feedback = "No se pudo abrir el permiso de instalación"
                }
            }
        }
    }
    return state
}

@Composable
internal fun AndroidUpdateBanner(state: AndroidUpdateState, visible: Boolean, modifier: Modifier = Modifier) {
    val release = state.release
    var dismissed by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = Oto.colors
    AnimatedVisibility(
        visible = visible && release != null && dismissed != release.version,
        modifier = modifier,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        Surface(color = colors.base, shape = RoundedCornerShape(20.dp), shadowElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val progress = state.progress
                if (progress != null) {
                    Text("Descargando Oto · ${(progress * 100).toInt()} %", Modifier.weight(1f), color = colors.text, style = OtoType.rowSub)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(.45f).padding(end = 10.dp), color = colors.accent)
                } else {
                    Text(state.feedback ?: "Oto ${release?.version.orEmpty()} disponible", Modifier.weight(1f), color = colors.text, style = OtoType.rowSub)
                    TextButton(onClick = state.onUpdate) { Text("Actualizar", color = colors.accent) }
                    TextButton(onClick = { dismissed = release?.version }) { Text("Ocultar", color = colors.textMuted) }
                }
            }
        }
    }
}
