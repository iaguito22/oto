package io.github.iaguito22.oto.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iaguito22.oto.*
import io.github.iaguito22.oto.account.Session
import io.github.iaguito22.oto.desktop.playback.DesktopPlayerConnection
import io.github.iaguito22.oto.desktop.supportsDesktopShortcut
import io.github.iaguito22.oto.desktop.isWindowsDesktop
import io.github.iaguito22.oto.desktop.DesktopGraphics
import io.github.iaguito22.oto.importer.ImportState
import io.github.iaguito22.oto.playback.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Ajustes con el aspecto y los textos del móvil; lo exclusivo del ordenador (atajo, fondo, RAM) en su sitio. */
@Composable
internal fun SettingsSpace(prefs:Prefs,player:DesktopPlayerConnection,palette:ArtworkPalette,session:Session?,importState:ImportState,artworkBackground:Boolean,onArtworkBackground:(Boolean)->Unit,backdropOpacity:Float,onBackdropOpacity:(Float)->Unit,shortcut:String,shortcutIssue:String?,onShortcut:(String)->Unit,onAccount:()->Unit,onImport:()->Unit,onDevices:()->Unit) {
    val scope=rememberCoroutineScope()
    val accent=palette.primary
    val quality by prefs.quality.collectAsState(initial=QualityMode.Auto)
    val saver by prefs.dataSaver.collectAsState(initial=false)
    val ramSaver by prefs.ramSaver.collectAsState(initial=false)
    // Como en el móvil: aleatorio y bucle de Ajustes son los de las listas nuevas; cada lista recuerda los suyos.
    val shuffle by prefs.shuffle.collectAsState(initial=true)
    val loop by prefs.loop.collectAsState(initial=false)
    val beat by prefs.coverBeat.collectAsState(initial=true)
    val remaining by prefs.showRemaining.collectAsState(initial=false)
    val romanize by prefs.romanize.collectAsState(initial=true)
    val crossfade by prefs.crossfade.collectAsState(initial=DEFAULT_CROSSFADE)
    val smartMix by prefs.smartMix.collectAsState(initial=DEFAULT_SMART_MIX)
    val crossfadeSupported = remember(player) { player.supportsCrossfade }
    val np by player.state.collectAsState()
    val nowId=np.id
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        item {SettingsLink(MobileIcons.accountCircle,"Cuenta de YouTube Music",session?.let { listOfNotNull(it.name.ifEmpty { null },it.email?.ifEmpty { null }).joinToString(" · ").ifEmpty { "Has entrado" } } ?: "Sin cuenta: recomendaciones de visitante",accent,onAccount)}
        item {SettingsLink(MobileIcons.playlistAdd,"Importar de Spotify",when(importState){is ImportState.Running->"Importando ${importState.done} de ${importState.total}";is ImportState.Done->"Importación terminada";else->"Tus listas y tus Me gusta"},accent,onImport)}
        item {SettingsLink(Icons.Rounded.Devices,"Dispositivos","Conectar con Oto móvil en la red local",accent,onDevices)}
        item {
            SettingFrame(MobileIcons.highQuality,accent) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Calidad del audio",style=OtoType.rowTitle,modifier=Modifier.weight(1f))
                    ChoiceSelector(listOf("Auto","Máxima"),QualityMode.entries.indexOf(quality),78.dp,accent) { scope.launch { prefs.setQuality(QualityMode.entries[it]) } }
                }
                FadingText(if(quality==QualityMode.Auto) "La mejor calidad que aguante tu conexión. Se elige al empezar cada canción y nunca cambia a mitad de la canción."
                    else "Siempre la mejor calidad. Si la conexión va justa, la canción tarda más en empezar, pero la calidad no baja.")
            }
        }
        item {SettingsToggle(MobileIcons.dataSaver,"Ahorro de datos","Las canciones suenan en calidad baja (unos 48 kbps), salvo con la calidad en Máxima.",saver,accent){scope.launch{prefs.setDataSaver(!saver)}}}
        item {
            SettingFrame(MobileIcons.transitionFade,accent) {
                Text("Fundido entre canciones",style=OtoType.rowTitle)
                FadingText(when {
                    !crossfadeSupported -> "Disponible cuando FFmpeg y la salida de audio PCM están disponibles."
                    ramSaver -> "Desactivado mientras esté activo el ahorro de RAM."
                    crossfade==0 -> "Cada canción acaba del todo antes de que empiece la siguiente."
                    else -> "Los últimos $crossfade segundos de una canción suenan a la vez que el principio de la siguiente. Solo cuando acaba sola, no al pasarla tú. Usa más RAM y CPU: suenan dos canciones a la vez."
                })
                if(crossfadeSupported && !ramSaver) Box(Modifier.padding(top=8.dp)) {
                    ChoiceSelector(CROSSFADE_CHOICES.map { if(it==0) "No" else "$it s" },CROSSFADE_CHOICES.indexOf(crossfade).coerceAtLeast(0),60.dp,accent) { scope.launch { prefs.setCrossfade(CROSSFADE_CHOICES[it]) } }
                }
            }
        }
        item {
            SettingsToggle(
                MobileIcons.graphicEq,
                "Mezcla inteligente",
                when {
                    !crossfadeSupported -> "Disponible cuando FFmpeg y la salida de audio PCM están disponibles."
                    ramSaver -> "Desactivada mientras esté activo el ahorro de RAM."
                    smartMix -> "Curva en S y relevo progresivo de graves al mezclar para que no choquen bombos ni bajos."
                    else -> "Fundido clásico de igual potencia sin ecualización entre pistas."
                },
                smartMix && crossfadeSupported && !ramSaver,
                accent,
            ) {
                if (crossfadeSupported && !ramSaver) scope.launch { prefs.setSmartMix(!smartMix) }
            }
        }
        item {SettingsToggle(MobileIcons.shuffle,"Aleatorio","Las listas nuevas empiezan mezcladas. Cada una recuerda cómo la dejaste.",shuffle,accent){scope.launch{prefs.setShuffle(!shuffle)}}}
        item {SettingsToggle(MobileIcons.repeat,"Repetir la lista",if(loop) "Al acabar una lista nueva, vuelve a empezar." else "Al acabar una lista nueva, siguen canciones parecidas.",loop,accent){scope.launch{prefs.setLoop(!loop)}}}
        item {SettingsToggle(MobileIcons.graphicEq,"Carátula al ritmo","La carátula late un poco con los graves de lo que suena. Usa CPU y GPU mientras suena.",beat,accent){scope.launch{prefs.setCoverBeat(!beat)}}}
        item {SettingsToggle(MobileIcons.timer,"Tiempo que falta",if(remaining) "A la derecha de la barra, lo que falta (-1:23)." else "A la derecha de la barra, lo que dura (3:45).",remaining,accent){scope.launch{prefs.setShowRemaining(!remaining)}}}
        item {SettingsToggle(MobileIcons.translate,"Romanización","La letra en japonés, coreano o chino lleva debajo cómo se lee. También manteniendo pulsada la letra.",romanize,accent){scope.launch{prefs.setRomanize(!romanize)}}}
        item {SettingsToggle(MobileIcons.libraryMusic,"Fondo con la carátula","La carátula de lo que suena, de fondo. Usa GPU al dibujarlo y algo de CPU y RAM al cambiar de canción.",artworkBackground,accent){onArtworkBackground(!artworkBackground)}}
        if(artworkBackground) item {SettingFrame(MobileIcons.libraryMusic,accent) { Text("Intensidad del fondo · ${(backdropOpacity*100).toInt()} %",style=OtoType.rowTitle); Slider(backdropOpacity,onBackdropOpacity,colors=SliderDefaults.colors(thumbColor=accent,activeTrackColor=accent)) }}
        item {SettingsToggle(MobileIcons.hardDrive,"Ahorro de RAM","Modo agresivo: casi sin caché de carátulas ni de dibujo, devuelve al sistema la memoria que no usa y, con las ventanas ocultas, suelta todo lo posible. No prepara la siguiente canción y quita el fundido. Las portadas y el cambio de canción pueden tardar un poco más.",ramSaver,accent){scope.launch{prefs.setRamSaver(!ramSaver)}}}
        if(isWindowsDesktop) item { DedicatedGpuSetting(accent) }
        if(supportsDesktopShortcut) item { ShortcutSetting(shortcut,shortcutIssue,accent,onShortcut) }
        item { SpaceRow(nowId,accent) }
        item {SettingFrame(MobileIcons.settings,accent){Text("Atajos de teclado",style=OtoType.rowTitle);Text("Espacio: reproducir o pausar · Ctrl + ← / →: canción anterior o siguiente · Ctrl + ↑ / ↓: volumen · Ctrl + Mayús + M: cambiar reproductor · Ctrl + B: guardar",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))}}
        item {
            // jpackage pone la versión del instalador; al ejecutar desde Gradle no la hay.
            Text("Oto${System.getProperty("jpackage.app-version")?.let { " $it" }.orEmpty()} · GPL-3.0\nCon InnerTubeX y partes de Metrolist.\nLetras de lyricsplus, NetEase, LRCLIB y YouTube Music.\ngithub.com/iaguito22/oto",
                Modifier.padding(16.dp,24.dp),style=OtoType.rowSub,color=Color.White.copy(alpha=.3f))
        }
    }
}

