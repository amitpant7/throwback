package app.throwback.ui.pause

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseTimingTest {

    @Test
    fun `lasts four seconds`() {
        assertEquals(4_000L, PauseTiming.DURATION_MILLIS)
        assertFalse(PauseTiming.isDone(3_999))
        assertTrue(PauseTiming.isDone(4_000))
    }

    @Test
    fun `progress is clamped to 0 to 1`() {
        assertEquals(0f, PauseTiming.progress(-50), 0f)
        assertEquals(0.5f, PauseTiming.progress(2_000), 0.0001f)
        assertEquals(1f, PauseTiming.progress(10_000), 0f)
    }

    @Test
    fun `countdown reads 4, 3, 2, 1 and never 0`() {
        assertEquals(4, PauseTiming.secondsLeft(0))
        assertEquals(4, PauseTiming.secondsLeft(999))
        assertEquals(3, PauseTiming.secondsLeft(1_000))
        assertEquals(1, PauseTiming.secondsLeft(3_500))
        assertEquals(1, PauseTiming.secondsLeft(4_000))
        assertEquals(1, PauseTiming.secondsLeft(9_000))
    }

    @Test
    fun `a tip every 50th pause, at most 10 in all`() {
        assertEquals(listOf(49), (0 until 50).filter { PauseLines.tipTurn(it, tipsShown = 0) })
        assertTrue(PauseLines.tipTurn(49, tipsShown = 9))
        assertFalse(PauseLines.tipTurn(49, tipsShown = 10))
    }

    @Test
    fun `a quote item splits into its text and attribution`() {
        assertEquals("Life is long if you know how to use it." to "Seneca", PauseLines.splitQuote("Life is long if you know how to use it.|Seneca"))
        assertEquals("Just a line." to null, PauseLines.splitQuote("Just a line."))
    }
}
