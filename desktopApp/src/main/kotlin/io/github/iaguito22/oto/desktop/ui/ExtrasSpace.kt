package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.desktop.wallpaper.WallpaperSettings
import io.github.iaguito22.oto.desktop.wallpaper.WallpaperStatus
import io.github.iaguito22.oto.desktop.wallpaper.WallpaperStore
import io.github.iaguito22.oto.desktop.wallpaper.parseColor
import io.github.iaguito22.oto.desktop.wallpaper.wallpaperSupported
import kotlin.math.roundToInt

/**
 * Ajustes → Extras (beta): cosas aparte del reproductor. [extraItems] añade filas arriba (las luces del laboratorio).
 */
@Composable
internal fun ExtrasSpace(accent: Color, onBack: () -> Unit, extraItems: LazyListScope.() -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp + LocalFloatingDockInset.current), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            HoverRow(onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver a Ajustes", Modifier.size(22.dp), tint = Color.White.copy(alpha = .8f))
                Column(Modifier.weight(1f)) {
                    Text("Extras (beta)", style = OtoType.section)
                    Text("Cosas aparte del reproductor. Están en pruebas.", style = OtoType.rowSub, color = Color.White.copy(alpha = .5f))
                }
            }
        }
        extraItems()
        wallpaperItems(accent)
        clockItems(accent)
        controlsItems(accent)
    }
}

/** Fila «Extras (beta)» de la lista principal de Ajustes. */
@Composable
internal fun ExtrasLink(accent: Color, onClick: () -> Unit) {
    val on by WallpaperStore.enabled.collectAsState()
    SettingsLink(Icons.Rounded.AutoAwesome, "Extras (beta)", if (on) "Fondo de escritorio vivo encendido · hora · controles · luces" else "Fondo de escritorio vivo, hora, controles de música, luces y visuales", accent, onClick)
}

/** Los controles de música en el escritorio: aparte del fondo vivo (van con él o solos), como la hora. */
private fun LazyListScope.controlsItems(accent: Color) {
    item { ControlsToggle(accent) }
    item { ControlsPanel(accent) }
}

@Composable
private fun ControlsToggle(accent: Color) {
    val p by WallpaperStore.settings.collectAsState()
    val wall by WallpaperStore.enabled.collectAsState()
    val subtitle = when {
        !wallpaperSupported -> "De momento solo en Windows."
        p.controles && wall -> "Anterior, pausa, siguiente y más en el escritorio, junto al fondo vivo (la letra les deja sitio)."
        p.controles -> "Anterior, pausa, siguiente y más en el escritorio, encima de tu fondo de pantalla. No necesita el fondo vivo."
        else -> "Pon los controles de lo que suena en el escritorio, detrás de los iconos. Funciona con o sin el fondo vivo."
    }
    SettingsToggle(Icons.Rounded.MusicNote, "Controles de música en el escritorio", subtitle, p.controles && wallpaperSupported, accent) {
        if (wallpaperSupported) WallpaperStore.update { it.copy(controles = !it.controles) }
    }
}

