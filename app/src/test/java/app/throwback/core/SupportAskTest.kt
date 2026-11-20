package app.throwback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportAskTest {
    @Test
    fun `after a month, then every two months, never after a tip`() {
        assertNull(SupportAsk.due(firstUseDay = 100, today = 129, lastShownDay = null, tipped = false))
        assertEquals("first", SupportAsk.due(firstUseDay = 100, today = 130, lastShownDay = null, tipped = false))
        assertNull(SupportAsk.due(firstUseDay = 100, today = 189, lastShownDay = 130, tipped = false))
        assertEquals("again", SupportAsk.due(firstUseDay = 100, today = 190, lastShownDay = 130, tipped = false))
        assertNull(SupportAsk.due(firstUseDay = 100, today = 400, lastShownDay = 130, tipped = true))
        assertNull(SupportAsk.due(firstUseDay = null, today = 400, lastShownDay = null, tipped = false))
    }
}
