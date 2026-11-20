package app.throwback.timelimit.engine

import app.throwback.core.LockState
import app.throwback.core.WebMode
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The daily time limit as a pure state machine: every function takes the current state and a
 * clock reading and returns a new state. No Android, no I/O, no hidden clock.
 *
 * Time model (anti-cheat, DECISIONS.md #21):
 * - Durations (usage, the 5-minute wait, the 3-day PIN reset, PIN blocks) are measured with the
 *   monotonic clock within a boot. Across a reboot the trusted wall clock is the only option.
 * - The engine keeps a "trusted" wall time: within a boot it is the last reading advanced by
 *   monotonic time, so moving the phone's clock (either way) changes nothing until a reboot.
 *   Small differences (network time corrections) are followed.
 * - The current day is the local date of the trusted time, but never earlier than the latest day
 *   seen, so moving the clock back can't reset usage.
 * - Usage only counts while a FULL-mode session is open, and any single counted interval is capped.
 *
 * PIN checks: functions that need the PIN take `pinOk`, the result of [PinHasher.verify], because
 * hashing is slow and the caller runs it off the main thread. The engine still owns the rules
 * around it (wrong-attempt counting, blocks, no PIN use during a reset).
 */
class TimeLimitEngine {

    /** The time base for one call: the raw reading plus the trusted wall time derived from it. */
    private class At(val reading: ClockReading, val wall: Long) {
        val moment get() = Moment(reading.bootCount, reading.elapsedMillis, wall)
        val date: LocalDate get() = Instant.ofEpochMilli(wall).atZone(reading.zone).toLocalDate()
    }

    // ---- Clock -----------------------------------------------------------------------------

    private fun sameBoot(m: Moment, r: ClockReading) =
        m.bootCount == r.bootCount && r.elapsedMillis >= m.elapsedMillis

    /** The wall time Throwback believes, given the last [anchor]. */
    internal fun trustedWall(anchor: Moment?, r: ClockReading): Long {
        if (anchor == null || !sameBoot(anchor, r)) return r.wallMillis
        val projected = anchor.wallMillis + (r.elapsedMillis - anchor.elapsedMillis)
        return if (abs(r.wallMillis - projected) <= Rules.CLOCK_RESYNC_TOLERANCE_MILLIS) r.wallMillis else projected
    }

    private fun at(s: TimeLimitState, r: ClockReading) = At(r, trustedWall(s.anchor, r))

    /** Real time since [m]: monotonic within a boot, trusted wall time across reboots. */
    private fun millisSince(m: Moment, at: At): Long =
        if (sameBoot(m, at.reading)) at.reading.elapsedMillis - m.elapsedMillis
        else max(0L, at.wall - m.wallMillis)

    private fun today(s: TimeLimitState, at: At): LocalDate = s.day?.let { maxOf(it, at.date) } ?: at.date

    // ---- Keeping up with time --------------------------------------------------------------

    /**
     * Brings [s] up to date: counts usage of the open session, starts a new day at local midnight,
     * applies pending changes that are due and records when a lock begins.
     */
    fun refresh(s: TimeLimitState, r: ClockReading): TimeLimitState = sync(s, r).first

    private fun sync(s: TimeLimitState, r: ClockReading): Pair<TimeLimitState, At> {
        val at = at(s, r)
        var st = s.copy(anchor = at.moment)
        st = account(st, at)
        st = rollover(st, at)
        st = applyPending(st, at)
        st = settleLock(st, at)
        st = expirePinBlock(st, at)
        return st to at
    }

    private fun account(s: TimeLimitState, at: At): TimeLimitState {
        val session = s.session ?: return s
        val r = at.reading
        val delta = if (session.bootCount == r.bootCount && r.elapsedMillis >= session.lastElapsedMillis) {
            r.elapsedMillis - session.lastElapsedMillis
        } else {
            0L // Rebooted since: the old session is meaningless.
        }
        val counted = min(delta, Rules.MAX_COUNTED_INTERVAL_MILLIS)
        val counts = session.mode == WebMode.FULL && s.enabled && !s.grantShowing && !s.overBudget
        val used = if (counts) min(s.usedMillis + counted, s.budgetMillis) else s.usedMillis
        return s.copy(usedMillis = used, session = session.copy(lastElapsedMillis = r.elapsedMillis, bootCount = r.bootCount))
    }

    private fun rollover(s: TimeLimitState, at: At): TimeLimitState {
        val today = today(s, at)
        if (s.day == today) return s
        if (s.day == null) return s.copy(day = today)
        return s.copy(
            day = today,
            usedMillis = 0,
            extensionsUsed = 0,
            lockedAt = null,
            grantShowing = false,
        )
    }

    private fun applyPending(s: TimeLimitState, at: At): TimeLimitState {
        val today = s.day ?: return s
        var st = s
        st.pendingPin?.let { if (!it.effective.isAfter(today)) st = st.copy(pin = it.pin, pendingPin = null) }
        st.pendingLimit?.let { p ->
            if (!p.effective.isAfter(today)) {
                st = if (p.minutes == null) turnedOff(st) else st.copy(limitMinutes = p.minutes, pendingLimit = null)
            }
        }
        st.pendingGuardOff?.let { if (!it.isAfter(today)) st = st.copy(pendingGuardOff = null, guardOffDue = true) }
        return st
    }

    /** No limit any more: nothing protects a scheduled guard turn-off, so it is due at once. */
    private fun turnedOff(s: TimeLimitState) = TimeLimitState(
        day = s.day, anchor = s.anchor, session = s.session,
        guardOffDue = s.guardOffDue || s.pendingGuardOff != null,
    )

    /**
     * Records when a lock begins, and clears it when time is available again. While locked, usage
     * is held at the budget (e.g. after lowering the limit below today's usage), so an extension
     * always gives exactly +5 minutes.
     */
    private fun settleLock(s: TimeLimitState, at: At): TimeLimitState = when {
        s.overBudget -> s.copy(usedMillis = s.budgetMillis, lockedAt = s.lockedAt ?: at.moment)
        s.lockedAt != null -> s.copy(lockedAt = null)
        else -> s
    }

    private fun expirePinBlock(s: TimeLimitState, at: At): TimeLimitState {
        val blockedAt = s.pinBlockedAt ?: return s
        return if (millisSince(blockedAt, at) >= Rules.PIN_BLOCK_MILLIS) s.copy(pinBlockedAt = null) else s
    }

    // ---- Foreground / background -----------------------------------------------------------

    /** Throwback's main screen is in use in [mode]. Called again whenever the mode changes. */
    fun foreground(s: TimeLimitState, r: ClockReading, mode: WebMode): TimeLimitState {
        val (st, _) = sync(s, r)
        return st.copy(session = Session(mode, r.elapsedMillis, r.bootCount))
    }

    /** Throwback's main screen is gone. Counts up to now and closes the session. */
    fun background(s: TimeLimitState, r: ClockReading): TimeLimitState {
        val (st, _) = sync(s, r)
        return st.copy(session = null, grantShowing = false)
    }

    // ---- Setup and settings ----------------------------------------------------------------

    /** Turns the limit on. Only possible when no limit exists. */
    fun setup(s: TimeLimitState, r: ClockReading, minutes: Int, pin: PinHash): Outcome {
        val (st, at) = sync(s, r)
        if (st.enabled) return Outcome(st, ActionResult.NotAllowed("A limit is already set"))
        if (minutes !in Rules.LIMIT_CHOICES) return Outcome(st, ActionResult.NotAllowed("Unsupported limit"))
        val on = TimeLimitState(
            limitMinutes = minutes,
            pin = pin,
            day = st.day,
            anchor = st.anchor,
            session = st.session,
        )
        return Outcome(settleLock(on, at), ActionResult.Done)
    }

    /**
     * Picks a new daily limit. Lower than today's limit: immediate, no PIN. The same as today's:
     * cancels any pending raise or turn-off. Higher: needs the PIN and starts tomorrow.
     */
    fun setLimit(s: TimeLimitState, r: ClockReading, minutes: Int, pinOk: Boolean? = null): Outcome {
        val (st, at) = sync(s, r)
        val current = st.limitMinutes ?: return Outcome(st, ActionResult.NotAllowed("No limit set"))
        if (minutes !in Rules.LIMIT_CHOICES) return Outcome(st, ActionResult.NotAllowed("Unsupported limit"))
        return when {
            minutes < current -> Outcome(settleLock(st.copy(limitMinutes = minutes, pendingLimit = null), at), ActionResult.Done)
            minutes == current -> Outcome(st.copy(pendingLimit = null), ActionResult.Done)
            else -> withPin(st, at, pinOk) { ok ->
                val effective = ok.day!!.plusDays(1)
                Outcome(ok.copy(pendingLimit = PendingLimit(minutes, effective)), ActionResult.Scheduled(effective))
            }
        }
    }

    /** Turns the limit off from tomorrow. Needs the PIN. */
    fun turnOff(s: TimeLimitState, r: ClockReading, pinOk: Boolean): Outcome {
        val (st, at) = sync(s, r)
        if (!st.enabled) return Outcome(st, ActionResult.NotAllowed("No limit set"))
        return withPin(st, at, pinOk) { ok ->
            val effective = ok.day!!.plusDays(1)
            Outcome(ok.copy(pendingLimit = PendingLimit(null, effective)), ActionResult.Scheduled(effective))
        }
    }

    /** Messages & calls off while a limit is on: needs the PIN, takes effect tomorrow. */
    fun scheduleGuardOff(s: TimeLimitState, r: ClockReading, pinOk: Boolean): Outcome {
        val (st, at) = sync(s, r)
        if (!st.enabled) return Outcome(st, ActionResult.NotAllowed("No limit set"))
        return withPin(st, at, pinOk) { ok ->
            val effective = ok.day!!.plusDays(1)
            Outcome(ok.copy(pendingGuardOff = effective), ActionResult.Scheduled(effective))
        }
    }

    fun cancelGuardOff(s: TimeLimitState, r: ClockReading): TimeLimitState = sync(s, r).first.copy(pendingGuardOff = null)

    /** The app has turned Messages & calls off. */
    fun guardOffHandled(s: TimeLimitState, r: ClockReading): TimeLimitState = sync(s, r).first.copy(guardOffDue = false)

    /** Cancels a pending raise or turn-off. Tightening never needs the PIN. */
    fun cancelPendingLimit(s: TimeLimitState, r: ClockReading): TimeLimitState =
        sync(s, r).first.copy(pendingLimit = null)

    /** Checks the PIN without changing anything else (gates the "change PIN" flow). */
    fun verifyPin(s: TimeLimitState, r: ClockReading, pinOk: Boolean): Outcome {
        val (st, at) = sync(s, r)
        return withPin(st, at, pinOk) { Outcome(it, ActionResult.Done) }
    }

    /** Schedules [newPin] for tomorrow. Needs the current PIN. */
    fun changePin(s: TimeLimitState, r: ClockReading, pinOk: Boolean, newPin: PinHash): Outcome {
        val (st, at) = sync(s, r)
        if (!st.enabled) return Outcome(st, ActionResult.NotAllowed("No limit set"))
        return withPin(st, at, pinOk) { ok ->
            val effective = ok.day!!.plusDays(1)
            Outcome(ok.copy(pendingPin = PendingPin(newPin, effective)), ActionResult.Scheduled(effective))
        }
    }

    fun cancelPendingPin(s: TimeLimitState, r: ClockReading): TimeLimitState =
        sync(s, r).first.copy(pendingPin = null)

    // ---- Extensions ------------------------------------------------------------------------

    /** +5 minutes for the right PIN, after the 5-minute wait, at most twice a day. */
    fun extend(s: TimeLimitState, r: ClockReading, pinOk: Boolean): Outcome {
        val (st, at) = sync(s, r)
        val lockedAt = st.lockedAt
        return when {
            !st.overBudget || lockedAt == null -> Outcome(st, ActionResult.NotAllowed("Not locked"))
            st.extensionsUsed >= Rules.MAX_EXTENSIONS_PER_DAY -> Outcome(st, ActionResult.NotAllowed("No extensions left today"))
            millisSince(lockedAt, at) < Rules.WAIT_MILLIS -> Outcome(st, ActionResult.NotAllowed("Still waiting"))
            else -> withPin(st, at, pinOk) { ok ->
                val extended = ok.copy(extensionsUsed = ok.extensionsUsed + 1, lockedAt = null, grantShowing = true)
                Outcome(extended, ActionResult.Done)
            }
        }
    }

    /** The user dismissed the "+5 minutes" confirmation; the extra time starts counting now. */
    fun acknowledgeGrant(s: TimeLimitState, r: ClockReading): TimeLimitState =
        sync(s, r).first.copy(grantShowing = false)

    // ---- Forgot PIN ------------------------------------------------------------------------

    fun requestPinReset(s: TimeLimitState, r: ClockReading): Outcome {
        val (st, at) = sync(s, r)
        return when {
            st.pin == null -> Outcome(st, ActionResult.NotAllowed("No PIN set"))
            st.pinResetRequestedAt != null -> Outcome(st, ActionResult.Done)
            else -> Outcome(st.copy(pinResetRequestedAt = at.moment), ActionResult.Done)
        }
    }

    fun cancelPinReset(s: TimeLimitState, r: ClockReading): TimeLimitState =
        sync(s, r).first.copy(pinResetRequestedAt = null)

    /** After the 3-day wait, [newPin] replaces the forgotten one immediately. The limit stays on. */
    fun completePinReset(s: TimeLimitState, r: ClockReading, newPin: PinHash): Outcome {
        val (st, at) = sync(s, r)
        val requested = st.pinResetRequestedAt ?: return Outcome(st, ActionResult.NotAllowed("No reset requested"))
        if (millisSince(requested, at) < Rules.PIN_RESET_MILLIS) return Outcome(st, ActionResult.NotAllowed("Reset still running"))
        val done = st.copy(
            pin = newPin,
            pendingPin = null,
            pinResetRequestedAt = null,
            wrongPinAttempts = 0,
            pinBlockedAt = null,
        )
        return Outcome(done, ActionResult.Done)
    }

    // ---- PIN gate --------------------------------------------------------------------------

    /**
     * Whether the PIN can be checked right now. Returns the refusal, or null when the caller may
     * go ahead and hash the entered PIN.
     */
    fun pinGate(s: TimeLimitState, r: ClockReading): ActionResult? {
        val at = at(s, r)
        return when {
            s.pin == null -> ActionResult.NotAllowed("No PIN set")
            s.pinResetRequestedAt != null -> ActionResult.PinResetPending
            else -> blockedFor(s, at).takeIf { it > 0 }?.let { ActionResult.PinBlocked(it) }
        }
    }

    private fun blockedFor(s: TimeLimitState, at: At): Long =
        s.pinBlockedAt?.let { max(0L, Rules.PIN_BLOCK_MILLIS - millisSince(it, at)) } ?: 0L

    /** [pinOk] null means no PIN was entered: nothing is counted, the caller is told one is needed. */
    private inline fun withPin(s: TimeLimitState, at: At, pinOk: Boolean?, onOk: (TimeLimitState) -> Outcome): Outcome {
        if (s.pin == null) return Outcome(s, ActionResult.NotAllowed("No PIN set"))
        if (s.pinResetRequestedAt != null) return Outcome(s, ActionResult.PinResetPending)
        val blocked = blockedFor(s, at)
        if (blocked > 0) return Outcome(s, ActionResult.PinBlocked(blocked))
        if (pinOk == null) return Outcome(s, ActionResult.PinRequired)
        if (pinOk) return onOk(s.copy(wrongPinAttempts = 0, pinBlockedAt = null))
        val attempts = s.wrongPinAttempts + 1
        return if (attempts >= Rules.WRONG_PINS_BEFORE_BLOCK) {
            Outcome(s.copy(wrongPinAttempts = 0, pinBlockedAt = at.moment), ActionResult.PinBlocked(Rules.PIN_BLOCK_MILLIS))
        } else {
            Outcome(s.copy(wrongPinAttempts = attempts), ActionResult.WrongPin(Rules.WRONG_PINS_BEFORE_BLOCK - attempts))
        }
    }

    // ---- View ------------------------------------------------------------------------------

    /** What the UI should show. Call it with a state that was just refreshed with the same reading. */
    fun view(s: TimeLimitState, r: ClockReading): TimeLimitView {
        val limit = s.limitMinutes ?: return TimeLimitView.Disabled.copy(guardOffDue = s.guardOffDue)
        val at = at(s, r)
        val today = today(s, at)
        val extensionsLeft = max(0, Rules.MAX_EXTENSIONS_PER_DAY - s.extensionsUsed)
        val reset = s.pinResetRequestedAt?.let {
            PinResetStatus(max(0L, Rules.PIN_RESET_MILLIS - millisSince(it, at)), Rules.PIN_RESET_MILLIS)
        }
        val locked = s.overBudget || s.grantShowing
        val phase = when {
            !locked -> null
            s.grantShowing -> LockPhase.Granted
            extensionsLeft == 0 -> LockPhase.DoneForToday(untilMidnight(today, at))
            else -> {
                val waited = s.lockedAt?.let { millisSince(it, at) } ?: 0L
                val left = max(0L, Rules.WAIT_MILLIS - waited)
                if (left > 0) LockPhase.Waiting(left, Rules.WAIT_MILLIS) else LockPhase.CanExtend(s.extensionsUsed + 1)
            }
        }
        val remaining = max(0L, s.budgetMillis - s.usedMillis)
        return TimeLimitView(
            lockState = if (locked) LockState.Locked else LockState.Running(remaining),
            limitMinutes = limit,
            today = today,
            remainingMillis = remaining,
            extensionsUsed = s.extensionsUsed,
            extensionsLeft = extensionsLeft,
            pendingGuardOff = s.pendingGuardOff,
            guardOffDue = s.guardOffDue,
            lock = phase,
            pendingLimit = s.pendingLimit,
            pendingPinEffective = s.pendingPin?.effective,
            pinReset = reset,
            pinBlockedMillis = blockedFor(s, at),
        )
    }

    private fun untilMidnight(today: LocalDate, at: At): Long {
        val midnight = today.plusDays(1).atStartOfDay(at.reading.zone).toInstant().toEpochMilli()
        return max(0L, midnight - at.wall)
    }
}
