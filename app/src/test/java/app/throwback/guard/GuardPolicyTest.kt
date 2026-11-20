package app.throwback.guard

import app.throwback.relay.CallSnapshot
import app.throwback.relay.CallTracker
import app.throwback.relay.InstagramPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardPolicyTest {

    /** A fake elapsed-realtime clock. */
    private var now = 1_000_000L
    private val grace = 5_000L
    private val watched = InstagramPackages.ALL
    private val connected = CallSnapshot(listenerConnected = true)

    private fun decide(fg: String?, calls: CallSnapshot = connected, allowUntil: Long = 0L, lastCoverAt: Long? = null) =
        GuardPolicy.decide(fg, watched, now, calls, grace, allowUntil, lastCoverAt)

    @Test
    fun `covers the Instagram app (and Lite) outside a call`() {
        assertEquals(GuardAction.COVER, decide(InstagramPackages.APP))
        assertEquals(GuardAction.COVER, decide(InstagramPackages.LITE))
    }

    @Test
    fun `leaves every other app alone`() {
        assertEquals(GuardAction.NONE, decide("app.throwback"))
        assertEquals(GuardAction.NONE, decide("com.android.launcher3"))
        assertEquals(GuardAction.NONE, decide(null))
    }

    @Test
    fun `allowed during a call and shortly after, covered after the call`() {
        val t = CallTracker()
        t.onConnected(emptySet())
        t.onCallShowing("call")
        assertEquals(GuardAction.NONE, decide(InstagramPackages.APP, t.state.value))
        now += 30 * 60_000 // a long call
        assertEquals(GuardAction.NONE, decide(InstagramPackages.APP, t.state.value))
        t.onRemoved("call", now)
        now += 4_000
        assertEquals(GuardAction.NONE, decide(InstagramPackages.APP, t.state.value))
        now += 1_000
        assertEquals(GuardAction.COVER, decide(InstagramPackages.APP, t.state.value))
    }

    @Test
    fun `does nothing while the relay can't see calls`() {
        assertEquals(GuardAction.NONE, decide(InstagramPackages.APP, CallSnapshot(listenerConnected = false)))
    }

    @Test
    fun `Throwback's own setup pass lets the app through until it runs out`() {
        assertEquals(GuardAction.NONE, decide(InstagramPackages.APP, allowUntil = now + 1))
        assertEquals(GuardAction.COVER, decide(InstagramPackages.APP, allowUntil = now))
    }

    @Test
    fun `doesn't cover again while the previous cover is still working`() {
        val coveredAt = now
        now += GuardPolicy.COVER_COOLDOWN_MS - 1
        assertEquals(GuardAction.NONE, decide(InstagramPackages.APP, lastCoverAt = coveredAt))
        now += 1
        assertEquals(GuardAction.COVER, decide(InstagramPackages.APP, lastCoverAt = coveredAt))
    }

    @Test
    fun `foreground tracking from usage events`() {
        val t = ForegroundTracker()
        assertNull(t.current)
        t.onEvent(ForegroundTracker.ACTIVITY_RESUMED, "com.android.launcher3")
        t.onEvent(ForegroundTracker.ACTIVITY_PAUSED, "com.android.launcher3")
        t.onEvent(ForegroundTracker.ACTIVITY_RESUMED, InstagramPackages.APP)
        assertEquals(InstagramPackages.APP, t.current)
        // A late pause of the previous app doesn't clear the new one.
        t.onEvent(ForegroundTracker.ACTIVITY_STOPPED, "com.android.launcher3")
        assertEquals(InstagramPackages.APP, t.current)
        t.onEvent(7, "app.other") // unrelated event types are ignored
        assertEquals(InstagramPackages.APP, t.current)
        t.onEvent(ForegroundTracker.ACTIVITY_PAUSED, InstagramPackages.APP)
        assertNull(t.current)
        t.onEvent(ForegroundTracker.ACTIVITY_RESUMED, null)
        assertNull(t.current)
    }
}
