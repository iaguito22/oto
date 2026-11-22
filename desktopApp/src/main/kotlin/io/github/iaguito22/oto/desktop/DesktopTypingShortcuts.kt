package io.github.iaguito22.oto.desktop

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.swing.SkiaSwingLayer
import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.JDialog
import javax.swing.SwingUtilities

internal sealed interface DesktopTypingAction {
    data object PlayPause : DesktopTypingAction
    data object Consume : DesktopTypingAction
    data class Search(val character: Char) : DesktopTypingAction
}

internal fun decideDesktopTypingShortcut(
    id: Int,
    keyCode: Int = KeyEvent.VK_UNDEFINED,
    character: Char = KeyEvent.CHAR_UNDEFINED,
    hasShortcutModifier: Boolean = false,
    acceptsTyping: Boolean = false,
    spaceHeld: Boolean = false,
): DesktopTypingAction? = when {
    spaceHeld && id == KeyEvent.KEY_RELEASED && keyCode == KeyEvent.VK_SPACE -> DesktopTypingAction.Consume
    acceptsTyping -> null
    id == KeyEvent.KEY_PRESSED && keyCode == KeyEvent.VK_SPACE ->
        if (spaceHeld) DesktopTypingAction.Consume else DesktopTypingAction.PlayPause
    id == KeyEvent.KEY_RELEASED && keyCode == KeyEvent.VK_SPACE -> DesktopTypingAction.Consume
    id == KeyEvent.KEY_TYPED && character == ' ' -> DesktopTypingAction.Consume
    id == KeyEvent.KEY_TYPED && !hasShortcutModifier && character.isLetterOrDigit() ->
        DesktopTypingAction.Search(character)
    else -> null
}

internal val LocalDesktopShortcutCapture = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

internal class DesktopTypingShortcuts(val playPause: () -> Unit, val typeToSearch: (Char) -> Unit)

@OptIn(ExperimentalSkikoApi::class)
@Composable
internal fun rememberDesktopTypingShortcuts(window: Window, shortcuts: DesktopTypingShortcuts?): (Boolean) -> Unit {
    val current by rememberUpdatedState(shortcuts)
    val capture = remember(window) { mutableStateOf(false) }

    DisposableEffect(window) {
        val keyboard = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        var spaceHeld = false
        // AWT entrega la pulsación, el carácter resultante y la suelta por separado.
        // Interceptar antes de Compose también cubre las capas de menús de la ventana.
        val dispatcher = KeyEventDispatcher { event ->
            val actions = current ?: return@KeyEventDispatcher false
            val source = event.component ?: return@KeyEventDispatcher false
            var target = (source as? Window) ?: SwingUtilities.getWindowAncestor(source)
            val components = generateSequence(source, Component::getParent).takeWhile { it !is Window }
            // Los diálogos nativos de selección de archivos conservan su teclado.
            if (target is JDialog && target !is ComposeDialog && components.none { it is SkiaLayer || it is SkiaSwingLayer }) {
                return@KeyEventDispatcher false
            }
            while (target is JDialog) target = target.owner
            if (target != window) {
                spaceHeld = false
                return@KeyEventDispatcher false
            }
            val acceptsTyping = capture.value || components.any { it.inputMethodRequests != null }
            val action = decideDesktopTypingShortcut(
                event.id, event.keyCode, event.keyChar,
                event.isControlDown || event.isAltDown || event.isAltGraphDown || event.isMetaDown,
                acceptsTyping, spaceHeld,
            )
            if (event.id == KeyEvent.KEY_RELEASED && event.keyCode == KeyEvent.VK_SPACE) spaceHeld = false
            when (action) {
                DesktopTypingAction.PlayPause -> { spaceHeld = true; actions.playPause() }
                is DesktopTypingAction.Search -> actions.typeToSearch(action.character)
                else -> Unit
            }
            action != null
        }
        val resetSpace = java.beans.PropertyChangeListener { spaceHeld = false }
        keyboard.addPropertyChangeListener("focusedWindow", resetSpace)
        keyboard.addKeyEventDispatcher(dispatcher)
        onDispose {
            keyboard.removeKeyEventDispatcher(dispatcher)
            keyboard.removePropertyChangeListener("focusedWindow", resetSpace)
        }
    }
    return remember(capture) { { recording -> capture.value = recording } }
}

/** Una consulta iniciada desde otro control se inserta con el cursor al final. */
@Composable
internal fun DesktopSearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier,
    textStyle: TextStyle,
    cursorBrush: Brush,
    decorationBox: @Composable (@Composable () -> Unit) -> Unit,
) {
    var editing by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    val value = if (editing.text == query) editing else TextFieldValue(query, TextRange(query.length))
    SideEffect { editing = value }
    BasicTextField(
        value, { editing = it; if (it.text != query) onQuery(it.text) }, modifier,
        textStyle = textStyle, cursorBrush = cursorBrush, singleLine = true, decorationBox = decorationBox,
    )
}
