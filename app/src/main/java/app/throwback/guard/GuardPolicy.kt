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
    enum class Kind { CALL, VISIT, CREATE }

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
    const val VISITS_PER_DAY = 1
    const val CALL_TAPS_PER_HOUR = 3
    private const val HOUR_MS = 3_600_000L

    fun visitAllowed(lastVisitDay: Long?, today: Long, locked: Boolean): Boolean =
        !locked && lastVisitDay != today

    /** [taps]: wall times of earlier call-button passes. */
    fun callAllowed(taps: List<Long>, now: Long): Boolean = taps.count { now - it in 0 until HOUR_MS } < CALL_TAPS_PER_HOUR

    fun recentTaps(taps: List<Long>, now: Long): List<Long> = taps.filter { now - it in 0 until HOUR_MS }

    /** Instagram's own redirects while the create screen opens. */
    const val CREATE_GRACE_MS = 3_000L

    /**
     * A create pass stays only while Instagram shows a creation screen ([screens], from the rules);
     * any other screen (the feed after posting, Reels) ends it. Unknown screen: kept.
     */
    fun createPassHolds(className: String?, screens: Regex?, sinceGrant: Long): Boolean =
        sinceGrant < CREATE_GRACE_MS || className == null || screens == null || screens.containsMatchIn(className)
}
