package app.throwback.guard

import app.throwback.guard.ForegroundTracker.Companion.ACTIVITY_DESTROYED
import app.throwback.guard.ForegroundTracker.Companion.ACTIVITY_PAUSED
import app.throwback.guard.ForegroundTracker.Companion.ACTIVITY_RESUMED
import app.throwback.guard.ForegroundTracker.Companion.ACTIVITY_STOPPED
import app.throwback.relay.CallSnapshot
import app.throwback.relay.CallTracker
import app.throwback.relay.InstagramPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardPolicyTest {

    /** A fake elapsed-realtime clock. */
    private var now = 1_000_000L
    private val grace = 5_000L
    private val watched = InstagramPackages.ALL
    private val connected = CallSnapshot(listenerConnected = true)
    private val ig = InstagramPackages.APP
    private val feed = "com.instagram.mainactivity.InstagramMainActivity"
    private val launcher = "com.android.launcher3"

    private fun decide(
        onScreen: Boolean,
        calls: CallSnapshot = connected,
        allowUntil: Long = 0L,
        coverShownAt: Long? = null,
        noCoverUntil: Long = 0L,
        callAudioAt: Long? = null,
    ) = GuardPolicy.decide(onScreen, now, calls, grace, allowUntil, coverShownAt, noCoverUntil, callAudioAt)

    // ---- Covering ---------------------------------------------------------------------------

    @Test
    fun `covers the Instagram app (and Lite) outside a call, nothing else`() {
        val t = ForegroundTracker()
        t.onEvent(ACTIVITY_RESUMED, "app.throwback", "app.throwback.MainActivity")
        assertFalse(t.onScreen(watched))
        assertEquals(GuardAction.NONE, decide(t.onScreen(watched)))
        t.onEvent(ACTIVITY_RESUMED, InstagramPackages.LITE, "com.instagram.lite.MainActivity")
        assertEquals(GuardAction.COVER, decide(t.onScreen(watched)))
    }

    @Test
    fun `allowed during a call and shortly after, covered after the call`() {
        val t = CallTracker()
        t.onConnected(emptySet())
        t.onCallShowing("call")
        assertEquals(GuardAction.NONE, decide(true, t.state.value))
        now += 30 * 60_000 // a long call
        assertEquals(GuardAction.NONE, decide(true, t.state.value))
        t.onRemoved("call", now)
        now += 4_000
        assertEquals(GuardAction.NONE, decide(true, t.state.value))
        now += 1_000
        assertEquals(GuardAction.COVER, decide(true, t.state.value))
    }

    @Test
    fun `does nothing while the relay can't see calls`() {
        assertEquals(GuardAction.NONE, decide(true, CallSnapshot(listenerConnected = false)))
    }

    @Test
    fun `a pass lets the app through until it runs out`() {
        assertEquals(GuardAction.NONE, decide(true, allowUntil = now + 1))
        assertEquals(GuardAction.COVER, decide(true, allowUntil = now))
    }

    @Test
    fun `an answered call (call audio, no notification) is never covered`() {
        assertEquals(GuardAction.NONE, decide(true, callAudioAt = now)) // call running
        val audioAt = now
        now += 4_000
        assertEquals(GuardAction.NONE, decide(true, callAudioAt = audioAt)) // just hung up: grace
        now += 2_000
        assertEquals(GuardAction.COVER, decide(true, callAudioAt = audioAt)) // call over
        assertEquals(GuardAction.COVER, decide(true, callAudioAt = null))
    }

    // ---- The cover while it is up -------------------------------------------------------------

    @Test
    fun `the cover goes once Instagram is off the screen, not before it has shown a moment`() {
        val shownAt = now
        now += 300
        assertEquals(GuardAction.NONE, decide(false, coverShownAt = shownAt))
        now = shownAt + GuardPolicy.COVER_MIN_MS
        assertEquals(GuardAction.UNCOVER, decide(false, coverShownAt = shownAt))
    }

    @Test
    fun `the cover stays while Instagram stays on screen, and steps aside now and then`() {
        val shownAt = now
        now += 2_500 // before: a new cover (and a new trip to Throwback) every 2.5 s
        assertEquals(GuardAction.NONE, decide(true, coverShownAt = shownAt))
        now = shownAt + GuardPolicy.COVER_HOLD_MAX_MS
        assertEquals(GuardAction.STEP_ASIDE, decide(true, coverShownAt = shownAt))
        // A moment without the cover, so a split-screen or picture-in-picture window can be closed...
        val until = now + GuardPolicy.STEP_ASIDE_MS
        now += 1_000
        assertEquals(GuardAction.NONE, decide(true, noCoverUntil = until))
        // ...then it is back.
        now = until
        assertEquals(GuardAction.COVER, decide(true, noCoverUntil = until))
    }

    @Test
    fun `a call or a pass takes the cover away at once`() {
        val shownAt = now
        now += 100
        val ringing = CallSnapshot(listenerConnected = true, activeCalls = setOf("call"))
        assertEquals(GuardAction.UNCOVER, decide(true, ringing, coverShownAt = shownAt))
        assertEquals(GuardAction.UNCOVER, decide(true, allowUntil = now + 60_000, coverShownAt = shownAt))
    }

    // ---- What is on screen ----------------------------------------------------------------------

    @Test
    fun `normal switch - Instagram is off the screen once its activity stops`() {
        val t = ForegroundTracker()
        t.onEvent(ACTIVITY_RESUMED, ig, feed)
        assertEquals(ig, t.current)
        assertTrue(t.onScreen(watched))
        // Another screen of Instagram: the first one stops after the second is resumed.
        t.onEvent(ACTIVITY_PAUSED, ig, feed)
        t.onEvent(ACTIVITY_RESUMED, ig, "com.instagram.direct.DirectActivity")
        t.onEvent(ACTIVITY_STOPPED, ig, feed)
        assertTrue(t.onScreen(watched))
        // Home.
        t.onEvent(ACTIVITY_PAUSED, ig, "com.instagram.direct.DirectActivity")
        t.onEvent(ACTIVITY_RESUMED, launcher, "Launcher")
        assertEquals(launcher, t.current)
        assertTrue(t.onScreen(watched)) // paused, still visible until it stops
        t.onEvent(ACTIVITY_STOPPED, ig, "com.instagram.direct.DirectActivity")
        assertFalse(t.onScreen(watched))
    }

    @Test
    fun `split screen - Instagram next to another app stays covered`() {
        val t = ForegroundTracker()
        t.onEvent(ACTIVITY_RESUMED, ig, feed)
        t.onEvent(ACTIVITY_RESUMED, "com.whatsapp", "com.whatsapp.HomeActivity") // both stay resumed
        assertEquals("com.whatsapp", t.current) // before, only this was looked at: Instagram uncovered
        assertTrue(t.onScreen(watched))
        assertEquals(GuardAction.COVER, decide(t.onScreen(watched)))
        // Closing Instagram's half ends it.
        t.onEvent(ACTIVITY_PAUSED, ig, feed)
        t.onEvent(ACTIVITY_STOPPED, ig, feed)
        assertFalse(t.onScreen(watched))
    }

    @Test
    fun `picture-in-picture - a paused Instagram window is still on screen until closed`() {
        val t = ForegroundTracker()
        val reels = "com.instagram.clips.ClipsPipActivity"
        t.onEvent(ACTIVITY_RESUMED, ig, reels)
        t.onEvent(ACTIVITY_PAUSED, ig, reels) // Home: it shrinks into a small window and keeps playing
        t.onEvent(ACTIVITY_RESUMED, launcher, "Launcher")
        assertTrue(t.onScreen(watched))
        assertEquals(GuardAction.COVER, decide(t.onScreen(watched)))
        t.onEvent(ACTIVITY_STOPPED, ig, reels) // the window is closed
        t.onEvent(ACTIVITY_DESTROYED, ig, reels)
        assertFalse(t.onScreen(watched))
    }

    @Test
    fun `a pass that ends while Instagram is in picture-in-picture is covered`() {
        val t = ForegroundTracker()
        val reels = "com.instagram.clips.ClipsPipActivity"
        val passUntil = now + 5 * 60_000
        t.onEvent(ACTIVITY_RESUMED, ig, reels)
        assertEquals(GuardAction.NONE, decide(t.onScreen(watched), allowUntil = passUntil))
        t.onEvent(ACTIVITY_PAUSED, ig, reels)
        t.onEvent(ACTIVITY_RESUMED, launcher, "Launcher")
        // The pass bookkeeping sees the user leave Instagram (current), which ends the pass...
        assertEquals(launcher, t.current)
        // ...and the window still playing is covered.
        assertEquals(GuardAction.COVER, decide(t.onScreen(watched), allowUntil = 0L))
        now = passUntil // and in any case once the pass runs out
        assertEquals(GuardAction.COVER, decide(t.onScreen(watched), allowUntil = passUntil))
    }

    @Test
    fun `below Android 10 a pause ends it (there are no stop events)`() {
        val t = ForegroundTracker(stopEvents = false)
        t.onEvent(ACTIVITY_RESUMED, ig, feed)
        assertTrue(t.onScreen(watched))
        t.onEvent(ACTIVITY_PAUSED, ig, feed)
        assertFalse(t.onScreen(watched))
    }

    @Test
    fun `screen off clears it, and the front app is tracked as before`() {
        val t = ForegroundTracker()
        assertNull(t.current)
        t.onEvent(ACTIVITY_RESUMED, launcher)
        t.onEvent(ACTIVITY_PAUSED, launcher)
        t.onEvent(ACTIVITY_RESUMED, ig, feed)
        assertEquals(ig, t.current)
        assertEquals(feed, t.currentClass)
        // A late pause of the previous app doesn't clear the new one.
        t.onEvent(ACTIVITY_STOPPED, launcher)
        assertEquals(ig, t.current)
        t.onEvent(7, "app.other") // unrelated event types are ignored
        assertEquals(ig, t.current)
        t.onEvent(ACTIVITY_RESUMED, null)
        assertEquals(ig, t.current)
        t.clear()
        assertNull(t.current)
        assertFalse(t.onScreen(watched))
    }
}
