package app.throwback.connect.guard

import app.throwback.connect.CallSnapshot

/**
 * Which app (and screen) is in front, from usage events: the last activity resumed, until it pauses
 * (every activity pauses before it leaves the front; a stop comes later, sometimes after a new
 * instance of the same screen was resumed, so stops are ignored). Only the app in front counts: split screen and floating windows aren't followed
 * (owner, 2026-11-21, DECISIONS.md #34aa), and opening any other app (the home screen too) ends it at
 * once, so a missed event can't leave Instagram "on screen". Pure, for tests.
 */
internal class ForegroundTracker {
    var current: String? = null
        private set

    /** The screen (activity class) of [current], when known. */
    var currentClass: String? = null
        private set

    fun onEvent(type: Int, packageName: String?, className: String? = null) {
        packageName ?: return
        when (type) {
            ACTIVITY_RESUMED -> {
                current = packageName
                currentClass = className
            }
            ACTIVITY_PAUSED -> leftFront(packageName, className)
        }
    }

    /** Whether one of [packages] is the app in front. */
    fun inFront(packages: Set<String>): Boolean = current in packages

    /** The screen in front (activity class; "" when unknown) when it belongs to one of [packages]. */
    fun activeScreens(packages: Set<String>): List<String> =
        if (current in packages) listOf(currentClass.orEmpty()) else emptyList()

    /** Start over (the screen went off, or a check from scratch): the events replayed next rebuild it. */
    fun clear() {
        current = null
        currentClass = null
    }

    private fun leftFront(packageName: String, className: String?) {
        if (current == packageName && (className == null || className == currentClass)) {
            current = null
            currentClass = null
        }
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

    /** Take the cover away: Instagram left the front, or a call or a pass allows it now. */
    UNCOVER,
}

/**
 * The guard's decision (DECISIONS.md #27), pure so it is unit-tested with a fake clock.
 * All times are elapsed-realtime millis.
 */
internal object GuardPolicy {
    /** The cover stays at least this long, so it doesn't flash while Throwback comes to the front. */
    const val COVER_MIN_MS = 900L

    /**
     * During a call that keeps to its screens (CallScreens.restricting), a pass counts only this long
     * after it was granted: enough for Instagram to open the chat, never the rest of the app.
     */
    const val CALL_PASS_BRIDGE_MS = 4_000L

    /** The cover couldn't be shown (no "display over other apps"): the next try waits this long. */
    const val NO_OVERLAY_RETRY_MS = 2_500L

    /**
     * @param instagramOnScreen the Instagram app is the app in front (see ForegroundTracker).
     * @param calls the relay's call state. If the relay isn't connected, calls can't be seen, so the
     *        guard allows everything: a call must never be blocked (DECISIONS.md #20).
     * @param allowUntil a pass (Throwback itself opened the Instagram app) runs until then.
     * @param coverShownAt when the cover went up, or null while it is down.
     * @param noCoverUntil the cover couldn't be shown; no new try until then.
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
            blocked -> GuardAction.NONE // it stays while Instagram is in front and not allowed
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
 * During an Instagram call only its call screens, and the chat a minimised call shows (with its
 * return-to-call banner), may be on screen (DECISIONS.md #34b, #34z): browsing the rest of
 * Instagram next to a call, or in front of it, is covered. That starts once a call screen has been
 * seen in this call; until then (Instagram renamed it, or it hasn't shown yet) all of Instagram is
 * allowed, as before, so a call is never cut off. Pure, for tests.
 */
internal class CallScreens {
    private var seen = false

    /** A call runs and keeps to its screens now: a pass then only bridges the way into its chat. */
    var restricting = false
        private set

    /**
     * Called once per check. @param inCall an Instagram call runs. @param active Instagram's screen in
     * front (ForegroundTracker.activeScreens; "" when unknown, which counts as a call screen), or none.
     * @param pattern `appCall.screens`.
     * @param chat `appCall.chatScreens`, also allowed during the call. @return whether the call
     * allows what is on screen.
     */
    fun allow(inCall: Boolean, active: List<String>, pattern: Regex?, chat: Regex? = null): Boolean {
        if (!inCall || pattern == null) {
            seen = false
            restricting = false
            return true
        }
        fun isCall(screen: String) = screen.isEmpty() || pattern.containsMatchIn(screen) || chat?.containsMatchIn(screen) == true
        if (shown(active, pattern)) seen = true
        restricting = seen
        return !seen || active.all(::isCall)
    }

    companion object {
        /** One of Instagram's call screens ([pattern]) is among its [active] screens. */
        fun shown(active: List<String>, pattern: Regex?): Boolean =
            pattern != null && active.any { it.isNotEmpty() && pattern.containsMatchIn(it) }
    }
}

/**
 * An Instagram call recognised by what Android shows of it, for phones where neither its call
 * notification (its channel turned off) nor the call audio mode gives it away (DECISIONS.md #34y,
 * seen on the owner's phone 2026-11-21): it starts when Instagram's call screen is on screen while
 * voice-call sound plays (Android's sound label, never the sound itself), and lasts while that sound
 * plays, with [GAP_MS] of silence allowed. Pure, for tests.
 */
internal class AppCallSession(private val gapMs: Long = GAP_MS) {
    private var lastVoiceAt: Long? = null

    var active = false
        private set

    /**
     * Called once per check. @param callScreenShown one of Instagram's call screens is among its
     * active screens. @param voiceAudio some app plays sound labelled as a voice call.
     */
    fun update(now: Long, callScreenShown: Boolean, voiceAudio: Boolean): Boolean {
        if (voiceAudio) lastVoiceAt = now
        if (!active && callScreenShown && voiceAudio) active = true
        if (active && lastVoiceAt.let { it == null || now - it > gapMs }) active = false
        return active
    }

    companion object {
        const val GAP_MS = 4_000L
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

