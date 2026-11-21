package app.throwback.connect.guard

import app.throwback.connect.CallSnapshot

/**
 * Follows the apps on screen from usage events (UsageStatsManager), in order. Pure Kotlin: the
 * service feeds it (type, package, class).
 *
 * [current] is the app last brought to the front (for the passes' bookkeeping). Being on screen is
 * followed per app, the way Android's own usage tracking does it (Android 10+): an activity is
 * visible from its RESUMED, or a PAUSED (one can come back paused, e.g. a picture-in-picture window
 * after the screen was off), until its STOPPED or DESTROYED. So an app in split screen, a pop-up
 * window or picture-in-picture still counts: there it stays resumed, or only pauses, and switching
 * between windows logs nothing. Without STOPPED events (below Android 10, [stopEvents] false)
 * PAUSED ends it.
 */
internal class ForegroundTracker(private val stopEvents: Boolean = true) {
    var current: String? = null
        private set

    /** The screen (activity class) of [current], when known. */
    var currentClass: String? = null
        private set

    /**
     * The visible activities (classes) of each app; true while that screen was resumed after its
     * last pause. Events don't say which instance of a screen they are about, and a screen opened
     * from the same screen (A1 paused, A2 resumed, then A1 stopped) must stay visible.
     */
    private val visible = HashMap<String, HashMap<String, Boolean>>()

    fun onEvent(type: Int, packageName: String?, className: String? = null) {
        packageName ?: return
        val screen = className.orEmpty()
        when (type) {
            ACTIVITY_RESUMED -> {
                current = packageName
                currentClass = className
                visible.getOrPut(packageName) { HashMap() }[screen] = true
            }
            ACTIVITY_PAUSED -> {
                leftFront(packageName, className)
                if (stopEvents) visible.getOrPut(packageName) { HashMap() }[screen] = false else hidden(packageName, screen)
            }
            ACTIVITY_STOPPED, ACTIVITY_DESTROYED -> {
                if (visible[packageName]?.get(screen) == true) return // an older instance of a screen resumed since
                leftFront(packageName, className)
                hidden(packageName, screen)
            }
        }
    }

    /** Whether any of [packages] is on screen, in front or not. */
    fun onScreen(packages: Set<String>): Boolean = packages.any { !visible[it].isNullOrEmpty() }

    /**
     * The screens (activity classes; "" when unknown) of [packages] that are active: resumed, so in
     * front or in split screen. Not one only paused on screen (picture-in-picture, or the one being
     * left while Android switches screens).
     */
    fun activeScreens(packages: Set<String>): List<String> =
        packages.flatMap { pkg -> visible[pkg]?.filterValues { it }?.keys.orEmpty() }

    /** Start over (the screen went off, or a check from scratch): the events replayed next rebuild it. */
    fun clear() {
        visible.clear()
        current = null
        currentClass = null
    }

    private fun leftFront(packageName: String, className: String?) {
        if (current == packageName && (className == null || className == currentClass)) {
            current = null
            currentClass = null
        }
    }

    private fun hidden(packageName: String, screen: String) {
        val screens = visible[packageName] ?: return
        screens.remove(screen)
        if (screens.isEmpty()) visible.remove(packageName)
    }

    companion object {
        // UsageEvents.Event constants (ACTIVITY_RESUMED == MOVE_TO_FOREGROUND on older Androids).
        const val ACTIVITY_RESUMED = 1
        const val ACTIVITY_PAUSED = 2
        const val ACTIVITY_STOPPED = 23
        const val ACTIVITY_DESTROYED = 24
    }
}

internal enum class GuardAction {
    NONE,

    /** Cover the Instagram app ("Use Throwback instead") and bring Throwback to the front. */
    COVER,

    /** Take the cover away: Instagram left the screen, or a call or a pass allows it now. */
    UNCOVER,

    /**
     * Instagram stays on screen under the cover (split screen, a pop-up window, picture-in-picture):
     * take the cover away for [GuardPolicy.STEP_ASIDE_MS], so its window can be closed. A cover that
     * never went away would leave the phone unusable.
     */
    STEP_ASIDE,
}

