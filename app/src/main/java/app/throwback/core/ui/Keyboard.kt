package app.throwback.core.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * The keyboard's Done for a text field: hides the keyboard, keeps the field focused, then [then].
 * Clearing focus here instead would, with a hardware keyboard, move focus to the screen's first
 * button (Back), and the same Enter press, released, would press it and close the screen.
 */
@Composable
internal fun doneHidesKeyboard(then: () -> Unit = {}): KeyboardActions {
    val keyboard = LocalSoftwareKeyboardController.current
    return KeyboardActions(onDone = {
        keyboard?.hide()
        then()
    })
}
