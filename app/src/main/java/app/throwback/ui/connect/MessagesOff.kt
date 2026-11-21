package app.throwback.ui.connect

import androidx.compose.runtime.Composable

/**
 * Whether disconnecting the Instagram app needs the PIN (a daily limit is on), and when a requested
 * disconnect happens ([offAt], trusted ms; null: none asked for).
 */
data class MessagesOffState(val limitOn: Boolean = false, val offAt: Long? = null)

/**
 * Provided by the app (the time limit owns the trusted clock and the PIN): "Disconnect Instagram"
 * always takes effect 24 hours after it is asked for, and needs the PIN while a daily limit is on
 * (DECISIONS.md #27b). [schedule] asks without a PIN (no limit on); [pinScreen] asks with one.
 */
class MessagesOffHooks(
    val state: @Composable () -> MessagesOffState,
    val cancel: () -> Unit,
    val schedule: suspend () -> Unit,
    val pinScreen: @Composable (onScheduled: () -> Unit, onBack: () -> Unit) -> Unit,
)
