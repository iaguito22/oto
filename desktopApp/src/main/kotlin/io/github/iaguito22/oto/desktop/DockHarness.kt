package io.github.iaguito22.oto.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*

/** Usa ModuleWindow, el mismo marco de la aplicación, sin servicios ni preferencias de usuario. */
@Composable
internal fun ApplicationScope.DockHarness() {
    // Reproductor y Tu música acoplados, como al instalar Oto; OTO_DOCK_HARNESS_LYRICS=1 añade la letra.
    val session = remember { DockSession(initialDockLayout()).apply {
        open(DockId.Library, DockId.Player)
        if (System.getenv("OTO_DOCK_HARNESS_LYRICS") == "1") open(DockId.Lyrics, DockId.Library)
    } }
    val revision = session.revision
    for (id in DockId.entries) {
        val rect = session.layout.bounds[id] ?: continue
        key(id) {
            val state = rememberWindowState(position = WindowPosition(rect.x.dp, rect.y.dp), size = androidx.compose.ui.unit.DpSize(rect.width.dp, rect.height.dp))
            ModuleWindow(when(id) { DockId.Library -> "Tu música"; DockId.Player -> "Reproductor"; DockId.Queue -> "Cola"; DockId.Lyrics -> "Letra" },
                state, true, { session.layout.close(id); if (session.layout.bounds.isEmpty()) exitApplication() },
                // Pruebas automáticas: por encima de todo para que los clics simulados lleguen aquí.
                pinned = System.getenv("OTO_DOCK_HARNESS_TOP") == "1",
                dock = session, dockId = id,
                extra = {}) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(when(id) { DockId.Library -> "Tu música"; DockId.Player -> "Reproductor"; DockId.Queue -> "Cola"; DockId.Lyrics -> "Letra" }, style = MaterialTheme.typography.headlineSmall)
                    Text("Prueba de ventanas · sin reproducción", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    if (id == DockId.Library) {
                        Button(onClick = session::openQueue) { Text("Abrir cola") }
                        Button(onClick = { session.open(DockId.Lyrics,DockId.Library) }) { Text("Abrir letra") }
                        if(session.supportsMaximize) Button(onClick = { session.maximize(id) }) { Text(if(session.layout.group(id).maximized) "Restaurar conjunto" else "Maximizar conjunto") }
                        Text("Arrastra la cabecera para mover. Usa la esquina inferior derecha para redimensionar.")
                        if (session.message.isNotEmpty()) Text(session.message)
                    }
                    if (id == DockId.Player) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.primaryContainer))
                        Text("Canción de prueba"); Text("Artista · 03:42")
                    } else repeat(if(id == DockId.Library) 5 else 8) { index ->
                        Text("${index + 1}    Canción de prueba    ·    Artista", maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${rect.width} × ${rect.height} · ${if(session.layout.group(id).leader == id) "Jefa" else "Acoplada"}")
                }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val observed = revision
}
