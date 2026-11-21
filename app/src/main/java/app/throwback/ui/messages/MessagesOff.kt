package app.throwback.ui.messages

import androidx.compose.runtime.Composable
import java.time.LocalDate

/** Whether disconnecting the Instagram app needs the PIN (a daily limit is on), and any scheduled disconnect. */
data class MessagesOffState(val limitOn: Boolean = false, val pendingDate: LocalDate? = null)

/**
 * Provided by the app (the time limit owns the PIN): disconnecting the Instagram app while a daily
 * limit is on needs the PIN and takes effect the next day (DECISIONS #27b).
 */
class MessagesOffHooks(
    val state: @Composable () -> MessagesOffState,
    val cancel: () -> Unit,
    val pinScreen: @Composable (onScheduled: () -> Unit, onBack: () -> Unit) -> Unit,
)
