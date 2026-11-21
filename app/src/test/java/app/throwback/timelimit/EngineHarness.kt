package app.throwback.timelimit

import app.throwback.core.WebMode
import app.throwback.timelimit.engine.ActionResult
import app.throwback.timelimit.engine.ClockReading
import app.throwback.timelimit.engine.Outcome
import app.throwback.timelimit.engine.PinHash
import app.throwback.timelimit.engine.TimeLimitEngine
import app.throwback.timelimit.engine.TimeLimitState
import app.throwback.timelimit.engine.TimeLimitView
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.min

const val SEC = 1_000L
const val MIN = 60 * SEC
const val HOUR = 60 * MIN
const val DAY = 24 * HOUR

val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/** The engine only sees "was the PIN right"; real hashing is tested in PinHasherTest. */
val PIN_A = PinHash(salt = "c2FsdEE=", hash = "aGFzaEE=", iterations = 1)
val PIN_B = PinHash(salt = "c2FsdEI=", hash = "aGFzaEI=", iterations = 1)

/**
 * Drives [TimeLimitEngine] the way the controller does, with a fake phone clock:
 * [advance] is real time passing, [moveClock] is the user changing the clock, [reboot] restarts
 * the monotonic clock. While [networkUp] the reading carries network time (the real time), as once
 * the phone has synced or Instagram's page has loaded; a reboot loses it until the test sets
 * [networkUp] again. Off by default: the engine's own clock rules are what most tests check.
 */
class EngineHarness(
    start: LocalDateTime = LocalDateTime.of(2026, 11, 20, 9, 0),
    var zone: ZoneId = BERLIN,
) {
    val engine = TimeLimitEngine()
    var elapsed = 3 * HOUR // Phone booted three hours ago.
    var wall = start.atZone(zone).toInstant().toEpochMilli()

    /** The true time; [moveClock] only changes the phone's clock ([wall]). */
    var real = wall
    var networkUp = false
    var boot = 41
    var s = TimeLimitState()

    fun reading() = ClockReading(elapsed, wall, zone, boot, networkMillis = real.takeIf { networkUp })

    fun advance(ms: Long) {
        elapsed += ms
        wall += ms
        real += ms
    }

    fun moveClock(ms: Long) {
        wall += ms
    }

    fun reboot(downtime: Long = 2 * MIN, clockChange: Long = 0) {
        boot++
        elapsed = 30 * SEC
        wall += downtime + 30 * SEC + clockChange
        real += downtime + 30 * SEC
        networkUp = false
    }

    fun refresh() {
        s = engine.refresh(s, reading())
    }

    fun foreground(mode: WebMode = WebMode.FULL) {
        s = engine.foreground(s, reading(), mode)
    }

    fun background() {
        s = engine.background(s, reading())
    }

    /** Real time passes while the controller ticks every second (foreground or not). */
    fun tick(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = min(SEC, left)
            advance(step)
            refresh()
            left -= step
        }
    }

    /** Uses Throwback in the foreground in [mode] for [ms], then leaves. */
    fun use(ms: Long, mode: WebMode = WebMode.FULL) {
        foreground(mode)
        tick(ms)
        background()
    }

    fun view(): TimeLimitView = engine.view(s, reading())

    fun act(op: (TimeLimitState, ClockReading) -> Outcome): ActionResult {
        val o = op(s, reading())
        s = o.state
        return o.result
    }

    fun setup(minutes: Int = 30, pin: PinHash = PIN_A): ActionResult = act { st, r -> engine.setup(st, r, minutes, pin) }

    fun extend(pinOk: Boolean): ActionResult = act { st, r -> engine.extend(st, r, pinOk) }

    fun acknowledge() {
        s = engine.acknowledgeGrant(s, reading())
    }

    /** Lock by using up the whole allowance in one go. */
    fun useUntilLocked() {
        use(s.budgetMillis - s.usedMillis)
    }

    /** Advances real time to the next local midnight of the phone's clock (plus [extra]). */
    fun advanceToMidnight(extra: Long = SEC) {
        val now = java.time.Instant.ofEpochMilli(wall).atZone(zone)
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        advance(midnight - wall + extra)
    }

    /** Advances real time to the next real local midnight (plus [extra]), whatever the phone's clock says. */
    fun advanceToRealMidnight(extra: Long = SEC) {
        val now = java.time.Instant.ofEpochMilli(real).atZone(zone)
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        advance(midnight - real + extra)
    }

    val today: LocalDate get() = s.day!!
}