/** Los ajustes de los controles (solo con ellos encendidos). */
@Composable
private fun ControlsPanel(accent: Color) {
    val p by WallpaperStore.settings.collectAsState()
    if (!p.controles) return
    val set: ((WallpaperSettings) -> WallpaperSettings) -> Unit = { WallpaperStore.update(it) }
    Column(Modifier.fillMaxWidth().padding(start = 68.dp, end = 12.dp, top = 4.dp, bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .045f))
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("Estilo")
            Chips(listOf("Compacta", "Píldora", "Tarjeta", "Solo botones", "Vinilo"), CTL_STYLES.indexOf(p.ctlEstilo), accent) { i -> set { it.copy(ctlEstilo = CTL_STYLES[i]) } }
            if (p.ctlEstilo == "vinilo") Note("El vinilo va abajo a la izquierda, pegado al borde.")
            else { Label("Dónde"); Chips(WIDGET_POS_LABELS, WIDGET_POS.indexOf(p.ctlPos), accent) { i -> set { it.copy(ctlPos = WIDGET_POS[i]) } } }
            Label("Fuente")
            FontChips(listOf("oto") + FONT_KEYS.filter { it != "oto" }, p.ctlFuente, accent) { k -> set { it.copy(ctlFuente = k) } }
            FontHelp(accent)
            Range("Tamaño", p.ctlTam, .6f, 1.8f, accent, pct) { v -> set { it.copy(ctlTam = v) } }
            Range("Opacidad", p.ctlOpacidad, .2f, 1f, accent, pct) { v -> set { it.copy(ctlOpacidad = v) } }
            if (p.ctlEstilo == "vinilo") {
                Toggle("El disco gira mientras suena", p.ctlGiro, accent) { set { it.copy(ctlGiro = !it.ctlGiro) } }
                Toggle("Las siguientes de la cola (pulsa una para saltar a ella)", p.ctlCola, accent) { set { it.copy(ctlCola = !it.ctlCola) } }
                if (p.ctlCola) Chips(listOf("1", "2", "3", "4", "5"), p.ctlColaN - 1, accent) { i -> set { it.copy(ctlColaN = i + 1) } }
            }
            if (p.ctlEstilo == "pildora" || p.ctlEstilo == "tarjeta") Range("Fondo oscuro detrás", p.ctlFondo, 0f, .9f, accent, pct) { v -> set { it.copy(ctlFondo = v) } }
            Label("Color de los botones")
            Chips(listOf("De la carátula", "Blanco"), listOf("caratula", "blanco").indexOf(p.ctlColor), accent) { i -> set { it.copy(ctlColor = listOf("caratula", "blanco")[i]) } }
            Label("Qué se ve")
            if (p.ctlEstilo != "botones" && p.ctlEstilo != "vinilo") {
                Toggle("Carátula", p.ctlCaratula, accent) { set { it.copy(ctlCaratula = !it.ctlCaratula) } }
                Toggle("Título y artista", p.ctlTitulo, accent) { set { it.copy(ctlTitulo = !it.ctlTitulo) } }
                Toggle("Tiempo (1:23 / 3:45)", p.ctlTiempos, accent) { set { it.copy(ctlTiempos = !it.ctlTiempos) } }
            }
            Toggle("Barra de la canción (pulsa para saltar)", p.ctlProgreso, accent) { set { it.copy(ctlProgreso = !it.ctlProgreso) } }
            Toggle("Me gusta", p.ctlMeGusta, accent) { set { it.copy(ctlMeGusta = !it.ctlMeGusta) } }
            Toggle("Aleatorio", p.ctlAleatorio, accent) { set { it.copy(ctlAleatorio = !it.ctlAleatorio) } }
            Toggle("Repetir", p.ctlRepetir, accent) { set { it.copy(ctlRepetir = !it.ctlRepetir) } }
            if (p.ctlEstilo != "vinilo") Toggle("Volumen", p.ctlVolumen, accent) { set { it.copy(ctlVolumen = !it.ctlVolumen) } }
            Toggle("Solo al acercar el ratón", p.ctlAlPasar, accent) { set { it.copy(ctlAlPasar = !it.ctlAlPasar) } }
            Note("Se pulsan con el escritorio a la vista. No salen si no hay nada en la cola. Con el fondo vivo, la letra les deja sitio.")
    }
}

/** La hora en el escritorio: aparte del fondo vivo (va con él o sola). */
private fun LazyListScope.clockItems(accent: Color) {
    item { ClockToggle(accent) }
    item { ClockPanel(accent) }
}

