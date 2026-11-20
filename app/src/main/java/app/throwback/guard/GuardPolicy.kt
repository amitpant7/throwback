package app.throwback.guard

import app.throwback.relay.CallSnapshot

/**
 * Follows which app is in the foreground from usage events (UsageStatsManager), in order.
 * Pure Kotlin: the service feeds it (type, package) pairs.
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
            ACTIVITY_PAUSED, ACTIVITY_STOPPED -> if (current == packageName && (className == null || className == currentClass)) {
                current = null
                currentClass = null
            }
        }
    }

    companion object {
        // UsageEvents.Event constants (ACTIVITY_RESUMED == MOVE_TO_FOREGROUND on older Androids).
        const val ACTIVITY_RESUMED = 1
        const val ACTIVITY_PAUSED = 2
        const val ACTIVITY_STOPPED = 23
    }
}

internal enum class GuardAction { NONE, COVER }

/**
 * The guard's decision (DECISIONS.md #27), pure so it is unit-tested with a fake clock.
 * All times are elapsed-realtime millis.
 */
internal object GuardPolicy {
    /** Don't cover again while the previous cover is still taking the user back. */
    const val COVER_COOLDOWN_MS = 2_500L

    /**
     * @param foreground the app on screen, or null if unknown.
     * @param watched the Instagram packages.
     * @param calls the relay's call state. If the relay isn't connected, calls can't be seen, so the
     *        guard does nothing at all: a call must never be blocked (DECISIONS.md #20).
     * @param allowUntil Throwback itself opened the Instagram app (setup) and allows it until then.
     * @param lastCoverAt when the guard last covered the app, or null.
     * @param callAudioAt the last time the phone's audio was in call mode (a voice/video call is
     *        running), or null. Instagram shows no notification once a call is answered (seen on the
     *        owner's phone), so the call notification alone ended the call too early.
     */
    fun decide(
        foreground: String?,
        watched: Set<String>,
        now: Long,
        calls: CallSnapshot,
        graceMillis: Long,
        allowUntil: Long = 0L,
        lastCoverAt: Long? = null,
        callAudioAt: Long? = null,
    ): GuardAction = when {
        foreground == null || foreground !in watched -> GuardAction.NONE
        !calls.listenerConnected -> GuardAction.NONE
        calls.inCallOrGrace(now, graceMillis) -> GuardAction.NONE
        callAudioAt != null && now - callAudioAt in 0..graceMillis -> GuardAction.NONE
        now < allowUntil -> GuardAction.NONE
        lastCoverAt != null && now - lastCoverAt in 0 until COVER_COOLDOWN_MS -> GuardAction.NONE
        else -> GuardAction.COVER
    }
}

/**
 * A short pass for when Throwback itself opens the Instagram app: the chat's call button ([Kind.CALL])
 * or "Open Instagram for 5 minutes" ([Kind.VISIT]). Ends early once the user has been in Instagram
 * and left it, so time can't be saved up; a call pass also ends once the call is running (the call
 * itself keeps Instagram allowed).
 */
internal object GuardPass {
    /** CREATE: posting from the Instagram app ("+" / "Your story"), only its creation screens. */
    enum class Kind { CALL, VISIT, CREATE, SETTINGS, STORY }

    /** STORY: since when the camera has been off (editing), or null while it's on. */
    @Volatile
    var cameraOffSince: Long? = null

    /** SETTINGS: time spent on the app's screens other than its settings (capped, see GuardService). */
    @Volatile
    var outsideMillis: Long = 0L

    /** When the current pass began (elapsed millis). */
    @Volatile
    var grantedAt: Long = 0L
        private set

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
        cameraOffSince = null
        allowUntil = now + durationMs
        this.kind = kind
        sawInstagram = false
    }

    fun active(now: Long): Boolean = now < allowUntil

    fun end() {
        allowUntil = 0L
    }
}

/** How often the passes can be used (pure, for tests). */
internal object PassRules {
    const val CALL_TAPS_PER_HOUR = 3
    private const val HOUR_MS = 3_600_000L

    fun visitAllowed(lastVisitDay: Long?, today: Long, locked: Boolean): Boolean =
        !locked && lastVisitDay != today

    /** [taps]: wall times of earlier call-button passes. */
    fun callAllowed(taps: List<Long>, now: Long): Boolean = taps.count { now - it in 0 until HOUR_MS } < CALL_TAPS_PER_HOUR

    fun recentTaps(taps: List<Long>, now: Long): List<Long> = taps.filter { now - it in 0 until HOUR_MS }

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

    /** A story pass is available: fewer than [perDay] today, and no wait running ([waitUntilWall], wall ms). */
    fun storyAllowed(lastDay: Long?, countThatDay: Int, today: Long, waitUntilWall: Long?, nowWall: Long, perDay: Int): Boolean {
        if (lastDay == today && countThatDay >= perDay) return false
        return waitUntilWall == null || nowWall >= waitUntilWall
    }

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
     * any other screen (the feed after posting, Reels) ends it. Unknown screen: kept.
     */
    fun createPassHolds(className: String?, screens: Regex?, sinceGrant: Long): Boolean =
        sinceGrant < CREATE_GRACE_MS || className == null || screens == null || screens.containsMatchIn(className)
}
