package app.throwback.guard

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.throwback.MainActivity
import app.throwback.R
import app.throwback.relay.CallState
import app.throwback.relay.MessagesFeature
import app.throwback.relay.RelayDebug
import app.throwback.relay.RelayNotifier
import app.throwback.web.rules.DEFAULT_CALL_GRACE_SECONDS
import app.throwback.web.rules.RulesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The guard (DECISIONS.md #27): while the screen is on, it checks about twice a second which app
 * is in front (usage access). If it is the Instagram app and there is no call, it covers it with
 * "Use Throwback instead" (display over other apps) and brings Throwback to the front. During a
 * call it does nothing; when the call ends and the app is still open, it covers it then.
 *
 * Why a foreground service: Android has no callback for "another app came to the front" without an
 * accessibility service (which Play reserves for accessibility tools), so the guard has to ask, and
 * only a foreground service is reliably kept alive for that on Android 8 to 15 (owner accepted the
 * small ongoing notification, plan section D). Battery: it does nothing while the screen is off
 * (no wake locks, no alarms; it sleeps until SCREEN_ON), and a check is one in-memory usage-events
 * query.
 */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private val screenOn = MutableStateFlow(true)
    private val tracker = ForegroundTracker()
    private var lastQueryEnd = 0L
    private var lastCoverAt: Long? = null
    private var coverShownAt = 0L
    private lateinit var overlay: GuardOverlay

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn.value = intent?.action != Intent.ACTION_SCREEN_OFF
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = GuardOverlay(this)
        RelayNotifier.ensureChannels(this)
        goForeground()
        screenOn.value = getSystemService(PowerManager::class.java)?.isInteractive != false
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A restart by Android after the process was killed also lands here: call startForeground again.
        goForeground()
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        overlay.hide()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            // not registered
        }
        super.onDestroy()
    }

    private fun goForeground() {
        val open = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(MainActivity.ACTION_OPEN_SETTINGS, null, this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, RelayNotifier.CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_throwback_mark)
            .setContentTitle(getString(R.string.guard_notification_title))
            .setContentText(getString(R.string.guard_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.guard_notification_text)))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
        // specialUse: none of the specific types fits "watch which app is in front" (see the manifest).
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, RelayNotifier.GUARD_NOTIFICATION_ID, notification, type)
        running = true
    }

    private suspend fun run() {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return
        val graceMillis = graceSeconds() * 1000L
        var lastPermissionCheck = 0L
        while (scope.isActive) {
            if (!screenOn.value) {
                overlay.hide()
                screenOn.first { it }
                lastQueryEnd = 0L // events while off are irrelevant; start fresh
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastPermissionCheck > PERMISSION_CHECK_MS) {
                lastPermissionCheck = now
                if (!MessagesFeature.guardPossible(this) || !MessagesFeature.isSetUp(this)) {
                    Log.i(TAG, "permissions gone or turned off: stopping")
                    stopSelf()
                    return
                }
            }
            poll(usm)
            val fg = tracker.current
            if (overlay.showing && (fg == packageName || now - coverShownAt > COVER_MAX_MS) && now - coverShownAt > COVER_MIN_MS) {
                overlay.hide()
            }
            val action = GuardPolicy.decide(
                foreground = fg,
                watched = RelayDebug.watchedPackages(),
                now = now,
                calls = CallState.tracker.state.value,
                graceMillis = graceMillis,
                allowUntil = GuardPass.allowUntil,
                lastCoverAt = lastCoverAt,
            )
            if (action == GuardAction.COVER) cover(now)
            delay(POLL_MS)
        }
    }

    private fun poll(usm: UsageStatsManager) {
        val end = System.currentTimeMillis()
        // First run, or the clock moved back: look back a little to learn what is in front now.
        val begin = if (lastQueryEnd == 0L || lastQueryEnd > end) end - LOOK_BACK_MS else lastQueryEnd
        val events = try {
            usm.queryEvents(begin, end)
        } catch (e: RuntimeException) {
            null
        } ?: return
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            tracker.onEvent(event.eventType, event.packageName)
        }
        lastQueryEnd = end
    }

    private fun cover(now: Long) {
        Log.i(TAG, "Instagram app opened outside a call: back to Throwback")
        lastCoverAt = now
        if (overlay.show()) coverShownAt = now
        // Allowed from the background: the user granted "display over other apps".
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not bring Throwback to the front", e)
        }
    }

    private suspend fun graceSeconds(): Int = try {
        RulesRepository.get(this).let { it.rules.value ?: it.load() }.notifications?.callGraceSeconds ?: DEFAULT_CALL_GRACE_SECONDS
    } catch (e: RuntimeException) {
        DEFAULT_CALL_GRACE_SECONDS
    }

    companion object {
        private const val TAG = "ThrowbackGuard"
        private const val POLL_MS = 500L
        private const val LOOK_BACK_MS = 60 * 60 * 1000L
        private const val PERMISSION_CHECK_MS = 10_000L
        private const val COVER_MIN_MS = 900L
        private const val COVER_MAX_MS = 3_000L

        /** True while the service is in the foreground (for Settings' status line). */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java))
            } catch (e: IllegalStateException) {
                // Android 12+ refuses foreground-service starts from the background in some cases
                // (e.g. Android 15 without a visible window). The next time Throwback opens, at boot,
                // or when Android restarts the sticky service, it starts again.
                Log.w(TAG, "guard could not start now: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "guard could not start: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}