@Composable
private fun ClockToggle(accent: Color) {
    val p by WallpaperStore.settings.collectAsState()
    val wall by WallpaperStore.enabled.collectAsState()
    val subtitle = when {
        !wallpaperSupported -> "De momento solo en Windows."
        p.reloj && wall -> "La hora y la fecha en el escritorio, detrás de los iconos, junto al fondo vivo (la letra le deja sitio)."
        p.reloj -> "La hora y la fecha en el escritorio, detrás de los iconos, encima de tu fondo de pantalla. No necesita el fondo vivo."
        else -> "Pon la hora y la fecha en el escritorio, detrás de los iconos. Funciona con o sin el fondo vivo."
    }
    SettingsToggle(Icons.Rounded.Schedule, "Hora en el escritorio", subtitle, p.reloj && wallpaperSupported, accent) {
        if (wallpaperSupported) WallpaperStore.update { it.copy(reloj = !it.reloj) }
    }
}

/** Los ajustes de la hora (solo con ella encendida). */
@Composable
private fun ClockPanel(accent: Color) {
    val p by WallpaperStore.settings.collectAsState()
    if (!p.reloj) return
    val set: ((WallpaperSettings) -> WallpaperSettings) -> Unit = { WallpaperStore.update(it) }
    Column(Modifier.fillMaxWidth().padding(start = 68.dp, end = 12.dp, top = 4.dp, bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .045f))
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("Dónde")
            Chips(WIDGET_POS_LABELS, WIDGET_POS.indexOf(p.relojPos), accent) { i -> set { it.copy(relojPos = WIDGET_POS[i]) } }
            Label("Estilo")
            Chips(listOf("El día en grande", "Fina", "Gruesa", "Digital", "Contorno"), CLOCK_STYLES.indexOf(p.relojEstilo), accent) { i -> set { it.copy(relojEstilo = CLOCK_STYLES[i]) } }
            Label("Fuente")
            FontChips(listOf("estilo") + FONT_KEYS, p.relojFuente, accent) { k -> set { it.copy(relojFuente = k) } }
            FontHelp(accent)
            Range("Tamaño", p.relojTam, .5f, 2f, accent, pct) { v -> set { it.copy(relojTam = v) } }
            Range("Opacidad", p.relojOpacidad, .2f, 1f, accent, pct) { v -> set { it.copy(relojOpacidad = v) } }
            Label("Color")
            Chips(listOf("Blanco", "De la carátula"), listOf("blanco", "caratula").indexOf(p.relojColor), accent) { i -> set { it.copy(relojColor = listOf("blanco", "caratula")[i]) } }
            Label("Idioma")
            Chips(listOf("Español", "English"), listOf("es", "en").indexOf(p.relojIdioma), accent) { i -> set { it.copy(relojIdioma = listOf("es", "en")[i]) } }
            Toggle("Formato de 24 horas", p.hora24, accent) { set { it.copy(hora24 = !it.hora24) } }
            Toggle("Segundos", p.segundos, accent) { set { it.copy(segundos = !it.segundos) } }
            Toggle("Fecha debajo", p.fecha, accent) { set { it.copy(fecha = !it.fecha) } }
            Toggle("Late con el bombo (con el fondo vivo)", p.relojLatido, accent) { set { it.copy(relojLatido = !it.relojLatido) } }
            Note("«Encima/debajo del círculo» toma como referencia dónde va el círculo del fondo vivo. ")
    }
}

private fun LazyListScope.wallpaperItems(accent: Color) {    item { WallpaperToggle(accent) }
    item { WallpaperPanel(accent) }
}

@Composable
private fun WallpaperToggle(accent: Color) {
    val on by WallpaperStore.enabled.collectAsState()
    val issue = WallpaperStatus.issue
    val subtitle = when {
        !wallpaperSupported -> "De momento solo en Windows."
        on && issue != null -> issue
        on -> "El visualizador, la carátula y la letra (y si quieres la hora y los controles) se pintan detrás de los iconos, encima de tu fondo de pantalla. No pinta mientras algo tapa el escritorio (pantalla completa o una ventana maximizada) y casi no gasta sin música."
        else -> "Pon el visualizador con la carátula y la letra en el escritorio, detrás de los iconos y encima de tu fondo de siempre. Al apagarlo vuelve tu fondo."
    }
    SettingsToggle(Icons.Rounded.Wallpaper, "Fondo de escritorio vivo", subtitle, on && wallpaperSupported, accent) {
        if (wallpaperSupported) WallpaperStore.setEnabled(!on)
    }
}

