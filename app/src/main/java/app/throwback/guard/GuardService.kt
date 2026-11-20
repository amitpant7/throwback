package app.throwback.guard

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.core.app.NotificationManagerCompat
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
import app.throwback.web.rules.AppStoryRules
import app.throwback.web.rules.DEFAULT_CALL_GRACE_SECONDS
import app.throwback.web.rules.FilterRules
import app.throwback.web.rules.RulesRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
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

    /** Where the next usage-events query starts (phone wall ms); null: look back [LOOK_BACK_MS]. */
    private var queryFrom: Long? = null

    /** When the screen last went off (phone wall ms): the replay at screen-on starts there. */
    @Volatile
    private var screenOffAt: Long? = null

    /** What the rules say about passes and calls, worked out once per rules object. */
    private var rulesCache: GuardRules? = null

    /** Last time the phone's audio was in call mode for an Instagram call. */
    private var callAudioAt: Long? = null

    /** Whether the call audio is an Instagram call. */
    private val callAudio = CallAudioLink()

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

    /** The ongoing notification says nothing is blocked (the listener is disconnected). */
    private var statusPaused = false

    /** When the guard last noted that it runs (see GuardHeartbeat). */
    private var lastBeatAt: Long? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val off = intent?.action == Intent.ACTION_SCREEN_OFF
            if (off) screenOffAt = System.currentTimeMillis()
            screenOn.value = !off
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
        if (goForeground() && loop?.isActive != true) loop = scope.launch { run() }
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

    /** Runs in the foreground with the ongoing notification; false if Android refused (then it stops). */
    private fun goForeground(): Boolean {
        val paused = !CallState.tracker.state.value.listenerConnected
        // specialUse: none of the specific types fits "watch which app is in front" (see the manifest).
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, RelayNotifier.GUARD_NOTIFICATION_ID, statusNotification(paused), type)
        } catch (e: RuntimeException) {
            // Android 12+ may refuse a foreground start (e.g. a restart after a low-memory kill).
            Log.w(TAG, "guard could not start in the foreground: ${e.javaClass.simpleName}")
            RelayNotifier.showGuardOff(this)
            stopSelf()
            return false
        }
        statusPaused = paused
        running = true
        RelayNotifier.cancelGuardOff(this)
        return true
    }

    /**
     * The ongoing notification. It says what is true: while the notification listener is
     * disconnected ([paused]) calls can't be seen, so the guard lets the Instagram app open.
     */
    private fun statusNotification(paused: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(MainActivity.ACTION_OPEN_SETTINGS, null, this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = getString(if (paused) R.string.guard_notification_paused_text else R.string.guard_notification_text)
        return NotificationCompat.Builder(this, RelayNotifier.CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_stat_throwback)
            .setContentTitle(getString(if (paused) R.string.guard_notification_paused_title else R.string.guard_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    /** The listener connected or disconnected: the ongoing notification follows. */
    private fun updateStatus(paused: Boolean) {
        if (paused == statusPaused) return
        statusPaused = paused
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(RelayNotifier.GUARD_NOTIFICATION_ID, statusNotification(paused))
        } catch (e: SecurityException) {
            // Revoked between the check and now.
        }
    }

    private suspend fun run() {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return
        var lastPermissionCheck = 0L
        // The rules (call grace, pass screens) are then read from memory at each check.
        runCatching { RulesRepository.get(this).load() }
        // Back after being stopped (or killed): the Instagram app's time meanwhile counts first.
        withContext(Dispatchers.IO) { GuardHeartbeat.catchUp(this@GuardService) }
        while (scope.isActive) {
            if (!screenOn.value) {
                overlay.hide()
                screenOn.first { it }
                // What is on screen now is rebuilt from the events since the screen went off (a pass
                // that ran out meanwhile is then covered); before, a whole hour was replayed.
                tracker.clear()
                queryFrom = screenOffAt?.let { it - SCREEN_OFF_MARGIN_MS }
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
            if (lastBeatAt.let { it == null || now - it >= GuardHeartbeat.BEAT_MS }) {
                lastBeatAt = now
                GuardHeartbeat.beat(this)
            }
            val watched = RelayDebug.watchedPackages()
            val instagramOnScreen = tracker.onScreen(watched)
            val calls = CallState.tracker.state.value
            followListener(calls, now)
            val audioMode = getSystemService(AudioManager::class.java)?.mode
            val inCallAudio = audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION
            // Call audio keeps Instagram allowed only if it is an Instagram call (see CallAudioLink):
            // its call notification was seen within IG_CALL_LINK_MS (the relay notes when it went, also
            // with the screen off), or the call started in Instagram.
            val igCallRecently = calls.inCall || calls.lastCallEndedAt?.let { now - it in 0 until IG_CALL_LINK_MS } == true
            if (callAudio.update(inCallAudio, tracker.current in watched, igCallRecently)) callAudioAt = now
            if (GuardPass.active(now)) followPass(now, tracker.current, calls, inCallAudio)
            val action = GuardPolicy.decide(
                instagramOnScreen = instagramOnScreen,
                now = now,
                calls = calls,
                graceMillis = rules().callGraceMillis, // read each time: a rules update applies at once
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
                    queryFrom = null // and checks from scratch, in case an event was missed
                }
                GuardAction.NONE -> Unit
            }
            delay(POLL_MS)
        }
    }

    private fun poll(usm: UsageStatsManager) {
        val end = System.currentTimeMillis()
        val from = queryFrom?.takeIf { it <= end }
        val begin = if (from != null) {
            max(from, end - LOOK_BACK_MS)
        } else {
            // First run, the clock moved back, or a check from scratch: what is on screen now is
            // rebuilt from the last hour (not added on top, or an old screen could seem still open).
            tracker.clear()
            end - LOOK_BACK_MS
        }
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
        // The next window overlaps this one a little: events are recorded asynchronously and one
        // stamped just before this end could be missed. Replaying events in order is harmless.
        queryFrom = end - QUERY_OVERLAP_MS
    }

    /**
     * Without the notification listener the guard can't see calls and allows everything (on purpose),
     * and its notification says so. Some phones don't reconnect it after a crash or update: ask
     * Android to, every 30 s.
     */
    private fun followListener(calls: CallSnapshot, now: Long) {
        updateStatus(paused = !calls.listenerConnected)
        if (calls.listenerConnected) {
            listenerDownSince = null
            return
        }
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

    /** A pass is running: "Your day" time, leaving Instagram ends it, and each kind's own rules. */
    private fun followPass(now: Long, fg: String?, calls: CallSnapshot, inCallAudio: Boolean) {
        val inInstagram = fg != null && fg in RelayDebug.watchedPackages()
        val flow = if (GuardPass.kind == GuardPass.Kind.SETTINGS) rules().settingsFlow else rules().createFlow
        if (inInstagram) {
            countAppTime()
            GuardPass.sawInstagram = true
        } else if (GuardPass.sawInstagram && fg != null && !PassRules.partOfInstagramFlow(fg, flow)) {
            leftInstagram(now)
        }
        when (GuardPass.kind) {
            GuardPass.Kind.CALL -> callPass(calls, inCallAudio)
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
        val leftEarly = GuardPass.kind == GuardPass.Kind.STORY && PassRules.storyLeftEarly(
            sinceGrant = now - GuardPass.grantedAt,
            cameraOffMillis = GuardPass.outsideMillis,
            freeLeaveMillis = (rules().story?.freeLeaveMinutes ?: 3) * 60_000L,
        )
        // Only if this ended it: the relay may just have ended it as posted (and forgiven it).
        if (GuardPass.endIfActive(now) && leftEarly) CallInApp.storyLeftEarly(this)
    }

    /**
     * The call button's pass ends once a call runs. An Instagram call then keeps Instagram allowed by
     * itself; any other call (phone, WhatsApp) doesn't (see CallAudioLink).
     */
    private fun callPass(calls: CallSnapshot, inCallAudio: Boolean) {
        if (inCallAudio || calls.inCall) GuardPass.end()
    }

    /** Posting: only Instagram's creation screens. */
    private fun createPass(now: Long) {
        if (!PassRules.createPassHolds(tracker.currentClass, rules().createScreens, now - GuardPass.grantedAt)) {
            Log.i(TAG, "left Instagram's create screens: pass ends")
            GuardPass.end()
        }
    }

    /** Instagram's settings screens, and its other screens for at most `appSettings.otherScreenMinutes`. */
    private fun settingsPass(now: Long) {
        if (now - GuardPass.grantedAt < PassRules.CREATE_GRACE_MS) return
        val rules = rules()
        if (!PassRules.onScreens(tracker.currentClass, rules.settingsScreens)) {
            GuardPass.outsideMillis += POLL_MS
            if (GuardPass.outsideMillis > rules.settingsOtherMillis) {
                Log.i(TAG, "settings pass: time outside the settings screens is up")
                GuardPass.end()
            }
        }
    }

    /** Minutes of Instagram for a story; time with the camera on doesn't count. */
    private fun storyPass() {
        if (camerasInUse.isEmpty()) GuardPass.outsideMillis += POLL_MS
        if (!PassRules.storyPassHolds(GuardPass.outsideMillis, (rules().story?.editMinutes ?: 5) * 60_000L)) {
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

    companion object {
        private const val TAG = "ThrowbackGuard"
        private const val POLL_MS = 500L
        private const val QUERY_OVERLAP_MS = 3_000L
        private const val REBIND_AFTER_MS = 30_000L
        /** Call audio counts if an Instagram call notification was seen this recently (ringing, then answered). */
        private const val IG_CALL_LINK_MS = 120_000L
        /** The first query after the guard starts looks back this far, to learn what is on screen. */
        private const val LOOK_BACK_MS = 60 * 60 * 1000L

        /** The replay at screen-on starts this long before the screen went off. */
        private const val SCREEN_OFF_MARGIN_MS = 5_000L
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
                // or when Android restarts the sticky service, it starts again; meanwhile a
                // notification says the guard is off (tap: Throwback opens and starts it).
                Log.w(TAG, "guard could not start now: ${e.message}")
                if (!running) RelayNotifier.showGuardOff(context)
            } catch (e: SecurityException) {
                Log.w(TAG, "guard could not start: ${e.message}")
                if (!running) RelayNotifier.showGuardOff(context)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }

    /** The rules' guard and pass settings, worked out once per rules object (not every 500 ms). */
    private class GuardRules(val source: FilterRules?) {
        /** A call is allowed this long after its notification went (`notifications.callGraceSeconds`). */
        val callGraceMillis = (source?.notifications?.callGraceSeconds ?: DEFAULT_CALL_GRACE_SECONDS) * 1000L

        /** Instagram's creation and settings screens (`appCreate.screens`, `appSettings.screens`). */
        val createScreens = PassRules.screensPattern(source?.appCreate?.screens)
        val settingsScreens = PassRules.screensPattern(source?.appSettings?.screens)
        val settingsOtherMillis = (source?.appSettings?.otherScreenMinutes ?: 0) * 60_000L

        /** Android screens Instagram opens on the way (`appCreate.flowPackages`; for settings also `appSettings.flowPackages`). */
        val createFlow: Set<String> = source?.appCreate?.flowPackages.orEmpty().toSet()
        val settingsFlow: Set<String> = createFlow + source?.appSettings?.flowPackages.orEmpty()
        val story: AppStoryRules? = source?.appCreate?.story
    }

    private fun rules(): GuardRules {
        val current = RulesRepository.get(this).rules.value
        rulesCache?.takeIf { it.source === current }?.let { return it }
        return GuardRules(current).also { rulesCache = it }
    }

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
