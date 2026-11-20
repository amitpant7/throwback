package app.throwback.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateAskTest {
    @Test
    fun `asks once, after a month and ten days of use, never again`() {
        assertFalse(RateAsk.due(firstDay = 100, today = 129, useDays = 20, asked = false)) // under a month
        assertFalse(RateAsk.due(firstDay = 100, today = 140, useDays = 9, asked = false)) // used too little
        assertTrue(RateAsk.due(firstDay = 100, today = 130, useDays = 10, asked = false))
        assertFalse(RateAsk.due(firstDay = 100, today = 200, useDays = 60, asked = true)) // only once
    }
}
