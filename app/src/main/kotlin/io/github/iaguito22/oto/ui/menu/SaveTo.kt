package io.github.iaguito22.oto.ui.menu

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iaguito22.oto.Heard
import io.github.iaguito22.oto.OtoApp
import io.github.iaguito22.oto.R
import io.github.iaguito22.oto.ui.theme.Motion
import io.github.iaguito22.oto.ui.theme.Oto
import io.github.iaguito22.oto.ui.theme.OtoType
import io.github.iaguito22.oto.ui.theme.Radius
import io.github.iaguito22.oto.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lo que se guarda: una canción o un álbum/lista entero. [art]/[title]/[sub] encabezan la tarjeta. */
data class Saving(val songs: List<Heard>, val title: String, val sub: String, val art: String?) {
    constructor(song: Heard) : this(listOf(song), song.title, song.artist, song.artwork)
}

/**
 * Guardar una canción, o todas las de un álbum: Me gusta y cada una de tus listas, cada una con su marca. Tocar una
 * la pone o la quita al momento (no hay que confirmar nada); abajo se crea una lista nueva con lo guardado dentro.
 * Con varias, la marca solo sale si están todas; tocar añade las que falten (o, si están todas, las quita).
 */
@Composable
fun ColumnScope.SaveTo(what: Saving, onDone: () -> Unit) {
    val c = Oto.colors
    val prefs = (LocalContext.current.applicationContext as OtoApp).prefs
    val liked by prefs.likedIds.collectAsStateWithLifecycle(initialValue = null)
    val playlists by prefs.playlists.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val tick: (Boolean) -> Unit = { on -> haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff) }
    val songs = what.songs
    val ids = remember(songs) { songs.mapTo(HashSet()) { it.id } }

    Row(Modifier.padding(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Art(what.art, 48.dp, Radius.sm)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(what.title, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(what.sub, style = OtoType.rowSub, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Una canción (lo que suena, al mantener la carátula): también se puede mandar a alguien.
        songs.singleOrNull()?.let { h ->
            val context = LocalContext.current
            RoundIcon(R.drawable.ic_share) { shareSong(context, h.id, h.title, h.artist) }
        }
    }
    Text(
        "GUARDAR EN", style = OtoType.label, color = c.textMuted,
        modifier = Modifier.padding(start = Space.md, top = Space.sm, bottom = Space.xs),
    )
    // Hasta saber qué hay guardado no se pinta ninguna marca: si no, se verían encenderse todas a la vez.
    val likedNow = liked ?: return
    val lists = playlists ?: return
    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
        val isLiked = likedNow.containsAll(ids)
        ToggleRow({ LikedArt(40.dp) }, "Me gusta", null, isLiked) {
            tick(!isLiked)
            scope.launch { prefs.setLikedAll(songs, !isLiked) }
        }
        lists.forEach { p ->
            val inside = remember(p.songs, ids) { p.songs.count { it.id in ids } == ids.size }
            ToggleRow({ Mosaic(p.songs, 40.dp) }, p.name, count(p.songs.size), inside) {
                tick(!inside)
                scope.launch { if (inside) prefs.removeAllFromPlaylist(p.id, ids) else prefs.addAllToPlaylist(p.id, songs) }
            }
        }
        // Una canción suelta empieza sin nombre; un álbum propone el suyo.
        NewListRow(suggested = if (songs.size > 1) what.title else "", onCreate = { name ->
            tick(true)
            scope.launch { prefs.createPlaylist(name, songs) }
        })
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.sm), horizontalArrangement = Arrangement.End) {
        Pill("Listo", onDone, icon = R.drawable.ic_check)
    }
}

@Composable
fun ToggleRow(art: @Composable () -> Unit, title: String, sub: String?, on: Boolean, onClick: () -> Unit) {
    val c = Oto.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(remember { MutableInteractionSource() }, ripple(color = c.text), onClick = onClick)
            .padding(horizontal = Space.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art()
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = OtoType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = OtoType.rowSub, color = c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.width(Space.sm))
        Check(on)
    }
}

/** La marca: un aro vacío que se llena de acento con un pequeño rebote. */
@Composable
internal fun Check(on: Boolean) {
    val c = Oto.colors
    val f by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "check")
    Box(
        Modifier
            .size(26.dp)
            .graphicsLayer { val s = 1f + 0.12f * (f * (1f - f) * 4f); scaleX = s; scaleY = s }
            .clip(CircleShape)
            .border(1.5.dp, lerp(c.textFaint, c.accent, f.coerceIn(0f, 1f)), CircleShape)
            .background(c.accent.copy(alpha = f.coerceIn(0f, 1f))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_check), null,
            Modifier.size(18.dp).graphicsLayer { val s = f.coerceIn(0f, 1.2f); scaleX = s; scaleY = s; alpha = f.coerceIn(0f, 1f) },
            tint = c.onAccent,
        )
    }
}

