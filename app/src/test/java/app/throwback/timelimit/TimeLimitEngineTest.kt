package app.throwback.timelimit

import app.throwback.core.LockState
import app.throwback.core.WebMode
import app.throwback.timelimit.engine.ActionResult
import app.throwback.timelimit.engine.LockPhase
import app.throwback.timelimit.engine.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimeLimitEngineTest {

    private val h = EngineHarness()

    private fun remaining() = (h.view().lockState as LockState.Running).remainingMillis

    // ---- Off by default, setup ----------------------------------------------------------

    @Test
    fun `off by default and nothing is counted`() {
        h.use(2 * HOUR)
        assertEquals(LockState.Disabled, h.view().lockState)
        assertEquals(0L, h.s.usedMillis)
    }

    @Test
    fun `setup turns the limit on with the full allowance`() {
        assertEquals(ActionResult.Done, h.setup(30))
        assertEquals(LockState.Running(30 * MIN), h.view().lockState)
        assertEquals(PIN_A, h.s.pin)
    }

    @Test
    fun `setup only accepts the offered limits and only when no limit exists`() {
        assertTrue(h.setup(20) is ActionResult.NotAllowed)
        assertEquals(ActionResult.Done, h.setup(15))
        assertTrue(h.setup(60) is ActionResult.NotAllowed)
        assertEquals(15, h.s.limitMinutes)
    }

    // ---- What counts ------------------------------------------------------------------------

    @Test
    fun `only foreground time in FULL mode counts`() {
        h.setup(30)
        h.use(10 * MIN, WebMode.FULL)
        assertEquals(20 * MIN, remaining())

        h.use(10 * MIN, WebMode.MESSAGES_ONLY)
        assertEquals(20 * MIN, remaining())

        h.tick(3 * HOUR) // In the background (or on a call in another app).
        assertEquals(20 * MIN, remaining())
    }

    @Test
    fun `switching modes inside one visit counts only the FULL parts`() {
        h.setup(30)
        h.foreground(WebMode.FULL)
        h.tick(5 * MIN)
        h.foreground(WebMode.MESSAGES_ONLY)
        h.tick(7 * MIN)
        h.foreground(WebMode.FULL)
        h.tick(4 * MIN)
        h.background()
        assertEquals(21 * MIN, remaining())
    }

    @Test
    fun `repeated onForeground calls don't double count`() {
        h.setup(30)
        h.foreground()
        h.tick(MIN)
        h.foreground() // e.g. ON_RESUME and LaunchedEffect(mode) both firing
        h.foreground()
        h.tick(MIN)
        h.background()
        assertEquals(28 * MIN, remaining())
    }

    @Test
    fun `a missed onBackground can't bill hours`() {
        h.setup(30)
        h.foreground()
        h.advance(3 * HOUR) // No ticks and no onBackground: process frozen, phone asleep...
        h.refresh()
        assertEquals(30 * MIN - Rules.MAX_COUNTED_INTERVAL_MILLIS, remaining())
    }

    @Test
    fun `a session from before a reboot is not billed`() {
        h.setup(30)
        h.foreground()
        h.tick(MIN)
        h.reboot(downtime = HOUR)
        h.refresh()
        assertEquals(29 * MIN, remaining())
    }

    @Test
    fun `Instagram app time while the guard was off counts, capped at the allowance`() {
        h.setup(30)
        h.use(10 * MIN)
        h.s = h.engine.addOutsideUsage(h.s, h.reading(), 12 * MIN)
        assertEquals(8 * MIN, remaining())
        h.s = h.engine.addOutsideUsage(h.s, h.reading(), 2 * HOUR)
        assertEquals(LockState.Locked, h.view().lockState) // it can lock, and the 5-minute wait starts
        assertEquals(30 * MIN, h.s.usedMillis)
        assertEquals(LockPhase.Waiting(Rules.WAIT_MILLIS, Rules.WAIT_MILLIS), h.view().lock)
        h.tick(5 * MIN)
        assertEquals(ActionResult.Done, h.extend(pinOk = true)) // and an extension still gives 5 minutes
        h.acknowledge()
        assertEquals(LockState.Running(5 * MIN), h.view().lockState)
    }

    @Test
    fun `Instagram app time outside counts for nothing without a limit`() {
        h.s = h.engine.addOutsideUsage(h.s, h.reading(), HOUR)
        assertEquals(0L, h.s.usedMillis)
        assertEquals(LockState.Disabled, h.view().lockState)
    }

    // ---- Locking and the 5-minute wait -----------------------------------------------------

    @Test
    fun `reaching the limit locks and starts the 5-minute wait`() {
        h.setup(15)
        h.use(14 * MIN)
        assertEquals(LockState.Running(MIN), h.view().lockState)
        h.foreground()
        h.tick(MIN)
        assertEquals(LockState.Locked, h.view().lockState)
        assertEquals(LockPhase.Waiting(Rules.WAIT_MILLIS, Rules.WAIT_MILLIS), h.view().lock)
        assertEquals(2, h.view().extensionsLeft)
    }

    @Test
    fun `time while locked is not counted`() {
        h.setup(15)
        h.useUntilLocked()
        h.use(20 * MIN)
        assertEquals(15 * MIN, h.s.usedMillis)
    }

    @Test
    fun `the PIN is refused during the 5-minute wait`() {
        h.setup(15)
        h.useUntilLocked()
        h.tick(4 * MIN + 59 * SEC)
        assertEquals(LockPhase.Waiting(SEC, Rules.WAIT_MILLIS), h.view().lock)
        assertTrue(h.extend(pinOk = true) is ActionResult.NotAllowed)
        assertEquals(0, h.s.extensionsUsed)
        assertEquals(0, h.s.wrongPinAttempts)

        h.tick(SEC)
        assertEquals(LockPhase.CanExtend(1), h.view().lock)
    }

    @Test
    fun `the wait runs in real time, also with Throwback closed`() {
        h.setup(15)
        h.useUntilLocked()
        h.advance(6 * MIN) // App closed, no ticks.
        h.refresh()
        assertEquals(LockPhase.CanExtend(1), h.view().lock)
    }

    // ---- PIN and extensions -----------------------------------------------------------------

    @Test
    fun `wrong PIN keeps the lock, right PIN gives exactly 5 more minutes`() {
        h.setup(15)
        h.useUntilLocked()
        h.tick(5 * MIN)

        assertEquals(ActionResult.WrongPin(Rules.WRONG_PINS_BEFORE_BLOCK - 1), h.extend(pinOk = false))
        assertEquals(LockState.Locked, h.view().lockState)
        assertEquals(0, h.s.extensionsUsed)

        assertEquals(ActionResult.Done, h.extend(pinOk = true))
        assertEquals(1, h.s.extensionsUsed)
        // The "+5 minutes" confirmation keeps the lock up until it's dismissed...
        assertEquals(LockState.Locked, h.view().lockState)
        assertEquals(LockPhase.Granted, h.view().lock)
        assertEquals(1, h.view().extensionsLeft)
        // ...and time spent reading it isn't billed.
        h.foreground()
        h.tick(MIN)
        h.acknowledge()
        assertEquals(LockState.Running(5 * MIN), h.view().lockState)
        assertEquals(0, h.s.wrongPinAttempts)
    }

    @Test
    fun `two extensions, each after its own wait, then locked until midnight`() {
        h.setup(15) // 09:00
        h.useUntilLocked() // 09:15
        h.tick(5 * MIN)
        assertEquals(ActionResult.Done, h.extend(pinOk = true))
        h.acknowledge()
        h.useUntilLocked() // 09:25

        // The second extension needs its own fresh 5-minute wait.
        assertEquals(LockPhase.Waiting(Rules.WAIT_MILLIS, Rules.WAIT_MILLIS), h.view().lock)
        assertTrue(h.extend(pinOk = true) is ActionResult.NotAllowed)
        h.tick(5 * MIN)
        assertEquals(LockPhase.CanExtend(2), h.view().lock)
        assertEquals(ActionResult.Done, h.extend(pinOk = true))
        assertEquals(0, h.view().extensionsLeft)
        h.acknowledge()
        h.useUntilLocked() // 09:35

        val phase = h.view().lock as LockPhase.DoneForToday
        assertEquals(14 * HOUR + 25 * MIN, phase.untilMidnightMillis)
        // No third extension, no matter how long they wait.
        h.tick(2 * HOUR)
        assertTrue(h.view().lock is LockPhase.DoneForToday)
        assertTrue(h.extend(pinOk = true) is ActionResult.NotAllowed)
        assertEquals(2, h.s.extensionsUsed)
    }

    @Test
    fun `too many wrong PINs block entry for a while, even the right one`() {
        h.setup(15)
        h.useUntilLocked()
        h.tick(5 * MIN)
        repeat(Rules.WRONG_PINS_BEFORE_BLOCK - 1) { h.extend(pinOk = false) }
        assertEquals(ActionResult.PinBlocked(Rules.PIN_BLOCK_MILLIS), h.extend(pinOk = false))
        assertTrue(h.extend(pinOk = true) is ActionResult.PinBlocked)
        assertTrue(h.engine.pinGate(h.s, h.reading()) is ActionResult.PinBlocked)
        assertEquals(Rules.PIN_BLOCK_MILLIS, h.view().pinBlockedMillis)

        h.tick(Rules.PIN_BLOCK_MILLIS)
        assertNull(h.engine.pinGate(h.s, h.reading()))
        assertEquals(ActionResult.Done, h.extend(pinOk = true))
    }

    // ---- New day ----------------------------------------------------------------------------

    @Test
    fun `local midnight resets usage and extensions`() {
        h.setup(15)
        h.useUntilLocked()
        h.tick(5 * MIN)
        h.extend(pinOk = true)
        h.acknowledge()
        h.useUntilLocked()

        h.advanceToMidnight()
        h.refresh()
        assertEquals(LockState.Running(15 * MIN), h.view().lockState)
        assertEquals(0, h.s.extensionsUsed)
        assertNull(h.s.lockedAt)
        assertEquals(LocalDateTime.of(2026, 11, 21, 0, 0).toLocalDate(), h.today)
    }

    @Test
    fun `midnight unlocks while the lock screen is open`() {
        h.setup(15)
        h.foreground()
        h.tick(15 * MIN)
        assertEquals(LockState.Locked, h.view().lockState)
        val untilMidnight = h.s.day!!.plusDays(1).atStartOfDay(BERLIN).toInstant().toEpochMilli() - h.wall
        h.elapsed += untilMidnight - SEC
        h.wall += untilMidnight - SEC
        h.tick(2 * SEC)
        assertEquals(LockState.Running(15 * MIN - SEC), h.view().lockState)
    }

    // ---- Clock changes ----------------------------------------------------------------------

    @Test
    fun `moving the clock back a day doesn't reset usage`() {
        h.setup(30)
        h.use(20 * MIN)
        h.moveClock(-DAY)
        h.refresh()
        assertEquals(10 * MIN, remaining())
        // Not even after a reboot, when the stopwatch starts again.
        h.reboot()
        h.refresh()
        assertEquals(10 * MIN, remaining())
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0).toLocalDate(), h.today)
    }

    @Test
    fun `after moving the clock back, the day still ends at the real midnight`() {
        h.setup(30)
        h.use(20 * MIN)
        val toRealMidnight = h.today.plusDays(1).atStartOfDay(BERLIN).toInstant().toEpochMilli() - h.wall
        h.moveClock(-3 * HOUR)
        h.refresh()
        h.advance(toRealMidnight - SEC) // The phone's clock shows 20:59:59.
        h.refresh()
        assertEquals(10 * MIN, remaining())
        h.advance(2 * SEC)
        h.refresh()
        assertEquals(30 * MIN, remaining())
    }

    @Test
    fun `moving the clock back across the date line doesn't reopen yesterday`() {
        h.setup(30)
        h.use(30 * MIN)
        h.zone = ZoneId.of("Pacific/Honolulu") // Fly west: the local date goes back.
        h.refresh()
        assertEquals(LockState.Locked, h.view().lockState)
    }

    @Test
    fun `moving the clock forward doesn't skip the 5-minute wait`() {
        h.setup(15)
        h.useUntilLocked()
        h.moveClock(10 * MIN)
        h.refresh()
        assertTrue(h.view().lock is LockPhase.Waiting)
        assertTrue(h.extend(pinOk = true) is ActionResult.NotAllowed)
    }

    @Test
    fun `moving the clock forward to tomorrow doesn't start a new day within a boot`() {
        h.setup(15)
        h.useUntilLocked()
        assertEquals(ActionResult.Scheduled(h.today.plusDays(1)), h.act { s, r -> h.engine.turnOff(s, r, pinOk = true) })

        h.moveClock(DAY)
        h.refresh()
        assertEquals(LockState.Locked, h.view().lockState)
        assertEquals(15, h.s.limitMinutes) // The pending turn-off didn't jump ahead either.
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0).toLocalDate(), h.today)
    }

    @Test
    fun `switching to a time zone where it's already tomorrow doesn't start a new day`() {
        h.setup(30)
        h.use(30 * MIN)
        assertEquals(LockState.Locked, h.view().lockState)
        val day = h.today
        h.zone = java.time.ZoneId.of("Pacific/Kiritimati") // UTC+14: already tomorrow there
        h.refresh()
        assertEquals(day, h.s.day)
        assertEquals(LockState.Locked, h.view().lockState)
        // At the next real midnight (of the saved zone) the day changes and the new zone is taken over.
        val saved = h.s.zone!!
        h.zone = java.time.ZoneId.of(saved)
        h.advanceToMidnight()
        h.zone = java.time.ZoneId.of("Pacific/Kiritimati")
        h.refresh()
        assertEquals(day.plusDays(1), h.s.day)
        assertEquals("Pacific/Kiritimati", h.s.zone)
        // It's already the day after there, but not 23 h since this day began: no second new day.
        h.moveClock(MIN)
        h.refresh()
        assertEquals(day.plusDays(1), h.s.day)
    }

    @Test
    fun `small clock corrections are followed`() {
        h.setup(15)
        h.moveClock(40 * SEC) // Network time correction.
        h.refresh()
        assertEquals(h.wall, h.s.anchor!!.wallMillis)
    }

    // ---- Clock forward + reboot (network time) -----------------------------------------------

    /** The review's steps: Forgot PIN, date +3 days, reboot, set a new PIN. */
    private fun pinResetAfterClockForwardReboot(networkAfterBoot: Boolean): EngineHarness {
        val e = EngineHarness().apply { networkUp = true }
        e.setup(15)
        e.act { s, r -> e.engine.requestPinReset(s, r) }
        e.moveClock(3 * DAY)
        e.refresh()
        e.reboot()
        e.networkUp = networkAfterBoot
        e.refresh()
        assertFalse(e.view().pinReset!!.ready)
        assertTrue(e.act { s, r -> e.engine.completePinReset(s, r, PIN_B) } is ActionResult.NotAllowed)
        assertEquals(PIN_A, e.s.pin)
        return e
    }

    @Test
    fun `clock forward plus a reboot doesn't shorten the forgot-PIN wait, with network time`() {
        val e = pinResetAfterClockForwardReboot(networkAfterBoot = true)
        e.advance(3 * DAY) // The real three days still work.
        e.refresh()
        assertTrue(e.view().pinReset!!.ready)
    }

    @Test
    fun `clock forward plus a reboot doesn't shorten the forgot-PIN wait, before network time is back`() {
        val e = pinResetAfterClockForwardReboot(networkAfterBoot = false)
        e.advance(3 * DAY - MIN) // Over 3 real days since the request, but the reboot's downtime isn't counted yet...
        e.refresh()
        assertFalse(e.view().pinReset!!.ready)
        e.networkUp = true // ...until network time is back.
        e.refresh()
        assertTrue(e.view().pinReset!!.ready)
    }

    @Test
    fun `clock forward plus a reboot doesn't bring a turn-off forward`() {
        for (networkAfterBoot in listOf(true, false)) {
            val e = EngineHarness().apply { networkUp = true }
            e.setup(30)
            e.use(30 * MIN)
            e.act { s, r -> e.engine.turnOff(s, r, pinOk = true) } // "tomorrow"
            e.moveClock(DAY + HOUR)
            e.refresh()
            e.reboot()
            e.networkUp = networkAfterBoot
            e.refresh()
            assertEquals("network after boot: $networkAfterBoot", LockState.Locked, e.view().lockState)
            assertEquals(30, e.s.limitMinutes)
            assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0).toLocalDate(), e.today)
        }
    }

    @Test
    fun `a scheduled turn-off happens at the real midnight`() {
        val e = EngineHarness().apply { networkUp = true }
        e.setup(30)
        e.act { s, r -> e.engine.turnOff(s, r, pinOk = true) }
        e.moveClock(-2 * HOUR) // the phone's clock is wrong; network time isn't
        e.advanceToRealMidnight()
        e.refresh()
        assertEquals(LockState.Disabled, e.view().lockState)
    }

    @Test
    fun `a phone switched off overnight gets its new day once network time is back`() {
        val e = EngineHarness(start = LocalDateTime.of(2026, 11, 20, 22, 0)).apply { networkUp = true }
        e.setup(15)
        e.useUntilLocked() // 22:15
        e.reboot(downtime = 9 * HOUR) // switched on at 07:15; no network time yet
        e.refresh()
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0).toLocalDate(), e.today)
        e.networkUp = true
        e.refresh()
        assertEquals(LocalDateTime.of(2026, 11, 21, 0, 0).toLocalDate(), e.today)
        assertEquals(LockState.Running(15 * MIN), e.view().lockState)
    }

    @Test
    fun `a wrong clock at boot, set right later, doesn't hold the day up`() {
        // Drained battery: the phone starts in 2016, then the network sets its clock right.
        val e = EngineHarness(start = LocalDateTime.of(2026, 11, 20, 20, 0)).apply { networkUp = true }
        e.setup(15)
        e.useUntilLocked() // 20:15
        val tenYears = 3_652 * DAY
        e.reboot(downtime = 3 * HOUR, clockChange = -tenYears)
        e.refresh() // the first reading of this boot (BOOT_COMPLETED)
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0).toLocalDate(), e.today)
        assertEquals(LockState.Locked, e.view().lockState)
        e.moveClock(tenYears) // the phone's clock is right again; too big a jump to follow within a boot
        e.refresh()
        e.networkUp = true // network time is
        e.advanceToRealMidnight()
        e.refresh()
        assertEquals(LocalDateTime.of(2026, 11, 21, 0, 0).toLocalDate(), e.today)
        assertEquals(LockState.Running(15 * MIN), e.view().lockState)
    }

    @Test
    fun `network time wins over a phone clock moved within a boot`() {
        val e = EngineHarness().apply { networkUp = true }
        e.setup(15)
        e.useUntilLocked()
        e.moveClock(DAY)
        e.refresh()
        assertEquals(LockState.Locked, e.view().lockState)
        assertEquals(e.real, e.engine.trustedWall(e.s.anchor, e.reading()))
    }

    @Test
    fun `clock forward plus a reboot gives no new day, before network time is back too`() {
        h.setup(30)
        h.use(30 * MIN) // 09:30, locked.
        h.reboot(clockChange = DAY) // Clock set to tomorrow, then rebooted, with no network time yet.
        h.refresh()
        assertEquals(LockState.Locked, h.view().lockState)
        h.networkUp = true
        h.refresh()
        assertEquals(LockState.Locked, h.view().lockState)
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0).toLocalDate(), h.today)
    }

    // ---- Settings changes -------------------------------------------------------------------

    @Test
    fun `raising the limit needs the PIN and takes effect the next day`() {
        h.setup(30)
        assertEquals(ActionResult.PinRequired, h.act { s, r -> h.engine.setLimit(s, r, 60) })
        assertEquals(0, h.s.wrongPinAttempts)
        assertEquals(ActionResult.WrongPin(4), h.act { s, r -> h.engine.setLimit(s, r, 60, pinOk = false) })

        val tomorrow = h.today.plusDays(1)
        assertEquals(ActionResult.Scheduled(tomorrow), h.act { s, r -> h.engine.setLimit(s, r, 60, pinOk = true) })
        assertEquals(30, h.s.limitMinutes)
        assertEquals(LockState.Running(30 * MIN), h.view().lockState)
        assertEquals(60, h.view().pendingLimit!!.minutes)

        h.advanceToMidnight()
        h.refresh()
        assertEquals(60, h.s.limitMinutes)
        assertNull(h.s.pendingLimit)
        assertEquals(LockState.Running(60 * MIN), h.view().lockState)
    }

    @Test
    fun `turning the limit off needs the PIN and takes effect the next day`() {
        h.setup(30)
        assertEquals(ActionResult.WrongPin(4), h.act { s, r -> h.engine.turnOff(s, r, pinOk = false) })
        assertEquals(ActionResult.Scheduled(h.today.plusDays(1)), h.act { s, r -> h.engine.turnOff(s, r, pinOk = true) })
        h.use(30 * MIN)
        assertEquals(LockState.Locked, h.view().lockState)

        h.advanceToMidnight()
        h.refresh()
        assertEquals(LockState.Disabled, h.view().lockState)
        assertNull(h.s.pin)
        assertNull(h.s.pendingLimit)
        // And it can be set up again afterwards.
        assertEquals(ActionResult.Done, h.setup(15, PIN_B))
    }

    @Test
    fun `turning Messages and calls off needs the PIN and takes effect the next day`() {
        h.setup(30)
        assertEquals(ActionResult.WrongPin(4), h.act { s, r -> h.engine.scheduleGuardOff(s, r, pinOk = false) })
        assertEquals(ActionResult.Scheduled(h.today.plusDays(1)), h.act { s, r -> h.engine.scheduleGuardOff(s, r, pinOk = true) })
        assertEquals(h.today.plusDays(1), h.view().pendingGuardOff)
        assertFalse(h.view().guardOffDue)

        h.advanceToMidnight()
        h.refresh()
        assertTrue(h.view().guardOffDue)
        assertNull(h.view().pendingGuardOff)
        h.s = h.engine.guardOffHandled(h.s, h.reading())
        assertFalse(h.view().guardOffDue)
    }

    @Test
    fun `a scheduled guard turn-off can be cancelled, and is due at once when the limit goes`() {
        h.setup(30)
        h.act { s, r -> h.engine.scheduleGuardOff(s, r, pinOk = true) }
        h.s = h.engine.cancelGuardOff(h.s, h.reading())
        assertNull(h.view().pendingGuardOff)

        h.act { s, r -> h.engine.scheduleGuardOff(s, r, pinOk = true) }
        h.act { s, r -> h.engine.turnOff(s, r, pinOk = true) } // both tomorrow; the limit goes first
        h.advanceToMidnight()
        h.refresh()
        assertEquals(LockState.Disabled, h.view().lockState)
        assertTrue(h.view().guardOffDue)
    }

    @Test
    fun `changing the PIN needs the current one and takes effect the next day`() {
        h.setup(30)
        assertEquals(ActionResult.WrongPin(4), h.act { s, r -> h.engine.changePin(s, r, pinOk = false, newPin = PIN_B) })
        assertEquals(ActionResult.Scheduled(h.today.plusDays(1)), h.act { s, r -> h.engine.changePin(s, r, pinOk = true, newPin = PIN_B) })
        assertEquals(PIN_A, h.s.pin)
        assertEquals(h.today.plusDays(1), h.view().pendingPinEffective)

        h.advanceToMidnight()
        h.refresh()
        assertEquals(PIN_B, h.s.pin)
        assertNull(h.s.pendingPin)
    }

    @Test
    fun `pending changes can be cancelled without the PIN`() {
        h.setup(30)
        h.act { s, r -> h.engine.setLimit(s, r, 45, pinOk = true) }
        h.act { s, r -> h.engine.changePin(s, r, pinOk = true, newPin = PIN_B) }
        h.s = h.engine.cancelPendingLimit(h.s, h.reading())
        h.s = h.engine.cancelPendingPin(h.s, h.reading())
        h.advanceToMidnight()
        h.refresh()
        assertEquals(30, h.s.limitMinutes)
        assertEquals(PIN_A, h.s.pin)
    }

    @Test
    fun `picking today's limit again cancels a pending turn-off without the PIN`() {
        h.setup(30)
        h.act { s, r -> h.engine.turnOff(s, r, pinOk = true) }
        assertEquals(ActionResult.Done, h.act { s, r -> h.engine.setLimit(s, r, 30) })
        assertNull(h.s.pendingLimit)
    }

    @Test
    fun `lowering the limit is immediate and needs no PIN`() {
        h.setup(60)
        h.act { s, r -> h.engine.setLimit(s, r, 60, pinOk = true) } // no-op
        h.use(10 * MIN)
        assertEquals(ActionResult.Done, h.act { s, r -> h.engine.setLimit(s, r, 30) })
        assertEquals(LockState.Running(20 * MIN), h.view().lockState)
    }

    @Test
    fun `lowering below today's usage locks at once, and an extension still gives 5 minutes`() {
        h.setup(60)
        h.use(40 * MIN)
        h.act { s, r -> h.engine.setLimit(s, r, 15) }
        assertEquals(LockState.Locked, h.view().lockState)
        assertEquals(LockPhase.Waiting(Rules.WAIT_MILLIS, Rules.WAIT_MILLIS), h.view().lock)

        h.tick(5 * MIN)
        assertEquals(ActionResult.Done, h.extend(pinOk = true))
        h.acknowledge()
        assertEquals(LockState.Running(5 * MIN), h.view().lockState)
    }

    @Test
    fun `lowering replaces a pending raise`() {
        h.setup(30)
        h.act { s, r -> h.engine.setLimit(s, r, 60, pinOk = true) }
        h.act { s, r -> h.engine.setLimit(s, r, 15) }
        assertNull(h.s.pendingLimit)
        h.advanceToMidnight()
        h.refresh()
        assertEquals(15, h.s.limitMinutes)
    }

    // ---- Forgot PIN -------------------------------------------------------------------------

    @Test
    fun `forgot PIN takes 3 days, keeps the limit on and blocks extensions meanwhile`() {
        h.setup(15)
        h.useUntilLocked()
        h.tick(5 * MIN)
        assertEquals(ActionResult.Done, h.act { s, r -> h.engine.requestPinReset(s, r) })
        assertEquals(Rules.PIN_RESET_MILLIS, h.view().pinReset!!.remainingMillis)

        assertEquals(ActionResult.PinResetPending, h.extend(pinOk = true))
        assertEquals(ActionResult.PinResetPending, h.act { s, r -> h.engine.turnOff(s, r, pinOk = true) })
        assertEquals(ActionResult.PinResetPending, h.engine.pinGate(h.s, h.reading()))
        assertEquals(0, h.s.extensionsUsed)
        assertEquals(LockState.Locked, h.view().lockState)

        assertTrue(h.act { s, r -> h.engine.completePinReset(s, r, PIN_B) } is ActionResult.NotAllowed)
        h.advance(3 * DAY - MIN)
        h.refresh()
        assertFalse(h.view().pinReset!!.ready)
        assertTrue(h.act { s, r -> h.engine.completePinReset(s, r, PIN_B) } is ActionResult.NotAllowed)
        h.advance(MIN)
        h.refresh()
        assertTrue(h.view().pinReset!!.ready)

        assertEquals(ActionResult.Done, h.act { s, r -> h.engine.completePinReset(s, r, PIN_B) })
        assertEquals(PIN_B, h.s.pin)
        assertNull(h.s.pinResetRequestedAt)
        assertEquals(15, h.s.limitMinutes) // Still on.
    }

    @Test
    fun `cancelling the reset makes the old PIN work again`() {
        h.setup(15)
        h.act { s, r -> h.engine.requestPinReset(s, r) }
        h.useUntilLocked()
        h.tick(5 * MIN)
        h.s = h.engine.cancelPinReset(h.s, h.reading())
        assertNull(h.view().pinReset)
        assertEquals(ActionResult.Done, h.extend(pinOk = true))
    }

    @Test
    fun `the reset countdown can't be skipped by moving the clock forward`() {
        h.setup(15)
        h.act { s, r -> h.engine.requestPinReset(s, r) }
        h.moveClock(4 * DAY)
        h.refresh()
        assertFalse(h.view().pinReset!!.ready)
        assertTrue(h.act { s, r -> h.engine.completePinReset(s, r, PIN_B) } is ActionResult.NotAllowed)
    }

    @Test
    fun `the reset countdown carries on across reboots`() {
        h.setup(15)
        h.act { s, r -> h.engine.requestPinReset(s, r) }
        h.advance(DAY)
        h.reboot(downtime = 2 * DAY)
        h.networkUp = true // The downtime counts once network time is back.
        h.refresh()
        assertTrue(h.view().pinReset!!.ready)
    }

    @Test
    fun `a pending PIN change is dropped when a reset sets a new PIN`() {
        h.setup(15)
        h.act { s, r -> h.engine.changePin(s, r, pinOk = true, newPin = PIN_B) }
        h.act { s, r -> h.engine.requestPinReset(s, r) }
        h.advance(3 * DAY)
        h.refresh()
        val pinC = PIN_A.copy(salt = "c2FsdEM=")
        h.act { s, r -> h.engine.completePinReset(s, r, pinC) }
        assertEquals(pinC, h.s.pin)
        assertNull(h.s.pendingPin)
    }

    // ---- Daylight saving --------------------------------------------------------------------

    @Test
    fun `the lock ends at local midnight on a daylight-saving day`() {
        // 2027-03-28: clocks in Berlin jump from 02:00 to 03:00, so that day is 23 hours long.
        val dst = EngineHarness(start = LocalDateTime.of(2027, 3, 28, 0, 30))
        dst.setup(15)
        dst.useUntilLocked()
        dst.tick(5 * MIN)
        dst.extend(pinOk = true)
        dst.acknowledge()
        dst.useUntilLocked()
        dst.tick(5 * MIN)
        dst.extend(pinOk = true)
        dst.acknowledge()
        dst.useUntilLocked() // 01:05, before the jump
        val phase = dst.view().lock as LockPhase.DoneForToday
        assertEquals(21 * HOUR + 55 * MIN, phase.untilMidnightMillis)
    }
}
