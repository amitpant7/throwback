package app.throwback.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullGestureTest {

    // threshold 100 px of indicator travel = 200 px of finger travel (resistance 2), slop 10 px.
    private fun gesture() = PullGesture(thresholdPx = 100f, touchSlopPx = 10f)

    private fun PullGesture.dragTo(y: Float, x: Float = 0f, atTop: Boolean = true) = move(x, y, atTop)

    @Test
    fun `a long pull at the top refreshes`() {
        val g = gesture()
        g.down(0f, 0f, documentAtTop = true, pageAllowsPull = true)
        g.scriptReport(atTop = true)
        g.dragTo(20f)
        g.dragTo(250f)
        assertTrue(g.progress >= 1f)
        assertTrue(g.up())
        assertEquals(0f, g.progress)
    }

    @Test
    fun `a short pull does not refresh`() {
        val g = gesture()
        g.down(0f, 0f, documentAtTop = true, pageAllowsPull = true)
        g.scriptReport(atTop = true)
        g.dragTo(20f)
        g.dragTo(120f)
        assertTrue(g.progress in 0.01f..0.99f)
        assertFalse(g.up())
    }

    @Test
    fun `nothing happens until the page script confirms the touch is at the top`() {
        val g = gesture()
        g.down(0f, 0f, documentAtTop = true, pageAllowsPull = true)
        g.dragTo(250f)
        assertEquals(0f, g.progress)
        g.scriptReport(atTop = true) // late report: the pull starts counting from here
        g.dragTo(260f)
        assertTrue(g.progress > 0f)
    }

    @Test
    fun `inner scroller or dialog under the finger cancels`() {
        val g = gesture()
        g.down(0f, 0f, documentAtTop = true, pageAllowsPull = true)
        g.scriptReport(atTop = false)
        g.dragTo(300f)
        assertEquals(0f, g.progress)
        assertFalse(g.up())
    }

    @Test
    fun `not at the top, or a page without pull-to-refresh, never pulls`() {
        listOf(false to true, true to false).forEach { (atTop, allowed) ->
            val g = gesture()
            g.down(0f, 0f, documentAtTop = atTop, pageAllowsPull = allowed)
            g.scriptReport(atTop = true)
            g.dragTo(300f)
            assertFalse(g.up())
        }
    }

    @Test
    fun `scrolling the page or moving up cancels`() {
        val g = gesture()
        g.down(0f, 100f, documentAtTop = true, pageAllowsPull = true)
        g.scriptReport(atTop = true)
        g.dragTo(200f)
        g.dragTo(250f, atTop = false)
        assertFalse(g.up())

        val h = gesture()
        h.down(0f, 100f, documentAtTop = true, pageAllowsPull = true)
        h.scriptReport(atTop = true)
        h.dragTo(80f)
        h.dragTo(400f)
        assertFalse(h.up())
    }

    @Test
    fun `horizontal swipes (stories tray) never pull`() {
        val g = gesture()
        g.down(0f, 0f, documentAtTop = true, pageAllowsPull = true)
        g.scriptReport(atTop = true)
        g.dragTo(y = 15f, x = 80f)
        g.dragTo(y = 300f, x = 90f)
        assertFalse(g.up())
    }

    @Test
    fun `progress is capped`() {
        val g = gesture()
        g.down(0f, 0f, documentAtTop = true, pageAllowsPull = true)
        g.scriptReport(atTop = true)
        g.dragTo(20f)
        g.dragTo(5_000f)
        assertEquals(PullGesture.MAX_PROGRESS, g.progress)
    }
}