/**
 * Lo que ocupa la caché en el ordenador. Se vacía con su botón y un segundo toque; lo de la canción que suena se
 * queda (se está leyendo) y las descargas no se tocan. Como en el móvil.
 */
@Composable private fun SpaceRow(playingId:String?,accent:Color) {
    val scope=rememberCoroutineScope()
    var used by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm){if(confirm){delay(3000);confirm=false}}
    LaunchedEffect(clearing){ if(!clearing) used=withContext(Dispatchers.IO){ OtoDirs.cacheDir.walkTopDown().filter{it.isFile}.sumOf{it.length()} } }
    SettingFrame(MobileIcons.hardDrive,accent) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Espacio en el ordenador",style=OtoType.rowTitle,modifier=Modifier.weight(1f))
            val fg by animateColorAsState(if(confirm) Danger else Color.White,tween(150),label="clearFg")
            val enabled=!clearing && (used ?: 0L)>0L
            Text(if(confirm)"Toca otra vez" else "Vaciar",style=OtoType.rowTitle,color=fg,
                modifier=Modifier.graphicsLayer { alpha=if(enabled) 1f else .4f }.clip(CircleShape).background(Color.White.copy(alpha=.06f))
                    .clickable(enabled=enabled) {
                        if(!confirm){confirm=true;return@clickable}
                        confirm=false;clearing=true
                        scope.launch { withContext(Dispatchers.IO){ clearCache(playingId) };clearing=false }
                    }.padding(horizontal=14.dp,vertical=8.dp))
        }
        Text(when { clearing->"Vaciando…"; used==null->"Midiendo…"; else->"${formatSize(used!!)} de caché. Lo descargado no cuenta ni se toca." },style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
    }
}

