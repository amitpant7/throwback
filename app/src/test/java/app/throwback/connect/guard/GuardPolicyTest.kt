package app.throwback.connect.guard

import app.throwback.connect.guard.ForegroundTracker.Companion.ACTIVITY_DESTROYED
import app.throwback.connect.guard.ForegroundTracker.Companion.ACTIVITY_PAUSED
import app.throwback.connect.guard.ForegroundTracker.Companion.ACTIVITY_RESUMED
import app.throwback.connect.guard.ForegroundTracker.Companion.ACTIVITY_STOPPED
import app.throwback.connect.CallSnapshot
import app.throwback.connect.CallTracker
import app.throwback.connect.InstagramPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.throwback.connect.GuardPass

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
    fun `during a call only its screens once seen, before that or after a rename all of Instagram`() {
        val callScreen = "com.instagram.rtc.activity.RtcCallActivity"
        val pattern = PassRules.screensPattern("^com\\.instagram\\.rtc\\.")
        val rule = CallScreens()
        assertTrue("not seen yet: allowed as before", rule.allow(true, listOf(feed), pattern))
        assertTrue(rule.allow(true, listOf(callScreen), pattern))
        assertFalse("feed in front of the call", rule.allow(true, listOf(feed), pattern))
        assertFalse("feed in split screen next to the call", rule.allow(true, listOf(callScreen, feed), pattern))
        assertTrue("an unknown screen counts as the call's", rule.allow(true, listOf(callScreen, ""), pattern))
        assertTrue("no call: not this rule's business", rule.allow(false, listOf(feed), pattern))
        assertTrue("a new call starts unseen again", rule.allow(true, listOf(feed), pattern))
        assertTrue("no pattern in the rules: all allowed", CallScreens().allow(true, listOf(callScreen), null).also {
            assertTrue(CallScreens().allow(true, listOf(feed), null))
        })
    }

    @Test
    fun `a call that doesn't allow the screen covers it, but a pass still allows it`() {
        val inCall = CallSnapshot(listenerConnected = true, activeCalls = setOf("call"))
        assertEquals(GuardAction.COVER, GuardPolicy.decide(true, now, inCall, grace, callScreensOk = false))
        assertEquals(GuardAction.NONE, GuardPolicy.decide(true, now, inCall, grace, callScreensOk = true))
        assertEquals(GuardAction.NONE, GuardPolicy.decide(true, now, inCall, grace, allowUntil = now + 1_000, callScreensOk = false))
    }

    @Test
    fun `active screens leave out paused ones (picture-in-picture, the screen being left)`() {
        val call = "com.instagram.rtc.activity.RtcCallActivity"
        val t = ForegroundTracker()
        t.onEvent(ACTIVITY_RESUMED, ig, feed)
        t.onEvent(ACTIVITY_PAUSED, ig, feed) // the chat, while the call screen opens
        t.onEvent(ACTIVITY_RESUMED, ig, call)
        assertEquals(listOf(call), t.activeScreens(watched))
        t.onEvent(ACTIVITY_STOPPED, ig, feed)
        t.onEvent(ACTIVITY_PAUSED, ig, call) // the call shrinks to picture-in-picture
        t.onEvent(ACTIVITY_RESUMED, ig, feed) // and the feed comes back in front
        assertEquals(listOf(feed), t.activeScreens(watched))
        assertTrue(t.onScreen(watched))
    }


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
    fun `a notification only ongoing allows Instagram only with call audio or a call screen`() {
        val ongoing = CallSnapshot(listenerConnected = true, activeCalls = setOf("x"), maybeCalls = setOf("x"))
        assertEquals(GuardAction.COVER, GuardPolicy.decide(true, now, ongoing, grace, callSigns = false))
        assertEquals(GuardAction.NONE, GuardPolicy.decide(true, now, ongoing, grace, callSigns = true))
        val ringing = CallSnapshot(listenerConnected = true, activeCalls = setOf("x", "ring"), maybeCalls = setOf("x"))
        assertEquals(GuardAction.NONE, GuardPolicy.decide(true, now, ringing, grace, callSigns = false))
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

    // ---- Which call audio is an Instagram call ---------------------------------------------------

    private val voip = CallAudioLink.MODE_IN_COMMUNICATION
    private val phone = CallAudioLink.MODE_IN_CALL
    private val quiet = 0 // AudioManager.MODE_NORMAL

    private fun CallAudioLink.at(mode: Int, front: Boolean, igCall: Boolean = false, callScreen: Boolean = false) =
        update(mode, instagramInFront = front, instagramCallSeenRecently = igCall, callScreenShown = callScreen)

    @Test
    fun `a WhatsApp call, even during the call button's pass, doesn't unlock Instagram`() {
        val link = CallAudioLink()
        assertFalse(link.at(quiet, front = true))
        // The call button's pass is running, Instagram's chat is open; a WhatsApp call is answered and
        // its call screen comes up: the audio switches with Instagram no longer in front.
        assertFalse(link.at(voip, front = false))
        // Back to Instagram during that call: still not an Instagram call.
        assertFalse(link.at(voip, front = true))
    }

    @Test
    fun `a phone call never unlocks Instagram on its own, even answered with Instagram in front`() {
        val link = CallAudioLink()
        assertFalse(link.at(quiet, front = true))
        // Answered from the phone's pop-up during a pass: Instagram stays in front (Samsung), or the
        // audio switches before the dialer's screen is reported.
        assertFalse(link.at(phone, front = true))
        assertFalse(link.at(phone, front = true))
        assertFalse(CallAudioLink.isCallAudio(phone))
        assertTrue(CallAudioLink.isCallAudio(voip))
    }

    @Test
    fun `an Instagram call reported with phone-call audio is still linked by Instagram's own signs`() {
        // Some phones may play self-managed internet calls as MODE_IN_CALL: the call must not be covered.
        val byNotification = CallAudioLink()
        assertTrue(byNotification.at(phone, front = false, igCall = true))
        assertTrue(byNotification.at(phone, front = false)) // answered: the notification went, still linked
        val byScreen = CallAudioLink()
        assertFalse(byScreen.at(phone, front = false))
        assertTrue(byScreen.at(phone, front = true, callScreen = true))
    }

    @Test
    fun `a call started in Instagram stays an Instagram call until it ends`() {
        val link = CallAudioLink()
        link.at(quiet, front = true)
        // Call tapped in Instagram (call button pass, or during a visit or story pass).
        assertTrue(link.at(voip, front = true))
        // The user goes elsewhere while it runs (a video call shrinks into a small window): still allowed.
        assertTrue(link.at(voip, front = false))
        // Hung up.
        assertFalse(link.at(quiet, front = false))
        assertFalse(link.at(voip, front = false))
    }

    @Test
    fun `an Instagram call notification links the call audio`() {
        val link = CallAudioLink()
        link.at(voip, front = false) // another call
        assertTrue(link.at(voip, front = false, igCall = true))
        assertTrue(link.at(voip, front = false))
    }

    @Test
    fun `a call answered with the screen off is linked once its call screen shows`() {
        val link = CallAudioLink()
        // Answered on a headset: the notification went, the audio is on, Instagram isn't in front.
        assertFalse(link.at(voip, front = false))
        // Much later the user opens the call: its screen links it, also after leaving it again.
        assertTrue(link.at(voip, front = true, callScreen = true))
        assertTrue(link.at(voip, front = false))
        // A call screen without call audio links nothing.
        assertFalse(CallAudioLink().at(quiet, front = true, callScreen = true))
    }

    @Test
    fun `a call screen is shown only when one of Instagram's active screens matches`() {
        val pattern = PassRules.screensPattern("^com\\.instagram\\.rtc\\.")
        val call = "com.instagram.rtc.activity.RtcCallActivity"
        assertTrue(CallScreens.shown(listOf(feed, call), pattern))
        assertFalse(CallScreens.shown(listOf(feed, ""), pattern))
        assertFalse(CallScreens.shown(listOf(call), null))
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
    fun `leaving Instagram ends the pass without covering its screen while it stops`() {
        val t = ForegroundTracker()
        t.onEvent(ACTIVITY_RESUMED, ig, feed)
        t.onEvent(ACTIVITY_PAUSED, ig, feed) // Home during a pass
        t.onEvent(ACTIVITY_RESUMED, launcher, "Launcher")
        // The guard sees the user leave (current) and ends the pass; Instagram is paused, not stopped.
        val leftAt = now
        val noCoverUntil = leftAt + GuardPolicy.LEAVING_MS
        assertTrue(t.onScreen(watched))
        assertEquals(GuardAction.NONE, decide(t.onScreen(watched), noCoverUntil = noCoverUntil))
        t.onEvent(ACTIVITY_STOPPED, ig, feed)
        now += 500
        assertEquals(GuardAction.NONE, decide(t.onScreen(watched), noCoverUntil = noCoverUntil))
        // Had it stayed on screen (picture-in-picture), it is covered after that moment.
        now = noCoverUntil
        assertEquals(GuardAction.COVER, decide(true, noCoverUntil = noCoverUntil))
    }

    @Test
    fun `a picture-in-picture window that comes back paused after the screen was off is on screen`() {
        val t = ForegroundTracker()
        val reels = "com.instagram.clips.ClipsPipActivity"
        t.onEvent(ACTIVITY_RESUMED, ig, reels)
        t.onEvent(ACTIVITY_PAUSED, ig, reels)
        t.onEvent(ACTIVITY_RESUMED, launcher, "Launcher")
        t.onEvent(ACTIVITY_STOPPED, ig, reels) // screen off: everything stops
        t.clear() // and the guard starts over from the events since
        t.onEvent(ACTIVITY_RESUMED, launcher, "Launcher") // unlocked
        t.onEvent(ACTIVITY_PAUSED, ig, reels) // the small window is back: paused, never resumed
        assertTrue(t.onScreen(watched))
        assertEquals(launcher, t.current)
    }

    @Test
    fun `a screen opened from the same screen stays on screen when the first one stops`() {
        val t = ForegroundTracker()
        val modal = "com.instagram.modal.ModalActivity"
        t.onEvent(ACTIVITY_RESUMED, ig, modal) // a profile
        t.onEvent(ACTIVITY_PAUSED, ig, modal)
        t.onEvent(ACTIVITY_RESUMED, ig, modal) // another profile, a second instance of the same screen
        t.onEvent(ACTIVITY_STOPPED, ig, modal) // the first one stops
        assertTrue(t.onScreen(watched))
        assertEquals(ig, t.current)
        // Back: the second one goes, the first one is resumed again.
        t.onEvent(ACTIVITY_PAUSED, ig, modal)
        t.onEvent(ACTIVITY_RESUMED, ig, modal)
        t.onEvent(ACTIVITY_STOPPED, ig, modal)
        t.onEvent(ACTIVITY_DESTROYED, ig, modal)
        assertTrue(t.onScreen(watched))
        assertEquals(ig, t.current)
        // Home.
        t.onEvent(ACTIVITY_PAUSED, ig, modal)
        t.onEvent(ACTIVITY_RESUMED, launcher, "Launcher")
        t.onEvent(ACTIVITY_STOPPED, ig, modal)
        assertFalse(t.onScreen(watched))
    }

    @Test
    fun `a pass ends, and is forgiven, only once`() {
        GuardPass.grant(now, 60_000, GuardPass.Kind.STORY)
        assertTrue(GuardPass.endIfActive(now + 1)) // e.g. the guard: left early
        assertFalse(GuardPass.endIfActive(now + 2)) // the relay's "posted" a moment later: not again
        GuardPass.grant(now, 60_000, GuardPass.Kind.STORY)
        assertFalse(GuardPass.endIfActive(now + 60_000)) // ran out by itself
    }

    @Test
    fun `replaying the last seconds' events again changes nothing (the queries overlap)`() {
        val t = ForegroundTracker()
        val modal = "com.instagram.modal.ModalActivity"
        val events = listOf(
            Triple(ACTIVITY_RESUMED, ig, feed),
            Triple(ACTIVITY_RESUMED, "com.whatsapp", "com.whatsapp.HomeActivity"), // split screen
            Triple(ACTIVITY_PAUSED, ig, feed),
            Triple(ACTIVITY_RESUMED, ig, modal),
            Triple(ACTIVITY_STOPPED, ig, feed),
        )
        events.forEach { (type, pkg, cls) -> t.onEvent(type, pkg, cls) }
        val before = Triple(t.current, t.activeScreens(watched), t.onScreen(watched))
        for (from in events.indices) {
            events.drop(from).forEach { (type, pkg, cls) -> t.onEvent(type, pkg, cls) }
            assertEquals(before, Triple(t.current, t.activeScreens(watched), t.onScreen(watched)))
        }
    }

    @Test
    fun `a create pass's grace starts when Instagram comes to the front, not at the tap`() {
        val screens = PassRules.screensPattern("^instagram\\.features\\.creation\\.")
        GuardPass.grant(now, 60_000, GuardPass.Kind.CREATE)
        assertEquals(0L, GuardPass.inFrontFor(now + 5_000)) // Instagram still starting
        GuardPass.inFront(now + 5_000) // its main screen first, then the editor
        GuardPass.inFront(now + 6_000) // later checks don't move it
        assertTrue(PassRules.createPassHolds(feed, screens, GuardPass.inFrontFor(now + 7_000)))
        assertFalse(PassRules.createPassHolds(feed, screens, GuardPass.inFrontFor(now + 8_000)))
        GuardPass.grant(now + 9_000, 60_000, GuardPass.Kind.CREATE) // a new pass starts over
        assertEquals(null, GuardPass.firstInFrontAt)
        GuardPass.end()
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

    @Test
    fun `a camera counts as Instagram's only when it opened while Instagram was in front`() {
        val use = CameraUse()
        assertFalse(use.instagramCamera())
        use.opened("0", instagramInFront = false) // a camera app in split screen, opened before switching
        assertFalse(use.instagramCamera())
        use.opened("1", instagramInFront = true) // Instagram's story camera
        assertTrue(use.instagramCamera())
        use.closed("1")
        assertFalse(use.instagramCamera())
        use.opened("0", instagramInFront = true) // the other app released it, then Instagram took it
        assertTrue(use.instagramCamera())
        use.closed("0")
        assertFalse(use.instagramCamera())
    }
}
