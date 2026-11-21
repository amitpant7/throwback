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
}
