package app.throwback.connect.relay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RelayStatsTest {
    @Test
    fun `sums the last 7 days including today`() {
        val today = LocalDate.of(2026, 11, 20)
        val d = today.toEpochDay()
        val counts = mapOf(d to 3, d - 6 to 2, d - 7 to 50, d + 1 to 9)
        assertEquals(5, RelayStats.sum(counts, today, 7))
        assertEquals(3, RelayStats.sum(counts, today, 1))
        assertEquals(0, RelayStats.sum(emptyMap(), today, 7))
    }
}
