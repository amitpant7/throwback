package app.throwback.timelimit

import app.throwback.core.LockState
import app.throwback.core.WebMode
import app.throwback.timelimit.data.StateCodec
import app.throwback.timelimit.engine.ActionResult
import app.throwback.timelimit.engine.LockPhase
import app.throwback.timelimit.engine.Moment
import app.throwback.timelimit.engine.PendingLimit
import app.throwback.timelimit.engine.PendingPin
import app.throwback.timelimit.engine.Session
import app.throwback.timelimit.engine.TimeLimitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StateCodecTest {

    private val full = TimeLimitState(
        limitMinutes = 45,
        pin = PIN_A,
        day = LocalDate.of(2026, 11, 20),
        usedMillis = 1_234_567,
        extensionsUsed = 1,
        lockedAt = Moment(41, 10_000, 1_800_000_000_000),
        pendingLimit = PendingLimit(60, LocalDate.of(2026, 11, 21)),
        pendingPin = PendingPin(PIN_B, LocalDate.of(2026, 11, 21)),
        pinResetRequestedAt = Moment(41, 9_000, 1_799_000_000_000),
        wrongPinAttempts = 3,
        pinBlockedAt = Moment(40, 1, 2),
        anchor = Moment(41, 12_000, 1_800_000_002_000),
        session = Session(WebMode.FULL, 12_000, 41),
        grantShowing = true,
    )

    @Test
    fun `every persisted field survives a round trip, transient ones don't`() {
        val back = StateCodec.decode(StateCodec.encode(full))
        assertEquals(full.persistent(), back)
        assertNull(back!!.session)
    }

    @Test
    fun `a pending turn-off survives`() {
        val s = full.copy(pendingLimit = PendingLimit(null, LocalDate.of(2026, 11, 21)))
        assertEquals(s.persistent(), StateCodec.decode(StateCodec.encode(s)))
    }

    @Test
    fun `the default (no limit) state survives`() {
        assertEquals(TimeLimitState(), StateCodec.decode(StateCodec.encode(TimeLimitState())))
    }

    @Test
    fun `the stored text holds the hash, never the PIN`() {
        val text = StateCodec.encode(full)
        assertTrue(PIN_A.hash in text)
        assertTrue(PIN_A.salt in text)
    }

    @Test
    fun `unreadable or unknown data decodes to null`() {
        assertNull(StateCodec.decode(""))
        assertNull(StateCodec.decode("{not json"))
        assertNull(StateCodec.decode("""{"version":99,"limitMinutes":30}"""))
        assertNull(StateCodec.decode("""{"version":1,"day":"not a date"}"""))
    }

    @Test
    fun `unknown keys from a newer build are ignored`() {
        val s = StateCodec.decode("""{"version":1,"limitMinutes":30,"somethingNew":true}""")
        assertEquals(30, s!!.limitMinutes)
    }

    @Test
    fun `reinstall restore on the same boot keeps the lock and the wait`() {
        val h = EngineHarness()
        h.setup(15)
        h.useUntilLocked()
        h.tick(2 * MIN)
        h.act { s, r -> h.engine.requestPinReset(s, r) }

        // Uninstall, reinstall, Auto Backup restores the DataStore file.
        h.s = StateCodec.decode(StateCodec.encode(h.s))!!
        h.tick(MIN)
        h.refresh()
        assertEquals(LockState.Locked, h.view().lockState)
        assertEquals(LockPhase.Waiting(2 * MIN, 5 * MIN), h.view().lock)
        assertEquals(15, h.s.limitMinutes)
        assertEquals(PIN_A, h.s.pin)
        assertTrue(h.view().pinReset != null)
        assertEquals(ActionResult.PinResetPending, h.engine.pinGate(h.s, h.reading()))
    }

    @Test
    fun `restore after a reboot (or on another phone) carries on from the saved time`() {
        val h = EngineHarness()
        h.setup(15)
        h.useUntilLocked()
        h.tick(5 * MIN)
        h.extend()
        h.acknowledge()
        h.useUntilLocked()
        val text = StateCodec.encode(h.s)

        h.reboot(downtime = MIN)
        h.s = StateCodec.decode(text)!!
        h.refresh()
        assertEquals(LockState.Locked, h.view().lockState)
        assertEquals(1, h.view().extensionsLeft)
        val wait = h.view().lock as LockPhase.Waiting
        assertTrue(wait.remainingMillis < 5 * MIN)

        h.networkUp = true // Android's time sync, or Instagram's page.
        h.advanceToMidnight()
        h.refresh()
        assertEquals(LockState.Running(15 * MIN), h.view().lockState)
    }
}
