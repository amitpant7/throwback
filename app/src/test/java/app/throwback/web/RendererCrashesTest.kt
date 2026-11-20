package app.throwback.web

import app.throwback.web.RendererCrashes.Next
import org.junit.Assert.assertEquals
import org.junit.Test

class RendererCrashesTest {

    private var now = 1_000_000L
    private val crashes = RendererCrashes(now = { now }, windowMillis = 60_000)

    @Test
    fun `a renderer killed for memory is just replaced`() {
        repeat(5) { assertEquals(Next.RESTORE, crashes.onGone(crashed = false)) }
    }

    @Test
    fun `crashes in a row go home, then give up`() {
        assertEquals(Next.RESTORE, crashes.onGone(crashed = true))
        now += 10_000
        assertEquals(Next.HOME, crashes.onGone(crashed = true))
        now += 59_999
        assertEquals(Next.GIVE_UP, crashes.onGone(crashed = true))
        now += 1_000
        assertEquals(Next.GIVE_UP, crashes.onGone(crashed = true))
    }

    @Test
    fun `crashes far apart start over`() {
        assertEquals(Next.RESTORE, crashes.onGone(crashed = true))
        now += 60_000
        assertEquals(Next.RESTORE, crashes.onGone(crashed = true))
        now += 30_000
        assertEquals(Next.HOME, crashes.onGone(crashed = true))
        now += 5 * 60_000
        assertEquals(Next.RESTORE, crashes.onGone(crashed = true))
    }

    @Test
    fun `try again starts counting afresh`() {
        crashes.onGone(crashed = true)
        crashes.onGone(crashed = true)
        assertEquals(Next.GIVE_UP, crashes.onGone(crashed = true))
        crashes.reset()
        assertEquals(Next.RESTORE, crashes.onGone(crashed = true))
    }
}
