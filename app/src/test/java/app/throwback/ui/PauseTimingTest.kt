package app.throwback.ui

import app.throwback.ui.pause.PauseTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseTimingTest {

    @Test
    fun `lasts three seconds`() {
        assertEquals(3_000L, PauseTiming.DURATION_MILLIS)
        assertFalse(PauseTiming.isDone(2_999))
        assertTrue(PauseTiming.isDone(3_000))
    }

    @Test
    fun `progress is clamped to 0 to 1`() {
        assertEquals(0f, PauseTiming.progress(-50), 0f)
        assertEquals(0.5f, PauseTiming.progress(1_500), 0.0001f)
        assertEquals(1f, PauseTiming.progress(10_000), 0f)
    }

    @Test
    fun `countdown reads 3, 2, 1 and never 0`() {
        assertEquals(3, PauseTiming.secondsLeft(0))
        assertEquals(3, PauseTiming.secondsLeft(999))
        assertEquals(2, PauseTiming.secondsLeft(1_000))
        assertEquals(1, PauseTiming.secondsLeft(2_500))
        assertEquals(1, PauseTiming.secondsLeft(3_000))
        assertEquals(1, PauseTiming.secondsLeft(9_000))
    }
}
