package app.throwback.connect

/**
 * A short pass for when Throwback itself opens the Instagram app (see CallInApp; how often: PassBook).
 * Ends early once the user has been in Instagram and left it, so time can't be saved up; each kind
 * has its own rules on top (GuardService).
 */
internal object GuardPass {
    enum class Kind {
        /** The chat's call button: ends once a call runs and its call screen is up (the call then keeps its screens). */
        CALL,

        /** "Open Instagram for 5 minutes". */
        VISIT,

        /** "+": posting from the Instagram app, only its creation screens. */
        CREATE,

        /** "Open settings in the Instagram app": its settings screens. */
        SETTINGS,

        /** "Your story": a few minutes of Instagram, charged to the story budget when it ends (DECISIONS.md #34cc). */
        STORY,
    }

    /** Time that uses a SETTINGS pass up: on the app's screens other than its settings (see GuardService). */
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

    /** When Instagram was first in front during this pass (elapsed millis), or null. */
    @Volatile
    var firstInFrontAt: Long? = null
        private set

    @Synchronized
    fun grant(now: Long, durationMs: Long, kind: Kind) {
        grantedAt = now
        outsideMillis = 0L
        allowUntil = now + durationMs
        this.kind = kind
        firstInFrontAt = null
    }

    /** Instagram is in front now (the guard calls this at each check during a pass). */
    fun inFront(now: Long) {
        if (firstInFrontAt == null) firstInFrontAt = now
    }

    /**
     * How long since Instagram first came to the front in this pass (0 before). The screen rules'
     * grace counts from then, not from the grant: Instagram can take seconds to start.
     */
    fun inFrontFor(now: Long): Long = firstInFrontAt?.let { now - it } ?: 0L

    fun active(now: Long): Boolean = now < allowUntil

    @Synchronized
    fun end() {
        allowUntil = 0L
    }

    /**
     * Ends the pass if it still runs; true only for the one caller that ended it. The guard (left, or
     * time up) and the relay (posted) can both see a story pass end: only one may charge it.
     */
    @Synchronized
    fun endIfActive(now: Long): Boolean {
        if (!active(now)) return false
        allowUntil = 0L
        return true
    }
}