private val pct: (Float) -> String = { "${(it * 100).roundToInt()} %" }

/** Todos los ajustes del fondo vivo, por apartados (los mismos del prototipo del laboratorio). */
@Composable
private fun WallpaperPanel(accent: Color) {
    val p by WallpaperStore.settings.collectAsState()
    val set: ((WallpaperSettings) -> WallpaperSettings) -> Unit = { WallpaperStore.update(it) }
    var open by remember { mutableStateOf("Visualizador") }
    Column(Modifier.fillMaxWidth().padding(start = 68.dp, end = 12.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Estilos rápidos", open, { open = it }) {
            Chips(WallpaperSettings.presets.keys.toList(), -1, accent) { i -> val f = WallpaperSettings.presets.values.toList()[i]; set { f(it) } }
            Note("Cambian varios ajustes a la vez. Después puedes tocar lo que quieras.")
        }
        Section("Visualizador", open, { open = it }) {
            Toggle("Mostrar el visualizador", p.visualizador, accent) { set { it.copy(visualizador = !it.visualizador) } }
            if (!p.visualizador) Note("Sin él quedan la letra y, si los enciendes, la hora y los controles (lo que iba encima o debajo del círculo, en el centro).")
            if (p.visualizador) {
            Chips(listOf("NCS: esfera de hilos", "Espectro en capas", "Barras en anillo", "Onda", "Anillo que tiembla", "Eclipse", "Un anillo por instrumento", "Barras abajo"),
                STYLE_KEYS.indexOf(p.estilo), accent) { i -> set { it.copy(estilo = STYLE_KEYS[i]) } }
            Range("Resplandor", p.resplandor, 0f, 1.5f, accent, pct) { v -> set { it.copy(resplandor = v) } }
            if (p.estilo == "malla") Range("Intensidad del aro", p.aro, .3f, 2f, accent, pct) { v -> set { it.copy(aro = v) } }
            if (p.estilo == "malla") Range("Estela", p.estela, 0f, 1f, accent, pct) { v -> set { it.copy(estela = v) } }
            Range("Tamaño", p.tam, .05f, .2f, accent, { "${(it / .11f * 100).roundToInt()} %" }) { v -> set { it.copy(tam = v) } }
            Range("Largo de la reacción", p.largo, .3f, 2f, accent, pct) { v -> set { it.copy(largo = v) } }
            if (p.estilo == "anillo" || p.estilo == "barras") Range("Número de barras", p.barras.toFloat(), 32f, 180f, accent, { "${it.roundToInt()}" }) { v -> set { it.copy(barras = (v / 4).roundToInt() * 4) } }
            Range("Giro", p.giro, 0f, 1.5f, accent, pct) { v -> set { it.copy(giro = v) } }
            Range("Suavizado", p.suavizado, 0f, 1f, accent, pct) { v -> set { it.copy(suavizado = v) } }
            Toggle("Simétrico (graves abajo, agudos arriba)", p.espejo, accent) { set { it.copy(espejo = !it.espejo) } }
            Label("Posición en la pantalla")
            PositionGrid(p.pos, accent) { v -> set { it.copy(pos = v, offX = 0f, offY = 0f) } }
            Range("Ajuste horizontal", p.offX, -.2f, .2f, accent, pct) { v -> set { it.copy(offX = v) } }
            Range("Ajuste vertical", p.offY, -.2f, .2f, accent, pct) { v -> set { it.copy(offY = v) } }
            }
            Label("Monitor")
            Chips(listOf("Principal", "Segundo", "Todos"), listOf("principal", "segundo", "todos").indexOf(p.monitor), accent) { i -> set { it.copy(monitor = listOf("principal", "segundo", "todos")[i]) } }
        }
        Section("Carátula", open, { open = it }) {
            Label("Dentro del círculo")
            Chips(listOf("Carátula", "Carátula y esfera de puntos", "Esfera de puntos (NCS)"), listOf("caratula", "ambas", "esfera").indexOf(p.dentro), accent) { i -> set { it.copy(dentro = listOf("caratula", "ambas", "esfera")[i]) } }
            Label("Forma")
            Chips(listOf("Círculo", "Redondeada", "Cuadrada", "Vinilo"), listOf("circulo", "redondeada", "cuadrada", "vinilo").indexOf(p.forma), accent) { i -> set { it.copy(forma = listOf("circulo", "redondeada", "cuadrada", "vinilo")[i]) } }
            Range("Tamaño de la carátula", p.tamCaratula, .6f, 1.2f, accent, pct) { v -> set { it.copy(tamCaratula = v) } }
            Range("Borde", p.borde, 0f, 1f, accent, pct) { v -> set { it.copy(borde = v) } }
            Range("Latido con el bombo", p.latido, 0f, 2f, accent, pct) { v -> set { it.copy(latido = v) } }
            Label("Al cambiar de canción")
            Chips(listOf("Rebote", "Giro", "Fundido"), listOf("rebote", "giro", "fundido").indexOf(p.transicion), accent) { i -> set { it.copy(transicion = listOf("rebote", "giro", "fundido")[i]) } }
        }
        Section("Colores", open, { open = it }) {
            Chips(listOf("De la carátula", "Carátula y música", "Fijos", "Según la música"), listOf("caratula", "mezcla", "fijo", "musica").indexOf(p.colores), accent) { i -> set { it.copy(colores = listOf("caratula", "mezcla", "fijo", "musica")[i]) } }
            if (p.colores == "fijo") {
                Label("Color principal"); Swatches(p.color1) { c -> set { it.copy(color1 = c) } }
                Label("Segundo color"); Swatches(p.color2) { c -> set { it.copy(color2 = c) } }
            }
            Range("Saturación", p.saturacion, 0f, 1f, accent, pct) { v -> set { it.copy(saturacion = v) } }
            Toggle("Arcoíris alrededor del círculo", p.arcoiris, accent) { set { it.copy(arcoiris = !it.arcoiris) } }
        }
        Section("Reacción", open, { open = it }) {
            Range("Sensibilidad", p.sens, .3f, 2f, accent, pct) { v -> set { it.copy(sens = v) } }
            Range("Bombo", p.kick, 0f, 2f, accent, pct) { v -> set { it.copy(kick = v) } }
            Range("Caja", p.snare, 0f, 2f, accent, pct) { v -> set { it.copy(snare = v) } }
            Range("Platos", p.hat, 0f, 2f, accent, pct) { v -> set { it.copy(hat = v) } }
            Range("Bajo", p.bass, 0f, 2f, accent, pct) { v -> set { it.copy(bass = v) } }
            Range("Voz", p.voice, 0f, 2f, accent, pct) { v -> set { it.copy(voice = v) } }
            Range("Cámara lenta en los respiros", p.lenta, 0f, 1f, accent, pct) { v -> set { it.copy(lenta = v) } }
            Range("Temblor en los golpes", p.temblor, 0f, 1f, accent, pct) { v -> set { it.copy(temblor = v) } }
            Toggle("Ondas con el bombo", p.anillosBombo, accent) { set { it.copy(anillosBombo = !it.anillosBombo) } }
            Toggle("Destellos con la caja y los drops", p.destellos, accent) { set { it.copy(destellos = !it.destellos) } }
            Toggle("Láseres en los estribillos", p.laseres, accent) { set { it.copy(laseres = !it.laseres) } }
            Range("Desfase", p.desfase, -250f, 250f, accent, { "${it.roundToInt()} ms" }) { v -> set { it.copy(desfase = (v / 5).roundToInt() * 5f) } }
            Note("Si notas que va adelantado o atrasado respecto a lo que oyes.")
        }
        Section("Partículas", open, { open = it }) {
            Toggle("Partículas", p.particulas, accent) { set { it.copy(particulas = !it.particulas) } }
            if (p.particulas) {
                Chips(listOf("Chispas", "Estrellas", "Polvo"), listOf("chispas", "estrellas", "polvo").indexOf(p.tipoPart), accent) { i -> set { it.copy(tipoPart = listOf("chispas", "estrellas", "polvo")[i]) } }
                Range("Cantidad", p.cantidad, 0f, 1.5f, accent, pct) { v -> set { it.copy(cantidad = v) } }
                Range("Velocidad", p.velPart, .2f, 2.5f, accent, pct) { v -> set { it.copy(velPart = v) } }
                Range("Tamaño", p.tamPart, .5f, 3f, accent, pct) { v -> set { it.copy(tamPart = v) } }
            }
        }
        Section("Letra", open, { open = it }) {
            Toggle("Mostrar la letra (nunca encima del visualizador)", p.letra, accent) { set { it.copy(letra = !it.letra) } }
            if (p.letra) {
                Label("Dónde")
                Chips(listOf("Donde haya sitio", "Izquierda", "Derecha", "Arriba", "Abajo"), listOf("auto", "izquierda", "derecha", "arriba", "abajo").indexOf(p.letraPos), accent) { i -> set { it.copy(letraPos = listOf("auto", "izquierda", "derecha", "arriba", "abajo")[i]) } }
                Label("Estilo")
                Chips(listOf("Según la canción", "Editorial", "Electrónica", "Rock", "Hip-hop", "Indie", "Sobria (karaoke)"), LYRIC_KEYS.indexOf(p.estiloLetra), accent) { i -> set { it.copy(estiloLetra = LYRIC_KEYS[i]) } }
                Range("Tamaño", p.tamLetra, .5f, 1.8f, accent, pct) { v -> set { it.copy(tamLetra = v) } }
                Toggle("Dejar libre la columna de iconos", p.respetarIconos, accent) { set { it.copy(respetarIconos = !it.respetarIconos) } }
                Toggle("Ver los huecos de la letra", p.verZonas, accent) { set { it.copy(verZonas = !it.verZonas) } }
            }
        }
        Section("Fondo", open, { open = it }) {
            Chips(listOf("Tu fondo actual", "Carátula desenfocada", "Degradado", "Color", "Negro"), BG_KEYS.indexOf(p.fondo), accent) { i -> set { it.copy(fondo = BG_KEYS[i]) } }
            Note("Con «Tu fondo actual» solo se pintan el visualizador y la letra encima de tu fondo de pantalla.")
            if (p.fondo == "color") Swatches(p.colorFondo, dark = true) { c -> set { it.copy(colorFondo = c) } }
            if (p.fondo == "caratula") {
                Range("Desenfoque", p.desenfoque, 0f, 1f, accent, pct) { v -> set { it.copy(desenfoque = v) } }
                Toggle("Movimiento lento", p.movimiento, accent) { set { it.copy(movimiento = !it.movimiento) } }
            }
            if (p.fondo != "negro") Range("Oscurecer", p.oscurecer, 0f, .9f, accent, pct) { v -> set { it.copy(oscurecer = v) } }
            Range("Sombra detrás del círculo y la letra", p.sombra, 0f, 1f, accent, pct) { v -> set { it.copy(sombra = v) } }
            Range("Bordes oscuros", p.vineta, 0f, 1f, accent, pct) { v -> set { it.copy(vineta = v) } }
        }
        Section("Rendimiento", open, { open = it }) {
            Range("Opacidad", p.opacidad, .2f, 1f, accent, pct) { v -> set { it.copy(opacidad = v) } }
            Label("Cuadros por segundo")
            Chips(listOf("24", "30", "60", "120", "Sin límite"), listOf(24, 30, 60, 120, 0).indexOf(p.fps), accent) { i -> set { it.copy(fps = listOf(24, 30, 60, 120, 0)[i]) } }
            Note("Nunca pasa de lo que da tu pantalla. Menos cuadros, menos gasto: 30 gasta más o menos la cuarta parte que 120.")
            Toggle("Ahorro de energía (sin música casi no pinta)", p.ahorro, accent) { set { it.copy(ahorro = !it.ahorro) } }
            Toggle("No pintar si el escritorio está tapado (pantalla completa o ventana maximizada)", p.pausaCompleta, accent) { set { it.copy(pausaCompleta = !it.pausaCompleta) } }
            PillButton("Restablecer todo", accent) { set { WallpaperSettings() } }
        }
    }
}

