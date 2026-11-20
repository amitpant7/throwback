package app.throwback.web.rules

import app.throwback.web.TestRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesSelectionTest {

    private val bundled = TestRules.bundled
    private val newer = bundled.copy(version = bundled.version + 1)
    private val older = bundled.copy(version = bundled.version - 1)

    @Test
    fun `newer cached copy wins`() {
        assertSame(newer, RulesSelection.choose(bundled, newer))
    }

    @Test
    fun `bundled copy wins over an older or equal cache (app update with fresher rules)`() {
        assertSame(bundled, RulesSelection.choose(bundled, older))
        assertSame(bundled, RulesSelection.choose(bundled, bundled.copy()))
    }

    @Test
    fun `whichever exists is used`() {
        assertSame(bundled, RulesSelection.choose(bundled, null))
        assertSame(older, RulesSelection.choose(null, older))
        assertNull(RulesSelection.choose(null, null))
    }

    @Test
    fun `fetch is due once a day`() {
        val day = RULES_CHECK_INTERVAL_MILLIS
        val now = 1_000_000_000_000L
        assertTrue(RulesSchedule.isFetchDue(null, now))
        assertFalse(RulesSchedule.isFetchDue(now - 1, now))
        assertFalse(RulesSchedule.isFetchDue(now - day + 1, now))
        assertTrue(RulesSchedule.isFetchDue(now - day, now))
        assertTrue(RulesSchedule.isFetchDue(now - 3 * day, now))
    }

    @Test
    fun `a clock moved back does not block updates`() {
        val now = 1_000_000_000_000L
        assertTrue(RulesSchedule.isFetchDue(now + 2 * RULES_CHECK_INTERVAL_MILLIS, now))
        // Small skew is tolerated.
        assertFalse(RulesSchedule.isFetchDue(now + 1_000, now))
    }

    @Test
    fun `daily interval is 24 hours`() {
        assertEquals(24L * 60 * 60 * 1000, RULES_CHECK_INTERVAL_MILLIS)
    }
}
