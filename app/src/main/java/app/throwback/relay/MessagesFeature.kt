package app.throwback.relay

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.throwback.guard.GuardHeartbeat
import app.throwback.guard.GuardService
import app.throwback.ui.UiPrefs
import app.throwback.ui.messages.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On/off switch for Messages & calls (relay + guard). Nothing runs until the wizard is finished
 * (`messages_setup_done` in ui_prefs) and the permissions are granted; "Turn off" in Settings stops it.
 *
 * - The relay (RelayListenerService) is bound by Android whenever notification access is granted;
 *   it checks [isSetUp] for every notification and does nothing while it is false.
 * - The guard (GuardService) is a foreground service, started here when everything is in place and
 *   stopped when it isn't.
 */
internal object MessagesFeature {
    private const val TAG = "ThrowbackRelay"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The setup's last known state, for callers that can't wait for DataStore; null until read. */
    @Volatile
    var setUpNow: Boolean? = null
        private set

    /** Called once from ThrowbackApp.onCreate. Cheap: one DataStore read, no services started unless set up. */
    fun install(app: Application) {
        scope.launch {
            var before: Boolean? = null
            UiPrefs.from(app).messagesSetupDone.distinctUntilChanged().collect { setUp ->
                setUpNow = setUp
                // Connected again after being off: the time it was off never counts.
                if (setUp && before == false) GuardHeartbeat.clear(app)
                before = setUp
                syncGuard(app, setUp)
            }
        }
        // Permissions may have been granted or revoked in Android's settings while Throwback was away.
        // If that (or Android) stopped the guard, the Instagram app's time since counts first.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch {
                    if (isSetUp(app)) withContext(Dispatchers.IO) { GuardHeartbeat.catchUp(app) }
                    syncGuard(app, isSetUp(app)) // read again: a scheduled turn-off may have come due meanwhile
                }
            }
        })
    }

    suspend fun isSetUp(context: Context): Boolean = UiPrefs.from(context).messagesSetupDone.first()

    /** Everything the guard needs: set up, notification access (for call state), usage access, overlay. */
    fun guardPossible(context: Context): Boolean =
        Permissions.hasNotificationAccess(context) && Permissions.hasUsageAccess(context) && Permissions.canDrawOverlays(context)

    /** Starts or stops the guard to match the setup. Safe to call any time, from anywhere. */
    suspend fun sync(context: Context) = syncGuard(context, isSetUp(context))

    private fun syncGuard(context: Context, setUp: Boolean) {
        if (setUp && guardPossible(context)) {
            GuardService.start(context)
        } else {
            Log.d(TAG, "guard off (set up: $setUp)")
            GuardService.stop(context)
            // Set up, but a permission was turned off (Android may have ended Throwback with it).
            if (setUp) RelayNotifier.showGuardOff(context, permissionOff = true)
            // Turned off on purpose: the Instagram app is free from now, nothing to count later.
            if (!setUp) GuardHeartbeat.clear(context)
        }
    }
}
