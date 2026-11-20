package app.throwback.timelimit

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.throwback.core.TimeLimitController
import app.throwback.timelimit.data.DataStoreStateStore
import app.throwback.timelimit.ui.LockOverlay
import app.throwback.timelimit.ui.RemainingChip
import app.throwback.timelimit.ui.TimeLimitSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/*
 * Public entry points of the timelimit area, used by core/AppRoot.kt (signatures fixed by the
 * scaffold). The rules live in engine/TimeLimitEngine.kt; DefaultTimeLimitController adds
 * persistence and ticking; ui/ holds the screens.
 */

object TimeLimitModule {
    @Volatile
    private var instance: TimeLimitController? = null

    /** Called once by ThrowbackApp; return the app-wide controller. Repeat calls return the same one. */
    fun create(context: Context): TimeLimitController =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(app: Context): TimeLimitController = DefaultTimeLimitController(
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
 */
@Composable
fun TimeLimitOverlay(
    controller: TimeLimitController,
    onOpenMessagesOnly: () -> Unit,
    onCloseApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val impl = controller as? DefaultTimeLimitController ?: return
    LockOverlay(impl, onOpenMessagesOnly, onCloseApp, modifier)
}

/** Small "18 min left" chip. Draws nothing when the limit is disabled. */
@Composable
fun TimeRemainingChip(controller: TimeLimitController, modifier: Modifier = Modifier) {
    RemainingChip(controller, modifier)
}

/** Settings entry for the limit: first-time setup (limit + PIN) or manage an existing limit (PIN-protected). */
@Composable
fun TimeLimitSettingsScreen(controller: TimeLimitController, onClose: () -> Unit) {
    val impl = controller as? DefaultTimeLimitController ?: return
    TimeLimitSettings(impl, onClose)
}
