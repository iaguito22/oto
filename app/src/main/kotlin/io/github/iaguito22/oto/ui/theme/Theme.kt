package io.github.iaguito22.oto.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colores por rol. El único color con tono es [accent], que sale de la carátula que suena. */
@Immutable
data class OtoColors(
    val base: Color,
    val veil: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val glass: Color,
    val field: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val dark: Boolean,
)

val DefaultAccent = Color(0xFF8FD3DB)

fun darkColors(accent: Color = DefaultAccent) = OtoColors(
    base = Color(0xFF0B0B0B),
    veil = Color(0x800B0B0B),
    text = Color.White,
    textMuted = Color.White.copy(alpha = 0.62f),
    textFaint = Color.White.copy(alpha = 0.36f),
    glass = Color(0xFF181818).copy(alpha = 0.56f),
    field = Color.White.copy(alpha = 0.08f),
    line = Color.White.copy(alpha = 0.09f),
    accent = accent,
    onAccent = Color(0xFF101010),
    dark = true,
)

fun lightColors(accent: Color = Color(0xFF2F6A72)) = OtoColors(
    base = Color(0xFFF4F4F4),
    veil = Color(0x99F7F7F7),
    text = Color(0xFF121212),
    textMuted = Color(0xFF121212).copy(alpha = 0.62f),
    textFaint = Color(0xFF121212).copy(alpha = 0.32f),
    glass = Color.White.copy(alpha = 0.6f),
    field = Color.Black.copy(alpha = 0.06f),
    line = Color.Black.copy(alpha = 0.07f),
    accent = accent,
    onAccent = Color.White,
    dark = false,
)

val LocalOtoColors = staticCompositionLocalOf { darkColors() }

object Oto {
    val colors: OtoColors @Composable get() = LocalOtoColors.current
}

/** Oto es oscura siempre (la carátula desenfocada es el fondo); el claro queda para cuando haga falta. */
@Composable
fun OtoTheme(accent: Color = DefaultAccent, dark: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (dark) darkColors(accent) else lightColors()
    CompositionLocalProvider(
        LocalOtoColors provides colors,
        LocalTextStyle provides OtoType.body.copy(color = colors.text),
        LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f)),
        content = content,
    )
}
