package app.throwback.timelimit.engine

import app.throwback.core.LockState
import app.throwback.core.WebMode
import java.time.LocalDate
import java.time.ZoneId

/*
 * Data types of the time-limit engine. Pure Kotlin: no Android imports, so every rule is
 * testable on the JVM. See TimeLimitEngine for the rules themselves.
 */

/** One reading of the device clocks, taken by the caller and handed to the engine. */
data class ClockReading(
    /** Monotonic time since boot (SystemClock.elapsedRealtime). Unaffected by clock changes; resets on reboot. */
    val elapsedMillis: Long,
    /** Wall-clock time (System.currentTimeMillis). The user can change it at will. */
    val wallMillis: Long,
    /** The phone's current time zone. */
    val zone: ZoneId,
    /** Identifies the current boot (Settings.Global.BOOT_COUNT); -1 when unknown. */
    val bootCount: Int,
    /**
     * Network time, which the user can't set: Android's last time sync plus the stopwatch since
     * (Android 13+), else Instagram's server time as the page last saw it (any version). Null until
     * there is one in this boot or process.
     */
    val networkMillis: Long? = null,
)

/** Source of [ClockReading]s; Android implementation in AndroidClock, a fake one in tests. */
fun interface Clock {
    fun now(): ClockReading
}

/**
 * A remembered point in time. [wallMillis] is the engine's trusted wall time at that point, and
 * [bootCount] + [elapsedMillis] let it measure the time since then monotonically within a boot.
 */
data class Moment(val bootCount: Int, val elapsedMillis: Long, val wallMillis: Long)

/**
 * The time limit's trusted clock, for other features' daily counters (the passes into the Instagram
 * app): trusted wall ms, today as an epoch day (never goes back; a new day needs 23 h of real time)
 * and the zone days are counted in.
 */
data class TrustedTime(val wallMillis: Long, val epochDay: Long, val zone: ZoneId)

/** Salted PBKDF2 hash of a 4-digit PIN. The PIN itself is never stored. Fields are Base64. */
data class PinHash(val salt: String, val hash: String, val iterations: Int)

/** A raise of the limit, or turning it off ([minutes] == null), that waits until [effective]. */
data class PendingLimit(val minutes: Int?, val effective: LocalDate)

/** A PIN change that waits until [effective]. */
data class PendingPin(val pin: PinHash, val effective: LocalDate)

/** Throwback is in the foreground in [mode]; usage was last accounted at [lastElapsedMillis]. */
data class Session(val mode: WebMode, val lastElapsedMillis: Long, val bootCount: Int)

/** Everything the engine knows. All fields except the transient ones are persisted. */
data class TimeLimitState(
    /** Daily limit in minutes; null when no limit is set up (the default). */
    val limitMinutes: Int? = null,
    val pin: PinHash? = null,
    /** The day [usedMillis] and [extensionsUsed] belong to. Also the latest day seen: it never goes back. */
    val day: LocalDate? = null,
    /** Time counted today (foreground, FULL mode only). */
    val usedMillis: Long = 0,
    val extensionsUsed: Int = 0,
    /** When the current lock began; the 5-minute wait counts from here. Null when not locked. */
    val lockedAt: Moment? = null,
    val pendingLimit: PendingLimit? = null,
    val pendingPin: PendingPin? = null,
    /** When "Forgot PIN" was requested; null when no reset is running. */
    val pinResetRequestedAt: Moment? = null,
    /** Consecutive wrong PIN entries since the last block or correct entry. */
    val wrongPinAttempts: Int = 0,
    /** Start of a short PIN-entry block after too many wrong entries. */
    val pinBlockedAt: Moment? = null,
    /** Last clock reading, used to tell real time passing from the wall clock being changed. */
    val anchor: Moment? = null,
    /**
     * Turning Messages & calls (the guard on the Instagram app) off while a limit is on: needs the PIN
     * and takes effect the next day, like turning the limit off (owner, 2026-11-20).
     */
    val pendingGuardOff: LocalDate? = null,
    /** The scheduled guard turn-off is due; the app turns Messages & calls off and clears this. */
    val guardOffDue: Boolean = false,
    /**
     * The time zone days are counted in (ZoneId). A new phone time zone is taken over only at the next
     * midnight of this one, so switching to a zone where it's already tomorrow doesn't start a new day.
     */
    val zone: String? = null,
    /** Trusted wall time when the current [day] began; a new day needs 23 h of real time after it. */
    val dayStart: Long? = null,

    // Transient: not persisted.
    /** Open foreground interval, or null in the background. */
    val session: Session? = null,
    /** An extension was just granted and its confirmation is on screen; the lock stays up until it's dismissed. */
    val grantShowing: Boolean = false,
) {
    val enabled: Boolean get() = limitMinutes != null

    /** Today's allowance: the limit plus any extensions. */
    val budgetMillis: Long get() = (limitMinutes ?: 0) * MINUTE + extensionsUsed * Rules.EXTENSION_MILLIS

    val overBudget: Boolean get() = enabled && usedMillis >= budgetMillis

    /** The part that goes to disk. */
    fun persistent(): TimeLimitState = copy(session = null, grantShowing = false)
}

