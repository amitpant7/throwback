package app.throwback.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageDayStatsTest {
    @Test
    fun `reads the page's counts and ignores what it doesn't know`() {
        val s = PageDayStats.parse(
            """{"areas":{"feed":620,"stories":95},"adsRemoved":4,"suggestionsRemoved":31,"exploreUsedSec":120,""" +
                """"exploreAllowanceSec":300,"discoverShown":2,"discoverMax":20,"reelCooldownMs":0,"newField":1}""",
        )!!
        assertEquals(620L, s.areas["feed"])
        assertEquals(31, s.suggestionsRemoved)
        assertEquals(300, s.exploreAllowanceSec)
        assertNull(PageDayStats.parse("not json"))
        assertNull(PageDayStats.parse(null))
    }
}