/**
 * The guard's decision (DECISIONS.md #27), pure so it is unit-tested with a fake clock.
 * All times are elapsed-realtime millis.
 */
internal object GuardPolicy {
    /** The cover stays at least this long, so it doesn't flash while Throwback comes to the front. */
    const val COVER_MIN_MS = 900L

    /** The longest one cover stays while Instagram stays on screen. */
    const val COVER_HOLD_MAX_MS = 8_000L

    /** How long the cover steps aside after [COVER_HOLD_MAX_MS]. */
    const val STEP_ASIDE_MS = 2_500L

    /**
     * No cover this long after leaving Instagram ended a pass: its screen is paused, still "on
     * screen", until Android reports it stopped. Covering it then would take the user to Throwback
     * instead of where they went (Home). A window left open (picture-in-picture) is covered after.
     */
    const val LEAVING_MS = 1_500L

    /**
     * @param instagramOnScreen an Instagram app is on screen, in front or not (see ForegroundTracker).
     * @param calls the relay's call state. If the relay isn't connected, calls can't be seen, so the
     *        guard allows everything: a call must never be blocked (DECISIONS.md #20).
     * @param allowUntil a pass (Throwback itself opened the Instagram app) runs until then.
     * @param coverShownAt when the cover went up, or null while it is down.
     * @param noCoverUntil the cover stepped aside (or couldn't be shown) until then.
     * @param callAudioAt the last time the phone's audio was in call mode for an Instagram call, or
     *        null. Instagram shows no notification once a call is answered (seen on the owner's
     *        phone), so the call notification alone ended the call too early.
     * @param callScreensOk what is on screen is allowed during a call (see [CallScreens]).
     * @param callSigns call audio or an Instagram call screen: needed for a notification that is only
     *        ongoing to count as a call (see NotificationKind.ONGOING).
     */
    fun decide(
        instagramOnScreen: Boolean,
        now: Long,
        calls: CallSnapshot,
        graceMillis: Long,
        allowUntil: Long = 0L,
        coverShownAt: Long? = null,
        noCoverUntil: Long = 0L,
        callAudioAt: Long? = null,
        callScreensOk: Boolean = true,
        callSigns: Boolean = true,
    ): GuardAction {
        val blocked = instagramOnScreen && !allowed(now, calls, graceMillis, allowUntil, callAudioAt, callScreensOk, callSigns)
        if (coverShownAt == null) return if (blocked && now >= noCoverUntil) GuardAction.COVER else GuardAction.NONE
        val shownFor = now - coverShownAt
        return when {
            blocked -> if (shownFor >= COVER_HOLD_MAX_MS) GuardAction.STEP_ASIDE else GuardAction.NONE
            instagramOnScreen -> GuardAction.UNCOVER // a call or a pass allows it now: at once
            shownFor >= COVER_MIN_MS -> GuardAction.UNCOVER
            else -> GuardAction.NONE
        }
    }

    /** An Instagram call runs (or just ended): its notification, or Instagram call audio. */
    fun inCall(now: Long, calls: CallSnapshot, graceMillis: Long, callAudioAt: Long?, callSigns: Boolean = true): Boolean =
        calls.inCallOrGrace(now, graceMillis, callSigns) || (callAudioAt != null && now - callAudioAt in 0..graceMillis)

    /** Instagram may be on screen now: a call (its screens), or a pass. */
    private fun allowed(
        now: Long,
        calls: CallSnapshot,
        graceMillis: Long,
        allowUntil: Long,
        callAudioAt: Long?,
        callScreensOk: Boolean,
        callSigns: Boolean,
    ): Boolean =
        !calls.listenerConnected ||
            (inCall(now, calls, graceMillis, callAudioAt, callSigns) && callScreensOk) ||
            now < allowUntil
}