/** Borra la caché salvo lo de la canción que suena y lo que se está escribiendo ahora mismo. */
private fun clearCache(playingId:String?) {
    val keep=playingId?.let { id -> Tier.entries.map { it.key(id) } }.orEmpty()
    OtoDirs.cacheDir.walkBottomUp().filter { it.isFile }.forEach { f ->
        if(f.name.endsWith(".part") || keep.any { f.name.startsWith("$it.") }) return@forEach
        runCatching { f.delete() }
    }
}

private fun formatSize(n:Long)=if(n>=1L shl 30) "%.1f GB".format(n/(1L shl 30).toDouble()) else "${(n+(1L shl 19)) shr 20} MB"

/** Un texto que cambia con un fundido, como las explicaciones del móvil. */
@Composable private fun FadingText(text:String) {
    AnimatedContent(text,transitionSpec={ fadeIn(tween(150,delayMillis=40)) togetherWith fadeOut(tween(100)) },label="settingBody") {
        Text(it,style=OtoType.rowSub,color=Color.White.copy(alpha=.5f),modifier=Modifier.padding(top=2.dp))
    }
}

/** El selector del móvil: un indicador que se desliza a la opción elegida. */
@Composable private fun ChoiceSelector(options:List<String>,selected:Int,width:Dp,accent:Color,onSelect:(Int)->Unit) {
    val at by animateFloatAsState(selected.toFloat(),Motion.toggle(),label="choice")
    Row(Modifier.height(36.dp).clip(CircleShape).background(Color.White.copy(alpha=.06f)).drawBehind {
        val w=size.width/options.size
        drawRoundRect(accent.copy(alpha=.22f),Offset(w*at+3.dp.toPx(),3.dp.toPx()),Size(w-6.dp.toPx(),size.height-6.dp.toPx()),CornerRadius(size.height))
    }) {
        options.forEachIndexed { i,label ->
            val interaction=remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(Modifier.width(width).height(36.dp).clip(CircleShape).hoverable(interaction).clickable(interactionSource=interaction,indication=null){onSelect(i)},contentAlignment=Alignment.Center) {
                val fg by animateColorAsState(if(i==selected) Color.White else Color.White.copy(alpha=if(hovered) .8f else .5f),tween(150),label="choiceFg")
                Text(label,style=OtoType.rowTitle.copy(fontSize=OtoType.rowSub.fontSize*1.05f),color=fg)
            }
        }
    }
}

