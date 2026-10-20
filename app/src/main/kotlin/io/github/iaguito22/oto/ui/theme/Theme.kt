package io.github.iaguito22.oto.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colores por rol. El único color con tono es [accent], que sale de la carátula que suena.
 *
 * El acento va dentro de un State: al cambiar de canción se funde durante casi un segundo, y si cada fotograma diera un
 * OtoColors nuevo se recompondría la app entera (también el Menú, montado aunque no se vea) justo mientras entra la
 * carátula. Así solo se recompone o se redibuja lo que lo lee.
 */
@Stable
class OtoColors(
    val base: Color,
    val veil: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val glass: Color,
    val field: Color,
    val line: Color,
    private val accentState: State<Color>,
    val onAccent: Color,
    val dark: Boolean,
) {
    val accent: Color get() = accentState.value
}

val DefaultAccent = Color(0xFF8FD3DB)

fun darkColors(accent: State<Color> = mutableStateOf(DefaultAccent)) = OtoColors(
    base = Color(0xFF0B0B0B),
    veil = Color(0x800B0B0B),
    text = Color.White,
    textMuted = Color.White.copy(alpha = 0.62f),
    textFaint = Color.White.copy(alpha = 0.36f),
    glass = Color(0xFF181818).copy(alpha = 0.56f),
    field = Color.White.copy(alpha = 0.08f),
    line = Color.White.copy(alpha = 0.09f),
    accentState = accent,
    onAccent = Color(0xFF101010),
    dark = true,
)

fun lightColors(accent: State<Color> = mutableStateOf(Color(0xFF2F6A72))) = OtoColors(
    base = Color(0xFFF4F4F4),
    veil = Color(0x99F7F7F7),
    text = Color(0xFF121212),
    textMuted = Color(0xFF121212).copy(alpha = 0.62f),
    textFaint = Color(0xFF121212).copy(alpha = 0.32f),
    glass = Color.White.copy(alpha = 0.6f),
    field = Color.Black.copy(alpha = 0.06f),
    line = Color.Black.copy(alpha = 0.07f),
    accentState = accent,
    onAccent = Color.White,
    dark = false,
)

val LocalOtoColors = staticCompositionLocalOf { darkColors() }

object Oto {
    val colors: OtoColors @Composable get() = LocalOtoColors.current
}

/** Oto es oscura siempre (la carátula desenfocada es el fondo); el claro queda para cuando haga falta. */
@Composable
fun OtoTheme(accent: State<Color> = mutableStateOf(DefaultAccent), dark: Boolean = true, content: @Composable () -> Unit) {
    val colors = remember(accent, dark) { if (dark) darkColors(accent) else lightColors() }
    CompositionLocalProvider(
        LocalOtoColors provides colors,
        LocalTextStyle provides OtoType.body.copy(color = colors.text),
        LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f)),
        content = content,
    )
}
