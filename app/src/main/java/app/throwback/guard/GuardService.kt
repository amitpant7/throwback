package app.throwback.guard

import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.throwback.BuildConfig
import app.throwback.MainActivity
import app.throwback.R
import app.throwback.relay.CallInApp
import app.throwback.relay.CallSnapshot
import app.throwback.relay.CallState
import app.throwback.relay.MessagesFeature
import app.throwback.relay.RelayDebug
import app.throwback.relay.RelayListenerService
import app.throwback.relay.RelayNotifier
import app.throwback.web.rules.DEFAULT_CALL_GRACE_SECONDS
import app.throwback.web.rules.RulesRepository
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.withContext

/**
 * The guard (DECISIONS.md #27): while the screen is on, it checks about twice a second which apps
 * are on screen (usage access). If an Instagram app is (in front, or in split screen, a pop-up
 * window or picture-in-picture) and no call or pass allows it, it covers the screen with "Use
 * Throwback instead" (display over other apps) and brings Throwback to the front. The cover stays
 * while Instagram stays on screen, stepping aside for a moment now and then so a window left open
 * can be closed. During a call it does nothing; when the call ends and the app is still open, it
 * covers it then.
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
    private val tracker = ForegroundTracker(stopEvents = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    private var lastQueryEnd = 0L

    /** Last time the phone's audio was in call mode (MODE_IN_CALL / MODE_IN_COMMUNICATION). */
    private var callAudioAt: Long? = null

    /** When an Instagram call notification was last seen, and whether the current call audio is that call. */
    private var lastInstagramCallAt: Long? = null
    private var instagramCallAudio = false

    /** Since when the notification listener is disconnected, and the last rebind request. */
    private var listenerDownSince: Long? = null
    private var lastRebindAt = 0L

    /** When the cover went up, and until when it stays away after stepping aside. */
    private var coverShownAt = 0L
    private var noCoverUntil = 0L

    /** Instagram stayed on screen under the last cover: the next one says to close its window. */
    private var windowLeftOpen = false
    private lateinit var overlay: GuardOverlay

    /** The story pass just ran out without a post: the next cover says so. */
    private var storyTimeUp = false

    /** Pass time in the Instagram app not yet saved for "Your day". */
    private var unsavedAppMillis = 0L

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
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(MainActivity.ACTION_OPEN_SETTINGS, null, this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
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
                tracker.clear() // the replay rebuilds what is on screen
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
            val instagramOnScreen = tracker.onScreen(RelayDebug.watchedPackages())
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
            val audioMode = getSystemService(AudioManager::class.java)?.mode
            val inCallAudio = audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION
            // Pass bookkeeping: a call started from the call button counts as an Instagram call (its
            // notification may not show for outgoing calls), and then the pass itself ends.
            if (GuardPass.active(now)) followPass(now, tracker.current, calls, inCallAudio)
            if (!inCallAudio) {
                instagramCallAudio = false
            } else if (instagramCallAudio || (lastInstagramCallAt != null && now - lastInstagramCallAt!! < IG_CALL_LINK_MS)) {
                instagramCallAudio = true
                callAudioAt = now
            }
            val action = GuardPolicy.decide(
                instagramOnScreen = instagramOnScreen,
                now = now,
                calls = calls,
                graceMillis = graceMillis,
                allowUntil = GuardPass.allowUntil,
                coverShownAt = coverShownAt.takeIf { overlay.showing },
                noCoverUntil = noCoverUntil,
                callAudioAt = callAudioAt,
            )
            if (!instagramOnScreen) windowLeftOpen = false
            when (action) {
                GuardAction.COVER -> cover(now)
                GuardAction.UNCOVER -> overlay.hide()
                GuardAction.STEP_ASIDE -> {
                    Log.i(TAG, "Instagram stays on screen (split screen, pop-up or picture-in-picture): cover steps aside")
                    overlay.hide()
                    noCoverUntil = now + GuardPolicy.STEP_ASIDE_MS
                    windowLeftOpen = true
                }
                GuardAction.NONE -> Unit
            }
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

    /** A pass is running: "Your day" time, leaving Instagram ends it, and each kind's own rules. */
    private fun followPass(now: Long, fg: String?, calls: CallSnapshot, inCallAudio: Boolean) {
        val inInstagram = fg != null && fg in RelayDebug.watchedPackages()
        if (inInstagram) {
            countAppTime()
            GuardPass.sawInstagram = true
        } else if (GuardPass.sawInstagram && fg != null && !PassRules.partOfInstagramFlow(fg, flowPackages(GuardPass.kind))) {
            leftInstagram(now)
        }
        when (GuardPass.kind) {
            GuardPass.Kind.CALL -> callPass(now, calls, inCallAudio)
            GuardPass.Kind.VISIT -> Unit
            GuardPass.Kind.CREATE -> if (inInstagram) createPass(now)
            GuardPass.Kind.SETTINGS -> if (inInstagram) settingsPass(now)
            GuardPass.Kind.STORY -> if (inInstagram) storyPass()
        }
    }

    /** "Your day": time the Instagram app is in front during a pass (calls aren't passes). */
    private fun countAppTime() {
        unsavedAppMillis += POLL_MS
        if (unsavedAppMillis >= 10_000L) {
            CallInApp.addAppTime(this, unsavedAppMillis)
            unsavedAppMillis = 0L
        }
    }

    /**
     * Left Instagram: the rest of the pass isn't kept for later. A story pass left within a few
     * minutes, having shown little but the camera, was a change of mind (owner): it doesn't count,
     * and only a short wait follows.
     */
    private fun leftInstagram(now: Long) {
        if (GuardPass.kind == GuardPass.Kind.STORY && PassRules.storyLeftEarly(
                sinceGrant = now - GuardPass.grantedAt,
                cameraOffMillis = GuardPass.outsideMillis,
                freeLeaveMillis = (storyRules()?.freeLeaveMinutes ?: 3) * 60_000L,
            )
        ) {
            CallInApp.storyLeftEarly(this)
        }
        GuardPass.end()
    }

    /** The call button's pass ends once the call runs (the call itself then keeps Instagram allowed). */
    private fun callPass(now: Long, calls: CallSnapshot, inCallAudio: Boolean) {
        if (inCallAudio || calls.inCall) {
            if (inCallAudio) lastInstagramCallAt = now
            GuardPass.end()
        }
    }

    /** Posting: only Instagram's creation screens. */
    private fun createPass(now: Long) {
        if (!PassRules.createPassHolds(tracker.currentClass, createScreens(), now - GuardPass.grantedAt)) {
            Log.i(TAG, "left Instagram's create screens: pass ends")
            GuardPass.end()
        }
    }

    /** Instagram's settings screens, and its other screens for at most `appSettings.otherScreenMinutes`. */
    private fun settingsPass(now: Long) {
        if (now - GuardPass.grantedAt < PassRules.CREATE_GRACE_MS) return
        val rules = settingsRules()
        if (!PassRules.onScreens(tracker.currentClass, PassRules.screensPattern(rules?.screens))) {
            GuardPass.outsideMillis += POLL_MS
            if (GuardPass.outsideMillis > (rules?.otherScreenMinutes ?: 0) * 60_000L) {
                Log.i(TAG, "settings pass: time outside the settings screens is up")
                GuardPass.end()
            }
        }
    }

    /** Minutes of Instagram for a story; time with the camera on doesn't count. */
    private fun storyPass() {
        if (camerasInUse.isEmpty()) GuardPass.outsideMillis += POLL_MS
        if (!PassRules.storyPassHolds(GuardPass.outsideMillis, (storyRules()?.editMinutes ?: 5) * 60_000L)) {
            Log.i(TAG, "story pass: time is up")
            storyTimeUp = true
            GuardPass.end()
        }
    }

    /** Covers Instagram and brings Throwback to the front; the cover stays while Instagram is on screen. */
    private fun cover(now: Long) {
        Log.i(TAG, "Instagram app opened outside a call: back to Throwback")
        if (overlay.show(storyTimeUp, windowHint = windowLeftOpen)) {
            coverShownAt = now
        } else {
            noCoverUntil = now + GuardPolicy.STEP_ASIDE_MS // no overlay permission: don't retry every poll
        }
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
    private fun createScreens(): Regex? = PassRules.screensPattern(RulesRepository.get(this).rules.value?.appCreate?.screens)

    /** Android screens Instagram opens on the way (rules `appCreate.flowPackages`; for settings also `appSettings.flowPackages`). */
    private fun flowPackages(kind: GuardPass.Kind): Set<String> {
        val rules = RulesRepository.get(this).rules.value
        val settings = if (kind == GuardPass.Kind.SETTINGS) rules?.appSettings?.flowPackages.orEmpty() else emptyList()
        return (rules?.appCreate?.flowPackages.orEmpty() + settings).toSet()
    }

    private fun settingsRules() = RulesRepository.get(this).rules.value?.appSettings

    private fun storyRules() = RulesRepository.get(this).rules.value?.appCreate?.story

    /**
     * Cameras in use by any app (Android reports this to every app, no permission). Throwback itself
     * never uses the camera, so "in use" while Instagram is in front means its camera is open.
     */
    private val camerasInUse = ConcurrentHashMap.newKeySet<String>()
    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            camerasInUse.add(cameraId)
            if (BuildConfig.DEBUG) Log.d(TAG, "camera $cameraId in use")
        }

        override fun onCameraAvailable(cameraId: String) {
            camerasInUse.remove(cameraId)
            if (BuildConfig.DEBUG) Log.d(TAG, "camera $cameraId free")
        }
    }

    private fun watchCameras(on: Boolean) {
        val cm = getSystemService(CameraManager::class.java) ?: return
        try {
            if (on) cm.registerAvailabilityCallback(cameraCallback, Handler(Looper.getMainLooper()))
            else cm.unregisterAvailabilityCallback(cameraCallback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "camera watch unavailable: ${e.javaClass.simpleName}")
        }
    }
}