private val STYLE_KEYS = listOf("malla", "ncs", "anillo", "onda", "pulso", "eclipse", "instrumentos", "barras")
private val LYRIC_KEYS = listOf("auto", "editorial", "electronica", "rock", "hiphop", "indie", "sobria")
private val BG_KEYS = listOf("actual", "caratula", "degradado", "color", "negro")
private val WIDGET_POS = listOf("centro", "sobre", "bajo", "izquierda", "derecha", "ai", "ad", "bi", "bd")
private val WIDGET_POS_LABELS = listOf("En el centro", "Encima del círculo", "Debajo del círculo", "A su izquierda", "A su derecha", "Esquina arriba izq.", "Esquina arriba dcha.", "Esquina abajo izq.", "Esquina abajo dcha.")
private val CLOCK_STYLES = listOf("dia", "fino", "grueso", "digital", "contorno")
private val FONT_KEYS = listOf("anurati", "blanka", "elianto", "ailerons", "michroma", "oto")
private val FONT_NAMES = mapOf("estilo" to "Según el estilo", "anurati" to "Anurati", "blanka" to "Blanka", "elianto" to "Elianto", "ailerons" to "Ailerons", "michroma" to "Michroma", "oto" to "La de Oto")
/** Fuentes que no vienen con Oto (sus licencias no dejan repartirlas). */
private val EXTERNAL_FONTS = listOf("anurati", "blanka", "elianto", "ailerons")