/** What happened when the UI asked for something. */
sealed interface ActionResult {
    /** Done, effective now. */
    data object Done : ActionResult

    /** Accepted, takes effect on [effective] (the next day). */
    data class Scheduled(val effective: LocalDate) : ActionResult

    /** This change needs the PIN and none was given. */
    data object PinRequired : ActionResult

    data class WrongPin(val attemptsLeft: Int) : ActionResult

    /** Too many wrong PINs; try again after [remainingMillis]. */
    data class PinBlocked(val remainingMillis: Long) : ActionResult

    /** A forgotten-PIN reset is running, so the PIN can't be used until it's cancelled or finished. */
    data object PinResetPending : ActionResult

    /** Not possible in the current state (the UI shouldn't have offered it). */
    data class NotAllowed(val reason: String) : ActionResult
}

data class Outcome(val state: TimeLimitState, val result: ActionResult)

/** Which lock screen to show. */
sealed interface LockPhase {
    /** The 5-minute wait before an extension can be requested. */
    data class Waiting(val remainingMillis: Long, val totalMillis: Long) : LockPhase

    /** The wait is over; the PIN can be entered for extension number [extensionNumber]. */
    data class CanExtend(val extensionNumber: Int) : LockPhase

    /** An extension was just granted (confirmation screen). */
    data object Granted : LockPhase

    /** Both extensions used: locked until the next day begins (local midnight, later after a time-zone change). */
    data class DoneForToday(val untilNextDayMillis: Long) : LockPhase
}

/** Running "Forgot PIN" countdown. */
data class PinResetStatus(val remainingMillis: Long, val totalMillis: Long) {
    val ready: Boolean get() = remainingMillis <= 0
}

/** Read-only summary of the state for the UI, computed at one clock reading. */
data class TimeLimitView(
    val lockState: LockState,
    val limitMinutes: Int? = null,
    val today: LocalDate? = null,
    val remainingMillis: Long = 0,
    val extensionsUsed: Int = 0,
    val extensionsLeft: Int = 0,
    /** Set when [lockState] is Locked. */
    val lock: LockPhase? = null,
    val pendingLimit: PendingLimit? = null,
    val pendingPinEffective: LocalDate? = null,
    val pinReset: PinResetStatus? = null,
    val pinBlockedMillis: Long = 0,
    /** Messages & calls turns off on this day (scheduled with the PIN). */
    val pendingGuardOff: LocalDate? = null,
    /** That day has come: the app should turn Messages & calls off now. */
    val guardOffDue: Boolean = false,
) {
    /** Minutes of today's allowance (limit plus extensions). */
    val budgetMinutes: Int get() = (limitMinutes ?: 0) + extensionsUsed * Rules.EXTENSION_MINUTES

    companion object {
        val Disabled = TimeLimitView(lockState = LockState.Disabled)
    }
}

internal const val SECOND = 1_000L
internal const val MINUTE = 60 * SECOND
internal const val HOUR = 60 * MINUTE
internal const val DAY = 24 * HOUR

/** The product rules (DECISIONS.md #16-22). */
object Rules {
    val LIMIT_CHOICES = listOf(15, 30, 45, 60)
    const val EXTENSION_MINUTES = 5

    /** Shortest real day (a daylight-saving day); a time-zone switch can't start days faster. */
    const val MIN_DAY_MILLIS = 23 * 60 * 60 * 1000L
    const val EXTENSION_MILLIS = EXTENSION_MINUTES * MINUTE
    const val WAIT_MILLIS = 5 * MINUTE
    const val MAX_EXTENSIONS_PER_DAY = 2
    const val PIN_RESET_MILLIS = 3 * DAY
    const val PIN_LENGTH = 4

    /** No single counted interval is longer than this, so a missed onBackground can't bill hours. */
    const val MAX_COUNTED_INTERVAL_MILLIS = 60 * SECOND

    const val WRONG_PINS_BEFORE_BLOCK = 5
    const val PIN_BLOCK_MILLIS = 30 * SECOND
}
