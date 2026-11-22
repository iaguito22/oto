package io.github.iaguito22.oto.desktop

import java.awt.event.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopTypingShortcutsTest {
    @Test
    fun spaceIsHandledBeforeFocusedControlsCanActivate() {
        assertEquals(DesktopTypingAction.PlayPause, decideDesktopTypingShortcut(KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE))
        assertEquals(DesktopTypingAction.Consume, decideDesktopTypingShortcut(KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE))
        assertEquals(DesktopTypingAction.Consume, decideDesktopTypingShortcut(KeyEvent.KEY_TYPED, character = ' '))
    }

    @Test
    fun holdingSpaceOnlyTogglesOnce() {
        assertEquals(DesktopTypingAction.Consume, decideDesktopTypingShortcut(
            KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, spaceHeld = true,
        ))
        assertEquals(DesktopTypingAction.Consume, decideDesktopTypingShortcut(
            KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, acceptsTyping = true, spaceHeld = true,
        ))
    }

    @Test
    fun textFieldsAndShortcutRecordingKeepTheirInput() {
        assertEquals(null, decideDesktopTypingShortcut(
            KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, acceptsTyping = true,
        ))
        assertEquals(null, decideDesktopTypingShortcut(
            KeyEvent.KEY_TYPED, character = ' ', acceptsTyping = true,
        ))
        assertEquals(null, decideDesktopTypingShortcut(
            KeyEvent.KEY_TYPED, character = 'ñ', acceptsTyping = true,
        ))
    }

    @Test
    fun searchUsesTheCharacterProducedByTheKeyboard() {
        for (character in listOf('a', 'Ñ', 'é', '7', '京')) {
            assertEquals(DesktopTypingAction.Search(character), decideDesktopTypingShortcut(
                KeyEvent.KEY_TYPED, character = character,
            ))
        }
    }

    @Test
    fun existingCombinationsAndOtherKeysPassThrough() {
        assertEquals(null, decideDesktopTypingShortcut(
            KeyEvent.KEY_TYPED, character = 'k', hasShortcutModifier = true,
        ))
        assertEquals(null, decideDesktopTypingShortcut(KeyEvent.KEY_PRESSED, KeyEvent.VK_F1))
        assertEquals(null, decideDesktopTypingShortcut(KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE))
        assertEquals(null, decideDesktopTypingShortcut(KeyEvent.KEY_PRESSED, KeyEvent.VK_A, 'a'))
        assertEquals(null, decideDesktopTypingShortcut(KeyEvent.KEY_TYPED, character = '?'))
    }
}
