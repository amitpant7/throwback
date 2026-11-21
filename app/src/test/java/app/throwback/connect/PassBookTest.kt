package app.throwback.connect

import app.throwback.connect.guard.PassRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The passes' counters, on the trusted clock: days are epoch days, times trusted wall ms. */
class PassBookTest {
    private val m = 60_000L
    private val h = 60 * m
    private val today = 20_000L
    private val noon = today * 24 * h + 12 * h

    @Test
    fun `open Instagram for 5 minutes works once a day and never while locked`() {
        val b = PassBook()
        assertTrue(b.visitAllowed(today, locked = false))
        assertFalse(b.visitAllowed(today, locked = true))
        val used = b.visited(today)
        assertFalse(used.visitAllowed(today, locked = false))
        assertTrue(used.visitAllowed(today + 1, locked = false))
    }

    @Test
    fun `a day that went back never gives a second visit`() {
        // The trusted day never goes back; if a stored day were ever ahead (lost time-limit data),
        // it is not a new day. Before, any different day (e.g. a time zone switched back) was.
        val used = PassBook().visited(today)
        assertFalse(used.visitAllowed(today - 1, locked = false))
    }

    @Test
    fun `call button allows 15 tries an hour`() {
        val max = PassBook.CALL_TAPS_PER_HOUR
        assertEquals(15, max)
        var b = PassBook()
        repeat(max) { i ->
            assertTrue(b.callAllowed(noon + i * m))
            b = b.callTapped(noon + i * m)
        }
        assertFalse(b.callAllowed(noon + max * m))
        assertFalse(b.callAllowed(noon + h - 1)) // the first tap is not yet an hour old
        assertTrue(b.callAllowed(noon + h)) // now it is
        assertEquals(max, b.callTapped(noon + h).callTaps.size) // and it is dropped
    }

    @Test
    fun `call taps from a clock that was ahead still count`() {
        // Before, a tap "in the future" didn't count, so moving the clock back an hour reset them.
        val b = (1..PassBook.CALL_TAPS_PER_HOUR).fold(PassBook()) { book, _ -> book.callTapped(noon) }
        assertFalse(b.callAllowed(noon - 30 * m))
    }

    @Test
    fun `story budget - a post costs 2 minutes, a pass without one its exact time`() {
        val budget = 10 * m
        val lock = 30 * m
        var b = PassBook()
        assertTrue(b.storyAllowed(noon))
        b = b.storyCharged(noon, 2 * m, budget, lock) // posted
        assertEquals(2 * m, b.storyUsed(noon))
        b = b.storyCharged(noon + m, 70_000, budget, lock) // left after 1:10 without a post: not rounded
        assertEquals(2 * m + 70_000, b.storyUsed(noon + m))
        assertTrue(b.storyAllowed(noon + m))
        assertEquals(0, b.storyLockMinutes(noon + m))
    }

    @Test
    fun `story budget - used up, a 30-minute lock, then it starts again from nothing`() {
        val budget = 10 * m
        val lock = 30 * m
        var b = PassBook()
        repeat(4) { b = b.storyCharged(noon, 2 * m, budget, lock) } // 4 posts: 8 minutes
        assertTrue(b.storyAllowed(noon)) // with 8 used, the next pass still gets its full 5 minutes
        b = b.storyCharged(noon + 5 * m, 5 * m, budget, lock) // no post: 13 minutes, past the budget
        assertFalse(b.storyAllowed(noon + 5 * m))
        assertEquals(30, b.storyLockMinutes(noon + 5 * m))
        assertEquals(1, b.storyLockMinutes(noon + 34 * m + 1))
        assertEquals(0L, b.storyUsed(noon + 10 * m)) // shown as nothing during the lock
        assertTrue(b.storyAllowed(noon + 35 * m))
        assertEquals(0L, b.storyUsed(noon + 35 * m))
        b = b.storyCharged(noon + 40 * m, 2 * m, budget, lock)
        assertEquals(2 * m, b.storyUsed(noon + 40 * m))
        assertEquals(null, b.storyLockUntil) // the old lock is gone
    }

    @Test
    fun `an open story pass is charged exactly once`() {
        val budget = 10 * m
        val lock = 30 * m
        var b = PassBook().storyOpened(noon)
        assertEquals(noon, b.storyOpenAt)
        b = b.storyClosed(noon + 3 * m, 3 * m, budget, lock) // left after 3 minutes
        assertEquals(3 * m, b.storyUsed(noon + 3 * m))
        assertEquals(null, b.storyOpenAt)
        // A second close of the same pass (the relay's late notice, a settle) charges nothing.
        assertEquals(b, b.storyClosed(noon + 4 * m, 2 * m, budget, lock))
    }

    @Test
    fun `story budget - five posts in a row reach it exactly`() {
        var b = PassBook()
        repeat(5) { b = b.storyCharged(noon, 2 * m, 10 * m, 30 * m) }
        assertFalse(b.storyAllowed(noon))
    }

    @Test
    fun `the app's settings 5 times a day, its post editor 10 times`() {
        var b = PassBook()
        repeat(PassBook.SETTINGS_PER_DAY) {
            assertTrue(b.settingsAllowed(today))
            b = b.settingsOpened(today)
        }
        assertFalse(b.settingsAllowed(today))
        assertTrue(b.settingsAllowed(today + 1))
        repeat(PassBook.CREATE_PER_DAY) {
            assertTrue(b.createAllowed(today))
            b = b.createOpened(today)
        }
        assertFalse(b.createAllowed(today))
        assertTrue(b.createAllowed(today + 1))
        assertEquals(1, b.createOpened(today + 1).createCount) // a new day starts at 1
        assertEquals(5, PassBook.SETTINGS_PER_DAY)
        assertEquals(10, PassBook.CREATE_PER_DAY)
    }

    @Test
    fun `app time counts per day`() {
        val b = PassBook().withAppTime(today, 10_000).withAppTime(today, 20_000)
        assertEquals(30_000L, b.appMillisOn(today))
        assertEquals(0L, b.appMillisOn(today + 1))
        assertEquals(5_000L, b.withAppTime(today + 1, 5_000).appMillisOn(today + 1))
    }
}
