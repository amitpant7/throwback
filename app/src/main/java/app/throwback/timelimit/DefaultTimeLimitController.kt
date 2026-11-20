package app.throwback.timelimit

import app.throwback.core.LockState
import app.throwback.core.TimeLimitController
import app.throwback.core.WebMode
import app.throwback.timelimit.data.StateStore
import app.throwback.timelimit.engine.ActionResult
import app.throwback.timelimit.engine.Clock
import app.throwback.timelimit.engine.ClockReading
import app.throwback.timelimit.engine.Outcome
import app.throwback.timelimit.engine.PinHasher
import app.throwback.timelimit.engine.TimeLimitEngine
import app.throwback.timelimit.engine.TimeLimitState
import app.throwback.timelimit.engine.TimeLimitView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app-wide [TimeLimitController]: wraps [TimeLimitEngine] with persistence, a once-a-second
 * tick while Throwback is in the foreground, and the PIN hashing (off the main thread).
 *
 * [onForeground] / [onBackground] update [state] synchronously, so AppRoot never draws a frame
 * with a stale lock state. The extra UI-facing API ([view] and the actions) is used by the
 * time-limit screens in this package.
 */
internal class DefaultTimeLimitController(
    private val clock: Clock,
    private val store: StateStore,
    /** Runs the tick loop and the initial load; main-thread confined in the app. */
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val hashDispatcher: CoroutineDispatcher,
    private val hasher: PinHasher = PinHasher(),
    /** Second guard against a missed onBackground: no counting while no activity is visible. */
    private val isAppVisible: () -> Boolean = { true },
    private val engine: TimeLimitEngine = TimeLimitEngine(),
    private val tickMillis: Long = 1_000,
    private val saveEveryMillis: Long = 10_000,
) : TimeLimitController {

    private val lock = Any()
    private var current = TimeLimitState()
    private var loaded = false
    private var lastSaved: TimeLimitState? = null
    private var lastSaveElapsed: Long? = null
    private var ticker: Job? = null
    private val saves = Channel<TimeLimitState>(Channel.CONFLATED)

    private val _state = MutableStateFlow<LockState>(LockState.Disabled)
    override val state: StateFlow<LockState> = _state.asStateFlow()

    private val _view = MutableStateFlow(TimeLimitView.Disabled)

    /** Everything the time-limit screens show. */
    val view: StateFlow<TimeLimitView> = _view.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** False until the saved state has been read. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            for (s in saves) runCatching { store.save(s) }
        }
        scope.launch {
            val stored = withContext(ioDispatcher) { runCatching { store.load() }.getOrNull() } ?: TimeLimitState()
            synchronized(lock) {
                loaded = true
                // Keep a foreground session that was opened while loading.
                update(Save.Now) { s, r -> engine.refresh(stored.copy(session = s.session), r) }
            }
            _ready.value = true
        }
    }

    // ---- TimeLimitController ---------------------------------------------------------------

    override fun onForeground(mode: WebMode) {
        update(Save.Periodic) { s, r -> engine.foreground(s, r, mode) }
        startTicker()
    }

    override fun onBackground() {
        stopTicker()
        update(Save.Now) { s, r -> engine.background(s, r) }
    }

    // ---- For the screens -------------------------------------------------------------------

    /** Re-reads the clock (countdowns, midnight). Cheap; the screens call it every second. */
    fun refresh() = update(Save.Periodic) { s, r -> engine.refresh(s, r) }

    suspend fun setup(minutes: Int, pin: String): ActionResult {
        if (!loaded) return NOT_READY
        if (!PinHasher.isValidPin(pin)) return BAD_PIN
        val hash = withContext(hashDispatcher) { hasher.hash(pin) }
        return act { s, r -> engine.setup(s, r, minutes, hash) }
    }

    /** Lowering (or picking today's limit again) needs no PIN; raising needs [pin]. */
    suspend fun setLimit(minutes: Int, pin: String? = null): ActionResult {
        if (!loaded) return NOT_READY
        return if (pin == null) act { s, r -> engine.setLimit(s, r, minutes, pinOk = null) }
        else withPinCheck(pin) { s, r, ok -> engine.setLimit(s, r, minutes, ok) }
    }

    suspend fun turnOff(pin: String): ActionResult = withPinCheck(pin) { s, r, ok -> engine.turnOff(s, r, ok) }

    fun cancelPendingLimit() = update(Save.Now) { s, r -> engine.cancelPendingLimit(s, r) }

    /** Messages & calls off, the next day (PIN). */
    suspend fun scheduleGuardOff(pin: String): ActionResult = withPinCheck(pin) { s, r, ok -> engine.scheduleGuardOff(s, r, ok) }

    fun cancelGuardOff() = update(Save.Now) { s, r -> engine.cancelGuardOff(s, r) }

    fun guardOffHandled() = update(Save.Now) { s, r -> engine.guardOffHandled(s, r) }

    /**
     * Trusted time for the page's own daily counters (Explore minutes, Discover, reel cooldown):
     * how far the trusted wall time is from the phone's clock, and the saved zone's UTC offset then.
     */
    fun pageClock(): PageClockInfo = synchronized(lock) {
        val r = clock.now()
        val wall = engine.trustedWall(current.anchor, r)
        val zone = engine.savedZone(current, r)
        val offsetMin = zone.rules.getOffset(java.time.Instant.ofEpochMilli(wall)).totalSeconds / 60
        val (day, nextAt) = engine.pageDay(current, r)
        PageClockInfo(wall - System.currentTimeMillis(), offsetMin, day.toEpochDay(), nextAt)
    }

    /** See [pageClock]: [offsetMs] trusted minus phone clock; [day] epoch day; [nextDayAt] trusted ms. */
    data class PageClockInfo(val offsetMs: Long, val zoneOffsetMin: Int, val day: Long, val nextDayAt: Long)

    /** Checks [pin] (counts towards the wrong-PIN block) without changing anything. */
    suspend fun verifyPin(pin: String): ActionResult = withPinCheck(pin) { s, r, ok -> engine.verifyPin(s, r, ok) }

    suspend fun changePin(currentPin: String, newPin: String): ActionResult {
        if (!PinHasher.isValidPin(newPin)) return BAD_PIN
        val newHash = withContext(hashDispatcher) { hasher.hash(newPin) }
        return withPinCheck(currentPin) { s, r, ok -> engine.changePin(s, r, ok, newHash) }
    }

    fun cancelPendingPin() = update(Save.Now) { s, r -> engine.cancelPendingPin(s, r) }

    suspend fun extend(pin: String): ActionResult = withPinCheck(pin) { s, r, ok -> engine.extend(s, r, ok) }

    fun acknowledgeExtension() = update(Save.Now) { s, r -> engine.acknowledgeGrant(s, r) }

    fun requestPinReset(): ActionResult = act { s, r -> engine.requestPinReset(s, r) }

    fun cancelPinReset() = update(Save.Now) { s, r -> engine.cancelPinReset(s, r) }

    suspend fun completePinReset(newPin: String): ActionResult {
        if (!loaded) return NOT_READY
        if (!PinHasher.isValidPin(newPin)) return BAD_PIN
        val hash = withContext(hashDispatcher) { hasher.hash(newPin) }
        return act { s, r -> engine.completePinReset(s, r, hash) }
    }

    // ---- Internals -------------------------------------------------------------------------

    private enum class Save { Now, Periodic }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(tickMillis)
                if (!isAppVisible()) {
                    update(Save.Now) { s, r -> engine.background(s, r) }
                    break
                }
                refresh()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** Runs [op] with whether [pin] is right, unless the PIN can't be used right now (blocked, reset running). */
    private suspend fun withPinCheck(pin: String, op: (TimeLimitState, ClockReading, Boolean) -> Outcome): ActionResult {
        if (!loaded) return NOT_READY
        val (gate, hash) = synchronized(lock) { engine.pinGate(current, clock.now()) to current.pin }
        if (gate != null) return gate
        val ok = hash != null && withContext(hashDispatcher) { hasher.verify(pin, hash) }
        return act { s, r -> op(s, r, ok) }
    }

    private fun act(op: (TimeLimitState, ClockReading) -> Outcome): ActionResult {
        if (!loaded) return NOT_READY
        var result: ActionResult = NOT_READY
        update(Save.Now) { s, r -> op(s, r).also { result = it.result }.state }
        return result
    }

    private fun update(save: Save, op: (TimeLimitState, ClockReading) -> TimeLimitState) {
        synchronized(lock) {
            val r = clock.now()
            val next = op(current, r)
            current = next
            val view = engine.view(next, r)
            _view.value = view
            _state.value = view.lockState
            persist(next, r, save)
        }
    }

    /** Saves after real changes at once, and usage-only changes at most every [saveEveryMillis]. */
    private fun persist(s: TimeLimitState, r: ClockReading, save: Save) {
        if (!loaded) return // Never overwrite the stored state with the blank pre-load one.
        val p = s.persistent()
        val previous = lastSaved
        if (p == previous) return
        val usageOnly = previous != null && p.copy(usedMillis = previous.usedMillis, anchor = previous.anchor) == previous
        val last = lastSaveElapsed
        val due = last == null || r.elapsedMillis < last || r.elapsedMillis - last >= saveEveryMillis
        if (save == Save.Now || !usageOnly || due) {
            lastSaved = p
            lastSaveElapsed = r.elapsedMillis
            saves.trySend(p)
        }
    }

    private companion object {
        val NOT_READY = ActionResult.NotAllowed("Still loading")
        val BAD_PIN = ActionResult.NotAllowed("PIN must be 4 digits")
    }
}