/** «Nueva lista…» se convierte en el campo del nombre sin salir de la tarjeta. */
@Composable
fun NewListRow(suggested: String, label: String = "Nueva lista…", icon: Int = R.drawable.ic_add, onCreate: (String) -> Unit) {
    val c = Oto.colors
    var naming by remember { mutableStateOf(false) }
    AnimatedContent(
        targetState = naming,
        transitionSpec = { fadeIn(tween(Motion.EFFECTS, delayMillis = 40)) togetherWith fadeOut(tween(Motion.EFFECTS_FAST)) using SizeTransform(clip = false) },
        label = "newList",
    ) { editing ->
        if (!editing) Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .clickable(remember { MutableInteractionSource() }, ripple(color = c.text)) { naming = true }
                .padding(horizontal = Space.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.md)).background(c.accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(22.dp), tint = c.accent)
            }
            Spacer(Modifier.width(Space.md))
            Text(label, style = OtoType.rowTitle, color = c.accent)
        } else {
            // Con nombre propuesto, el cursor al final: lo normal es dejarlo o añadirle algo.
            var name by remember { mutableStateOf(TextFieldValue(suggested, TextRange(suggested.length))) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(120); focus.requestFocus() }
            val ok = name.text.isNotBlank()
            val done = { if (ok) { onCreate(name.text); naming = false } }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.xs, vertical = Space.xs).height(50.dp).clip(CircleShape).background(c.field)
                    .padding(start = 18.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    val style = OtoType.body.copy(fontSize = OtoType.body.fontSize * 1.07f)
                    if (name.text.isEmpty()) Text("Ponle un nombre", style = style, color = c.textFaint)
                    BasicTextField(
                        value = name,
                        onValueChange = { name = if (it.text.length <= 60) it else it.copy(text = it.text.take(60)) },
                        singleLine = true,
                        textStyle = style.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                        keyboardActions = KeyboardActions(onDone = { done() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .graphicsLayer { alpha = if (ok) 1f else 0.4f }
                        .clip(CircleShape)
                        .background(c.accent)
                        .clickable(remember { MutableInteractionSource() }, ripple(color = c.onAccent)) { done() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = c.onAccent)
                }
            }
        }
    }
}

/** La portada de Me gusta: un corazón sobre el acento, que cambia con lo que suena. */
@Composable
fun LikedArt(size: Dp, modifier: Modifier = Modifier) {
    val c = Oto.colors
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(if (size < 64.dp) Radius.sm else Radius.md))
            .background(Brush.linearGradient(listOf(c.accent, lerp(c.accent, c.base, 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_favorite), null, Modifier.size(size * 0.42f), tint = c.onAccent)
    }
}

/** La portada de una lista: las cuatro primeras carátulas en cuadrícula (o la primera, si hay menos). */
@Composable
fun Mosaic(songs: List<Heard>, size: Dp) {
    val c = Oto.colors
    val arts = songs.mapNotNull { it.artwork }.distinct()
    Box(Modifier.size(size).clip(RoundedCornerShape(if (size < 64.dp) Radius.sm else Radius.md)).background(c.field), contentAlignment = Alignment.Center) {
        when {
            arts.size >= 4 -> Column {
                Row { Art(arts[0], size / 2, 0.dp); Art(arts[1], size / 2, 0.dp) }
                Row { Art(arts[2], size / 2, 0.dp); Art(arts[3], size / 2, 0.dp) }
            }
            arts.isNotEmpty() -> Art(arts[0], size, 0.dp)
            else -> Icon(painterResource(R.drawable.ic_queue_music), null, Modifier.size(size * 0.3f), tint = c.textFaint)
        }
    }
}

fun count(n: Int) = if (n == 1) "1 canción" else "$n canciones"

/**
 * Una tarjeta que sube desde abajo sobre un velo; tocar el velo la cierra. Guarda lo último que enseñó para
 * poder irse con ello puesto. [cardModifier]: fuera de una hoja, los insets (barra de gestos y teclado).
 */
@Composable
fun <T : Any> Floating(value: T?, onDismiss: () -> Unit, cardModifier: Modifier = Modifier, content: @Composable ColumnScope.(T) -> Unit) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(value != null, enter = fadeIn(tween(Motion.EFFECTS)), exit = fadeOut(tween(Motion.EFFECTS))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
        }
        AnimatedVisibility(
            value != null,
            modifier = Modifier.align(Alignment.BottomCenter).then(cardModifier),
            enter = fadeIn(tween(Motion.EFFECTS)) + slideInVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) { it / 3 } +
                scaleIn(spring(dampingRatio = 0.82f, stiffness = 420f), initialScale = 0.94f),
            exit = fadeOut(tween(Motion.EFFECTS_FAST)) + slideOutVertically(tween(Motion.SPATIAL_FAST, easing = Motion.EmphasizedAccelerate)) { it / 4 } +
                scaleOut(tween(Motion.SPATIAL_FAST), targetScale = 0.96f),
        ) {
            Column(
                Modifier
                    .padding(Space.md)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.xl))
                    .background(Color(0xFF1C1C1C))
                    .background(Oto.colors.accent.copy(alpha = 0.06f))
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .padding(Space.sm),
            ) { last?.let { content(it) } }
        }
    }
}