/** La marca del móvil: un aro vacío que se llena de acento con un pequeño rebote. */
@Composable private fun Check(on:Boolean,accent:Color) {
    val f by animateFloatAsState(if(on)1f else 0f,Motion.toggle(),label="check")
    Box(Modifier.size(26.dp).graphicsLayer { val s=1f+.12f*(f*(1f-f)*4f);scaleX=s;scaleY=s }.clip(CircleShape)
        .border(1.5.dp,lerp(Color.White.copy(alpha=.36f),accent,f.coerceIn(0f,1f)),CircleShape).background(accent.copy(alpha=f.coerceIn(0f,1f))),contentAlignment=Alignment.Center) {
        Icon(MobileIcons.check,null,Modifier.size(18.dp).graphicsLayer { val s=f.coerceIn(0f,1.2f);scaleX=s;scaleY=s;alpha=f.coerceIn(0f,1f) },tint=Color(0xFF101010))
    }
}

/** El icono de un ajuste: en una pastilla redonda del color de lo que suena. */
@Composable private fun SettingIcon(icon:ImageVector,accent:Color) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha=.16f)),contentAlignment=Alignment.Center) {
        Icon(icon,null,Modifier.size(22.dp),tint=accent)
    }
}

@Composable private fun ShortcutSetting(shortcut:String,issue:String?,accent:Color,onShortcut:(String)->Unit) {
    var recording by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val focus=remember { FocusRequester() }
    LaunchedEffect(recording) { if(recording) focus.requestFocus() }
    SettingFrame(MobileIcons.settings,accent) {
        Text("Atajo global",style=OtoType.rowTitle)
        Text("Muestra u oculta todas las ventanas de Oto.",style=OtoType.rowSub,color=Color.White.copy(alpha=.5f))
        Box(Modifier.padding(top=6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha=if(recording).12f else .06f))
            .focusRequester(focus).onPreviewKeyEvent { e ->
                if(!recording) false else {
                    if(e.type==KeyEventType.KeyDown) {
                        if(e.key==Key.Escape) { recording=false;hint=null }
                        else {
                            val code=(e.nativeKeyEvent as? java.awt.event.KeyEvent)?.keyCode ?: e.key.keyCode.toInt()
                            val modifiers=buildList { if(e.isCtrlPressed)add("CTRL");if(e.isAltPressed)add("ALT");if(e.isShiftPressed)add("SHIFT");if(e.isMetaPressed)add("SUPER") }
                            if(modifiers.isNotEmpty() && (code in 65..90 || code in 48..57)) {
                                onShortcut((modifiers+code.toChar().toString()).joinToString(" + "));recording=false;hint=null
                            } else if(code in 65..90 || code in 48..57) hint="Añade Ctrl, Alt, Mayús o la tecla Windows."
                        }
                    }
                    true
                }
            }.focusable().clickable { recording=true;hint=null }.padding(horizontal=18.dp,vertical=10.dp)) {
            Text(if(recording) "Pulsa la combinación… (Esc para cancelar)" else "$shortcut  ·  Cambiar",style=OtoType.rowSub,color=Color.White)
        }
        (hint ?: issue)?.let { Text(it,style=OtoType.rowSub,color=MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun SettingFrame(icon:ImageVector,accent:Color,content:@Composable ColumnScope.()->Unit) {
    Row(Modifier.fillMaxWidth().cascadeIn().padding(horizontal=12.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
        SettingIcon(icon,accent);Column(Modifier.weight(1f).padding(top=2.dp),verticalArrangement=Arrangement.spacedBy(4.dp),content=content)
    }
}

@Composable private fun HoverRow(onClick:()->Unit,content:@Composable RowScope.()->Unit) {
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight by animateFloatAsState(if(hovered).055f else 0f,tween(180),label="settingsHover")
    Row(Modifier.fillMaxWidth().cascadeIn().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha=highlight)).hoverable(interaction).clickable(interactionSource=interaction,indication=null,onClick=onClick).padding(horizontal=12.dp,vertical=12.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp),content=content)
}

@Composable private fun SettingsLink(icon:ImageVector,title:String,subtitle:String,accent:Color,onClick:()->Unit) {
    HoverRow(onClick) {
        SettingIcon(icon,accent);Column(Modifier.weight(1f)){Text(title,style=OtoType.rowTitle);Text(subtitle,style=OtoType.rowSub,color=Color.White.copy(alpha=.5f),maxLines=2,overflow=TextOverflow.Ellipsis)};Icon(MobileIcons.chevronRight,null,Modifier.size(18.dp),tint=Color.White.copy(alpha=.4f))
    }
}

/** La gráfica dedicada: se elige al arrancar, así que el cambio vale al volver a abrir Oto. */
@Composable private fun DedicatedGpuSetting(accent:Color) {
    var on by remember { mutableStateOf(runCatching { DesktopGraphics.dedicated }.getOrDefault(false)) }
    val subtitle = when {
        on != DesktopGraphics.activeDedicated -> "Se aplica al volver a abrir Oto (Salir de Oto desde la bandeja)."
        on -> "Dibuja con la gráfica dedicada: sin los parones que tienen algunos portátiles con dos gráficas. Gasta más batería y calienta más."
        else -> "Dibuja con la gráfica que elige Windows, normalmente la integrada, que gasta menos. En algunos portátiles con dos gráficas, la letra y las animaciones se paran un instante cada pocos segundos: actívalo si te pasa."
    }
    SettingsToggle(Icons.Rounded.Tune,"Gráfica dedicada",subtitle,on,accent) {
        on = !on
        DesktopGraphics.dedicated = on
    }
}

@Composable private fun SettingsToggle(icon:ImageVector,title:String,subtitle:String,on:Boolean,accent:Color,onClick:()->Unit) {
    HoverRow(onClick) {
        SettingIcon(icon,accent);Column(Modifier.weight(1f)){Text(title,style=OtoType.rowTitle);FadingText(subtitle)};Check(on,accent)
    }
}