/** Si falta alguna fuente: cómo descargarla y dónde ponerla, con un botón a la carpeta. Se vuelve a mirar al abrir Ajustes. */
@Composable
private fun FontHelp(accent: Color) {
    val missing = remember { EXTERNAL_FONTS.filter { !io.github.iaguito22.oto.desktop.wallpaper.WidgetFonts.installed(it) } }
    if (missing.isEmpty()) return
    val names = missing.joinToString(", ") { FONT_NAMES.getValue(it) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .04f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Cómo conseguir ${if (missing.size == 1) "la fuente que falta" else "las fuentes que faltan"} ($names)", style = OtoType.rowSub, color = Color.White.copy(alpha = .85f))
        Note("No vienen con Oto: sus licencias no dejan repartirlas. Son gratis para uso personal.")
        Note("1. Busca el nombre de la fuente (por ejemplo «${FONT_NAMES.getValue(missing.first())} font») en dafont.com o 1001fonts.com, o en Google, y pulsa «Descargar».")
        Note("2. Descomprime el .zip y copia el archivo .otf o .ttf en la carpeta de fuentes de Oto (el botón de abajo la abre). También vale instalarla en Windows: clic derecho en el archivo → Instalar.")
        Note("3. En unos segundos Oto la usa; no hace falta reiniciar. Mientras tanto se usa Michroma, que se parece.")
        Text("Abrir la carpeta de fuentes de Oto", style = OtoType.rowSub, color = accent,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                runCatching {
                    val dir = java.io.File(io.github.iaguito22.oto.OtoDirs.filesDir, "fuentes").apply { mkdirs() }
                    java.awt.Desktop.getDesktop().open(dir)
                }
            }.padding(vertical = 4.dp))
    }
}