/**
 * During an Instagram call only its call screens may be on screen (DECISIONS.md #34b): browsing the
 * rest of Instagram next to a call, or in front of it, is covered. That starts once a call screen
 * has been seen in this call; until then (Instagram renamed it, or it hasn't shown yet) all of
 * Instagram is allowed, as before, so a call is never cut off. Pure, for tests.
 */
internal class CallScreens {
    private var seen = false

    /**
     * Called once per check. @param inCall an Instagram call runs. @param active Instagram's active
     * screens (ForegroundTracker.activeScreens; "" when unknown, which counts as a call screen): a
     * call shrunk to picture-in-picture is only paused, so the feed in front of it is what counts,
     * and the screen being left while the call screen opens doesn't. @param pattern `appCall.screens`.
     * @return whether the call allows what is on screen.
     */
    fun allow(inCall: Boolean, active: List<String>, pattern: Regex?): Boolean {
        if (!inCall || pattern == null) {
            seen = false
            return true
        }
        fun isCall(screen: String) = screen.isEmpty() || pattern.containsMatchIn(screen)
        if (shown(active, pattern)) seen = true
        return !seen || active.all(::isCall)
    }

    companion object {
        /** One of Instagram's call screens ([pattern]) is among its [active] screens. */
        fun shown(active: List<String>, pattern: Regex?): Boolean =
            pattern != null && active.any { it.isNotEmpty() && pattern.containsMatchIn(it) }
    }
}

/**
 * Whether the phone's call audio is an Instagram call. An answered Instagram call removes its
 * notification but keeps the audio in call mode, and calls are never blocked (DECISIONS.md #20), so
 * that audio keeps Instagram allowed. Only audio linked to Instagram does:
 * - an Instagram call notification shortly before (ringing, then answered);
 * - one of Instagram's call screens showing during it (a call answered with the screen off, on a
 *   headset or watch, and opened later: the notification was long gone);
 * - the audio switching into call mode while Instagram is in front (a call started there: the chat's
 *   call button, or during a visit or story pass).
 * Instagram's calls are internet calls (MODE_IN_COMMUNICATION). A phone call's MODE_IN_CALL is never
 * Instagram's: only the phone itself may set it (2026-11-21 review: a phone call answered from a
 * pop-up with Instagram in front unlocked all of Instagram until hang-up). Another app's internet
 * call (WhatsApp), even during the call button's pass, doesn't unlock Instagram either.
 */
internal class CallAudioLink {
    private var wasInCall = false
    private var linked = false

    /**
     * @param audioMode AudioManager's mode. @param callScreenShown one of Instagram's call screens is
     * active ([CallScreens.shown]). @return whether the call audio, if any, is an Instagram call.
     * Called once per check.
     */
    fun update(audioMode: Int?, instagramInFront: Boolean, instagramCallSeenRecently: Boolean, callScreenShown: Boolean): Boolean {
        val anyCall = audioMode == MODE_IN_CALL || audioMode == MODE_IN_COMMUNICATION
        // Starting while Instagram is in front links only an internet call: a phone call answered from
        // a pop-up during a pass mustn't. Instagram's own signs (its call notification, its call
        // screen) link either mode: its calls are self-managed internet calls, which Android plays as
        // MODE_IN_COMMUNICATION, but a phone maker may report them as MODE_IN_CALL, and covering a
        // real call is the one thing that must never happen (DECISIONS.md #20).
        linked = anyCall &&
            (linked || instagramCallSeenRecently || callScreenShown || (isCallAudio(audioMode) && !wasInCall && instagramInFront))
        wasInCall = anyCall
        return linked
    }

    companion object {
        // AudioManager's modes (plain ints, so tests run on the JVM).
        const val MODE_IN_CALL = 2
        const val MODE_IN_COMMUNICATION = 3

        /** An internet call's audio, as Instagram's calls use; a phone call's is MODE_IN_CALL. */
        fun isCallAudio(audioMode: Int?): Boolean = audioMode == MODE_IN_COMMUNICATION
    }
}

