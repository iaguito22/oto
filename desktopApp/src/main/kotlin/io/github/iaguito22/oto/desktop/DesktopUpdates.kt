package io.github.iaguito22.oto.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.OtoDirs
import io.github.iaguito22.oto.Prefs
import io.github.iaguito22.oto.desktop.ui.HoverAction
import io.github.iaguito22.oto.desktop.ui.OtoType
import io.github.iaguito22.oto.update.OtoRelease
import io.github.iaguito22.oto.update.OtoUpdates
import io.github.iaguito22.oto.update.UpdatePlatform
import io.github.iaguito22.oto.update.downloadUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.util.Base64
import java.util.Properties

internal val desktopVersion: String by lazy {
    val resource = checkNotNull(object {}.javaClass.getResourceAsStream("/oto-version.properties"))
    resource.use { Properties().apply { load(it) }.getProperty("version") }
}

internal class DesktopUpdateState {
    var release by mutableStateOf<OtoRelease?>(null)
    var progress by mutableStateOf<Float?>(null)
    var feedback by mutableStateOf<String?>(null)
    var onUpdate: () -> Unit = {}
}

internal val LocalDesktopUpdate = staticCompositionLocalOf<DesktopUpdateState?> { null }

@Composable
internal fun rememberDesktopUpdates(prefs: Prefs, quit: () -> Unit): DesktopUpdateState {
    val state = remember { DesktopUpdateState() }
    val scope = rememberCoroutineScope()
    val mock = remember { System.getenv("OTO_UPDATE_PREVIEW_JSON")?.let(::File) }
    val linux = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
    LaunchedEffect(prefs) {
        val platforms = when {
            isWindowsDesktop -> arrayOf(UpdatePlatform.Windows)
            linux -> withContext(Dispatchers.IO) { linuxUpdatePlatforms() }
            else -> emptyArray()
        }
        if (platforms.isEmpty()) return@LaunchedEffect
        if (mock == null) OtoUpdates(prefs, desktopVersion, *platforms).observe { state.release = it }
        else OtoUpdates(prefs, desktopVersion, *platforms, fetch = { withContext(Dispatchers.IO) { mock.readText() } }, persistChecks = false)
            .observe { state.release = it }
    }
    val onUpdate: () -> Unit = update@{
        val release = state.release ?: return@update
        if (state.progress != null) return@update
        state.feedback = null
        state.progress = 0f
        scope.launch {
            val downloaded = MutableStateFlow(0f)
            val meter = launch { downloaded.collectLatest { state.progress = it } }
            var installer: File? = null
            var handedOff = false
            try {
                when {
                    mock != null -> {
                        for (step in 1..20) {
                            delay(100)
                            downloaded.value = step / 20f
                        }
                        state.feedback = "Descarga simulada de Oto ${release.version}"
                    }
                    isWindowsDesktop -> {
                        val msi = downloadUpdate(release.asset, File(OtoDirs.filesDir, "updates")) { downloaded.value = it }
                        installer = msi
                        launchInstallerAfterExit(msi)
                        handedOff = true
                        quit()
                    }
                    linux -> withContext(Dispatchers.IO) { Desktop.getDesktop().browse(release.page) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.feedback = "No se pudo actualizar Oto ${release.version}. Pulsa para reintentar."
            } finally {
                if (!handedOff) installer?.delete()
                meter.cancel()
                state.progress = null
            }
        }
    }
    SideEffect { state.onUpdate = onUpdate }
    return state
}

/**
 * Espera a que Oto se cierre, abre el instalador y, al acabar (también si se cancela), vuelve a abrir Oto. Los datos
 * van por variables de entorno, no dentro del texto: así una ruta con comillas o espacios no rompe nada.
 */
internal val installerScript = """
    Wait-Process -Id ${'$'}env:OTO_UPDATE_PARENT_PID -ErrorAction SilentlyContinue
    try {
        ${'$'}arguments = '/i "' + ${'$'}env:OTO_UPDATE_MSI + '"'
        Start-Process -FilePath (Join-Path ${'$'}env:SystemRoot 'System32\msiexec.exe') -ArgumentList ${'$'}arguments -Wait
    } finally {
        Remove-Item -LiteralPath ${'$'}env:OTO_UPDATE_MSI -ErrorAction SilentlyContinue
        if (${'$'}env:OTO_UPDATE_RELAUNCH -and (Test-Path -LiteralPath ${'$'}env:OTO_UPDATE_RELAUNCH)) {
            Start-Process -FilePath ${'$'}env:OTO_UPDATE_RELAUNCH
        }
    }
""".trimIndent()

private fun launchInstallerAfterExit(installer: File) {
    // Windows Installer necesita que el lanzador y los archivos de la app estén libres.
    val encoded = Base64.getEncoder().encodeToString(installerScript.toByteArray(Charsets.UTF_16LE))
    // Solo se vuelve a abrir el Oto instalado (Oto.exe), no una prueba lanzada con java.
    val relaunch = ProcessHandle.current().info().command().orElse(null)?.takeIf { it.endsWith("Oto.exe", ignoreCase = true) }
    ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .apply {
            environment()["OTO_UPDATE_PARENT_PID"] = ProcessHandle.current().pid().toString()
            environment()["OTO_UPDATE_MSI"] = installer.absolutePath
            if (relaunch != null) environment()["OTO_UPDATE_RELAUNCH"] = relaunch
        }
        .start()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopUpdateButton(accent: Color, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val state = LocalDesktopUpdate.current ?: return
    val release = state.release
    val progress = state.progress
    var lastVersion by remember { mutableStateOf("") }
    SideEffect { if (release != null) lastVersion = release.version }
    val version = release?.version ?: lastVersion
    AnimatedVisibility(
        visible = release != null,
        modifier = modifier,
        enter = fadeIn() + expandHorizontally(spring(dampingRatio = Spring.DampingRatioMediumBouncy), expandFrom = Alignment.Start),
        exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
    ) {
        val label = when {
            progress != null -> "Descargando Oto $version · ${(progress * 100).toInt()} %"
            state.feedback != null -> state.feedback.orEmpty()
            isWindowsDesktop -> "Actualizar a Oto $version"
            else -> "Ver Oto $version en GitHub"
        }
        key(LocalWindowTooltipEpoch.current) {
            TooltipArea(tooltip = {
                Surface(color = Color(0xFF30362F), shape = RoundedCornerShape(8.dp)) {
                    Text(label, Modifier.padding(8.dp), style = OtoType.rowSub)
                }
            }) {
                if (progress != null) {
                    Box(Modifier.size(size).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(minOf(21.dp, size - 4.dp)), color = accent, strokeWidth = 2.dp)
                    }
                } else {
                    HoverAction(
                        if (state.feedback?.startsWith("No se pudo") == true) Icons.Rounded.ErrorOutline else Icons.Rounded.SystemUpdateAlt,
                        label, onClick = state.onUpdate, tint = accent, size = size,
                    )
                }
            }
        }
    }
}

private fun linuxUpdatePlatforms(): Array<UpdatePlatform> {
    val ids = runCatching { File("/etc/os-release").readLines() }.getOrDefault(emptyList())
        .filter { it.startsWith("ID=") || it.startsWith("ID_LIKE=") }
        .flatMap { it.substringAfter('=').trim('"', '\'').split(' ') }
    return when {
        "arch" in ids -> arrayOf(UpdatePlatform.Arch)
        "debian" in ids || "ubuntu" in ids -> arrayOf(UpdatePlatform.Debian)
        else -> arrayOf(UpdatePlatform.Debian, UpdatePlatform.Arch)
    }
}
