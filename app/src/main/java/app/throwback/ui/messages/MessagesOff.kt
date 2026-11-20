package app.throwback.ui.messages

import androidx.compose.runtime.Composable
import java.time.LocalDate

/** Whether turning Messages & calls off needs the PIN (a daily limit is on), and any scheduled turn-off. */
data class MessagesOffState(val limitOn: Boolean = false, val pendingDate: LocalDate? = null)

/**
 * Provided by the app (the time limit owns the PIN): turning Messages & calls off while a daily limit
 * is on needs the PIN and takes effect the next day (DECISIONS #27b).
 */
class MessagesOffHooks(
    val state: @Composable () -> MessagesOffState = { MessagesOffState() },
    val cancel: () -> Unit = {},
    val pinScreen: @Composable (onScheduled: () -> Unit, onBack: () -> Unit) -> Unit = { _, onBack -> onBack() },
)
