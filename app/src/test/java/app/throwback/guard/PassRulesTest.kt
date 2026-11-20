package app.throwback.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassRulesTest {
    @Test
    fun `open Instagram for 5 minutes works once a day and never while locked`() {
        assertTrue(PassRules.visitAllowed(lastVisitDay = null, today = 200, locked = false))
        assertFalse(PassRules.visitAllowed(lastVisitDay = 200, today = 200, locked = false))
        assertTrue(PassRules.visitAllowed(lastVisitDay = 199, today = 200, locked = false))
        assertFalse(PassRules.visitAllowed(lastVisitDay = null, today = 200, locked = true))
    }

    @Test
    fun `call button allows 3 tries an hour`() {
        val h = 3_600_000L
        val now = 10 * h
        assertTrue(PassRules.callAllowed(listOf(now - 1000, now - 2000), now))
        assertFalse(PassRules.callAllowed(listOf(now - 1000, now - 2000, now - 3000), now))
        assertTrue(PassRules.callAllowed(listOf(now - h - 1, now - 2000, now - 3000), now)) // oldest is over an hour old
        assertEquals(2, PassRules.recentTaps(listOf(now - h - 1, now - 2000, now - 3000), now).size)
    }
}
