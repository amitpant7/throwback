package app.throwback.guard

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
    fun `call button allows 3 tries an hour`() {
        var b = PassBook()
        repeat(3) { i ->
            assertTrue(b.callAllowed(noon + i * m))
            b = b.callTapped(noon + i * m)
        }
        assertFalse(b.callAllowed(noon + 3 * m))
        assertFalse(b.callAllowed(noon + h - 1)) // the first tap is not yet an hour old
        assertTrue(b.callAllowed(noon + h)) // now it is
        assertEquals(3, b.callTapped(noon + h).callTaps.size) // and it is dropped
    }

    @Test
    fun `call taps from a clock that was ahead still count`() {
        // Before, a tap "in the future" didn't count, so moving the clock back an hour reset them.
        val b = PassBook().callTapped(noon).callTapped(noon).callTapped(noon)
        assertFalse(b.callAllowed(noon - 30 * m))
    }

    @Test
    fun `story passes - 3 a day, 30 minutes apart`() {
        var b = PassBook()
        val wait = 30 * m
        assertTrue(b.storyAllowed(today, noon, perDay = 3))
        b = b.storyStarted(today, noon, wait)
        assertEquals(1, b.storiesOn(today))
        assertFalse(b.storyAllowed(today, noon + 29 * m, perDay = 3))
        assertEquals(1, b.storyWaitMinutes(today, noon + 29 * m, perDay = 3))
        assertEquals(30, b.storyWaitMinutes(today, noon, perDay = 3))
        assertTrue(b.storyAllowed(today, noon + 30 * m, perDay = 3))
        b = b.storyStarted(today, noon + h, wait).storyStarted(today, noon + 2 * h, wait)
        assertFalse(b.storyAllowed(today, noon + 5 * h, perDay = 3)) // 3 a day
        assertNull(b.storyWaitMinutes(today, noon + 5 * h, perDay = 3)) // not today
        assertTrue(b.storyAllowed(today + 1, noon + 12 * h, perDay = 3)) // a new day
        assertEquals(0, b.storyWaitMinutes(today + 1, noon + 12 * h, perDay = 3))
    }

    @Test
    fun `a story pass that posted is free, one left early is free but starts a short wait`() {
        val started = PassBook().storyStarted(today, noon, waitMillis = 30 * m)
        val posted = started.storyPosted(today)
        assertEquals(0, posted.storiesOn(today))
        assertTrue(posted.storyAllowed(today, noon + m, perDay = 3))

        val left = started.storyLeftEarly(today, noon + 2 * m)
        assertEquals(0, left.storiesOn(today))
        assertFalse(left.storyAllowed(today, noon + 11 * m, perDay = 3))
        assertTrue(left.storyAllowed(today, noon + 2 * m + PassRules.FREE_LEAVE_WAIT_MS, perDay = 3))
    }

    @Test
    fun `app time counts per day`() {
        val b = PassBook().withAppTime(today, 10_000).withAppTime(today, 20_000)
        assertEquals(30_000L, b.appMillisOn(today))
        assertEquals(0L, b.appMillisOn(today + 1))
        assertEquals(5_000L, b.withAppTime(today + 1, 5_000).appMillisOn(today + 1))
    }
}
