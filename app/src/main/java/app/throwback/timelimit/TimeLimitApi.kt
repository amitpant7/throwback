package app.throwback.timelimit

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.throwback.core.LockState
import app.throwback.core.TimeLimitController
import app.throwback.core.WebMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * STUBS owned by the timelimit agent: replace the bodies, keep the signatures.
 */

object TimeLimitModule {
    /** Called once by ThrowbackApp; return the app-wide controller. */
    fun create(context: Context): TimeLimitController = object : TimeLimitController {
        override val state: StateFlow<LockState> = MutableStateFlow(LockState.Disabled)
        override fun onForeground(mode: WebMode) = Unit
        override fun onBackground() = Unit
    }
}

/**
 * Full-screen lock UI drawn over the Instagram screen. Draws nothing unless the state is Locked.
 * Handles the 5-minute wait, PIN entry, +5 minutes, locked-until-midnight and forgot-PIN screens.
 */
@Composable
fun TimeLimitOverlay(
    controller: TimeLimitController,
    onOpenMessagesOnly: () -> Unit,
    onCloseApp: () -> Unit,
    modifier: Modifier = Modifier,
) = Unit

/** Small "18 min left" chip. Draws nothing when the limit is disabled. */
@Composable
fun TimeRemainingChip(controller: TimeLimitController, modifier: Modifier = Modifier) = Unit

/** Settings entry for the limit: first-time setup (limit + PIN) or manage an existing limit (PIN-protected). */
@Composable
fun TimeLimitSettingsScreen(controller: TimeLimitController, onClose: () -> Unit) = Unit
