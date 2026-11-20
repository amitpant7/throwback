package app.throwback.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationsTest {

    @Test
    fun `countdowns round up so they never show zero while time is left`() {
        assertEquals(Durations.MinSec(3, 28), Durations.minSec(3 * MIN + 27 * SEC + 1))
        assertEquals(Durations.MinSec(5, 0), Durations.minSec(5 * MIN))
        assertEquals(Durations.MinSec(0, 1), Durations.minSec(1))
        assertEquals(Durations.MinSec(0, 0), Durations.minSec(0))
        assertEquals(Durations.MinSec(0, 0), Durations.minSec(-5))
    }

    @Test
    fun `hours and minutes`() {
        assertEquals(Durations.HoursMinutes(6, 42), Durations.hoursMinutes(6 * HOUR + 41 * MIN + 1))
        assertEquals(Durations.HoursMinutes(0, 1), Durations.hoursMinutes(SEC))
        assertEquals(Durations.HoursMinutes(1, 0), Durations.hoursMinutes(HOUR))
    }

    @Test
    fun `days and hours only from a day up`() {
        assertEquals(Durations.DaysHours(2, 23), Durations.daysHours(3 * DAY - 30 * MIN))
        assertEquals(Durations.DaysHours(1, 0), Durations.daysHours(DAY))
        assertNull(Durations.daysHours(DAY - 1))
    }

    @Test
    fun `chip shows minutes rounded up, then the seconds in the last minute`() {
        assertEquals(Durations.Chip.Minutes(18), Durations.chip(17 * MIN + 30 * SEC))
        assertEquals(Durations.Chip.Minutes(2), Durations.chip(MIN + 1))
        assertEquals(Durations.Chip.LastMinute(60), Durations.chip(MIN))
        assertEquals(Durations.Chip.LastMinute(42), Durations.chip(42 * SEC))
        assertEquals(Durations.Chip.LastMinute(1), Durations.chip(1))
    }

    @Test
    fun `time spent rounds down, and under a minute is its own case`() {
        assertEquals(Durations.HoursMinutes(0, 17), Durations.spent(17 * MIN + 59 * SEC))
        assertEquals(Durations.HoursMinutes(1, 5), Durations.spent(HOUR + 5 * MIN + 30 * SEC))
        assertEquals(Durations.HoursMinutes(0, 1), Durations.spent(MIN))
        assertNull(Durations.spent(1))
        assertNull(Durations.spent(MIN - 1))
        assertEquals(Durations.HoursMinutes(0, 0), Durations.spent(0))
    }

    @Test
    fun `time left and time spent of the same moment round apart`() {
        // 17.5 minutes: "Your day" and the chip both say 18 min left (they share remainingText);
        // spent, the same span reads 17 min.
        val span = 17 * MIN + 30 * SEC
        assertEquals(Durations.Chip.Minutes(18), Durations.chip(span))
        assertEquals(Durations.HoursMinutes(0, 17), Durations.spent(span))
    }

    private companion object {
        const val SEC = 1_000L
        const val MIN = 60 * SEC
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
    }
}