/** Las fuentes, marcando las que no están en este PC. */
@Composable
private fun FontChips(keys: List<String>, current: String, accent: Color, onPick: (String) -> Unit) {
    val labels = remember(keys) { keys.map { k -> FONT_NAMES.getValue(k) + if (k == "estilo" || io.github.iaguito22.oto.desktop.wallpaper.WidgetFonts.installed(k)) "" else " (no está)" } }
    Chips(labels, keys.indexOf(current), accent) { i -> onPick(keys[i]) }
}
private val CTL_STYLES = listOf("compacta", "pildora", "tarjeta", "botones", "vinilo")

/** Un apartado plegable: solo uno abierto a la vez para que la lista no sea eterna. */
@Composable
private fun Section(title: String, open: String, onOpen: (String) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val isOpen = open == title
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = if (isOpen) .045f else if (hovered) .035f else .02f))) {
        Row(Modifier.fillMaxWidth().hoverable(interaction).clickable(interactionSource = interaction, indication = null) { onOpen(if (isOpen) "" else title) }
            .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = OtoType.rowTitle, modifier = Modifier.weight(1f))
            Text(if (isOpen) "−" else "+", style = OtoType.rowTitle, color = Color.White.copy(alpha = .5f))
        }
        AnimatedVisibility(isOpen, enter = fadeIn(tween(160)) + expandVertically(), exit = fadeOut(tween(120)) + shrinkVertically()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable private fun Label(text: String) = Text(text, style = OtoType.rowSub, color = Color.White.copy(alpha = .62f))
@Composable private fun Note(text: String) = Text(text, style = OtoType.rowSub, color = Color.White.copy(alpha = .42f))

@Composable
private fun Range(label: String, value: Float, min: Float, max: Float, accent: Color, fmt: (Float) -> String, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = OtoType.rowSub, color = Color.White.copy(alpha = .85f), modifier = Modifier.weight(1f))
            Text(fmt(value), style = OtoType.rowSub, color = accent)
        }
        FineSlider(((value - min) / (max - min)).coerceIn(0f, 1f), { onChange(min + it * (max - min)) }, Modifier.fillMaxWidth(), label = label)
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = OtoType.rowSub, color = Color.White.copy(alpha = .85f), modifier = Modifier.weight(1f))
        Check(on, accent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<String>, selected: Int, accent: Color, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val bg by animateColorAsState(if (on) accent.copy(alpha = .26f) else Color.White.copy(alpha = if (hovered) .1f else .06f), tween(150), label = "chip")
            Box(Modifier.clip(CircleShape).background(bg).hoverable(interaction).clickable(interactionSource = interaction, indication = null) { onSelect(i) }
                .padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text(label, style = OtoType.rowSub, color = if (on) Color.White else Color.White.copy(alpha = .75f))
            }
        }
    }
}

