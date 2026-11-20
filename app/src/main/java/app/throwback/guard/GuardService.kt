package app.throwback.guard

import app.throwback.relay.RelayListenerService
import android.service.notification.NotificationListenerService
import android.content.ComponentName
import kotlinx.coroutines.withContext
import android.media.AudioManager
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

    /** Last time the phone's audio was in call mode (MODE_IN_CALL / MODE_IN_COMMUNICATION). */
    private var callAudioAt: Long? = null

    /** When an Instagram call notification was last seen, and whether the current call audio is that call. */
    private var lastInstagramCallAt: Long? = null
    private var instagramCallAudio = false

    /** Since when the notification listener is disconnected, and the last rebind request. */
    private var listenerDownSince: Long? = null
    private var lastRebindAt = 0L
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
        watchCameras(true)
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
        watchCameras(false)
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
            .setSmallIcon(R.drawable.ic_stat_throwback)
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
        try {
            ServiceCompat.startForeground(this, RelayNotifier.GUARD_NOTIFICATION_ID, notification, type)
        } catch (e: RuntimeException) {
            // Android 12+ may refuse a foreground start (e.g. a restart after a low-memory kill).
            Log.w(TAG, "guard could not start in the foreground: ${e.javaClass.simpleName}")
            stopSelf()
            return
        }
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
            // Reading usage events off the main thread (the WebView shares it).
            withContext(Dispatchers.Default) { poll(usm) }
            val fg = tracker.current
            if (overlay.showing && (fg == packageName || now - coverShownAt > COVER_MAX_MS) && now - coverShownAt > COVER_MIN_MS) {
                overlay.hide()
            }
            // An answered Instagram call removes its notification, but keeps the audio in call mode.
            // Call audio counts only for a call that began as an Instagram call notification (seen
            // within IG_CALL_LINK_MS), so a WhatsApp or Meet call doesn't unlock Instagram.
            val calls = CallState.tracker.state.value
            if (calls.inCall) lastInstagramCallAt = now
            // Without the listener the guard can't see calls and allows everything (on purpose). Some
            // phones don't reconnect it after a crash or update: ask Android to, every 30 s.
            if (calls.listenerConnected) {
                listenerDownSince = null
            } else {
                val since = listenerDownSince ?: now.also { listenerDownSince = it }
                if (now - since > REBIND_AFTER_MS && now - lastRebindAt > REBIND_AFTER_MS) {
                    lastRebindAt = now
                    try {
                        NotificationListenerService.requestRebind(ComponentName(this, RelayListenerService::class.java))
                    } catch (e: RuntimeException) {
                        // Notification access was revoked; the permission check stops the guard.
                    }
                }
            }
            val audioMode = (getSystemService(AUDIO_SERVICE) as AudioManager).mode
            val inCallAudio = audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION
            // Pass bookkeeping: a call started from the call button counts as an Instagram call (its
            // notification may not show for outgoing calls), and then the pass itself ends.
            if (GuardPass.active(now)) {
                val watched = RelayDebug.watchedPackages()
                // "Your day": time the Instagram app is in front during a pass (calls aren't passes).
                if (fg != null && fg in watched) {
                    unsavedAppMillis += POLL_MS
                    if (unsavedAppMillis >= 10_000L) {
                        app.throwback.relay.CallInApp.addAppTime(this, unsavedAppMillis)
                        unsavedAppMillis = 0L
                    }
                }
                if (fg != null && fg in watched) {
                    GuardPass.sawInstagram = true
                } else if (GuardPass.sawInstagram && fg != null && !PassRules.partOfInstagramFlow(fg, flowPackages() + settingsRules()?.flowPackages.orEmpty().takeIf { GuardPass.kind == GuardPass.Kind.SETTINGS }.orEmpty())) {
                    // Left Instagram: the rest of the pass isn't kept for later. A story pass left within
                    // a few minutes, having shown little but the camera, was a change of mind (owner):
                    // it doesn't count, and only a short wait follows.
                    if (GuardPass.kind == GuardPass.Kind.STORY && PassRules.storyLeftEarly(
                            sinceGrant = now - GuardPass.grantedAt,
                            cameraOffMillis = GuardPass.outsideMillis,
                            freeLeaveMillis = (storyRules()?.freeLeaveMinutes ?: 3) * 60_000L,
                        )
                    ) {
                        app.throwback.relay.CallInApp.storyLeftEarly(this)
                    }
                    GuardPass.end()
                }
                if (GuardPass.kind == GuardPass.Kind.CREATE && fg != null && fg in watched &&
                    !PassRules.createPassHolds(tracker.currentClass, createScreens(), now - GuardPass.grantedAt)
                ) {
                    Log.i(TAG, "left Instagram's create screens: pass ends")
                    GuardPass.end()
                }
                if (GuardPass.kind == GuardPass.Kind.STORY && fg != null && fg in watched) {
                    // Minutes of Instagram for a story; time with the camera on doesn't count.
                    if (camerasInUse.isEmpty()) GuardPass.outsideMillis += POLL_MS
                    if (!PassRules.storyPassHolds(GuardPass.outsideMillis, (storyRules()?.editMinutes ?: 5) * 60_000L)) {
                        Log.i(TAG, "story pass: time is up")
                        storyTimeUp = true
                        GuardPass.end()
                    }
                }
                if (GuardPass.kind == GuardPass.Kind.SETTINGS && fg != null && fg in watched &&
                    now - GuardPass.grantedAt >= PassRules.CREATE_GRACE_MS
                ) {
                    val rules = settingsRules()
                    val screens = rules?.screens?.let { runCatching { Regex(it) }.getOrNull() }
                    val cls = tracker.currentClass
                    if (cls != null && (screens == null || !screens.containsMatchIn(cls))) {
                        GuardPass.outsideMillis += POLL_MS
                        if (GuardPass.outsideMillis > (rules?.otherScreenMinutes ?: 0) * 60_000L) {
                            Log.i(TAG, "settings pass: time outside the settings screens is up")
                            GuardPass.end()
                        }
                    }
                }
                if (GuardPass.kind == GuardPass.Kind.CALL && (inCallAudio || calls.inCall)) {
                    if (inCallAudio) lastInstagramCallAt = now
                    GuardPass.end()
                }
            }
            if (!inCallAudio) {
                instagramCallAudio = false
            } else if (instagramCallAudio || (lastInstagramCallAt != null && now - lastInstagramCallAt!! < IG_CALL_LINK_MS)) {
                instagramCallAudio = true
                callAudioAt = now
            }
            val action = GuardPolicy.decide(
                foreground = fg,
                watched = RelayDebug.watchedPackages(),
                now = now,
                calls = calls,
                graceMillis = graceMillis,
                allowUntil = GuardPass.allowUntil,
                lastCoverAt = lastCoverAt,
                callAudioAt = callAudioAt,
            )
            if (action == GuardAction.COVER) cover(now)
            delay(POLL_MS)
        }
    }

    private fun poll(usm: UsageStatsManager) {
        val end = System.currentTimeMillis()
        // First run, or the clock moved back: look back a little to learn what is in front now.
        // Overlap the previous window a little: events are recorded asynchronously and one stamped
        // just before the last end could be missed. Replaying events in order is harmless.
        val begin = if (lastQueryEnd == 0L || lastQueryEnd > end) end - LOOK_BACK_MS else lastQueryEnd - QUERY_OVERLAP_MS
        val events = try {
            usm.queryEvents(begin, end)
        } catch (e: RuntimeException) {
            null
        } ?: return
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            tracker.onEvent(event.eventType, event.packageName, event.className)
        }
        lastQueryEnd = end
    }

    private fun cover(now: Long) {
        Log.i(TAG, "Instagram app opened outside a call: back to Throwback")
        lastCoverAt = now
        if (overlay.show(storyTimeUp)) coverShownAt = now
        storyTimeUp = false
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
        private const val QUERY_OVERLAP_MS = 3_000L
        private const val REBIND_AFTER_MS = 30_000L
        /** Call audio counts if an Instagram call notification was seen this recently (ringing, then answered). */
        private const val IG_CALL_LINK_MS = 120_000L
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

    /** Instagram's creation screens (rules `appCreate.screens`). */
    private fun createScreens(): Regex? = app.throwback.web.rules.RulesRepository.get(this).rules.value?.appCreate?.screens
        ?.let { runCatching { Regex(it) }.getOrNull() }


    /** Android screens Instagram opens on the way (rules `appCreate.flowPackages`, plus built-in ones). */
    private fun flowPackages(): Set<String> =
        app.throwback.web.rules.RulesRepository.get(this).rules.value?.appCreate?.flowPackages?.toSet().orEmpty()


    private fun settingsRules() = app.throwback.web.rules.RulesRepository.get(this).rules.value?.appSettings


    private fun storyRules() = app.throwback.web.rules.RulesRepository.get(this).rules.value?.appCreate?.story

    /**
     * Cameras in use by any app (Android reports this to every app, no permission). Throwback itself
     * never uses the camera, so "in use" while Instagram is in front means its camera is open.
     */
    private val camerasInUse = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val cameraCallback = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            camerasInUse.add(cameraId)
            if (app.throwback.BuildConfig.DEBUG) Log.d(TAG, "camera $cameraId in use")
        }

        override fun onCameraAvailable(cameraId: String) {
            camerasInUse.remove(cameraId)
            if (app.throwback.BuildConfig.DEBUG) Log.d(TAG, "camera $cameraId free")
        }
    }

    private fun watchCameras(on: Boolean) {
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java) ?: return
        try {
            if (on) cm.registerAvailabilityCallback(cameraCallback, android.os.Handler(android.os.Looper.getMainLooper()))
            else cm.unregisterAvailabilityCallback(cameraCallback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "camera watch unavailable: ${e.javaClass.simpleName}")
        }
    }


    /** The story pass just ran out without a post: the next cover says so. */
    private var storyTimeUp = false


    /** Pass time in the Instagram app not yet saved for "Your day". */
    private var unsavedAppMillis = 0L

}
