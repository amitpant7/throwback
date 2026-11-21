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
        // A fractional cooldown (pages from before 2026-11-21 sent one) is read, not a parse failure.
        val cooling = PageDayStats.parse("""{"exploreUsedSec":12,"reelCooldownMs":3599123.4000000004}""")!!
        assertEquals(3599123L, cooling.reelCooldownMs)
        assertEquals(12, cooling.exploreUsedSec)
        assertNull(PageDayStats.parse(null))
    }
}