@Composable
private fun PillButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = .06f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, style = OtoType.rowTitle, color = Color.White)
    }
}

private val LIGHT_SWATCHES = listOf("#8f6bff", "#ff4fa3", "#ffd23f", "#3fd0ff", "#4dff9e", "#ff6b3f", "#ffffff", "#2f6bff")
private val DARK_SWATCHES = listOf("#120f1c", "#000000", "#0b1a2b", "#1c0f14", "#0f1c12", "#1f1a0b", "#202020", "#140b24")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(current: String, dark: Boolean = false, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in if (dark) DARK_SWATCHES else LIGHT_SWATCHES) {
            val on = c.equals(current, ignoreCase = true)
            Box(Modifier.size(28.dp).clip(CircleShape).background(parseColor(c)).border(if (on) 2.dp else 1.dp, Color.White.copy(alpha = if (on) .9f else .2f), CircleShape).clickable { onPick(c) })
        }
    }
}

/** Los nueve sitios de la pantalla (arriba, centro, abajo × izquierda, centro, derecha). */
@Composable
private fun PositionGrid(pos: String, accent: Color, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in "tmb") Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in "lcr") {
                val v = "$r$c"; val on = v == pos
                Box(Modifier.size(width = 34.dp, height = 22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) accent.copy(alpha = .45f) else Color.White.copy(alpha = .07f)).clickable { onPick(v) })
            }
        }
    }
}
