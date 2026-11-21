package app.throwback.web

import app.throwback.web.RendererCrashes.Next
import org.junit.Assert.assertEquals
import org.junit.Test

class RendererCrashesTest {

    private var now = 1_000_000L
    private val crashes = RendererCrashes(now = { now }, windowMillis = 60_000)

    @Test
    fun `a renderer killed in the background is just replaced`() {
        repeat(5) { assertEquals(Next.RESTORE, crashes.onGone(crashed = false, inFront = false)) }
    }

    @Test
    fun `kills while in front count like crashes`() {
        // A page that eats memory gets its renderer killed on every load: that must not loop either.
        assertEquals(Next.RESTORE, crashes.onGone(crashed = false, inFront = true))
        now += 5_000
        assertEquals(Next.HOME, crashes.onGone(crashed = false, inFront = true))
        now += 5_000
        assertEquals(Next.RESTORE, crashes.onGone(crashed = false, inFront = false)) // not counted
        assertEquals(Next.GIVE_UP, crashes.onGone(crashed = true, inFront = false))
    }

    @Test
    fun `crashes in a row go home, then give up`() {
        assertEquals(Next.RESTORE, crashes.onGone(crashed = true, inFront = true))
        now += 10_000
        assertEquals(Next.HOME, crashes.onGone(crashed = true, inFront = true))
        now += 59_999
        assertEquals(Next.GIVE_UP, crashes.onGone(crashed = true, inFront = true))
        now += 1_000
        assertEquals(Next.GIVE_UP, crashes.onGone(crashed = true, inFront = true))
    }

    @Test
    fun `crashes far apart start over`() {
        assertEquals(Next.RESTORE, crashes.onGone(crashed = true, inFront = true))
        now += 60_000
        assertEquals(Next.RESTORE, crashes.onGone(crashed = true, inFront = true))
        now += 30_000
        assertEquals(Next.HOME, crashes.onGone(crashed = true, inFront = true))
        now += 5 * 60_000
        assertEquals(Next.RESTORE, crashes.onGone(crashed = true, inFront = true))
    }

    @Test
    fun `try again starts counting afresh`() {
        crashes.onGone(crashed = true, inFront = true)
        crashes.onGone(crashed = true, inFront = true)
        assertEquals(Next.GIVE_UP, crashes.onGone(crashed = true, inFront = true))
        crashes.reset()
        assertEquals(Next.RESTORE, crashes.onGone(crashed = true, inFront = true))
    }
}
