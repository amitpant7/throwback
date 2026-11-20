package app.throwback.guard

import app.throwback.relay.CallSnapshot

/**
 * Follows which app is in the foreground from usage events (UsageStatsManager), in order.
 * Pure Kotlin: the service feeds it (type, package) pairs.
 */
internal class ForegroundTracker {
    var current: String? = null
        private set

    fun onEvent(type: Int, packageName: String?) {
        packageName ?: return
        when (type) {
            ACTIVITY_RESUMED -> current = packageName
            ACTIVITY_PAUSED, ACTIVITY_STOPPED -> if (current == packageName) current = null
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

/** A short pass for when Throwback itself opens the Instagram app (call button, "Open Instagram for 5 minutes", quieter-phone tip). */
internal object GuardPass {
    const val SETUP_PASS_MS = 10 * 60 * 1000L

    @Volatile
    var allowUntil: Long = 0L
        private set

    fun grant(now: Long, durationMs: Long = SETUP_PASS_MS) {
        allowUntil = now + durationMs
    }
}
