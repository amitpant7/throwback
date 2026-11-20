package app.throwback.guard

import app.throwback.relay.CallSnapshot

/**
 * Follows the apps on screen from usage events (UsageStatsManager), in order. Pure Kotlin: the
 * service feeds it (type, package, class).
 *
 * [current] is the app last brought to the front (for the passes' bookkeeping). Being on screen is
 * followed per app: from an activity's RESUMED until its STOPPED or DESTROYED (Android 10+), so an
 * app in split screen, a pop-up window or picture-in-picture still counts. There it stays resumed,
 * or only pauses, and switching between windows logs nothing. Without STOPPED events (below
 * Android 10, [stopEvents] false) PAUSED ends it.
 */
internal class ForegroundTracker(private val stopEvents: Boolean = true) {
    var current: String? = null
        private set

    /** The screen (activity class) of [current], when known. */
    var currentClass: String? = null
        private set

    /** The visible activities (classes) of each app. */
    private val visible = HashMap<String, MutableSet<String>>()

    fun onEvent(type: Int, packageName: String?, className: String? = null) {
        packageName ?: return
        when (type) {
            ACTIVITY_RESUMED -> {
                current = packageName
                currentClass = className
                visible.getOrPut(packageName) { HashSet() }.add(className.orEmpty())
            }
            ACTIVITY_PAUSED -> {
                leftFront(packageName, className)
                if (!stopEvents) hidden(packageName, className)
            }
            ACTIVITY_STOPPED, ACTIVITY_DESTROYED -> {
                leftFront(packageName, className)
                hidden(packageName, className)
            }
        }
    }

    /** Whether any of [packages] is on screen, in front or not. */
    fun onScreen(packages: Set<String>): Boolean = packages.any { !visible[it].isNullOrEmpty() }

    /** The screen went off: nothing is on screen; the events replayed from then on rebuild it. */
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

    private fun hidden(packageName: String, className: String?) {
        val classes = visible[packageName] ?: return
        classes.remove(className.orEmpty())
        if (classes.isEmpty()) visible.remove(packageName)
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
     * @param instagramOnScreen an Instagram app is on screen, in front or not (see ForegroundTracker).
     * @param calls the relay's call state. If the relay isn't connected, calls can't be seen, so the
     *        guard allows everything: a call must never be blocked (DECISIONS.md #20).
     * @param allowUntil a pass (Throwback itself opened the Instagram app) runs until then.
     * @param coverShownAt when the cover went up, or null while it is down.
     * @param noCoverUntil the cover stepped aside (or couldn't be shown) until then.
     * @param callAudioAt the last time the phone's audio was in call mode for an Instagram call, or
     *        null. Instagram shows no notification once a call is answered (seen on the owner's
     *        phone), so the call notification alone ended the call too early.
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
    ): GuardAction {
        val blocked = instagramOnScreen && !allowed(now, calls, graceMillis, allowUntil, callAudioAt)
        if (coverShownAt == null) return if (blocked && now >= noCoverUntil) GuardAction.COVER else GuardAction.NONE
        val shownFor = now - coverShownAt
        return when {
            blocked -> if (shownFor >= COVER_HOLD_MAX_MS) GuardAction.STEP_ASIDE else GuardAction.NONE
            instagramOnScreen -> GuardAction.UNCOVER // a call or a pass allows it now: at once
            shownFor >= COVER_MIN_MS -> GuardAction.UNCOVER
            else -> GuardAction.NONE
        }
    }

    /** Instagram may be on screen now: a call (or just after one), Instagram call audio, or a pass. */
    private fun allowed(now: Long, calls: CallSnapshot, graceMillis: Long, allowUntil: Long, callAudioAt: Long?): Boolean =
        !calls.listenerConnected ||
            calls.inCallOrGrace(now, graceMillis) ||
            (callAudioAt != null && now - callAudioAt in 0..graceMillis) ||
            now < allowUntil
}

/**
 * Whether the phone's call audio (MODE_IN_CALL / MODE_IN_COMMUNICATION) is an Instagram call. An
 * answered Instagram call removes its notification but keeps the audio in call mode, and calls are
 * never blocked (DECISIONS.md #20), so that audio keeps Instagram allowed. Only audio linked to
 * Instagram does, though: a call notification from Instagram shortly before (ringing, then
 * answered), or the audio switching into call mode while Instagram is in front (a call started
 * there: the chat's call button, or during a visit or story pass). A phone or WhatsApp call, even
 * during the call button's pass, doesn't unlock Instagram.
 */
internal class CallAudioLink {
    private var wasInCall = false
    private var linked = false

    /** @return whether the call audio, if any, is an Instagram call. Called once per check. */
    fun update(inCallAudio: Boolean, instagramInFront: Boolean, instagramCallSeenRecently: Boolean): Boolean {
        linked = inCallAudio && (linked || instagramCallSeenRecently || (!wasInCall && instagramInFront))
        wasInCall = inCallAudio
        return linked
    }
}

/**
 * A short pass for when Throwback itself opens the Instagram app (see CallInApp; how often: PassBook).
 * Ends early once the user has been in Instagram and left it, so time can't be saved up; each kind
 * has its own rules on top (GuardService).
 */
internal object GuardPass {
    enum class Kind {
        /** The chat's call button: ends once the call runs (the call itself keeps Instagram allowed). */
        CALL,

        /** "Open Instagram for 5 minutes". */
        VISIT,

        /** "+": posting from the Instagram app, only its creation screens. */
        CREATE,

        /** "Open settings in the Instagram app": its settings screens. */
        SETTINGS,

        /** "Your story": a few minutes of Instagram, not counting time with the camera on. */
        STORY,
    }

    /**
     * Time that uses the pass up: SETTINGS, time on the app's screens other than its settings;
     * STORY, time with the camera off (see GuardService).
     */
    @Volatile
    var outsideMillis: Long = 0L

    /** When the current pass began (elapsed millis). */
    @Volatile
    var grantedAt: Long = 0L
        private set

    /** The pass allows Instagram until then (elapsed millis); 0 when no pass runs. */
    @Volatile
    var allowUntil: Long = 0L
        private set

    @Volatile
    var kind: Kind = Kind.VISIT
        private set

    /** Instagram was in front during this pass. */
    @Volatile
    var sawInstagram: Boolean = false

    fun grant(now: Long, durationMs: Long, kind: Kind) {
        grantedAt = now
        outsideMillis = 0L
        allowUntil = now + durationMs
        this.kind = kind
        sawInstagram = false
    }

    fun active(now: Long): Boolean = now < allowUntil

    fun end() {
        allowUntil = 0L
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

    /** Instagram's own redirects while the create screen opens. */
    const val CREATE_GRACE_MS = 3_000L

    /**
     * A create pass stays only while Instagram shows a creation screen ([screens], from the rules);
     * any other screen (the feed after posting, Reels) ends it. Fails closed: without a usable
     * pattern or a known screen, only the grace while the editor opens.
     */
    fun createPassHolds(className: String?, screens: Regex?, sinceGrant: Long): Boolean =
        sinceGrant < CREATE_GRACE_MS || onScreens(className, screens)

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
