package app.throwback.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTrackerTest {

    @Test
    fun `follows ringing, in-call and the end of a call`() {
        val t = CallTracker()
        assertFalse(t.state.value.listenerConnected)
        t.onConnected(emptySet())
        assertTrue(t.state.value.listenerConnected)
        t.onCallShowing("ring")
        assertTrue(t.state.value.inCall)
        // Ringing replaced by the ongoing-call notification: still in a call.
        t.onCallShowing("ongoing")
        t.onRemoved("ring", now = 1_000)
        assertTrue(t.state.value.inCall)
        assertNull(t.state.value.lastCallEndedAt)
        t.onRemoved("ongoing", now = 5_000)
        assertFalse(t.state.value.inCall)
        assertEquals(5_000L, t.state.value.lastCallEndedAt)
    }

    @Test
    fun `other notifications going away don't touch the call state`() {
        val t = CallTracker()
        t.onConnected(setOf("call"))
        t.onRemoved("message", now = 1_000)
        assertTrue(t.state.value.inCall)
        assertNull(t.state.value.lastCallEndedAt)
    }

    @Test
    fun `calls no longer showing are dropped, others kept`() {
        val t = CallTracker()
        t.onConnected(setOf("a", "b"))
        t.keepOnly(setOf("a", "message"), now = 1_000)
        assertEquals(setOf("a"), t.state.value.activeCalls)
        assertNull(t.state.value.lastCallEndedAt)
        t.keepOnly(setOf("a"), now = 2_000) // nothing to drop
        assertNull(t.state.value.lastCallEndedAt)
        t.keepOnly(emptySet(), now = 3_000) // its removal was never reported
        assertFalse(t.state.value.inCall)
        assertEquals(3_000L, t.state.value.lastCallEndedAt)
    }

    @Test
    fun `grace after a call`() {
        val s = CallSnapshot(listenerConnected = true, lastCallEndedAt = 10_000)
        assertTrue(s.inCallOrGrace(now = 10_000, graceMillis = 5_000))
        assertTrue(s.inCallOrGrace(now = 14_999, graceMillis = 5_000))
        assertFalse(s.inCallOrGrace(now = 15_000, graceMillis = 5_000))
        assertFalse(s.inCallOrGrace(now = 9_000, graceMillis = 5_000)) // reboot: elapsed time went back
    }

    @Test
    fun `disconnecting during a call starts the grace period`() {
        val t = CallTracker()
        t.onConnected(setOf("call"))
        t.onDisconnected(now = 7_000)
        assertFalse(t.state.value.listenerConnected)
        assertFalse(t.state.value.inCall)
        assertEquals(7_000L, t.state.value.lastCallEndedAt)
    }
}
