package app.throwback.timelimit

import app.throwback.core.LockState
import app.throwback.core.WebMode
import app.throwback.timelimit.data.StateCodec
import app.throwback.timelimit.data.StateStore
import app.throwback.timelimit.engine.ActionResult
import app.throwback.timelimit.engine.Clock
import app.throwback.timelimit.engine.ClockReading
import app.throwback.timelimit.engine.LockPhase
import app.throwback.timelimit.engine.PinHasher
import app.throwback.timelimit.engine.TimeLimitState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTimeLimitControllerTest {

    /** Saves go through the JSON codec, like the real DataStore file. The next [failReads] reads and [failWrites] writes fail. */
    private class MemoryStore(var text: String? = null, var failReads: Int = 0, var failWrites: Int = 0) : StateStore {
        var saves = 0
        override suspend fun load(): TimeLimitState {
            if (failReads > 0) {
                failReads--
                throw IOException("read failed")
            }
            val t = text ?: return TimeLimitState()
            return StateCodec.decode(t) ?: throw IOException("unreadable")
        }
        override suspend fun save(state: TimeLimitState) {
            if (failWrites > 0) {
                failWrites--
                throw IOException("write failed")
            }
            text = StateCodec.encode(state.persistent())
            saves++
        }
        fun stored(): TimeLimitState? = text?.let(StateCodec::decode)
    }

    /** Phone clock that follows the test scheduler's virtual time. */
    private class TestClock(private val scope: TestScope) : Clock {
        var wallOffset = LocalDateTime.of(2026, 11, 20, 9, 0).atZone(BERLIN).toInstant().toEpochMilli()
        var bootOffset = 3 * HOUR
        override fun now() = ClockReading(
            elapsedMillis = scope.testScheduler.currentTime + bootOffset,
            wallMillis = scope.testScheduler.currentTime + wallOffset,
            zone = BERLIN,
            bootCount = 41,
        )
    }

    private var visible = true

    private fun TestScope.controller(store: MemoryStore, clock: Clock = TestClock(this)): DefaultTimeLimitController {
        val d = StandardTestDispatcher(testScheduler)
        return DefaultTimeLimitController(
            clock = clock,
            store = store,
            scope = backgroundScope,
            ioDispatcher = d,
            hashDispatcher = d,
            hasher = PinHasher(iterations = 1_000),
            isAppVisible = { visible },
        ).also { runCurrent() }
    }

    private fun DefaultTimeLimitController.remaining() = (state.value as LockState.Running).remainingMillis

    @Test
    fun `loads, counts once a second in the foreground and stops in the background`() = runTest {
        val store = MemoryStore()
        val c = controller(store)
        assertTrue(c.ready.value)
        assertEquals(LockState.Disabled, c.state.value)
        assertEquals(ActionResult.Done, c.setup(15, "1234"))
        assertEquals(LockState.Running(15 * MIN), c.state.value)

        c.onForeground(WebMode.FULL)
        advanceTimeBy(3 * SEC)
        runCurrent()
        assertEquals(15 * MIN - 3 * SEC, c.remaining()) // Updated every second.
        advanceTimeBy(MIN - 3 * SEC)
        runCurrent()
        assertEquals(14 * MIN, c.remaining())

        c.onBackground()
        advanceTimeBy(HOUR)
        assertEquals(14 * MIN, c.remaining())
        runCurrent()
        assertEquals(MIN, store.stored()!!.usedMillis) // Saved on background.
    }

    @Test
    fun `messages-only time is not counted`() = runTest {
        val c = controller(MemoryStore())
        c.setup(15, "1234")
        c.onForeground(WebMode.MESSAGES_ONLY)
        advanceTimeBy(10 * MIN)
        assertEquals(15 * MIN, c.remaining())
        c.onBackground()
    }

    @Test
    fun `usage is saved regularly while ticking`() = runTest {
        val store = MemoryStore()
        val c = controller(store)
        c.setup(30, "1234")
        c.onForeground(WebMode.FULL)
        advanceTimeBy(25 * SEC)
        runCurrent()
        val used = store.stored()!!.usedMillis
        assertTrue("saved usage $used", used in 10 * SEC..25 * SEC)
        c.onBackground()
    }

    @Test
    fun `the clock reading alone is saved only now and then, not every 10 s`() = runTest {
        val store = MemoryStore()
        val c = controller(store) // no limit: nothing but the clock reading changes while open
        val loaded = store.saves
        c.onForeground(WebMode.FULL)
        advanceTimeBy(14 * MIN)
        runCurrent()
        assertEquals(loaded, store.saves) // before: a write every 10 s
        advanceTimeBy(MIN + 1)
        runCurrent()
        assertEquals(loaded + 1, store.saves) // every 15 minutes
        c.onBackground()
        runCurrent()
        assertEquals(loaded + 2, store.saves) // and on leaving (a reboot counts on from it)
        // Real changes still go at once.
        c.setup(15, "1234")
        runCurrent()
        assertEquals(loaded + 3, store.saves)
    }

    @Test
    fun `a failed write is sent again with the next update`() = runTest {
        val store = MemoryStore()
        val c = controller(store)
        store.failWrites = 1
        c.setup(15, "1234")
        runCurrent()
        assertNull(store.stored()!!.limitMinutes)
        c.refresh() // Only the clock reading changed since, but the limit isn't on disk yet.
        runCurrent()
        assertEquals(15, store.stored()!!.limitMinutes)
    }

    @Test
    fun `no counting once no activity is visible, even without onBackground`() = runTest {
        val c = controller(MemoryStore())
        c.setup(30, "1234")
        c.onForeground(WebMode.FULL)
        advanceTimeBy(10 * SEC + 1)
        visible = false
        advanceTimeBy(HOUR)
        assertTrue(c.remaining() >= 30 * MIN - 12 * SEC)
    }

    @Test
    fun `the whole lock flow with real PIN hashing`() = runTest {
        val c = controller(MemoryStore())
        c.setup(15, "4821")
        c.onForeground(WebMode.FULL)
        advanceTimeBy(15 * MIN + 1)
        assertEquals(LockState.Locked, c.state.value)
        assertTrue(c.view.value.lock is LockPhase.Waiting)

        assertTrue(c.extend("4821") is ActionResult.NotAllowed) // Still waiting.
        advanceTimeBy(5 * MIN)
        assertEquals(LockPhase.CanExtend(1), c.view.value.lock)
        assertEquals(ActionResult.WrongPin(4), c.extend("0000"))
        assertEquals(LockState.Locked, c.state.value)
        assertEquals(ActionResult.Done, c.extend("4821"))
        assertEquals(LockPhase.Granted, c.view.value.lock)
        c.acknowledgeExtension()
        assertEquals(LockState.Running(5 * MIN), c.state.value)
        c.onBackground()
    }

    @Test
    fun `state survives a process restart`() = runTest {
        val store = MemoryStore()
        val clock = TestClock(this)
        val first = controller(store, clock)
        first.setup(30, "1234")
        first.onForeground(WebMode.FULL)
        advanceTimeBy(10 * MIN)
        runCurrent()
        first.onBackground()
        assertEquals(ActionResult.Scheduled(LocalDateTime.of(2026, 11, 21, 0, 0).toLocalDate()), first.setLimit(60, "1234"))
        runCurrent()

        val second = controller(store, clock)
        assertEquals(LockState.Running(20 * MIN), second.state.value)
        assertEquals(60, second.view.value.pendingLimit!!.minutes)
        assertEquals(ActionResult.WrongPin(4), second.turnOff("9999"))
        assertNotNull(store.stored()!!.pin)
    }

    @Test
    fun `a foreground session opened before loading finishes still counts`() = runTest {
        val store = MemoryStore(StateCodec.encode(TimeLimitState(limitMinutes = 30, pin = PinHasher(1_000).hash("1234"))))
        val d = StandardTestDispatcher(testScheduler)
        val c = DefaultTimeLimitController(TestClock(this), store, backgroundScope, d, d, PinHasher(1_000))
        assertFalse(c.ready.value)
        c.onForeground(WebMode.FULL) // AppRoot may call this before the DataStore read completes.
        advanceTimeBy(MIN + 1)
        assertTrue(c.ready.value)
        assertEquals(29 * MIN, c.remaining())
        c.onBackground()
    }

    @Test
    fun `nothing is written before the stored state is loaded`() = runTest {
        val store = MemoryStore(StateCodec.encode(TimeLimitState(limitMinutes = 45)))
        val d = StandardTestDispatcher(testScheduler)
        val c = DefaultTimeLimitController(TestClock(this), store, backgroundScope, d, d, PinHasher(1_000))
        c.onForeground(WebMode.FULL)
        c.onBackground()
        assertEquals(0, store.saves)
        runCurrent()
        assertEquals(45, store.stored()!!.limitMinutes)
    }

    @Test
    fun `a failed read is tried again before the limit is taken as missing`() = runTest {
        val store = MemoryStore(StateCodec.encode(TimeLimitState(limitMinutes = 30)), failReads = 2)
        val c = controller(store)
        advanceTimeBy(SEC)
        runCurrent()
        assertTrue(c.ready.value)
        assertEquals(30, c.view.value.limitMinutes)
    }

    @Test
    fun `a saved state that can't be read is kept until a new limit is set up`() = runTest {
        // E.g. restored from a newer version's backup. Throwback works without a limit meanwhile...
        val unreadable = StateCodec.encode(TimeLimitState(limitMinutes = 30)).replace("\"version\":1", "\"version\":2")
        val store = MemoryStore(unreadable)
        val c = controller(store)
        advanceTimeBy(SEC)
        runCurrent()
        assertTrue(c.ready.value)
        assertEquals(LockState.Disabled, c.state.value)
        // ...but doesn't write "no limit" over it: the next start (or an update) can still read it.
        c.onForeground(WebMode.FULL)
        advanceTimeBy(20 * MIN)
        c.onBackground()
        runCurrent()
        assertEquals(0, store.saves)
        assertEquals(unreadable, store.text)
        // A limit the user sets up replaces it.
        assertEquals(ActionResult.Done, c.setup(15, "1234"))
        runCurrent()
        assertEquals(15, store.stored()!!.limitMinutes)
    }

    @Test
    fun `lowering needs no PIN, raising without one is refused without counting a try`() = runTest {
        val c = controller(MemoryStore())
        c.setup(30, "1234")
        assertEquals(ActionResult.Done, c.setLimit(15))
        assertEquals(LockState.Running(15 * MIN), c.state.value)
        assertEquals(ActionResult.PinRequired, c.setLimit(45))
        assertEquals(ActionResult.WrongPin(4), c.setLimit(45, "1111"))
    }

    @Test
    fun `the passes' trusted clock - none before loading, then today and trusted time`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val clock = TestClock(this)
        val c = DefaultTimeLimitController(clock, MemoryStore(), backgroundScope, d, d, PinHasher(1_000))
        assertNull(c.trustedTime()) // no passes before the stored state is known
        runCurrent()
        val day = LocalDateTime.of(2026, 11, 20, 0, 0).toLocalDate().toEpochDay()
        val t = c.trustedTime()!!
        assertEquals(day, t.epochDay)
        assertEquals(clock.now().wallMillis, t.wallMillis)
        assertEquals(BERLIN, t.zone)
        // Moving the phone's clock a day ahead changes neither (no app restart needed: it's the same boot).
        clock.wallOffset += DAY
        advanceTimeBy(MIN)
        assertEquals(day, c.trustedTime()!!.epochDay)
        assertEquals(t.wallMillis + MIN, c.trustedTime()!!.wallMillis)
    }

    @Test
    fun `in the background the lock state is current once asked for, past midnight too`() = runTest {
        val c = controller(MemoryStore())
        c.setup(15, "1234")
        c.onForeground(WebMode.FULL)
        advanceTimeBy(15 * MIN + 1)
        c.onBackground()
        assertEquals(LockState.Locked, c.state.value)
        advanceTimeBy(DAY) // no ticks in the background: nothing recomputes the state...
        assertEquals(LockState.Locked, c.state.value)
        // ...until someone (the relay, a pass) asks for it.
        assertEquals(LockState.Running(15 * MIN), c.currentLockState())
        assertEquals(LockState.Running(15 * MIN), c.state.value)
    }

    @Test
    fun `forgot PIN, then set a new one after three days`() = runTest {
        val c = controller(MemoryStore())
        c.setup(30, "1234")
        assertEquals(ActionResult.Done, c.requestPinReset())
        assertEquals(ActionResult.PinResetPending, c.verifyPin("1234"))
        assertTrue(c.completePinReset("5678") is ActionResult.NotAllowed)
        advanceTimeBy(3 * DAY)
        c.refresh()
        assertTrue(c.view.value.pinReset!!.ready)
        assertEquals(ActionResult.Done, c.completePinReset("5678"))
        assertNull(c.view.value.pinReset)
        assertEquals(ActionResult.Done, c.verifyPin("5678"))
        assertEquals(ActionResult.WrongPin(4), c.verifyPin("1234"))
    }
}