/** What a running pass allows inside the Instagram app (pure, for tests). How often: see [PassBook]. */
internal object PassRules {
    /**
     * Android's own screens that Instagram opens along the way: the permission dialog, the photo
     * picker ("Select photos" / "Select more photos" with limited access), the file picker. Going
     * there is not leaving Instagram, so a pass isn't ended (it was: the picker bounced the user
     * back to Throwback on the owner's phone).
     */
    val FLOW_PACKAGES = setOf(
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.google.android.providers.media.module", "com.android.providers.media.module",
        "com.google.android.photopicker", "com.android.photopicker",
        "com.google.android.documentsui", "com.android.documentsui",
    )

    fun partOfInstagramFlow(pkg: String, extra: Set<String> = emptySet()): Boolean = pkg in FLOW_PACKAGES || pkg in extra

    /**
     * Story pass (owner, 2026-11-20): Instagram's story camera, gallery and editor share the app's main
     * screen with the feed, so the user gets a few minutes of Instagram ([limitMillis], counted only
     * while the phone's camera is off: taking photos never uses it up). False when it should end.
     */
    fun storyPassHolds(cameraOffMillis: Long, limitMillis: Long): Boolean = cameraOffMillis <= limitMillis

    /** Camera-off time (the feed, Reels…) a story pass left early may have used and still be a change of mind. */
    const val FREE_LEAVE_CAMERA_OFF_MS = 30_000L

    /** Even a story pass left early is followed by this wait, so leaving early can't be repeated back to back. */
    const val FREE_LEAVE_WAIT_MS = 10 * 60_000L

    /**
     * A story pass left within [freeLeaveMillis] of its start was a change of mind (owner): it doesn't
     * count. Only if it showed almost nothing but the camera, though, since the story link opens on
     * Instagram's feed: otherwise "Your story", ~3 minutes of feed, Home, again… would never count.
     */
    fun storyLeftEarly(sinceGrant: Long, cameraOffMillis: Long, freeLeaveMillis: Long): Boolean =
        sinceGrant < freeLeaveMillis && cameraOffMillis <= FREE_LEAVE_CAMERA_OFF_MS

    /** Instagram's own redirects while the create (or settings) screen opens, from its first screen on. */
    const val CREATE_GRACE_MS = 3_000L

    /**
     * A create pass stays only while Instagram shows a creation screen ([screens], from the rules);
     * any other screen (the feed after posting, Reels) ends it. Fails closed: without a usable
     * pattern or a known screen, only the grace while the editor opens ([inFrontFor]: see
     * GuardPass.inFrontFor).
     */
    fun createPassHolds(className: String?, screens: Regex?, inFrontFor: Long): Boolean =
        inFrontFor < CREATE_GRACE_MS || onScreens(className, screens)

    /** [className] is one of [screens] (a creation or settings screen). Unknown screen or no pattern: no. */
    fun onScreens(className: String?, screens: Regex?): Boolean =
        className != null && screens != null && screens.containsMatchIn(className)

    /**
     * A screens pattern from the rules (`appCreate.screens`, `appSettings.screens`); null if it is
     * invalid or matches every screen (e.g. empty), so a bad rule can't open the whole app.
     */
    fun screensPattern(pattern: String?): Regex? =
        pattern?.let { runCatching { Regex(it) }.getOrNull() }?.takeUnless { it.containsMatchIn("") }
}

/**
 * Which cameras Instagram is using, for the story pass (pure, for tests). Android tells every app
 * that a camera is in use, not by whom: a camera counts as Instagram's when it went into use while
 * Instagram was in front. A camera another app opened before (a camera app next to Instagram in split
 * screen) stays someone else's until it is released.
 */
internal class CameraUse {
    private val byInstagram = mutableSetOf<String>()

    @Synchronized
    fun opened(cameraId: String, instagramInFront: Boolean) {
        if (instagramInFront) byInstagram += cameraId else byInstagram -= cameraId
    }

    @Synchronized
    fun closed(cameraId: String) {
        byInstagram -= cameraId
    }

    @Synchronized
    fun instagramCamera(): Boolean = byInstagram.isNotEmpty()
}
