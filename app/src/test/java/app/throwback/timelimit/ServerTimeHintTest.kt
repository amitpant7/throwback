package app.throwback.timelimit

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerTimeHintTest {
    @Test
    fun `the server time runs on with the stopwatch from when it arrived`() {
        ServerTimeHint.note(serverMillis = 1_795_225_093_000L, elapsedNow = 10_000L)
        assertEquals(1_795_225_093_000L, ServerTimeHint.now(elapsedNow = 10_000L))
        assertEquals(1_795_225_153_000L, ServerTimeHint.now(elapsedNow = 70_000L))
        ServerTimeHint.note(serverMillis = 1_795_225_200_000L, elapsedNow = 80_000L) // a newer one replaces it
        assertEquals(1_795_225_201_000L, ServerTimeHint.now(elapsedNow = 81_000L))
    }
}
