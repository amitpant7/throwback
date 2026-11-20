package app.throwback.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateAskTest {
    @Test
    fun `asks once, after a week and five days of use`() {
        assertFalse(RateAsk.due(firstDay = 100, today = 106, useDays = 7, asked = false)) // under a week
        assertFalse(RateAsk.due(firstDay = 100, today = 110, useDays = 4, asked = false)) // used too little
        assertTrue(RateAsk.due(firstDay = 100, today = 107, useDays = 5, asked = false))
        assertFalse(RateAsk.due(firstDay = 100, today = 140, useDays = 30, asked = true)) // only once
    }
}
