package app.throwback.timelimit

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.throwback.timelimit.data.DataStoreStateStore
import app.throwback.timelimit.ui.GuardOffPin
import app.throwback.timelimit.ui.LockOverlay
import app.throwback.timelimit.ui.RemainingChip
import app.throwback.timelimit.ui.TimeLimitSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/*
 * Entry points of the timelimit area for the rest of the app: TimeLimitModule makes the app-wide
 * DefaultTimeLimitController (the rules are in engine/TimeLimitEngine.kt, persistence and ticking in
 * the controller), and the composables below show its screens (ui/).
 */

internal object TimeLimitModule {
    @Volatile
    private var instance: DefaultTimeLimitController? = null

    /** The app-wide controller, made on the first call (ThrowbackApp's start); later calls return the same one. */
    fun create(context: Context): DefaultTimeLimitController =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(app: Context) = DefaultTimeLimitController(
        clock = AndroidClock.create(app),
        store = DataStoreStateStore.create(app),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ioDispatcher = Dispatchers.IO,
        hashDispatcher = Dispatchers.Default,
        isAppVisible = { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
    )
}

/**
 * Full-screen lock UI drawn over the Instagram screen. Draws nothing unless the state is Locked.
 * Handles the 5-minute wait, PIN entry, +5 minutes, locked-until-midnight and forgot-PIN screens.
 * [backEnabled] false leaves system Back alone (while Settings covers the lock).
 */
@Composable
internal fun TimeLimitOverlay(
    controller: DefaultTimeLimitController,
    onOpenMessagesOnly: () -> Unit,
    onCloseApp: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
) {
    LockOverlay(controller, onOpenMessagesOnly, onCloseApp, modifier, backEnabled)
}

/** Small "18 min left" chip. Draws nothing when the limit is disabled. */
@Composable
internal fun TimeRemainingChip(controller: DefaultTimeLimitController, modifier: Modifier = Modifier) {
    RemainingChip(controller, modifier)
}

/**
 * Settings entry for the limit: first-time setup (limit + PIN) or manage an existing limit
 * (PIN-protected). [onLimitSet] instead of [onClose] once a new limit is on.
 */
@Composable
internal fun TimeLimitSettingsScreen(controller: DefaultTimeLimitController, onClose: () -> Unit, onLimitSet: () -> Unit = onClose) {
    TimeLimitSettings(controller, onClose, onLimitSet)
}

/**
 * Disconnecting the Instagram app while a daily limit is on: the PIN, then it happens tomorrow (the
 * controller's view then has pendingGuardOff, and guardOffDue on that day).
 */
@Composable
internal fun GuardOffPinScreen(controller: DefaultTimeLimitController, onScheduled: () -> Unit, onBack: () -> Unit) {
    GuardOffPin(controller, onScheduled, onBack)
}
